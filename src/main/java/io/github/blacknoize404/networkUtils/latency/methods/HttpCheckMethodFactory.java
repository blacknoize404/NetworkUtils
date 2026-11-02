package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Hace una petición HTTP de verdad y da por buena la respuesta solo si es la esperada: un código
 * concreto (o cualquiera por debajo de 400 si es 0) y, opcionalmente, un texto que debe aparecer en
 * el cuerpo. Una API que responde 500 o una página con un error dentro ya no cuentan como activas.
 * <p>El host puede llevar esquema ({@code http://}); sin él se usa {@code https://}. El tiempo medido
 * es el de la respuesta completa (con el cuerpo si hay texto que comprobar).
 */
public final class HttpCheckMethodFactory implements PingMethodFactory {

    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final int expectedStatus;
    private final String expectedText;

    /** Sin condiciones: cualquier código por debajo de 400. */
    public HttpCheckMethodFactory() {
        this(0, "");
    }

    /**
     * @param expectedStatus código que debe devolver, o 0 para aceptar cualquiera por debajo de 400
     * @param expectedText   texto que debe contener el cuerpo; vacío o nulo no comprueba el cuerpo
     */
    public HttpCheckMethodFactory(int expectedStatus, String expectedText) {
        this.expectedStatus = expectedStatus;
        this.expectedText = expectedText == null ? "" : expectedText;
    }

    @Override
    public void ping(String host, int timeoutMillis, Consumer<PingResponse> callback) {

        HttpURLConnection connection = null;
        try {
            long initial = System.nanoTime();
            connection = (HttpURLConnection) new URL(address(host)).openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "NetworkUtils");

            int status = connection.getResponseCode();
            if (expectedStatus > 0 ? status != expectedStatus : status >= 400) {
                callback.accept(new PingErrorResponse(host, "Respondió con el código " + status));
                return;
            }
            if (!expectedText.isEmpty() && !body(connection, status).contains(expectedText)) {
                callback.accept(new PingErrorResponse(host, "La respuesta no contiene el texto esperado"));
                return;
            }
            callback.accept(new PingSuccessResponse(host, Math.max(0, (System.nanoTime() - initial) / 1_000_000)));

        } catch (SocketTimeoutException e) {
            callback.accept(new PingErrorResponse(host, "Se ha alcanzado el tiempo de petición"));
        } catch (UnknownHostException e) {
            callback.accept(new PingErrorResponse(host, "La dirección ip del host no pudo ser determinada"));
        } catch (IOException | RuntimeException e) {
            callback.accept(new PingErrorResponse(host, "No se pudo completar la petición HTTP"));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

    }

    static String address(String host) {

        String text = host.strip();
        return text.contains("://") ? text : "https://" + text;

    }

    private static String body(HttpURLConnection connection, int status) throws IOException {

        try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            return in == null ? "" : new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        }

    }

}
