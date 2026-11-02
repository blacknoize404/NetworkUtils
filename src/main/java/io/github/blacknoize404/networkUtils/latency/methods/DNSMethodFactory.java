package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Mide cuánto tarda el sistema en resolver el nombre de un host con su servidor DNS, sin conectar
 * con él. Útil para distinguir un DNS lento o caído de un host caído.
 * <p>Java guarda en caché las resoluciones y así cada medida sería casi instantánea: al cargar esta
 * clase se desactiva esa caché (propiedad de seguridad {@code networkaddress.cache.ttl}), lo que
 * solo surte efecto si todavía no se ha resuelto ningún nombre en la máquina virtual.
 */
public final class DNSMethodFactory implements PingMethodFactory {

    static {
        try {
            Security.setProperty("networkaddress.cache.ttl", "0");
            Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (SecurityException ignored) {
            // Sin permiso, las medidas pueden salir de la caché.
        }
    }

    @Override
    public void ping(String host, int timeoutMillis, Consumer<PingResponse> callback) {

        long initial = System.nanoTime();
        CompletableFuture<InetAddress> lookup = CompletableFuture.supplyAsync(() -> {
            try {
                return InetAddress.getByName(host.strip());
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        });

        try {
            lookup.get(timeoutMillis, TimeUnit.MILLISECONDS);
            callback.accept(new PingSuccessResponse(host, Math.max(0, (System.nanoTime() - initial) / 1_000_000)));
        } catch (TimeoutException e) {
            lookup.cancel(true);
            callback.accept(new PingErrorResponse(host, "El servidor DNS no respondió a tiempo"));
        } catch (ExecutionException e) {
            callback.accept(new PingErrorResponse(host, "El nombre no se pudo resolver con el servidor DNS"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.accept(new PingErrorResponse(host, "Resolución interrumpida"));
        }

    }

}
