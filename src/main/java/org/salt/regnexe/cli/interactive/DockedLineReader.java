package org.salt.regnexe.cli.interactive;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.MouseEvent;
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
            if (isSet(org.jline.reader.LineReader.Option.MOUSE)) {
                terminal.trackMouse(Terminal.MouseTracking.Button);
                terminal.writer().print("\u001b[?1006h");
            }
        }
        super.redisplay(flush);
    }
    /** SGR mouse coordinates are ASCII and work beyond legacy X10's coordinate limits. */
    MouseEvent readSgrMouse() {
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < 48; i++) {
            int ch = readCharacter();
            if (ch == 'M' || ch == 'm') {
                try {
                    String[] parts = report.toString().split(";", -1);
                    if (parts.length != 3) return null;
                    int code = Integer.parseInt(parts[0]);
                    int x = Integer.parseInt(parts[1]) - 1, y = Integer.parseInt(parts[2]) - 1;
                    MouseEvent.Type type = ch == 'm' ? MouseEvent.Type.Released
                            : (code & 64) != 0 ? MouseEvent.Type.Wheel
                            : (code & 32) != 0 ? MouseEvent.Type.Dragged : MouseEvent.Type.Pressed;
                    MouseEvent.Button button = ch == 'm' ? MouseEvent.Button.NoButton
                            : (code & 64) != 0 ? ((code & 1) == 0 ? MouseEvent.Button.WheelUp : MouseEvent.Button.WheelDown)
                            : switch (code & 3) {
                                case 0 -> MouseEvent.Button.Button1;
                                case 1 -> MouseEvent.Button.Button2;
                                case 2 -> MouseEvent.Button.Button3;
                                default -> MouseEvent.Button.NoButton;
                            };
                    return new MouseEvent(type, button, java.util.EnumSet.noneOf(MouseEvent.Modifier.class), x, y);
                } catch (NumberFormatException e) { return null; }
            }
            if (ch < 0 || !(ch == ';' || ch >= '0' && ch <= '9')) return null;
            report.append((char) ch);
        }
        return null;
    }

    @Override public void printAbove(String text) {
        Dock current = dock;
        if (current == null) { if (initialOutput.size() < 1000) initialOutput.add(text); super.printAbove(text); }
        else current.output(text);
    }
    @Override protected void doCleanup(boolean newline) {
        if (dock == null) { super.doCleanup(newline); return; }
        super.doCleanup(false);
        terminal.writer().print("\u001b[?1006l"); terminal.flush();
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
