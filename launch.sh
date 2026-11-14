#!/bin/sh
# Starts the server without Docker: Java 21, a MySQL 8 database (DB_* in config.yaml) and ASSETS_PATH (config.yaml or
# the environment) are needed. Build it first with: ./mvnw package -Dmaven.test.skip
cd "$(dirname "$0")" || exit 1
while :; do
  java -Xmx2048m -jar target/voyage-server.jar
  code=$?
  # the admin page's Restart ends the server with code 75: start it again
  [ "$code" = 75 ] || exit "$code"
done
