package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

import java.io.IOException;
import java.net.*;
import java.util.function.Consumer;

/**
 *
 */
public final class HTTPMethodFactory implements PingMethodFactory {

    public enum Protocol {
        SECURE,
        UNSECURE
    }

    private final int port;

    public HTTPMethodFactory(Protocol protocol) {

        this.port = switch(protocol) {
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

            callback.accept(new PingSuccessResponse(host, duration));

        } catch (SocketTimeoutException e) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            "Se ha alcanzado el tiempo de petición"
                    )
            );
        } catch (NoRouteToHostException e) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            "No se pudo resolver la url con el servidor DNS"
                    )
            );
        } catch (UnknownHostException e) {
            callback.accept(
                    new PingErrorResponse(
                            host,
                            "La dirección ip del host no pudo ser determinada"
                    )
            );
        } catch (IOException e) {
            callback.accept(
                    new PingErrorResponse (
                            host,
                            "Ha ocurrido un error de entrada/salida"
                    )
            );
        }
    }
}
