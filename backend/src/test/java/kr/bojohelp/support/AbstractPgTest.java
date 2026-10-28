package kr.bojohelp.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 통합 시험 공통: Docker 가 있으면 postgres:18 컨테이너를 쓰고, 없으면 환경변수 TEST_DB_URL(+TEST_DB_USER/TEST_DB_PASSWORD)로 지정한 빈 DB 를 쓴다.
 * 둘 다 없으면 시험을 건너뛴다.
 */
public abstract class AbstractPgTest {

    private static final String ENV_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer container;

    @BeforeAll
    static void startDatabase() {
        if (ENV_URL != null) return;
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 도 TEST_DB_URL 도 없음");
        synchronized (AbstractPgTest.class) {
            if (container == null) {
                container = new PostgreSQLContainer("postgres:18");
                container.start();
            }
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (ENV_URL != null) {
            registry.add("spring.datasource.url", () -> ENV_URL);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "postgres"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        } else if (container != null) {
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }
}
