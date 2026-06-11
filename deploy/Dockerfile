# TesseraQL runtime image with one (or more) apps baked in (docs/deployment.md, pattern A).
#
#   docker build -f deploy/Dockerfile --build-arg APP_HOME=examples/user-admin-app -t myapp .
#
# APP_HOME is the app directory (config/, web/, db/, ...) baked into the image. For the
# .tqlapp distribution patterns (B / multi-server), bake no app and mount or fetch instead
# (tesseraql.apps.<name>.url + sha256 fetches hash-pinned packages at boot).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN ./mvnw -ntp -DskipTests -pl tesseraql-cli -am package \
    && ./mvnw -ntp -DskipTests -pl tesseraql-cli dependency:copy-dependencies \
         -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:25-jre
RUN useradd --system --home /opt/tesseraql tesseraql
WORKDIR /opt/tesseraql
COPY --from=build /build/tesseraql-cli/target/tesseraql-cli-*.jar lib/
COPY --from=build /build/tesseraql-cli/target/dependency/*.jar lib/

ARG APP_HOME
COPY ${APP_HOME} /app

# The work directory holds spools, drafts and fetched apps; mount a volume over it so
# generated exports and downloaded .tqlapp packages survive container replacement.
RUN mkdir -p /app/work && chown -R tesseraql /app
VOLUME /app/work

USER tesseraql
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
  CMD curl -fsS http://localhost:8080/_tesseraql/health || exit 1

ENTRYPOINT ["java", "-cp", "lib/*", "io.tesseraql.cli.TesseraqlCli"]
CMD ["serve", "--app", "/app"]
