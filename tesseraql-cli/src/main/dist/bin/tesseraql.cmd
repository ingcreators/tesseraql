@echo off
rem TesseraQL CLI launcher (fat jar). Requires a JDK 25+ on PATH.
rem Pass extra JVM options via TESSERAQL_JAVA_OPTS (e.g. proxy or truststore settings). They are
rem applied after the defaults below, so any default can be overridden.
setlocal enabledelayedexpansion
set "DIR=%~dp0"

rem JVM defaults (docs/jvm-baseline.md decision 4), measured on the example app:
rem   -XX:+UseCompactObjectHeaders           live heap -7%%, RSS -5%% (default from JDK 27)
rem   a CDS archive                          time-to-ready -25%%, RSS -20%%
rem   --sun-misc-unsafe-memory-access=allow  silences the three lines Netty's use of
rem                                          sun.misc.Unsafe prints at every start on JDK 25
set "OPTS=-XX:+UseCompactObjectHeaders --sun-misc-unsafe-memory-access=allow"

rem The base classpath, extensible in one documented place (docs/module-channel.md decision 6):
rem a jar in lib\ext\, or a path in TESSERAQL_CLASSPATH, joins it here — that is how a framework
rem database driver reaches the stack-scoped pools, which resolve through DriverManager and cannot
rem be served by any application's module channel.
set "CP=%DIR%..\lib\tesseraql.jar"
set "FINGERPRINT="
for %%J in ("%DIR%..\lib\tesseraql.jar") do set "FINGERPRINT=%%~zJ"
if exist "%DIR%..\lib\ext\" (
  for %%J in ("%DIR%..\lib\ext\*.jar") do (
    set "CP=!CP!;%%~fJ"
    set "FINGERPRINT=!FINGERPRINT!-%%~nJ%%~zJ"
  )
)
if not "%TESSERAQL_CLASSPATH%"=="" set "CP=!CP!;%TESSERAQL_CLASSPATH%"

rem The archive is tied to the classpath it was built from, and a changed classpath does not cause
rem a rebuild — the JVM quietly stops using it — so the classpath's fingerprint names the file and
rem an upgrade, or an added extension jar, lands on a new one. -Xlog:cds=error keeps the writing
rem run from listing the classes it skipped, while still reporting an archive the JVM refuses.
if not "%LOCALAPPDATA%"=="" if not "%FINGERPRINT%"=="" (
  if not exist "%LOCALAPPDATA%\tesseraql\" mkdir "%LOCALAPPDATA%\tesseraql" 2>nul
  if exist "%LOCALAPPDATA%\tesseraql\" (
    if not exist "%LOCALAPPDATA%\tesseraql\cds-!FINGERPRINT!.jsa" del /q "%LOCALAPPDATA%\tesseraql\cds-*.jsa" 2>nul
    set "OPTS=!OPTS! -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=%LOCALAPPDATA%\tesseraql\cds-!FINGERPRINT!.jsa -Xlog:cds=error:stderr"
  )
)

java %OPTS% %TESSERAQL_JAVA_OPTS% -cp "%CP%" io.tesseraql.cli.TesseraqlCli %*
