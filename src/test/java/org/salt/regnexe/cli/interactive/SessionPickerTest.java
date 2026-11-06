package org.salt.regnexe.cli.interactive;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SessionPickerTest {
    @Test public void startsAtCurrentSessionAndSelectsOnlyAfterEnter() {
        var picker = new SessionPicker();
        var visible = new AtomicReference<SessionPicker.Menu>(); picker.setView(visible::set);
        assertTrue(picker.open(List.of("alpha", "beta"), "beta"));
        assertEquals(1, visible.get().selected()); assertNull(picker.takeSelection());
        assertTrue(picker.move(1)); assertEquals(0, visible.get().selected());
        assertTrue(picker.move(-1)); assertEquals(1, visible.get().selected());
        assertTrue(picker.acceptSelection(true)); assertNull(visible.get());
        assertEquals("beta", picker.takeSelection()); assertNull(picker.takeSelection());
        assertFalse(picker.move(1));
    }
    @Test public void cancelAndTypedInputDoNotOpenASelectedSession() {
        var picker = new SessionPicker();
        picker.open(List.of("alpha", "beta"), "missing");
        picker.move(1); assertFalse(picker.acceptSelection(false));
        assertTrue(picker.cancel()); assertNull(picker.takeSelection());
        assertFalse(picker.cancel()); assertFalse(picker.acceptSelection(true));
        assertFalse(picker.open(List.of(), "alpha"));
    }
    @Test public void longListKeepsTheSelectedNameVisibleInABoundedWindow() {
        var picker = new SessionPicker();
        var visible = new AtomicReference<SessionPicker.Menu>(); picker.setView(visible::set);
        picker.open(List.of("a", "b", "c", "d", "e", "f"), "e");
        var lines = visible.get().lines(3);
        assertEquals(3, lines.size()); assertTrue(lines.contains("> e"));
        assertFalse(lines.contains("  a"));
    }
}
