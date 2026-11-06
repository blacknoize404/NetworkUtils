package io.github.blacknoize404.networkUtils.latency.methods;

import io.github.blacknoize404.networkUtils.latency.responses.PingErrorResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingResponse;
import io.github.blacknoize404.networkUtils.latency.responses.PingSuccessResponse;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.net.InetAddress;
import java.nio.ByteOrder;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * {@code IcmpSendEcho} de {@code iphlpapi.dll}: la misma función que usa {@code ping.exe},
 * sin privilegios y sin crear un proceso por sondeo. Es solo IPv4; la resolución
 * queda fuera ({@link NativeEcho}) y cualquier cosa rara sube como
 * {@link NativeEcho.Unavailable} para caer al ping por proceso.
 *
 * <p>El {@code ICMP_ECHO_REPLY} de x64 ocupa 36 bytes: Address (4), Status (4),
 * RoundTripTime (4), DataSize (2), Reserved (2), Data (puntero, 8) y
 * Options (IP_OPTION_INFORMATION, 12). El búfer de respuesta ha de llevar además
 * el eco de la petición y ocho bytes de margen para errores.
 */
final class WindowsIcmpSendEcho {

    private static final long STATUS = 4;
    private static final long ROUND_TRIP = 8;
    private static final int REPLY_STRUCT = 36;
    private static final int REQUEST_BYTES = 32;
    private static final int REPLY_BUFFER = REPLY_STRUCT + REQUEST_BYTES + 8 + 64;

    private static final int IP_SUCCESS = 0;
    private static final int IP_REQ_TIMED_OUT = 11010;
    private static final int IP_DEST_HOST_UNREACHABLE = 11003;
    private static final int IP_DEST_NET_UNREACHABLE = 11002;
    private static final int IP_TTL_EXPIRED_TRANSIT = 11013;

    /** Los símbolos se resuelven al primer eco: fuera de Windows la clase debe poder leerse. */
    private static final class Handles {

        private static final MethodHandle CREATE_FILE = find("IcmpCreateFile",
                FunctionDescriptor.of(ADDRESS));
        private static final MethodHandle SEND_ECHO = find("IcmpSendEcho",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_SHORT,
                        ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        private static final MethodHandle CLOSE_HANDLE = find("IcmpCloseHandle",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

    }

    private WindowsIcmpSendEcho() {
    }

    /** Las funciones están en iphlpapi desde Windows Vista; icmp.dll es la fuente histórica. */
    private static MethodHandle find(String name, FunctionDescriptor signature) {

        Linker linker = Linker.nativeLinker();
        for (String library : new String[]{"iphlpapi", "icmp"}) {
            try {
                SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
                Optional<MemorySegment> symbol = lookup.find(name);
                if (symbol.isPresent()) {
                    return linker.downcallHandle(symbol.get(), signature);
                }
            } catch (IllegalArgumentException e) {
                // Biblioteca ausente en este sistema: se intenta la siguiente.
            }
        }
        throw new ExceptionInInitializerError("iphlpapi/icmp no exporta " + name);

    }

    static PingResponse echo(String host, InetAddress address, int timeoutMillis)
            throws NativeEcho.Unavailable {

        try (Arena arena = Arena.ofConfined()) {

            MemorySegment icmp;
            try {
                icmp = (MemorySegment) Handles.CREATE_FILE.invokeExact();
            } catch (Throwable e) {
                throw new NativeEcho.Unavailable("IcmpCreateFile no invocable", e);
            }
            if (icmp.equals(MemorySegment.NULL)) {
                throw new NativeEcho.Unavailable("IcmpCreateFile devolvió un handle nulo");
            }

            try {
                MemorySegment request = arena.allocate(REQUEST_BYTES);
                for (int i = 0; i < REQUEST_BYTES; i++) {
                    request.set(JAVA_BYTE, i, (byte) ('a' + i % 23));
                }
                MemorySegment reply = arena.allocate(REPLY_BUFFER);

                // IPAddr es el IPv4 en orden de red: la dirección ya viene big-endian.
                int destination = address.getAddress()[0] & 0xFF
                        | (address.getAddress()[1] & 0xFF) << 8
                        | (address.getAddress()[2] & 0xFF) << 16
                        | (address.getAddress()[3] & 0xFF) << 24;

                int replies = (int) Handles.SEND_ECHO.invokeExact(
                        icmp, destination, request, (short) REQUEST_BYTES,
                        MemorySegment.NULL, reply, REPLY_BUFFER, timeoutMillis);

                if (replies < 1) {
                    return new PingErrorResponse(host, "Tiempo de espera agotado para esta solicitud.");
                }
                int status = reply.get(JAVA_INT, STATUS);
                if (status == IP_SUCCESS) {
                    return new PingSuccessResponse(host, reply.get(JAVA_INT, ROUND_TRIP) & 0xFFFFFFFFL);
                }
                return new PingErrorResponse(host, message(status));

            } finally {
                int closed = (int) Handles.CLOSE_HANDLE.invokeExact(icmp);
            }

        } catch (NativeEcho.Unavailable e) {
            throw e;
        } catch (Throwable e) {
            throw new NativeEcho.Unavailable("IcmpSendEcho no invocable", e);
        }

    }

    private static String message(int status) {

        return switch (status) {
            case IP_REQ_TIMED_OUT -> "Tiempo de espera agotado para esta solicitud.";
            case IP_DEST_HOST_UNREACHABLE -> "Host de destino inaccesible.";
            case IP_DEST_NET_UNREACHABLE -> "Red de destino inaccesible.";
            case IP_TTL_EXPIRED_TRANSIT -> "TTL agotado en tránsito.";
            default -> "Error de eco ICMP (código " + status + ").";
        };

    }

}
