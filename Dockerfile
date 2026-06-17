FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-jammy AS backend-build-base
WORKDIR /code/backend
COPY backend/gradlew backend/gradlew.bat backend/build.gradle.kts backend/settings.gradle.kts ./
COPY backend/gradle ./gradle
# Cache-friendly warmup: downloads Gradle wrapper/distribution and plugin metadata once
RUN ./gradlew --no-daemon help

FROM backend-build-base AS backend-build-jvm
COPY docs/api ../docs/api
COPY backend/src ./src
COPY --from=frontend-build /frontend/dist ./src/main/resources/META-INF/resources
RUN ./gradlew --no-daemon quarkusBuild -Dquarkus.package.type=fast-jar -x test

FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 AS backend-build-native-base
WORKDIR /code/backend
COPY --chown=quarkus:quarkus backend/gradlew backend/gradlew.bat backend/build.gradle.kts backend/settings.gradle.kts ./
COPY --chown=quarkus:quarkus backend/gradle ./gradle
USER quarkus
RUN ./gradlew --no-daemon help

FROM backend-build-native-base AS backend-build-native
COPY --chown=quarkus:quarkus docs/api ../docs/api
COPY --chown=quarkus:quarkus backend/src ./src
COPY --chown=quarkus:quarkus --from=frontend-build /frontend/dist ./src/main/resources/META-INF/resources
RUN ./gradlew --no-daemon quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -x test

FROM eclipse-temurin:21-jre-jammy AS runtime-jvm
ARG VEYQ_VERSION=""
ARG VEYQ_REVISION=""
ENV VEYQ_VERSION="${VEYQ_VERSION}"
ENV VEYQ_REVISION="${VEYQ_REVISION}"
WORKDIR /work/
# curl: container healthcheck only. ffmpeg removed — the operator-blind backend never transcodes (thumbnails/
# previews are client-generated blobs), so no server-side media binary is ever invoked. Create a non-root user
# and the default media dir owned by it: an empty named volume mounted there inherits this 10001 ownership, so
# the unprivileged process can write blobs. (Bind/CIFS media mounts must be chown'd to 10001 by the operator.)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 10001 veyq \
    && useradd -r -u 10001 -g veyq -s /usr/sbin/nologin veyq \
    && mkdir -p /data/veyq-media \
    && chown -R 10001:10001 /work /data/veyq-media
COPY --from=backend-build-jvm --chown=10001:10001 /code/backend/build/quarkus-app /work/quarkus-app
USER 10001
EXPOSE 8080
# MaxRAMPercentage is a percentage of the CONTAINER memory limit (set via compose mem_limit) — without that
# limit the JVM would size the heap off host RAM. ExitOnOutOfMemoryError lets the orchestrator restart a
# wedged JVM instead of it limping on in an undefined state.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:InitialRAMPercentage=40.0", "-XX:+ExitOnOutOfMemoryError", "-Dquarkus.http.host=0.0.0.0", "-jar", "/work/quarkus-app/quarkus-run.jar"]

FROM registry.access.redhat.com/ubi9/ubi-minimal:9.5 AS runtime-native
ARG VEYQ_VERSION=""
ARG VEYQ_REVISION=""
ENV VEYQ_VERSION="${VEYQ_VERSION}"
ENV VEYQ_REVISION="${VEYQ_REVISION}"
WORKDIR /work/
# curl: healthcheck only; ffmpeg removed (see runtime-jvm — no server-side transcode in an operator-blind core).
RUN microdnf install -y curl && microdnf clean all \
    && mkdir -p /data/veyq-media
COPY --from=backend-build-native --chown=10001:10001 /code/backend/build/*-runner /work/application
RUN chmod 0755 /work/application && chown -R 10001:10001 /work /data/veyq-media
USER 10001
EXPOSE 8080
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
