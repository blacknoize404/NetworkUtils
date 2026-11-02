package io.github.blacknoize404.networkUtils.latency.methods;

public enum PingMethod {

    HTTP_SECURE(new HTTPMethodFactory(HTTPMethodFactory.Protocol.SECURE), "HTTPS"),
    HTTP_UNSECURE(new HTTPMethodFactory(HTTPMethodFactory.Protocol.UNSECURE), "HTTP"),
    NATIVE_ICMP(new NativeICMPMethodFactory(), "ICMP (sistema)"),
    ICMP(new ICMPMethodFactory(), "ICMP (Java)"),
    /** El host se escribe {@code nombre:puerto}. */
    TCP_PORT(new TCPMethodFactory(), "TCP (host:puerto)"),
    DNS(new DNSMethodFactory(), "DNS (resolución)"),
    /** Petición HTTP real; {@link HttpCheckMethodFactory} permite exigir un código y un texto. */
    HTTP_CHECK(new HttpCheckMethodFactory(), "HTTP (comprobar respuesta)");

    private final PingMethodFactory method;
    private final String displayName;

    PingMethod(PingMethodFactory method, String displayName) {
        this.method = method;
        this.displayName = displayName;
    }

    public PingMethodFactory method() {
        return method;
    }

    /**
     * Nombre legible para el usuario; {@link #toString()} lo devuelve, así los
     * controles estándar (ComboBox y similares) lo muestran por defecto.
     */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
