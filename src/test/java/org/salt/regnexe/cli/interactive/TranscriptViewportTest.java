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
    @Test public void softWrapAndReverseDraggingCopyOnlyOriginalText() {
        var view = new TranscriptViewport();
        view.append("\u001b[36mab中文cd\u001b[0m");
        view.view(6, 4);
        view.beginSelection(0, 0);
        view.extendSelection(1, 1);
        assertEquals("ab中文cd", view.finishSelection(1, 1));
        assertEquals("ab中文cd", view.selectedText());
        assertTrue(view.view(6, 4).get(0).toAnsi().contains("7"));
        view.beginSelection(1, 1);
        assertEquals("ab中文cd", view.finishSelection(0, 0));
        view.view(80, 4);
        assertEquals("ab中文cd", view.selectedText());
    }

    @Test public void selectionKeepsHardNewlinesAndBlankLinesButNotPadding() {
        var view = new TranscriptViewport();
        view.append("one\n\n二三 tail");
        view.view(80, 5);
        view.beginSelection(1, 0);
        assertEquals("ne\n\n二三", view.finishSelection(3, 2));
        view.beginSelection(0, 0);
        assertEquals("one", view.finishSelection(70, 0));
    }

    @Test public void wideAndCombiningCharactersAreNeverSplit() {
        var view = new TranscriptViewport();
        view.append("A😀e\u0301Z");
        view.view(80, 3);
        view.beginSelection(2, 0); // second screen cell of the emoji
        assertEquals("😀e\u0301", view.finishSelection(3, 0));
    }

    @Test public void backgroundOutputRetainsSelectionAndItsVisibleText() {
        var view = new TranscriptViewport();
        for (int i = 0; i < 20; i++) view.append("line " + i);
        view.view(80, 5);
        view.beginSelection(0, 1);
        view.finishSelection(6, 1);
        String text = view.selectedText();
        view.append("new output");
        assertEquals("line 15", view.view(80, 5).get(0).toString());
        assertEquals(text, view.selectedText());
        view.scroll(4, 5);
        assertEquals(text, view.selectedText());
    }

    @Test public void clickAndEvictedTextDoNotProduceAnInvalidSelection() {
        var view = new TranscriptViewport();
        view.append("first line"); view.view(80, 2);
        view.beginSelection(0, 0);
        assertEquals("", view.finishSelection(0, 0));
        view.beginSelection(0, 0); view.finishSelection(5, 0);
        view.append("later\n".repeat(10001));
        assertFalse(view.hasSelection());
        assertEquals("", view.selectedText());
    }

}
