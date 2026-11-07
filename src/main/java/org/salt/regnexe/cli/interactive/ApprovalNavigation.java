package org.salt.regnexe.cli.interactive;

import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.MouseEvent;
import org.jline.utils.*;
import java.util.*;

/** Approval and session-picker widgets share the existing JLine reader and delegate to its editor when idle. */
public final class ApprovalNavigation implements AutoCloseable, DockedLineReader.Dock {
    private final LineReader reader;
    private final InteractiveApproval approvals;
    private final Status status;
    private final Terminal terminal;
    private final SessionPicker sessions;
    private final TranscriptViewport transcript = new TranscriptViewport();
    private final boolean color;
    private final boolean fullscreen;
    private boolean running;
    private boolean mouseEnabled;
    private boolean closed;
    private final ClipboardSupport clipboard = new ClipboardSupport();
    private long selectionVersion;
    private String copyFeedback = "";
    private String lastInput = "";
    private boolean scrollbarDragging;
    private List<AttributedString> inputLines = List.of(new AttributedString(""));
    private int inputCursor;
    private int inputStride = 1;
    private int reservedInput = 1;
    private int layoutWidth;
    private int layoutHeight;
    private int rows;
    private int columns;
    private String model = "";
    private String session = "";
    private String directory = "";
    private InteractiveApproval.Menu approvalMenu;
    private SessionPicker.Menu sessionMenu;
    private final Map<String, Binding> originals = new LinkedHashMap<>();
    private final List<String> widgets = new ArrayList<>();
    public ApprovalNavigation(LineReader reader, Terminal terminal, InteractiveApproval approvals, SessionPicker sessions, boolean color, boolean fullscreen, boolean mouseEnabled) {
        this.reader = reader; this.approvals = approvals; this.terminal = terminal; this.sessions = sessions; this.color = color; this.fullscreen = fullscreen; this.mouseEnabled = fullscreen && mouseEnabled;
        refreshSize();
        boolean canShowStatus = terminal.getSize().getRows() > 0 && terminal.getSize().getColumns() > 0
                && Arrays.stream(new InfoCmp.Capability[]{InfoCmp.Capability.change_scroll_region,
                        InfoCmp.Capability.save_cursor, InfoCmp.Capability.restore_cursor, InfoCmp.Capability.cursor_address})
                .allMatch(capability -> terminal.getStringCapability(capability) != null);
        status = canShowStatus ? Status.getStatus(terminal) : null;
        if (status != null && reader instanceof DockedLineReader docked) {
            if (fullscreen) {
                terminal.puts(InfoCmp.Capability.enter_ca_mode);
                terminal.puts(InfoCmp.Capability.clear_screen);
            }
            if (this.mouseEnabled) docked.setOpt(LineReader.Option.MOUSE);
            else { docked.unsetOpt(LineReader.Option.MOUSE); terminal.trackMouse(Terminal.MouseTracking.Off); }
            docked.setDock(this);
        }
        approvals.setView(menu -> { synchronized (this) { approvalMenu = menu; render(); } });
        sessions.setView(menu -> { synchronized (this) { sessionMenu = menu; render(); } });
        bind("\u001b[A", -1, LineReader.UP_LINE_OR_HISTORY);
        bind("\u001bOA", -1, LineReader.UP_LINE_OR_HISTORY);
        bind("\u001b[B", 1, LineReader.DOWN_LINE_OR_HISTORY);
        bind("\u001bOB", 1, LineReader.DOWN_LINE_OR_HISTORY);
        bind("\u001b[C", 1, LineReader.FORWARD_CHAR);
        bind("\u001bOC", 1, LineReader.FORWARD_CHAR);
        bind("\u001b[D", -1, LineReader.BACKWARD_CHAR);
        bind("\u001bOD", -1, LineReader.BACKWARD_CHAR);
        bind("\r", null, LineReader.ACCEPT_LINE);
        bind("\n", null, LineReader.ACCEPT_LINE);
        bindScroll("\u001b[5~", 1);
        bindScroll("\u001b[6~", -1);
        var mouse = reader.getKeyMaps().get(LineReader.MAIN);
        originals.put("\u001b[M", mouse.getBound("\u001b[M"));
        String mouseName = "rex-history-mouse"; widgets.add(mouseName);
        reader.getWidgets().put(mouseName, () -> {
            MouseEvent event = reader.readMouseEvent();
            synchronized (this) { handleMouse(event); }
            return true;
        });
        if (status != null && fullscreen) {
            mouse.bind(new Reference(mouseName), "\u001b[M");
            if (reader instanceof DockedLineReader docked) {
                String sgrName = mouseName + "-sgr"; widgets.add(sgrName);
                originals.put("\u001b[<", mouse.getBound("\u001b[<"));
                reader.getWidgets().put(sgrName, () -> {
                    MouseEvent event = docked.readSgrMouse();
                    if (event != null) synchronized (this) { handleMouse(event); }
                    return true;
                });
                mouse.bind(new Reference(sgrName), "\u001b[<");
            }
        }
        bindCopy("\u001bc");
        bindCopy("\u001b[99;6u");
    }

