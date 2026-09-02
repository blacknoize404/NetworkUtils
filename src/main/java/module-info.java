/**
 * Un módulo para monitorear el tiempo de respuesta de un host remoto por distintos protocolos.
 */
module PingUtils {
    requires javafx.base;
    requires org.jetbrains.annotations;
    exports io.github.blacknoize404.pingUtils.methods;
    exports io.github.blacknoize404.pingUtils.responses;
    exports io.github.blacknoize404.pingUtils;
}