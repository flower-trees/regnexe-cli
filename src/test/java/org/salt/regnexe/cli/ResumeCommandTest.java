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
                RexConfig.class, Terminal.class, RegnexeAgent.class, SessionContext.class, RexDatabase.class,
                org.salt.regnexe.cli.ui.CliRenderer.class);
        method.setAccessible(true);
        return method.invoke(new CliMain(), text, new PrintWriter(output), new RexConfig(), null, null, context, db, renderer(output));
    }

    private org.salt.regnexe.cli.ui.CliRenderer renderer(StringWriter output) {
        return new org.salt.regnexe.cli.ui.TerminalCliRenderer(null,
                new org.salt.regnexe.cli.ui.ThemeConfig(org.salt.regnexe.cli.ui.CliTheme.MINIMAL,
                        org.salt.regnexe.cli.ui.ColorMode.NEVER, false, true, false), new PrintWriter(output));
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
    private PendingResume restore(SessionContext context, RexDatabase db, StringWriter output) throws Exception {
        var method = CliMain.class.getDeclaredMethod("restoreOpenedSession", SessionContext.class, RexDatabase.class, PrintWriter.class, org.salt.regnexe.cli.ui.CliRenderer.class);
        method.setAccessible(true);
        return (PendingResume) method.invoke(new CliMain(), context, db, new PrintWriter(output), renderer(output));
    }

    private void history(RexDatabase db, String sessionName, String question) {
        var turn = org.salt.jlangchain.core.history.HistoryInfos.builder().id(UUID.randomUUID().toString())
                .type(org.salt.jlangchain.core.history.HistoryInfos.Type.NORMAL).createdAt(1000L)
                .messages(List.of(org.salt.jlangchain.core.message.HumanMessage.builder().content(question).build(),
                        org.salt.jlangchain.core.message.AIMessage.builder().content("saved answer").build())).build();
        new SqliteConversationStorage(db).append("0", "0", sessionName, turn);
    }

    @Test public void startupAndResumeDisplaySameHistoryAndSelectSameUnfinishedTask() throws Exception {
        try (var db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db")); var current = session("demo")) {
            saveSession(db, current); current.open(db.lockDirectory());
            history(db, "demo", "saved question"); history(db, "other", "other session secret");
            var state = new TaskExecutionState(); state.setTaskId("paused-task"); state.setSessionId("demo");
            state.setStatus(TaskStatus.PAUSED);
            var request = new TaskRequest(); request.setGoal("original task goal"); state.setRequest(request);
            new SqliteTaskStore(db).save(state);
            StringWriter startup = new StringWriter(), resume = new StringWriter();
            assertEquals(new PendingResume(current.getSessionId(), "paused-task"), restore(current, db, startup));
            Object response = command("/resume demo", current, db, resume);
            var selection = response.getClass().getDeclaredField("pendingResume"); selection.setAccessible(true);
            assertEquals(new PendingResume(current.getSessionId(), "paused-task"), selection.get(response));
            assertEquals(startup.toString(), resume.toString());
            assertTrue(startup.toString().contains("rex> saved question"));
            assertFalse(startup.toString().contains("NORMAL"));
            assertFalse(startup.toString().contains("human:"));
            assertTrue(startup.toString().contains("saved answer"));
            assertFalse(startup.toString().contains("other session secret"));
            assertTrue(startup.toString().indexOf("saved question") < startup.toString().indexOf("Found an unfinished task"));
            assertEquals(TaskStatus.PAUSED, new SqliteTaskStore(db).load("paused-task").orElseThrow().getStatus());
        }
    }

    @Test public void finishedSessionShowsHistoryWithoutResumingAndEmptySessionStaysQuiet() throws Exception {
        try (var db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db")); var current = session("demo")) {
            saveSession(db, current); current.open(db.lockDirectory()); history(db, "demo", "finished conversation");
            var state = new TaskExecutionState(); state.setTaskId("finished"); state.setSessionId("demo");
            state.setStatus(TaskStatus.FINISHED); new SqliteTaskStore(db).save(state);
            StringWriter output = new StringWriter();
            assertNull(restore(current, db, output));
            assertTrue(output.toString().contains("finished conversation"));
            assertFalse(output.toString().contains("Found an unfinished task"));
            StringWriter empty = new StringWriter();
            assertNull(restore(session("new"), db, empty)); assertEquals("", empty.toString());
        }
    }

    @Test public void resumeWithoutNamePrintsOnlyNamesInCurrentWorkspace() throws Exception {
        try (var db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db"));
             var alpha = session("alpha"); var beta = session("beta")) {
            saveSession(db, alpha); saveSession(db, beta);
            var other = db.findSessionByName("beta").orElseThrow();
            other.setWorkingDir(temporary.newFolder("another-workspace").getAbsolutePath()); db.upsertSession(other);
            StringWriter output = new StringWriter();
            command("/resume", alpha, db, output);
            assertEquals(java.util.Set.of("alpha"), output.toString().lines().collect(java.util.stream.Collectors.toSet()));
            StringWriter help = new StringWriter();
            command("/help", alpha, db, help);
            assertFalse(help.toString().contains("/sessions"));
            assertFalse(help.toString().contains("/sessins"));
            assertFalse(output.toString().contains(alpha.getSessionId()));
            assertFalse(output.toString().contains("WORKING DIR"));
        }
    }

}
