package com.winlator.box64;

import android.util.Log;

import com.winlator.core.ArrayUtils;
import com.winlator.core.StreamUtils;
import com.winlator.hikariro.HikariConfig;
import com.winlator.xenvironment.RootFS;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

public abstract class Box64Utils {
    public static String extractBinVersion(Context context) {
        File binFile = new File(RootFS.find(context).getRootDir(), "/usr/local/bin/box64");
        try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(binFile), StreamUtils.BUFFER_SIZE)) {
            int bytesRead;
            byte[] buffer = new byte[4096];
            final byte[] str = {'B','o','x','6','4',' ','a','r','m','6','4',' ','v'};
            while ((bytesRead = inStream.read(buffer)) != -1) {
                int index = ArrayUtils.indexOf(buffer, 0, bytesRead, str);
                if (index != ArrayUtils.INDEX_NOT_FOUND) {
                    int start = index + str.length;
                    int end = ArrayUtils.indexOf(buffer, start, bytesRead, (byte)' ');
                    return end != ArrayUtils.INDEX_NOT_FOUND ? new String(buffer, start, end - start) : "";
                }
            }
        }
        catch (IOException e) {}
        return "";
    }

    // El binario box64 que distribuye Winlator viene compilado con una ruta
    // absoluta FIJA como interprete ELF (el enlazador dinamico que el propio
    // kernel usa al hacer exec()):
    //   /data/data/com.winlator/files/rootfs/lib/ld-linux-aarch64.so.1
    // Esa ruta solo existe de verdad si la app esta instalada exactamente
    // con el applicationId "com.winlator". HikariRO Movil usa a proposito
    // un applicationId distinto ("com.hikarimovil.launcher", ver
    // build.gradle) para poder instalarse sin chocar con una instalacion ya
    // existente de Winlator en el mismo telefono. Como consecuencia, esa
    // ruta hardcodeada no existe en este telefono: el archivo box64 SI
    // existe y tiene permisos correctos, pero el kernel no puede localizar
    // su interprete al ejecutarlo, y eso hace que ProcessBuilder falle con
    // "No such file or directory" -el mismo mensaje que si el propio box64
    // no existiera- justo lo que se veia en el cuelgue de "Starting up...".
    //
    // Aqui reescribimos, dentro del propio binario ELF, esa ruta para que
    // apunte al lugar real donde vive el enlazador EN ESTE telefono
    // (calculado a partir del rootDir real de la app, que ya tiene en
    // cuenta el applicationId y el perfil de usuario correctos), en vez de
    // asumir el paquete original de Winlator.
    public static boolean fixInterpreterPath(File elfFile, String correctInterpreterPath) {
        try (RandomAccessFile raf = new RandomAccessFile(elfFile, "rw")) {
            byte[] header = new byte[64];
            raf.seek(0);
            raf.readFully(header);
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[4] != 2) {
                Log.e(HikariConfig.LOG_TAG, "fixInterpreterPath: cabecera ELF64 no reconocida en "+elfFile);
                return false;
            }

            long ePhOff = readLE(header, 32, 8);
            int ePhEntSize = (int)readLE(header, 54, 2);
            int ePhNum = (int)readLE(header, 56, 2);

            for (int i = 0; i < ePhNum; i++) {
                long entryOffset = ePhOff + (long)i * ePhEntSize;
                byte[] entry = new byte[ePhEntSize];
                raf.seek(entryOffset);
                raf.readFully(entry);

                long pType = readLE(entry, 0, 4);
                if (pType != 3) continue; // PT_INTERP

                long pOffset = readLE(entry, 8, 8);
                long pFilesz = readLE(entry, 32, 8);

                byte[] currentBytes = new byte[(int)pFilesz];
                raf.seek(pOffset);
                raf.readFully(currentBytes);
                int nullIdx = 0;
                while (nullIdx < currentBytes.length && currentBytes[nullIdx] != 0) nullIdx++;
                String current = new String(currentBytes, 0, nullIdx, "UTF-8");

                if (current.equals(correctInterpreterPath)) {
                    Log.i(HikariConfig.LOG_TAG, "fixInterpreterPath: el interprete ya es correcto ("+current+")");
                    return true;
                }

                Log.i(HikariConfig.LOG_TAG, "fixInterpreterPath: interprete incorrecto ('"+current+"'), corrigiendo a '"+correctInterpreterPath+"'");

                byte[] newBytes = (correctInterpreterPath+"\0").getBytes("UTF-8");
                long newOffset = raf.length();
                raf.seek(newOffset);
                raf.write(newBytes);

                writeLE(raf, entryOffset+8, newOffset, 8);
                writeLE(raf, entryOffset+32, newBytes.length, 8);
                writeLE(raf, entryOffset+40, newBytes.length, 8);

                Log.i(HikariConfig.LOG_TAG, "fixInterpreterPath: interprete corregido correctamente");
                return true;
            }

            Log.e(HikariConfig.LOG_TAG, "fixInterpreterPath: no se encontro segmento PT_INTERP en "+elfFile);
            return false;
        }
        catch (Exception e) {
            Log.e(HikariConfig.LOG_TAG, "fixInterpreterPath: fallo al parchear "+elfFile, e);
            return false;
        }
    }

    private static long readLE(byte[] data, int offset, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            value |= ((long)(data[offset+i] & 0xFF)) << (8*i);
        }
        return value;
    }

    private static void writeLE(RandomAccessFile raf, long offset, long value, int size) throws IOException {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte)((value >> (8*i)) & 0xFF);
        }
        raf.seek(offset);
        raf.write(bytes);
    }
}
