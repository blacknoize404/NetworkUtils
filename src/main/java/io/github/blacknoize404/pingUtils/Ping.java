package io.github.blacknoize404.pingUtils;

import io.github.blacknoize404.pingUtils.methods.PingMethodFactory;
import io.github.blacknoize404.pingUtils.responses.PingResponse;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.*;
import java.util.function.Consumer;

import static io.github.blacknoize404.pingUtils.Ping.State.*;

public final class Ping {

    enum State {
        RUNNING,
        STOPPED
    }

    private State state;

    private final String location;
    private final int timeoutMillis;
    private final int updateRateMillis;

    private final boolean isUsingFixedRequestQuantity;
    private final int requestQuantity;
    private int requestsLeft;

    private final PingMethodFactory method;
    private final Consumer<PingResponse> callback;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    @Contract("_, _, _, _ -> new")
    public static @NotNull Ping ofRequestQuantity(String location, int requestQuantity, PingMethodFactory method, Consumer<PingResponse> callback) {

        if (requestQuantity <= 0) throw new IllegalArgumentException("Debe definir una cantidad de peticiones mayor que 0");

        return new Ping(location, -1, -1, requestQuantity, method, callback, 1);

    }

    @Contract("_, _, _, _ -> new")
    public static @NotNull Ping withUpdateRate(String location, int updateRateMillis, PingMethodFactory method, Consumer<PingResponse> callback) {

        if (updateRateMillis < 0) throw new IllegalArgumentException("Debe definir una duración mayor que 0 milisegundos");

        return new Ping(location, -1, updateRateMillis, 0, method, callback, 1);

    }

    public static @NotNull Ping withTimeout(String location, int timeoutMillis, PingMethodFactory method, Consumer<PingResponse> callback) {

        if (timeoutMillis < 0) throw new IllegalArgumentException("Debe definir una duración mayor que 0 milisegundos");

        return new Ping(location, timeoutMillis, -1, 0, method, callback, 1);

    }

    @Contract("_, _, _ -> new")
    public static @NotNull Ping of(String location, PingMethodFactory method, Consumer<PingResponse> callback) {

        return new Ping(location, -1, -1, 0, method, callback, 1);

    }

    private Ping(String location, int timeoutMillis, int updateRateMillis, int requestQuantity, PingMethodFactory method, Consumer<PingResponse> callback, int corePoolSize) {
        this.location = location;

        this.timeoutMillis = (updateRateMillis < 0) ? 10000 : timeoutMillis;

        this.updateRateMillis = (updateRateMillis < 0) ? 1000 : updateRateMillis;

        this.isUsingFixedRequestQuantity = requestQuantity > 0;
        this.requestQuantity = requestQuantity;
        this.requestsLeft = requestQuantity;

        this.method = method;
        this.callback = callback;
        this.scheduler = Executors.newScheduledThreadPool(corePoolSize, new DaemonThreadFactory());
    }

    public void start() {

        if (state == RUNNING) throw new IllegalStateException("El scheduler ya ha sido iniciado");
        state = RUNNING;

        task = scheduler.scheduleAtFixedRate(this::run, 0, updateRateMillis, TimeUnit.MILLISECONDS);

    }

    public void poll() throws InterruptedException {

        if (state != RUNNING) throw new IllegalStateException("No se ha iniciado el scheduler");

        while (!task.isDone()) {
            TimeUnit.MILLISECONDS.sleep(100);
        }

    }

    public void stop() {
        state = STOPPED;
        reset();
    }

    private void reset() {

        if (isUsingFixedRequestQuantity) {
            requestsLeft = requestQuantity;
        }

    }

    private void run() {

        if (isUsingFixedRequestQuantity) {

            if (requestsLeft == 0) state = STOPPED;
            else requestsLeft -= 1;

        }

        if (state != RUNNING) {
            scheduler.shutdown();
            reset();
            return;
        }

        try {
            method.ping(location, timeoutMillis, callback);
            TimeUnit.MILLISECONDS.sleep(this.updateRateMillis);
        } catch (InterruptedException e) {
            System.out.println("ERROR GRAVE: " + e.getMessage());
        }

    }

    private static class DaemonThreadFactory implements ThreadFactory {

        @Override
        public @NotNull Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        }

    }

}
