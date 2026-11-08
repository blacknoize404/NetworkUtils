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
        return create(false);
    }

    /**
     * The best source of this system. On Windows the chain is the system's own
     * usage accounting and then the elevated per-connection counters — or the
     * other way round when {@code preferElevated} asks for the EStats backend
     * (which only exists when the process is elevated). On macOS it is
     * {@code nettop}; on Linux, {@code ss} — complete for the user's own
     * processes, and for everything when run with the right privileges.
     */
    static Optional<ProcessTrafficSource> create(boolean preferElevated) {

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.startsWith("windows")) {
            ProcessTrafficSource winRt = null;
            try {
                WinRtUsage usage = new WinRtUsage();
                usage.read();
                winRt = usage;
            } catch (Throwable _) {
                // No DLL or no service: the fallback may still work.
            }
            ProcessTrafficSource eStats = WindowsEStats.create().orElse(null);
            List<ProcessTrafficSource> chain = new ArrayList<>();
            if (preferElevated) {
                if (eStats != null) {
                    chain.add(eStats);
                }
                if (winRt != null) {
                    chain.add(winRt);
                }
            } else {
                if (winRt != null) {
                    chain.add(winRt);
                }
                if (eStats != null) {
                    chain.add(eStats);
                }
            }
            return chain.isEmpty() ? Optional.empty()
                    : Optional.of(chain.size() == 1 ? chain.get(0) : new FallbackUsage(chain));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return MacOsNettop.create();
        }
        if (os.contains("linux")) {
            return LinuxSs.create();
        }
        return Optional.empty();

    }

    /**
     * A source that tries its candidates in order and pins the first that
     * produces data — or, if one fails outright, moves on for the rest of the
     * run — so counters do not mix between sources mid-run.
     */
    final class FallbackUsage implements ProcessTrafficSource {

        private final List<ProcessTrafficSource> candidates;
        private ProcessTrafficSource chosen;

        FallbackUsage(List<ProcessTrafficSource> candidates) {
            this.candidates = candidates;
        }

        @Override
        public List<ProcessCounters> read() throws IOException {

            if (chosen != null) {
                return chosen.read();
            }
            List<ProcessCounters> last = List.of();
            for (java.util.Iterator<ProcessTrafficSource> it = candidates.iterator(); it.hasNext(); ) {
                ProcessTrafficSource candidate = it.next();
                try {
                    List<ProcessCounters> out = candidate.read();
                    if (!out.isEmpty()) {
                        chosen = candidate;
                        return out;
                    }
                    last = out;
                } catch (IOException _) {
                    it.remove();
                }
            }
            return last;

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
