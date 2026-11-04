package com.example.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.rate-limit.login-per-minute=3")
class RateLimitHttpTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void fourthLoginAttemptFromTheSameIpIsBlocked() throws Exception {
        String ip = "10.1." + ThreadLocalRandom.current().nextInt(1, 255) + "." + ThreadLocalRandom.current().nextInt(1, 255);

        for (int i = 0; i < 3; i++) {
            attempt(ip).andExpect(status().isUnauthorized());          
        }
        attempt(ip).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        attempt("10.2.0.1").andExpect(status().isUnauthorized());     
    }

    private ResultActions attempt(String ip) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"));
    }
}