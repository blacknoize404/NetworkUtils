package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.function.Consumer;

/**
 * Mide el tiempo de establecer una conexión TCP con un puerto concreto. El host se escribe como
 * {@code nombre:puerto} ({@code ejemplo.com:5432}); sin puerto se usa el 80.
 */
public final class TCPMethodFactory implements PingMethodFactory {

    public static final int DEFAULT_PORT = 80;

    /** El host y el puerto de una dirección {@code host:puerto}. */
    public record Endpoint(String host, int port) {
    }

    /**
     * Separa {@code host:puerto}. Una dirección con más de un {@code :} (IPv6 sin corchetes) se
     * toma entera como host; {@code [::1]:443} separa el puerto tras los corchetes.
     */
    public static Endpoint parse(String address) {

        String text = address.strip();
        int colon = text.lastIndexOf(':');

        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close > 0) {
                String host = text.substring(1, close);
                return close + 1 < text.length() && text.charAt(close + 1) == ':'
                        ? new Endpoint(host, port(text.substring(close + 2)))
                        : new Endpoint(host, DEFAULT_PORT);
            }
        }
        if (colon > 0 && text.indexOf(':') == colon) {
            return new Endpoint(text.substring(0, colon), port(text.substring(colon + 1)));
        }
        return new Endpoint(text, DEFAULT_PORT);

    }

    private static int port(String text) {

        try {
            int port = Integer.parseInt(text.strip());
            return port >= 1 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }

    }

    @Override
    public void ping(String address, int timeoutMillis, Consumer<PingResponse> callback) {

        Endpoint endpoint = parse(address);

        try (Socket socket = new Socket()) {

            long initial = System.currentTimeMillis();
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), timeoutMillis);
            callback.accept(new PingSuccessResponse(address, System.currentTimeMillis() - initial));

        } catch (SocketTimeoutException e) {
            callback.accept(new PingErrorResponse(address, "Se ha alcanzado el tiempo de petición"));
        } catch (NoRouteToHostException e) {
            callback.accept(new PingErrorResponse(address, "No hay ruta hasta el host"));
        } catch (UnknownHostException e) {
            callback.accept(new PingErrorResponse(address, "La dirección ip del host no pudo ser determinada"));
        } catch (IOException e) {
            callback.accept(new PingErrorResponse(address, "El puerto " + endpoint.port() + " no acepta conexiones"));
        }

    }

}
