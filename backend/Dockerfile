# =====================================================================================
#  Cab Eye Backend - Production Dockerfile
#  Target: Eclipse Temurin JRE 17, Non-root User, Container-aware JVM Memory
# =====================================================================================

FROM eclipse-temurin:17-jre-jammy AS runner
WORKDIR /app

# Install curl for container healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Run as non-root user
RUN groupadd -g 10001 cabeye && \
    useradd -u 10001 -g cabeye -s /bin/sh -m cabeye && \
    mkdir -p /app/data && \
    chown -R cabeye:cabeye /app

# Copy executable jar built by Gradle
COPY --chown=cabeye:cabeye build/libs/cabeye-backend-0.1.0-SNAPSHOT.jar /app/app.jar

USER cabeye

EXPOSE 8080

# Container-aware JVM options: cgroup memory limit, fast secure entropy
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
