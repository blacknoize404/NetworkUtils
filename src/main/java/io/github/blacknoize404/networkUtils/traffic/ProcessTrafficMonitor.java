package io.github.blacknoize404.networkUtils.traffic;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-application traffic: how many bytes each process moved in every
 * interval — the process-level counterpart of {@link TrafficMonitor}, built
 * on {@link ProcessTrafficSource}. Samples arrive on the sampling thread, so
 * UI consumers must hop to their own thread.
 */
public final class ProcessTrafficMonitor implements AutoCloseable {

    /** The bytes a process moved in the interval that ended at {@code time}. */
    public record ProcessSample(Instant time, String process, long received, long sent) {
    }

    private final ProcessTrafficSource source;
    private final ReadOnlyObjectWrapper<List<ProcessSample>> samples = new ReadOnlyObjectWrapper<>();
    private final Object pollLock = new Object();
    private ScheduledExecutorService executor;
    private int intervalSeconds = 1;
    private Map<String, long[]> previous;

    public ProcessTrafficMonitor() {
        this(ProcessTrafficSource.create().orElse(null));
    }

    /** With a source to read from; {@link #isAvailable()} is false with {@code null}. */
    public ProcessTrafficMonitor(ProcessTrafficSource source) {
        this.source = source;
    }

    /** Whether this system can attribute traffic to processes. */
    public boolean isAvailable() {
        return source != null;
    }

    /** The processes that moved bytes in the last interval. Updated from the sampling thread. */
    public ReadOnlyObjectProperty<List<ProcessSample>> samplesProperty() {
        return samples.getReadOnlyProperty();
    }

    /**
     * Changes the sampling interval. The byte reference is kept, so no interval
     * of traffic is lost when the rate changes while running.
     */
    public synchronized void setIntervalSeconds(int seconds) {

        if (seconds < 1) {
            throw new IllegalArgumentException("The interval must be at least one second");
        }
        if (seconds == intervalSeconds) {
            return;
        }
        intervalSeconds = seconds;

        if (executor != null) {
            executor.shutdownNow();
            executor = schedule();
        }

    }

    public synchronized void start() {

        if (executor != null || source == null) {
            return;
        }
        previous = null;
        executor = schedule();

    }

    public synchronized void stop() {

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

    }

    @Override
    public void close() {
        stop();
    }

    private ScheduledExecutorService schedule() {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "process-traffic-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::poll, 0, intervalSeconds, TimeUnit.SECONDS);
        return scheduler;

    }

    /** Package-private so tests can drive a sample directly instead of sleeping on the scheduler. */
    void poll() {

        synchronized (pollLock) {
            try {
                List<ProcessTrafficSource.ProcessCounters> all = source.read();
                if (previous != null) {
                    List<ProcessSample> moved = new ArrayList<>();
                    for (ProcessTrafficSource.ProcessCounters counters : all) {
                        long[] before = previous.get(counters.process());
                        long received = counters.received() - (before != null ? before[0] : 0);
                        long sent = counters.sent() - (before != null ? before[1] : 0);
                        // A counter below the last reading means a reused identity: rebased, not negative.
                        if (received > 0 || sent > 0) {
                            moved.add(new ProcessSample(Instant.now(), counters.process(),
                                    Math.max(0, received), Math.max(0, sent)));
                        }
                    }
                    if (!moved.isEmpty()) {
                        samples.set(List.copyOf(moved));
                    }
                }
                previous = new HashMap<>();
                for (ProcessTrafficSource.ProcessCounters counters : all) {
                    previous.put(counters.process(), new long[]{counters.received(), counters.sent()});
                }
            } catch (Exception e) {
                // A failed reading must not kill the scheduler; the next one retries.
            }
        }

    }

}
