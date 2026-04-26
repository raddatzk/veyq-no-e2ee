FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 AS backend-build-base
WORKDIR /code/backend
COPY --chown=quarkus:quarkus backend/gradlew backend/gradlew.bat backend/build.gradle.kts backend/settings.gradle.kts ./
COPY --chown=quarkus:quarkus backend/gradle ./gradle
USER quarkus
# Cache-friendly warmup: downloads Gradle wrapper/distribution and plugin metadata once
RUN ./gradlew --no-daemon help

FROM backend-build-base AS backend-build-jvm
COPY --chown=quarkus:quarkus backend/src ./src
COPY --chown=quarkus:quarkus --from=frontend-build /frontend/dist ./src/main/resources/META-INF/resources
RUN ./gradlew --no-daemon quarkusBuild -Dquarkus.package.type=fast-jar -x test

FROM backend-build-base AS backend-build-native
COPY --chown=quarkus:quarkus backend/src ./src
COPY --chown=quarkus:quarkus --from=frontend-build /frontend/dist ./src/main/resources/META-INF/resources
RUN ./gradlew --no-daemon quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -x test

FROM eclipse-temurin:21-jre-jammy AS runtime-jvm
WORKDIR /work/
COPY --from=backend-build-jvm /code/backend/build/quarkus-app /work/quarkus-app
EXPOSE 8080
ENTRYPOINT ["java", "-Dquarkus.http.host=0.0.0.0", "-jar", "/work/quarkus-app/quarkus-run.jar"]

FROM registry.access.redhat.com/ubi9/ubi-minimal:9.5 AS runtime-native
WORKDIR /work/
COPY --from=backend-build-native /code/backend/build/*-runner /work/application
RUN chmod 775 /work /work/application
EXPOSE 8080
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
