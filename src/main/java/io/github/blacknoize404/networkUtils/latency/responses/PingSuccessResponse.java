package io.github.blacknoize404.networkUtils.latency.responses;

public class PingSuccessResponse extends PingResponse {

    private final long responseMsecs;

    public PingSuccessResponse(String host, long responseMsecs) {
        super(host);
        this.responseMsecs = responseMsecs;
    }

    public long getResponseMsecs() {
        return responseMsecs;
    }

    @Override
    public String getResponse() {
        return String.valueOf(responseMsecs);
    }

    @Override
    public String toString() {
        return super.toString() + " ms";
    }
}
