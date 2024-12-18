package io.github.blacknoize404;

import io.github.blacknoize404.pingUtils.Ping;
import io.github.blacknoize404.pingUtils.methods.*;
import io.github.blacknoize404.pingUtils.responses.PingErrorResponse;
import io.github.blacknoize404.pingUtils.responses.PingResponse;
import io.github.blacknoize404.pingUtils.responses.PingSuccessResponse;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Main {

    public static Set<Long> set = new HashSet<>();

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
////        p.poll();
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

        Ping p3 = Ping.withTimeout(
                "ahora.cu",
                500,
                PingMethod.HTTP_SECURE.method(),
                Main::receive);

        p3.start();

        CountDownLatch c = new CountDownLatch(1);

        c.await(2, TimeUnit.SECONDS);

        p3.changeUrl("google.com.cu");

        c.await(10, TimeUnit.SECONDS);

    }

    public static void receive(PingResponse pingResponse) {

        if (pingResponse instanceof PingSuccessResponse) {
            System.out.println("Respuesta desde " + pingResponse.getHost() + " vía HTTPS: " + pingResponse);
        } else if (pingResponse instanceof PingErrorResponse) {
            System.out.println("Error desde " + pingResponse.getHost() + " vía HTTP: " + pingResponse);
        } else {
            System.out.println("Mensaje desde " + pingResponse.getHost() + " vía HTTP: " + pingResponse);
        }
    }

}