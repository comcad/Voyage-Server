package net.server.admin;

import client.Character;
import client.Job;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import config.YamlConfig;
import constants.game.GameConstants;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.DataProviderFactory;
import server.TimerManager;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The server's admin page, on its own port (ADMIN_PORT), behind a password (ADMIN_PASSWORD; the environment
 * variables of the same names win over config.yaml). Without a password it stays off. It shows how the server is
 * doing and who is playing (and kicks, bans and unbans them), sends a notice to everyone, restarts or shuts the server down, changes the worlds and
 * every other setting of config.yaml, and shows the console. The page itself is in the jar (resources/admin).
 */
public final class AdminServer {
    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);
    private static final Path CONFIG = Path.of("config.yaml");
    private static final long SESSION_MS = 12L * 60 * 60 * 1000, LOCK_MS = 60_000;
    private static final int TRIES = 5, BODY_MAX = 1 << 20;
    /** The exit code that asks whatever started the server (container/start.sh) to start it again. */
    public static final int RESTART_CODE = 75;
    private static final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private static final Map<String, long[]> fails = new ConcurrentHashMap<>();
    private static final long STARTED = System.currentTimeMillis();
    private static HttpServer http;

    private AdminServer() {
    }

    private static String setting(String key, String fromFile) {
        String env = System.getenv(key);
        return env != null && !env.isBlank() ? env : fromFile == null ? "" : fromFile;
    }

    /** The admin page's password, and the admin account's when it is first made (AdminAccount). */
    static String password() {
        return setting("ADMIN_PASSWORD", YamlConfig.config.server.ADMIN_PASSWORD);
    }

    /** Whether the admin page is to run: a port and a password. Its console is kept from the start when it is. */
    public static boolean wanted() {
        return port() > 0 && !password().isEmpty();
    }

    private static int port() {
        try {
            return Integer.parseInt(setting("ADMIN_PORT", String.valueOf(YamlConfig.config.server.ADMIN_PORT)).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static synchronized void start() {
        if (http != null) {
            return;
        }
        if (port() <= 0) {
            return;
        }
        if (password().isEmpty()) {
            log.warn("The admin page is off: it has a port ({}) but no password. Set ADMIN_PASSWORD in config.yaml or the environment.", port());
            return;
        }
        String host = setting("ADMIN_HOST", YamlConfig.config.server.ADMIN_HOST);
        try {
            http = HttpServer.create(new InetSocketAddress(host.isEmpty() ? "0.0.0.0" : host, port()), 0);
        } catch (IOException e) {
            log.error("The admin page could not start on port {}", port(), e);
            return;
        }
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/", AdminServer::handle);
        http.start();
        log.info("Admin page on http://{}:{}/", host.isEmpty() ? "0.0.0.0" : host, port());
    }

    // ---------------------------------------------------------------- answering
    private static void handle(HttpExchange ex) {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                api(ex, path.substring(4));
                return;
            }
            String name = path.equals("/") ? "index.html" : path.substring(1);
            if (!name.matches("[a-z]+\\.(html|js|css)")) {
                send(ex, 404, "text/plain; charset=utf-8", "Not here.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body;
            try (InputStream in = AdminServer.class.getResourceAsStream("/admin/" + name)) {
                if (in == null) {
                    send(ex, 404, "text/plain; charset=utf-8", "Not here.".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                body = in.readAllBytes();
            }
            String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript" : "text/css";
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, type + "; charset=utf-8", body);
        } catch (Exception e) {
            log.error("Admin page: {} {}", ex.getRequestMethod(), ex.getRequestURI(), e);
        }
    }

    private static void api(HttpExchange ex, String route) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        String method = ex.getRequestMethod();
        try {
            if (route.equals("/session")) {
                json(ex, 200, Map.of("in", signedIn(ex), "password", true));
                return;
            }
            if (!method.equals("GET") && !"1".equals(ex.getRequestHeaders().getFirst("X-Voyage-Admin"))) {
                json(ex, 403, Map.of("error", "not from the admin page"));
                return;
            }
            if (route.equals("/login") && method.equals("POST")) {
                login(ex);
                return;
            }
            if (route.equals("/logout") && method.equals("POST")) {
                String id = cookie(ex);
                if (id != null) {
                    sessions.remove(id);
                }
                ex.getResponseHeaders().add("Set-Cookie", "voyage_server_admin=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
                json(ex, 200, Map.of("in", false));
                return;
            }
            if (!signedIn(ex)) {
                json(ex, 401, Map.of("error", "Sign in."));
                return;
            }
            switch (method + " " + route) {
                case "GET /status" -> json(ex, 200, status());
                case "GET /players" -> json(ex, 200, players());
                case "POST /players/kick" -> kick(ex, body(ex));
                case "POST /players/ban" -> ban(ex, body(ex));
                case "POST /players/unban" -> unban(ex, body(ex));
                case "GET /settings" -> json(ex, 200, Map.of("file", CONFIG.toAbsolutePath().normalize().toString(), "settings", AdminSettings.read(CONFIG)));
                case "PUT /settings" -> {
                    int n = AdminSettings.write(CONFIG, body(ex));
                    if (n > 0) {
                        log.warn("Admin page: {} line{} of config.yaml changed", n, n == 1 ? "" : "s");
                    }
                    json(ex, 200, Map.of("changed", n, "settings", AdminSettings.read(CONFIG)));
                }
                case "GET /log" -> json(ex, 200, AdminLog.after(number(query(ex, "after"))));
                case "POST /notice" -> notice(ex, body(ex));
                case "POST /control" -> control(ex, body(ex));
                default -> json(ex, 404, Map.of("error", "No such thing."));
            }
        } catch (IllegalArgumentException e) {
            json(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Admin page: {} {}", method, route, e);
            json(ex, 500, Map.of("error", "Something went wrong on the server: " + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- signing in
    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ip(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static void login(HttpExchange ex) throws IOException {
        String ip = ip(ex);
        long now = System.currentTimeMillis();
        long[] f = fails.get(ip);
        if (f != null && f[0] >= TRIES && now - f[1] < LOCK_MS) {
            json(ex, 429, Map.of("error", "Too many tries. Wait a minute."));
            return;
        }
        Object given = body(ex).get("password");
        if (!MessageDigest.isEqual(digest(given instanceof String s ? s : ""), digest(password()))) {
            fails.put(ip, new long[] {(f != null && now - f[1] < LOCK_MS ? f[0] : 0) + 1, now});
            log.warn("Admin page: a wrong password from {}", ip);
            json(ex, 401, Map.of("error", "Wrong password."));
            return;
        }
        fails.remove(ip);
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        sessions.put(id, now + SESSION_MS);
        sessions.values().removeIf(until -> until < now);
        ex.getResponseHeaders().add("Set-Cookie", "voyage_server_admin=" + id + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + SESSION_MS / 1000);
        log.info("Admin page: signed in from {}", ip);
        json(ex, 200, Map.of("in", true));
    }

    private static String cookie(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals("voyage_server_admin")) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static boolean signedIn(HttpExchange ex) {
        String id = cookie(ex);
        Long until = id != null ? sessions.get(id) : null;
        return until != null && until > System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- what it shows and does
    private static Map<String, Object> status() {
        Server server = Server.getInstance();
        List<Map<String, Object>> worlds = new ArrayList<>();
        int online = 0;
        for (World w : server.getWorlds()) {
            List<Map<String, Object>> channels = new ArrayList<>();
            for (Channel ch : w.getChannels()) {
                channels.add(Map.of("id", ch.getId(), "players", ch.getPlayerStorage().getAllCharacters().size()));
            }
            int players = w.getPlayerStorage().getAllCharacters().size();
            online += players;
            Map<String, Object> world = new LinkedHashMap<>();
            world.put("id", w.getId());
            world.put("name", w.getId() < GameConstants.WORLD_NAMES.length ? GameConstants.WORLD_NAMES[w.getId()] : "World " + w.getId());
            world.put("players", players);
            world.put("channels", channels);
            worlds.add(world);
        }
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", server.isOnline());
        out.put("since", STARTED);
        out.put("players", online);
        out.put("worlds", worlds);
        out.put("memory", Map.of("used", rt.totalMemory() - rt.freeMemory(), "max", rt.maxMemory()));
        out.put("assets", String.valueOf(DataProviderFactory.assetsFolder()));
        out.put("customs", String.valueOf(DataProviderFactory.customsFolder()));
        return out;
    }

    private static String words(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return s.isEmpty() ? s : java.lang.Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Who is in the game, and every character of a banned account (so it can be unbanned), each with whether it is
     * online and whether its account is banned.
     */
    private static List<Map<String, Object>> players() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Integer, String> banned = new HashMap<>();
        Set<String> listed = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT id, banreason FROM accounts WHERE banned = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    banned.put(rs.getInt(1), rs.getString(2));
                }
            }
            for (World w : Server.getInstance().getWorlds()) {
                for (Character c : w.getPlayerStorage().getAllCharacters()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", c.getName());
                    p.put("level", c.getLevel());
                    p.put("job", c.getJob() != null ? words(c.getJob().name()) : "");
                    p.put("map", c.getMapId());
                    p.put("mapName", c.getMap() != null ? c.getMap().getMapName() : "");
                    p.put("world", worldName(w.getId()));
                    p.put("channel", c.getClient() != null ? c.getClient().getChannel() : 0);
                    p.put("gm", c.gmLevel());
                    p.put("online", true);
                    p.put("banned", banned.containsKey(c.getAccountID()));
                    p.put("reason", banned.getOrDefault(c.getAccountID(), ""));
                    out.add(p);
                    listed.add(c.getName().toLowerCase(Locale.ROOT));
                }
            }
            if (!banned.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement("SELECT c.name, c.level, c.job, c.map, c.world, c.gm, c.accountid FROM characters c JOIN accounts a ON a.id = c.accountid WHERE a.banned = 1");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        if (!listed.add(name.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        Job job = Job.getById(rs.getInt("job"));
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("name", name);
                        p.put("level", rs.getInt("level"));
                        p.put("job", job != null ? words(job.name()) : "");
                        p.put("map", rs.getInt("map"));
                        p.put("mapName", "");
                        p.put("world", worldName(rs.getInt("world")));
                        p.put("channel", 0);
                        p.put("gm", rs.getInt("gm"));
                        p.put("online", false);
                        p.put("banned", true);
                        p.put("reason", banned.getOrDefault(rs.getInt("accountid"), ""));
                        out.add(p);
                    }
                }
            }
        }
        out.sort((a, b) -> a.get("online") != b.get("online") ? (Boolean) a.get("online") ? -1 : 1
                : String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    private static String worldName(int id) {
        return id >= 0 && id < GameConstants.WORLD_NAMES.length ? GameConstants.WORLD_NAMES[id] : String.valueOf(id);
    }

    /** A character in the game by its name, or null. */
    private static Character online(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character c = w.getPlayerStorage().getCharacterByName(name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static String nameIn(Map<String, Object> body) {
        Object name = body.get("name");
        if (!(name instanceof String s) || s.isBlank() || s.length() > 13) {
            throw new IllegalArgumentException("Which character?");
        }
        return s.trim();
    }

    /** Disconnects a player: logs the character out and closes their connection, so their game ends too. */
    private static void kick(HttpExchange ex, Map<String, Object> body) throws IOException {
        String name = nameIn(body);
        Character c = online(name);
        if (c == null) {
            throw new IllegalArgumentException(name + " is not in the game.");
        }
        dropConnection(c.getClient());
        log.warn("Admin page: kicked {}", c.getName());
        json(ex, 200, Map.of("ok", true));
    }

    /**
     * Logs the character out and closes the connection. Client.disconnect alone only logs the character out on the
     * server: the connection stays open and the player's game keeps running as if nothing happened.
     */
    private static void dropConnection(client.Client client) {
        client.disconnect(false, false);
        client.closeSession();
    }

    /**
     * Bans a character's account (every character on it), as !ban does: a player in the game is told why and is
     * disconnected five seconds later, and the machine ids their game sent are banned too. Unlike !ban it bans no IP
     * address: players of the web client all reach the server from the address of the client's relay.
     */
    private static void ban(HttpExchange ex, Map<String, Object> body) throws IOException {
        String name = nameIn(body);
        String given = body.get("reason") instanceof String s ? s.trim() : "";
        if (given.length() > 100 || given.contains("\n")) {
            throw new IllegalArgumentException("A reason is one line of up to 100 characters.");
        }
        String reason = "Banned from the admin page" + (given.isEmpty() ? "" : ": " + given);
        Character c = online(name);
        if (c != null) {
            c.getClient().banMacs();
            c.ban(reason);
            c.yellowMessage("You have been banned. " + (given.isEmpty() ? "" : "Reason: " + given));
            Character gone = c;
            TimerManager.getInstance().schedule(() -> dropConnection(gone.getClient()), 5000);
        } else if (!Character.ban(name, reason, false)) {
            throw new IllegalArgumentException("There is no character called " + name + ".");
        }
        log.warn("Admin page: banned {} ({})", name, reason);
        json(ex, 200, Map.of("ok", true));
    }

    /** Lifts the ban on a character's account and the machine and address bans that came with it, as !unban does. */
    private static void unban(HttpExchange ex, Map<String, Object> body) throws IOException, SQLException {
        String name = nameIn(body);
        int aid = Character.getAccountIdByName(name);
        if (aid <= 0) {
            throw new IllegalArgumentException("There is no character called " + name + ".");
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET banned = -1 WHERE id = ?")) {
                ps.setInt(1, aid);
                ps.executeUpdate();
            }
            for (String table : new String[] {"ipbans", "macbans"}) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + table + " WHERE aid = ?")) {
                    ps.setString(1, String.valueOf(aid));
                    ps.executeUpdate();
                }
            }
        }
        log.warn("Admin page: unbanned {}", name);
        json(ex, 200, Map.of("ok", true));
    }

    private static void notice(HttpExchange ex, Map<String, Object> body) throws IOException {
        Object text = body.get("text");
        if (!(text instanceof String s) || s.isBlank() || s.length() > 200 || s.contains("\n")) {
            throw new IllegalArgumentException("A notice is one line of up to 200 characters.");
        }
        for (World w : Server.getInstance().getWorlds()) {
            Server.getInstance().broadcastMessage(w.getId(), PacketCreator.serverNotice(6, s.trim()));
        }
        log.info("Admin page: notice sent: {}", s.trim());
        json(ex, 200, Map.of("ok", true));
    }

    private static void control(HttpExchange ex, Map<String, Object> body) throws IOException {
        String action = String.valueOf(body.get("action"));
        int code = switch (action) {
            case "restart" -> RESTART_CODE;
            case "shutdown" -> 0;
            default -> throw new IllegalArgumentException("Restart or shutdown.");
        };
        log.warn("Admin page: {}", action);
        json(ex, 200, Map.of("ok", true));
        Thread.ofPlatform().name("admin-" + action).start(() -> Server.getInstance().stopWith(code));
    }

    // ---------------------------------------------------------------- plumbing
    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(BODY_MAX + 1);
        if (raw.length > BODY_MAX) {
            throw new IllegalArgumentException("That is too much to send.");
        }
        if (raw.length == 0) {
            return new LinkedHashMap<>();
        }
        Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("That is not something to do.");
        }
        return (Map<String, Object>) parsed;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv[0].equals(key)) {
                return kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private static long number(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
