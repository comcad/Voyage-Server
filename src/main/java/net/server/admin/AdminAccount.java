package net.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.BCrypt;
import tools.DatabaseConnection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The server's one admin account: "admin", with the character "Admin" at GM level 6. A database has none until the
 * server first starts with ADMIN_PASSWORD set; it is then made with that password (the admin page's), so no password
 * is ever written in this repository. Once it is there it is left alone: its password is changed in the game or the
 * database, not by changing ADMIN_PASSWORD.
 */
public final class AdminAccount {
    private static final Logger log = LoggerFactory.getLogger(AdminAccount.class);
    public static final String NAME = "admin";

    private AdminAccount() {
    }

    /** Makes the admin account when the database has none and a password is set. */
    public static void ensure() {
        String password = AdminServer.password();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM accounts WHERE name = ?")) {
                ps.setString(1, NAME);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return;
                    }
                }
            }
            if (password.isEmpty()) {
                log.warn("There is no admin account yet. Set ADMIN_PASSWORD and start the server again to make one (\"{}\", with that password).", NAME);
                return;
            }
            String sql;
            try (InputStream in = AdminAccount.class.getResourceAsStream("/db/admin-account.sql")) {
                if (in == null) {
                    log.error("The admin account could not be made: db/admin-account.sql is missing from the jar.");
                    return;
                }
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // (a bcrypt hash is letters, digits, . / and $: nothing that could end the SQL string it goes in)
            String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
            if (!hash.matches("[A-Za-z0-9./$]+")) {
                throw new IllegalStateException("unexpected characters in a bcrypt hash");
            }
            sql = sql.replace("{PASSWORD_HASH}", hash);
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                for (String one : sql.split(";\\s*\\n")) {
                    if (!one.isBlank()) {
                        st.execute(one);
                    }
                }
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
            log.info("Made the admin account \"{}\" (character Admin, GM level 6) with the password in ADMIN_PASSWORD.", NAME);
        } catch (SQLException | IOException e) {
            log.error("The admin account could not be made", e);
        }
    }
}
