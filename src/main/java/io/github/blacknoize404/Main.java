package io.github.blacknoize404;

import io.github.blacknoize404.pingUtils.Ping;
import io.github.blacknoize404.pingUtils.methods.*;
import io.github.blacknoize404.pingUtils.responses.PingErrorResponse;
import io.github.blacknoize404.pingUtils.responses.PingResponse;
import io.github.blacknoize404.pingUtils.responses.PingSuccessResponse;

import java.util.HashSet;
import java.util.Set;

public class Main {

    public static Set<Long> set = new HashSet<>();

    public static void main(String[] args) throws InterruptedException {

        Ping p = Ping.withTimeout(
                "google.com.cu",
                500, new ICMPMethodFactory(),
                pingResponse -> {
                    if (pingResponse instanceof PingSuccessResponse) {
                        set.add(((PingSuccessResponse) pingResponse).getResponseMsecs());
                        System.out.println("Respuesta desde ICMP: " + pingResponse);
                        System.out.println(set.size());
                    } else if (pingResponse instanceof PingErrorResponse) {

                        System.out.println("Error desde ICMP: " + pingResponse);
                    }
                }
        );

        p.start();
//        p.poll();

        Ping p2 = Ping.withTimeout(
                "google.com.cu",
                500,
                new NativeICMPMethodFactory(),
                pingResponse -> {
                    if (pingResponse instanceof PingSuccessResponse) {
                        set.add(((PingSuccessResponse) pingResponse).getResponseMsecs());
                        System.out.println("Respuesta desde NativeICPM: " + pingResponse);
                        System.out.println(set.size());
                    } else if (pingResponse instanceof PingErrorResponse) {

                        System.out.println("Error desde NativeICPM: " + pingResponse);
                    }
                }
        );

        p2.start();

        Ping p3 = Ping.withTimeout(
                "google.com.cu",
                500,
                new HTTPMethodFactory(HTTPMethodFactory.Protocol.SECURE),
                Main::receive);

        p3.start();
        p2.stop();
        p.poll();
    }

    public static void receive(PingResponse pingResponse) {

        if (pingResponse instanceof PingSuccessResponse) {
            set.add(((PingSuccessResponse) pingResponse).getResponseMsecs());
            System.out.println("Respuesta desde HTTPS: " + pingResponse);
            System.out.println(set.size());
        } else if (pingResponse instanceof PingErrorResponse) {

            System.out.println("Error desde HTTP: " + pingResponse);
        }
    }

}