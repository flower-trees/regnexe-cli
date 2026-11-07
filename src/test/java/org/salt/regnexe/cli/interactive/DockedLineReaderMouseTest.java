package org.salt.regnexe.cli.interactive;

import org.jline.terminal.MouseEvent;
import org.jline.terminal.TerminalBuilder;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.Assert.*;

public class DockedLineReaderMouseTest {
    private MouseEvent parse(String report) throws Exception {
        try (var terminal = TerminalBuilder.builder().system(false).dumb(true)
                .streams(new ByteArrayInputStream(report.getBytes(StandardCharsets.US_ASCII)), new ByteArrayOutputStream()).build()) {
            return new DockedLineReader(terminal, Map.of()).readSgrMouse();
        }
    }
    @Test public void sgrReportsSupportWideTerminalsAndReleaseEvents() throws Exception {
        var drag = parse("32;300;24M");
        assertEquals(MouseEvent.Type.Dragged, drag.getType());
        assertEquals(MouseEvent.Button.Button1, drag.getButton());
        assertEquals(299, drag.getX());
        assertEquals(23, drag.getY());
        assertEquals(MouseEvent.Type.Released, parse("0;300;24m").getType());
        assertEquals(MouseEvent.Button.WheelUp, parse("64;1;1M").getButton());
        assertEquals(MouseEvent.Button.WheelDown, parse("65;1;1M").getButton());
    }
    @Test public void malformedMouseReportsAreIgnored() throws Exception {
        assertNull(parse("wrong"));
        assertNull(parse("9999999999999999999999;1;1M"));
        assertNull(parse("0;1M"));
    }
}
