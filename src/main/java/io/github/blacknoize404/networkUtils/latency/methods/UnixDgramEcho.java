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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Eco ICMP por el «ping socket» de Unix ({@code SOCK_DGRAM, IPPROTO_ICMP}): el mismo
 * socket sin privilegios que usa {@code ping} en Linux y macOS. El kernel asigna el
 * identificador ICMP y rehace el checksum; la respuesta llega ya sin cabecera IP.
 *
 * <p>En Linux depende de {@code net.ipv4.ping_group_range} —las distribuciones con
 * systemd lo abren por defecto—; si el socket no puede abrirse, se lanza
 * {@link NativeEcho.Unavailable} y la petición sigue por el {@code ping} del sistema.
 * Solo IPv4 (ICMPv6 necesitaría otro protocolo).
 */
final class UnixDgramEcho {

    private static final int AF_INET = 2;
    private static final int SOCK_DGRAM = 2;
    private static final int IPPROTO_ICMP = 1;

    // SOL_SOCKET / SO_RCVTIMEO cambian de valor entre Linux y macOS
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static final int SOL_SOCKET = MAC ? 0xFFFF : 1;
    private static final int SO_RCVTIMEO = MAC ? 0x1006 : 20;

    private static final int ECHO_REQUEST = 8;
    private static final int ECHO_REPLY = 0;
    private static final int DEST_UNREACHABLE = 3;
    private static final int TIME_EXCEEDED = 11;

    private static final int PACKET = 32;          // cabecera ICMP (8) + carga (24)
    private static final byte[] MAGIC = {'P', 'M', '0', '1'};

    /**
     * Los símbolos de la libc se resuelven al primer eco, no al cargar la clase: en Windows
     * no existe y el paquete y el checksum se pueden construir igualmente (los usan los tests).
     */
    private static final class Handles {

