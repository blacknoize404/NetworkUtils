/**
 * Detectores de red: el tiempo de respuesta de un host remoto por distintos protocolos
 * y la velocidad de descarga y subida del sistema.
 */
module NetworkUtils {
    requires javafx.base;
    requires org.jetbrains.annotations;
    exports io.github.blacknoize404.networkUtils.latency.methods;
    exports io.github.blacknoize404.networkUtils.latency.responses;
    exports io.github.blacknoize404.networkUtils.latency;
    exports io.github.blacknoize404.networkUtils.traffic;
}
