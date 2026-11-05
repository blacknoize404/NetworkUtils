package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingMessageResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NativeICMPMethodFactory implements PingMethodFactory {

    @Override
    public void ping(String host, int timeoutMillis, Consumer<PingResponse> callback) {

        ProcessBuilder pb = new ProcessBuilder(command(host, timeoutMillis));

        try {
            Process process = pb.start();

            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

            processInputBuffer(host, stdInput, callback);
            processErrorBuffer(host, stdError, callback);

        } catch (RuntimeException | IOException e) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            e.getMessage()
                    )
            );
        }

    }

    private void processInputBuffer(String host, BufferedReader reader, Consumer<PingResponse> callback) {

        String line;

        Optional<String> lines = reader
                .lines()
                .reduce(
                        (s, s2) -> s + "\n" + s2
                );

        if (lines.isEmpty()) return;

        line = lines.get();

        if (!isWindows()) {
            parseUnix(host, line, callback);
            return;
        }

        final Pattern p = Pattern.compile("[A-z]+=\\d+ [A-z]+=(\\d+)ms [A-z]+=\\d+");
        final Matcher m = p.matcher(line);
        final boolean isFound = m.find();

        if (isFound) {
            long responseMillis = Long.parseLong(m.group(1));
            callback.accept(
                    new PingSuccessResponse(
                            host,
                            responseMillis));

        }
        else if (line.equals("Tiempo de espera agotado para esta solicitud.") ||
                line.startsWith("La solicitud de ping no pudo encontrar el host")) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            line.replace('\n', ' ')
                    )
            );
        } else {
            callback.accept(
                    new PingMessageResponse(
                            host,
                            line
                    )
            );
        }
    }

    private void processErrorBuffer(String host, BufferedReader reader, Consumer<PingResponse> callback) {

        String line;

        Optional<String> lines = reader
                .lines()
                .reduce(
                        (s, s2) -> s + "\n" + s2
                );

        if (lines.isEmpty()) return;

        line = lines.get();

        callback.accept(
                new PingMessageResponse(
                        host,
                        line
                )
        );
    }

    /**
     * El comando de ping del sistema: los argumentos de Windows no valen fuera de él.
     * En macOS (BSD) {@code -W} es la espera por respuesta en milisegundos; en Linux
     * (iputils) es en segundos, con {@code -n} para no resolver nombres a la inversa.
     */
    static List<String> command(String host, int timeoutMillis) {

        if (isWindows()) {
            return List.of("ping", host, "-n", "1", "-w", String.valueOf(timeoutMillis));
        }
        if (isMac()) {
            return List.of("ping", host, "-c", "1", "-W", String.valueOf(timeoutMillis));
        }
        long seconds = Math.max(1L, Math.round(timeoutMillis / 1000.0));
        return List.of("ping", host, "-n", "-c", "1", "-W", String.valueOf(seconds));

    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }

    private static final Pattern UNIX_TIME = Pattern.compile("time[=<](\\d+(?:\\.\\d+)?)\\s*ms");
    private static final Pattern UNIX_FAIL = Pattern.compile(
            "100(?:\\.0)?% packet loss|cannot resolve|Unknown host|Name or service not known|"
                    + "No route to host|Request timeout|Time to live exceeded",
            Pattern.CASE_INSENSITIVE);

    /** La salida del ping de macOS y Linux: {@code ... time=12.3 ms} al responder, errores en inglés. */
    static void parseUnix(String host, String output, Consumer<PingResponse> callback) {

        Matcher ok = UNIX_TIME.matcher(output);
        if (ok.find()) {
            callback.accept(new PingSuccessResponse(host, Math.round(Double.parseDouble(ok.group(1)))));
            return;
        }
        if (UNIX_FAIL.matcher(output).find()) {
            callback.accept(new PingErrorResponse(host, output.replace('\n', ' ')));
            return;
        }
        callback.accept(new PingMessageResponse(host, output));

    }

}
