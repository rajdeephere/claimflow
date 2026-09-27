# ADR-0028: One parameterised, layered, non-root Dockerfile for all services

- **Status:** Accepted
- **Date:** 2026-09-27
- **Phase:** 8

## Context

Five Spring Boot services share one Maven build (they all depend on the parent POM, and four on
`common`). Each needs an image that is small, fast to rebuild, and safe to run.

## Decision

A single root `Dockerfile` with `ARG MODULE`:

1. **Build stage** (`maven:3.9-eclipse-temurin-17`): copy all POMs first, then `common` and the
   module's sources; `mvn -pl $MODULE -am package -DskipTests` with a BuildKit cache mount for `~/.m2`.
   Tests don't run here: CI runs them before building images.
2. Extract Spring Boot's **layered jar** (`-Djarmode=tools extract --layers --launcher`).
3. **Runtime stage** (`eclipse-temurin:17-jre-alpine`): copy the layers least-changing first
   (dependencies → loader → snapshot deps → application), run as the **non-root** `claimflow` user,
   `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`, `JarLauncher` entrypoint.

Configuration comes from environment variables (`DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `POLICY_SERVICE_URL`,
`PORT`, …) that already exist in every `application.yml`, so the same image runs anywhere.

## Consequences

- ✅ A code change touches only the ~200 kB application layer; rebuilds took 2–15 s after the first.
- ✅ No JDK, Maven or sources in runtime images; no root process.
- ✅ One file to maintain for five services.
- ⚠️ Each image copies all POMs, so a POM change in one module invalidates the dependency layer for all (acceptable).
- ⚠️ ~220–260 MB per image (mostly the JRE). A `jlink` custom runtime or distroless base could shrink it.

## Alternatives considered

- **Spring Boot buildpacks (`spring-boot:build-image`):** good defaults, no Dockerfile; less transparent
  and slower first build. A strong option for teams that don't want to own Dockerfiles.
- **Jib:** daemonless, layered, fast; adds a plugin and hides the image definition.
- **Copying the fat jar as one layer:** simplest, but every code change re-ships all dependencies.
- **GraalVM native images:** fast start, small memory; long builds and reflection configuration for Spring/Kafka/Hibernate.
