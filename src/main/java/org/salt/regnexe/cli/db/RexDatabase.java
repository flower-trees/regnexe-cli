package org.salt.regnexe.cli.db;

import org.salt.regnexe.cli.session.SessionRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages the single SQLite connection and schema for ~/.rex/rex.db.
 * Create once at startup; pass to the two Storage implementations.
 */
public class RexDatabase implements AutoCloseable {

    private static final Path DB_PATH =
            Path.of(System.getProperty("user.home"), ".rex", "rex.db");

    private final Connection conn;

    public RexDatabase() throws Exception {
        Files.createDirectories(DB_PATH.getParent());
        conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        conn.setAutoCommit(true);
        // WAL mode: allows concurrent reads while a write is in progress.
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
        }
        initSchema();
        migrateConversationSessionIdsToNames();
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
     * One-time, idempotent migration: {@code conversation_turns.session_id} used to store
     * {@code sessionName.hashCode()} (a lossy 32-bit int — j-langchain's {@code ConversationStorage}
     * interface required a {@code Long}, and two different session names could in principle
     * collide on the same hash, silently mixing their histories). Now that the interface takes a
     * {@code String}, this rewrites any row still keyed by an old hash back to the real session
     * name, using {@code sessions} (which still has every current name) to recompute each name's
     * old hash and match against it. Safe to run on every startup: once a row is migrated, its
     * {@code session_id} is the real name, no longer a bare integer, so it never matches again.
     */
    private void migrateConversationSessionIdsToNames() throws SQLException {
        List<SessionRow> sessions = listSessions();
        String sql = "UPDATE conversation_turns SET session_id = ? WHERE session_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (SessionRow row : sessions) {
                String name = row.getName();
                long oldHash = (long) name.hashCode();
                ps.setString(1, name);
                ps.setLong(2, oldHash);
                ps.addBatch();
            }
            ps.executeBatch();
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
