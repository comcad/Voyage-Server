#!/bin/bash
# Voyage-Server's container: MySQL, then the game server. The admin page's Restart ends the game server with code 75
# and it is started again here; any other end of it stops the database and the container. Stopping the container
# stops the game server first (it saves what it holds), then the database.
set -u

# MySQL, through its image's own start script, which sets an empty data folder up first (MYSQL_DATABASE).
docker-entrypoint.sh mysqld > >(sed -u 's/^/[database] /') 2>&1 &
db=$!
game=

stop() {
  trap - TERM INT
  if [ -n "$game" ] && kill -0 "$game" 2>/dev/null; then echo "Stopping the game server..."; kill -TERM "$game"; wait "$game"; fi
  echo "Stopping the database..."
  kill -TERM "$db" 2>/dev/null; wait "$db" 2>/dev/null
  exit "${1:-0}"
}
trap stop TERM INT

echo "Waiting for the database..."
until mysqladmin ping -h 127.0.0.1 --silent >/dev/null 2>&1; do
  kill -0 "$db" 2>/dev/null || { echo "The database did not start."; exit 1; }
  sleep 1
done

while :; do
  java -jar /opt/server/voyage-server.jar &
  game=$!
  wait "$game"
  code=$?
  if [ "$code" = 75 ]; then echo "Starting the game server again..."; continue; fi
  game=
  stop "$code"
done
