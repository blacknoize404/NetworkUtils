package io.github.blacknoize404.pingUtils.responses;

public class PingSuccessResponse extends PingResponse {

    private final long responseMsecs;

    public PingSuccessResponse(long responseMsecs) {
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
