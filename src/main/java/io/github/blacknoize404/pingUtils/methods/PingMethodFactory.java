package io.github.blacknoize404.pingUtils.methods;

import io.github.blacknoize404.pingUtils.responses.PingResponse;

import java.util.function.Consumer;

public interface PingMethodFactory {

    void ping(String host, int timeoutMillis, Consumer<PingResponse> callback);

}
