package io.github.blacknoize404.networkUtils.traffic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void procNetDevListsEveryInterfaceExceptLoopback() {

        List<String> lines = List.of(
                "Inter-|   Receive                                                |  Transmit",
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
                "    lo: 9999 10 0 0 0 0 0 0 9999 10 0 0 0 0 0 0",
                "  eth0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0",
                " wlan0: 10 1 0 0 0 0 0 0 20 2 0 0 0 0 0 0"
        );

        List<TrafficSource.Counters> counters = TrafficSource.ProcNetDev.parse(lines, name -> !name.equals("wlan0"));

        assertEquals(List.of("eth0", "wlan0"), counters.stream().map(c -> c.info().id()).toList());
        assertEquals(List.of(true, false), counters.stream().map(c -> c.info().up()).toList());
        assertArrayEquals(new long[]{110, 220}, TrafficSource.sum(counters));

    }

    @Test
    void macNetstatKeepsLinkBytesAndMarksInterfacesWithAnAddressUp() {

        String output = "Name  Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll\n"
                + "lo0   16384 <Link#1>                          100   0         9999      100   0         9999    0\n"
                + "lo0   16384 127           localhost         100   0         9999      100   0         9999    0\n"
                + "en0   1500  <Link#6>      aa:bb:cc:dd:ee:ff 5000  0         1000000   4000  0         200000  0\n"
                + "en0   1500  10.0.0/24     10.0.0.50         5000  0         1000000   4000  0         200000  0\n"
                + "en1   1500  <Link#7>      11:22:33:44:55:66 10    0         500       10    0         500     0\n";

        List<TrafficSource.Counters> counters = TrafficSource.MacNetstat.parse(output);

        assertEquals(List.of("en0", "en1"), counters.stream().map(c -> c.info().id()).toList());
        assertEquals(List.of(true, false), counters.stream().map(c -> c.info().up()).toList());
        assertArrayEquals(new long[]{1000500, 200500}, TrafficSource.sum(counters));

    }

    @Test
    void selectionOfASingleInterfaceNeedsItsId() {

        assertThrows(IllegalArgumentException.class, () -> new TrafficSelection(TrafficSelection.Mode.ONLY, null));
        assertEquals("eth0", TrafficSelection.only("eth0").interfaceId());

    }

    @Test
    void windowsInterfaceTableListsRealAdaptersOnce() throws IOException {

        assumeTrue(System.getProperty("os.name", "").toLowerCase().startsWith("windows"));

        TrafficSource table;
        try {
            table = new TrafficSource.WindowsIfTable();
        } catch (Throwable e) {
            assumeTrue(false, "native access is not enabled");
            return;
        }
        List<TrafficSource.Counters> counters = table.readInterfaces();

        assertEquals(counters.size(), counters.stream().map(c -> c.info().id()).distinct().count());
        // Driver filter layers (WFP, QoS, Native WiFi...) repeat the adapter's counters and must not show up.
        assertTrue(counters.stream().noneMatch(c -> c.info().name().matches(".*-(WFP|QoS|Native WiFi|Virtual WiFi).*-\\d{4}")));

    }

}
