package com.winlator.xenvironment;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.R;
import com.winlator.core.AppDefaults;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DownloadProgressDialog;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.hikariro.HardcodedPathPatcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class RootFSInstaller {
    // Subido de 23 a 24 para forzar una reinstalacion del rootfs y que se
    // apliquen los parches de HardcodedPathPatcher sobre wine/wineserver
    // (ver patchHardcodedWinlatorPackagePaths) en telefonos que ya tenian el
    // rootfs anterior instalado sin esos parches.
    //
    // Subido de 24 a 25: el mismo parche se quedaba corto. Dentro de
    // rootfs.tzst hay bastantes mas binarios con "/data/data/com.winlator"
    // grabado ademas de wineserver/ntdll.so -la lista completa (sacada
    // buscando ese texto dentro de todo rootfs.tzst ya descomprimido) incluye
    // practicamente todas las librerias del sistema, pero la gran mayoria son
    // solo restos de RPATH/metadatos de compilacion que no afectan en
    // ejecucion (la app ya arrancaba bien con esas sin tocarlas). De esa
    // lista, tres SI son rutas que de verdad se usan en tiempo de ejecucion y
    // quedaban sin corregir: nsiproxy.so (usa esa ruta para sus ficheros
    // temporales de interfaces de red -provoca el "err:nsi:poll_events bind
    // failed, errno 13" que se ve en el log-), y
    // libasound.so.2.0.0 + usr/share/alsa/alsa.conf (la libreria ALSA y su
    // fichero de configuracion, que provocan el aviso "ALSA lib ...
    // Cannot access file .../alsa.conf" en el log). Se corrigen aqui igual
    // que wineserver/ntdll.so.
    //
    // Subido de 25 a 26: el parche de la version 25 para libasound.so.2.0.0 /
    // alsa.conf quedo A MEDIAS (confirmado en el log: seguia sin encontrar
    // alsa.conf, solo que ahora con el nombre de paquete ya corregido). El
    // motivo: a diferencia de wineserver (que solo necesita crear una
    // carpeta temporal vacia en la ruta corta), estos SI necesitan
    // encontrar ficheros reales que existen dentro del rootfs extraido
    // (alsa.conf, las carpetas ucm2/ucm, etc.), y la ruta corta que usamos
    // como reemplazo ("/data/data/"+paquete, SIN "/files/rootfs") apunta a
    // un sitio donde esos ficheros no estan -estan dentro del rootfs, en
    // ".../files/rootfs/usr/share/alsa/...". La ruta larga correcta no cabe
    // en el hueco de 36 bytes del prefijo viejo (con "com.hikarimovil.
    // launcher" ya gastamos 35 de esos 36 bytes solo con la parte corta).
    // La solucion es la misma que ya se uso para el socket X11 de gladio/
    // vortek (ver XServerDisplayActivity.createShortSocketSymlink): en vez
    // de mover el fichero real, creamos enlaces simbolicos "usr" y "etc"
    // justo debajo de la ruta corta que SI apuntan al sitio real dentro del
    // rootfs. Ver createShortRootfsSymlinks, mas abajo.
    //
    // Subido de 26 a 27: bug en el propio parcheador (HardcodedPathPatcher),
    // no en esta clase, pero hace falta reinstalar el rootfs para que el
    // alsa.conf ya corrupto se regenere desde cero con el parche corregido.
    // El sintoma en la v26 era "alsa.conf may be old or corrupted" /
    // "_toplevel_:9:2:Unexpected end of file": alsa.conf es un fichero de
    // TEXTO (no un binario), y su unica ruta "/data/data/com.winlator/..."
    // va entre comillas, sin ningun byte \0 real en todo el fichero.
    // HardcodedPathPatcher buscaba el final de cada cadena como "el
    // siguiente \0", y al no haber ninguno se iba hasta el final del
    // fichero entero -el relleno de \0 que hace para dejar el fichero del
    // mismo tamano se comio entonces el ultimo byte REAL del fichero (el
    // "]" que cierra el bloque "@hooks"), dejando el JSON/config sin cerrar.
    // HardcodedPathPatcher.patchPrefix ahora distingue este caso (corta en
    // \0, en comilla " o en fin de fichero, lo que llegue antes) y para
    // texto no rellena con \0 -deja el fichero mas corto en vez de
    // corromperlo-. Ver el comentario de esa clase para el detalle.
    //
    // Subido de 27 a 28: CAUSA RAIZ del "nodrv_CreateWindow". libxcb.so
    // (dentro de rootfs.tzst) trae grabada la ruta del socket X11 del
    // paquete original ("/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X").
    // XOpenDisplay fallaba siempre, winex11.drv no inicializaba ("Initialization
    // of winex11.drv failed" en el log) y wine caia al driver nulo. La
    // "carrera" de lock_display_devices era solo una consecuencia: sin driver,
    // cada hilo reintenta cargarlo y rehace la GPU sintetica. Ver
    // patchHardcodedWinlatorPackagePaths (bloque de librerias X11).
    //
    // Subido de 28 a 29: la v28 corrompia libfontconfig (el parcheador lo
    // acortaba 1 byte por un fonts.conf embebido con comillas) y box64
    // fallaba en dlopen("libfontconfig.so.1"). HardcodedPathPatcher ya no
    // cambia nunca el tamano de un ELF; hay que reinstalar el rootfs.
    //
    // Subido de 29 a 30: glibc (libc.so.6, libresolv.so.2) busca sus ficheros
    // de configuracion en "/data/data/com.winlator/files/rootfs/etc/..."
    // (resolv.conf, hosts, nsswitch.conf, services...). Sin resolv.conf usa
    // 127.0.0.1 como DNS y ningun nombre de dominio resuelve: el juego entra
    // (conecta por IP) pero el patcher (patch.hikariro.com) da "Failed to
    // communicate with server". Se parchea SOLO el prefijo ".../rootfs/etc"
    // hacia el enlace corto "etc" de createShortRootfsSymlinks.
    public static final byte LATEST_VERSION = 30; // TODO increment it on rootfs update
    public static final byte UPDATE_WINEPREFIX_VERSION = 16; // set it if main wine version change
    public static final String FILENAME = "rootfs.tzst";

    // wine y wineserver (dentro de rootfs.tzst) traen grabadas rutas
    // absolutas fijas que asumen el paquete original "com.winlator" (igual
    // que le pasaba a box64, ver Box64Utils.fixInterpreterPath). En
    // concreto, wineserver necesita crear una carpeta temporal para su
    // socket de comunicacion (".wine-<uid>") y usa esa ruta hardcodeada en
    // vez de calcularla a partir del paquete real, asi que el mkdir fallaba
    // con "No such file or directory" porque "/data/data/com.winlator" ni
    // siquiera existe en este telefono.
    //
    // A diferencia del interprete ELF de box64, aqui no podemos alargar la
    // ruta grabada (son strings de texto normales, no un campo de la
    // cabecera ELF que se pueda mover). Por eso usamos como reemplazo nuestra
    // carpeta de datos SIN el "/files/rootfs" final (mas corta), que es lo
    // bastante corta para caber en el hueco original y sigue siendo un sitio
    // real y propio de esta app donde puede crear esa carpeta temporal.
    private static void patchHardcodedWinlatorPackagePaths(Context context, File rootDir) {
        String oldPrefix = "/data/data/com.winlator/files/rootfs";
        String newPrefix = "/data/data/"+context.getPackageName();

        File wineserver = new File(rootDir, "/opt/wine/bin/wineserver");
        File ntdll = new File(rootDir, "/opt/wine/lib/wine/x86_64-unix/ntdll.so");
        HardcodedPathPatcher.patchPrefix(wineserver, oldPrefix, newPrefix);
        HardcodedPathPatcher.patchPrefix(ntdll, oldPrefix, newPrefix);

        // nsiproxy.so (el proveedor de wine para info de red) guarda su
        // propia ruta temporal ("/tmp/ifaddrs") con el mismo prefijo fijo;
        // sin esto falla al hacer bind con errno 13 (EACCES) al arrancar.
        File nsiproxy = new File(rootDir, "/opt/wine/lib/wine/x86_64-unix/nsiproxy.so");
        HardcodedPathPatcher.patchPrefix(nsiproxy, oldPrefix, newPrefix);

        // La libreria ALSA (usada por wine para el audio) y su fichero de
        // configuracion tienen el mismo problema: sin esto, alsa-lib no
        // encuentra su propio alsa.conf ("Cannot access file ...") y varias
        // rutas internas (ucm2, dmix, etc.) tampoco existen de verdad.
        File libasound = new File(rootDir, "/usr/lib/libasound.so.2.0.0");
        File alsaConf = new File(rootDir, "/usr/share/alsa/alsa.conf");
        HardcodedPathPatcher.patchPrefix(libasound, oldPrefix, newPrefix);
        HardcodedPathPatcher.patchPrefix(alsaConf, oldPrefix, newPrefix);

        // Librerias X11: libxcb trae grabada la ruta del socket del XServer
        // (".../tmp/.X11-unix/X<n>"); con el prefijo corto llega al enlace que
        // crea XServerDisplayActivity.createShortSocketSymlink. libX11,
        // libXcursor y fontconfig traen rutas a usr/share y etc, que resuelven
        // por los enlaces "usr"/"etc" de createShortRootfsSymlinks.
        String[] x11Libs = {
            "/usr/lib/libxcb.so.1.1.0",
            "/usr/lib/libX11.so.6.4.0",
            "/usr/lib/libXcursor.so.1.0.2",
            "/usr/lib/libfontconfig.so.1.14.0"
        };
        for (String lib : x11Libs) {
            File libFile = new File(rootDir, lib);
            if (libFile.isFile()) HardcodedPathPatcher.patchPrefix(libFile, oldPrefix, newPrefix);
        }

        // glibc: solo las rutas de /etc (DNS, hosts, servicios, zona horaria...)
        String[] glibcLibs = {"/usr/lib/libc.so.6", "/usr/lib/libresolv.so.2"};
        for (String lib : glibcLibs) {
            File libFile = new File(rootDir, lib);
            if (libFile.isFile()) HardcodedPathPatcher.patchPrefix(libFile, oldPrefix+"/etc", newPrefix+"/etc");
        }

        // Nos aseguramos de que exista la carpeta que esa ruta corregida
        // necesita, ya que wineserver solo crea el ultimo tramo (".wine-
        // <uid>") y no toda la jerarquia de carpetas.
        File tmpDir = new File(context.getDataDir(), "tmp");
        if (!tmpDir.isDirectory()) tmpDir.mkdirs();

        createShortRootfsSymlinks(context, rootDir);
    }

    // alsa.conf y libasound.so.2.0.0 necesitan que existan de verdad, en la
    // ruta corta parcheada, las carpetas "usr" (alsa.conf, usr/share/alsa/
    // ucm2, usr/share/alsa/ucm) y "etc" (usr/share/alsa/alsa.conf carga por
    // dentro un fichero de ahi: etc/alsa/conf.d/android_aserver.conf). Como
    // no cabe la ruta completa (ver el comentario de LATEST_VERSION = 26),
    // en vez de copiar esas carpetas creamos un enlace simbolico corto que
    // apunta a la carpeta real correspondiente dentro del rootfs -exactamente
    // la misma idea que createShortSocketSymlink usa para el socket X11 de
    // gladio/vortek, solo que aqui enlazamos carpetas normales en vez de un
    // socket-.
    //
    // A proposito NO enlazamos "lib" (aunque libasound.so.2.0.0 tambien
    // tiene un par de apariciones con esa ruta, para su carpeta de plugins
    // "lib/alsa-lib"): "/data/data/<paquete>/lib" no es una carpeta libre,
    // es un enlace que gestiona el propio Android hacia las bibliotecas
    // nativas del APK (libwinlator.so, etc.). Tocarla podria romper la
    // carga de esas bibliotecas. Sin esa carpeta, ALSA simplemente no
    // encuentra los plugins opcionales (dmix y similares) y se queda con
    // sonido silenciado o degradado, pero no debería impedir que la app
    // arranque -el problema real que estamos depurando (nodrv_CreateWindow)
    // es de video, no de audio-.
    private static void createShortRootfsSymlinks(Context context, File rootDir) {
        File dataDir = context.getDataDir();

        File shortUsrDir = new File(dataDir, "usr");
        if (!FileUtils.isSymlink(shortUsrDir)) {
            FileUtils.delete(shortUsrDir);
            FileUtils.symlink(new File(rootDir, "usr"), shortUsrDir);
        }

        File shortEtcDir = new File(dataDir, "etc");
        if (!FileUtils.isSymlink(shortEtcDir)) {
            FileUtils.delete(shortEtcDir);
            FileUtils.symlink(new File(rootDir, "etc"), shortEtcDir);
        }
    }

    private static void resetContainerRFSVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String rfsVersion = container.getExtra("rfsVersion");
            String wineVersion = container.getWineVersion();
            if (!rfsVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(rfsVersion) <= UPDATE_WINEPREFIX_VERSION) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("rfsVersion", null);
            container.saveData();
        }
    }

    public static void install(final AppCompatActivity activity) {
        AppUtils.keepScreenOn(activity);
        RootFS rootFS = RootFS.find(activity);
        final File rootDir = rootFS.getRootDir();

        AppDefaults.resetPreferenceVersions(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final long contentLength = TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir);
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                rootFS.createRFSVersionFile(LATEST_VERSION);
                resetContainerRFSVersions(activity);
                patchHardcodedWinlatorPackagePaths(activity, rootDir);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
        });
    }

    public static void installIfNeeded(final AppCompatActivity activity) {
        RootFS rootFS = RootFS.find(activity);
        if (!rootFS.isValid() || rootFS.getVersion() < LATEST_VERSION) install(activity);
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home") || name.equals("opt")) {
                            if (name.equals("opt")) clearOptDir(file);
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }

    public static void generateCompactContainerPattern(final AppCompatActivity activity) {
        AppUtils.keepScreenOn(activity);
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        Executors.newSingleThreadExecutor().execute(() -> {
            File[] srcFiles, dstFiles;
            File rootDir = RootFS.find(activity).getRootDir();
            File wineSystem32Dir = new File(rootDir, "/opt/wine/lib/wine/x86_64-windows");
            File wineSysWoW64Dir = new File(rootDir, "/opt/wine/lib/wine/i386-windows");

            File containerPatternDir = new File(activity.getCacheDir(), "container_pattern");
            FileUtils.delete(containerPatternDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "container_pattern.tzst", containerPatternDir);

            File containerSystem32Dir = new File(containerPatternDir, ".wine/drive_c/windows/system32");
            File containerSysWoW64Dir = new File(containerPatternDir, ".wine/drive_c/windows/syswow64");

            dstFiles = containerSystem32Dir.listFiles();
            srcFiles = wineSystem32Dir.listFiles();

            ArrayList<String> system32Files = new ArrayList<>();
            ArrayList<String> syswow64Files = new ArrayList<>();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) system32Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            dstFiles = containerSysWoW64Dir.listFiles();
            srcFiles = wineSysWoW64Dir.listFiles();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) syswow64Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            try {
                JSONObject data = new JSONObject();

                JSONArray system32JSONArray = new JSONArray();
                for (String name : system32Files) {
                    FileUtils.delete(new File(containerSystem32Dir, name));
                    system32JSONArray.put(name);
                }
                data.put("system32", system32JSONArray);

                JSONArray syswow64JSONArray = new JSONArray();
                for (String name : syswow64Files) {
                    FileUtils.delete(new File(containerSysWoW64Dir, name));
                    syswow64JSONArray.put(name);
                }
                data.put("syswow64", syswow64JSONArray);

                FileUtils.writeString(new File(activity.getCacheDir(), "common_dlls.json"), data.toString());

                File outputFile = new File(activity.getCacheDir(), "container_pattern.tzst");
                FileUtils.delete(outputFile);
                TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(containerPatternDir, ".wine"), outputFile, 22);

                FileUtils.delete(containerPatternDir);
                preloaderDialog.closeOnUiThread();
            }
            catch (JSONException e) {}
        });
    }
}
