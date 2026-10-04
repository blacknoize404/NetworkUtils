package io.github.blacknoize404;


import io.github.blacknoize404.networkUtils.latency.HostActivityService;
import io.github.blacknoize404.networkUtils.latency.methods.PingMethod;
import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

public class Main {

    public static void main(String[] args) throws InterruptedException {

//        Ping p = Ping.withTimeout(
//                "google.com.cu",
//                500,
//                new ICMPMethodFactory(),
//                pingResponse -> {
//                    if (pingResponse instanceof PingSuccessResponse) {
//                        System.out.println("Respuesta desde ICMP: " + pingResponse);
//                    } else if (pingResponse instanceof PingErrorResponse) {
//                        System.out.println("Error desde ICMP: " + pingResponse);
//                    }
//                }
//        );
//
//        p.start();
//        p.poll();
//
//        Ping p2 = Ping.withTimeout(
//                "google.com.cu",
//                500,
//                new NativeICMPMethodFactory(),
//                pingResponse -> {
//                    if (pingResponse instanceof PingSuccessResponse) {
//                        System.out.println("Respuesta desde NativeICPM: " + pingResponse);
//                    } else if (pingResponse instanceof PingErrorResponse) {
//
//                        System.out.println("Error desde NativeICPM: " + pingResponse);
//                    }
//                }
//        );
//
//        p2.start();

        HostActivityService p3 = HostActivityService.of(
                "google.com.cu",
                500,
                -1,
                -1,
                PingMethod.ICMP.method(),
                null);

        p3.start();

        p3.setOnPingResponse(Main::receive);

        p3.poll();
//        CountDownLatch c = new CountDownLatch(1);
//
//        c.await(2, TimeUnit.SECONDS);
//
//        p3.changeUrl("google.com.cu");
//
//        c.await(100, TimeUnit.SECONDS);

    }

    public static void receive(PingResponse pingResponse) {

        if (pingResponse instanceof PingSuccessResponse) {
            System.out.println("Respuesta desde " + pingResponse.getHost() + " vía HTTP: " + pingResponse);
        } else if (pingResponse instanceof PingErrorResponse) {
            System.out.println("Error desde " + pingResponse.getHost() + " vía HTTP: " + pingResponse);
        } else {
            System.out.println("Mensaje desde " + pingResponse.getHost() + " vía HTTP: " + pingResponse);
        }
    }

}