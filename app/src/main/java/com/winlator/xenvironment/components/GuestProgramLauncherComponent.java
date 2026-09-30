package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.box64.Box64Utils;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.hikariro.HikariConfig;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            extractBox64File();
            copyDefaultBox64RCFile();
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    // PID del ultimo proceso guest lanzado (box64 + el ejecutable de Windows),
    // para poder consultar su estado (RUNNING/SLEEPING/WAITING/ZOMBIE...)
    // desde fuera sin depender del recorrido completo de /proc (ver
    // ProcessHelper.getChildProcesses(), que tiene un fallo de carrera).
    public static int getCurrentPid() {
        synchronized (lock) {
            return pid;
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;

        // Registro del comando exacto que se lanza: si el juego no arranca,
        // esto confirma de un vistazo si el ejecutable/ruta son los
        // correctos antes de mirar mas abajo en el log.
        Log.i(HikariConfig.LOG_TAG, "Lanzando proceso guest: "+command);

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            Log.i(HikariConfig.LOG_TAG, "Proceso guest termino con status="+status);
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private void extractBox64File() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = preferences.getString("box64_version", DefaultVersion.BOX64);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        // La preferencia "ya extraje esta version" puede quedar desincronizada
        // del disco de verdad: si el RootFS se reinstala (por ejemplo al subir
        // RootFSInstaller.LATEST_VERSION) se borra y recrea desde cero, pero
        // esta preferencia no se entera y sigue diciendo "ya esta" aunque el
        // binario ya no exista. Eso es justo lo que causaba el cuelgue en
        // "Starting up...": box64 no arrancaba (error=2, No such file or
        // directory) porque el ejecutable simplemente no estaba en el
        // telefono. Comprobamos tambien el archivo real, no solo la
        // preferencia.
        File box64Binary = new File(rootFS.getRootDir(), "/usr/local/bin/box64");
        boolean existiaAntes = box64Binary.isFile();

        Log.i(HikariConfig.LOG_TAG, "extractBox64File: box64Version="+box64Version
            +" currentBox64Version="+currentBox64Version+" existiaAntes="+existiaAntes
            +" ruta="+box64Binary.getAbsolutePath());

        if (!box64Version.equals(currentBox64Version) || !existiaAntes) {
            Log.i(HikariConfig.LOG_TAG, "extractBox64File: extrayendo box64...");
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
            Log.i(HikariConfig.LOG_TAG, "extractBox64File: tras extraer, existe="+box64Binary.isFile()
                +" tamano="+box64Binary.length()+" puedeEjecutar="+box64Binary.canExecute());
        }
        else {
            Log.i(HikariConfig.LOG_TAG, "extractBox64File: se salta la extraccion (ya estaba)");
        }

        // La causa real del cuelgue: box64 trae grabada una ruta absoluta
        // fija para su interprete ELF (el enlazador dinamico), pensada para
        // cuando la app se llama "com.winlator". Como este fork usa a
        // proposito un applicationId distinto, esa ruta no existe en este
        // telefono y el exec() falla con "No such file or directory" aunque
        // el archivo box64 si este ahi. La corregimos para que apunte al
        // enlazador real dentro de nuestro propio rootDir. Ver el comentario
        // completo en Box64Utils.fixInterpreterPath().
        //
        // Importante: para reescribir el binario hace falta permiso de
        // escritura. Si una version anterior de esta app ya se lo quito
        // (mas abajo), lo restauramos primero; si no, esta linea no hace
        // nada (el archivo recien extraido ya es escribible).
        if (box64Binary.isFile()) {
            box64Binary.setWritable(true, false);
            String correctInterpreter = rootFS.getRootDir().getAbsolutePath()+"/lib/ld-linux-aarch64.so.1";
            Box64Utils.fixInterpreterPath(box64Binary, correctInterpreter);
        }

        // Ademas, desde Android 10 el sistema puede bloquear ejecutar un
        // archivo que la propia app todavia pueda escribir (proteccion W^X).
        // La extraccion deja el binario en 0771 (rwx para el dueno, es decir
        // TAMBIEN con permiso de escritura). Quitamos ese permiso de
        // escritura por si acaso, aunque no era la causa principal del
        // cuelgue (el interprete incorrecto lo era).
        if (box64Binary.isFile() && box64Binary.canWrite()) {
            boolean ok = box64Binary.setWritable(false, false);
            Log.i(HikariConfig.LOG_TAG, "extractBox64File: quitando permiso de escritura de box64 (W^X), resultado="+ok
                +" puedeEjecutarAhora="+box64Binary.canExecute());
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        // HikariRO Movil no muestra la pantalla de ajustes de Winlator donde
        // normalmente se elige este nivel a mano (normalmente 0, silenciado).
        // Como el objetivo principal del fork es funcionar en el mayor
        // numero posible de telefonos distintos, forzamos aqui el nivel
        // maximo de diagnostico siempre: sin esto, un cuelgue o crash de
        // box64 al arrancar el juego (justo el problema que estamos
        // depurando ahora) no dejaria ningun rastro revisable en Logcat.
        int box64Logs = HikariConfig.DIAGNOSTIC_MODE ? 1 : 0;
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_DYNACACHE", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        // DESCARTADO (se deja el razonamiento por historial): se probo
        // "BOX64_DYNAREC=0" (desactiva el JIT de box64 por completo,
        // fuerza el interprete puro) para comprobar si el tamano de
        // ventana corrupto de raghikari.exe (el alto sale siempre
        // "0x729e4f6b", segun el canal "relay" ya puesto en los propios
        // argumentos que raghikari.exe pasa a CreateWindowExA) era un
        // fallo del compilador JIT de box64. Con el interprete puro (muy
        // lento: el mismo punto que antes tardaba ~20s tardo mas de 8
        // minutos en alcanzarse) el valor salio exactamente igual de
        // corrupto, byte a byte -eso descarta a box64 del todo (con o sin
        // JIT da lo mismo). El fallo real esta mas arriba: nuestro XServer
        // en Java no implementa RandR/Xinerama, asi que wine nunca puede
        // leer la configuracion real de monitores
        // ("lock_display_devices Failed to read display config"), cae a
        // su monitor sintetico "Wine GPU", y ese fallback (no box64) es lo
        // que hace que ni siquiera la ventana de explorer.exe cargue un
        // driver real (nodrv_CreateWindow) -y de ahi, por una via que aun
        // no tenemos clara, sale el alto corrupto de raghikari.exe.
        //
        // envVars.put("BOX64_DYNAREC", "0");

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}