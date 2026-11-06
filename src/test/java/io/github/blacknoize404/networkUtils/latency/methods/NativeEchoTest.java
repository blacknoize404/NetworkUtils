package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * La vía nativa de eco ICMP: el paquete que se construye y la llamada real
 * ({@code IcmpSendEcho} en Windows, socket datagram en Unix) contra 127.0.0.1.
 */
class NativeEchoTest {

    @Test
    void icmpChecksumMatchesTheStandardSum() {

        // Palabras de 16 bits: 0x0800 + 0x0000 + 0x0000 + 0x0001 = 0x0801 -> ~0x0801
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(8);
            p.set(JAVA_BYTE, 0, (byte) 8);
            p.set(JAVA_BYTE, 7, (byte) 1);
            assertEquals(0xF7FE, UnixDgramEcho.checksum(p, 8));
        }

    }

    @Test
    void theEchoPacketCarriesItsMagicAndAValidChecksum() {

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = UnixDgramEcho.packet(arena);
            assertEquals(8, packet.get(JAVA_BYTE, 0) & 0xFF);   // echo request
            assertEquals(0, packet.get(JAVA_BYTE, 1) & 0xFF);   // código
            assertEquals('P', (char) (packet.get(JAVA_BYTE, 8) & 0xFF));
            // El checksum revalida sobre el paquete entero: la suma debe dar 0
            assertEquals(0, UnixDgramEcho.checksum(packet, packet.byteSize()));
        }

    }

    @Test
    void nativeEchoReachesLoopback() {

        try {
            PingResponse response = NativeEcho.echo("127.0.0.1", 3000);
            assertInstanceOf(PingSuccessResponse.class, response);
            assertTrue(((PingSuccessResponse) response).getResponseMsecs() >= 0);
        } catch (NativeEcho.Unavailable e) {
            fail("la vía nativa no está disponible en este sistema: " + e.getMessage());
        }

    }

    @Test
    void theFactoryPrefersTheNativePath() {

        PingResponse[] result = new PingResponse[1];
        new NativeICMPMethodFactory().ping("127.0.0.1", 3000, r -> result[0] = r);
        assertInstanceOf(PingSuccessResponse.class, result[0]);

    }

}
