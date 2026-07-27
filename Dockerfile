# syntax=docker/dockerfile:1
#
# Multi-stage build for the ZenZ Money Manager backend (svcs/core).
# Stage 1 builds the fat jar with Maven + JDK 17; stage 2 runs it on a slim JRE.

# ---- Stage 1: build ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Install Maven (the repo has no mvnw wrapper).
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

# Copy the full multi-module project. (A .dockerignore keeps target/ and other
# noise out of the build context.)
COPY pom.xml ./
COPY svcs ./svcs

# Build only the runnable module (svcs/core) and everything it depends on (-am),
# skipping tests — tests need a live Postgres/Redis which isn't present at build time.
# -P prd packages src/main/profile/prd/resources (application-prd.properties), which
# the runtime activates via SPRING_PROFILES_ACTIVE=prd. Without it, Maven's default
# 'loc' profile is used and the prd datasource config never lands in the jar.
RUN mvn -q -pl svcs/core -am clean package -DskipTests -P prd

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Run as a non-root user. Create the log dir the prd/dev logback config writes to
# (${DEPLOYMENT_DIRECTORY:-.}/logs → /app/logs) and hand it to that user, so the
# rolling file appenders can create debug.log / error.log at startup.
RUN useradd -r -u 1001 appuser \
    && mkdir -p /app/logs \
    && chown -R appuser:appuser /app
USER appuser

# Copy the built fat jar. The artifactId is zenzmoney-core; the version comes
# from the POM (0.0.1-SNAPSHOT). The wildcard keeps this working across version bumps.
COPY --from=build --chown=appuser:appuser /app/svcs/core/target/zenzmoney-core-*.jar app.jar

# Spring profile and JVM options are provided at runtime via env vars.
# SPRING_PROFILES_ACTIVE should be "dev" or "prd" in the cloud (they read env-var config).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

# Use the shell form so $JAVA_OPTS expands.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
