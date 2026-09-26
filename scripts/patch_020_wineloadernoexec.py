from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("2000")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.20.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.19", "0.20"), encoding="utf-8")

# Root cause found by reading ntdll.so's own strings and matching the exact
# device error: after Box64 successfully dlopens ntdll.so and resolves
# __wine_main, Wine's own startup code (inside ntdll.so, not in bin/wine)
# tries to re-exec itself into "the wine loader" (historically wine64-preloader,
# for low-address-space reservation) via execve(). That binary does not exist
# in this rootfs (confirmed: wine64-preloader and wine-preloader are both
# absent, upstream, not a corrupted install), so the exec fails and ntdll.so
# prints its own hardcoded "could not exec the wine loader" and aborts --
# matching the device logs exactly (confirmed via ntdll.so's string table).
#
# This re-exec is redundant here: Box64 already performs the equivalent
# address-space reservation itself (visible in the Box64 trace as "WINE
# prereserve of 0x...:0x... done", logged before the crash). Wine supports
# skipping its own re-exec via the WINELOADERNOEXEC environment variable --
# a documented escape hatch for exactly this situation (running under a
# binary translator that already handles prereserve). Set it so Wine
# continues in the current (Box64-managed) process instead of trying, and
# failing, to exec a preloader binary that was never going to exist here.
guest = root / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
t = guest.read_text(encoding="utf-8")

old = '        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);'
new = old + '\n        envVars.put("WINELOADERNOEXEC", "1");'

if old not in t:
    raise RuntimeError("No se encontro el bloque de envVars base en execGuestProgram")
t = t.replace(old, new, 1)
guest.write_text(t, encoding="utf-8")

print("0.20 patch applied: set WINELOADERNOEXEC=1 so Wine skips its (unavailable) preloader re-exec")
