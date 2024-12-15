package io.github.blacknoize404.pingUtils.responses;

public abstract class PingResponse {

    public abstract String getResponse();

    @Override
    public String toString() {
        return getResponse();
    }

}
