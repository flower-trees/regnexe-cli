package org.salt.regnexe.cli.interactive;

import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.utils.*;
import java.util.*;

/** Approval widgets share the existing JLine reader and delegate to its editor when idle. */
public final class ApprovalNavigation implements AutoCloseable {
    private final LineReader reader;
    private final InteractiveApproval approvals;
    private final Status status;
    private final Map<String, Binding> originals = new LinkedHashMap<>();
    private final List<String> widgets = new ArrayList<>();
    public ApprovalNavigation(LineReader reader, Terminal terminal, InteractiveApproval approvals) {
        this.reader = reader; this.approvals = approvals;
        boolean canShowStatus = terminal.getSize().getRows() > 0 && terminal.getSize().getColumns() > 0
                && Arrays.stream(new InfoCmp.Capability[]{InfoCmp.Capability.change_scroll_region,
                        InfoCmp.Capability.save_cursor, InfoCmp.Capability.restore_cursor, InfoCmp.Capability.cursor_address})
                .allMatch(capability -> terminal.getStringCapability(capability) != null);
        status = canShowStatus ? Status.getStatus(terminal) : null;
        approvals.setView(menu -> {
            if (status == null) {
                if (menu != null) reader.printAbove(menu.text() + "\nArrow keys select; Enter confirms.");
            } else if (menu == null) status.update(List.of());
            else status.update(List.of(new AttributedString(menu.text()),
                    new AttributedString("←/→ or ↑/↓ select · Enter confirm · Esc interrupt · Ctrl+C exit")));
        });
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
    }
    private void bind(String sequence, Integer delta, String fallback) {
        var keys = reader.getKeyMaps().get(LineReader.MAIN);
        Binding original = keys.getBound(sequence);
        originals.put(sequence, original);
        String name = "rex-approval-" + widgets.size(); widgets.add(name);
        reader.getWidgets().put(name, () -> {
            boolean handled = delta == null ? approvals.acceptSelection(reader.getBuffer().length() == 0) : approvals.move(delta);
            if (!handled) {
                if (original instanceof Reference reference) reader.callWidget(reference.name());
                else if (original instanceof Macro macro) reader.runMacro(macro.getSequence());
                else reader.callWidget(fallback);
            }
            return true;
        });
        keys.bind(new Reference(name), sequence);
    }
    @Override public void close() {
        approvals.setView(null);
        if (status != null) status.update(List.of());
        var keys = reader.getKeyMaps().get(LineReader.MAIN);
        originals.forEach((sequence, binding) -> {
            if (binding == null) keys.unbind(sequence); else keys.bind(binding, sequence);
        });
        widgets.forEach(name -> reader.getWidgets().remove(name));
    }
}
