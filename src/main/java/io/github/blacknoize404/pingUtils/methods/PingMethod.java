package io.github.blacknoize404.pingUtils.methods;

import io.github.blacknoize404.pingUtils.responses.PingResponse;

import java.io.IOException;
import java.util.function.Consumer;

public interface PingMethod {

    void ping(String host, int timeoutMillis, Consumer<PingResponse> callback);

}
