# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
ARG BUILD_VERSION=dev
ARG BUILD_REVISION=unknown
LABEL org.opencontainers.image.title="Harvex" \
      org.opencontainers.image.description="Self-hosted crawling and indexing platform" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/Harvex" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}"
RUN addgroup -S harvex && adduser -S -G harvex -u 65532 harvex && mkdir -p /app/data && chown -R harvex:harvex /app
COPY target/harvex-*.jar /app/app.jar
USER 65532:65532
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
