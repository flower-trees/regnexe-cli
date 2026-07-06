package org.salt.regnexe.cli.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

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
        initSchema();
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
                    CREATE TABLE IF NOT EXISTS conversation_turns (
                        id         TEXT    NOT NULL,
                        app_id     INTEGER NOT NULL DEFAULT 0,
                        user_id    INTEGER NOT NULL DEFAULT 0,
                        session_id INTEGER NOT NULL,
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
}
