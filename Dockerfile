# syntax=docker/dockerfile:1.7
# Directory scaffolding only: cgr.dev/chainguard/jre has no shell or package manager, so the
# volume mount points are pre-created and chowned here, then copied into the minimal final image.
FROM cgr.dev/chainguard/wolfi-base:latest AS scaffold
RUN mkdir -p /app/data/models /models

FROM cgr.dev/chainguard/jre:latest
WORKDIR /app
ARG BUILD_VERSION=dev
ARG BUILD_REVISION=unknown
LABEL org.opencontainers.image.title="ContextCrate" \
      org.opencontainers.image.description="Self-hosted crawling and indexing platform" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/ContextCrate" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}"
# UID/GID 65532 is this image's built-in "nonroot" user; no groupadd/useradd needed or possible.
COPY --from=scaffold --chown=65532:65532 /app/data /app/data
COPY --from=scaffold --chown=65532:65532 /models /models
COPY --chown=65532:65532 target/contextcrate-*.jar /app/app.jar
USER 65532:65532
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"
VOLUME ["/app/data", "/models"]
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
