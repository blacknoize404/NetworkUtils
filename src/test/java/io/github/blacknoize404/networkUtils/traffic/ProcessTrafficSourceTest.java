package io.github.blacknoize404.networkUtils.traffic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTrafficSourceTest {

    @Test
    void readsProcessCountersOnWindows() {

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.startsWith("windows")) {
            return;
        }

        var source = ProcessTrafficSource.create();
        assertTrue(source.isPresent());
        List<ProcessTrafficSource.ProcessCounters> first = assertDoesNotThrow(() -> source.get().read());
        assertNotNull(first);
        // The machine always has some connections alive; retired-and-live bookkeeping must not break the second read either.
        List<ProcessTrafficSource.ProcessCounters> second = assertDoesNotThrow(() -> source.get().read());
        assertNotNull(second);

    }

    @Test
    void reportsNoSourceOutsideWindows() {

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.startsWith("windows")) {
            return;
        }
        assertFalse(ProcessTrafficSource.create().isPresent());

    }

    @Test
    void theFallbackAnswersWhenThePrimaryIsEmptyOrFails() throws IOException {

        AtomicInteger primaryReads = new AtomicInteger();
        ProcessTrafficSource empty = () -> {
            primaryReads.incrementAndGet();
            return List.of();
        };
        ProcessTrafficSource data = () -> List.of(new ProcessTrafficSource.ProcessCounters("a.exe", 10, 5));

        ProcessTrafficSource.FallbackUsage both = new ProcessTrafficSource.FallbackUsage(new java.util.ArrayList<>(List.of(empty, data)));
        assertEquals(1, both.read().size());
        // Pinned: the primary is not asked again even though it exists.
        assertEquals(1, both.read().size());
        assertEquals(1, primaryReads.get());

        ProcessTrafficSource.FallbackUsage failing = new ProcessTrafficSource.FallbackUsage(new java.util.ArrayList<>(List.of(() -> {
            throw new IOException("down");
        }, data)));
        assertEquals(1, failing.read().size());
        assertEquals(1, failing.read().size());

    }

    @Test
    void thePrimaryWinsWhileItProducesData() throws IOException {

        AtomicInteger fallbackReads = new AtomicInteger();
        ProcessTrafficSource primary = () -> List.of(new ProcessTrafficSource.ProcessCounters("a.exe", 10, 5));
        ProcessTrafficSource fallback = () -> {
            fallbackReads.incrementAndGet();
            return List.of(new ProcessTrafficSource.ProcessCounters("b.exe", 1, 1));
        };

        ProcessTrafficSource.FallbackUsage both = new ProcessTrafficSource.FallbackUsage(new java.util.ArrayList<>(List.of(primary, fallback)));
        both.read();
        both.read();

        assertEquals(0, fallbackReads.get());

    }

    @Test
    void nettopRowsAreParsedByProcess() {

        List<MacOsNettop.Row> rows = MacOsNettop.parse(List.of(
                "time,interface,state,bytes_in,bytes_out",
                "curl.1234,en0,4,1000,200",
                "curl.1234,en1,4,3000,50",
                "Safari.567,,0,0",
                "not-a-process"));

        assertEquals(3, rows.size());
        assertEquals(1000, rows.get(0).received());
        assertEquals(200, rows.get(0).sent());
        assertEquals("curl.1234", rows.get(0).key());

    }

    @Test
    void ssWithoutTheNetidColumnIsParsedToo() {

        // «ss -tipnaH» real: con -t solo no hay columna Netid y la línea empieza por el estado
        List<LinuxSs.SocketRow> rows = LinuxSs.parse(List.of(
                "ESTAB  0  0  192.168.1.5:55332  93.184.216.34:443  users:((\"curl\",pid=1234,fd=3))",
                "	 cubic wscale:7,7 bytes_sent:5000 bytes_received:90000 segs_out:10",
                "LISTEN 0  4096  0.0.0.0:22  0.0.0.0:*  users:((\"sshd\",pid=1,fd=3))",
                "	 cubic bytes_sent:1 bytes_received:1"));

        assertEquals(1, rows.size());
        assertEquals("curl", rows.get(0).process());
        assertEquals("192.168.1.5:55332", rows.get(0).local());
        assertEquals("93.184.216.34:443", rows.get(0).peer());
        assertEquals(5000, rows.get(0).sent());
        assertEquals(90000, rows.get(0).received());

    }

    @Test
    void ssBlocksAreParsedWithBytesAndOwner() {

        List<LinuxSs.SocketRow> rows = LinuxSs.parse(List.of(
                "tcp   ESTAB  0  0  192.168.1.5:55332  93.184.216.34:443  users:((\"curl\",pid=1234,fd=3))",
                "\t bytes_sent:5000 bytes_received:90000 ts sack",
                "tcp   LISTEN 0  4096  0.0.0.0:22  0.0.0.0:*  users:((\"sshd\",pid=1,fd=3))",
                "tcp   ESTAB  0  0  192.168.1.5:55333  93.184.216.34:443",
                "\t bytes_sent:100 bytes_received:200",
                "udp   UNCONN 0  0  192.168.1.5:68  192.168.1.1:67"));

        assertEquals(2, rows.size());
        assertEquals("curl", rows.get(0).process());
        assertEquals(1234, rows.get(0).pid());
        assertEquals(5000, rows.get(0).sent());
        assertEquals(90000, rows.get(0).received());
        assertEquals("192.168.1.5:55332", rows.get(0).local());
        // The second socket has no owner — unattributable but counted.
        assertEquals(100, rows.get(1).sent());
        assertEquals(null, rows.get(1).pid());

    }

}
