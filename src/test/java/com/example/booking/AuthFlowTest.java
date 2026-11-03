package com.example.booking;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void fullFlow() throws Exception {
        mvc.perform(get("/api/resources")).andExpect(status().isOk());
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        String admin = login("admin@example.com", "admin12345");
        long resourceId = createAndGetId("/api/resources", admin, "{\"name\":\"Court 1\"}");
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        long slotId = createAndGetId("/api/resources/" + resourceId + "/slots", admin,
                "{\"startTime\":\"" + start + "\",\"endTime\":\"" + start.plus(1, ChronoUnit.HOURS) + "\"}");

        String alice = registerAndLogin();
        String bob = registerAndLogin();

        mvc.perform(post("/api/resources").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden());

        String bookBody = "{\"slotId\":" + slotId + "}";
        long bookingId = createAndGetId("/api/bookings", alice, bookBody);
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON).content(bookBody))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/bookings/mine").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));

        createAndGetId("/api/bookings", bob, bookBody);
    }


    private String registerAndLogin() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.dev";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"name\":\"Test User\"}"))
                .andExpect(status().isCreated());
        return login(email, "password123");
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private long createAndGetId(String url, String token, String json) throws Exception {
        String body = mvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
