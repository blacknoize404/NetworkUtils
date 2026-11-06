package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Eco ICMP sin lanzar un proceso: cada sistema tiene su propio camino nativo
 * ({@link WindowsIcmpSendEcho} en Windows, {@link UnixDgramEcho} en Linux y macOS).
 * Cuando la vía nativa no aplica —el host no resuelve a IPv4, falta la biblioteca
 * o el sistema no permite abrir el socket— lanza {@link Unavailable} y quien
 * llama cae al {@code ping} del sistema de toda la vida.
 */
final class NativeEcho {

    /** La vía nativa no puede atender esta petición; se intenta por proceso. */
    static final class Unavailable extends Exception {
        Unavailable(String reason) {
            super(reason);
        }
        Unavailable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    private NativeEcho() {
    }

    static PingResponse echo(String host, int timeoutMillis) throws Unavailable {

        InetAddress address = resolve(host);
        String os = System.getProperty("os.name", "").toLowerCase();

        try {
            if (os.startsWith("windows")) {
                return WindowsIcmpSendEcho.echo(host, address, timeoutMillis);
            }
            if (os.contains("linux") || os.contains("mac") || os.contains("darwin")) {
                return UnixDgramEcho.echo(host, address, timeoutMillis);
            }
        } catch (Unavailable e) {
            throw e;
        } catch (Throwable e) {
            // Incluye el ExceptionInInitializerError de una clase nativa sin su biblioteca.
            throw new Unavailable("la vía nativa no arranca en este sistema", e);
        }
        throw new Unavailable("sin implementación nativa para " + os);

    }

    /** Solo IPv4: IPv6 necesita otro camino (Icmp6SendEcho, ICMPv6) y sigue por proceso. */
    private static InetAddress resolve(String host) throws Unavailable {

        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet4Address)) {
                throw new Unavailable("el host resuelve a IPv6");
            }
            return address;
        } catch (java.net.UnknownHostException e) {
            throw new Unavailable("el host no resuelve: " + e.getMessage());
        }

    }

}