    private void handleMouse(MouseEvent event) {
        if (status == null || !mouseEnabled) return;
        int height = Math.max(1, rows - status.size());
        int x = Math.max(0, Math.min(columns - 3, event.getX()));
        int y = Math.max(0, Math.min(height - 1, event.getY()));
        if (event.getType() == MouseEvent.Type.Wheel && event.getY() < height) {
            transcript.scroll(event.getButton() == MouseEvent.Button.WheelUp ? 3 : -3, height);
        } else if (event.getType() == MouseEvent.Type.Pressed && event.getButton() == MouseEvent.Button.Button1) {
            if (event.getY() >= height) return;
            scrollbarDragging = event.getX() >= columns - 2;
            if (scrollbarDragging) transcript.seek(y, height);
            else {
                selectionVersion++; copyFeedback = "";
                transcript.beginSelection(x, y);
            }
        } else if (event.getType() == MouseEvent.Type.Dragged && event.getButton() == MouseEvent.Button.Button1) {
            if (scrollbarDragging) transcript.seek(y, height);
            else if (transcript.isDragging()) {
                if (event.getY() < 0 || event.getY() >= height) {
                    transcript.scroll(event.getY() < 0 ? 1 : -1, height);
                    paintHistory();
                }
                transcript.extendSelection(x, y);
            }
        } else if (event.getType() == MouseEvent.Type.Released) {
            if (transcript.isDragging()) {
                String text = transcript.finishSelection(x, y);
                if (!text.isEmpty()) copy(text);
            }
            scrollbarDragging = false;
        }
        render();
    }

    private void copy(String text) {
        long version = selectionVersion;
        copyFeedback = "Copying selection…";
        clipboard.copy(text, result -> {
            synchronized (this) {
                if (closed || selectionVersion != version) return;
                if (!result.terminalSequence().isEmpty()) terminal.writer().print(result.terminalSequence());
                copyFeedback = result.message();
                render();
            }
        });
    }

    private void bindCopy(String sequence) {
        var keys = reader.getKeyMaps().get(LineReader.MAIN);
        Binding original = keys.getBound(sequence);
        originals.put(sequence, original);
        String name = "rex-copy-selection-" + widgets.size(); widgets.add(name);
        reader.getWidgets().put(name, () -> {
            synchronized (this) {
                if (transcript.hasSelection()) { copy(transcript.selectedText()); render(); return true; }
            }
            if (original instanceof Reference reference) reader.callWidget(reference.name());
            else if (original instanceof Macro macro) reader.runMacro(macro.getSequence());
            return true;
        });
        keys.bind(new Reference(name), sequence);
    }

    private void bindScroll(String sequence, int direction) {
        var keys = reader.getKeyMaps().get(LineReader.MAIN);
        originals.put(sequence, keys.getBound(sequence));
        String name = "rex-history-scroll-" + widgets.size(); widgets.add(name);
        reader.getWidgets().put(name, () -> {
            if (status != null) synchronized (this) {
                int height = Math.max(1, rows - status.size());
                transcript.scroll(direction * Math.max(1, height - 1), height);
                paintHistory(); paintInput();
            }
            return true;
        });
        keys.bind(new Reference(name), sequence);
    }

    public synchronized boolean mouseEnabled() { return mouseEnabled; }

    public synchronized void mouse(boolean enabled) {
        selectionVersion++; transcript.clearSelection(); copyFeedback = "";
        mouseEnabled = status != null && fullscreen && enabled;
        if (mouseEnabled) {
            reader.setOpt(LineReader.Option.MOUSE);
            terminal.trackMouse(Terminal.MouseTracking.Button);
            terminal.writer().print("\u001b[?1006h");
        } else {
            reader.unsetOpt(LineReader.Option.MOUSE);
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.writer().print("\u001b[?1006l");
        }
        render();
    }

    public synchronized void running(boolean value) { running = value; render(); }

