package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppDefaults;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.hikariro.HardcodedPathPatcher;
import com.winlator.hikariro.HikariCompat;
import com.winlator.hikariro.HikariConfig;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.services.ForegroundService;
import com.winlator.widget.FrameRating;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    // Sube este numero cada vez que patchGraphicsDriverHardcodedPath cambie
    // (o se añada un parche nuevo de este tipo). Un telefono que ya haya
    // extraido gladio/vortek con una build anterior de la app tiene
    // guardado en preferencias el mismo "current_graphics_driver" de
    // siempre (driver+version no cambian), asi que sin esto
    // extractGraphicsDriverFiles nunca detectaria que hace falta volver a
    // extraer esos binarios para aplicarles el parche -aunque el usuario
    // reinstale la app con el codigo ya corregido, seguiria usando el
    // libGL.so.1.7.0 / libvulkan_vortek.so viejo y sin parchear-.
    private static final int GRAPHICS_DRIVER_PATCH_VERSION = 1;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    public int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        ForegroundService.startSession(this);

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));

        // Modo diagnostico: salida de wine/box64 a Logcat
        if (HikariConfig.DIAGNOSTIC_MODE) ProcessHelper.addDebugCallback(line -> Log.i(HikariConfig.LOG_TAG, line));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            String dxwrapper = container.getDXWrapper();
            wincomponents = container.getWinComponents();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = new ScreenInfo(container.getScreenSize());

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = new ScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (intent.hasExtra("exec_path")) win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(intent.getStringExtra("exec_path")));
            }

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
            if (isHikariContainer() && HikariConfig.MAX_FPS > 0) this.dxwrapperConfig[0].put("framerate", HikariConfig.MAX_FPS);
        }

        if (!isHikariContainer()) preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        final boolean[] flags = {false, shortcut != null || getIntent().hasExtra("exec_path")};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            if (isHikariContainer()) {
                ensureWineGecko();
                installOpenUrlHelper();
            }
            setupXEnvironment();
        });

    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == HIKARI_EDIT_CONTROLS_REQUEST_CODE) {
            hikariReloadControls(false);
            return;
        }
        if (requestCode == AppDefaults.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            if (capturePointerOnExternalMouse) touchpadView.requestPointerCapture();

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (environment != null) {
            xServerView.onResume();
            if (isHikariContainer()) hikariReturnFromBackground();
            else environment.onResume();
        }
        ForegroundService.onResumeSession(this);
    }

    @Override
    public void onPause() {
        ForegroundService.onPauseSession(this);
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            if (isHikariContainer()) hikariGoToBackground();
            else environment.onPause();
            xServerView.onPause();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        ForegroundService.setPipMode(isInPictureInPictureMode);
    }

    @Override
    protected void onDestroy() {
        hikariHandler.removeCallbacks(hikariSuspendRunnable);
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        ForegroundService.stopSession(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isHikariContainer()) {
            showHikariMenu();
            return;
        }
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_active_windows:
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    private void exit() {
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        // HikariRO: al cerrar el juego se sale de la app. Reiniciarla volvia a
        // abrir HikariLauncherActivity, que relanza el juego automaticamente.
        if (isHikariContainer()) {
            ForegroundService.stopSession(this);
            finishAndRemoveTask();
            Runtime.getRuntime().exit(0);
            return;
        }

        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.containerId = container.id;
            options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
            AppUtils.restartApplication(this, options);
        }
        else AppUtils.restartApplication(this);
        ForegroundService.stopSession(this);
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        if (extractDXWrapperFiles()) containerDataChanged = true;

        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container, true);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        if (isHikariContainer()) WineUtils.enableRpcSsOnDemand(container);

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        // Gladio (driver OpenGL) y Vortek (driver Vulkan) vienen compilados
        // -fuera de este proyecto, como binarios ya listos dentro de
        // graphics_driver/*.tzst- con la ruta de su socket grabada de forma
        // fija asumiendo el paquete original "com.winlator" (ver el parcheo
        // de esos binarios en extractGraphicsDriverFiles). Esa ruta grabada
        // no puede alargarse lo suficiente sin reestructurar el binario
        // entero (igual que le paso a wineserver): "rootPath" incluye
        // "/files/rootfs" y el resultado con nuestro paquete mas largo no
        // cabe en el hueco original. En vez de mover el socket real (que
        // arriesgaria romper la conexion de wine, que si usa "rootPath" y
        // parece funcionar bien), creamos ADEMAS un enlace simbolico mas
        // corto que apunte al mismo socket real: gladio/vortek se conectan
        // por ese enlace corto (que si cabe en su ruta grabada) y llegan
        // exactamente al mismo socket. Ver createShortSocketSymlink, mas
        // abajo.
        String shortRootPath = "/data/data/"+getPackageName();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS);
        // Wine en silencio salvo en modo diagnostico
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : (HikariConfig.DIAGNOSTIC_MODE ? HikariConfig.DIAGNOSTIC_WINEDEBUG : "-all"));

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        if (container != null) {
            if (container.getHUDMode() == FrameRating.Mode.FULL.ordinal()) envVars.put("X11_WND_GPU_INFO", "1");

            String desktopName = shortcut != null || getIntent().hasExtra("exec_path") ? "nogui" : "shell";

            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+
                " "+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));

            // ESYNC necesita shm_open, que falla en varios moviles: siempre desactivado
            envVars.put("WINEESYNC", "0");

            guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());
        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        createShortSocketSymlink(rootPath, shortRootPath, UnixSocketConfig.XSERVER_PATH);
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
            createShortSocketSymlink(rootPath, shortRootPath, UnixSocketConfig.VORTEK_SERVER_PATH);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        environment.startEnvironmentComponents();

        winHandler.start();
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
        wincomponents = null;
    }

    // Crea, ademas del socket real en "rootPath+relativePath", un enlace
    // simbolico al mismo socket en "shortRootPath+relativePath". Hace falta
    // porque los binarios de gladio y vortek (ver extractGraphicsDriverFiles
    // mas abajo) traen grabada de fabrica una ruta de conexion fija que no
    // cabe alargada con nuestro applicationId salvo que se le quite el
    // "/files/rootfs" de en medio -shortRootPath es justo esa version mas
    // corta-. No movemos el socket real a shortRootPath directamente porque
    // wine SI se conecta bien a el en "rootPath" (usa la convencion habitual
    // de X11 y no lleva ninguna ruta grabada de por medio), y no queremos
    // arriesgarnos a romper esa conexion que ya funciona.
    //
    // Un symlink hacia un socket unix funciona igual que hacia cualquier
    // otro archivo: connect() sigue el enlace hasta encontrar el socket real
    // igual que open() seguiria el enlace hasta el archivo real. No hace
    // falta que el socket real ya exista en este momento (aun no esta
    // "escuchando", eso pasa mas tarde en environment.startEnvironmentComponents()):
    // el enlace solo es una referencia a la ruta, se resuelve cuando gladio
    // o vortek intenten conectar, momento en el que wine ya lleva rato
    // arrancado y el socket real ya existe.
    // Los .json de los drivers Vulkan (icd.d) traen "library_path" con el
    // paquete original "com.winlator": el cargador de Vulkan no encontraba el
    // driver ("Failed to find any vulkan GPU") y DXVK no podria arrancar. Es
    // texto JSON, asi que se puede escribir la ruta real completa.
    private void fixVulkanIcdPaths(File rootDir) {
        File[] icdFiles = new File(rootDir, "/usr/share/vulkan/icd.d").listFiles();
        if (icdFiles == null) return;
        String oldPrefix = "/data/data/com.winlator/files/rootfs";
        StringBuilder icdList = new StringBuilder();
        for (File icdFile : icdFiles) {
            if (!icdFile.getName().endsWith(".json")) continue;
            String content = FileUtils.readString(icdFile);
            if (content != null && content.contains(oldPrefix)) {
                FileUtils.writeString(icdFile, content.replace(oldPrefix, rootDir.getPath()));
                Log.i(HikariConfig.LOG_TAG, "Ruta de driver Vulkan corregida en "+icdFile);
            }
            if (icdList.length() > 0) icdList.append(":");
            icdList.append(icdFile.getPath());
        }

        // libvulkan.so (el cargador) busca icd.d en la ruta del paquete original
        // por defecto, asi que le decimos explicitamente donde estan los drivers.
        if (icdList.length() > 0) {
            envVars.put("VK_ICD_FILENAMES", icdList.toString());
            envVars.put("VK_DRIVER_FILES", icdList.toString());
        }
    }

    // Al salir con "Volver a Windows", raghikari.exe lanza una excepcion C++
    // (0x80000100 en MSVCP140) durante su propio cierre y se queda parado en
    // la ventana "Gravity(tm) Error Handler" sin terminar nunca. La sesion ya
    // esta cerrada en ese punto, asi que simplemente cerramos la app.
    private void watchGravityErrorHandler() {
        xServer.windowManager.addOnWindowModificationListener(new com.winlator.xserver.WindowManager.OnWindowModificationListener() {
            private boolean handled = false;
            private volatile int mappedGameWindows = 0;
            private boolean gameWasShown = false;

            private void closeApp(String reason, long delay) {
                if (handled) return;
                handled = true;
                Log.i(HikariConfig.LOG_TAG, reason+": cerrando la app");
                runOnUiThread(() -> xServerView.postDelayed(XServerDisplayActivity.this::exit, delay));
            }

            private boolean isMainGameWindow(com.winlator.xserver.Window window) {
                return window.getClassName().toLowerCase().contains("raghikari") && window.getWidth() >= 800;
            }

            private void check(com.winlator.xserver.Window window) {
                if (!handled && window.getName().toLowerCase().contains("error handler")) closeApp("Ventana de error de Gravity detectada", 500);
            }

            @Override
            public void onMapWindow(com.winlator.xserver.Window window) {
                if (isMainGameWindow(window)) {
                    mappedGameWindows++;
                    gameWasShown = true;
                }
                check(window);
            }

            // Plan B: si la ventana principal del juego desaparece y no vuelve
            // en 3 s, el juego se esta cerrando (aunque el titulo no se lea).
            @Override
            public void onUnmapWindow(com.winlator.xserver.Window window) {
                if (!isMainGameWindow(window)) return;
                mappedGameWindows = Math.max(0, mappedGameWindows - 1);
                if (gameWasShown && mappedGameWindows == 0) {
                    runOnUiThread(() -> xServerView.postDelayed(() -> {
                        if (mappedGameWindows == 0) closeApp("La ventana principal del juego se ha cerrado", 0);
                    }, 3000));
                }
            }

            @Override
            public void onUpdateWindowContent(com.winlator.xserver.Window window) {
                check(window);
            }
        });
    }

    // Wine Gecko (motor HTML que sustituye a Internet Explorer). Lo necesita
    // el patcher para las noticias (NoticeBox = control WebBrowser). Wine lo
    // busca en <datadir>/wine/gecko/ y, si esta ahi, lo instala solo la primera
    // vez que hace falta, sin preguntar ni descargar nada.
    private void ensureWineGecko() {
        final String name = "wine-gecko-2.47.4-x86.msi";
        File target = new File(rootFS.getRootDir(), "/opt/wine/share/wine/gecko/"+name);
        try {
            long assetSize;
            try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("hikari/"+name)) {
                assetSize = fd.getLength();
            }
            catch (Exception e) {
                assetSize = -1; // comprimido dentro del APK: no se puede saber el tamano sin leerlo
            }
            if (target.isFile() && (assetSize < 0 || target.length() == assetSize)) return;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            File tmp = new File(target.getPath()+".tmp");
            try (java.io.InputStream in = getAssets().open("hikari/"+name);
                 java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            if (!tmp.renameTo(target)) Log.w(HikariConfig.LOG_TAG, "No se pudo renombrar "+tmp);
            else Log.i(HikariConfig.LOG_TAG, "Wine Gecko copiado a "+target);
        }
        catch (Exception e) {
            Log.e(HikariConfig.LOG_TAG, "No se pudo preparar Wine Gecko", e);
        }
    }

    // Enlaces de las noticias del patcher: Wine los abre "en ventana nueva"
    // pidiendo a COM un Internet Explorer fuera de proceso (lento y oculto
    // detras del patcher). hikari_openurl.exe (fuente en hikari_tools/) se
    // registra en su lugar y manda la URL a Android via winhandler.exe /url.
    private void installOpenUrlHelper() {
        File wineDir = new File(container.getRootDir(), ".wine");
        File exe = new File(wineDir, "drive_c/windows/hikari_openurl.exe");
        try {
            // Son 25 KB: se copia siempre, asi cualquier version nueva sustituye a la anterior.
            try (java.io.InputStream in = getAssets().open("hikari/hikari_openurl.exe");
                 java.io.OutputStream out = new java.io.FileOutputStream(exe)) {
                byte[] buffer = new byte[1 << 15];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }

            copyHikariAsset("hikari/hikari_setupok.exe", new File(wineDir, "drive_c/windows/hikari_setupok.exe"));

            String server = "C:\\windows\\hikari_openurl.exe";
            String clsid = "{0002DF01-0000-0000-C000-000000000046}";
            try (WineRegistryEditor registry = new WineRegistryEditor(new File(wineDir, "system.reg"))) {
                registry.setStringValue("Software\\Classes\\CLSID\\"+clsid+"\\LocalServer32", null, server);
                registry.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\"+clsid+"\\LocalServer32", null, server);
            }
        }
        catch (Exception e) {
            Log.e(HikariConfig.LOG_TAG, "No se pudo instalar hikari_openurl.exe", e);
        }
    }

    private void copyHikariAsset(String asset, File target) {
        try (java.io.InputStream in = getAssets().open(asset);
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 15];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        catch (Exception e) {
            Log.e(HikariConfig.LOG_TAG, "No se pudo copiar "+asset, e);
        }
    }

    // Pregunta a wine que procesos hay en marcha y devuelve sus nombres (en minusculas).
    private void queryWineProcesses(com.winlator.core.Callback<java.util.Set<String>> callback) {
        final java.util.Set<String> names = new java.util.HashSet<>();
        final boolean[] done = {false};
        winHandler.setOnGetProcessInfoListener((index, count, info) -> {
            if (info != null) names.add(info.name.toLowerCase());
            if (!done[0] && (count == 0 || index >= count - 1)) {
                done[0] = true;
                winHandler.setOnGetProcessInfoListener(null);
                runOnUiThread(() -> callback.call(names));
            }
        });
        winHandler.listProcesses();
    }

    private boolean isHikariContainer() {
        return container != null && container.getExtra(HikariConfig.CONTAINER_MARKER_KEY, "0").equals("1");
    }

    // Copia assets/inputcontrols/hikariro.icp a la carpeta de perfiles (solo
    // cuando cambia HikariConfig.CONTROLS_VERSION) y lo devuelve cargado.
    private ControlsProfile installHikariControlsProfile() {
        File target = ControlsProfile.getProfileFile(this, HikariConfig.CONTROLS_PROFILE_ID);
        int installed = preferences.getInt("hikari_controls_version", 0);
        if (!target.isFile() || installed != HikariConfig.CONTROLS_VERSION) {
            FileUtils.copy(this, "inputcontrols/hikariro.icp", target);
            if (target.isFile()) {
                preferences.edit().putInt("hikari_controls_version", HikariConfig.CONTROLS_VERSION).apply();
            }
            inputControlsManager.loadProfiles(true);
        }
        return inputControlsManager.getProfile(HikariConfig.CONTROLS_PROFILE_ID);
    }

    // Estado compartido entre la pantalla de carga y los controles (HikariRO):
    // los controles del juego solo se muestran cuando ya se ve raghikari.exe,
    // no encima del patcher.
    private volatile boolean hikariGameVisible = false;
    private Runnable hikariApplyControlsVisibility;
    private com.winlator.hikariro.HikariLoadingView hikariLoadingView;

    private void showHikariLoading(FrameLayout rootView, String status) {
        if (hikariLoadingView != null && hikariLoadingView.getParent() != null && !hikariLoadingView.isDismissing()) {
            hikariLoadingView.setStatus(status);
            return;
        }
        hikariLoadingView = new com.winlator.hikariro.HikariLoadingView(this);
        hikariLoadingView.setStatus(status);
        rootView.addView(hikariLoadingView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void checkAfterPatcherClosed(FrameLayout rootView, int attempt) {
        if (hikariGameVisible) return;
        queryWineProcesses((names) -> {
            if (hikariGameVisible) return;
            boolean gameRunning = false, patcherRunning = false;
            for (String name : names) {
                if (name.startsWith("raghikari")) gameRunning = true;
                if (name.startsWith("hikariro") || name.startsWith("tmp.exe")) patcherRunning = true;
            }
            if (gameRunning) {
                HikariCompat.markGameStarting(this, () -> hikariGameVisible);
                showHikariLoading(rootView, "Iniciando el juego...");
                // Si el juego no llega a mostrarse, no dejamos la carga tapando todo.
                rootView.postDelayed(() -> { if (!hikariGameVisible) hideHikariLoading(); }, 45000);
            }
            else if (attempt < 8) {
                // raghikari.exe puede tardar un poco en aparecer tras pulsar "Jugar"
                rootView.postDelayed(() -> checkAfterPatcherClosed(rootView, attempt + 1), 500);
            }
            else if (!patcherRunning) {
                // Patcher cerrado sin jugar: salir directamente.
                Log.i(HikariConfig.LOG_TAG, "Patcher cerrado sin lanzar el juego: cerrando la app");
                exit();
            }
        });
    }

    private void hideHikariLoading() {
        if (hikariLoadingView != null) hikariLoadingView.dismiss();
    }

    // Pantalla de carga (HikariLoadingView) encima de todo hasta que se ve
    // algo util: primero el patcher (HikariRO.exe) y, tras pulsar "Jugar",
    // otra vez hasta que raghikari.exe pinta su ventana principal.
    private void setupLoadingOverlay(FrameLayout rootView) {
        showHikariLoading(rootView, "Preparando el sistema...");

        xServer.windowManager.addOnWindowModificationListener(new com.winlator.xserver.WindowManager.OnWindowModificationListener() {
            private boolean started = false;
            private boolean patcherShown = false;

            private boolean isGameWindow(com.winlator.xserver.Window window) {
                return window.getClassName().toLowerCase().contains("raghikari");
            }

            private boolean isPatcherWindow(com.winlator.xserver.Window window) {
                String className = window.getClassName().toLowerCase();
                return (className.startsWith("hikariro") || className.startsWith("tmp.exe")) && window.getWidth() >= 300;
            }

            private boolean isSetupWindow(com.winlator.xserver.Window window) {
                return window.getClassName().toLowerCase().startsWith("setup.exe") || window.getName().toLowerCase().contains("ragnarok setup");
            }

            @Override
            public void onMapWindow(com.winlator.xserver.Window window) {
                // Setup.exe de la primera vez: se acepta solo y la carga lo tapa
                if (!hikariGameVisible && isSetupWindow(window)) {
                    winHandler.exec("C:\\windows\\hikari_setupok.exe");
                    runOnUiThread(() -> showHikariLoading(rootView, "Preparando la configuración del juego..."));
                }
                if (!started) {
                    started = true;
                    runOnUiThread(() -> { if (hikariLoadingView != null) hikariLoadingView.setStatus("Arrancando HikariRO..."); });
                }
                if (!hikariGameVisible && isGameWindow(window)) {
                    HikariCompat.markGameStarting(XServerDisplayActivity.this, () -> hikariGameVisible);
                    runOnUiThread(() -> showHikariLoading(rootView, "Cargando HikariRO..."));
                }
            }

            @Override
            public void onUnmapWindow(com.winlator.xserver.Window window) {
                // El patcher se cierra al pulsar "Jugar": tapamos el hueco hasta que se ve el juego.
                if (patcherShown && !hikariGameVisible && isPatcherWindow(window)) {
                    // Se ha cerrado el patcher: o se pulso "Jugar" (arranca raghikari.exe)
                    // o se cerro sin jugar. Se mira la lista de procesos unas cuantas veces.
                    runOnUiThread(() -> checkAfterPatcherClosed(rootView, 0));
                }
            }

            @Override
            public void onUpdateWindowContent(com.winlator.xserver.Window window) {
                if (!hikariGameVisible && isGameWindow(window) && window.getWidth() >= 800) {
                    hikariGameVisible = true;
                    // Se confirma el modo grafico solo si el juego sigue en pie unos segundos
                    xServerView.postDelayed(() -> { if (!isFinishing()) HikariCompat.markGameVisible(XServerDisplayActivity.this); }, 10000);
                    runOnUiThread(() -> {
                        hideHikariLoading();
                        if (hikariApplyControlsVisibility != null) hikariApplyControlsVisibility.run();
                    });
                }
                else if (!hikariGameVisible && isPatcherWindow(window) && (!patcherShown || (hikariLoadingView != null && hikariLoadingView.getParent() != null && !hikariLoadingView.isDismissing()))) {
                    // Primera vez que se ve el patcher, o vuelve a pintarse con la carga delante
                    patcherShown = true;
                    runOnUiThread(() -> xServerView.postDelayed(() -> { if (!hikariGameVisible) hideHikariLoading(); }, 300));
                }
            }
        });

        // Por si acaso no se detecta ninguna ventana: nunca mas de 3 minutos.
        rootView.postDelayed(this::hideHikariLoading, 180000);
    }

    private android.widget.ImageButton createFloatingButton(int iconRes) {
        float density = getResources().getDisplayMetrics().density;
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setImageResource(iconRes);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int padding = (int)(10 * density);
        button.setPadding(padding, padding, padding, padding);
        button.setColorFilter(0xE6FFFFFF);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(0x59000000);
        background.setStroke((int)Math.max(1, density), 0x33FFFFFF);
        button.setBackground(background);
        button.setAlpha(0.75f);
        button.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
            else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
        return button;
    }

    private void setupFloatingButtons(FrameLayout rootView) {
        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int size = (int)(40 * density);
        int gap = (int)(8 * density);

        android.widget.ImageButton settingsButton = createFloatingButton(R.drawable.hikari_ic_settings);
        settingsButton.setOnClickListener((v) -> showHikariMenu());
        android.widget.ImageButton toggleButton = createFloatingButton(R.drawable.hikari_ic_eye);
        android.widget.ImageButton keyboardButton = createFloatingButton(R.drawable.hikari_ic_keyboard);
        keyboardButton.setOnClickListener((v) -> AppUtils.showKeyboard(this));

        final boolean[] controlsHidden = {preferences.getBoolean("hikari_controls_hidden", false)};
        Runnable applyVisibility = () -> {
            boolean hasProfile = inputControlsView.getProfile() != null;
            boolean gameReady = !isHikariContainer() || hikariGameVisible;
            inputControlsView.setVisibility(controlsHidden[0] || !hasProfile || !gameReady ? View.GONE : View.VISIBLE);
            toggleButton.setImageResource(controlsHidden[0] ? R.drawable.hikari_ic_eye_off : R.drawable.hikari_ic_eye);
            // El ojo y los ajustes solo tienen sentido cuando ya se ve el juego
            toggleButton.setVisibility(gameReady ? View.VISIBLE : View.GONE);
            settingsButton.setVisibility(gameReady ? View.VISIBLE : View.GONE);
        };
        hikariApplyControlsVisibility = applyVisibility;
        toggleButton.setOnClickListener((v) -> {
            controlsHidden[0] = !controlsHidden[0];
            preferences.edit().putBoolean("hikari_controls_hidden", controlsHidden[0]).apply();
            applyVisibility.run();
        });
        rootView.post(applyVisibility);

        android.widget.LinearLayout.LayoutParams settingsParams = new android.widget.LinearLayout.LayoutParams(size, size);
        settingsParams.rightMargin = gap;
        bar.addView(settingsButton, settingsParams);
        android.widget.LinearLayout.LayoutParams first = new android.widget.LinearLayout.LayoutParams(size, size);
        first.rightMargin = gap;
        bar.addView(toggleButton, first);
        bar.addView(keyboardButton, new android.widget.LinearLayout.LayoutParams(size, size));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.END);
        int margin = (int)(12 * density);
        barParams.setMargins(margin, margin, margin, margin);
        rootView.addView(bar, barParams);
        hikariMenuAnchor = bar;
    }

    // ---------------- HikariRO: segundo plano ----------------
    private final Handler hikariHandler = new Handler(Looper.getMainLooper());
    private boolean hikariSuspended = false;
    private final Runnable hikariSuspendRunnable = () -> {
        if (environment != null && !hikariSuspended) {
            Log.i(HikariConfig.LOG_TAG, "Mucho tiempo en segundo plano: se congela el juego para ahorrar bateria");
            environment.onPause();
            hikariSuspended = true;
            ForegroundService.setGameSuspended(true);
        }
    };

    private void hikariGoToBackground() {
        if (!HikariConfig.BACKGROUND_KEEP_ALIVE) {
            hikariSuspendRunnable.run();
            return;
        }
        if (HikariConfig.BACKGROUND_MUTE_AUDIO) com.winlator.alsaserver.ALSAClient.setMuted(true);
        hikariHandler.removeCallbacks(hikariSuspendRunnable);
        if (HikariConfig.BACKGROUND_SUSPEND_AFTER_MINUTES > 0) {
            hikariHandler.postDelayed(hikariSuspendRunnable, HikariConfig.BACKGROUND_SUSPEND_AFTER_MINUTES * 60000L);
        }
    }

    private void hikariReturnFromBackground() {
        hikariHandler.removeCallbacks(hikariSuspendRunnable);
        if (hikariSuspended) {
            environment.onResume();
            hikariSuspended = false;
            ForegroundService.setGameSuspended(false);
        }
        com.winlator.alsaserver.ALSAClient.setMuted(false);
    }

    // ---------------- HikariRO: menu de ajustes ----------------
    private static final int HIKARI_EDIT_CONTROLS_REQUEST_CODE = 42;
    private View hikariMenuAnchor;

    private void showHikariMenu() {
        View anchor = hikariMenuAnchor != null ? hikariMenuAnchor : xServerView;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(new android.view.ContextThemeWrapper(this, android.R.style.Theme_Material), anchor, android.view.Gravity.END);
        Menu items = menu.getMenu();
        final int EDIT = 1, RESET = 2, HAPTICS = 3, COMPAT = 5, EXIT = 6;
        if (hikariGameVisible) {
            items.add(0, EDIT, 0, "Editar botones");
            items.add(0, RESET, 1, "Restablecer botones");
        }
        items.add(0, HAPTICS, 2, "Vibracion al pulsar").setCheckable(true).setChecked(preferences.getBoolean("hikari_haptics", true));
        items.add(0, COMPAT, 4, "Modo grafico de compatibilidad").setCheckable(true).setChecked(HikariCompat.getLevel(this) == HikariCompat.LEVEL_OPENGL);
        items.add(0, EXIT, 5, "Salir del juego");
        menu.setOnMenuItemClickListener((item) -> {
            switch (item.getItemId()) {
                case EDIT: {
                    Intent intent = new Intent(this, ControlsEditorActivity.class);
                    intent.putExtra("profile_id", HikariConfig.CONTROLS_PROFILE_ID);
                    intent.putExtra("hikari_style", true);
                    startActivityForResult(intent, HIKARI_EDIT_CONTROLS_REQUEST_CODE);
                    break;
                }
                case RESET:
                    hikariConfirm("¿Volver a colocar los botones como venian de serie?", () -> hikariReloadControls(true));
                    break;
                case HAPTICS: {
                    boolean enabled = !preferences.getBoolean("hikari_haptics", true);
                    preferences.edit().putBoolean("hikari_haptics", enabled).apply();
                    inputControlsView.setHapticsEnabled(enabled);
                    break;
                }
                case COMPAT: {
                    boolean toCompat = HikariCompat.getLevel(this) != HikariCompat.LEVEL_OPENGL;
                    HikariCompat.setLevel(this, toCompat ? HikariCompat.LEVEL_OPENGL : HikariCompat.LEVEL_VULKAN);
                    AppUtils.showToast(this, toCompat ? "Se usara el modo de compatibilidad la proxima vez que abras HikariRO" : "Se usara el modo grafico normal la proxima vez que abras HikariRO");
                    break;
                }
                case EXIT:
                    hikariConfirm("¿Salir de HikariRO?", () -> {
                        // Salida voluntaria: no cuenta como arranque fallido
                        HikariCompat.clearPending(this);
                        exit();
                    });
                    break;
            }
            return true;
        });
        menu.show();
    }

    private void hikariConfirm(String message, Runnable onYes) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(message)
            .setPositiveButton("Si", (d, w) -> onYes.run())
            .setNegativeButton("No", null)
            .create();
        dialog.setOnDismissListener((d) -> AppUtils.hideSystemUI(this));
        dialog.show();
    }

    private void hikariReloadControls(boolean resetToDefault) {
        File target = ControlsProfile.getProfileFile(this, HikariConfig.CONTROLS_PROFILE_ID);
        if (resetToDefault) FileUtils.copy(this, "inputcontrols/hikariro.icp", target);
        inputControlsManager.loadProfiles(true);
        ControlsProfile profile = inputControlsManager.getProfile(HikariConfig.CONTROLS_PROFILE_ID);
        if (profile != null) showInputControls(profile);
        if (hikariApplyControlsVisibility != null) hikariApplyControlsVisibility.run();
    }

    private void createShortSocketSymlink(String rootPath, String shortRootPath, String relativePath) {
        File shortSocketFile = new File(shortRootPath, relativePath);
        File shortSocketDir = shortSocketFile.getParentFile();
        if (shortSocketDir != null && !shortSocketDir.isDirectory()) shortSocketDir.mkdirs();
        FileUtils.symlink(rootPath+relativePath, shortSocketFile.getPath());
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        boolean forceFullscreen = (shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1")) ||
            (container != null && container.getExtra("forceFullscreen", "0").equals("1"));
        renderer.setForceWindowsFullscreen(forceFullscreen);

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (isHikariContainer()) runOnUiThread(this::showHikariMenu);
            else if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        // Botones flotantes arriba a la derecha: teclado y ocultar/mostrar controles.
        setupFloatingButtons(rootView);

        setupLoadingOverlay(rootView);

        if (isHikariContainer()) {
            touchpadView.setLongPressDragEnabled(true);
            touchpadView.setPinchZoomEnabled(true);
            inputControlsView.setHikariStyle(true);
            inputControlsView.setHapticsEnabled(preferences.getBoolean("hikari_haptics", true));
            ControlsProfile hikariProfile = installHikariControlsProfile();
            if (hikariProfile != null) showInputControls(hikariProfile);
            // showInputControls() los hace visibles; se ocultan hasta que se vea el juego.
            if (hikariApplyControlsVisibility != null) hikariApplyControlsVisibility.run();
            watchGravityErrorHandler();
        }

        if (container != null && container.getHUDMode() != FrameRating.Mode.DISABLED.ordinal()) {
            frameRating = new FrameRating(this);
            frameRating.setMode(FrameRating.Mode.values()[container.getHUDMode()]);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating);
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (AppDefaults.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            if (position <= 0) return;
            Intent intent = new Intent(this, ControlsEditorActivity.class);
            intent.putExtra("profile_id", inputControlsManager.getProfiles().get(position - 1).id);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, AppDefaults.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        String currentGraphicsDriver = preferences.getString("current_graphics_driver", "");
        int currentGraphicsDriverPatchVersion = preferences.getInt("graphics_driver_patch_version", 0);
        // Ver GRAPHICS_DRIVER_PATCH_VERSION: aunque el driver y su version
        // sean los mismos que la ultima vez, si esta build trae un parche
        // mas nuevo que el que se aplico entonces, forzamos la reextraccion
        // igualmente.
        boolean changed = !cacheId.equals(currentGraphicsDriver) || currentGraphicsDriverPatchVersion < GRAPHICS_DRIVER_PATCH_VERSION;
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            preferences.edit()
                .putString("current_graphics_driver", cacheId)
                .putInt("graphics_driver_patch_version", GRAPHICS_DRIVER_PATCH_VERSION)
                .apply();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK) && (changed || AppDefaults.DEBUG_MODE)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
            patchGraphicsDriverHardcodedPath(new File(libDir, "libvulkan_vortek.so"));
        }

        fixVulkanIcdPaths(rootDir);

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                    // Rutas de drirc con el paquete original (resuelven por el enlace "usr").
                    patchGraphicsDriverHardcodedPath(new File(libDir, "libGL.so.1.7.0"));
                }
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");

                if (changed || AppDefaults.DEBUG_MODE) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                    patchGraphicsDriverHardcodedPath(new File(libDir, "libGL.so.1.7.0"));
                }
                break;
        }
    }

    // gladio (libGL.so.1.7.0) y vortek (libvulkan_vortek.so) son binarios ya
    // compilados que vienen dentro de graphics_driver/*.tzst con la ruta de
    // conexion a su socket grabada de fabrica asumiendo el paquete original
    // "com.winlator" (exactamente el mismo problema que ya tenian wineserver
    // y ntdll.so, ver RootFSInstaller.patchHardcodedWinlatorPackagePaths).
    // Sin este parche, ambos intentan conectar a un socket que no existe
    // (porque esta app usa el applicationId "com.hikarimovil.launcher") y
    // wine se queda sin ningun driver de ventanas/OpenGL que cargar
    // ("nodrv_CreateWindow ... no driver could be loaded"): el juego (o su
    // Setup.exe) no consigue nunca abrir una ventana y termina saliendo
    // solo, sin ni siquiera un error visible, lo que produce el bucle de
    // "Starting up" que se cierra y se vuelve a abrir.
    //
    // Igual que con wineserver, el string grabado no se puede alargar sin
    // reestructurar el binario, asi que usamos como reemplazo la ruta mas
    // corta "/data/data/<packageName>" (sin el "/files/rootfs" de en medio)
    // -shortRootPath en setupXEnvironment es esa misma ruta-, y alli mismo
    // creamos un enlace simbolico que hace que esa ruta corta lleve al
    // mismo socket real que usa wine.
    private void patchGraphicsDriverHardcodedPath(File driverFile) {
        String oldPrefix = "/data/data/com.winlator/files/rootfs";
        String newPrefix = "/data/data/"+getPackageName();
        HardcodedPathPatcher.patchPrefix(driverFile, oldPrefix, newPrefix);
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper"));
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        // HikariRO no usa Direct3D 12 (VKD3D no se incluye en el APK)
        if (!isHikariContainer()) GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        return true;
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.isLinkPath()) {
                cmdArgs = "\""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            Intent intent = getIntent();
            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);

                if (execPath.endsWith(".lnk")) {
                    cmdArgs = "\""+execPath+"\"";
                    execPath = null;
                }
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }
            cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \""+filename+"\""+execArgs;
        }

        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\windows \"wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = rootFS.getRootDir();
            File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals(AudioDrivers.ALSA)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        AppDefaults.resetPreferenceVersions(this);
    }

    public void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null) return;
        if (visible) {
            if (window.id == frameRatingWindowId) return;
            Window child = window.getChildAt(0);
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            Window frameRatingWindow = null;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                frameRatingWindow = window.isSurface() ? window : child;
            }
            else if (window.isSurface() && !window.isApplicationWindow()) {
                Window parent = window.getParent();
                if (parent != null && parent.isApplicationWindow() && !parent.isSurface()) frameRatingWindow = window;
            }

            if (frameRatingWindow != null) {
                if (frameRating.getMode() == FrameRating.Mode.FULL) {
                    Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                    frameRating.setGPUInfo(gpuInfo != null ? new String(gpuInfo.data.array()) : "N/A");
                }
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}