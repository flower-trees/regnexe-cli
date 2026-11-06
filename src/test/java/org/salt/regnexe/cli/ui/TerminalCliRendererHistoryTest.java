package org.salt.regnexe.cli.ui;

import org.junit.Test;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.message.*;
import org.salt.regnexe.cli.session.SessionContext;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import static org.junit.Assert.*;

public class TerminalCliRendererHistoryTest {
    private final SessionContext context = new SessionContext("test", "demo",
            new WorkspaceContext(List.of(Path.of(".").toAbsolutePath())));

    @Test public void savedAnswersUseLiveRenderingAndCurrentThemePromptWithoutTruncation() {
        String question = "Review this task\nand retain the formatting";
        String answer = "## Result\n\n```java\n    int value = 1;\n```\n" + "x".repeat(2300);
        HistoryInfos turn = HistoryInfos.builder().type(HistoryInfos.Type.NORMAL).createdAt(1000L)
                .messages(List.of(HumanMessage.builder().content(question).build(),
                        AIMessage.builder().content(answer).build())).build();
        for (CliTheme theme : CliTheme.values()) {
            for (boolean icons : new boolean[]{true, false}) {
                var config = new ThemeConfig(theme, ColorMode.NEVER, icons, true, false);
                StringWriter live = new StringWriter(), saved = new StringWriter();
                var liveRenderer = new TerminalCliRenderer(null, config, new PrintWriter(live));
                var savedRenderer = new TerminalCliRenderer(null, config, new PrintWriter(saved));
                liveRenderer.assistantMessage(answer);
                savedRenderer.sessionHistory(context, List.of(turn));
                assertEquals(savedRenderer.prompt(context) + question + System.lineSeparator() + live, saved.toString());
                assertFalse(saved.toString().contains("NORMAL"));
                assertFalse(saved.toString().contains("human:"));
                assertFalse(saved.toString().contains("truncated"));
                assertTrue(saved.toString().contains("    int value = 1;"));
            }
        }
    }

    @Test public void compactedHistoryIsIdentifiedAsSummaryRatherThanInventedDialogue() {
        StringWriter output = new StringWriter();
        var renderer = new TerminalCliRenderer(null,
                new ThemeConfig(CliTheme.MINIMAL, ColorMode.NEVER, false, true, false), new PrintWriter(output));
        var summary = HistoryInfos.builder().type(HistoryInfos.Type.SUMMARY)
                .messages(List.of(SystemMessage.builder().content("Earlier decisions were saved.").build())).build();
        renderer.sessionHistory(context, List.of(summary));
        assertTrue(output.toString().contains("Earlier conversation summary"));
        assertTrue(output.toString().contains("Earlier decisions were saved."));
        assertFalse(output.toString().contains("system:"));
        assertFalse(output.toString().contains("rex>"));
    }
}
