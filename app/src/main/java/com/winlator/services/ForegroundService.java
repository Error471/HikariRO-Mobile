package com.winlator.services;

import android.app.KeyguardManager;
import android.app.Service;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class ForegroundService extends Service {

    private static volatile ForegroundService instance;
    private static final String TAG = "FGService";

    private static final String ACTION_ENSURE_FOREGROUND = "com.winlator.action.ENSURE_FOREGROUND";

    private static final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private static final AtomicBoolean serviceRunning = new AtomicBoolean(false);
    private static volatile boolean serviceStopping = false;
    private static volatile boolean isSessionInBackground = false;

    // ── App visibility state ──────────────────────────────────────────────
    // isAppInBackground: true when no Activity is STARTED (ProcessLifecycleOwner).
    // isScreenLocked: true after ACTION_SCREEN_OFF, cleared by ACTION_USER_PRESENT.
    private static volatile boolean isAppInBackground = false;
    private static volatile boolean isScreenLocked = false;
    private BroadcastReceiver screenStateReceiver;
    private static final AtomicBoolean appInPipMode = new AtomicBoolean(false);

    private PowerManager.WakeLock wakeLock;
    private HandlerThread screenReceiverThread;
    private Handler screenReceiverHandler;

    private static volatile SharedPreferences prefs;
    private static final String PREF_USE_WAKELOCK = "enable_background_wakelock";

    private NotificationUtils notificationUtils;
    private int notificationId = -1;
    private static final String NOTIFICATION_ID_NAME = "winlator.FGS";

    // ── Container / game session lifecycle ──────────────────────────────────────────────
    public static void startSession(Context ctx) {
        if (ctx == null) return;
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());

        // HikariRO Movil no muestra la pantalla de ajustes de Winlator (la
        // misma razon por la que en GuestProgramLauncherComponent se fuerza
        // el nivel de logs de box64 en vez de leerlo de una preferencia). En
        // el Winlator original esta proteccion en segundo plano (foreground
        // service real + wakelock) viene DESACTIVADA por defecto, porque alli
        // el usuario la activa a mano si la necesita. Aqui no existe esa
        // pantalla, asi que "enable_background_protection" se quedaba
        // siempre en false y este metodo devolvia sin hacer nada: el
        // ForegroundService NUNCA llegaba a arrancar mientras jugabamos.
        //
        // Esto importa para el cuelgue/reinicio en bucle de "Starting up":
        // raghikari.exe se lanza con un ProcessBuilder normal y corriente
        // (ver ProcessHelper.exec()), sin ningun oom_score_adj ni cgroup
        // especial asignado - Android/Nothing OS no tiene forma directa de
        // saber que ese proceso nativo (hijo, no registrado en
        // ActivityManager) pertenece a una app que deberia tener prioridad.
        // Un Foreground Service real, con tipo mediaPlayback declarado, es
        // la unica señal adicional de "no mates esto" que podemos dar desde
        // el lado Java, asi que la forzamos siempre activa.
        if (prefs == null) {
            Log.w(TAG, "startSession called with null SharedPreferences; aborting protection");
            return;
        }

        if (!com.winlator.hikariro.HikariConfig.BACKGROUND_KEEP_ALIVE) {
            Log.d(TAG, "startSession: servicio en primer plano desactivado (sin notificacion)");
            return;
        }

        sessionActive.set(true);
        isSessionInBackground = false;
        registerProcessObserver(ctx.getApplicationContext());
        Log.d(TAG, "startSession: aviso solo mientras la app esta en segundo plano");
        updateForegroundState(ctx);
    }

    // La notificacion solo aparece mientras ninguna pantalla de la app esta visible
    private static boolean processObserverRegistered = false;

    private static void registerProcessObserver(final Context appContext) {
        if (processObserverRegistered) return;
        processObserverRegistered = true;
        androidx.lifecycle.Lifecycle lifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().getLifecycle();
        // Se llama desde onCreate de la pantalla del juego: la app esta en primer plano
        isAppInBackground = false;
        lifecycle.addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                isAppInBackground = false;
                updateForegroundState(appContext);
            }

            @Override
            public void onStop(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                isAppInBackground = true;
                updateForegroundState(appContext);
            }
        });
    }

    public static void onPauseSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.get()) {
            Log.i(TAG, "onPauseSession called with no active session; ignoring");
            return;
        }
        isSessionInBackground = true;
        Log.d(TAG, "onPauseSession");
        updateForegroundState(ctx);
    }

    public static void onResumeSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.get()) {
            Log.i(TAG, "onResumeSession called with no active session; ignoring");
            return;
        }
        isSessionInBackground = false;
        Log.d(TAG, "onResumeSession");
        if (instance != null) {
            instance.releaseWakeLock();
        }
        updateForegroundState(ctx);
    }

    public static void stopSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.compareAndSet(true, false)) return;
        isSessionInBackground = false;
        if (instance != null) {
            instance.releaseWakeLock();
        }
        updateForegroundState(ctx);
        Log.d(TAG, "Stopping session in keep-alive services. Request by: " +
                Objects.requireNonNull(ctx.getClass().getName()));
    }

    // ── Service validation logic ──────────────────────────────────────────────
    public static boolean isSessionActive() {
        return sessionActive.get();
    }

    public static void setPipMode(boolean inPip) {
        appInPipMode.set(inPip);
        if (instance != null) {
            instance.ensureForeground();
            Log.i(TAG, "setPipMode: " + inPip);
        }
    }

    private static boolean isInPictureInPictureMode() {
        return appInPipMode.get();
    }

    public static boolean isAppInBackground()  {
        return isAppInBackground;
    }
    public static boolean isDeviceLocked()     {
        return isScreenLocked;
    }

    public static boolean isAppNotVisible() {
        return isAppInBackground || isScreenLocked;
    }

    private final androidx.lifecycle.DefaultLifecycleObserver appLifecycleObserver =
            new androidx.lifecycle.DefaultLifecycleObserver() {
                @Override
                public void onStart(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                    isAppInBackground = false;
                    Log.d(TAG, "App came to foreground (ProcessLifecycleOwner)");
                    updateForegroundState(ForegroundService.this);
                }

                @Override
                public void onStop(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                    isAppInBackground = true;
                    Log.d(TAG, "App went to background (ProcessLifecycleOwner)");
                    updateForegroundState(ForegroundService.this);
                }
            };

    private static boolean hasReason() {
        return sessionActive.get() && isAppInBackground;
    }

    private static synchronized void updateForegroundState(Context ctx) {
        ForegroundService svc = instance;

        if (hasReason()) {
            if (svc != null && !serviceStopping) {
                svc.ensureForeground();
            } else {
                Context app = ctx.getApplicationContext();
                Intent intent = new Intent(app, ForegroundService.class);
                intent.setAction(ACTION_ENSURE_FOREGROUND);
                try {
                    androidx.core.content.ContextCompat.startForegroundService(app, intent);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start foreground services", e);
                }
            }
        } else if (svc != null && serviceRunning.get()) {
            // Si aun no ha llamado a startForeground, onStartCommand lo parara despues
            Log.d(TAG, "No active reason remains; stopping foreground services");
            serviceStopping = true;
            serviceRunning.set(false);
            svc.stopForegroundCompat();
            svc.stopSelf();
        }
    }

    // ── Foreground class logic ──────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();

        notificationUtils = new NotificationUtils(getApplicationContext());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Winlator:ForegroundService"
            );
            wakeLock.setReferenceCounted(false);
        }

        instance = this;

        // El estado de primer/segundo plano lo lleva el observador de startSession
        // Dispatch onReceive() on a dedicated background Looper and serializes
        // SCREEN_OFF / USER_PRESENT / SCREEN_ON handling in arrival order.
        screenReceiverThread = new HandlerThread("ForegroundService-ScreenReceiver");
        screenReceiverThread.start();
        screenReceiverHandler = new Handler(screenReceiverThread.getLooper());

        // Screen-lock detection. ACTION_SCREEN_OFF/USER_PRESENT are protected
        // broadcasts — dynamic registration only, no manifest entry needed.
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    isScreenLocked = true;
                    Log.d(TAG, "Screen turned off / device locked");
                    acquireWakeLock();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    isScreenLocked = false;
                    Log.d(TAG, "Device unlocked (user present)");
                    releaseWakeLock();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // Screen on but keyguard may still be showing.
                    KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    isScreenLocked = km != null && km.isKeyguardLocked();
                }
                updateForegroundState(context);
            }
        };

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, screenFilter, null, screenReceiverHandler);

        // Si el servicio arranca con la pantalla ya apagada, mantener la CPU despierta desde ya
        if (pm != null && !pm.isInteractive()) {
            isScreenLocked = true;
            acquireWakeLock();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        serviceStopping = false;
        ensureForeground();
        serviceRunning.set(true);

        if (!hasReason()) {
            Log.d(TAG, "onStartCommand found no active reason; stopping immediately");
            serviceStopping = true;
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);
        }
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        boolean containerActive = sessionActive.get();

        // Determine target activity: Game screen if active, else Main menu
        Class<?> targetActivity = XServerDisplayActivity.class;

        Notification n = notificationUtils.createForegroundNotification(
                getNotificationContent(),
                "HikariRO",
                XServerDisplayActivity.class, // Service class for the 'Exit' action
                null, // Exit action here, not used because might cause issues
                targetActivity // Activity class for the 'Open' (notification tap) action
        );

        // Generate a custom notification ID if it wasn't set
        if (notificationId == -1) {
            notificationId = NotificationUtils.generateNotificationId(this, NOTIFICATION_ID_NAME);
        }

        try {
            // Only call startForeground the first time. Use notify() for updates.
            if (!serviceRunning.get()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                }
                else {
                    startForeground(notificationId, n);
                }
            }
            else {
                notificationUtils.notify(notificationId, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to startForeground in ensureForeground()", e);
            if (!serviceRunning.get()) {
                serviceStopping = true;
                stopSelf();
            }
        }
    }

    // Juego congelado en segundo plano: no hace falta mantener la CPU despierta
    private static volatile boolean gameSuspended = false;

    public static void setGameSuspended(boolean suspended) {
        gameSuspended = suspended;
        ForegroundService svc = instance;
        if (svc != null) {
            if (suspended) svc.releaseWakeLock();
            else if (isScreenLocked) svc.acquireWakeLock();
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            Log.w(TAG, "Failed to stopForeground in stopForegroundCompat()", e);
        }
    }

    // ── Cleaning methods ──────────────────────────────────────────────
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed by user. Tearing down session and exiting process.");

        sessionActive.set(false);
        isSessionInBackground = false;

        new Thread(() -> new Handler(Looper.getMainLooper()).post(() -> {
            serviceRunning.set(false);
            if (instance != null) {
                instance.stopForegroundCompat();
                instance.stopSelf();
            }
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> android.os.Process.killProcess(android.os.Process.myPid()), 500L);
        }), "ForegroundServiceCleanup").start();
    }

    @Override
    public void onDestroy() {
        instance = null;
        serviceStopping = false;
        serviceRunning.set(false);
        isSessionInBackground = false;

        releaseWakeLock();

        if (screenStateReceiver != null) {
            try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
            screenStateReceiver = null;
        }

        if (notificationUtils != null) {
            notificationUtils.cancel(notificationId);
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Utility methods ──────────────────────────────────────────────
    @NonNull
    private static String getNotificationContent() {
        ForegroundService svc = instance;
        if (svc == null) return "HikariRO sigue abierto";

        if (sessionActive.get()) {
            return isSessionInBackground && (isDeviceLocked() || !isInPictureInPictureMode())
                    ? svc.getString(R.string.fgs_notification_content_container_background)
                    : svc.getString(R.string.fgs_notification_content_container_foreground);
        }
        return svc.getString(R.string.fgs_notification_content_else);
    }

    private void acquireWakeLock() {
        if (wakeLock == null || gameSuspended) return;

        // Mismo motivo que en startSession(): sin pantalla de ajustes en
        // este fork, "enable_background_wakelock" nunca pasaba de su
        // default (false), asi que el wakelock jamas se pedia al bloquear
        // la pantalla. Se fuerza activo siempre por la misma razon.
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.d(TAG, "WakeLock acquired");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
    }
}
