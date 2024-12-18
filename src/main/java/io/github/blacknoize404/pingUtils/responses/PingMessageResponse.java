package io.github.blacknoize404.pingUtils.responses;

public final class PingMessageResponse extends PingResponse {

    private final String message;

    public PingMessageResponse(String host, String message) {
        super(host);
        this.message = message;
    }

    @Override
    public String getResponse() {
        return message;
    }

}
