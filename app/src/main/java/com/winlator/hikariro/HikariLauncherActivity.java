package com.winlator.hikariro;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineUtils;
import com.winlator.win32.WinVersions;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

// Arranque de HikariRO Movil: permisos, sistema base, contenedor, cliente y juego
public class HikariLauncherActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private HikariLoadingView loadingView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadingView = new HikariLoadingView(this);
        setContentView(loadingView);
        com.winlator.core.AppUtils.hideSystemUI(this);

        Intent intent = getIntent();
        int containerId = intent.getIntExtra("container_id", 0);
        String startPath = intent.getStringExtra("start_path");
        if (containerId > 0 && startPath != null && !startPath.isEmpty()) {
            launchGame(containerId, HikariConfig.getLaunchExe(new File(startPath)).getPath());
            return;
        }

        // Canal de notificaciones al abrir (el permiso se pide ahora y no en mitad de la partida)
        if (HikariConfig.BACKGROUND_KEEP_ALIVE) com.winlator.services.NotificationUtils.getInstance(this);

        setStatus(getString(R.string.hikari_status_checking));
        runCompatibilityChecks(() -> {
            if (!requestStoragePermissionsIfNeeded()) proceedAfterPermissions();
        });
    }

    // Compatibilidad del movil (punto de entrada)
    private void runCompatibilityChecks(Runnable onContinue) {
        Log.i(HikariConfig.LOG_TAG, "Dispositivo: "+HikariCompat.describeDevice(this));
        HikariCompat.StartResult result = HikariCompat.onAppStart(this);

        if (result == HikariCompat.StartResult.SUGGEST_FALLBACK) {
            showDialog("¿Probar el modo de compatibilidad?",
                "El juego no ha llegado a abrirse en los ultimos intentos. Si tu movil tiene problemas con el modo grafico normal, el modo de compatibilidad funciona en mas moviles, aunque va bastante mas lento.\n\nPuedes cambiarlo mas tarde desde el menu de ajustes del juego.",
                "Probar compatibilidad", () -> {
                    HikariCompat.setLevel(this, HikariCompat.LEVEL_OPENGL);
                    onContinue.run();
                }, "Seguir en modo normal", onContinue);
            return;
        }
        if (result == HikariCompat.StartResult.GAVE_UP) {
            showDialog("No se ha podido abrir HikariRO",
                "El juego no ha conseguido arrancar en este movil ni en modo de compatibilidad.\n\n"+HikariCompat.describeDevice(this)+
                "\n\nPuedes volver a intentarlo. Si sigue fallando, envia estos datos al equipo de HikariRO.",
                "Reintentar", onContinue, "Salir", this::finishAndRemoveTask);
            return;
        }

        String warning = HikariCompat.getCompatibilityWarning(this);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (warning != null && !warning.equals(preferences.getString("hikari_compat_warning_shown", ""))) {
            preferences.edit().putString("hikari_compat_warning_shown", warning).apply();
            showDialog("Aviso de compatibilidad", warning, "Continuar", onContinue, "Salir", this::finishAndRemoveTask);
            return;
        }
        onContinue.run();
    }

    private void showDialog(String title, String message, String positive, Runnable onPositive, String negative, Runnable onNegative) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(positive, (d, w) -> onPositive.run());
        if (negative != null) builder.setNegativeButton(negative, (d, w) -> { if (onNegative != null) onNegative.run(); });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener((d) -> com.winlator.core.AppUtils.hideSystemUI(this));
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (granted) {
                proceedAfterPermissions();
            } else {
                setStatus(getString(R.string.hikari_status_permission_denied));
            }
        }
    }

    private boolean requestStoragePermissionsIfNeeded() {
        boolean hasWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (hasWrite && hasRead) return false;

        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        }, PERMISSION_REQUEST_CODE);
        return true;
    }

    private void proceedAfterPermissions() {
        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) {
            setStatus(getString(R.string.hikari_status_installing_base));
            // RootFSInstaller no avisa al terminar: se sondea hasta que este listo
            RootFSInstaller.install(this);
            waitForRootFSAndContinue();
        } else {
            ensureContainerAndLaunch();
        }
    }

    private void waitForRootFSAndContinue() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final RootFS rootFS = RootFS.find(this);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (rootFS.isValid() && rootFS.getVersion() >= RootFSInstaller.LATEST_VERSION) {
                    ensureContainerAndLaunch();
                } else {
                    handler.postDelayed(this, 500);
                }
            }
        }, 500);
    }

    private void ensureContainerAndLaunch() {
        setStatus(getString(R.string.hikari_status_preparing_container));
        ContainerManager containerManager = new ContainerManager(this);
        Container existing = findHikariContainer(containerManager);

        if (existing != null) {
            // Autocorrige contenedores creados con versiones anteriores de la app
            boolean needsSave = false;

            String recommendedDriver = HikariCompat.getGraphicsDriver(this);
            if (!recommendedDriver.equals(existing.getGraphicsDriver())) {
                existing.setGraphicsDriver(recommendedDriver);
                needsSave = true;
            }

            String expectedDrives = "D:" + getClientDir().getPath();
            if (!expectedDrives.equals(existing.getDrives())) {
                existing.setDrives(expectedDrives);
                needsSave = true;
            }

            if (!HikariConfig.getScreenSize(this).equals(existing.getScreenSize())) {
                existing.setScreenSize(HikariConfig.getScreenSize(this));
                needsSave = true;
            }

            // Windows XP: la version contemporanea al cliente
            File systemRegFile = new File(existing.getRootDir(), ".wine/system.reg");
            if (systemRegFile.isFile()) {
                String currentProductName;
                try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                    currentProductName = registryEditor.getStringValue("Software\\Microsoft\\Windows NT\\CurrentVersion", "ProductName", "");
                }

                if (!"Microsoft Windows XP".equals(currentProductName)) {
                    int winXpIndex = -1;
                    WinVersions.WinVersion[] winVersions = WinVersions.getWinVersions();
                    for (int i = 0; i < winVersions.length; i++) {
                        if (winVersions[i].version.equals("winxp")) {
                            winXpIndex = i;
                            break;
                        }
                    }
                    if (winXpIndex != -1) WineUtils.setWinVersion(existing, winXpIndex);
                }
            }

            String dxWrapper = HikariCompat.getDXWrapper(this);
            if (!dxWrapper.equals(existing.getDXWrapper())) {
                existing.setDXWrapper(dxWrapper);
                needsSave = true;
            }

            if (!HikariConfig.BOX64_PRESET.equals(existing.getBox64Preset())) {
                existing.setBox64Preset(HikariConfig.BOX64_PRESET);
                needsSave = true;
            }

            if (needsSave) existing.saveData();
            checkClientFilesAndLaunch(existing.id);
            return;
        }

        JSONObject data = new JSONObject();
        try {
            data.put("name", HikariConfig.CONTAINER_NAME);
            data.put("screenSize", HikariConfig.getScreenSize(this));
            data.put("graphicsDriver", HikariCompat.getGraphicsDriver(this));
            data.put("dxwrapper", HikariCompat.getDXWrapper(this));
            data.put("box64Preset", HikariConfig.BOX64_PRESET);
            data.put("drives", "D:" + getClientDir().getPath());

            JSONObject extraData = new JSONObject();
            extraData.put("forceFullscreen", "1");
            extraData.put(HikariConfig.CONTAINER_MARKER_KEY, "1");
            data.put("extraData", extraData);
        } catch (JSONException e) {
            setStatus(getString(R.string.hikari_status_container_error));
            return;
        }

        containerManager.createContainerAsync(data, container -> {
            if (container == null) {
                setStatus(getString(R.string.hikari_status_container_error));
                return;
            }
            checkClientFilesAndLaunch(container.id);
        });
    }

    private Container findHikariContainer(ContainerManager containerManager) {
        for (Container container : containerManager.getContainers()) {
            if (container.getExtra(HikariConfig.CONTAINER_MARKER_KEY, "0").equals("1")) return container;
        }
        return null;
    }

    // Carpeta privada (sin FUSE): wine puede mapear los .grf con permiso de ejecucion
    private File getClientDir() {
        return new File(getFilesDir(), HikariConfig.CLIENT_DIR_NAME);
    }

    // Carpeta publica antigua: solo para migrar instalaciones viejas
    private File getLegacyPublicClientDir() {
        return new File(Environment.getExternalStorageDirectory(), HikariConfig.CLIENT_DIR_NAME);
    }

    private void checkClientFilesAndLaunch(final int containerId) {
        // Primero la migracion (marcador ausente) y solo despues el exe
        File migrationMarker = new File(getClientDir(), HikariClientMigrator.MARKER_FILE_NAME);
        File legacyExeFile = new File(getLegacyPublicClientDir(), HikariConfig.EXE_NAME);
        if (legacyExeFile.isFile() && !migrationMarker.isFile()) {
            migrateFromLegacyPublicDirAndLaunch(containerId);
            return;
        }

        // Cliente completo: el exe existe y no queda una descarga a medias
        File exeFile = new File(getClientDir(), HikariConfig.EXE_NAME);
        File pendingDownload = new File(getClientDir(), HikariClientDownloader.TMP_DIR_NAME);
        if (exeFile.isFile() && !pendingDownload.isDirectory()) {
            HikariOptionInfoFixer.fit(getClientDir(), HikariConfig.getScreenSize(this), HikariConfig.GAME_FULLSCREEN);
            launchGame(containerId, HikariConfig.getLaunchExe(getClientDir()).getPath());
            return;
        }

        setStatus(getString(R.string.hikari_status_checking_space));
        StatFs statFs = new StatFs(getFilesDir().getPath());
        long freeBytes = statFs.getAvailableBytes();
        if (freeBytes < HikariConfig.MIN_FREE_SPACE_BYTES) {
            setStatus(getString(R.string.hikari_status_insufficient_space,
                formatGigabytes(HikariConfig.MIN_FREE_SPACE_BYTES), getClientDir().getPath()));
            return;
        }

        HikariClientDownloader.downloadAndInstallAsync(HikariConfig.getDownloadBaseUrl(this), getClientDir(), new HikariClientDownloader.ProgressListener() {
            @Override
            public void onStatus(String message) {
                setStatus(message);
            }

            @Override
            public void onFileProgress(String fileLabel, int percent) {
                setStatus(getString(R.string.hikari_status_downloading, fileLabel, percent));
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> checkClientFilesAndLaunch(containerId));
            }

            @Override
            public void onError(String message) {
                setStatus(getString(R.string.hikari_status_download_error, message));
            }
        });
    }

    private void migrateFromLegacyPublicDirAndLaunch(final int containerId) {
        setStatus(getString(R.string.hikari_status_migration_checking));
        HikariClientMigrator.migrateAsync(getLegacyPublicClientDir(), getClientDir(),
            new HikariClientMigrator.ProgressListener() {
                @Override
                public void onStatus(String message) {
                    setStatus(getString(R.string.hikari_status_migrating, message));
                }

                @Override
                public void onFileProgress(String fileLabel, int percent) {
                    setStatus(getString(R.string.hikari_status_migrating, fileLabel + ": " + percent + "%"));
                }

                @Override
                public void onComplete() {
                    runOnUiThread(() -> checkClientFilesAndLaunch(containerId));
                }

                @Override
                public void onError(String message) {
                    setStatus(getString(R.string.hikari_status_migration_error, message));
                }
            });
    }

    private static String formatGigabytes(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void launchGame(int containerId, String exePath) {
        Log.i(HikariConfig.LOG_TAG, "Lanzando XServerDisplayActivity: containerId="+containerId+" exePath="+exePath);
        setStatus(getString(R.string.hikari_status_launching));
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", containerId);
        intent.putExtra("exec_path", exePath);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    private void setStatus(final String message) {
        runOnUiThread(() -> {
            if (loadingView != null) loadingView.setStatus(message);
        });
    }
}