    /** Refresh metadata and anchor the next editable prompt above the reserved footer. */
    public synchronized String prepareInput(String prompt, String model, String session, String directory) {
        refreshSize();
        this.model = model; this.session = session; this.directory = directory;
        render();
        if (status == null) return prompt;
        return prompt;
    }

    @Override public synchronized void input(List<AttributedString> lines, int cursor, int stride) {
        refreshSize();
        String currentInput = reader.getBuffer().toString();
        boolean clear = !currentInput.equals(lastInput) && transcript.hasSelection();
        lastInput = currentInput;
        if (clear) { transcript.clearSelection(); selectionVersion++; copyFeedback = ""; }
        inputLines = lines.isEmpty() ? List.of(new AttributedString("")) : List.copyOf(lines);
        inputCursor = Math.max(0, cursor); inputStride = Math.max(1, stride);
        int count = Math.min(Math.max(1, Math.min(5, rows - 8)), inputLines.size());
        if (clear || count != reservedInput || layoutWidth != columns || layoutHeight != rows) render();
        else paintInput();
    }

    @Override public synchronized void output(String text) {
        if (fullscreen) { transcript.append(text); render(); }
        else {
            boolean interrupted = Thread.interrupted();
            try {
                terminal.puts(InfoCmp.Capability.save_cursor);
                terminal.puts(InfoCmp.Capability.cursor_address, Math.max(0, rows - status.size() - 1), 0);
                terminal.writer().print(text);
                if (!text.endsWith("\n")) terminal.writer().println();
                terminal.puts(InfoCmp.Capability.restore_cursor); terminal.flush();
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private void paintHistory() {
        if (!fullscreen) return;
        int height = Math.max(1, rows - status.size());
        var visible = transcript.view(Math.max(1, columns - (mouseEnabled ? 2 : 1)), height);
        int thumb = transcript.thumb(height);
        for (int i = 0; i < height; i++) {
            terminal.puts(InfoCmp.Capability.cursor_address, i, 0);
            terminal.writer().print(visible.get(i).toAnsi(terminal));
            terminal.puts(InfoCmp.Capability.clr_eol);
            if (mouseEnabled) {
                terminal.puts(InfoCmp.Capability.cursor_address, i, Math.max(0, columns - 1));
                terminal.writer().print(i == thumb ? "█" : "│");
            }
        }
    }

    private void render() {
        if (closed) return;
        boolean interrupted = Thread.interrupted();
        try { renderDock(); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void renderDock() {
        List<AttributedString> lines = new ArrayList<>();
        int firstInput = 0;
        int visibleInput = 0;
        if (status != null) {
            String activity = approvalMenu != null ? "Waiting for approval · arrows select · Enter confirm"
                    : running ? "● Running · Enter adds instruction · Esc interrupts"
                    : mouseEnabled ? "○ Ready · drag to select, release to copy · wheel/PgUp/PgDn scroll"
                    : "○ Ready · drag to select/copy · PgUp/PgDn scroll · /mouse on enables wheel";
            if (!copyFeedback.isEmpty()) activity = (approvalMenu != null ? "Waiting for approval" : running ? "● Running" : "○ Ready") + " · " + copyFeedback;
            else if (transcript.hasSelection()) activity += " · Alt+C copies selection again";
            if (transcript.isScrolled()) activity += " · viewing earlier output";
            lines.add(styled(activity, running || approvalMenu != null ? AttributedStyle.YELLOW : AttributedStyle.GREEN));
            lines.add(rule());
            int available = Math.max(1, Math.min(5, rows - 8));
            int cursorRow = inputCursor / inputStride;
            firstInput = Math.max(0, Math.min(inputLines.size() - 1, cursorRow - available + 1));
            visibleInput = Math.min(available, inputLines.size() - firstInput);
            reservedInput = Math.max(1, visibleInput);
            for (int i = 0; i < reservedInput; i++) lines.add(inputLines.get(firstInput + i));
            lines.add(rule());
        }
        if (approvalMenu != null) {
            lines.add(new AttributedString(approvalMenu.text()));
            lines.add(new AttributedString("←/→ or ↑/↓ select · Enter confirm · Esc interrupt · Ctrl+C exit"));
        } else if (sessionMenu != null) {
            int visible = Math.max(1, Math.min(8, rows - 9));
            sessionMenu.lines(visible).forEach(line -> lines.add(new AttributedString(line)));
            lines.add(new AttributedString("↑/↓ select · Enter open · Esc cancel"));
        }
        if (status != null) {
            lines.add(new AttributedString(singleLine(model) + " · " + singleLine(session) + " · " + singleLine(directory)));
            // Status owns only the reservation. Paint absolute rows ourselves so its
            // incremental display cache cannot erase input or metadata during editing.
            status.update(Collections.nCopies(lines.size(), new AttributedString("")));
            layoutWidth = columns; layoutHeight = rows;
            paintHistory();
            int top = Math.max(0, rows - status.size());
            int width = Math.max(1, columns - 1);
            for (int i = 0; i < lines.size(); i++) {
                terminal.puts(InfoCmp.Capability.cursor_address, top + i, 0);
                AttributedString line = lines.get(i);
                terminal.writer().print(line.columnSubSequence(0, Math.min(width, line.columnLength())).toAnsi(terminal));
                terminal.puts(InfoCmp.Capability.clr_eol);
            }
            terminal.puts(InfoCmp.Capability.cursor_address,
                    top + 2 + Math.max(0, Math.min(reservedInput - 1, inputCursor / inputStride - firstInput)),
                    Math.min(width, inputCursor % inputStride));
            terminal.flush();
        }
        else if (!lines.isEmpty()) reader.printAbove(String.join("\n", lines.stream().map(Object::toString).toList()));
    }

    private void paintInput() {
        int first = Math.max(0, Math.min(inputLines.size() - reservedInput,
                inputCursor / inputStride - reservedInput + 1));
        int top = Math.max(0, rows - status.size() + 2);
        int width = Math.max(1, columns - 1);
        for (int i = 0; i < reservedInput; i++) {
            terminal.puts(InfoCmp.Capability.cursor_address, top + i, 0);
            AttributedString line = inputLines.get(first + i);
            terminal.writer().print(line.columnSubSequence(0, Math.min(width, line.columnLength())).toAnsi(terminal));
            terminal.puts(InfoCmp.Capability.clr_eol);
        }
        terminal.puts(InfoCmp.Capability.cursor_address,
                top + Math.max(0, Math.min(reservedInput - 1, inputCursor / inputStride - first)),
                Math.min(width, inputCursor % inputStride));
        terminal.flush();
    }

    // Only the input thread queries the TTY. A cancelled worker must never run stty.
    private void refreshSize() {
        var size = terminal.getSize();
        rows = Math.max(1, size.getRows()); columns = Math.max(1, size.getColumns());
    }

    private AttributedString rule() {
        return styled("─".repeat(Math.max(1, columns - 1)), AttributedStyle.CYAN);
    }

    private AttributedString styled(String text, int foreground) {
        return new AttributedString(text, color ? AttributedStyle.DEFAULT.foreground(foreground) : AttributedStyle.DEFAULT);
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ");
    }

    private void bind(String sequence, Integer delta, String fallback) {
        var keys = reader.getKeyMaps().get(LineReader.MAIN);
        Binding original = keys.getBound(sequence);
        originals.put(sequence, original);
        String name = "rex-approval-" + widgets.size(); widgets.add(name);
        reader.getWidgets().put(name, () -> {
            boolean inputEmpty = reader.getBuffer().length() == 0;
            boolean handled;
            if (delta == null && sessions.acceptSelection(inputEmpty)) {
                // Return to the REPL dispatcher so opening uses the normal /resume path.
                reader.callWidget(LineReader.ACCEPT_LINE);
                handled = true;
            } else {
                handled = delta == null ? approvals.acceptSelection(inputEmpty)
                        : sessions.move(delta) || approvals.move(delta);
            }
            if (!handled) {
                if (original instanceof Reference reference) reader.callWidget(reference.name());
                else if (original instanceof Macro macro) reader.runMacro(macro.getSequence());
                else reader.callWidget(fallback);
            }
            return true;
        });
        keys.bind(new Reference(name), sequence);
    }
    @Override public synchronized void close() {
        closed = true;
        clipboard.close();
        approvals.setView(null);
        sessions.setView(menu -> {});
        sessions.cancel();
        if (reader instanceof DockedLineReader docked) docked.setDock(null);
        if (status != null) {
            status.update(List.of()); terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.writer().print("\u001b[?1006l");
            reader.unsetOpt(LineReader.Option.MOUSE);
            if (fullscreen) terminal.puts(InfoCmp.Capability.exit_ca_mode); terminal.flush();
        }
        var keys = reader.getKeyMaps().get(LineReader.MAIN);
        originals.forEach((sequence, binding) -> {
            if (binding == null) keys.unbind(sequence); else keys.bind(binding, sequence);
        });
        widgets.forEach(name -> reader.getWidgets().remove(name));
    }
}
