package io.github.blacknoize404.networkUtils.traffic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linux: {@code ss -tipnaH} (iproute2) lists every TCP socket with its
 * {@code bytes_sent}/{@code bytes_received} counters and — for sockets the
 * caller may inspect — the owning process in {@code users:(("name",pid=…))}.
 * Unprivileged that covers the user's own processes; with {@code CAP_DAC_OVERRIDE}
 * or root, all of them. Bytes are folded per connection into per-process
 * totals so a socket's last bytes still count after it closes. UDP sockets do
 * not expose byte counters, so they are not attributed.
 */
final class LinuxSs implements ProcessTrafficSource {

    /** users:(("curl",pid=1234,fd=5)) — name and pid of the owning process. */
    private static final Pattern PROCESS = Pattern.compile("\\(\\(\"([^\"]+)\",pid=(\\d+)");

    /** The last counters seen per socket (local→peer). */
    private final Map<String, long[]> seen = new HashMap<>();
    /** Cumulative bytes per process name. */
    private final Map<String, long[]> totals = new HashMap<>();
    /** pid → process name, so /proc is read once per process. */
    private final Map<Integer, String> names = new HashMap<>();

    private LinuxSs() {
    }

    /** An instance when {@code ss} is present — it ships with every modern distro (iproute2). */
    static Optional<ProcessTrafficSource> create() {

        try {
            ProcessBuilder probe = new ProcessBuilder("ss", "-H", "-t", "-l");
            Process process = probe.start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return Optional.of(new LinuxSs());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

    }

    @Override
    public synchronized List<ProcessCounters> read() throws IOException {

        Process process = new ProcessBuilder("ss", "-tipnaH").start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, long[]> now = new HashMap<>();
        for (SocketRow row : parse(lines)) {
            if (row.pid() == null) {
                continue; // Unattributable: another user's or kernel socket.
            }
            String key = row.local() + ">" + row.peer();
            now.put(key, new long[]{row.received(), row.sent()});

            long[] before = seen.get(key);
            long dReceived = before == null || row.received() < before[0] ? row.received() : row.received() - before[0];
            long dSent = before == null || row.sent() < before[1] ? row.sent() : row.sent() - before[1];
            if (dReceived > 0 || dSent > 0) {
                totals.merge(name(row), new long[]{dReceived, dSent}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
            }
        }
        seen.clear();
        seen.putAll(now);

        List<ProcessCounters> counters = new ArrayList<>();
        totals.forEach((name, v) -> counters.add(new ProcessCounters(name, v[0], v[1])));
        return counters;

    }

    /** The display name of the socket's owner: the name {@code ss} gave, or /proc's {@code comm}. */
    private String name(SocketRow row) {

        if (row.process() != null) {
            return row.process();
        }
        return names.computeIfAbsent(row.pid(), LinuxSs::commName);

    }

    private static String commName(int pid) {

        try {
            String comm = Files.readString(Path.of("/proc", String.valueOf(pid), "comm"), StandardCharsets.UTF_8).strip();
            return comm.isEmpty() ? "PID " + pid : comm;
        } catch (IOException | RuntimeException e) {
            return "PID " + pid;
        }

    }

    /** One attributed socket: endpoints, cumulative bytes and, when readable, its process. */
    record SocketRow(String local, String peer, long sent, long received, String process, Integer pid) {
    }

    /**
     * The socket blocks of {@code ss -tipnaH}: a socket starts on an unindented
     * line (state first, or {@code tcp} and then the state)
     * whose local and peer fields are {@code addr:port}; {@code bytes_sent}/{@code bytes_received} and the
     * {@code users:((…))} tag may sit on the same line or on indented
     * continuations. Sockets without a byte counter or in LISTEN are skipped.
     */
    static List<SocketRow> parse(List<String> lines) {

        List<SocketRow> rows = new ArrayList<>();
        String local = null;
        String peer = null;
        String process = null;
        Integer pid = null;
        long sent = -1;
        long received = -1;

        for (String line : lines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // Continuation of the current socket: info fields may live here.
            } else {
                if (sent >= 0 || received >= 0) {
                    rows.add(new SocketRow(local, peer, Math.max(0, sent), Math.max(0, received), process, pid));
                }
                local = null;
                peer = null;
                process = null;
                pid = null;
                sent = -1;
                received = -1;
                String[] f = line.trim().split("\\s+");
                // Con -t solo, ss no escribe la columna Netid: «ESTAB 0 0 local peer»; con varios tipos de
                // socket sí: «tcp ESTAB 0 0 local peer». Se aceptan las dos formas.
                int first = f.length > 0 && f[0].equals("tcp") ? 1 : 0;
                if (f.length >= first + 5 && !f[first].equalsIgnoreCase("LISTEN") && f[first].matches("[A-Za-z-]+")) {
                    local = f[first + 3];
                    peer = f[first + 4];
                }
            }
            if (local == null) {
                continue;
            }
            for (String token : line.split("\\s+")) {
                if (token.startsWith("bytes_sent:")) {
                    sent = parse(token.substring("bytes_sent:".length()));
                } else if (token.startsWith("bytes_received:")) {
                    received = parse(token.substring("bytes_received:".length()));
                } else if (token.startsWith("users:")) {
                    Matcher matcher = PROCESS.matcher(token);
                    if (matcher.find()) {
                        process = matcher.group(1);
                        pid = Integer.parseInt(matcher.group(2));
                    }
                }
            }
        }
        if (local != null && (sent >= 0 || received >= 0)) {
            rows.add(new SocketRow(local, peer, Math.max(0, sent), Math.max(0, received), process, pid));
        }
        return rows;

    }

    private static long parse(String text) {

        try {
            return Long.parseLong(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException _) {
            return -1;
        }

    }

}
