package com.bookflow.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Transactional(isolation = Isolation.READ_COMMITTED)
@Sql("/catalog-fixture.sql")
class BookingWorkflowMySqlIT {
    private static final String PASSWORD = "M2-Integration-Only-2030";

    @DynamicPropertySource
    static void requireTestDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:[?].*)?")) {
            throw new IllegalStateException("Only a dedicated _test database is allowed");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(Instant.parse("2030-01-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void prepareRealLoginUsers() {
        String hash = encoder.encode(PASSWORD);
        jdbc.update("UPDATE app_user SET password_hash = ?, enabled = TRUE WHERE id IN (900001, 900002)", hash);
        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name, role) VALUES (900003, 'it-admin', ?, '测试管理员', 'ADMIN')", hash);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions write(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        JsonNode csrf = body(mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()));
        return mvc.perform(request.header("Idempotency-Key", java.util.UUID.randomUUID().toString()).session(session).header(csrf.get("headerName").asText(), csrf.get("token").asText()));
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        write(session, post("/api/auth/login").param("username", username).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username));
        return session;
    }

    private MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, Object value) throws Exception {
        return request.contentType("application/json").content(json.writeValueAsString(value));
    }

    private long reserve(MockHttpSession session, long slotId) throws Exception {
        return body(write(session, jsonRequest(post("/api/reservations"), Map.of("slotId", slotId)))
                .andExpect(status().isCreated())).path("reservation").path("id").asLong();
    }

    private Map<String, Object> device(String code, String name, String status) {
        return Map.of("code", code, "name", name, "category", "检测", "location", "测试楼 A101", "description", "M2 测试设备", "status", status);
    }

    private Map<String, Object> slot(String start, String end, String status) {
        return Map.of("startAt", start, "endAt", end, "status", status);
    }

    @Test
    void databaseLoginUsesHashAndDoesNotExposeSensitiveFields() throws Exception {
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = 900001", String.class);
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        MockHttpSession session = login("it-user-a");
        JsonNode user = body(mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()));
        assertThat(user.path("id").asLong()).isEqualTo(900001);
        assertThat(user.path("role").asText()).isEqualTo("USER");
        assertThat(user.has("password")).isFalse();
        assertThat(user.has("passwordHash")).isFalse();
    }

    @Test
    void invalidDisabledAndUnknownLoginsAreRejectedWithoutEnumeration() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (String username : new String[]{"it-user-a", "missing-user"}) {
            write(session, post("/api/auth/login").param("username", username).param("password", "incorrect"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = 900002");
        write(session, post("/api/auth/login").param("username", "it-user-b").param("password", PASSWORD))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginAndBookingRequireCsrfAndLogoutInvalidatesAuthentication() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "it-user-a").param("password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        MockHttpSession session = login("it-user-a");
        mvc.perform(jsonRequest(post("/api/reservations"), Map.of("slotId", 900001)).session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        write(session, post("/api/auth/logout")).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAndNormalUsersCannotManageResources() throws Exception {
        mvc.perform(get("/api/admin/reservations")).andExpect(status().isUnauthorized());
        MockHttpSession anonymous = new MockHttpSession();
        write(anonymous, jsonRequest(post("/api/reservations"), Map.of("slotId", 900001))).andExpect(status().isUnauthorized());
        MockHttpSession user = login("it-user-a");
        mvc.perform(get("/api/admin/reservations").session(user)).andExpect(status().isForbidden());
        write(user, jsonRequest(post("/api/admin/devices"), device("IT-DENIED", "Forbidden", "ACTIVE")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE code = 'IT-DENIED'", Integer.class)).isZero();
    }

    @Test
    void createsOneReservationAndRejectsDuplicateEvenForAnotherUser() throws Exception {
        MockHttpSession a = login("it-user-a");
        MockHttpSession b = login("it-user-b");
        long id = reserve(a, 900001);
        assertThat(id).isPositive();
        for (MockHttpSession session : new MockHttpSession[]{a, b}) {
            write(session, jsonRequest(post("/api/reservations"), Map.of("slotId", 900001)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_OCCUPIED"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE slot_id = 900001", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM reservation WHERE id = ?", Long.class, id)).isEqualTo(900001);
    }

    @Test
    void cancelRetainsHistoryAndRebookingCreatesANewRecord() throws Exception {
        MockHttpSession user = login("it-user-a");
        long original = reserve(user, 900001);
        JsonNode cancelled = body(write(user, post("/api/reservations/" + original + "/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservation.status").value("CANCELLED")));
        JsonNode again = body(write(user, post("/api/reservations/" + original + "/cancel")).andExpect(status().isOk()));
        assertThat(again.path("reservation").path("cancelledAt")).isEqualTo(cancelled.path("reservation").path("cancelledAt"));
        long replacement = reserve(user, 900001);
        assertThat(replacement).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE slot_id = 900001", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE active_slot_id = 900001", Integer.class)).isEqualTo(1);
    }

    @Test
    void otherUsersAndAdminCannotCancelSomeoneElsesReservation() throws Exception {
        MockHttpSession a = login("it-user-a");
        long id = reserve(a, 900001);
        for (String username : new String[]{"it-user-b", "it-admin"}) {
            write(login(username), post("/api/reservations/" + id + "/cancel"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id)).isEqualTo("CONFIRMED");
    }

    @Test
    void personalHistoryCannotBeSwitchedBySupplyingAnotherUserId() throws Exception {
        MockHttpSession b = login("it-user-b");
        mvc.perform(get("/api/reservations/mine").param("userId", "900001").session(b))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        write(b, jsonRequest(post("/api/reservations"), Map.of("slotId", 900001, "userId", 900001)))
                .andExpect(status().isBadRequest());
        long id = reserve(b, 900001);
        mvc.perform(get("/api/reservations/mine").session(b)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].reservation.id").value(id))
                .andExpect(jsonPath("$[0].reservation.userId").value(900002));
    }

    @Test
    void startedAndEndedSlotsRejectBothBookingAndCancellation() throws Exception {
        jdbc.update("INSERT INTO equipment_slot (id, device_id, start_at, end_at) VALUES (900010, 900001, '2030-01-01 10:00:00', '2030-01-01 11:00:00'), (900011, 900001, '2029-12-31 09:00:00', '2029-12-31 10:00:00')");
        MockHttpSession user = login("it-user-a");
        for (long slotId : new long[]{900010, 900011}) {
            write(user, jsonRequest(post("/api/reservations"), Map.of("slotId", slotId)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_STARTED"));
            jdbc.update("INSERT INTO reservation (id, user_id, slot_id) VALUES (?, 900001, ?)", slotId, slotId);
            write(user, post("/api/reservations/" + slotId + "/cancel"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_STARTED"));
            assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, slotId)).isEqualTo("CONFIRMED");
        }
    }

    @Test
    void closedSlotsInactiveDevicesAndInvalidSlotIdsAreRejected() throws Exception {
        MockHttpSession user = login("it-user-a");
        jdbc.update("UPDATE equipment_slot SET status = 'CLOSED' WHERE id = 900001");
        write(user, jsonRequest(post("/api/reservations"), Map.of("slotId", 900001)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_CLOSED"));
        jdbc.update("UPDATE device SET status = 'INACTIVE' WHERE id = 900001");
        write(user, jsonRequest(post("/api/reservations"), Map.of("slotId", 900003)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_CLOSED"));
        write(user, jsonRequest(post("/api/reservations"), Map.of("slotId", -1))).andExpect(status().isBadRequest());
        write(user, jsonRequest(post("/api/reservations"), Map.of())).andExpect(status().isBadRequest());
        write(user, jsonRequest(post("/api/reservations"), Map.of("slotId", 999999))).andExpect(status().isNotFound());
    }

    @Test
    void administratorCanCreateEditAndDeleteUnreferencedResources() throws Exception {
        MockHttpSession admin = login("it-admin");
        long deviceId = body(write(admin, jsonRequest(post("/api/admin/devices"), device("IT-NEW", "新增设备", "ACTIVE")))
                .andExpect(status().isCreated())).path("id").asLong();
        write(admin, jsonRequest(put("/api/admin/devices/" + deviceId), device("IT-NEW", "已修改设备", "ACTIVE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("已修改设备"));
        long slotId = body(write(admin, jsonRequest(post("/api/admin/devices/" + deviceId + "/slots"), slot("2035-03-01T09:00:00", "2035-03-01T10:00:00", "OPEN")))
                .andExpect(status().isCreated())).path("id").asLong();
        write(admin, jsonRequest(put("/api/admin/slots/" + slotId), slot("2035-03-01T10:00:00", "2035-03-01T11:00:00", "CLOSED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/api/admin/devices/" + deviceId + "/slots").param("date", "2035-03-01").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].hasHistory").value(false));
        write(admin, delete("/api/admin/devices/" + deviceId)).andExpect(status().isConflict());
        write(admin, delete("/api/admin/slots/" + slotId)).andExpect(status().isNoContent());
        write(admin, delete("/api/admin/devices/" + deviceId)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE id = ?", Integer.class, deviceId)).isZero();
    }

    @Test
    void overlappingSlotsAreRejectedButAdjacentSlotsAreAllowed() throws Exception {
        MockHttpSession admin = login("it-admin");
        write(admin, jsonRequest(post("/api/admin/devices/900001/slots"), slot("2035-01-01T10:00:00", "2035-01-01T11:00:00", "OPEN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));
        write(admin, jsonRequest(post("/api/admin/devices/900001/slots"), slot("2035-01-01T10:30:00", "2035-01-01T11:00:00", "OPEN")))
                .andExpect(status().isCreated());
        write(admin, jsonRequest(put("/api/admin/slots/900001"), slot("2035-01-01T09:00:00", "2035-01-01T10:45:00", "OPEN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));
    }

    @Test
    void reservationsPreventDeviceShutdownSlotClosureAndHistoryRewriting() throws Exception {
        MockHttpSession admin = login("it-admin");
        write(admin, jsonRequest(put("/api/admin/devices/900001"), device("IT-MIC", "不能停用", "INACTIVE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_HAS_RESERVATIONS"));
        write(admin, jsonRequest(put("/api/admin/slots/900002"), slot("2035-01-01T14:00:00", "2035-01-01T15:30:00", "CLOSED")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_OCCUPIED"));
        write(admin, jsonRequest(put("/api/admin/slots/900002"), slot("2035-01-01T14:00:00", "2035-01-01T15:00:00", "OPEN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_HAS_HISTORY"));
        write(admin, delete("/api/admin/slots/900002")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_HAS_HISTORY"));
        // 失败的写事务不能部分修改设备或时段。
        assertThat(jdbc.queryForObject("SELECT status FROM device WHERE id = 900001", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM equipment_slot WHERE id = 900002", String.class)).isEqualTo("OPEN");
    }

    @Test
    void cancelledHistoryStillPreventsDeletionAndTimeChangesButAllowsClosure() throws Exception {
        MockHttpSession user = login("it-user-a");
        long id = reserve(user, 900001);
        write(user, post("/api/reservations/" + id + "/cancel")).andExpect(status().isOk());
        MockHttpSession admin = login("it-admin");
        write(admin, delete("/api/admin/slots/900001")).andExpect(status().isConflict());
        write(admin, jsonRequest(put("/api/admin/slots/900001"), slot("2035-01-01T09:00:00", "2035-01-01T10:00:00", "OPEN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_HAS_HISTORY"));
        write(admin, jsonRequest(put("/api/admin/slots/900001"), slot("2035-01-01T09:00:00", "2035-01-01T10:30:00", "CLOSED")))
                .andExpect(status().isOk());
    }

    @Test
    void administratorSeesAllReservationsButUserOnlySeesTheirOwn() throws Exception {
        MockHttpSession b = login("it-user-b");
        reserve(b, 900001);
        mvc.perform(get("/api/reservations/mine").session(b)).andExpect(jsonPath("$", hasSize(1)));
        MockHttpSession admin = login("it-admin");
        mvc.perform(get("/api/admin/reservations").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].reservation.username", containsInAnyOrder("it-user-a", "it-user-b")));
    }

    @Test
    void invalidAdminInputAndImmutableDeviceCodeAreRejected() throws Exception {
        MockHttpSession admin = login("it-admin");
        write(admin, jsonRequest(post("/api/admin/devices"), device("IT-MIC", "重复编号", "ACTIVE")))
                .andExpect(status().isConflict());
        write(admin, jsonRequest(post("/api/admin/devices"), device("IT-INVALID", "", "ACTIVE"))).andExpect(status().isBadRequest());
        write(admin, jsonRequest(put("/api/admin/devices/900001"), device("IT-CHANGED", "设备", "ACTIVE")))
                .andExpect(status().isBadRequest());
        write(admin, jsonRequest(post("/api/admin/devices/900001/slots"), slot("2035-04-01T10:00:00", "2035-04-01T09:00:00", "OPEN")))
                .andExpect(status().isBadRequest());
        write(admin, jsonRequest(post("/api/admin/devices/900001/slots"), slot("2030-01-01T10:00:00", "2030-01-01T11:00:00", "OPEN")))
                .andExpect(status().isConflict());
    }
}
