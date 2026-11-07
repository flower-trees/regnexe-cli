package org.salt.regnexe.cli.interactive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ClipboardSupportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void nativeClipboardReceivesLiteralUtf8ThroughStdin() throws Exception {
        var script = temp.newFile("clipboard.sh").toPath();
        var output = temp.newFile("selection.txt").toPath();
        Files.writeString(script, "#!/bin/sh\ncat > \"$1\"\n");
        String text = "中文😀\n$(touch unexpected-file) `literal`  ";
        assertTrue(ClipboardSupport.run(List.of("/bin/sh", script.toString(), output.toString()),
                text, Duration.ofSeconds(2), new AtomicReference<>()));
        assertEquals(text, Files.readString(output));
    }

    @Test public void clipboardFailureAndTimeoutDoNotHangTheInputThread() {
        assertFalse(ClipboardSupport.run(List.of("/bin/sh", "-c", "exit 1"), "text",
                Duration.ofSeconds(1), new AtomicReference<>()));
        long start = System.nanoTime();
        assertFalse(ClipboardSupport.run(List.of("/bin/sh", "-c", "sleep 20"), "text",
                Duration.ofMillis(100), new AtomicReference<>()));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 3);
    }

    @Test public void remoteClipboardUsesEncodedOsc52WithoutClaimingSuccess() {
        String text = "中文\nno ANSI colors";
        var result = ClipboardSupport.osc52(text);
        String sequence = result.terminalSequence();
        assertTrue(sequence.startsWith("\u001b]52;c;"));
        assertEquals(text, new String(Base64.getDecoder().decode(
                sequence.substring(7, sequence.length() - 1)), StandardCharsets.UTF_8));
        assertTrue(result.message().contains("permission"));
        assertTrue(ClipboardSupport.remote(Map.of("SSH_CONNECTION", "host")));
        assertFalse(ClipboardSupport.remote(Map.of()));
        assertEquals("pbcopy", ClipboardSupport.commands("Mac OS X", Map.of()).get(0).get(0));
        assertEquals("wl-copy", ClipboardSupport.commands("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0")).get(0).get(0));
    }
}
