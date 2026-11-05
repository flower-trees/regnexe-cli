package org.salt.regnexe.cli.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.store.TaskStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed TaskStore (Layer 2 — task ledger).
 * TaskExecutionState is stored as JSON.
 * markFinished() is a no-op: the framework calls save() with updated status before calling this.
 */
public class SqliteTaskStore implements TaskStore {

    private static final ObjectMapper MAPPER = JsonUtil.objectMapper;
    // Saved agent steps hold AIMessage / ToolMessage, which the shared mapper cannot instantiate.
    private static final ObjectMapper LOAD_MAPPER = MessageDeserializer.loadMapper();

    private final Connection conn;

    public SqliteTaskStore(RexDatabase db) {
        this.conn = db.connection();
    }

    @Override
    public void save(TaskExecutionState state) {
        long revision = state.getVersion();
        // A migrated legacy task can exist at revision zero. A new task also starts at zero.
        String sql = """
                INSERT INTO task_execution_states (task_id, session_id, status, updated_at, data, version)
                SELECT ?, ?, ?, ?, ?, 1 WHERE ? = 0
                ON CONFLICT(task_id) DO UPDATE SET
                    session_id=excluded.session_id, status=excluded.status,
                    updated_at=excluded.updated_at, data=excluded.data,
                    version=task_execution_states.version+1
                WHERE task_execution_states.version=?
                """;
        if (revision != 0) {
            sql = "UPDATE task_execution_states SET session_id=?, status=?, updated_at=?, data=?, "
                    + "version=version+1 WHERE task_id=? AND version=?";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            int i = 1;
            if (revision == 0) ps.setString(i++, state.getTaskId());
            ps.setString(i++, state.getSessionId());
            ps.setString(i++, state.getStatus() != null ? state.getStatus().name() : "UNKNOWN");
            ps.setLong(i++, now);
            ps.setString(i++, MAPPER.writeValueAsString(state));
            if (revision == 0) ps.setLong(i++, revision);
            else ps.setString(i++, state.getTaskId());
            ps.setLong(i, revision);
            if (ps.executeUpdate() != 1) {
                throw new org.salt.regnexe.agent.core.task.store.TaskConflictException(state.getTaskId());
            }
            // The dedicated database column is authoritative, including on load.
            state.setVersion(revision + 1);
            state.setUpdatedAt(now);
        } catch (org.salt.regnexe.agent.core.task.store.TaskConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("save failed for task " + state.getTaskId(), e);
        }
    }

    @Override
    public Optional<TaskExecutionState> load(String taskId) {
        String sql = "SELECT data, version, updated_at FROM task_execution_states WHERE task_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readState(rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("load failed for task " + taskId, e);
        }
        return Optional.empty();
    }

    private TaskExecutionState readState(ResultSet rs) throws Exception {
        TaskExecutionState state = LOAD_MAPPER.readValue(rs.getString("data"), TaskExecutionState.class);
        state.setVersion(rs.getLong("version"));
        state.setUpdatedAt(rs.getLong("updated_at"));
        return state;
    }

    @Override
    public void markFinished(String taskId) {
        // No-op: framework calls save() with final status before calling this.
    }

    @Override
    public List<TaskExecutionState> listResumable(String sessionId) {
        String sql = "SELECT data, version, updated_at FROM task_execution_states WHERE session_id = ? AND status != 'FINISHED'";
        List<TaskExecutionState> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readState(rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("listResumable failed for session " + sessionId, e);
        }
        return result;
    }
}
