package io.github.blacknoize404.pingUtils.responses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HostActivityServiceMessageResponseTest {

    PingMessageResponse pingSuccessResponse;

    @BeforeEach
    void setUp() {

        pingSuccessResponse = new PingMessageResponse("ahora.cu", "Texto");

    }

    @Test
    void getResponse() {

        assert pingSuccessResponse.getResponse().equals("Texto");

    }

    @Test
    void testToString() {

        assert pingSuccessResponse.toString().equals("Texto");

    }
}