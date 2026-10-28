package kr.bojohelp.feature;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 공개 API(/features, /app/config)와 보안 기본 거부를 전체 컨텍스트에서 확인. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FeatureGateTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Autowired
    MockMvc mvc;

    @Test
    void featuresArePublicAndMoneyFlagsAreOff() throws Exception {
        mvc.perform(get("/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYOUT_EXECUTION").value(false))
                .andExpect(jsonPath("$.BANK_TRANSFER_FILE").value(false));
    }

    @Test
    void oldAppIsToldToUpdate() throws Exception {
        mvc.perform(get("/app/config").param("platform", "android").param("version", "0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateRequired").value(true));
        mvc.perform(get("/app/config").param("platform", "android").param("version", "1.0.0"))
                .andExpect(jsonPath("$.updateRequired").value(false));
    }

    @Test
    void badInputIs400() throws Exception {
        mvc.perform(get("/app/config").param("platform", "tv").param("version", "1.0.0")).andExpect(status().isBadRequest());
        mvc.perform(get("/app/config").param("platform", "android").param("version", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void everythingElseRequiresLogin() throws Exception {
        mvc.perform(get("/company/payout-batches")).andExpect(status().isUnauthorized());
    }
}
