package io.github.blacknoize404.networkUtils.traffic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * macOS: {@code nettop -x} reports one CSV row per connection carrying the
 * process name, pid and cumulative bytes — the platform's own per-application
 * accounting, no root needed. Rows are summed per process on every read and the
 * positive delta folded into cumulative totals, which stay monotonic even when
 * a connection closes between reads (its last bytes are lost, the accepted
 * trade-off of this API).
 */
final class MacOsNettop implements ProcessTrafficSource {

    /** Last total seen per process key (name.pid). */
    private final Map<String, long[]> seen = new HashMap<>();
    /** Cumulative bytes per process name. */
    private final Map<String, long[]> totals = new HashMap<>();

    private MacOsNettop() {
    }

    /** An instance when {@code nettop} answers; it ships with every macOS. */
    static Optional<ProcessTrafficSource> create() {

        try {
            ProcessBuilder probe = new ProcessBuilder("nettop", "-x", "-L", "1");
            Process process = probe.start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return Optional.of(new MacOsNettop());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

    }

    @Override
    public synchronized List<ProcessCounters> read() throws IOException {

        Process process = new ProcessBuilder("nettop", "-x", "-L", "1", "-J", "bytes_in,bytes_out").start();
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
        for (Row row : parse(lines)) {
            now.merge(row.key(), new long[]{row.received(), row.sent()}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        }
        now.forEach((key, cur) -> {
            String name = key.substring(0, key.lastIndexOf('.'));
            long[] before = seen.get(key);
            long dReceived = before == null || cur[0] < before[0] ? cur[0] : cur[0] - before[0];
            long dSent = before == null || cur[1] < before[1] ? cur[1] : cur[1] - before[1];
            if (dReceived > 0 || dSent > 0) {
                totals.merge(name, new long[]{dReceived, dSent}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
            }
        });
        seen.clear();
        seen.putAll(now);

        List<ProcessCounters> counters = new ArrayList<>();
        totals.forEach((name, v) -> counters.add(new ProcessCounters(name, v[0], v[1])));
        return counters;

    }

    /** One row of {@code nettop -x}: the key is {@code name.pid}. */
    record Row(String key, long received, long sent) {
    }

    /**
     * The CSV rows of a {@code nettop -x} capture: first field {@code name.pid},
     * the last two numeric fields {@code bytes_in},{@code bytes_out} (with
     * {@code -J bytes_in,bytes_out} they are the only ones). Lines that do not
     * match — headers, separators — are skipped.
     */
    static List<Row> parse(List<String> lines) {

        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split(",", -1);
            if (fields.length < 3) {
                continue;
            }
            String key = fields[0].trim();
            int dot = key.lastIndexOf('.');
            if (dot <= 0 || dot == key.length() - 1 || !key.substring(dot + 1).chars().allMatch(Character::isDigit)) {
                continue;
            }
            long in = -1;
            long out = -1;
            for (int i = fields.length - 1; i >= 1; i--) {
                try {
                    long value = Long.parseLong(fields[i].trim());
                    if (out < 0) {
                        out = value;
                    } else {
                        in = value;
                        break;
                    }
                } catch (NumberFormatException _) {
                }
            }
            if (in >= 0) {
                rows.add(new Row(key, in, out));
            }
        }
        return rows;

    }

}
