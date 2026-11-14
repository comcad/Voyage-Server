@echo off
@title Voyage Server
rem Starts the server without Docker: Java 21, a MySQL 8 database (DB_* in config.yaml) and ASSETS_PATH (config.yaml) are needed.
rem Build it first with: mvnw.cmd package -Dmaven.test.skip
:start
java -Xmx2048m -jar target\voyage-server.jar
rem the admin page's Restart ends the server with code 75: start it again
if %ERRORLEVEL% EQU 75 goto start
pause
