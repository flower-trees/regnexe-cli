package org.salt.regnexe.cli.interactive;

import org.jline.reader.LineReader;
import java.io.Writer;

/** Render background messages above the editable JLine buffer. */
public final class AboveLineWriter extends Writer {
    private final LineReader reader;
    private final StringBuilder buffer = new StringBuilder();
    public AboveLineWriter(LineReader reader) { this.reader = reader; }
    @Override public synchronized void write(char[] chars, int offset, int length) { buffer.append(chars, offset, length); }
    @Override public synchronized void flush() {
        if (!buffer.isEmpty()) { String text = buffer.toString(); buffer.setLength(0); reader.printAbove(text); }
    }
    @Override public void close() { flush(); }
}
