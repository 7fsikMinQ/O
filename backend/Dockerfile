# 운영 이미지: 빌드(JDK 25) → 실행(JRE 25, 비루트)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app && mkdir -p /data/files && chown app /data/files
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENV APP_STORAGE_LOCAL_DIR=/data/files
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -Duser.timezone=UTC"
ENTRYPOINT ["java","-jar","/app/app.jar"]
