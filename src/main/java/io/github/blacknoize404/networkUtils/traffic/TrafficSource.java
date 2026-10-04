package io.github.blacknoize404.networkUtils.traffic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cumulative byte counters of the system's network adapters. Java does not
 * expose them, so every operating system has its own source.
 */
interface TrafficSource {

    /**
     * @return bytes received and sent since boot, summed over all adapters, as {@code {received, sent}}
     */
    long[] readTotals() throws IOException;

    static Optional<TrafficSource> create() {

        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.startsWith("windows")) {
            return Optional.of(new Netstat());
        }
        if (os.contains("linux")) {
            return Optional.of(new ProcNetDev());
        }
        return Optional.empty();

    }

    /**
     * {@code netstat -e}: the bytes row is the first one holding two numbers,
     * whatever the language of Windows is.
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

    /** {@code /proc/net/dev}: receive and transmit bytes per interface, loopback excluded. */
    final class ProcNetDev implements TrafficSource {

        @Override
        public long[] readTotals() throws IOException {

            return parse(Files.readAllLines(Path.of("/proc/net/dev")));

        }

        static long[] parse(List<String> lines) {

            long received = 0;
            long sent = 0;

            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon < 0 || line.substring(0, colon).strip().equals("lo")) {
                    continue;
                }
                String[] fields = line.substring(colon + 1).strip().split("\\s+");
                received += Long.parseLong(fields[0]);
                sent += Long.parseLong(fields[8]);
            }
            return new long[]{received, sent};

        }

    }

}
