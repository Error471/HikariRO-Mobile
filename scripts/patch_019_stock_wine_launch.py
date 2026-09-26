from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("1900")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.19.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.18", "0.19"), encoding="utf-8")

# 0.13/0.14/0.15 replaced the stock guestExecutable ("wine explorer /desktop=...
# C:\windows\winhandler.exe ...") with a hand-picked Wine loader binary whenever
# it detected winhandler.exe, on the theory that Android could not exec the
# real x86_64 loader directly. The device logs show that theory was wrong:
# Box64 already detects Wine's own preloader convention on its own (it embeds
# "wine-preloader"/"wine64-preloader" special-casing and loads the real target
# directly when neither exists), and forcing a specific loader file here
# bypasses that detection instead of helping it -- it just reproduces the same
# "wine: could not exec the wine loader" failure Wine itself prints when it
# can't find its preloader, confirmed missing from the bundled rootfs (both
# wine64-preloader and wine-preloader are absent from upstream's own
# rootfs.tzst, so this is expected, not a broken/corrupted install).
#
# Revert to the stock invocation: hand Box64 the guestExecutable exactly as
# any other Winlator game does, and let its own preloader detection do its job.
guest = root / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
t = guest.read_text(encoding="utf-8")

old = '''        String launchExecutable = guestExecutable;
        if (guestExecutable != null && guestExecutable.contains("winhandler.exe")) {
            File wineBinary = new File(rootDir, rootFS.getWinePath()+"/bin/wine");
            File wineServer = new File(rootDir, rootFS.getWinePath()+"/bin/wineserver");
            HikariDiagnostics.record(environment.getContext(), "0.13 direct Wine launch habilitado");
            HikariDiagnostics.record(environment.getContext(), "wine exists=" + wineBinary.isFile());
            HikariDiagnostics.record(environment.getContext(), "wineserver exists=" + wineServer.isFile());
            File wine64 = new File(rootDir, rootFS.getWinePath()+"/bin/wine64");
            File wine64Preloader = new File(rootDir, rootFS.getWinePath()+"/bin/wine64-preloader");
            File winePreloader = new File(rootDir, rootFS.getWinePath()+"/bin/wine-preloader");
            File wineRealLoader = new File(rootDir, rootFS.getWinePath()+"/lib/wine/x86_64-unix/wine");
            File wineServerReal = new File(rootDir, rootFS.getWinePath()+"/bin/wineserver");
            HikariDiagnostics.record(environment.getContext(), "0.15 loader Wine real habilitado");
            HikariDiagnostics.record(environment.getContext(), "wine launcher=" + wineBinary.getAbsolutePath() + " exists=" + wineBinary.isFile() + " size=" + (wineBinary.isFile() ? wineBinary.length() : 0));
            HikariDiagnostics.record(environment.getContext(), "wine real loader=" + wineRealLoader.getAbsolutePath() + " exists=" + wineRealLoader.isFile() + " size=" + (wineRealLoader.isFile() ? wineRealLoader.length() : 0) + " exec=" + wineRealLoader.canExecute());
            HikariDiagnostics.record(environment.getContext(), "wineserver=" + wineServerReal.getAbsolutePath() + " exists=" + wineServerReal.isFile() + " size=" + (wineServerReal.isFile() ? wineServerReal.length() : 0));
            HikariDiagnostics.record(environment.getContext(), "wine64=" + wine64.getAbsolutePath() + " exists=" + wine64.isFile());
            HikariDiagnostics.record(environment.getContext(), "wine64-preloader=" + wine64Preloader.getAbsolutePath() + " exists=" + wine64Preloader.isFile());
            HikariDiagnostics.record(environment.getContext(), "wine-preloader=" + winePreloader.getAbsolutePath() + " exists=" + winePreloader.isFile());
            File selectedWineLoader = wineRealLoader.isFile() ? wineRealLoader : (wine64.isFile() ? wine64 : wineBinary);
            launchExecutable = selectedWineLoader.getAbsolutePath() + " cmd /c \\"E: && cd \\\\HikariRO && raghikari.exe\\"";
            HikariDiagnostics.record(environment.getContext(), "Wine loader seleccionado=" + selectedWineLoader.getAbsolutePath());
        }'''

new = '''        String launchExecutable = guestExecutable;
        HikariDiagnostics.record(environment.getContext(), "0.19 lanzamiento estandar de Wine (sin override de loader; Box64 gestiona el preloader)");'''

if old not in t:
    raise RuntimeError("No se encontro el bloque de override del loader de Wine (0.13-0.15)")
t = t.replace(old, new, 1)
guest.write_text(t, encoding="utf-8")

print("0.19 patch applied: reverted to stock Wine invocation, letting Box64's own preloader detection run")
