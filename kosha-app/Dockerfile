# syntax=docker/dockerfile:1.7
#
# BuildKit is required for the cache mounts below — Docker Desktop has it
# on by default; on a bare daemon set DOCKER_BUILDKIT=1 (and Compose v2
# uses BuildKit out of the box).

FROM eclipse-temurin:24-jdk-alpine AS build

WORKDIR /workspace
COPY . .

# Two cache mounts persist across rebuilds:
#   - /root/.gradle/caches    → resolved dependencies (~200MB)
#   - /root/.gradle/wrapper   → the Gradle distribution itself
# Without these, every container rebuild re-downloads the world even when
# only a single .kt file changed. With them, only the changed inputs are
# re-compiled and the network is touched only on dependency changes.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew :kosha-app:bootJar --no-daemon -x test

FROM eclipse-temurin:24-jre-alpine

RUN addgroup -S kosha && adduser -S kosha -G kosha
USER kosha

WORKDIR /app
COPY --from=build /workspace/kosha-app/build/libs/kosha-app-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
