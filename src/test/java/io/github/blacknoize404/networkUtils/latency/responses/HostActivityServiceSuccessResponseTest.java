package io.github.blacknoize404.networkUtils.latency.responses;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostActivityServiceSuccessResponseTest {

    PingSuccessResponse pingSuccessResponse;

    @BeforeEach
    void setUp() {

        pingSuccessResponse = new PingSuccessResponse("ahora.cu", 20);

    }

    @Test
    void getResponseMsecs() {

        assert pingSuccessResponse.getResponseMsecs() == 20L;

    }

    @Test
    void getResponse() {

        assert pingSuccessResponse.getResponse().equals("20");

    }

    @Test
    void testToString() {

        assert pingSuccessResponse.toString().equals("20 ms");

    }
}