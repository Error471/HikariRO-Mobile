package com.winlator.hikariro;

import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// El cliente guarda su resolucion en savedata/OptionInfo.lua (no en el
// registro). La copia que viene del PC trae 1768x992 en ventana, mas grande
// que el escritorio del contenedor. Antes de cada arranque se ajusta a la
// resolucion del contenedor, en pantalla completa (o ventana en 0,0).
public abstract class HikariOptionInfoFixer {
    public static void fit(File clientDir, String screenSize, boolean fullscreen) {
        File file = new File(clientDir, "savedata/OptionInfo.lua");
        if (!file.isFile()) return;

        String[] parts = screenSize.split("x");
        if (parts.length != 2) return;

        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String fixed = content;
            fixed = setValue(fixed, "WIDTH", parts[0]);
            fixed = setValue(fixed, "HEIGHT", parts[1]);
            fixed = setValue(fixed, "OLD_WIDTH", parts[0]);
            fixed = setValue(fixed, "OLD_HEIGHT", parts[1]);
            fixed = setValue(fixed, "ISFULLSCREENMODE", fullscreen ? "1" : "0");
            fixed = setValue(fixed, "Window_XPos", "0");
            fixed = setValue(fixed, "Window_YPos", "0");

            if (!fixed.equals(content)) {
                Files.write(file.toPath(), fixed.getBytes(StandardCharsets.UTF_8));
                Log.i(HikariConfig.LOG_TAG, "OptionInfo.lua ajustado a "+screenSize);
            }
        }
        catch (Exception e) {
            Log.e(HikariConfig.LOG_TAG, "No se pudo ajustar OptionInfo.lua", e);
        }
    }

    private static String setValue(String content, String key, String value) {
        Pattern pattern = Pattern.compile("(OptionInfoList\\[\""+Pattern.quote(key)+"\"\\]\\s*=\\s*)[^\\r\\n]*");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1)+value));
        return content;
    }
}
