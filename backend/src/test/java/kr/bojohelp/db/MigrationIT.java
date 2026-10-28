package kr.bojohelp.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Flyway V1 이 실제 PostgreSQL 18 에서 적용되고, 돈·법무 관련 기능 플래그가 기본 OFF 인지 확인(Spring 없이 순수 통합 시험). */
@Testcontainers(disabledWithoutDocker = true)
class MigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Test
    void migratesAndSeedsSafeDefaults() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(69);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM feature_flags WHERE enabled AND code IN ('SETTLEMENT_RECORD','BANK_TRANSFER_FILE','WITHHOLDING_EXPORT','PAYOUT_EXECUTION','MINOR_APPLY','REVIEW_DEMO_LOGIN')")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (ResultSet rs = st.executeQuery("SELECT value FROM app_settings WHERE key='star_head_start_minutes'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("1");
            }
        }
    }
}
