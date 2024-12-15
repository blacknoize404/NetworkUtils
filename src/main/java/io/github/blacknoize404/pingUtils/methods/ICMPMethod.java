package io.github.blacknoize404.pingUtils.methods;

import io.github.blacknoize404.pingUtils.responses.PingErrorResponse;
import io.github.blacknoize404.pingUtils.responses.PingResponse;
import io.github.blacknoize404.pingUtils.responses.PingSuccessResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.Consumer;

public class ICMPMethod implements PingMethod {

    @Override
    public void ping(String host, int timeoutMillis, Consumer<PingResponse> callback) {

        try {
            InetAddress address = InetAddress.getByName(host);

            long initial = System.currentTimeMillis();
            boolean reachable = address.isReachable(timeoutMillis);

            if (!reachable) callback.accept(new PingErrorResponse("No se pudo conectar con el servidor"));
            else {
                long duration = System.currentTimeMillis() - initial;
                callback.accept(new PingSuccessResponse(duration));
            }

        } catch (IOException e) {
            callback.accept(new PingErrorResponse(e.getMessage()));
        }

    }

}
