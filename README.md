# Voyage Server

A MapleStory v83 game server for Voyage: a fork of [Cosmic](https://github.com/P0nk/Cosmic) that reads the game data
from the assets folder the [Voyage WZ Extractor](https://github.com/comcad/Voyage-Wz-Extractor) makes, the same folder
[Voyage-Client](https://github.com/comcad/Voyage-Client) serves and [Voyage-Editor](https://github.com/comcad/Voyage-Editor)
publishes into. It has an admin page of its own.

```
voyage-assets/            # made by the extractor from your own game files
├── Map/ Npc/ String/ ...
└── customs/              # changes and additions (what Voyage-Editor publishes), the same layout
Voyage-Server/  Voyage-Client/  Voyage-Editor/   # each pointed at ../voyage-assets
```

## The game data

`ASSETS_PATH` in `config.yaml` (or the `ASSETS_PATH` environment variable, which wins) points at the assets folder.
The server reads each `.img` from its JSON file there; a file in `customs/` is read in place of the assets' file of the
same name, and one only `customs/` has is read as well. The server reads a file the first time it needs it, so after
publishing from the editor, restart the server. Without `ASSETS_PATH` it reads a `wz/` folder of XML as upstream does.

## Running it

You need Docker and an assets folder made from your own `.wz` files. The server and its database (MySQL) run in one
container:

```sh
cd Voyage-Server
cat > .env <<'END'
VOYAGE_ASSETS=../voyage-assets
VOYAGE_ADMIN_PASSWORD=<a long password>
END
docker compose up -d --build
docker compose logs -f
```

It listens on 8484 (login) and 7575-7577 (world 1's channels; add a line to `docker-compose.yml` for each further
world's), the admin page on 8585 and the database on 127.0.0.1:3307. The database's files are kept in
`database/docker-db-data`. `config.yaml` and `scripts/` are mounted into the container: a change takes a restart
(`docker compose restart`), not a rebuild; a change to the Java code takes `docker compose up -d --build`.

A new database has no accounts and no passwords. With `AUTOMATIC_REGISTER` on (the default), logging in with a name
that has no account makes one.

## The admin page

Set `ADMIN_PORT` and `ADMIN_PASSWORD` (in `config.yaml`, or the environment; `docker-compose.yml` sets the port to
8585 and takes the password from `.env`) and open `http://<this machine>:8585`. Without a password it stays off.

- **Server**: online or starting, uptime, who is playing in each world and channel, memory, where the game data comes
  from; **Restart** and **Shut down**; a notice to everyone in the game; the worlds (channels, rates, messages) and every
  other setting of `config.yaml`, changed in the file line by line with its comments kept (a copy of the file as it was
  is kept as `config.yaml.before-admin`); the console as it is written.
- **Players**: who is in the game, their level, job, map, world and channel.

Restart ends the server with exit code 75, and the container starts it again; Shut down ends it, and the container
with it. The sign-in is a password over plain HTTP: keep the port off the internet, or put it behind HTTPS.

## What is different from Cosmic

- `provider/json`: the JSON reader, and `ASSETS_PATH` in `DataProviderFactory`. Decimals are read as Cosmic's XML reader
  reads them with `USE_UNITPRICE_WITH_COMMA` on (a map's `mobRate` 1.6 as 1), so nothing plays differently.
- `net/server/admin`: the admin page; its page is in `src/main/resources/admin`.
- No `wz/` folder: the game data is the assets folder, never kept here.
- The admin account upstream's database setup made (changeset 161, a GM with a known password) is left out.
- One container with MySQL (`Dockerfile`, `docker/start.sh`), and the database is called `voyage`.

Everything else is Cosmic as it is, so its updates can be brought in. Cosmic's own README is in
`docs/Cosmic-README.md`, and its `docs/` and `handbook/` are as upstream has them.

## Credits and license

Cosmic and the servers it comes from (OdinMS, MapleSolaxia, HeavenMS and others; see `docs/Cosmic-README.md`), under
the AGPL-3.0 (`LICENSE`), as this fork is. No game files, and nothing made from them, are in this repository.
