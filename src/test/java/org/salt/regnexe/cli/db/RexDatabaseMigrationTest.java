package org.salt.regnexe.cli.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.Assert.*;

public class RexDatabaseMigrationTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path legacyDatabase() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("rex.db");
        try (RexDatabase db = new RexDatabase(path); Statement st = db.connection().createStatement()) {
            st.executeUpdate("DELETE FROM rex_schema_metadata");
        }
        return path;
    }

    private void session(Connection connection, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO sessions VALUES (?,?,'','',1,1)")) {
            ps.setString(1, "id-" + name);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void turn(Connection connection, String id, String key, int app) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO conversation_turns VALUES (?,?,0,?,1,'{}')")) {
            ps.setString(1, id);
            ps.setInt(2, app);
            ps.setString(3, key);
            ps.executeUpdate();
        }
    }

    private String key(Connection connection, String id, int app) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT session_id FROM conversation_turns WHERE id=? AND app_id=?")) {
            ps.setString(1, id);
            ps.setInt(2, app);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    @Test
    public void numericSessionNameMustNotBecomeAnotherSession() throws Exception {
        Path path = legacyDatabase();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            session(c, "A");
            session(c, "65");
            turn(c, "numeric", "65", 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals("65", key(db.connection(), "numeric", 0));
        }
    }

    @Test
    public void legacyRecordsMustNotMergeIntoAnAmbiguousNumericDestination() throws Exception {
        Path path = legacyDatabase();
        String numericHash = Integer.toString("65".hashCode());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            session(c, "A");
            session(c, "65");
            turn(c, "ambiguous", "65", 0);
            turn(c, "numeric-legacy", numericHash, 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals("65", key(db.connection(), "ambiguous", 0));
            assertEquals(numericHash, key(db.connection(), "numeric-legacy", 0));
        }
    }

    @Test
    public void migratesUniqueLegacyHashAndRetainsNamedHistory() throws Exception {
        Path path = legacyDatabase();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            session(c, "travel");
            turn(c, "old", Integer.toString("travel".hashCode()), 0);
            turn(c, "new", "travel", 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals("travel", key(db.connection(), "old", 0));
            assertEquals("travel", key(db.connection(), "new", 0));
        }
    }

    @Test
    public void preservesSharedHashInsteadOfGuessingOwnership() throws Exception {
        Path path = legacyDatabase();
        assertEquals("Aa".hashCode(), "BB".hashCode());
        String hash = Integer.toString("Aa".hashCode());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            session(c, "Aa");
            session(c, "BB");
            turn(c, "collision", hash, 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals(hash, key(db.connection(), "collision", 0));
        }
    }

    @Test
    public void destinationCollisionPreservesBothCopies() throws Exception {
        Path path = legacyDatabase();
        String hash = Integer.toString("travel".hashCode());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            session(c, "travel");
            turn(c, "same", hash, 0);
            turn(c, "same", "travel", 0);
        }
        try (RexDatabase db = new RexDatabase(path); Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM conversation_turns WHERE id='same'")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    public void leavesOtherApplicationsAndUnmatchedKeysUntouched() throws Exception {
        Path path = legacyDatabase();
        String hash = Integer.toString("travel".hashCode());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            session(c, "travel");
            turn(c, "foreign", hash, 7);
            turn(c, "orphan", "unmatched", 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals(hash, key(db.connection(), "foreign", 7));
            assertEquals("unmatched", key(db.connection(), "orphan", 0));
        }
    }

    @Test
    public void completedMigrationIsNotRepeatedOnRestart() throws Exception {
        Path path = legacyDatabase();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            session(c, "travel");
            turn(c, "legacy", Integer.toString("travel".hashCode()), 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            session(db.connection(), "A");
            turn(db.connection(), "later", "65", 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals("travel", key(db.connection(), "legacy", 0));
            assertEquals("65", key(db.connection(), "later", 0));
        }
    }

    @Test
    public void failedMigrationRollsBackAndCanBeRetried() throws Exception {
        Path path = legacyDatabase();
        String hash = Integer.toString("travel".hashCode());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = c.createStatement()) {
            session(c, "travel");
            turn(c, "legacy", hash, 0);
            st.execute("CREATE TRIGGER fail_migration BEFORE INSERT ON rex_schema_metadata BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        try (RexDatabase ignored = new RexDatabase(path)) {
            fail("expected migration to fail");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("injected failure"));
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = c.createStatement()) {
            assertEquals(hash, key(c, "legacy", 0));
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rex_schema_metadata")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            st.execute("DROP TRIGGER fail_migration");
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals("travel", key(db.connection(), "legacy", 0));
        }
    }

    @Test
    public void freshDatabaseIsMarkedBeforeAnyHistoryIsWritten() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("fresh.db");
        try (RexDatabase db = new RexDatabase(path)) {
            session(db.connection(), "A");
            session(db.connection(), "65");
            turn(db.connection(), "numeric", "65", 0);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            assertEquals("65", key(db.connection(), "numeric", 0));
        }
    }
}
