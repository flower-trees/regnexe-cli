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
}
