package io.github.blacknoize404.networkUtils.traffic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

}
