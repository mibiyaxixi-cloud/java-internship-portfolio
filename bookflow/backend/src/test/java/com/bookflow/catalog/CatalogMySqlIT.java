package com.bookflow.catalog;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Transactional
@Sql("/catalog-fixture.sql")
class CatalogMySqlIT {
    @DynamicPropertySource
    static void requireSeparateTestDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:[?].*)?")) {
            throw new IllegalStateException("TEST_DB_URL 必须显式配置且数据库名以 _test 结尾，拒绝使用应用库");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2030-01-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired SqlSessionTemplate sqlSession;

    @Test
    void usesRealMySqlAndFlywayIsRepeatable() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).endsWith("_test");
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void devicesAreReadFromDatabase() throws Exception {
        mvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'IT-MIC')].name", hasItem("集成测试显微镜")))
                .andExpect(jsonPath("$[?(@.code == 'IT-MIC')].demo", hasItem(false)));
        jdbc.update("UPDATE device SET name = '数据库已更新' WHERE id = 900001");
        // MockMvc 的两次调用共享测试事务；生产 HTTP 请求有独立 SqlSession。
        // JDBC 更新不会清除 MyBatis 一级缓存，此处模拟下一次请求的会话边界。
        sqlSession.clearCache();
        mvc.perform(get("/api/devices"))
                .andExpect(jsonPath("$[?(@.code == 'IT-MIC')].name", hasItem("数据库已更新")));
    }

    @Test
    void slotsAreFilteredSortedAndOccupancyComesFromDatabase() throws Exception {
        mvc.perform(get("/api/devices/900001/slots").param("date", "2035-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.slots", hasSize(2)))
                .andExpect(jsonPath("$.slots[0].startAt").value("2035-01-01T09:00:00"))
                .andExpect(jsonPath("$.slots[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.slots[1].availability").value("OCCUPIED"));
    }

    @Test
    void emptyDayAndMissingDeviceHaveDifferentResults() throws Exception {
        mvc.perform(get("/api/devices/900002/slots").param("date", "2035-01-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slots", hasSize(0)));
        mvc.perform(get("/api/devices/999999/slots"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidInputsAre400() throws Exception {
        for (String value : new String[]{"not-a-date", "2035-02-30", "9999-01-01"}) {
            mvc.perform(get("/api/devices/900001/slots").param("date", value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        mvc.perform(get("/api/devices/-1/slots")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/devices/abc/slots")).andExpect(status().isBadRequest());
    }

    @Test
    void anonymousCannotAccessWriteOrAdminEndpoints() throws Exception {
        mvc.perform(post("/api/devices").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reservations"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void activeReservationIsUniqueButMultipleCancelledHistoriesAreAllowed() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO reservation (user_id, slot_id) VALUES (900002, 900002)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE reservation SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP WHERE slot_id = 900002");
        jdbc.update("INSERT INTO reservation (user_id, slot_id, status, cancelled_at) VALUES (900001, 900002, 'CANCELLED', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO reservation (user_id, slot_id) VALUES (900002, 900002)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE slot_id = 900002", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE active_slot_id = 900002", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseRejectsInvalidTimesAndDeletionOfReferencedRows() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO equipment_slot (device_id, start_at, end_at) VALUES (900001, '2035-01-01 10:00:00', '2035-01-01 09:00:00')"))
                .hasMessageContaining("ck_slot_time")
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM equipment_slot WHERE id = 900002"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM device WHERE id = 900001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void demoSeedIsExplicitAndCanBeRepeatedWithoutDuplicates() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE is_demo = TRUE", Integer.class)).isZero();
        ResourceDatabasePopulator seed = new ResourceDatabasePopulator(new ClassPathResource("db/demo/R__demo_catalog.sql"));
        seed.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE is_demo = TRUE", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_slot WHERE is_demo = TRUE", Integer.class)).isEqualTo(42);
        seed.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE is_demo = TRUE", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_slot WHERE is_demo = TRUE", Integer.class)).isEqualTo(42);
    }
}
