package io.github.blacknoize404.networkUtils.traffic;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTrafficMonitorTest {

    /** A source that plays a script: each poll returns the next list. */
    private static final class FakeSource implements ProcessTrafficSource {

        private final Deque<List<ProcessTrafficSource.ProcessCounters>> script;

        FakeSource(List<List<ProcessTrafficSource.ProcessCounters>> script) {
            this.script = new ArrayDeque<>(script);
        }

        @Override
        public List<ProcessTrafficSource.ProcessCounters> read() {
            return script.isEmpty() ? List.of() : script.pollFirst();
        }

    }

    private static ProcessTrafficSource.ProcessCounters counters(String process, long received, long sent) {
        return new ProcessTrafficSource.ProcessCounters(process, received, sent);
    }

    @Test
    void deltasArePublishedPerProcess() {

        ProcessTrafficMonitor monitor = new ProcessTrafficMonitor(new FakeSource(List.of(
                List.of(counters("a.exe", 100, 10)),
                List.of(counters("a.exe", 150, 10), counters("b.exe", 5, 0)),
                List.of(counters("a.exe", 150, 40)))));

        List<ProcessTrafficMonitor.ProcessSample> seen = new ArrayList<>();
        monitor.samplesProperty().addListener((observable, old, samples) -> seen.addAll(samples));

        // The first reading only sets the reference: nothing moves yet.
        monitor.poll();
        assertTrue(seen.isEmpty());

        monitor.poll();
        assertEquals(2, seen.size());
        assertEquals("a.exe", seen.get(0).process());
        assertEquals(50, seen.get(0).received());
        assertEquals(0, seen.get(0).sent());
        assertEquals("b.exe", seen.get(1).process());
        assertEquals(5, seen.get(1).received());

        // A process that vanishes publishes nothing; deltas stay on the ones that moved.
        monitor.poll();
        assertEquals(3, seen.size());
        assertEquals("a.exe", seen.get(2).process());
        assertEquals(0, seen.get(2).received());
        assertEquals(30, seen.get(2).sent());

    }

    @Test
    void aRebasedCounterDoesNotPublishNegativeBytes() {

        ProcessTrafficMonitor monitor = new ProcessTrafficMonitor(new FakeSource(List.of(
                List.of(counters("a.exe", 500, 10)),
                List.of(counters("a.exe", 20, 10)))));

        List<ProcessTrafficMonitor.ProcessSample> seen = new ArrayList<>();
        monitor.samplesProperty().addListener((observable, old, samples) -> seen.addAll(samples));
        monitor.poll();
        monitor.poll();
        assertTrue(seen.isEmpty());

    }

    @Test
    void aMissingSourceMakesTheMonitorUnavailable() {
        assertFalse(new ProcessTrafficMonitor(null).isAvailable());
    }

}
