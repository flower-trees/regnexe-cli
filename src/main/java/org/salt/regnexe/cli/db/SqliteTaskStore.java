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

    private final Connection conn;

    public SqliteTaskStore(RexDatabase db) {
        this.conn = db.connection();
    }

    @Override
    public void save(TaskExecutionState state) {
        String sql = """
                INSERT OR REPLACE INTO task_execution_states
                    (task_id, session_id, status, updated_at, data)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.getTaskId());
            ps.setString(2, state.getSessionId());
            ps.setString(3, state.getStatus() != null ? state.getStatus().name() : "UNKNOWN");
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, MAPPER.writeValueAsString(state));
            ps.execute();
        } catch (Exception e) {
            throw new RuntimeException("save failed for task " + state.getTaskId(), e);
        }
    }

    @Override
    public Optional<TaskExecutionState> load(String taskId) {
        String sql = "SELECT data FROM task_execution_states WHERE task_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(MAPPER.readValue(rs.getString(1), TaskExecutionState.class));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("load failed for task " + taskId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<TaskExecutionState> listResumable(String sessionId, boolean includeFailed) {
        // Include RUNNING tasks: these are tasks interrupted by kill -9 before the shutdown
        // hook could mark them PAUSED. Treat them as resumable to avoid data loss.
        // FAILED is opt-in only (--force-resume) — see TaskStore.listResumable's javadoc.
        String sql = "SELECT data FROM task_execution_states WHERE session_id = ? AND status IN ("
                + (includeFailed ? "'PAUSED', 'RUNNING', 'FAILED'" : "'PAUSED', 'RUNNING'") + ")";
        List<TaskExecutionState> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(MAPPER.readValue(rs.getString(1), TaskExecutionState.class));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("listResumable failed for session " + sessionId, e);
        }
        return result;
    }

    @Override
    public void markFinished(String taskId) {
        // No-op: framework calls save() with final status before calling this.
    }
}
