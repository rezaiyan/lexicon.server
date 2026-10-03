# Multi-stage build for optimized Docker image

# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Set Gradle user home to avoid buildkit processing /home/gradle/.gradle
ENV GRADLE_USER_HOME=/app/.gradle

# Copy Gradle files
# The wrapper pins the same Gradle version as local and CI builds
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
# Strip Mac-local JDK path — not valid inside the container
RUN sed -i '/org.gradle.java.home/d' gradle.properties
COPY gradle gradle

# Download dependencies (cached layer) — ignore failure due to missing sources
RUN ./gradlew build --no-daemon || true

# Copy source code
COPY src src

# Build the boot jar, then split it into layers ordered from least to most frequently changed,
# so a code-only deploy rebuilds and ships just the small application layer
RUN ./gradlew bootJar --no-daemon \
 && java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination extracted

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring

# Create mount points for JWT RSA keys and avatar uploads (mounted as volumes)
RUN mkdir -p /app/keys /var/www/uploads/avatars \
 && chown -R spring:spring /app /var/www/uploads

COPY --from=builder --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /app/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /app/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /app/extracted/application/ ./

# Firebase service account is provided via a secure path or secret at runtime

# Switch to non-root user
USER spring:spring

# Expose default port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD sh -c 'PORT=${PORT:-8080}; wget --no-verbose --tries=1 --spider "http://localhost:${PORT}/api/v1/health" || exit 1'

# Heap is sized from the container memory limit (mem_limit in docker-compose.yml). On OOM the JVM
# exits instead of limping on half-broken, and the restart policy brings up a fresh one.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
