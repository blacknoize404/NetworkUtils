package io.github.blacknoize404.networkUtils.diagnosis;

import io.github.blacknoize404.networkUtils.latency.methods.TCPMethodFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Averigua por dónde se corta la conexión cuando un host deja de responder: comprueba por orden
 * el router, Internet y la resolución de nombres, y lo que falle primero es la causa.
 * Se llama cuando el host ya falló; tarda como mucho unos segundos por la espera de cada paso.
 */
public final class NetworkDiagnosis {

    public enum Cause {
        /** El router no responde: la red local o el propio equipo. */
        LOCAL_NETWORK("la red local o el router"),
        /** El router responde pero no se llega a Internet: el proveedor. */
        INTERNET("la salida a Internet (el proveedor)"),
        /** Se llega a Internet pero el nombre no se resuelve: el servidor DNS. */
        DNS("el servidor DNS"),
        /** Todo lo demás funciona: el problema es del host. */
        HOST("el propio host"),
        /** No se pudo concluir. */
        UNKNOWN("una causa desconocida");

        private final String description;

        Cause(String description) {
            this.description = description;
        }

        /** Lo que falla, listo para una frase («Parece fallar …»). */
        public String description() {
            return description;
        }
    }

    /**
     * @param cause   lo que falló primero
     * @param gateway la puerta de enlace comprobada, o {@code null} si no se encontró
     */
    public record Result(Cause cause, String gateway) {
    }

    // Servidores públicos conocidos: se prueba con ICMP y, si la red lo filtra, con TCP.
    private static final List<InetSocketAddress> PUBLIC_ENDPOINTS = List.of(
            InetSocketAddress.createUnresolved("1.1.1.1", 443),
            InetSocketAddress.createUnresolved("8.8.8.8", 53));

    private NetworkDiagnosis() {
    }

    public static Result diagnose(String host, int timeoutMillis) {

        Optional<String> gateway = DefaultGateway.find();

        if (gateway.isPresent() && !reachable(gateway.get(), timeoutMillis)) {
            return new Result(Cause.LOCAL_NETWORK, gateway.get());
        }
        if (!internetReachable(timeoutMillis)) {
            return new Result(Cause.INTERNET, gateway.orElse(null));
        }
        if (!resolves(host)) {
            return new Result(Cause.DNS, gateway.orElse(null));
        }
        return new Result(Cause.HOST, gateway.orElse(null));

    }

    private static boolean internetReachable(int timeoutMillis) {

        for (InetSocketAddress endpoint : PUBLIC_ENDPOINTS) {
            if (reachable(endpoint.getHostString(), timeoutMillis) || connects(endpoint, timeoutMillis)) {
                return true;
            }
        }
        return false;

    }

    /** El nombre del host (sin puerto ni esquema) se resuelve; una IP siempre «se resuelve». */
    static boolean resolves(String host) {

        String name = TCPMethodFactory.parse(stripScheme(host)).host();
        int slash = name.indexOf('/');
        if (slash > 0) {
            name = name.substring(0, slash);
        }
        try {
            InetAddress.getByName(name);
            return true;
        } catch (IOException e) {
            return false;
        }

    }

    static String stripScheme(String host) {

        int scheme = host.indexOf("://");
        return scheme >= 0 ? host.substring(scheme + 3) : host;

    }

    private static boolean connects(InetSocketAddress endpoint, int timeoutMillis) {

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.getHostString(), endpoint.getPort()), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }

    }

    /** Un eco ICMP con el {@code ping} del sistema: lo que importa es su código de salida, no el idioma. */
    static boolean reachable(String address, int timeoutMillis) {

        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        List<String> command = windows
                ? List.of("ping", "-n", "1", "-w", String.valueOf(timeoutMillis), address)
                : List.of("ping", "-c", "1", "-W", String.valueOf(Math.max(1, timeoutMillis / 1000)), address);

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(timeoutMillis + 3000L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

    }

}
