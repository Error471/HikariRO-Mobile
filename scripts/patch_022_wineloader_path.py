from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("2200")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.22.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.21", "0.22"), encoding="utf-8")

# Real root cause, found via a Debian bug report (#1029536) about this EXACT
# error text ("wine: could not exec the wine loader"), which traces it to
# upstream Wine commit "ntdll: Always use the name of the current loader to
# exec a new process." -- since that change, Wine's ntdll re-execs using
# whatever path it believes IS the currently running loader (normally
# resolved via something like readlink("/proc/self/exe")), not a generic
# PATH search. Under Box64, the actual OS-level running process is box64
# itself (the guest "wine" ELF is just data box64 interprets/dynarecs), so
# that self-path resolution can come back wrong/empty from inside the guest,
# and Wine then tries to exec a bogus path -- ENOENT -- "could not exec the
# wine loader", matching our logs exactly.
#
# Wine has a real, documented escape hatch for exactly this: the WINELOADER
# environment variable overrides that auto-detected path. Setting it to the
# real, absolute path of this rootfs's own wine binary means the re-exec
# targets a file that actually exists, so it can succeed for real -- which
# also means Wine goes through its normal, complete preload/address-space
# handoff instead of skipping it (as 0.20's WINELOADERNOEXEC did), avoiding
# the "failed to map the shared user data" conflict that skip caused.
#
# 0.21's WINEARCH=wow64 change did not help (confirmed on-device: identical
# "could not exec the wine loader" failure came back), so it is replaced
# here rather than kept.
guest = root / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
t = guest.read_text(encoding="utf-8")

old = '        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);\n        envVars.put("WINEARCH", "wow64");'
new = '        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);\n        envVars.put("WINELOADER", rootDir+rootFS.getWinePath()+"/bin/wine");\n        HikariDiagnostics.record(environment.getContext(), "WINELOADER=" + rootDir+rootFS.getWinePath()+"/bin/wine");'

if old not in t:
    raise RuntimeError("No se encontro el bloque de envVars de la 0.21 (WINEARCH) en execGuestProgram")
t = t.replace(old, new, 1)
guest.write_text(t, encoding="utf-8")

print("0.22 patch applied: set WINELOADER to the rootfs's real wine binary path so Wine's self-reexec actually finds it")
