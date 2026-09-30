package com.winlator.hikariro;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copia el cliente de HikariRO desde la carpeta publica antigua
 * (almacenamiento compartido, Environment.getExternalStorageDirectory() +
 * "/HikariRO") a una carpeta privada interna de la app (getFilesDir()).
 *
 * Motivo del cambio (encontrado analizando logcat con -b all + WINEDEBUG
 * +file mientras el juego se quedaba colgado en "Starting up" y se
 * reiniciaba solo cada ~40 segundos): en Android 10+ el almacenamiento
 * compartido (/storage/emulated/0/...) esta mediado por FUSE, y el kernel
 * (SELinux, en modo enforcing) PROHIBE mapear en memoria con permiso de
 * EJECUCION cualquier archivo que viva ahi - se veian cientos de líneas
 * "avc: denied { execute } ... path=.../data.grf dev=fuse ... permissive=0"
 * en el log. Wine pide ese permiso de ejecucion al crear un "file mapping"
 * de CUALQUIER archivo que abre (incluso solo para leerlo), por como
 * Windows permite luego VirtualProtect sobre memoria mapeada de fichero.
 * Con ese mmap bloqueado, la carga del juego fallaba en cascada (por eso
 * aparecia tambien "nodrv_CreateWindow: no driver could be loaded" un poco
 * despues) y el proceso guest terminaba solo con status=0 - no era un
 * crash, ni falta de memoria, ni ningun limite de la Nothing OS: era esto.
 *
 * La carpeta privada interna de la app (donde ya vive el rootfs de
 * wine/box64, que SI puede mapearse con ejecucion) no tiene esta
 * restriccion porque no pasa por FUSE.
 *
 * Resumible igual que HikariClientDownloader: si se interrumpe a mitad
 * (app cerrada, sin espacio, etc.), en el siguiente intento se saltan los
 * archivos que ya tengan el tamano correcto en el destino en vez de
 * volver a copiar todo. No borra nada de la carpeta publica de origen:
 * eso se deja para que el usuario lo haga a mano una vez confirme que
 * el juego funciona bien desde la carpeta nueva.
 */
public final class HikariClientMigrator {
    private static final int BUFFER_SIZE = 1 << 16;

    // Marcador que confirma que la migracion termino completa. Importante:
    // el ejecutable (raghikari.exe) suele copiarse pronto (esta en la raiz
    // de la carpeta, y el orden de File.listFiles() no esta garantizado),
    // asi que "el exe ya existe en la carpeta nueva" NO significa "ya se
    // puede jugar" - faltarian los .grf y los Lua, y el juego se quedaria
    // colgado en "Starting up" otra vez, pero ahora por archivos a medio
    // copiar en vez de por el permiso de ejecucion. HikariLauncherActivity
    // comprueba este marcador (no solo el exe) antes de lanzar el juego.
    public static final String MARKER_FILE_NAME = ".hikari_migration_complete";

    public interface ProgressListener {
        /** Mensaje de estado general (fase actual, error de un archivo, etc.). */
        void onStatus(String message);

        /** Progreso de copia del archivo actual (0-100), junto con su nombre. */
        void onFileProgress(String fileLabel, int percent);

        void onComplete();

        void onError(String message);
    }

    public static class MigrationException extends IOException {
        public MigrationException(String message) {
            super(message);
        }
    }

    private HikariClientMigrator() {}

    public static void migrateAsync(File oldDir, File newDir, ProgressListener listener) {
        new Thread(() -> {
            try {
                migrate(oldDir, newDir, listener);
                listener.onComplete();
            } catch (Exception e) {
                listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }, "HikariClientMigration").start();
    }

    private static void migrate(File oldDir, File newDir, ProgressListener listener) throws IOException {
        if (!newDir.isDirectory() && !newDir.mkdirs()) {
            throw new MigrationException("No se pudo crear la carpeta " + newDir.getPath());
        }

        List<File> files = new ArrayList<>();
        collectFiles(oldDir, files);

        String oldRootPath = oldDir.getPath();
        int total = files.size();
        int done = 0;

        for (File src : files) {
            done++;
            String relativePath = src.getPath().substring(oldRootPath.length()).replaceFirst("^[/\\\\]+", "");
            File dst = new File(newDir, relativePath);

            if (dst.isFile() && dst.length() == src.length()) {
                // Ya se copio en un intento anterior (interrumpido a mitad); lo saltamos.
                continue;
            }

            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new MigrationException("No se pudo crear la carpeta " + parent.getPath());
            }

            String label = String.format(Locale.getDefault(), "(%d/%d) %s", done, total, relativePath);
            listener.onStatus(label);

            File tmpDst = new File(parent, dst.getName() + ".migrating");
            copyFile(src, tmpDst, label, listener);

            if (dst.isFile() && !dst.delete()) {
                throw new MigrationException("No se pudo reemplazar " + dst.getPath());
            }
            if (!tmpDst.renameTo(dst)) {
                throw new MigrationException("No se pudo mover " + tmpDst.getPath() + " a su destino final");
            }
        }

        // Todos los archivos se copiaron (o ya estaban) sin lanzar ninguna
        // excepcion: ahora si esta completo. Se marca al final adrede, para
        // que una migracion interrumpida a mitad NUNCA deje el marcador
        // puesto por error.
        File marker = new File(newDir, MARKER_FILE_NAME);
        if (!marker.isFile() && !marker.createNewFile()) {
            throw new MigrationException("No se pudo crear el marcador de migracion completa");
        }
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, out);
            } else {
                out.add(child);
            }
        }
    }

    private static void copyFile(File src, File dst, String label, ProgressListener listener) throws IOException {
        long total = src.length();
        long copied = 0;
        long lastReportMs = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                copied += n;
                long now = System.currentTimeMillis();
                if (now - lastReportMs > 400) {
                    lastReportMs = now;
                    int percent = total > 0 ? (int) Math.min(100, (copied * 100) / total) : 100;
                    listener.onFileProgress(label, percent);
                }
            }
        }
        listener.onFileProgress(label, 100);
    }
}
