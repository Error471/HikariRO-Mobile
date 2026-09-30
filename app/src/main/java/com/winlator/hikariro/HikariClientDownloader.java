package com.winlator.hikariro;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Descarga el cliente de HikariRO (repartido en varios assets de un GitHub
 * Release, ver HikariConfig) y lo reconstruye dentro de la carpeta publica
 * del cliente. Pensado para poder reanudarse si se interrumpe a mitad
 * (perdida de red, app cerrada, etc.): los trozos parciales y los marcadores
 * de "ya hecho" se guardan en una subcarpeta oculta dentro de la carpeta del
 * cliente, y en el siguiente intento se retoma justo donde se quedo en vez
 * de volver a descargar todo desde cero.
 *
 * Se ejecuta entero en un hilo aparte; toda la comunicacion hacia la UI pasa
 * por el ProgressListener indicado.
 */
public final class HikariClientDownloader {
    public static final String TMP_DIR_NAME = ".hikari_download_tmp";
    private static final String MANIFEST_NAME = "manifest.json";
    private static final int BUFFER_SIZE = 1 << 16;
    private static final int MAX_ATTEMPTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 30000;

    public interface ProgressListener {
        /** Mensaje de estado general (cambio de fase, reintentos, etc.). */
        void onStatus(String message);

        /** Progreso de la descarga o extraccion del archivo actual (0-100). */
        void onFileProgress(String fileLabel, int percent);

        void onComplete();

        void onError(String message);
    }

    /** Lanzada cuando algo fallo de forma irrecuperable tras los reintentos. */
    public static class DownloadException extends IOException {
        public DownloadException(String message) {
            super(message);
        }
    }

    private HikariClientDownloader() {}

    private static String baseUrl = HikariConfig.DEFAULT_DOWNLOAD_BASE_URL;

    public static void downloadAndInstallAsync(String downloadBaseUrl, File clientDir, ProgressListener listener) {
        baseUrl = downloadBaseUrl;
        new Thread(() -> {
            try {
                downloadAndInstall(clientDir, listener);
                listener.onComplete();
            } catch (Exception e) {
                listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }, "HikariClientDownload").start();
    }

    private static void downloadAndInstall(File clientDir, ProgressListener listener) throws IOException {
        if (!clientDir.isDirectory() && !clientDir.mkdirs()) {
            throw new DownloadException("No se pudo crear la carpeta " + clientDir.getPath());
        }

        File tmpDir = new File(clientDir, TMP_DIR_NAME);
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            throw new DownloadException("No se pudo crear la carpeta temporal de descarga");
        }

        // Lista de ficheros: manifest.json del release, o la integrada si no existe
        HikariConfig.SplitFile[] splitFiles = HikariConfig.SPLIT_FILES;
        String[] zipAssets = HikariConfig.ZIP_ASSETS;
        listener.onStatus("Consultando la lista de ficheros del cliente…");
        try {
            String json = downloadText(baseUrl + MANIFEST_NAME);
            org.json.JSONObject manifest = new org.json.JSONObject(json);
            org.json.JSONArray splitArray = manifest.getJSONArray("splitFiles");
            splitFiles = new HikariConfig.SplitFile[splitArray.length()];
            for (int i = 0; i < splitArray.length(); i++) {
                org.json.JSONObject item = splitArray.getJSONObject(i);
                org.json.JSONArray partsArray = item.getJSONArray("parts");
                String[] parts = new String[partsArray.length()];
                for (int j = 0; j < parts.length; j++) parts[j] = partsArray.getString(j);
                splitFiles[i] = new HikariConfig.SplitFile(item.getString("name"), item.getLong("size"), parts);
            }
            org.json.JSONArray zipArray = manifest.getJSONArray("zips");
            zipAssets = new String[zipArray.length()];
            for (int i = 0; i < zipAssets.length; i++) zipAssets[i] = zipArray.getString(i);
            android.util.Log.i(HikariConfig.LOG_TAG, "Descarga: manifest.json con "+splitFiles.length+" ficheros partidos y "+zipAssets.length+" zips");
        }
        catch (Exception e) {
            android.util.Log.w(HikariConfig.LOG_TAG, "Descarga: sin manifest.json, se usa la lista integrada ("+e.getMessage()+")");
            splitFiles = HikariConfig.SPLIT_FILES;
            zipAssets = HikariConfig.ZIP_ASSETS;
        }

        for (HikariConfig.SplitFile splitFile : splitFiles) {
            downloadSplitFile(splitFile, clientDir, tmpDir, listener);
        }

        for (String zipName : zipAssets) {
            downloadAndExtractZip(zipName, clientDir, tmpDir, listener);
        }

        deleteRecursive(tmpDir);
    }

