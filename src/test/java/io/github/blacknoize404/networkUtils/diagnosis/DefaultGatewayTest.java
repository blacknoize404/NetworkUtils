package io.github.blacknoize404.networkUtils.diagnosis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGatewayTest {

    @Test
    void windowsTakesTheDefaultRouteWithTheLowestMetric() {

        String output = "===========================================================================\n"
                + "Rutas activas:\n"
                + "  Destino de red        Máscara de red  Puerta de enlace    Interfaz  Métrica\n"
                + "          0.0.0.0          0.0.0.0      10.0.0.1       10.0.0.50     50\n"
                + "          0.0.0.0          0.0.0.0      192.168.1.1   192.168.1.20     25\n"
                + "        127.0.0.0        255.0.0.0       En vínculo        127.0.0.1    331\n";

        assertEquals(Optional.of("192.168.1.1"), DefaultGateway.parseWindows(output));
        assertEquals(Optional.empty(), DefaultGateway.parseWindows("sin rutas"));

    }

    @Test
    void macTakesTheGatewayLine() {

        String output = "   route to: default\n"
                + "destination: default\n"
                + "       mask: default\n"
                + "    gateway: 192.168.1.1\n"
                + "  interface: en0\n"
                + "      flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>\n";

        assertEquals(Optional.of("192.168.1.1"), DefaultGateway.parseMac(output));
        assertEquals(Optional.empty(), DefaultGateway.parseMac("route: writing to routing socket: not in table"));

    }

    @Test
    void linuxDecodesTheLittleEndianGateway() {

        List<String> lines = List.of(
                "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT",
                "eth0\t00000000\t0101A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0",
                "wlan0\t00000000\t0100000A\t0003\t0\t0\t600\t00000000\t0\t0\t0",
                "eth0\t0001A8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\t0\t0\t0");

        assertEquals(Optional.of("192.168.1.1"), DefaultGateway.parseLinux(lines));
        assertEquals(Optional.empty(), DefaultGateway.parseLinux(List.of("Iface Destination Gateway")));

    }

    @Test
    void stripsTheSchemeAndPortBeforeResolving() {

        assertEquals("ejemplo.com/api", NetworkDiagnosis.stripScheme("https://ejemplo.com/api"));
        assertTrue(NetworkDiagnosis.resolves("localhost:8080"));
        assertTrue(NetworkDiagnosis.resolves("http://127.0.0.1/ruta"));
        assertFalse(NetworkDiagnosis.resolves("no-existe.invalid"));

    }

}
