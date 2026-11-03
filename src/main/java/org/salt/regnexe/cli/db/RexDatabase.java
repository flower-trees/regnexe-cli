package org.salt.regnexe.cli.db;

import org.salt.regnexe.cli.session.SessionRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manages the single SQLite connection and schema for ~/.rex/rex.db.
 * Create once at startup; pass to the two Storage implementations.
 */
public class RexDatabase implements AutoCloseable {

    private final Connection conn;

    public RexDatabase() throws Exception {
        this(Path.of(System.getProperty("user.home"), ".rex", "rex.db"));
    }

    /** Opens an explicit database path, also useful for isolated verification. */
    public RexDatabase(Path databasePath) throws Exception {
        Path absolutePath = databasePath.toAbsolutePath();
        Files.createDirectories(absolutePath.getParent());
        conn = DriverManager.getConnection("jdbc:sqlite:" + absolutePath);
        try {
            conn.setAutoCommit(true);
            // WAL mode: allows concurrent reads while a write is in progress.
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            }
            initSchema();
            migrateConversationSessionIdsToNames();
        } catch (Exception e) {
            try {
                conn.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public Connection connection() {
        return conn;
    }

    @Override
    public void close() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ── DDL ─────────────────────────────────────────────────────────────────

    private void initSchema() throws Exception {
        try (Statement st = conn.createStatement()) {

            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        session_id  TEXT    PRIMARY KEY,
                        name        TEXT    NOT NULL UNIQUE,
                        working_dir TEXT    NOT NULL DEFAULT '',
                        model       TEXT    NOT NULL DEFAULT '',
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL
                    )""");

            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_sessions_name
                        ON sessions(name)""");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_turns (
                        id         TEXT    NOT NULL,
                        app_id     INTEGER NOT NULL DEFAULT 0,
                        user_id    INTEGER NOT NULL DEFAULT 0,
                        session_id TEXT    NOT NULL,
                        created_at INTEGER NOT NULL,
                        data       TEXT    NOT NULL,
                        PRIMARY KEY (app_id, user_id, session_id, id)
                    )""");

            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_conv_lookup
                        ON conversation_turns(app_id, user_id, session_id, created_at)""");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS task_execution_states (
                        task_id    TEXT    PRIMARY KEY,
                        session_id TEXT    NOT NULL,
                        status     TEXT    NOT NULL,
                        updated_at INTEGER NOT NULL,
                        data       TEXT    NOT NULL
                    )""");

            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_task_session
                        ON task_execution_states(session_id, status)""");
        }
    }

    // ── Sessions CRUD ────────────────────────────────────────────────────────

    public void upsertSession(SessionRow row) throws SQLException {
        String sql = """
                INSERT INTO sessions (session_id, name, working_dir, model, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    working_dir = excluded.working_dir,
                    model       = excluded.model,
                    updated_at  = excluded.updated_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.getSessionId());
            ps.setString(2, row.getName());
            ps.setString(3, row.getWorkingDir());
            ps.setString(4, row.getModel() != null ? row.getModel() : "");
            ps.setLong(5, row.getCreatedAt());
            ps.setLong(6, row.getUpdatedAt());
            ps.execute();
        }
    }

    public void touchSession(String name) throws SQLException {
        String sql = "UPDATE sessions SET updated_at=? WHERE name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, name);
            ps.execute();
        }
    }

    public Optional<SessionRow> findSessionByName(String name) throws SQLException {
        String sql = "SELECT * FROM sessions WHERE name=? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public List<SessionRow> listSessions() throws SQLException {
        String sql = "SELECT * FROM sessions ORDER BY updated_at DESC";
        List<SessionRow> result = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) result.add(mapRow(rs));
        }
        return result;
    }

    /** Deletes all conversation turns for the given session name. */
    public void clearConversation(String sessionName) throws SQLException {
        String sql = "DELETE FROM conversation_turns WHERE app_id=0 AND user_id=0 AND session_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionName);
            ps.execute();
        }
    }

    /**
     * Loads every conversation turn recorded for the given session name, oldest first — the
     * same {@code SqliteConversationStorage} deserialization the running agent itself reads
     * through, so this returns exactly what the running agent would see for this session.
     */
    public java.util.List<org.salt.jlangchain.core.history.HistoryInfos> loadConversation(String sessionName) {
        return new SqliteConversationStorage(this).loadAll("0", "0", sessionName);
    }

    /**
     * Migrates unambiguous legacy hash keys once, in a transaction. A key that is already a
     * session name, maps to several names, or would collide with a destination turn is preserved
     * for manual recovery. Never guess ownership of ambiguous history. Only CLI-owned (0/0)
     * conversation rows are migrated; other applications' rows are untouched.
     */
    private void migrateConversationSessionIdsToNames() throws SQLException {
        conn.setAutoCommit(false);
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS rex_schema_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                try (ResultSet rs = st.executeQuery(
                        "SELECT value FROM rex_schema_metadata WHERE key = 'conversation_session_ids'")) {
                    if (rs.next()) {
                        if (!"names-v1".equals(rs.getString(1))) {
                            throw new SQLException("Unsupported conversation session ID format");
                        }
                        conn.commit();
                        return;
                    }
                }
            }

            Set<String> names = new HashSet<>();
            Map<String, List<String>> namesByHash = new HashMap<>();
            for (SessionRow session : listSessions()) {
                String name = session.getName();
                names.add(name);
                namesByHash.computeIfAbsent(Integer.toString(name.hashCode()), key -> new ArrayList<>()).add(name);
            }
            List<String> keys = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT session_id FROM conversation_turns WHERE app_id=0 AND user_id=0")) {
                while (rs.next()) keys.add(rs.getString(1));
            }
            String conflictSql = """
                    SELECT 1 FROM conversation_turns src JOIN conversation_turns dst
                    ON src.app_id=dst.app_id AND src.user_id=dst.user_id AND src.id=dst.id
                    WHERE src.app_id=0 AND src.user_id=0 AND src.session_id=? AND dst.session_id=? LIMIT 1
                    """;
            try (PreparedStatement conflict = conn.prepareStatement(conflictSql);
                 PreparedStatement update = conn.prepareStatement(
                         "UPDATE conversation_turns SET session_id=? WHERE app_id=0 AND user_id=0 AND session_id=?")) {
                for (String key : keys) {
                    List<String> candidates = namesByHash.get(key);
                    if (candidates == null) continue;
                    if (names.contains(key) || candidates.size() != 1) {
                        System.err.println("[warn] Preserved ambiguous conversation key '" + key
                                + "'; inspect a database backup before manual recovery.");
                        continue;
                    }
                    String name = candidates.get(0);
                    // A numeric destination may itself contain another session's old hash
                    // records. Do not merge known legacy history into that ambiguous bucket.
                    if (keys.contains(name) && namesByHash.containsKey(name)) {
                        System.err.println("[warn] Preserved conversation key '" + key
                                + "' because its destination is ambiguous; inspect a database backup.");
                        continue;
                    }
                    conflict.setString(1, key);
                    conflict.setString(2, name);
                    try (ResultSet rs = conflict.executeQuery()) {
                        if (rs.next()) {
                            System.err.println("[warn] Preserved conflicting conversation key '" + key
                                    + "'; inspect a database backup before manual recovery.");
                            continue;
                        }
                    }
                    update.setString(1, name);
                    update.setString(2, key);
                    update.executeUpdate();
                }
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO rex_schema_metadata VALUES ('conversation_session_ids','names-v1')");
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static SessionRow mapRow(ResultSet rs) throws SQLException {
        SessionRow row = new SessionRow();
        row.setSessionId(rs.getString("session_id"));
        row.setName(rs.getString("name"));
        row.setWorkingDir(rs.getString("working_dir"));
        row.setModel(rs.getString("model"));
        row.setCreatedAt(rs.getLong("created_at"));
        row.setUpdatedAt(rs.getLong("updated_at"));
        return row;
    }
}
