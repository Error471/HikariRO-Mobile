package com.winlator.hikariro;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.GPUHelper;

import java.util.Locale;

// Compatibilidad entre moviles: elige el modo grafico segun la GPU y, si el juego
// no llega a verse dos veces seguidas, baja solo al modo de maxima compatibilidad.
public abstract class HikariCompat {
    // Nivel 0: Vulkan + DXVK (rapido). Nivel 1: OpenGL (VirGL) + WineD3D (compatible).
    public static final int LEVEL_VULKAN = 0;
    public static final int LEVEL_OPENGL = 1;
    public static final int MAX_LEVEL = LEVEL_OPENGL;
    private static final int FAILURES_BEFORE_FALLBACK = 2;

    private static final String PREF_LEVEL = "hikari_compat_level";
    private static final String PREF_PENDING = "hikari_compat_pending";
    private static final String PREF_FAILURES = "hikari_compat_failures";
    private static final String PREF_CONFIRMED = "hikari_compat_confirmed";
    private static final String PREF_DETECTED = "hikari_compat_detected";
    private static final String PREF_DETECTED_LEVEL = "hikari_compat_detected_level";
    private static final String PREF_REVISION = "hikari_compat_revision";

    public enum StartResult { NORMAL, SUGGEST_FALLBACK, GAVE_UP }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    // Deteccion inicial (una sola vez): sin Vulkan 1.1 se empieza en OpenGL
    private static int detectInitialLevel(Context context) {
        boolean vulkanOk = false;
        try {
            int vkVersion = GPUHelper.vkGetApiVersion();
            vulkanOk = getAdrenoModel(context) > 0 || vkVersion >= GPUHelper.vkMakeVersion(1, 1, 0);
        }
        catch (Throwable e) {
            Log.w(HikariConfig.LOG_TAG, "No se pudo consultar Vulkan", e);
        }
        return vulkanOk ? LEVEL_VULKAN : LEVEL_OPENGL;
    }

