package io.github.blacknoize404.networkUtils.latency.responses;

import java.time.Instant;

public abstract class PingResponse {

    protected final String host;

    private final Instant timestamp = Instant.now();

    protected PingResponse(String host) {
        this.host = host;
    }

    public String getHost() {
        return host;
    }

    /**
     * The instant at which this response was produced, useful for replaying
     * the service's history into time-based views.
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    public abstract String getResponse();

    @Override
    public String toString() {
        return getResponse();
    }

}
