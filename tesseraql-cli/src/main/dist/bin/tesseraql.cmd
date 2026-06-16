@echo off
rem TesseraQL CLI launcher (fat jar). Requires a JDK 21+ on PATH.
rem Pass extra JVM options via TESSERAQL_JAVA_OPTS (e.g. proxy or truststore settings).
setlocal
set "DIR=%~dp0"
java %TESSERAQL_JAVA_OPTS% -jar "%DIR%..\lib\tesseraql.jar" %*
