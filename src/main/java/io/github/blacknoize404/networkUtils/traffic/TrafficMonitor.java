package io.github.blacknoize404.networkUtils.traffic;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Measures the system's download and upload speed.
 * <p>
 * The sampling interval is adjustable ({@link #setIntervalSeconds(int)}): on
 * Windows every sample spawns a process, so a monitor running in the
 * background should sample less often than one feeding a live view. Samples
 * are published through {@link #sampleProperty()} — from the sampling thread,
 * so UI consumers must hop to their own thread — and the latest ones are kept
 * in {@link #history()} so late-attaching views can rehydrate.
 */
public final class TrafficMonitor implements AutoCloseable {

    /** Speeds in bytes per second at the instant {@code time}. */
    public record Sample(Instant time, double downloadBytesPerSecond, double uploadBytesPerSecond) {
    }

    private static final int HISTORY_LIMIT = 512;

    private final Optional<TrafficSource> source = TrafficSource.create();
    private final ReadOnlyObjectWrapper<Sample> sample = new ReadOnlyObjectWrapper<>();
    private final Deque<Sample> history = new ConcurrentLinkedDeque<>();

    private final Object pollLock = new Object();
    private ScheduledExecutorService executor;
    private int intervalSeconds = 1;
    private long[] previousTotals;
    private long previousNanos;

    /** Whether this operating system provides traffic counters. */
    public boolean isAvailable() {
        return source.isPresent();
    }

    public ReadOnlyObjectProperty<Sample> sampleProperty() {
        return sample.getReadOnlyProperty();
    }

    /**
     * @return an immutable copy of the latest {@value #HISTORY_LIMIT} samples, oldest first
     */
    public List<Sample> history() {
        return List.copyOf(history);
    }

    public synchronized void start() {

        if (executor != null || source.isEmpty()) {
            return;
        }

        previousTotals = null;
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

    private ScheduledExecutorService schedule() {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "traffic-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::poll, 0, intervalSeconds, TimeUnit.SECONDS);
        return scheduler;

    }

    private void poll() {

        synchronized (pollLock) {
            try {
                long[] totals = source.orElseThrow().readTotals();
                long now = System.nanoTime();

                // The first reading only sets the reference; if a counter restarts
                // (adapter reconnected) that interval is discarded.
                if (previousTotals != null && totals[0] >= previousTotals[0] && totals[1] >= previousTotals[1]) {
                    double seconds = (now - previousNanos) / 1e9;
                    publish(new Sample(
                            Instant.now(),
                            (totals[0] - previousTotals[0]) / seconds,
                            (totals[1] - previousTotals[1]) / seconds
                    ));
                }

                previousTotals = totals;
                previousNanos = now;
            } catch (Exception e) {
                // A failed reading must not kill the scheduler; the next one retries.
            }
        }

    }

    private void publish(Sample value) {

        while (history.size() >= HISTORY_LIMIT) history.pollFirst();
        history.addLast(value);
        sample.set(value);

    }

}
