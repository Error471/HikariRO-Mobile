package com.winlator.hikariro;

import android.content.Context;

import com.winlator.R;

// Constantes del fork HikariRO Movil
public abstract class HikariConfig {
    // Carpeta del cliente dentro del almacenamiento privado (unidad D:)
    public static final String CLIENT_DIR_NAME = "HikariRO";

    // Cliente real y patcher (Thor Patcher lanza raghikari.exe al pulsar "Jugar")
    public static final String EXE_NAME = "raghikari.exe";
    public static final String PATCHER_EXE_NAME = "HikariRO.exe";
    public static final boolean USE_PATCHER = true;

    public static java.io.File getLaunchExe(java.io.File clientDir) {
        java.io.File patcher = new java.io.File(clientDir, PATCHER_EXE_NAME);
        if (USE_PATCHER && patcher.isFile()) return patcher;
        return new java.io.File(clientDir, EXE_NAME);
    }

    public static final String CONTAINER_NAME = "HikariRO";

    // Pantalla: alto fijo y ancho segun la proporcion del movil (sin bandas negras)
    public static final String CONTAINER_SCREEN_SIZE = "1024x768";
    public static final int GAME_HEIGHT = 600;
    public static final boolean GAME_FULLSCREEN = true;

    public static String getScreenSize(Context context) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        android.view.WindowManager wm = (android.view.WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        if (shortSide <= 0) return CONTAINER_SCREEN_SIZE;
        int width = Math.round((float)GAME_HEIGHT * longSide / shortSide / 8.0f) * 8;
        return width+"x"+GAME_HEIGHT;
    }

    // Controles tactiles (assets/inputcontrols/hikariro.icp). Subir la version al cambiar el .icp.
    public static final int CONTROLS_PROFILE_ID = 100;
    public static final int CONTROLS_VERSION = 3;

    // Graficos: ver HikariCompat para la eleccion segun el movil
    public static final String OPENGL_DRIVER = "virgl";
    public static final String DX_WRAPPER = com.winlator.container.DXWrappers.DXVK;
    public static final String BOX64_PRESET = com.winlator.box64.Box64Preset.INTERMEDIATE;

    // Limite de FPS (0 = sin limite). Con 30 el limitador de DXVK dejaba el juego
    // lentisimo (CPU casi parada esperando al hilo dxvk-frame), asi que va desactivado.
    public static final int MAX_FPS = 0;

    // Segundo plano: el juego sigue conectado (con aviso en notificaciones mientras
    // estas fuera). Pasado este tiempo fuera se congela para ahorrar bateria (0 = nunca).
    public static final boolean BACKGROUND_KEEP_ALIVE = true;
    public static final int BACKGROUND_SUSPEND_AFTER_MINUTES = 15;
    public static final boolean BACKGROUND_MUTE_AUDIO = true;

    // Modo diagnostico: trazas de wine y box64 a Logcat (ralentiza el juego)
    public static final boolean DIAGNOSTIC_MODE = false;
    public static final String DIAGNOSTIC_WINEDEBUG = "+err,+ole,+seh,+process,+loaddll";

    public static final String LOG_TAG = "HikariRO";

    // Marca del contenedor de HikariRO en su extraData
    public static final String CONTAINER_MARKER_KEY = "hikariMarker";

    // Descarga del cliente. La URL base sale de hikari.properties (script
    // cambiar_repo_descarga.ps1), que Gradle copia al recurso hikari_download_base_url.
    public static final String DEFAULT_DOWNLOAD_BASE_URL =
        "https://github.com/Error471/hikariro-mobile/releases/download/v2-client/";

    public static String getDownloadBaseUrl(Context context) {
        String url = null;
        try {
            url = context.getString(R.string.hikari_download_base_url).trim();
        }
        catch (Exception e) {}
        if (url == null || url.isEmpty()) url = DEFAULT_DOWNLOAD_BASE_URL;
        return url.endsWith("/") ? url : url+"/";
    }

    // Archivo grande partido en trozos que se concatenan en este orden
    public static final class SplitFile {
        public final String targetName;
        public final long expectedSize;
        public final String[] partNames;

        public SplitFile(String targetName, long expectedSize, String... partNames) {
            this.targetName = targetName;
            this.expectedSize = expectedSize;
            this.partNames = partNames;
        }
    }

    // Lista integrada (cliente limpio v2). Si el release trae manifest.json, manda el manifest.
    public static final SplitFile[] SPLIT_FILES = {
        new SplitFile("data.grf", 4029686092L,
            "data.grf.part1", "data.grf.part2", "data.grf.part3"),
    };

    // Resto del cliente en zips que se extraen sobre la carpeta del cliente
    public static final String[] ZIP_ASSETS = {
        "hikariro_client_01.zip",
        "hikariro_client_02.zip",
        "hikariro_client_03.zip",
    };

    // Margen de espacio libre antes de descargar (trozos + archivo final a la vez)
    public static final long MIN_FREE_SPACE_BYTES = 11L * 1024 * 1024 * 1024;

    private HikariConfig() {}
}
