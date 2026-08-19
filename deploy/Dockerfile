# TesseraQL runtime image with one (or more) apps baked in (docs/deployment.md, pattern A).
#
#   docker build -f deploy/Dockerfile --build-arg APP_HOME=examples/user-admin-app -t myapp .
#
# APP_HOME is the app directory (config/, web/, db/, ...) baked into the image, as the one
# member of the stack directory /stack the container hosts (docs/hosting.md): the application
# answers at /<its name>/, and the gateway answers the origin's /_tesseraql/health itself. To
# run several applications, bake them side by side under /stack or mount an install root.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY . .
# install (not package): the second invocation resolves the reactor's own modules from the
# container-local repository, and package alone leaves them unresolvable, so
# copy-dependencies fails on the io.tesseraql:* coordinates. Dockerfile.demo learned this
# already; this image did not, and nothing in CI builds it, so it went unnoticed.
RUN ./mvnw -ntp -DskipTests -pl tesseraql-host -am install \
    && ./mvnw -ntp -DskipTests -pl tesseraql-host dependency:copy-dependencies \
         -DincludeScope=runtime -DoutputDirectory=target/dependency

# The deployment distribution, not the developer CLI (docs/runtime-footprint.md decision 1):
# tesseraql-host's dependency set carries the runtime and the operator verbs, and none of the
# workshop, the embedded-database supervisor, or the artifact-resolver stack.
FROM eclipse-temurin:25-jre
RUN useradd --system --home /opt/tesseraql tesseraql
WORKDIR /opt/tesseraql
COPY --from=build /build/tesseraql-host/target/tesseraql-host-*.jar lib/
COPY --from=build /build/tesseraql-host/target/dependency/*.jar lib/

ARG APP_HOME
COPY ${APP_HOME} /stack/app

# The work directory holds spools, drafts and fetched apps; mount a volume over it so
# generated exports and downloaded .tqlapp packages survive container replacement.
RUN mkdir -p /stack/app/work && chown -R tesseraql /stack
VOLUME /stack/app/work

# A class-data-sharing archive, trained here on the baked app's own routes so the classpath it
# records is the one the container runs (docs/jvm-baseline.md decision 4): time-to-ready -25%,
# resident memory -20%. Built once here rather than created at run time, because a container
# filesystem is often read-only and every replica would otherwise pay for its own. If the
# archive stops matching, the JVM warns and runs without it.
# The training run carries the same JVM flags as the ENTRYPOINT below. An archive records the
# object-header layout it was built with, and the JVM refuses one whose UseCompactObjectHeaders
# setting differs from the running JVM's — so training without the flag would produce an archive
# that is rejected, loudly, on every start.
#
# A training run that cannot read the baked app must not fail the image build: a missing archive
# is silent at run time, so the container simply starts without one.
RUN java -XX:+UseCompactObjectHeaders \
      -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=/opt/tesseraql/lib/cds.jsa \
      -cp 'lib/*' io.tesseraql.cli.TesseraqlHostCli routes --app /stack/app > /dev/null 2>&1 \
      && chown tesseraql /opt/tesseraql/lib/cds.jsa \
    || echo 'CDS training run failed; this image starts without a shared archive'

USER tesseraql
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
  CMD curl -fsS http://localhost:8080/_tesseraql/health/live || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseCompactObjectHeaders", \
  "--sun-misc-unsafe-memory-access=allow", \
  "-XX:SharedArchiveFile=/opt/tesseraql/lib/cds.jsa", \
  "-cp", "lib/*", "io.tesseraql.cli.TesseraqlHostCli"]
CMD ["host", "--stack", "/stack", "--port", "8080"]
