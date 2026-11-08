package io.github.blacknoize404.networkUtils.traffic;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
 * Windows, elevated: per-connection byte counts through {@code EStats}
 * ({@code GetPerTcpConnectionEStats}) over the connection tables of
 * {@code iphlpapi}, summed by owning process. Enabling the counters requires
 * administrator rights — {@code SetPerTcpConnectionEStats} answers
 * {@code ERROR_ACCESS_DENIED} otherwise — so this source only exists when the
 * process runs elevated; it is the fallback of {@link ProcessTrafficSource#create()}
 * behind {@link ProcessTrafficSource.WinRtUsage}, which needs no elevation.
 * <p>
 * Counters are kept per connection and folded into per-process totals, so the
 * bytes a connection moved before closing still count after it disappears from
 * the table. Only TCP is attributed (the UDP variant of EStats exposes no byte
 * counters) and only data bytes, not protocol overhead.
 */
final class WindowsEStats implements ProcessTrafficSource {

    private static final int AF_INET = 2;
    private static final int AF_INET6 = 23;
    private static final int TCP_TABLE_OWNER_PID_ALL = 5;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int TCP_ESTATS_DATA = 1;

    /** MIB_TCPROW_OWNER_PID: state, addrs, ports, pid. */
    private static final int ROW4 = 24;
    /** MIB_TCP6ROW_OWNER_PID: same fields, 16-byte addresses, state near the end. */
    private static final int ROW6 = 56;
    /** TCP_ESTATS_DATA_ROD_v0: DataOutBytes first, DataInBytes next. */
    private static final int ROD_SIZE = 88;

    /** dwState values that never carry data: LISTENING and DELETE_TCB. */
    private static boolean countable(int state) {
        return state != 2 && state != 12;
    }

    private final MethodHandle getTable;
    private final MethodHandle setEStats;
    private final MethodHandle getEStats;
    private final MethodHandle setEStats6;
    private final MethodHandle getEStats6;
    private final MethodHandle openProcess;
    private final MethodHandle queryName;
    private final MethodHandle closeHandle;

    /** The last counters seen per connection, identified by its row fields. */
    private final Map<ByteBuffer, long[]> seen = new HashMap<>();
    /** Cumulative bytes per process name. */
    private final Map<String, long[]> totals = new HashMap<>();
    /** pid → executable name, so each process is resolved once. */
    private final Map<Integer, String> names = new HashMap<>();

    private WindowsEStats() {

        SymbolLookup iphlpapi = library("iphlpapi");
        SymbolLookup kernel32 = library("kernel32");
        getTable = handle(iphlpapi, "GetExtendedTcpTable",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        FunctionDescriptor setFd = FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);
        setEStats = handle(iphlpapi, "SetPerTcpConnectionEStats", setFd);
        setEStats6 = handle(iphlpapi, "SetPerTcp6ConnectionEStats", setFd);
        FunctionDescriptor getFd = FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
        getEStats = handle(iphlpapi, "GetPerTcpConnectionEStats", getFd);
        getEStats6 = handle(iphlpapi, "GetPerTcp6ConnectionEStats", getFd);
        openProcess = handle(kernel32, "OpenProcess",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        queryName = handle(kernel32, "QueryFullProcessImageNameW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        closeHandle = handle(kernel32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    }

    private static SymbolLookup library(String name) {
        return SymbolLookup.libraryLookup(name, Arena.global());
    }

    private static MethodHandle handle(SymbolLookup library, String symbol, FunctionDescriptor descriptor) {

        return Linker.nativeLinker().downcallHandle(library.find(symbol).orElseThrow(), descriptor);

    }

    /**
     * An instance when the process is elevated and EStats accepts a row — the
     * only situation where this source can count. Empty otherwise.
     */
    static Optional<ProcessTrafficSource> create() {

        try {
            if (!elevated()) {
                return Optional.empty();
            }
            WindowsEStats source = new WindowsEStats();
            return source.probe() ? Optional.of(source) : Optional.empty();
        } catch (Throwable _) {
            return Optional.empty();
        }

    }

    /** Whether the process token is elevated; without it {@code SetPerTcpConnectionEStats} is denied. */
    private static boolean elevated() {

        try {
            SymbolLookup advapi32 = library("advapi32");
            SymbolLookup kernel32 = library("kernel32");
            MethodHandle currentProcess = kernel32.find("GetCurrentProcess")
                    .map(s -> Linker.nativeLinker().downcallHandle(s, FunctionDescriptor.of(ADDRESS))).orElseThrow();
            MethodHandle openToken = Linker.nativeLinker().downcallHandle(
                    advapi32.find("OpenProcessToken").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            MethodHandle tokenInfo = Linker.nativeLinker().downcallHandle(
                    advapi32.find("GetTokenInformation").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            MethodHandle close = Linker.nativeLinker().downcallHandle(
                    kernel32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment token = arena.allocate(ADDRESS);
                // TOKEN_QUERY = 0x8, TokenElevation = 20
                if ((int) openToken.invokeExact((MemorySegment) currentProcess.invokeExact(), 0x8, token) == 0) {
                    return false;
                }
                MemorySegment value = arena.allocate(JAVA_INT);
                MemorySegment returned = arena.allocate(JAVA_INT);
                boolean ok = (int) tokenInfo.invokeExact(token.get(ADDRESS, 0), 20, value, 4, returned) != 0;
                close.invokeExact(token.get(ADDRESS, 0));
                return ok && value.get(JAVA_INT, 0) != 0;
            }
        } catch (Throwable _) {
            return false;
        }

    }

    /** Tries enabling counters on a live connection; true if any row accepted. */
    private boolean probe() {

        try (Arena arena = Arena.ofConfined()) {
            boolean anyRow = false;
            for (MemorySegment row : rows(arena, AF_INET)) {
                anyRow = true;
                if ((int) setEStats.invokeExact(row, TCP_ESTATS_DATA, arena.allocate(JAVA_INT), 0, 1, 0) == 0) {
                    return true;
                }
            }
            for (MemorySegment row : rows(arena, AF_INET6)) {
                anyRow = true;
                if ((int) setEStats6.invokeExact(row, TCP_ESTATS_DATA, arena.allocate(JAVA_INT), 0, 1, 0) == 0) {
                    return true;
                }
            }
            return anyRow;
        } catch (Throwable _) {
            return false;
        }

    }

    @Override
    public synchronized List<ProcessCounters> read() throws IOException {

        try (Arena arena = Arena.ofConfined()) {
            Map<ByteBuffer, long[]> now = new HashMap<>();
            MemorySegment rw = arena.allocate(JAVA_INT);
            MemorySegment rod = arena.allocate(ROD_SIZE);

            for (MemorySegment row : rows(arena, AF_INET)) {
                pollQuietly(row, false, rw, rod, now);
            }
            for (MemorySegment row : rows(arena, AF_INET6)) {
                pollQuietly(row, true, rw, rod, now);
            }

            seen.clear();
            seen.putAll(now);
        }
        List<ProcessCounters> counters = new ArrayList<>();
        totals.forEach((name, v) -> counters.add(new ProcessCounters(name, v[0], v[1])));
        return counters;

    }

    /** {@link #poll} tolerante a filas rebeldes: una mala no invalida el resto de la tabla. */
    private void pollQuietly(MemorySegment row, boolean ipv6, MemorySegment rw, MemorySegment rod, Map<ByteBuffer, long[]> now) {

        try {
            poll(row, ipv6, rw, rod, now);
        } catch (Throwable _) {
        }

    }

    /** Enables and reads the counters of one row, folding the delta into the process totals. */
    private void poll(MemorySegment row, boolean ipv6, MemorySegment rw, MemorySegment rod, Map<ByteBuffer, long[]> now)
            throws Throwable {

        int state = row.get(JAVA_INT, ipv6 ? 48 : 0);
        if (!countable(state)) {
            return;
        }
        int pid = row.get(JAVA_INT, ipv6 ? 52 : 20);
        ByteBuffer key = key(row, ipv6);

        if (ipv6) {
            setEStats6.invokeExact(row, TCP_ESTATS_DATA, rw, 0, 1, 0);
            if ((int) getEStats6.invokeExact(row, TCP_ESTATS_DATA, rw, 0, 1,
                    MemorySegment.NULL, 0, 0, rod, 0, ROD_SIZE) != 0) {
                return;
            }
        } else {
            setEStats.invokeExact(row, TCP_ESTATS_DATA, rw, 0, 1, 0);
            if ((int) getEStats.invokeExact(row, TCP_ESTATS_DATA, rw, 0, 1,
                    MemorySegment.NULL, 0, 0, rod, 0, ROD_SIZE) != 0) {
                return;
            }
        }

        long sent = rod.get(JAVA_LONG, 0);
        long received = rod.get(JAVA_LONG, 8);
        now.put(key, new long[]{received, sent});

        long[] before = seen.get(key);
        long dReceived = before == null || received < before[0] ? received : received - before[0];
        long dSent = before == null || sent < before[1] ? sent : sent - before[1];
        if (dReceived > 0 || dSent > 0) {
            totals.merge(process(pid), new long[]{dReceived, dSent},
                    (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        }

    }

    /** Addresses, ports and pid — what identifies a connection, without its changing state. */
    private static ByteBuffer key(MemorySegment row, boolean ipv6) {

        if (ipv6) {
            byte[] key = new byte[52]; // addrs+scopes+ports (0..48), pid (52..56) al final
            MemorySegment.copy(row, 0, MemorySegment.ofArray(key), 0, 48);
            MemorySegment.copy(row, 52, MemorySegment.ofArray(key), 48, 4);
            return ByteBuffer.wrap(key);
        }
        return ByteBuffer.wrap(row.asSlice(4, 20).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));

    }

    /** The executable name of {@code pid}, resolved once; «PID n» if the name cannot be read. */
    private String process(int pid) {

        return names.computeIfAbsent(pid, this::resolveName);

    }

    private String resolveName(int pid) {

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment process = (MemorySegment) openProcess.invokeExact(0x1000, 0, pid);
            if (process.equals(MemorySegment.NULL)) {
                return "PID " + pid;
            }
            try {
                MemorySegment buffer = arena.allocate(JAVA_SHORT, 512);
                MemorySegment size = arena.allocate(JAVA_INT);
                size.set(JAVA_INT, 0, 512);
                if ((int) queryName.invokeExact(process, 0, buffer, size) == 0) {
                    return "PID " + pid;
                }
                String path = buffer.getString(0, StandardCharsets.UTF_16LE);
                int sep = path.lastIndexOf('\\');
                return sep >= 0 ? path.substring(sep + 1) : path;
            } finally {
                closeHandle.invokeExact(process);
            }
        } catch (Throwable _) {
            return "PID " + pid;
        }

    }

    /** All the rows of the connection table of one address family; empty if it cannot be read. */
    private List<MemorySegment> rows(Arena arena, int family) {

        try {
            MemorySegment size = arena.allocate(JAVA_INT);
            int rc = (int) getTable.invokeExact(MemorySegment.NULL, size, 1, family, TCP_TABLE_OWNER_PID_ALL, 0);
            if (rc != ERROR_INSUFFICIENT_BUFFER || size.get(JAVA_INT, 0) <= 4) {
                return List.of();
            }
            MemorySegment table = arena.allocate(size.get(JAVA_INT, 0));
            if ((int) getTable.invokeExact(table, size, 1, family, TCP_TABLE_OWNER_PID_ALL, 0) != 0) {
                return List.of();
            }
            int count = table.get(JAVA_INT, 0);
            int rowSize = family == AF_INET6 ? ROW6 : ROW4;
            List<MemorySegment> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rows.add(table.asSlice(4 + (long) i * rowSize, rowSize));
            }
            return rows;
        } catch (Throwable _) {
            return List.of();
        }

    }

}
