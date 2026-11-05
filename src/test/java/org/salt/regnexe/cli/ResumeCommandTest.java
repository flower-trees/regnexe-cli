package org.salt.regnexe.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jline.terminal.Terminal;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.db.*;
import org.salt.regnexe.cli.session.*;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class ResumeCommandTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Object command(String text, SessionContext context, RexDatabase db, StringWriter output) throws Exception {
        var method = CliMain.class.getDeclaredMethod("handleSlashCommand", String.class, PrintWriter.class,
                RexConfig.class, Terminal.class, RegnexeAgent.class, SessionContext.class, RexDatabase.class);
        method.setAccessible(true);
        return method.invoke(new CliMain(), text, new PrintWriter(output), new RexConfig(), null, null, context, db);
    }

    private SessionContext session(String name) {
        return new SessionContext(UUID.randomUUID().toString(), name,
                new WorkspaceContext(List.of(temporary.getRoot().toPath())));
    }

    private void saveSession(RexDatabase db, SessionContext context) throws Exception {
        SessionRow row = new SessionRow();
        row.setSessionId(context.getSessionId()); row.setName(context.getSessionName());
        row.setWorkingDir(temporary.getRoot().getAbsolutePath());
        row.setCreatedAt(1); row.setUpdatedAt(1);
        db.findOrCreateSession(row);
    }

    @Test public void previewOpensSessionAndKeepsLockWhileWaitingForInput() throws Exception {
        try (var db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db"));
             var current = session("current"); var target = session("target")) {
            saveSession(db, target);
            current.open(db.lockDirectory());
            String previousId = current.getSessionId();
            var state = new TaskExecutionState(); state.setTaskId("task"); state.setSessionId("target");
            state.setStatus(TaskStatus.PAUSED);
            var request = new TaskRequest(); request.setGoal("preview me"); state.setRequest(request);
            new SqliteTaskStore(db).save(state);
            var output = new StringWriter();
            Object response = command("/resume target", current, db, output);
            assertEquals("target", current.getSessionName());
            assertTrue(output.toString().contains("Found an unfinished task"));
            var selection = response.getClass().getDeclaredField("pendingResume"); selection.setAccessible(true);
            assertEquals(new PendingResume(target.getSessionId(), "task"), selection.get(response));
            assertThrows(IllegalStateException.class, () -> target.open(db.lockDirectory()));
            try (var previous = SessionExecutionLock.acquire(db.lockDirectory(), previousId)) { assertNotNull(previous); }
            // Reopening the current session and clearing history reuse its ownership.
            command("/resume target", current, db, new StringWriter());
            var cleared = new StringWriter(); command("/clear", current, db, cleared);
            assertFalse(cleared.toString().contains("[error]"));
            assertThrows(IllegalStateException.class, () -> target.open(db.lockDirectory()));
        }
    }

    @Test public void busyDestinationIsNotPreviewedAndCurrentSessionIsPreserved() throws Exception {
        try (var db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db"));
             var current = session("current"); var target = session("target")) {
            saveSession(db, target); current.open(db.lockDirectory()); target.open(db.lockDirectory());
            var output = new StringWriter();
            command("/resume target", current, db, output);
            assertEquals("current", current.getSessionName());
            assertTrue(output.toString().contains("already open"));
            assertFalse(output.toString().contains("Opened session:"));
            assertThrows(IllegalStateException.class,
                    () -> SessionExecutionLock.acquire(db.lockDirectory(), current.getSessionId()));
        }
    }
}