    private static void downloadSplitFile(HikariConfig.SplitFile splitFile, File clientDir, File tmpDir,
                                           ProgressListener listener) throws IOException {
        File target = new File(clientDir, splitFile.targetName);
        File doneMarker = new File(tmpDir, splitFile.targetName + ".done");
        if (doneMarker.isFile() && target.isFile() && target.length() == splitFile.expectedSize) {
            return;
        }

        File[] partFiles = new File[splitFile.partNames.length];
        for (int i = 0; i < splitFile.partNames.length; i++) {
            String partName = splitFile.partNames[i];
            File partFile = new File(tmpDir, partName);
            partFiles[i] = partFile;
            String label = String.format(Locale.getDefault(), "%s (parte %d/%d)",
                splitFile.targetName, i + 1, splitFile.partNames.length);
            downloadWithRetry(baseUrl + partName, partFile, listener, label);
        }

        listener.onStatus("Uniendo partes de " + splitFile.targetName + "…");
        File tmpTarget = new File(tmpDir, splitFile.targetName + ".joining");
        try (FileOutputStream out = new FileOutputStream(tmpTarget)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            for (File part : partFiles) {
                try (FileInputStream in = new FileInputStream(part)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
        }

        if (tmpTarget.length() != splitFile.expectedSize) {
            tmpTarget.delete();
            throw new DownloadException("El tamano de " + splitFile.targetName + " no coincide tras unir las partes");
        }

        if (target.isFile() && !target.delete()) {
            throw new DownloadException("No se pudo reemplazar " + splitFile.targetName);
        }
        if (!tmpTarget.renameTo(target)) {
            throw new DownloadException("No se pudo mover " + splitFile.targetName + " a su carpeta final");
        }

        for (File part : partFiles) part.delete();
        doneMarker.createNewFile();
    }

    private static void downloadAndExtractZip(String zipName, File clientDir, File tmpDir,
                                               ProgressListener listener) throws IOException {
        File doneMarker = new File(tmpDir, zipName + ".done");
        if (doneMarker.isFile()) return;

        File zipFile = new File(tmpDir, zipName);
        downloadWithRetry(baseUrl + zipName, zipFile, listener, zipName);

        listener.onStatus("Extrayendo " + zipName + "…");
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File outFile = new File(clientDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(clientDir.getCanonicalPath() + File.separator)) {
                    throw new DownloadException("Ruta no valida dentro de " + zipName + ": " + entry.getName());
                }
                File parent = outFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new DownloadException("No se pudo crear la carpeta " + parent.getPath());
                }
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = zis.read(buffer)) != -1) fos.write(buffer, 0, n);
                }
                zis.closeEntry();
            }
        }

        zipFile.delete();
        doneMarker.createNewFile();
    }

    private static void downloadWithRetry(String url, File dest, ProgressListener listener, String label)
            throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                downloadFile(url, dest, listener, label);
                return;
            } catch (IOException e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS) {
                    listener.onStatus(String.format(Locale.getDefault(),
                        "Error de red descargando %s, reintentando (%d/%d)…", label, attempt, MAX_ATTEMPTS));
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new DownloadException("Descarga cancelada");
                    }
                }
            }
        }
        throw new DownloadException("No se pudo descargar " + label + ": " +
            (lastError != null ? lastError.getMessage() : "error desconocido"));
    }

    private static void downloadFile(String urlStr, File dest, ProgressListener listener, String label)
            throws IOException {
        long existing = dest.isFile() ? dest.length() : 0;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

        try {
            int code = conn.getResponseCode();
            boolean resuming;
            long remaining;

            if (code == 416 && existing > 0) {
                // Ya estaba descargado entero (se corto justo despues)
                listener.onFileProgress(label, 100);
                return;
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                resuming = true;
                remaining = conn.getContentLengthLong();
            } else if (code == HttpURLConnection.HTTP_OK) {
                // El servidor ignoro el Range (o no habia nada que reanudar): empezar de cero.
                resuming = false;
                existing = 0;
                remaining = conn.getContentLengthLong();
            } else {
                throw new IOException("HTTP " + code + " al descargar " + urlStr);
            }

            long total = existing + Math.max(remaining, 0);

            try (InputStream in = conn.getInputStream();
                 RandomAccessFile out = new RandomAccessFile(dest, "rw")) {
                if (resuming) {
                    out.seek(existing);
                } else {
                    out.setLength(0);
                    out.seek(0);
                }

                byte[] buffer = new byte[BUFFER_SIZE];
                long downloaded = existing;
                long lastReportMs = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReportMs > 400) {
                        lastReportMs = now;
                        int percent = total > 0 ? (int) Math.min(100, (downloaded * 100) / total) : 0;
                        listener.onFileProgress(label, percent);
                    }
                }
                listener.onFileProgress(label, 100);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String downloadText(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (out.size() > 1 << 20) throw new IOException("manifest.json demasiado grande");
                }
                return out.toString("UTF-8");
            }
        }
        finally {
            conn.disconnect();
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