    public static int getLevel(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(PREF_DETECTED, false)) {
            int level = detectInitialLevel(context);
            p.edit().putBoolean(PREF_DETECTED, true).putInt(PREF_LEVEL, level).putInt(PREF_DETECTED_LEVEL, level).commit();
            Log.i(HikariConfig.LOG_TAG, "Compatibilidad: nivel inicial "+level+" ("+describeDevice(context)+")");
        }
        // Rev. 2: la bajada ya no es automatica; se deshace la de versiones anteriores
        if (p.getInt(PREF_REVISION, 0) < 2) {
            SharedPreferences.Editor editor = p.edit().putInt(PREF_REVISION, 2);
            if (p.getInt(PREF_LEVEL, LEVEL_VULKAN) == LEVEL_OPENGL && p.getInt(PREF_DETECTED_LEVEL, LEVEL_VULKAN) == LEVEL_VULKAN) {
                editor.putInt(PREF_LEVEL, LEVEL_VULKAN).putInt(PREF_FAILURES, 0);
                Log.i(HikariConfig.LOG_TAG, "Compatibilidad: se vuelve al modo grafico normal");
            }
            editor.commit();
        }
        return Math.max(0, Math.min(MAX_LEVEL, p.getInt(PREF_LEVEL, LEVEL_VULKAN)));
    }

    public static String getGraphicsDriver(Context context) {
        String vulkan = GraphicsDrivers.parseIdentifiers(GraphicsDrivers.getDefaultDriver(context))[0];
        return vulkan+","+HikariConfig.OPENGL_DRIVER;
    }

    public static String getDXWrapper(Context context) {
        return getLevel(context) == LEVEL_OPENGL ? DXWrappers.WINED3D : HikariConfig.DX_WRAPPER;
    }

    // Al abrir la app: si el arranque anterior no llego a mostrar el juego, cuenta un fallo
    public static StartResult onAppStart(Context context) {
        SharedPreferences p = prefs(context);
        int level = getLevel(context);
        if (!p.getBoolean(PREF_PENDING, false)) return StartResult.NORMAL;

        int failures = p.getInt(PREF_FAILURES, 0) + 1;
        SharedPreferences.Editor editor = p.edit().putBoolean(PREF_PENDING, false);
        StartResult result = StartResult.NORMAL;
        if (failures >= FAILURES_BEFORE_FALLBACK) {
            failures = 0;
            // Se pregunta al jugador antes de bajar (cerrar la app durante la carga tambien cuenta)
            if (level < MAX_LEVEL) result = StartResult.SUGGEST_FALLBACK;
            else result = StartResult.GAVE_UP;
        }
        editor.putInt(PREF_FAILURES, failures).commit();
        Log.i(HikariConfig.LOG_TAG, "Compatibilidad: arranque anterior sin juego visible, fallos="+failures+" nivel="+level+" resultado="+result);
        return result;
    }

    // raghikari.exe ha arrancado: si la app muere antes de verse el juego, cuenta como fallo
    public static void markGameStarting(Context context, java.util.function.BooleanSupplier alreadyVisible) {
        if (alreadyVisible.getAsBoolean()) return;
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(PREF_PENDING, false)) p.edit().putBoolean(PREF_PENDING, true).apply();
    }

    public static void markGameVisible(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(PREF_PENDING, false) && p.getInt(PREF_FAILURES, 0) == 0 && p.getBoolean(PREF_CONFIRMED, false)) return;
        p.edit().putBoolean(PREF_PENDING, false).putInt(PREF_FAILURES, 0).putBoolean(PREF_CONFIRMED, true).apply();
    }

    // Salida voluntaria antes de que se vea el juego (no es un fallo)
    public static void clearPending(Context context) {
        prefs(context).edit().putBoolean(PREF_PENDING, false).commit();
    }

    // Cambio manual desde el menu de ajustes
    public static void setLevel(Context context, int level) {
        prefs(context).edit().putBoolean(PREF_DETECTED, true).putInt(PREF_LEVEL, Math.max(0, Math.min(MAX_LEVEL, level)))
            .putInt(PREF_FAILURES, 0).putBoolean(PREF_PENDING, false).commit();
    }

    public static void resetToAutomatic(Context context) {
        prefs(context).edit().remove(PREF_DETECTED).remove(PREF_LEVEL).remove(PREF_PENDING)
            .remove(PREF_FAILURES).remove(PREF_CONFIRMED).commit();
    }

    public static boolean isConfirmed(Context context) {
        return prefs(context).getBoolean(PREF_CONFIRMED, false);
    }

    public static short getAdrenoModel(Context context) {
        try {
            return GPUHelper.getAdrenoModelId(context);
        }
        catch (Throwable e) {
            return 0;
        }
    }

    public static long getTotalRamMB(Context context) {
        ActivityManager am = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(info);
        return info.totalMem / (1024 * 1024);
    }

    public static String getGpuName(Context context) {
        try {
            String renderer = GPUHelper.glGetRenderer(context);
            return renderer != null && !renderer.isEmpty() ? renderer : "desconocida";
        }
        catch (Throwable e) {
            return "desconocida";
        }
    }

    public static String getVulkanVersion() {
        try {
            int v = GPUHelper.vkGetApiVersion();
            if (v == 0) return "no";
            return GPUHelper.vkVersionMajor(v)+"."+GPUHelper.vkVersionMinor(v);
        }
        catch (Throwable e) {
            return "no";
        }
    }

    public static String describeDevice(Context context) {
        return String.format(Locale.ROOT, "%s %s, Android %s, GPU %s, Vulkan %s, RAM %d MB",
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, getGpuName(context), getVulkanVersion(), getTotalRamMB(context));
    }

    // Aviso previo (null si todo parece correcto). El total de RAM que ve Android
    // es algo menor que el anunciado, de ahi los margenes.
    public static String getCompatibilityWarning(Context context) {
        long ram = getTotalRamMB(context);
        StringBuilder sb = new StringBuilder();
        if (ram > 0 && ram < 2800) {
            sb.append("Tu movil tiene poca memoria RAM (").append(Math.round(ram / 1024.0f * 10) / 10.0f)
              .append(" GB). HikariRO necesita al menos 4 GB y es probable que no funcione o se cierre.\n\n");
        }
        else if (ram > 0 && ram < 3700) {
            sb.append("Tu movil tiene ").append(Math.round(ram / 1024.0f * 10) / 10.0f)
              .append(" GB de RAM. El juego puede ir lento o cerrarse si tienes otras apps abiertas.\n\n");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sb.append("Tu version de Android (").append(Build.VERSION.RELEASE)
              .append(") es antigua y puede dar problemas.\n\n");
        }
        if (getLevel(context) == LEVEL_OPENGL && prefs(context).getInt(PREF_DETECTED_LEVEL, LEVEL_VULKAN) == LEVEL_OPENGL) {
            sb.append("Tu GPU no admite Vulkan 1.1: se usara el modo de compatibilidad, mas lento.\n\n");
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }
}
