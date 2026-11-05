package io.github.blacknoize404.networkUtils.diagnosis;

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
 * La puerta de enlace (el router) por la que el sistema sale a Internet: la del encaminamiento por
 * defecto de menor métrica. Se lee de {@code route print} en Windows, de {@code /proc/net/route} en
 * Linux y de {@code route -n get default} en macOS; en otro sistema, o si no hay ruta por defecto,
 * está vacía.
 */
public final class DefaultGateway {

    // 0.0.0.0  0.0.0.0  192.168.1.1  192.168.1.50  25  (destino, máscara, puerta, interfaz, métrica)
    private static final Pattern WINDOWS_ROW = Pattern.compile(
            "^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})\\s+\\S+\\s+(\\d+)\\s*$");

    private DefaultGateway() {
    }

    public static Optional<String> find() {

        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.startsWith("windows")) {
                Process process = new ProcessBuilder("route", "print", "-4", "0.0.0.0").redirectErrorStream(true).start();
                try (InputStream in = process.getInputStream()) {
                    return parseWindows(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                }
            }
            if (os.contains("linux")) {
                return parseLinux(Files.readAllLines(Path.of("/proc/net/route")));
            }
            if (os.contains("mac") || os.contains("darwin")) {
                Process process = new ProcessBuilder("route", "-n", "get", "default").redirectErrorStream(true).start();
                try (InputStream in = process.getInputStream()) {
                    return parseMac(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Sin puerta conocida el diagnóstico sigue por Internet.
        }
        return Optional.empty();

    }

    /** La puerta de la ruta por defecto de menor métrica en la salida de {@code route print -4}. */
    static Optional<String> parseWindows(String output) {

        String best = null;
        int bestMetric = Integer.MAX_VALUE;

        for (String line : output.split("\\R")) {
            Matcher matcher = WINDOWS_ROW.matcher(line);
            if (matcher.matches()) {
                int metric = Integer.parseInt(matcher.group(2));
                if (metric < bestMetric) {
                    best = matcher.group(1);
                    bestMetric = metric;
                }
            }
        }
        return Optional.ofNullable(best);

    }

    /** La puerta de la ruta por defecto de menor métrica en {@code /proc/net/route} (hexadecimal, little-endian). */
    static Optional<String> parseLinux(List<String> lines) {

        String best = null;
        long bestMetric = Long.MAX_VALUE;

        for (String line : lines) {
            String[] fields = line.strip().split("\\s+");
            // Iface Destination Gateway Flags RefCnt Use Metric Mask ...
            if (fields.length < 8 || !fields[1].equals("00000000")) {
                continue;
            }
            try {
                long gateway = Long.parseLong(fields[2], 16);
                long metric = Long.parseLong(fields[6]);
                if (gateway != 0 && metric < bestMetric) {
                    best = (gateway & 0xFF) + "." + ((gateway >> 8) & 0xFF) + "." + ((gateway >> 16) & 0xFF) + "." + ((gateway >> 24) & 0xFF);
                    bestMetric = metric;
                }
            } catch (NumberFormatException ignored) {
                // La cabecera y las líneas raras no cuentan.
            }
        }
        return Optional.ofNullable(best);

    }

    /** La puerta en la salida de {@code route -n get default} de macOS (la línea «gateway:»). */
    static Optional<String> parseMac(String output) {

        for (String line : output.split("\\R")) {
            String[] fields = line.strip().split(":\\s*");
            if (fields.length == 2 && fields[0].equals("gateway")) {
                return Optional.of(fields[1]);
            }
        }
        return Optional.empty();

    }

}
