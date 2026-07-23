# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jre
WORKDIR /app
ARG BUILD_VERSION=dev
ARG BUILD_REVISION=unknown
LABEL org.opencontainers.image.title="ContextCrate" \
      org.opencontainers.image.description="Self-hosted crawling and indexing platform" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/ContextCrate" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}"
RUN groupadd --system contextcrate && useradd --system --gid contextcrate --uid 65532 contextcrate && mkdir -p /app/data/models /models && chown -R contextcrate:contextcrate /app /models
COPY target/contextcrate-*.jar /app/app.jar
USER 65532:65532
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"
VOLUME ["/app/data", "/models"]
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
