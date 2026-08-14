@echo off
rem TesseraQL CLI launcher (fat jar). Requires a JDK 25+ on PATH.
rem Pass extra JVM options via TESSERAQL_JAVA_OPTS (e.g. proxy or truststore settings). They are
rem applied after the defaults below, so any default can be overridden.
setlocal
set "DIR=%~dp0"

rem JVM defaults (docs/jvm-baseline.md decision 4), measured on the example app:
rem   -XX:+UseCompactObjectHeaders           live heap -7%%, RSS -5%% (default from JDK 27)
rem   a CDS archive                          time-to-ready -25%%, RSS -20%%
rem   --sun-misc-unsafe-memory-access=allow  silences the three lines Netty's use of
rem                                          sun.misc.Unsafe prints at every start on JDK 25
set "OPTS=-XX:+UseCompactObjectHeaders --sun-misc-unsafe-memory-access=allow"

rem The archive is written on first run. It is tied to the jar it was built from, and a newer jar
rem at the same path does not cause a rebuild — the JVM quietly stops using it — so the jar's
rem size names the file and an upgrade lands on a new one. -Xlog:cds=error keeps the writing run
rem from listing the classes it skipped, while still reporting an archive the JVM refuses.
for %%J in ("%DIR%..\lib\tesseraql.jar") do set "JARSIZE=%%~zJ"
if not "%LOCALAPPDATA%"=="" if not "%JARSIZE%"=="" (
  if not exist "%LOCALAPPDATA%\tesseraql\" mkdir "%LOCALAPPDATA%\tesseraql" 2>nul
  if exist "%LOCALAPPDATA%\tesseraql\" (
    if not exist "%LOCALAPPDATA%\tesseraql\cds-%JARSIZE%.jsa" del /q "%LOCALAPPDATA%\tesseraql\cds-*.jsa" 2>nul
    set "OPTS=%OPTS% -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=%LOCALAPPDATA%\tesseraql\cds-%JARSIZE%.jsa -Xlog:cds=error:stderr"
  )
)

java %OPTS% %TESSERAQL_JAVA_OPTS% -jar "%DIR%..\lib\tesseraql.jar" %*