        private static final MethodHandle SOCKET = find("socket",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle SENDTO = find("sendto",
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle RECVFROM = find("recvfrom",
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle SETSOCKOPT = find("setsockopt",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle CLOSE = find("close",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    }

    private UnixDgramEcho() {
    }

    /** La libc: en macOS forma parte de la imagen por defecto; en Linux es libc.so.6. */
    private static MethodHandle find(String name, FunctionDescriptor signature) {

        Linker linker = Linker.nativeLinker();
        for (Optional<MemorySegment> symbol : new Optional[]{
                Linker.nativeLinker().defaultLookup().find(name),
                lookup(MAC ? "libSystem.dylib" : "libc.so.6").find(name)}) {
            if (symbol.isPresent()) {
                return linker.downcallHandle(symbol.get(), signature);
            }
        }
        throw new ExceptionInInitializerError("la libc no exporta " + name);

    }

    private static SymbolLookup lookup(String name) {

        try {
            return SymbolLookup.libraryLookup(name, Arena.global());
        } catch (IllegalArgumentException e) {
            return ignored -> Optional.empty();
        }

    }

    static PingResponse echo(String host, InetAddress address, int timeoutMillis)
            throws NativeEcho.Unavailable {

        try (Arena arena = Arena.ofConfined()) {

            int fd;
            try {
                fd = (int) Handles.SOCKET.invokeExact(AF_INET, SOCK_DGRAM, IPPROTO_ICMP);
            } catch (Throwable e) {
                throw new NativeEcho.Unavailable("socket() no invocable", e);
            }
            if (fd < 0) {
                // Sin permiso para el ping socket (ping_group_range, sandbox…): por proceso.
                throw new NativeEcho.Unavailable("el sistema no permite abrir un socket ICMP");
            }

            try {
                setTimeout(arena, fd, timeoutMillis);
                MemorySegment target = sockaddr(arena, address);
                MemorySegment packet = packet(arena);

                long sent = (long) Handles.SENDTO.invokeExact(fd, packet, (long) PACKET, 0, target, 16);
                if (sent < 0) {
                    throw new NativeEcho.Unavailable("sendto() rechazado por el sistema");
                }
                long started = System.nanoTime();

                MemorySegment reply = arena.allocate(1024);
                MemorySegment source = arena.allocate(32);
                MemorySegment sourceLength = arena.allocate(JAVA_INT, 32);
                long received = (long) Handles.RECVFROM.invokeExact(fd, reply, 1024L, 0, source, sourceLength);
                if (received < 0) {
                    return new PingErrorResponse(host, "Tiempo de espera agotado para esta solicitud.");
                }
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                int type = reply.get(JAVA_BYTE, 0) & 0xFF;
                if (type == ECHO_REPLY && received >= PACKET && own(reply)) {
                    return new PingSuccessResponse(host, millis);
                }
                if (type == DEST_UNREACHABLE) {
                    return new PingErrorResponse(host, "Host de destino inaccesible.");
                }
                if (type == TIME_EXCEEDED) {
                    return new PingErrorResponse(host, "TTL agotado en tránsito.");
                }
                return new PingErrorResponse(host, "Respuesta ICMP inesperada (tipo " + type + ").");

            } finally {
                int closed = (int) Handles.CLOSE.invokeExact(fd);
            }

        } catch (NativeEcho.Unavailable e) {
            throw e;
        } catch (Throwable e) {
            throw new NativeEcho.Unavailable("el eco ICMP no se pudo enviar", e);
        }

    }

    /** {@code SO_RCVTIMEO} con un {@code timeval} de dos longs: sin él un host caído bloquearía el hilo. */
    private static void setTimeout(Arena arena, int fd, int timeoutMillis) throws Throwable, NativeEcho.Unavailable {

        MemorySegment timeval = arena.allocate(16);
        timeval.set(JAVA_LONG, 0, timeoutMillis / 1000L);
        timeval.set(JAVA_LONG, 8, (timeoutMillis % 1000L) * 1000L);
        if ((int) Handles.SETSOCKOPT.invokeExact(fd, SOL_SOCKET, SO_RCVTIMEO, timeval, 16) != 0) {
            throw new NativeEcho.Unavailable("setsockopt(SO_RCVTIMEO) rechazado");
        }

    }

    /**
     * {@code sockaddr_in}: en macOS lleva un byte {@code sin_len} delante de la familia;
     * en Linux la familia ocupa dos. El puerto y la dirección van en orden de red.
     */
    private static MemorySegment sockaddr(Arena arena, InetAddress address) {

        MemorySegment out = arena.allocate(16);
        byte[] ip = address.getAddress();
        if (MAC) {
            out.set(JAVA_BYTE, 0, (byte) 16);
            out.set(JAVA_BYTE, 1, (byte) AF_INET);
        } else {
            out.set(JAVA_BYTE, 0, (byte) AF_INET);
            out.set(JAVA_BYTE, 1, (byte) 0);
        }
        out.set(JAVA_BYTE, 2, (byte) 0);
        out.set(JAVA_BYTE, 3, (byte) 0);
        for (int i = 0; i < 4; i++) {
            out.set(JAVA_BYTE, 4 + i, ip[i]);
        }
        return out;

    }

    /** El paquete de eco: tipo, código, checksum, identificador 0 (lo pone el kernel), secuencia y la marca. */
    static MemorySegment packet(Arena arena) {

        MemorySegment packet = arena.allocate(PACKET);
        packet.set(JAVA_BYTE, 0, (byte) ECHO_REQUEST);
        packet.set(JAVA_BYTE, 6, (byte) 0);
        packet.set(JAVA_BYTE, 7, (byte) 1);
        for (int i = 0; i < MAGIC.length; i++) {
            packet.set(JAVA_BYTE, 8 + i, MAGIC[i]);
        }
        int sum = checksum(packet, PACKET);
        packet.set(JAVA_BYTE, 2, (byte) (sum >> 8));
        packet.set(JAVA_BYTE, 3, (byte) (sum & 0xFF));
        return packet;

    }

    /** La marca propia dentro de la respuesta: un eco ajeno (de otro proceso) no la lleva. */
    private static boolean own(MemorySegment reply) {

        for (int i = 0; i < MAGIC.length; i++) {
            if (reply.get(JAVA_BYTE, 8 + i) != MAGIC[i]) {
                return false;
            }
        }
        return true;

    }

    /** El checksum ICMP: complemento a uno de las palabras de 16 bits, en orden de red. */
    static int checksum(MemorySegment segment, long bytes) {

        long sum = 0;
        for (long i = 0; i + 1 < bytes; i += 2) {
            sum += ((segment.get(JAVA_BYTE, i) & 0xFF) << 8) | (segment.get(JAVA_BYTE, i + 1) & 0xFF);
        }
        if (bytes % 2 == 1) {
            sum += (segment.get(JAVA_BYTE, bytes - 1) & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);

    }

}
