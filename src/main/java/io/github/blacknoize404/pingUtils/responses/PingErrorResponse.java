package io.github.blacknoize404.pingUtils.responses;

public class PingErrorResponse extends PingResponse {

    private final String reason;

    public PingErrorResponse(String reason) {
        this.reason = reason;
    }

    @Override
    public String getResponse() {
        return reason;
    }

}
