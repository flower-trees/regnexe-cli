package org.salt.regnexe.cli.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.jlangchain.core.agent.memory.AgentStep;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.ToolMessage;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class SqliteTaskStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void reopenedDatabaseRestoresActualMessageTypesAndSummary() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("rex.db");
        TaskExecutionState state = new TaskExecutionState();
        state.setTaskId("task");
        state.setSessionId("travel");
        state.setStatus(TaskStatus.PAUSED);
        state.setCurrentRound(2);
        state.setMaxRounds(5);
        state.setPriorStepsSummary("Earlier research is complete.");
        TaskRequest request = new TaskRequest();
        request.setGoal("Plan a trip");
        state.setRequest(request);
        state.setPriorSteps(List.of(AgentStep.ofFunctionCall(
                AIMessage.builder().content("Checking weather").build(),
                List.of(ToolMessage.builder().content("Sunny").name("weather").toolCallId("call-1").build()))));
        try (RexDatabase db = new RexDatabase(path)) {
            new SqliteTaskStore(db).save(state);
        }
        try (RexDatabase db = new RexDatabase(path)) {
            TaskExecutionState restored = new SqliteTaskStore(db).load("task").orElseThrow();
            assertEquals(TaskStatus.PAUSED, restored.getStatus());
            assertEquals(2, restored.getCurrentRound());
            assertEquals("Plan a trip", restored.getRequest().getGoal());
            assertEquals(state.getPriorStepsSummary(), restored.getPriorStepsSummary());
            AgentStep step = restored.getPriorSteps().get(0);
            assertEquals("Checking weather", step.getAiMessage().getContent());
            assertTrue(step.getToolResults().get(0) instanceof ToolMessage);
            ToolMessage tool = (ToolMessage) step.getToolResults().get(0);
            assertEquals("call-1", tool.getToolCallId());
            assertEquals("Sunny", tool.getContent());
        }
    }

    @Test
    public void resumableTasksIncludeEveryUnfinishedStatusOnlyForRequestedSession() throws Exception {
        try (RexDatabase db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db"))) {
            SqliteTaskStore store = new SqliteTaskStore(db);
            for (TaskStatus status : TaskStatus.values()) {
                TaskExecutionState state = new TaskExecutionState();
                state.setTaskId(status.name());
                state.setSessionId("travel");
                state.setStatus(status);
                store.save(state);
            }
            TaskExecutionState other = new TaskExecutionState();
            other.setTaskId("other");
            other.setSessionId("other-session");
            other.setStatus(TaskStatus.PAUSED);
            store.save(other);
            Set<TaskStatus> found = store.listResumable("travel").stream()
                    .map(TaskExecutionState::getStatus).collect(Collectors.toSet());
            assertEquals(Set.of(TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.FAILED,
                    TaskStatus.ESCALATED, TaskStatus.TIMEOUT), found);
            assertEquals(5, store.listResumable("travel").size());
        }
    }
    @Test
    public void staleSnapshotCannotOverwriteFinishedTaskAcrossConnections() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("rex.db");
        try (RexDatabase first = new RexDatabase(path); RexDatabase second = new RexDatabase(path)) {
            var a = new SqliteTaskStore(first);
            var b = new SqliteTaskStore(second);
            TaskExecutionState initial = new TaskExecutionState();
            initial.setTaskId("cas"); initial.setSessionId("s"); initial.setStatus(TaskStatus.PAUSED);
            a.save(initial);
            assertEquals(1, initial.getVersion());
            var old = a.load("cas").orElseThrow();
            var current = b.load("cas").orElseThrow();
            current.setStatus(TaskStatus.FINISHED);
            b.save(current);
            old.setStatus(TaskStatus.RUNNING);
            assertThrows(org.salt.regnexe.agent.core.task.store.TaskConflictException.class, () -> a.save(old));
            assertEquals(1, old.getVersion());
            assertEquals(TaskStatus.FINISHED, a.load("cas").orElseThrow().getStatus());
            assertEquals(2, a.load("cas").orElseThrow().getVersion());
            var missing = new TaskExecutionState();
            missing.setTaskId("deleted"); missing.setSessionId("s"); missing.setVersion(8);
            assertThrows(org.salt.regnexe.agent.core.task.store.TaskConflictException.class, () -> a.save(missing));
        }
    }

    @Test
    public void legacySchemaMigratesWithoutTrustingJsonVersion() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("legacy.db");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE task_execution_states (task_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
                    + "status TEXT NOT NULL, updated_at INTEGER NOT NULL, data TEXT NOT NULL)");
            st.execute("INSERT INTO task_execution_states VALUES ('legacy','s','PAUSED',123,"
                    + "'{\"task_id\":\"legacy\",\"session_id\":\"s\",\"status\":\"PAUSED\"}')");
        }
        try (var db = new RexDatabase(path)) {
            var store = new SqliteTaskStore(db);
            var state = store.load("legacy").orElseThrow();
            assertEquals(0, state.getVersion());
            assertEquals(123, state.getUpdatedAt());
            var stale = store.load("legacy").orElseThrow();
            store.save(state);
            assertEquals(1, state.getVersion());
            assertThrows(org.salt.regnexe.agent.core.task.store.TaskConflictException.class, () -> store.save(stale));
            try (var st = db.connection().createStatement()) {
                st.executeUpdate("UPDATE task_execution_states SET version=9 WHERE task_id='legacy'");
            }
            assertEquals(9, store.listResumable("s").get(0).getVersion());
        }
    }

}
