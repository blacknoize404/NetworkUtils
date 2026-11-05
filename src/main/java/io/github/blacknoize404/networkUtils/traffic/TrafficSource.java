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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Cumulative byte counters of the system's network adapters. Java does not
 * expose them, so every operating system has its own source.
 */
interface TrafficSource {

    /**
     * The counters of one adapter.
     *
     * @param index the operating system's interface index, or {@code 0} when it has none
     */
    record Counters(TrafficInterface info, int index, long received, long sent) {
    }

    /**
     * @return bytes received and sent since boot, summed over all real adapters, as {@code {received, sent}}
     */
    long[] readTotals() throws IOException;

    /**
     * @return the counters of every real adapter (loopback and driver filter layers excluded),
     * or an empty list when this source only knows the totals
     */
    List<Counters> readInterfaces() throws IOException;

    static Optional<TrafficSource> create() {

        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.startsWith("windows")) {
            try {
                WindowsIfTable table = new WindowsIfTable();
                table.readInterfaces();
                return Optional.of(table);
            } catch (Throwable e) {
                // Without native access the totals are still available, just not per adapter.
                return Optional.of(new Netstat());
            }
        }
        if (os.contains("linux")) {
            return Optional.of(new ProcNetDev());
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Optional.of(new MacNetstat());
        }
        return Optional.empty();

    }

    static long[] sum(List<Counters> counters) {

        long received = 0;
        long sent = 0;
        for (Counters c : counters) {
            received += c.received();
            sent += c.sent();
        }
        return new long[]{received, sent};

    }

    /**
     * {@code netstat -e}: the bytes row is the first one holding two numbers,
     * whatever the language of Windows is. Totals only.
     */
    final class Netstat implements TrafficSource {

        private static final Pattern BYTES_ROW = Pattern.compile("^\\S+\\s+(\\d+)\\s+(\\d+)\\s*$");

        @Override
        public long[] readTotals() throws IOException {

            Process process = new ProcessBuilder("netstat", "-e").redirectErrorStream(true).start();
            try (InputStream in = process.getInputStream()) {
                return parse(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            }

        }

        @Override
        public List<Counters> readInterfaces() {
            return List.of();
        }

        static long[] parse(String output) throws IOException {

            for (String line : output.split("\\R")) {
                Matcher matcher = BYTES_ROW.matcher(line.strip());
                if (matcher.matches()) {
                    return new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
                }
            }
            throw new IOException("Unrecognised netstat output");

        }

    }

    /**
     * {@code GetIfTable2}: the same table {@code Get-NetAdapterStatistics} reads. It also
     * lists the filter layers stacked over each adapter (WFP, QoS, Native WiFi…), which
     * repeat the adapter's counters, so those and the loopback are left out.
     */
    final class WindowsIfTable implements TrafficSource {

        // MIB_IF_ROW2 is 1352 bytes after an 8-byte header; offsets are those of the x64 layout.
        private static final long HEADER = 8;
        private static final long ROW = 1352;
        private static final long INDEX = 8;
        private static final long GUID = 12;
        private static final long ALIAS = 28;
        private static final long DESCRIPTION = 542;
        private static final long TYPE = 1128;
        private static final long FLAGS = 1152;
        private static final long OPER_STATUS = 1156;
        private static final long IN_OCTETS = 1208;
        private static final long OUT_OCTETS = 1280;

        private static final int IF_TYPE_LOOPBACK = 24;
        private static final int FLAG_FILTER = 0x02;
        private static final int OPER_STATUS_UP = 1;

        private final MethodHandle getTable;
        private final MethodHandle freeTable;

        WindowsIfTable() {

            Linker linker = Linker.nativeLinker();
            SymbolLookup library = SymbolLookup.libraryLookup("iphlpapi", Arena.global());
            getTable = linker.downcallHandle(library.find("GetIfTable2").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            freeTable = linker.downcallHandle(library.find("FreeMibTable").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS));

        }

        @Override
        public long[] readTotals() throws IOException {
            return sum(readInterfaces());
        }

        @Override
        public List<Counters> readInterfaces() throws IOException {

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ADDRESS);
                int result = (int) getTable.invokeExact(out);
                if (result != 0) {
                    throw new IOException("GetIfTable2 failed with code " + result);
                }
                MemorySegment pointer = out.get(ADDRESS, 0);
                try {
                    int count = pointer.reinterpret(HEADER).get(JAVA_INT, 0);
                    MemorySegment table = pointer.reinterpret(HEADER + count * ROW);
                    List<Counters> counters = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        long base = HEADER + i * ROW;
                        int flags = table.get(JAVA_BYTE, base + FLAGS) & 0xFF;
                        if ((flags & FLAG_FILTER) != 0 || table.get(JAVA_INT, base + TYPE) == IF_TYPE_LOOPBACK) {
                            continue;
                        }
                        String description = wide(table, base + DESCRIPTION, 257);
                        if (isSystemPlumbing(description)) {
                            continue;
                        }
                        counters.add(new Counters(
                                new TrafficInterface(
                                        guid(table, base + GUID),
                                        wide(table, base + ALIAS, 257),
                                        description,
                                        table.get(JAVA_INT, base + OPER_STATUS) == OPER_STATUS_UP),
                                table.get(JAVA_INT, base + INDEX),
                                table.get(JAVA_LONG, base + IN_OCTETS),
                                table.get(JAVA_LONG, base + OUT_OCTETS)));
                    }
                    return counters;
                } finally {
                    freeTable.invokeExact(pointer);
                }
            } catch (IOException e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException("Cannot read the interface table", e);
            }

        }

        /** Adapters Windows creates for its own VPN, tunnelling and debugging services: never a place to measure. */
        private static boolean isSystemPlumbing(String description) {

            return description.startsWith("WAN Miniport")
                    || description.startsWith("RAS Async")
                    || description.startsWith("Microsoft Kernel Debug")
                    || description.startsWith("Microsoft Wi-Fi Direct Virtual")
                    || description.startsWith("Microsoft Teredo")
                    || description.startsWith("Microsoft 6to4")
                    || description.startsWith("Microsoft IP-HTTPS");

        }

        private static String wide(MemorySegment segment, long offset, int chars) {

            byte[] bytes = segment.asSlice(offset, chars * 2L).toArray(JAVA_BYTE);
            int length = 0;
            while (length + 1 < bytes.length && (bytes[length] != 0 || bytes[length + 1] != 0)) {
                length += 2;
            }
            return new String(bytes, 0, length, StandardCharsets.UTF_16LE);

        }

        private static String guid(MemorySegment segment, long offset) {

            StringBuilder tail = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                tail.append(String.format("%02x", segment.get(JAVA_BYTE, offset + 8 + i)));
            }
            return String.format("{%08x-%04x-%04x-%s-%s}",
                    segment.get(JAVA_INT, offset),
                    segment.get(JAVA_SHORT, offset + 4) & 0xFFFF,
                    segment.get(JAVA_SHORT, offset + 6) & 0xFFFF,
                    tail.substring(0, 4), tail.substring(4));

        }

    }

    /** {@code /proc/net/dev}: receive and transmit bytes per interface, loopback excluded. */
    final class ProcNetDev implements TrafficSource {

        @Override
        public long[] readTotals() throws IOException {
            return sum(readInterfaces());
        }

        @Override
        public List<Counters> readInterfaces() throws IOException {
            return parse(Files.readAllLines(Path.of("/proc/net/dev")), ProcNetDev::isUp);
        }

        private static boolean isUp(String name) {

            try {
                String state = Files.readString(Path.of("/sys/class/net", name, "operstate")).strip();
                // Tunnels and some virtual devices report "unknown" while carrying traffic.
                return state.equals("up") || state.equals("unknown");
            } catch (IOException | RuntimeException e) {
                return true;
            }

        }

        static List<Counters> parse(List<String> lines, java.util.function.Predicate<String> isUp) {

            List<Counters> counters = new ArrayList<>();

            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).strip();
                if (name.equals("lo")) {
                    continue;
                }
                String[] fields = line.substring(colon + 1).strip().split("\\s+");
                counters.add(new Counters(
                        new TrafficInterface(name, name, "", isUp.test(name)),
                        0,
                        Long.parseLong(fields[0]),
                        Long.parseLong(fields[8])));
            }
            return counters;

        }

    }

    /**
     * {@code netstat -ibn} en macOS: una fila por interfaz y dirección. Los bytes reales
     * lleva la fila del enlace ({@code <Link#n>}); tener una fila con IP marca la interfaz
     * como conectada.
     */
    final class MacNetstat implements TrafficSource {

        @Override
        public long[] readTotals() throws IOException {
            return sum(readInterfaces());
        }

        @Override
        public List<Counters> readInterfaces() throws IOException {
            Process process = new ProcessBuilder("netstat", "-ibn").redirectErrorStream(true).start();
            try (InputStream in = process.getInputStream()) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        static List<Counters> parse(String output) {

            // Name Mtu Network Address Ipkts Ierrs Ibytes Opkts Oerrs Obytes Coll
            java.util.Map<String, long[]> bytes = new java.util.LinkedHashMap<>();
            java.util.Set<String> addressed = new java.util.HashSet<>();

            for (String line : output.split("\\R")) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length < 10 || fields[0].equals("Name") || fields[0].equals("lo0")) {
                    continue;
                }
                boolean link = fields[2].startsWith("<Link");
                if (link || !bytes.containsKey(fields[0])) {
                    try {
                        bytes.put(fields[0], new long[]{Long.parseLong(fields[6]), Long.parseLong(fields[9])});
                    } catch (NumberFormatException e) {
                        // Cabeceras o columnas de otro formato: no son contadores
                    }
                }
                if (!link) {
                    addressed.add(fields[0]);
                }
            }

            List<Counters> counters = new ArrayList<>();
            for (var entry : bytes.entrySet()) {
                String name = entry.getKey();
                counters.add(new Counters(
                        new TrafficInterface(name, name, "", addressed.contains(name)),
                        0,
                        entry.getValue()[0],
                        entry.getValue()[1]));
            }
            return counters;

        }

    }

}
