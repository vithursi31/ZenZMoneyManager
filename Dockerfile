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
RUN mvn -q -pl svcs/core -am clean package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN useradd -r -u 1001 appuser
USER appuser

# Copy the built fat jar. The artifactId is zenzmoney-core; the version comes
# from the POM (0.0.1-SNAPSHOT). The wildcard keeps this working across version bumps.
COPY --from=build /app/svcs/core/target/zenzmoney-core-*.jar app.jar

# Spring profile and JVM options are provided at runtime via env vars.
# SPRING_PROFILES_ACTIVE should be "dev" or "prd" in the cloud (they read env-var config).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

# Use the shell form so $JAVA_OPTS expands.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
