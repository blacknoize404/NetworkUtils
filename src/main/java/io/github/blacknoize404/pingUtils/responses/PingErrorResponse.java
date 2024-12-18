package io.github.blacknoize404.pingUtils.responses;

public final class PingErrorResponse extends PingResponse {

    private final String reason;

    public PingErrorResponse(String host, String reason) {
        super(host);
        this.reason = reason;
    }

    @Override
    public String getResponse() {
        return reason;
    }

}
