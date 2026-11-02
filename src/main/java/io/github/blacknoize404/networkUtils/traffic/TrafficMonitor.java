package io.github.blacknoize404.networkUtils.traffic;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Measures the download and upload speed of the system's network adapters: the
 * one carrying the default route, a chosen one or all of them
 * ({@link #setSelection(TrafficSelection)}).
 * <p>
 * The sampling interval is adjustable ({@link #setIntervalSeconds(int)}): a
 * monitor running in the background can sample less often than one feeding a
 * live view. Samples are published through {@link #sampleProperty()} — from the
 * sampling thread, so UI consumers must hop to their own thread — and the
 * latest ones are kept in {@link #history()} so late-attaching views can
 * rehydrate.
 */
public final class TrafficMonitor implements AutoCloseable {

    /**
     * Speeds in bytes per second at the instant {@code time}, and the bytes received and sent in the
     * interval that ended then (to add up how much data was used).
     */
    public record Sample(Instant time, double downloadBytesPerSecond, double uploadBytesPerSecond,
                         long downloadBytes, long uploadBytes) {

        public Sample(Instant time, double downloadBytesPerSecond, double uploadBytesPerSecond) {
            this(time, downloadBytesPerSecond, uploadBytesPerSecond, 0, 0);
        }

    }

    private static final int HISTORY_LIMIT = 2048;

    private final Optional<TrafficSource> source = TrafficSource.create();
    private final ReadOnlyObjectWrapper<Sample> sample = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyObjectWrapper<TrafficInterface> activeInterface = new ReadOnlyObjectWrapper<>();
    private final Deque<Sample> history = new ConcurrentLinkedDeque<>();

    private final Object pollLock = new Object();
    private ScheduledExecutorService executor;
    private int intervalSeconds = 1;
    private Map<String, long[]> previous;
    private long[] previousTotals;
    private long previousNanos;

    private volatile TrafficSelection selection = TrafficSelection.AUTO;
    private volatile List<TrafficInterface> interfaces = List.of();

    /** Whether this operating system provides traffic counters. */
    public boolean isAvailable() {
        return source.isPresent();
    }

    /** Whether the adapters can be told apart; otherwise every selection measures the whole system. */
    public boolean supportsInterfaceSelection() {

        try {
            return source.isPresent() && !source.get().readInterfaces().isEmpty();
        } catch (IOException e) {
            return false;
        }

    }

    public ReadOnlyObjectProperty<Sample> sampleProperty() {
        return sample.getReadOnlyProperty();
    }

    public TrafficSelection selection() {
        return selection;
    }

    /** Takes effect on the next sample; no interval of traffic is lost. */
    public void setSelection(TrafficSelection selection) {
        this.selection = Objects.requireNonNull(selection);
    }

    /**
     * The adapter being measured, or {@code null} when the measurement adds up
     * several or none is known yet. Updated from the sampling thread.
     */
    public ReadOnlyObjectProperty<TrafficInterface> activeInterfaceProperty() {
        return activeInterface.getReadOnlyProperty();
    }

    /**
     * Reads the adapters present right now, connected ones first.
     *
     * @return an immutable list, empty when this system cannot tell the adapters apart
     */
    public List<TrafficInterface> refreshInterfaces() {

        if (source.isEmpty()) {
            return List.of();
        }
        try {
            List<TrafficInterface> list = new ArrayList<>();
            source.get().readInterfaces().forEach(c -> list.add(c.info()));
            list.sort(Comparator.comparing((TrafficInterface i) -> !i.up())
                    .thenComparing(TrafficInterface::name, String.CASE_INSENSITIVE_ORDER));
            interfaces = List.copyOf(list);
        } catch (IOException e) {
            // Keeps the previous list.
        }
        return interfaces;

    }

    /** The adapters found by the last {@link #refreshInterfaces()}. */
    public List<TrafficInterface> interfaces() {
        return interfaces;
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

        previous = null;
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
                List<TrafficSource.Counters> all = source.orElseThrow().readInterfaces();
                long now = System.nanoTime();

                if (all.isEmpty()) {
                    pollTotals(now);
                } else {
                    pollInterfaces(all, now);
                }

                previousNanos = now;
            } catch (Exception e) {
                // A failed reading must not kill the scheduler; the next one retries.
            }
        }

    }

    /** Without per-adapter counters only the system total is known. */
    private void pollTotals(long now) throws IOException {

        long[] totals = source.orElseThrow().readTotals();

        // The first reading only sets the reference; if a counter restarts
        // (adapter reconnected) that interval is discarded.
        if (previousTotals != null && totals[0] >= previousTotals[0] && totals[1] >= previousTotals[1]) {
            double seconds = (now - previousNanos) / 1e9;
            publish(new Sample(
                    Instant.now(),
                    (totals[0] - previousTotals[0]) / seconds,
                    (totals[1] - previousTotals[1]) / seconds,
                    totals[0] - previousTotals[0],
                    totals[1] - previousTotals[1]
            ));
        }
        previousTotals = totals;
        activeInterface.set(null);

    }

    private void pollInterfaces(List<TrafficSource.Counters> all, long now) {

        TrafficSelection current = selection;
        List<TrafficSource.Counters> target = resolve(current, all);

        if (previous != null) {
            double seconds = (now - previousNanos) / 1e9;
            long received = 0;
            long sent = 0;
            boolean measured = false;

            for (TrafficSource.Counters c : target) {
                long[] before = previous.get(c.info().id());
                // An adapter that just appeared, or whose counter restarted, skips this interval.
                if (before != null && c.received() >= before[0] && c.sent() >= before[1]) {
                    received += c.received() - before[0];
                    sent += c.sent() - before[1];
                    measured = true;
                }
            }
            if (measured) {
                publish(new Sample(Instant.now(), received / seconds, sent / seconds, received, sent));
            }
        }

        // Every adapter is remembered, so changing the selection needs no new reference.
        Map<String, long[]> snapshot = new HashMap<>();
        all.forEach(c -> snapshot.put(c.info().id(), new long[]{c.received(), c.sent()}));
        previous = snapshot;

        boolean single = current.mode() != TrafficSelection.Mode.ALL && target.size() == 1;
        activeInterface.set(single ? target.get(0).info() : null);

    }

    private static List<TrafficSource.Counters> resolve(TrafficSelection selection, List<TrafficSource.Counters> all) {

        switch (selection.mode()) {
            case ONLY -> {
                return all.stream().filter(c -> c.info().id().equals(selection.interfaceId())).toList();
            }
            case AUTO -> {
                Optional<TrafficSource.Counters> route = defaultRoute(all);
                if (route.isPresent()) {
                    return List.of(route.get());
                }
            }
            default -> {
            }
        }
        // ALL, or AUTO without a route (offline): everything there is.
        return all;

    }

    /** The adapter the system would use to reach the Internet, found without sending anything. */
    private static Optional<TrafficSource.Counters> defaultRoute(List<TrafficSource.Counters> all) {

        try (DatagramSocket socket = new DatagramSocket()) {
            // Connecting a UDP socket only picks the route.
            socket.connect(InetAddress.getByAddress(new byte[]{1, 1, 1, 1}), 53);
            NetworkInterface network = NetworkInterface.getByInetAddress(socket.getLocalAddress());
            if (network == null) {
                return Optional.empty();
            }
            return all.stream()
                    .filter(c -> c.index() > 0 ? c.index() == network.getIndex() : c.info().name().equals(network.getName()))
                    .findFirst();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }

    }

    private void publish(Sample value) {

        while (history.size() >= HISTORY_LIMIT) history.pollFirst();
        history.addLast(value);
        sample.set(value);

    }

}
