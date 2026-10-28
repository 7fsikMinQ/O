import org.springframework.boot.gradle.plugin.SpringBootPlugin

// 버전 근거: docs/23_개발착수_버전호환표.md (2026-10-28 Maven Central·BOM 대조)
plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "kr.bojohelp"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get().toInt())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Boot BOM 이 Hibernate 7.4.13 · Flyway 12.4.0 · PostgreSQL 드라이버 42.7.14 · Testcontainers 2.0.5 · JUnit 6 을 고정한다
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // BOM 밖: springdoc 3.2.0 은 Boot 4.1.2 기준으로 빌드됨(POM 부모 확인)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")

    // 암호화 엑셀(정산·정보표) — 기능 플래그 OFF 상태에서도 컴파일되도록 처음부터 포함하지 않고 해당 모듈 작업 시 추가
    // implementation("org.apache.poi:poi-ooxml:6.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 로그·테스트 출력에 개인정보를 남기지 않는다(CLAUDE.md §6)
    testLogging { events("failed", "skipped") }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
