package org.salt.regnexe.cli.interactive;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import java.util.List;
import java.util.Map;

/** Keep JLine's editing and cursor calculation, but paint into a reserved bottom dock. */
public final class DockedLineReader extends LineReaderImpl {
    interface Dock {
        void input(List<AttributedString> lines, int cursor, int stride);
        void output(String text);
    }
    private volatile Dock dock;
    private final java.util.List<String> initialOutput = new java.util.ArrayList<>();
    public DockedLineReader(Terminal terminal, Map<String, Object> variables) {
        super(terminal, "rex", variables);
    }
    void setDock(Dock dock) {
        this.dock = dock;
        if (dock != null) { initialOutput.forEach(dock::output); initialOutput.clear(); }
    }
    @Override protected void redisplay(boolean flush) {
        if (dock != null && !(display instanceof DockDisplay)) {
            display = new DockDisplay();
            display.resize(size.getRows(), size.getColumns());
            if (isSet(org.jline.reader.LineReader.Option.MOUSE)) terminal.trackMouse(Terminal.MouseTracking.Button);
        }
        super.redisplay(flush);
    }
    @Override public void printAbove(String text) {
        Dock current = dock;
        if (current == null) { if (initialOutput.size() < 1000) initialOutput.add(text); super.printAbove(text); }
        else current.output(text);
    }
    @Override protected void doCleanup(boolean newline) {
        if (dock == null) { super.doCleanup(newline); return; }
        super.doCleanup(false);
        if (newline && buf.length() > 0) printAbove("\n" + prompt.toAnsi(terminal) + buf.toString() + "\n\n");
    }
    private final class DockDisplay extends Display {
        DockDisplay() { super(DockedLineReader.this.terminal, false); }
        @Override public void update(List<AttributedString> lines, int cursor, boolean flush) {
            Dock current = dock;
            if (current != null) current.input(lines, cursor, columns + 1);
            if (flush) terminal.flush();
        }
        @Override public void update(List<AttributedString> lines, int cursor) {
            update(lines, cursor, true);
        }
    }
}
