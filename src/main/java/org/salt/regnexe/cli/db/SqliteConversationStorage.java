package org.salt.regnexe.cli.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.utils.JsonUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed ConversationStorage (Layer 1 — session memory).
 *
 * Serialization uses JsonUtil.objectMapper (SNAKE_CASE, Long-as-string).
 * Deserialization uses LOAD_MAPPER which overrides @JsonSubTypes so all
 * BaseMessage subtypes deserialize as BaseMessage — because HumanMessage /
 * AIMessage / etc. only have builder constructors, not no-arg constructors.
 * The role field is preserved via @JsonTypeInfo(visible=true).
 */
public class SqliteConversationStorage implements ConversationStorage {

    // Write: standard JsonUtil mapper (uses getters → SNAKE_CASE)
    private static final ObjectMapper SAVE_MAPPER = JsonUtil.objectMapper;

    // Read: copy of JsonUtil mapper with mix-in that maps all BaseMessage
    // subtypes → BaseMessage (which has a no-arg constructor)
    private static final ObjectMapper LOAD_MAPPER;
    static {
        LOAD_MAPPER = JsonUtil.objectMapper.copy();
        LOAD_MAPPER.addMixIn(BaseMessage.class, BaseMessageDeserMixin.class);
    }

    private final Connection conn;

    public SqliteConversationStorage(RexDatabase db) {
        this.conn = db.connection();
    }

    @Override
    public List<HistoryInfos> loadAll(Long appId, Long userId, Long sessionId) {
        String sql = """
                SELECT data FROM conversation_turns
                WHERE app_id = ? AND user_id = ? AND session_id = ?
                ORDER BY created_at ASC
                """;
        List<HistoryInfos> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, appId);
            ps.setLong(2, userId);
            ps.setLong(3, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(LOAD_MAPPER.readValue(rs.getString(1), HistoryInfos.class));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("loadAll failed for session " + sessionId, e);
        }
        return result;
    }

    @Override
    public void append(Long appId, Long userId, Long sessionId, HistoryInfos turn) {
        String sql = """
                INSERT OR IGNORE INTO conversation_turns
                    (id, app_id, user_id, session_id, created_at, data)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, turn.getId());
            ps.setLong(2, appId);
            ps.setLong(3, userId);
            ps.setLong(4, sessionId);
            ps.setLong(5, turn.getCreatedAt());
            ps.setString(6, SAVE_MAPPER.writeValueAsString(turn));
            ps.execute();
        } catch (Exception e) {
            throw new RuntimeException("append failed for session " + sessionId, e);
        }
    }

    @Override
    public void replace(Long appId, Long userId, Long sessionId, List<HistoryInfos> compacted) {
        try {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM conversation_turns WHERE app_id=? AND user_id=? AND session_id=?")) {
                    del.setLong(1, appId);
                    del.setLong(2, userId);
                    del.setLong(3, sessionId);
                    del.execute();
                }
                for (HistoryInfos turn : compacted) {
                    try (PreparedStatement ins = conn.prepareStatement("""
                            INSERT INTO conversation_turns
                                (id, app_id, user_id, session_id, created_at, data)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                        ins.setString(1, turn.getId());
                        ins.setLong(2, appId);
                        ins.setLong(3, userId);
                        ins.setLong(4, sessionId);
                        ins.setLong(5, turn.getCreatedAt());
                        ins.setString(6, SAVE_MAPPER.writeValueAsString(turn));
                        ins.execute();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("replace failed for session " + sessionId, e);
        }
    }

    @Override
    public void clear(Long appId, Long userId, Long sessionId) {
        String sql = "DELETE FROM conversation_turns WHERE app_id=? AND user_id=? AND session_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, appId);
            ps.setLong(2, userId);
            ps.setLong(3, sessionId);
            ps.execute();
        } catch (Exception e) {
            throw new RuntimeException("clear failed for session " + sessionId, e);
        }
    }
}
