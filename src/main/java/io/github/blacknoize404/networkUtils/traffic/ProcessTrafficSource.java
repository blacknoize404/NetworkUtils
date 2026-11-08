package io.github.blacknoize404.networkUtils.traffic;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Cumulative traffic per process: what each running program sent and
 * received — the per-application counterpart of {@link TrafficSource}.
 * Every operating system attributes bytes to applications its own way;
 * only Windows has a source for now, so elsewhere there is nothing to read.
 */
public interface ProcessTrafficSource {

    /**
     * The bytes one application moved since it could first be counted, by name.
     */
    record ProcessCounters(String process, long received, long sent) {
    }

    /**
     * @return the running totals of every application with accounted usage
     */
    List<ProcessCounters> read() throws IOException;

    static Optional<ProcessTrafficSource> create() {

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.startsWith("windows")) {
            ProcessTrafficSource primary = null;
            try {
                WinRtUsage usage = new WinRtUsage();
                usage.read();
                primary = usage;
            } catch (Throwable _) {
                // No DLL or no service: the fallback may still work.
            }
            ProcessTrafficSource fallback = WindowsEStats.create().orElse(null);
            if (primary == null && fallback == null) {
                return Optional.empty();
            }
            return Optional.of(new FallbackUsage(primary, fallback));
        }
        return Optional.empty();

    }

    /**
     * A source that prefers the system's per-application accounting and falls
     * back to per-connection counters when the first never produces data (or
     * fails). Once the fallback yields a reading it sticks to it, so counters
     * do not mix between sources mid-run.
     */
    final class FallbackUsage implements ProcessTrafficSource {

        private final ProcessTrafficSource primary;
        private final ProcessTrafficSource fallback;
        private ProcessTrafficSource chosen;

        FallbackUsage(ProcessTrafficSource primary, ProcessTrafficSource fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public List<ProcessCounters> read() throws IOException {

            if (chosen != null) {
                return chosen.read();
            }
            if (primary != null) {
                try {
                    List<ProcessCounters> out = primary.read();
                    if (!out.isEmpty()) {
                        chosen = primary;
                        return out;
                    }
                } catch (IOException _) {
                    // The primary died for good: switch for the rest of the run.
                    chosen = fallback;
                    if (chosen == null) {
                        return List.of();
                    }
                    return chosen.read();
                }
            }
            if (fallback != null) {
                List<ProcessCounters> out = fallback.read();
                if (!out.isEmpty()) {
                    chosen = fallback;
                }
                return out;
            }
            return List.of();

        }

    }

    /**
     * Windows: the system's own per-application usage accounting — the same
     * numbers «Uso de datos» shows in Settings — exposed through
     * {@code ConnectionProfile.GetAttributedNetworkUsageAsync}. No
     * administrator rights needed and every process is covered, desktop or
     * packaged; the attribution id is the executable path. Each read asks the
     * system for the usage recorded since the previous one and accumulates it,
     * so the counters grow since the first read rather than since boot.
     * <p>
     * The WinRT call is asynchronous and its completion callback must be a
     * real COM delegate, which cannot be fabricated from Java; the work is
     * done inside {@code nuusage.dll}, a tiny native companion shipped next to
     * this class, and read here through the {@code NuReadUsage} export.
     */
    final class WinRtUsage implements ProcessTrafficSource {

        /** Maximum UTF-16 characters per attribution id, matching the DLL's layout. */
        private static final int ID_CHARS = 512;
        /** Maximum items read per call. */
        private static final int CAPACITY = 512;
        private static final long WINRT_EPOCH_OFFSET = 11_644_473_600L;

        private final MethodHandle readUsage;

        /** Totals accumulated over successive reads, by application name. */
        private final Map<String, long[]> totals = new HashMap<>();
        private long lastQuery;

        WinRtUsage() {

            readUsage = Linker.nativeLinker().downcallHandle(
                    loadBridge().find("NuReadUsage").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG,
                            ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

        }

        /** Unpacks {@code natives/win-x64/nuusage.dll} beside the JVM and loads it. */
        private static SymbolLookup loadBridge() {

            try (InputStream in = WinRtUsage.class.getResourceAsStream("/natives/win-x64/nuusage.dll")) {
                if (in == null) {
                    throw new IllegalStateException("nuusage.dll is not bundled");
                }
                Path dir = Files.createTempDirectory("nuusage");
                Path dll = dir.resolve("nuusage.dll");
                Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);
                dir.toFile().deleteOnExit();
                dll.toFile().deleteOnExit();
                return SymbolLookup.libraryLookup(dll, Arena.global());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot unpack nuusage.dll", e);
            }

        }

        @Override
        public synchronized List<ProcessCounters> read() throws IOException {

            try (Arena arena = Arena.ofConfined()) {
                long now = Instant.now().getEpochSecond();
                long start = lastQuery == 0 ? now - 86_400 : lastQuery;
                lastQuery = now;

                MemorySegment sent = arena.allocate(JAVA_LONG, CAPACITY);
                MemorySegment received = arena.allocate(JAVA_LONG, CAPACITY);
                MemorySegment ids = arena.allocate(JAVA_SHORT, (long) CAPACITY * ID_CHARS);

                int n;
                try {
                    n = (int) readUsage.invokeExact(ticks(start), ticks(now),
                            sent, received, ids, ID_CHARS, CAPACITY);
                } catch (Throwable t) {
                    throw new IOException("Cannot read the per-application usage", t);
                }
                if (n < 0) {
                    throw new IOException("NuReadUsage failed with HRESULT " + Integer.toHexString(n));
                }
                for (int i = 0; i < n; i++) {
                    String process = name(ids.getString((long) i * ID_CHARS * JAVA_SHORT.byteSize(),
                            StandardCharsets.UTF_16LE));
                    if (process != null) {
                        totals.merge(process,
                                new long[]{received.getAtIndex(JAVA_LONG, i), sent.getAtIndex(JAVA_LONG, i)},
                                (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
                    }
                }
                return snapshot();
            }

        }

        private List<ProcessCounters> snapshot() {

            List<ProcessCounters> counters = new ArrayList<>();
            totals.forEach((name, value) -> counters.add(new ProcessCounters(name, value[0], value[1])));
            return counters;

        }

        /** The last path component of an attribution id like {@code \device\harddiskvolume5\...\app.exe}. */
        private static String name(String attributionId) {

            if (attributionId == null || attributionId.isBlank()) {
                return null;
            }
            int sep = attributionId.lastIndexOf('\\');
            return sep >= 0 && sep + 1 < attributionId.length() ? attributionId.substring(sep + 1) : attributionId;

        }

        /** Java epoch seconds to WinRT ticks (100 ns since 1601-01-01 UTC). */
        private static long ticks(long epochSeconds) {
            return (epochSeconds + WINRT_EPOCH_OFFSET) * 10_000_000L;
        }

    }

}
