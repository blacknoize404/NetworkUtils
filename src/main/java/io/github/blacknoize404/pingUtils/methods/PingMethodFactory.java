package io.github.blacknoize404.pingUtils.methods;

import io.github.blacknoize404.pingUtils.responses.PingResponse;
import java.util.function.Consumer;

/**
 * Representa una operación de detección de respuesta de un host.
 *
 * <p>Esta es una <a href="package-summary.html">interfaz funcional</a>
 * cuyo método funcional es {@link #ping(String, int, Consumer)}.
 *
 */
@FunctionalInterface
public interface PingMethodFactory {

    /**
     * Ejecuta esta operación
     * @param host el sitio al cual realizar la detección
     * @param timeoutMillis los milisegundos de espera en caso de que no responda
     * @param callback el método de retorno de la respuesta
     */
    void ping(String host, int timeoutMillis, Consumer<PingResponse> callback);

}
