package org.salt.regnexe.cli.interactive;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded logical transcript. Selection uses text offsets, never screen padding or ANSI bytes. */
final class TranscriptViewport {
    private record Line(long id, AttributedString text) {}
    private record Row(long id, int start, AttributedString text) {}
    private record Position(long id, int index) implements Comparable<Position> {
        public int compareTo(Position other) {
            int line = Long.compare(id, other.id);
            return line != 0 ? line : Integer.compare(index, other.index);
        }
    }
    private final List<Line> lines = new ArrayList<>();
    private long nextId;
    private int offset;
    private int width = 80;
    private List<Row> visible = List.of();
    private Position anchor, anchorEnd, focus, focusEnd;
    private boolean dragging, moved;

    private List<Row> wrapped() {
        List<Row> result = new ArrayList<>();
        for (var line : lines) {
            if (line.text.length() == 0) result.add(new Row(line.id, 0, line.text));
            else {
                int start = 0;
                for (var part : line.text.columnSplitLength(width)) {
                    if (part.length() > 0) result.add(new Row(line.id, start, part));
                    start += part.length();
                }
            }
        }
        return result;
    }
    void append(String text) {
        boolean frozen = offset > 0 || anchor != null;
        int before = frozen ? wrapped().size() : 0;
        String normalized = text.replace("\r", "");
        if (normalized.endsWith("\n")) normalized = normalized.substring(0, normalized.length() - 1);
        for (String line : normalized.split("\n", -1)) lines.add(new Line(nextId++, AttributedString.fromAnsi(line)));
        if (lines.size() > 10000) {
            lines.subList(0, lines.size() - 10000).clear();
            if (anchor != null && (anchor.id < lines.get(0).id || focus.id < lines.get(0).id)) clearSelection();
        }
        if (frozen) offset += Math.max(0, wrapped().size() - before);
    }
    void scroll(int amount, int height) {
        offset = Math.max(0, Math.min(Math.max(0, wrapped().size() - height), offset + amount));
    }
    void seek(int row, int height) {
        int maximum = Math.max(0, wrapped().size() - height);
        offset = height <= 1 ? 0 : maximum - maximum * Math.max(0, Math.min(height - 1, row)) / (height - 1);
    }
    List<AttributedString> view(int columns, int height) {
        width = Math.max(1, columns);
        var all = wrapped();
        offset = Math.min(offset, Math.max(0, all.size() - height));
        int end = all.size() - offset;
        int start = Math.max(0, end - height);
        visible = List.copyOf(all.subList(start, end));
        List<AttributedString> result = new ArrayList<>();
        for (var row : visible) result.add(highlight(row));
        while (result.size() < height) result.add(new AttributedString(""));
        return result;
    }
    private Position at(int x, int y, boolean after) {
        if (visible.isEmpty() || y >= visible.size()) return null;
        Row row = visible.get(Math.max(0, y));
        String text = row.text.toString();
        BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
        chars.setText(text);
        int column = 0, first = chars.first();
        for (int last = chars.next(); last != BreakIterator.DONE; first = last, last = chars.next()) {
            int size = row.text.subSequence(first, last).columnLength();
            if (Math.max(0, x) < column + size) return new Position(row.id, row.start + (after ? last : first));
            column += size;
        }
        return new Position(row.id, row.start + text.length());
    }
    void beginSelection(int x, int y) {
        clearSelection();
        anchor = at(x, y, false); anchorEnd = at(x, y, true);
        focus = anchor; focusEnd = anchorEnd;
        dragging = anchor != null;
    }
    void extendSelection(int x, int y) {
        if (!dragging) return;
        Position point = at(x, Math.min(Math.max(0, y), visible.size() - 1), false);
        if (point == null) return;
        focus = point; focusEnd = at(x, Math.min(Math.max(0, y), visible.size() - 1), true);
        moved |= !focus.equals(anchor);
    }
    String finishSelection(int x, int y) {
        extendSelection(x, y); dragging = false;
        if (!hasSelection()) { clearSelection(); return ""; }
        return selectedText();
    }
    boolean isDragging() { return dragging; }
    boolean hasSelection() { return moved && anchor != null && focus != null; }
    void clearSelection() { anchor = anchorEnd = focus = focusEnd = null; moved = dragging = false; }
    private Position first() { return anchor.compareTo(focus) <= 0 ? anchor : focus; }
    private Position last() { return anchor.compareTo(focus) <= 0 ? focusEnd : anchorEnd; }
    String selectedText() {
        if (!hasSelection()) return "";
        Position first = first(), last = last();
        StringBuilder result = new StringBuilder();
        boolean started = false;
        for (var line : lines) {
            if (line.id < first.id || line.id > last.id) continue;
            if (started) result.append('\n');
            int start = line.id == first.id ? first.index : 0;
            int end = line.id == last.id ? last.index : line.text.length();
            result.append(line.text.toString(), start, end);
            started = true;
        }
        return result.toString();
    }
    private AttributedString highlight(Row row) {
        if (!hasSelection()) return row.text;
        Position first = first(), last = last();
        if (row.id < first.id || row.id > last.id) return row.text;
        int start = row.id == first.id ? first.index : 0;
        int end = row.id == last.id ? last.index : Integer.MAX_VALUE;
        var result = new AttributedStringBuilder();
        for (int i = 0; i < row.text.length(); i++) {
            var style = row.text.styleAt(i);
            result.style(row.start + i >= start && row.start + i < end ? style.inverse() : style);
            result.append(row.text.charAt(i));
        }
        return result.toAttributedString();
    }
    int thumb(int height) {
        int maximum = Math.max(0, wrapped().size() - height);
        return maximum == 0 ? height - 1 : (maximum - offset) * (height - 1) / maximum;
    }
    boolean isScrolled() { return offset > 0; }
}
