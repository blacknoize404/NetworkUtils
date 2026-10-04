package io.github.blacknoize404.networkUtils.traffic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrafficSourceTest {

    @Test
    void netstatBytesRowIsFoundInAnyLanguage() throws IOException {

        String spanish = "Estadísticas de interfaz\n\n"
                + "                                 Recibidos        Enviados\n\n"
                + "Bytes                           3254579358       525243462\n"
                + "Paquetes de unidifusión            3181356          989382\n";
        String english = "Interface Statistics\n\n"
                + "                           Received            Sent\n\n"
                + "Bytes                     1000                2000\n"
                + "Unicast packets             10                  20\n";

        assertArrayEquals(new long[]{3254579358L, 525243462L}, TrafficSource.Netstat.parse(spanish));
        assertArrayEquals(new long[]{1000, 2000}, TrafficSource.Netstat.parse(english));

    }

    @Test
    void netstatWithoutBytesRowFails() {

        assertThrows(IOException.class, () -> TrafficSource.Netstat.parse("nothing useful here"));

    }

    @Test
    void procNetDevSumsInterfacesExceptLoopback() {

        List<String> lines = List.of(
                "Inter-|   Receive                                                |  Transmit",
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
                "    lo: 9999 10 0 0 0 0 0 0 9999 10 0 0 0 0 0 0",
                "  eth0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0",
                " wlan0: 10 1 0 0 0 0 0 0 20 2 0 0 0 0 0 0"
        );

        assertArrayEquals(new long[]{110, 220}, TrafficSource.ProcNetDev.parse(lines));

    }

}
