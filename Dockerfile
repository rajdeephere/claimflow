# syntax=docker/dockerfile:1.7
#
# One Dockerfile for every service:  docker build --build-arg MODULE=claim-service -t claimflow/claim-service .
#
# Stage 1 builds the module (and the modules it depends on) with Maven.
# Stage 2 runs it on a small JRE image, as a non-root user, from Spring Boot's layered jar:
# dependencies change rarely and sit in lower layers, so a code change only rebuilds the small
# "application" layer, and pulls/pushes are fast.

ARG MODULE

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /src

# Poms first: this layer (and the dependency download) is reused until a pom changes.
COPY pom.xml .
COPY common/pom.xml common/
COPY api-gateway/pom.xml api-gateway/
COPY policy-service/pom.xml policy-service/
COPY claim-service/pom.xml claim-service/
COPY validation-service/pom.xml validation-service/
COPY payment-service/pom.xml payment-service/

COPY common/src common/src
COPY ${MODULE}/src ${MODULE}/src

# Tests run in CI before images are built; the BuildKit cache mount keeps ~/.m2 between builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am package -DskipTests \
 && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

# Split the fat jar into layers (dependencies / spring-boot-loader / snapshot-dependencies / application).
RUN java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-alpine
ARG MODULE
LABEL org.opencontainers.image.title="claimflow-${MODULE}" \
      org.opencontainers.image.source="https://github.com/<you>/claimflow"

RUN addgroup -S claimflow && adduser -S claimflow -G claimflow
WORKDIR /app

# Least-changing layers first.
COPY --from=build --chown=claimflow:claimflow /extracted/dependencies/ ./
COPY --from=build --chown=claimflow:claimflow /extracted/spring-boot-loader/ ./
COPY --from=build --chown=claimflow:claimflow /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=claimflow:claimflow /extracted/application/ ./

USER claimflow

# Container-aware memory: size the heap from the container limit, not the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
