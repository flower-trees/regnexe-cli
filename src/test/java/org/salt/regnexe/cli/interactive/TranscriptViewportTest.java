package org.salt.regnexe.cli.interactive;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranscriptViewportTest {
    @Test public void scrollingAndDraggingOnlyChangeTheHistoryViewport() {
        var view = new TranscriptViewport();
        for (int i = 0; i < 20; i++) view.append("line " + i);
        assertEquals("line 15", view.view(80, 5).get(0).toString());
        view.scroll(5, 5);
        assertEquals("line 10", view.view(80, 5).get(0).toString());
        view.append("line 20");
        assertEquals("line 10", view.view(80, 5).get(0).toString());
        view.seek(0, 5);
        assertEquals("line 0", view.view(80, 5).get(0).toString());
        view.seek(4, 5);
        assertEquals("line 16", view.view(80, 5).get(0).toString());
    }
    @Test public void blankLinesStylesAndWidthAreRetained() {
        var view = new TranscriptViewport();
        view.append("\n\u001b[36mquestion\u001b[0m\n\nanswer\n");
        var rows = view.view(80, 4);
        assertEquals("", rows.get(0).toString());
        assertEquals("question", rows.get(1).toString());
        assertEquals("", rows.get(2).toString());
        assertTrue(rows.get(1).toAnsi().contains("\u001b["));
        assertEquals("answer", rows.get(3).toString());
        assertTrue(view.view(4, 4).stream().allMatch(line -> line.columnLength() <= 4));
    }
}
