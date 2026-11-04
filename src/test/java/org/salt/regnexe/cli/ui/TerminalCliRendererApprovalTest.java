package org.salt.regnexe.cli.ui;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class TerminalCliRendererApprovalTest {
    @Test
    public void approvalSurvivesSameSessionRebuildButDoesNotLeakToAnotherSession() throws Exception {
        try (Fixture fixture = new Fixture("always\nn\nn\n", CliTheme.MINIMAL)) {
            TerminalCliRenderer renderer = fixture.renderer;
            renderer.setApprovalSession("uuid-a");
            assertEquals(ConfirmChoice.ALWAYS, renderer.confirm("run", "bash"));
            renderer.setApprovalSession("uuid-a");
            assertEquals(ConfirmChoice.YES, renderer.confirm("run", "bash"));
            renderer.setApprovalSession("uuid-b");
            assertEquals(ConfirmChoice.NO, renderer.confirm("run", "bash"));
            renderer.setApprovalSession("uuid-a");
            assertEquals(ConfirmChoice.YES, renderer.confirm("run", "bash"));
            assertEquals(ConfirmChoice.NO, renderer.confirm("apply", "write_file"));
            assertTrue(fixture.output.toString().contains("always (this session)"));
        }
    }

    @Test
    public void fileAndMcpApprovalsAreAlsoIndependentBetweenSessions() throws Exception {
        for (String key : new String[]{"write_file", "edit_file", "server_tool"}) {
            try (Fixture fixture = new Fixture("always\nn\n", CliTheme.MINIMAL)) {
                fixture.renderer.setApprovalSession("uuid-a");
                assertEquals(ConfirmChoice.ALWAYS, fixture.renderer.confirm("apply", key));
                fixture.renderer.setApprovalSession("uuid-b");
                assertEquals(key, ConfirmChoice.NO, fixture.renderer.confirm("apply", key));
                fixture.renderer.setApprovalSession("uuid-a");
                assertEquals(key, ConfirmChoice.YES, fixture.renderer.confirm("apply", key));
            }
        }
    }

    @Test
    public void yesApprovesOneCallOnly() throws Exception {
        try (Fixture fixture = new Fixture("yes\nn\n", CliTheme.MINIMAL)) {
            fixture.renderer.setApprovalSession("uuid-a");
            assertEquals(ConfirmChoice.YES, fixture.renderer.confirm("run", "bash"));
            assertEquals(ConfirmChoice.NO, fixture.renderer.confirm("run", "bash"));
        }
    }

    @Test
    public void newRendererDoesNotInheritPreviousProcessApprovals() throws Exception {
        try (Fixture first = new Fixture("always\n", CliTheme.MINIMAL);
             Fixture second = new Fixture("n\n", CliTheme.MINIMAL)) {
            first.renderer.setApprovalSession("same-session-uuid");
            assertEquals(ConfirmChoice.ALWAYS, first.renderer.confirm("run", "bash"));
            second.renderer.setApprovalSession("same-session-uuid");
            assertEquals(ConfirmChoice.NO, second.renderer.confirm("run", "bash"));
        }
    }

    @Test
    public void missingSessionOrToolKeyNeverRemembersApproval() throws Exception {
        try (Fixture fixture = new Fixture("always\nn\nalways\nn\n", CliTheme.MINIMAL)) {
            assertEquals(ConfirmChoice.ALWAYS, fixture.renderer.confirm("run", "bash"));
            assertEquals(ConfirmChoice.NO, fixture.renderer.confirm("run", "bash"));
            fixture.renderer.setApprovalSession("uuid-a");
            assertEquals(ConfirmChoice.ALWAYS, fixture.renderer.confirm("run", null));
            assertEquals(ConfirmChoice.NO, fixture.renderer.confirm("run", null));
            assertThrows(IllegalArgumentException.class, () -> fixture.renderer.setApprovalSession(null));
            assertThrows(IllegalArgumentException.class, () -> fixture.renderer.setApprovalSession(" "));
        }
    }

    @Test
    public void menuConfirmationUsesSameSessionIsolation() throws Exception {
        try (Fixture fixture = new Fixture("an", CliTheme.CODEX)) {
            fixture.renderer.setApprovalSession("uuid-a");
            assertEquals(ConfirmChoice.ALWAYS, fixture.renderer.confirm("run", "bash"));
            fixture.renderer.setApprovalSession("uuid-b");
            assertEquals(ConfirmChoice.NO, fixture.renderer.confirm("run", "bash"));
            fixture.renderer.setApprovalSession("uuid-a");
            assertEquals(ConfirmChoice.YES, fixture.renderer.confirm("run", "bash"));
            assertTrue(fixture.output.toString().contains("always (this session)"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final StringWriter output = new StringWriter();
        private final NonBlockingReader input;
        private final TerminalCliRenderer renderer;

        private Fixture(String answers, CliTheme theme) {
            input = NonBlocking.nonBlocking("approval-test", new StringReader(answers));
            PrintWriter writer = new PrintWriter(output);
            Terminal terminal = (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(),
                    new Class<?>[]{Terminal.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "writer" -> writer;
                        case "reader" -> input;
                        case "enterRawMode" -> new Attributes();
                        default -> null;
                    });
            renderer = new TerminalCliRenderer(terminal,
                    new ThemeConfig(theme, ColorMode.NEVER, false, true, false));
        }

        @Override public void close() throws Exception { input.close(); }
    }
}
