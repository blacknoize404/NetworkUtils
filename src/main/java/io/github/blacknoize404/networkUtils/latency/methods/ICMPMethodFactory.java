package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Consumer;

public final class ICMPMethodFactory implements PingMethodFactory {

    @Override
    public void ping(String host, int timeoutMillis, Consumer<PingResponse> callback) {

        try {
            InetAddress address = InetAddress.getByName(host);

            long initial = System.currentTimeMillis();
            boolean reachable = address.isReachable(timeoutMillis);

            if (!reachable) {
                callback.accept(
                        new PingErrorResponse(
                                host,
                                "No se pudo conectar con el servidor"
                        )
                );
            } else {
                long duration = System.currentTimeMillis() - initial;
                callback.accept(
                        new PingSuccessResponse(
                                host,
                                duration
                        )
                );
            }

        } catch (UnknownHostException e) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            "No se pudo resolver el host con el servidor DNS"
                    )
            );
        } catch (IOException e) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            "(" + e.getClass().getName() + ") " + e.getMessage()
                    )
            );
        }

    }

}
