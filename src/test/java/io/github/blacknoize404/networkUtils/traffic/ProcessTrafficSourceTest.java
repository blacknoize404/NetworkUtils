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

        ProcessTrafficSource.FallbackUsage both = new ProcessTrafficSource.FallbackUsage(empty, data);
        assertEquals(1, both.read().size());
        // Pinned: the primary is not asked again even though it exists.
        assertEquals(1, both.read().size());
        assertEquals(1, primaryReads.get());

        ProcessTrafficSource.FallbackUsage failing = new ProcessTrafficSource.FallbackUsage(() -> {
            throw new IOException("down");
        }, data);
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

        ProcessTrafficSource.FallbackUsage both = new ProcessTrafficSource.FallbackUsage(primary, fallback);
        both.read();
        both.read();

        assertEquals(0, fallbackReads.get());

    }

}
