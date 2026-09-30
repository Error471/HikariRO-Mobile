package com.winlator.hikariro;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Varios de los binarios que Winlator distribuye ya compilados (box64, wine,
// wineserver...) traen grabadas por dentro rutas absolutas fijas que asumen
// que la app se llama "com.winlator" (el paquete original de Winlator). Como
// HikariRO Movil usa a proposito un applicationId distinto
// (com.hikarimovil.launcher, ver build.gradle), esas rutas no existen en
// este telefono y provocan fallos como "No such file or directory" al
// arrancar box64 o al crear sus carpetas temporales -aunque los archivos
// afectados si existan y con permisos correctos-.
//
// Esta clase busca esas rutas dentro de un binario ya extraido y las
// reescribe en el propio archivo para que apunten a un sitio real de
// nuestra propia app. Solo puede acortar o mantener el mismo tamano (no
// puede alargar el string sin reestructurar el binario entero), así que el
// prefijo nuevo debe caber en el hueco del prefijo viejo.
public abstract class HardcodedPathPatcher {
    private static final byte QUOTE = '"';

    // Busca todas las apariciones de oldPrefix dentro de file (como prefijo
    // de una cadena de texto) y las sustituye por newPrefix, dejando el
    // resto de cada cadena igual. Devuelve cuantas apariciones se
    // corrigieron, o -1 si algo fue mal.
    //
    // El final de cada cadena se busca como el primero de estos tres que
    // aparezca: un byte \0 (strings de binarios/ELF, terminadas en NUL), una
    // comilla " (strings de ficheros de texto tipo alsa.conf, donde el
    // valor va entre comillas), o el final del fichero.
    //
    // OJO: solo en el caso \0 se rellena el hueco sobrante con bytes \0
    // (igual que hasta ahora) -eso es seguro porque un string NUL-terminado
    // simplemente queda mas corto, y el resto del binario no depende de que
    // el fichero mida lo mismo byte a byte-. Cuando el final encontrado NO
    // es un \0 real (fichero de texto delimitado por comillas, o fin de
    // fichero sin NUL de por medio) NO se rellena con \0 -meter un \0 a
    // medio string de texto (antes de la comilla de cierre) corrompia el
    // fichero: eso es justo lo que le paso a alsa.conf en la v26, donde el
    // "final de cadena" se calculo como el final del fichero entero (alsa.
    // conf es texto plano sin ni un solo \0 real) y el relleno de \0 se comio
    // el ultimo byte real del fichero (el "]" que cierra el bloque
    // "@hooks"), dejandolo con una llave sin cerrar ("_toplevel_:9:2:
    // Unexpected end of file" en el log). En vez de rellenar, el fichero se
    // acorta lo que haga falta (el string nuevo siempre es igual o mas corto
    // que el viejo).
    public static int patchPrefix(File file, String oldPrefix, String newPrefix) {
        byte[] oldPrefixBytes = oldPrefix.getBytes(StandardCharsets.UTF_8);
        byte[] newPrefixBytes = newPrefix.getBytes(StandardCharsets.UTF_8);

        if (newPrefixBytes.length > oldPrefixBytes.length) {
            Log.e(HikariConfig.LOG_TAG, "HardcodedPathPatcher: '"+newPrefix+"' ("+newPrefixBytes.length
                +" bytes) es mas largo que '"+oldPrefix+"' ("+oldPrefixBytes.length+" bytes), no se puede parchear "+file+" sin reestructurar el binario");
            return -1;
        }

        try {
            byte[] data;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                data = new byte[(int)raf.length()];
                raf.readFully(data);
            }

            // Un ELF nunca puede cambiar de tamano (desplazaria secciones y
            // simbolos). Ej.: libfontconfig lleva un fonts.conf embebido con
            // comillas; tratarlo como texto acortaba el .so y lo corrompia.
            boolean isElf = data.length >= 4 && data[0] == 0x7f && data[1] == 'E' && data[2] == 'L' && data[3] == 'F';

            List<Integer> matches = new ArrayList<>();
            int from = 0;
            while (true) {
                int idx = indexOf(data, oldPrefixBytes, from);
                if (idx < 0) break;
                matches.add(idx);
                from = idx + 1;
            }

            if (matches.isEmpty()) {
                Log.i(HikariConfig.LOG_TAG, "HardcodedPathPatcher: '"+oldPrefix+"' no aparece en "+file+" (ya estaba parcheado, o esta version no lo necesita)");
                return 0;
            }

            // Se reconstruye el fichero entero en memoria (en vez de editar
            // in-place con RandomAccessFile) porque el caso "texto" puede
            // acortar el fichero, y eso desplaza la posicion de las
            // siguientes coincidencias.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.length);
            int cursor = 0;
            int patchedCount = 0;
            for (int matchPos : matches) {
                if (matchPos < cursor) continue; // solapaba con una coincidencia ya procesada

                int end = matchPos;
                while (end < data.length && data[end] != 0 && data[end] != QUOTE) end++;
                if (isElf) {
                    // En binarios solo vale el \0 como final de cadena.
                    end = matchPos;
                    while (end < data.length && data[end] != 0) end++;
                }
                boolean nulTerminated = end < data.length && data[end] == 0;
                if (isElf && !nulTerminated) continue;
                int oldFullLen = end - matchPos;

                int tailStart = matchPos + oldPrefixBytes.length;
                int tailLen = end - tailStart;
                byte[] newFull = new byte[newPrefixBytes.length + tailLen];
                System.arraycopy(newPrefixBytes, 0, newFull, 0, newPrefixBytes.length);
                System.arraycopy(data, tailStart, newFull, newPrefixBytes.length, tailLen);

                if (newFull.length > oldFullLen) {
                    Log.e(HikariConfig.LOG_TAG, "HardcodedPathPatcher: el reemplazo no cabe en "+file+" en la posicion "+matchPos
                        +" (necesita "+newFull.length+" bytes, hay "+oldFullLen+"), se deja sin tocar");
                    continue;
                }

                out.write(data, cursor, matchPos - cursor);
                out.write(newFull);
                if (nulTerminated) {
                    // Binario: se mantiene el tamano original relleno de \0
                    // (el string NUL-terminado simplemente queda mas corto).
                    int padLen = oldFullLen - newFull.length;
                    if (padLen > 0) out.write(new byte[padLen]);
                } // texto: no se rellena, el fichero se acorta lo que sobre.
                cursor = end;
                patchedCount++;
            }
            out.write(data, cursor, data.length - cursor);
            byte[] patched = out.toByteArray();

            if (patchedCount > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.seek(0);
                    raf.write(patched);
                    raf.setLength(patched.length);
                }
            }

            Log.i(HikariConfig.LOG_TAG, "HardcodedPathPatcher: "+patchedCount+" aparicion(es) de '"+oldPrefix+"' corregidas en "+file);
            return patchedCount;
        }
        catch (IOException e) {
            Log.e(HikariConfig.LOG_TAG, "HardcodedPathPatcher: fallo al parchear "+file, e);
            return -1;
        }
    }

    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i+j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
