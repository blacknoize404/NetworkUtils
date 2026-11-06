package io.github.blacknoize404.networkUtils.latency;

import io.github.blacknoize404.networkUtils.latency.methods.PingMethodFactory;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los dos modos de reparto del intervalo se distinguen por el hueco entre
 * respuestas: con {@code AFTER_COMPLETION} el intervalo se suma a lo que tarda
 * la petición; con {@code EVERY_INTERVAL} una petición sale cada intervalo
 * aunque la anterior siga en marcha.
 */
class HostActivityServiceIntervalModeTest {

    /** Intervalo y coste de petición elegidos para que los huecos se separen con holgura. */
    private static final int INTERVAL = 150;
    private static final int PROBE_COST = 100;
    private static final int RESPONSES = 5;

    /** Método falso: cada petición bloquea {@code PROBE_COST} ms y responde con éxito. */
    private static PingMethodFactory slowMethod() {
        return (host, timeout, callback) -> {
            try {
                Thread.sleep(PROBE_COST);
            } catch (InterruptedException ignored) {
            }
            callback.accept(new PingSuccessResponse(host, PROBE_COST));
        };
    }

    /** Hueco medio entre los instantes de {@code RESPONSES} respuestas seguidas. */
    private static double measureAverageGap(HostActivityService service) throws InterruptedException {

        List<Long> times = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(RESPONSES);
        service.setOnPingResponse(response -> {
            if (response instanceof PingSuccessResponse) {
                times.add(response.getTimestamp().toEpochMilli());
                done.countDown();
            }
        });

        service.start();
        assertTrue(done.await(10, TimeUnit.SECONDS), "las respuestas no llegaron a tiempo");
        service.close();

        long gaps = 0;
        for (int i = 1; i < times.size(); i++) {
            gaps += times.get(i) - times.get(i - 1);
        }
        return (double) gaps / (times.size() - 1);

    }

    private static HostActivityService service(HostActivityService.IntervalMode mode) {

        HostActivityService service = HostActivityService.of(
                "localhost", 5000, INTERVAL, 0, slowMethod(), null);
        service.setIntervalMode(mode);
        return service;

    }

    @Test
    void afterCompletionAddsProbeCostToInterval() throws InterruptedException {

        double gap = measureAverageGap(service(HostActivityService.IntervalMode.AFTER_COMPLETION));

        // ~250 ms esperados (150 + 100); el planificador solo puede llegar tarde, nunca antes
        assertTrue(gap > 210, "hueco " + gap + " ms: no parece intervalo + coste de petición");

    }

    @Test
    void everyIntervalKeepsTheRateDespiteProbeCost() throws InterruptedException {

        double gap = measureAverageGap(service(HostActivityService.IntervalMode.EVERY_INTERVAL));

        // ~150 ms esperados; el promedio se sostiene aunque una ejecución se atrase
        assertTrue(gap < 210, "hueco " + gap + " ms: el ritmo no se mantuvo");

    }
}
