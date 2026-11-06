package org.salt.regnexe.cli.interactive;

import org.jline.utils.AttributedString;
import java.util.ArrayList;
import java.util.List;

/** Bounded transcript with an independent scroll position; never scrolls the input dock. */
final class TranscriptViewport {
    private final List<AttributedString> lines = new ArrayList<>();
    private int offset;
    private int width = 80;
    private List<AttributedString> wrapped() {
        List<AttributedString> result = new ArrayList<>();
        for (var line : lines) {
            if (line.length() == 0) result.add(line);
            else result.addAll(line.columnSplitLength(width));
        }
        return result;
    }
    void append(String text) {
        int before = offset > 0 ? wrapped().size() : 0;
        String normalized = text.replace("\r", "");
        if (normalized.endsWith("\n")) normalized = normalized.substring(0, normalized.length() - 1);
        for (String line : normalized.split("\n", -1)) lines.add(AttributedString.fromAnsi(line));
        if (lines.size() > 10000) lines.subList(0, lines.size() - 10000).clear();
        if (offset > 0) offset += Math.max(0, wrapped().size() - before);
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
        List<AttributedString> result = new ArrayList<>(all.subList(start, end));
        while (result.size() < height) result.add(new AttributedString(""));
        return result;
    }
    int thumb(int height) {
        int maximum = Math.max(0, wrapped().size() - height);
        return maximum == 0 ? height - 1 : (maximum - offset) * (height - 1) / maximum;
    }
    boolean isScrolled() { return offset > 0; }
}
