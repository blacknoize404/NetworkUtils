package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodsTest {

    private static PingResponse run(PingMethodFactory method, String host) {

        PingResponse[] result = new PingResponse[1];
        method.ping(host, 2000, response -> result[0] = response);
        return result[0];

    }

    @Test
    void tcpAddressIsSplitIntoHostAndPort() {

        assertEquals(new TCPMethodFactory.Endpoint("ejemplo.com", 5432), TCPMethodFactory.parse("ejemplo.com:5432"));
        assertEquals(new TCPMethodFactory.Endpoint("ejemplo.com", 80), TCPMethodFactory.parse(" ejemplo.com "));
        assertEquals(new TCPMethodFactory.Endpoint("ejemplo.com", 80), TCPMethodFactory.parse("ejemplo.com:abc"));
        assertEquals(new TCPMethodFactory.Endpoint("ejemplo.com", 80), TCPMethodFactory.parse("ejemplo.com:70000"));
        assertEquals(new TCPMethodFactory.Endpoint("::1", 443), TCPMethodFactory.parse("[::1]:443"));
        assertEquals(new TCPMethodFactory.Endpoint("fe80::1", 80), TCPMethodFactory.parse("fe80::1"));

    }

    @Test
    void tcpConnectsToAnOpenPortAndFailsOnAClosedOne() throws IOException {

        int open;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            open = server.getLocalPort();
            assertInstanceOf(PingSuccessResponse.class, run(new TCPMethodFactory(), "127.0.0.1:" + open));
        }
        // Cerrado el servidor, ese puerto ya no acepta conexiones
        PingResponse closed = run(new TCPMethodFactory(), "127.0.0.1:" + open);
        assertInstanceOf(PingErrorResponse.class, closed);
        assertTrue(closed.getResponse().contains(String.valueOf(open)));

    }

    @Test
    void dnsResolvesALocalNameAndFailsOnAnInvalidOne() {

        assertInstanceOf(PingSuccessResponse.class, run(new DNSMethodFactory(), "localhost"));
        assertInstanceOf(PingErrorResponse.class, run(new DNSMethodFactory(), "no-existe.invalid"));

    }

    @Test
    void httpCheckAcceptsOnlyTheExpectedResponse() throws IOException {

        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread thread = new Thread(() -> serve(server), "test-http");
            thread.setDaemon(true);
            thread.start();
            String base = "http://127.0.0.1:" + server.getLocalPort();

            assertInstanceOf(PingSuccessResponse.class, run(new HttpCheckMethodFactory(), base + "/ok"));
            assertInstanceOf(PingErrorResponse.class, run(new HttpCheckMethodFactory(), base + "/roto"));
            assertInstanceOf(PingSuccessResponse.class, run(new HttpCheckMethodFactory(500, ""), base + "/roto"));
            assertInstanceOf(PingErrorResponse.class, run(new HttpCheckMethodFactory(201, ""), base + "/ok"));
            assertInstanceOf(PingSuccessResponse.class, run(new HttpCheckMethodFactory(200, "OK"), base + "/ok"));

            PingResponse missing = run(new HttpCheckMethodFactory(0, "otra cosa"), base + "/ok");
            assertInstanceOf(PingErrorResponse.class, missing);
            assertTrue(missing.getResponse().contains("texto esperado"));
        }

    }

    /** Un servidor HTTP mínimo: /ok responde 200 con "todo bien: OK" y cualquier otra ruta, 500. */
    private static void serve(ServerSocket server) {

        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                String request = in.readLine();
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    // Se descartan las cabeceras
                }
                boolean ok = request != null && request.startsWith("GET /ok ");
                String body = ok ? "todo bien: OK" : "fallo";
                String head = "HTTP/1.1 " + (ok ? "200 OK" : "500 Error") + "\r\nContent-Length: " + body.length()
                        + "\r\nConnection: close\r\n\r\n";
                client.getOutputStream().write((head + body).getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                return;
            }
        }

    }

    @Test
    void httpCheckAddsHttpsWhenThereIsNoScheme() {

        assertEquals("https://ejemplo.com/api", HttpCheckMethodFactory.address(" ejemplo.com/api "));
        assertEquals("http://ejemplo.com", HttpCheckMethodFactory.address("http://ejemplo.com"));

    }

    private static PingResponse parseNative(String output) {
        PingResponse[] result = new PingResponse[1];
        NativeICMPMethodFactory.parseUnix("host", output, response -> result[0] = response);
        return result[0];
    }

    @Test
    void nativePingReadsTheUnixOutput() {

        String ok = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\n"
                + "64 bytes from 8.8.8.8: icmp_seq=0 ttl=117 time=14.2 ms\n\n"
                + "--- 8.8.8.8 ping statistics ---\n"
                + "1 packets transmitted, 1 packets received, 0.0% packet loss";
        PingResponse success = parseNative(ok);
        assertInstanceOf(PingSuccessResponse.class, success);
        assertEquals(14, ((PingSuccessResponse) success).getResponseMsecs());

        String down = "PING 1.2.3.4 (1.2.3.4): 56 data bytes\n\n"
                + "--- 1.2.3.4 ping statistics ---\n"
                + "1 packets transmitted, 0 packets received, 100.0% packet loss";
        assertInstanceOf(PingErrorResponse.class, parseNative(down));

        String unknown = "ping: cannot resolve no-existe.invalid: Unknown host";
        assertInstanceOf(PingErrorResponse.class, parseNative(unknown));

    }

}
