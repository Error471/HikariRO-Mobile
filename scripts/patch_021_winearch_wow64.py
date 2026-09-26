from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("2100")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.21.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.20", "0.21"), encoding="utf-8")

# 0.20 fixed the FIRST crash ("could not exec the wine loader") by setting
# WINELOADERNOEXEC=1, which works by making ntdll.so's own loader/main.c skip
# calling check_command_line()/reexec_loader() entirely (see wine source,
# dlls/ntdll/unix/loader.c and loader/main.c). That got us further, but it
# also means Wine never receives "preload_info" (the struct describing which
# address ranges are already reserved), so its own virtual_alloc_first_teb()
# tries to freshly map the fixed "shared user data" page (KUSER_SHARED_DATA,
# 0x7ffe0000) from scratch -- and that now conflicts with Box64's own "WINE
# prereserve" placeholder mappings over that exact address, producing the
# NEW crash: "failed to map the shared user data: c0000018".
#
# Reading Wine's actual source (get_alternate_wineloader() in loader.c)
# shows *why* the original reexec attempt failed in the first place: on
# every startup, a win64 Wine build unconditionally tries to find/exec an
# "alternate" 32-bit companion "wine" loader binary (for legacy 32-bit-app
# support) -- UNLESS the environment variable WINEARCH is set to "wow64",
# in which case get_alternate_wineloader() returns NULL immediately and
# Wine instead re-execs *itself* (the very same wine64 binary already
# running). Box64's own log strings ("Wine64 detected", "WINE prereserve of
# ... done") and reports from other Box64/Wine64-WOW64 setups showing lines
# like "Wine preloader detected, loading <path> directly" indicate Box64
# has built-in interception specifically for that self-reexec pattern,
# handling the address-space handoff internally instead of the (missing,
# and per Box64's own WINE.md unnecessary for a WOW64 build) external
# 32-bit "wine" companion binary.
#
# So: drop the 0.20 WINELOADERNOEXEC override (it prevents Wine from ever
# attempting the reexec that Box64 is designed to catch) and set
# WINEARCH=wow64 instead, matching what this rootfs's Wine build actually
# is (x86_64 Wine WOW64, confirmed via DefaultVersion.java/BOX64 docs).
guest = root / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
t = guest.read_text(encoding="utf-8")

old = '        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);\n        envVars.put("WINELOADERNOEXEC", "1");'
new = '        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);\n        envVars.put("WINEARCH", "wow64");'

if old not in t:
    raise RuntimeError("No se encontro el bloque de envVars de la 0.20 (WINELOADERNOEXEC) en execGuestProgram")
t = t.replace(old, new, 1)
guest.write_text(t, encoding="utf-8")

print("0.21 patch applied: removed WINELOADERNOEXEC, set WINEARCH=wow64 so Box64 handles the real Wine self-reexec/preload handoff")
