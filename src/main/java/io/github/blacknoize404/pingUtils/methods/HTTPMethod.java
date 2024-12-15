package io.github.blacknoize404.pingUtils.methods;

import io.github.blacknoize404.pingUtils.responses.PingErrorResponse;
import io.github.blacknoize404.pingUtils.responses.PingResponse;
import io.github.blacknoize404.pingUtils.responses.PingSuccessResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.function.Consumer;

public class HTTPMethod implements PingMethod {

    public enum Protocol {
        SECURE,
        UNSECURE
    }

    private final int port;

    public HTTPMethod(Protocol port) {

        this.port = switch(port) {
            case SECURE -> 443;
            case UNSECURE -> 80;
        };

    }

    @Override
    public void ping(String host, int timeoutMillis, Consumer<PingResponse> callback) {

        try (Socket socket = new Socket()) {

            long initial = System.currentTimeMillis();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            long duration = System.currentTimeMillis() - initial;
            callback.accept(new PingSuccessResponse(duration));

        } catch (SocketTimeoutException e) {
            callback.accept(
                    new PingErrorResponse(
                            "Se ha alcanzado el tiempo de petición"
                    )
            );
        } catch (NoRouteToHostException e) {
            callback.accept(
                    new PingErrorResponse(
                            "No se pudo alcanzar la url o no se pudo " +
                                    "resolver la url con el servidor DNS"
                    )
            );
        } catch (IOException e) {
            callback.accept(
                    new PingErrorResponse(
                            e.getMessage()
                    )
            );
        }
    }
}
