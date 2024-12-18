package io.github.blacknoize404.pingUtils.methods;

import io.github.blacknoize404.pingUtils.responses.PingErrorResponse;
import io.github.blacknoize404.pingUtils.responses.PingMessageResponse;
import io.github.blacknoize404.pingUtils.responses.PingResponse;
import io.github.blacknoize404.pingUtils.responses.PingSuccessResponse;

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

        ProcessBuilder pb = new ProcessBuilder(
                List.of("ping", host, "-n", "1", "-w", String.valueOf(timeoutMillis))
        );

        try {

            Process process = pb.start();

            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

            processBuffer(host, stdInput, callback);
            processBuffer(host, stdError, callback);

        } catch (RuntimeException | IOException e) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            e.getMessage()
                    )
            );
        }

    }

    private void processBuffer(String host, BufferedReader reader, Consumer<PingResponse> callback) {

        String line;

        Optional<String> lines = reader
                .lines()
                .reduce(
                        (s, s2) -> s + "\n" + s2
                );

        if (lines.isEmpty()) return;

        line = lines.get();

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

        if (line.equals("Tiempo de espera agotado para esta solicitud.") ||
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

}
