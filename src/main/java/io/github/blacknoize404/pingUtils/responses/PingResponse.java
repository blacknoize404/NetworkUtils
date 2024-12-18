package io.github.blacknoize404.pingUtils.responses;

public abstract class PingResponse {

    protected final String host;

    protected PingResponse(String host) {
        this.host = host;
    }

    public String getHost() {
        return host;
    }

    public abstract String getResponse();

    @Override
    public String toString() {
        return getResponse();
    }

}
