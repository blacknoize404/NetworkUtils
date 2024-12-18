package io.github.blacknoize404.pingUtils.methods;

public enum PingMethod {

    HTTP_SECURE(new HTTPMethodFactory(HTTPMethodFactory.Protocol.SECURE)),
    HTTP_UNSECURE(new HTTPMethodFactory(HTTPMethodFactory.Protocol.UNSECURE)),
    NATIVE_ICMP(new NativeICMPMethodFactory()),
    ICMP(new ICMPMethodFactory());

    private final PingMethodFactory method;

    PingMethod(PingMethodFactory method) {
        this.method = method;
    }

    public PingMethodFactory method() {
        return method;
    }
}
