FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew :kosha-app:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S kosha && adduser -S kosha -G kosha
USER kosha

WORKDIR /app
COPY --from=build /workspace/kosha-app/build/libs/kosha-app-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
