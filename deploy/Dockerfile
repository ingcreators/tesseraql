# Your application's image (docs/deployment.md, "Shipping apps"): derived from the official
# runtime image, with the package you built unpacked under /stack — one directory per
# application, named as the application declares itself. No source build and no resolver: a
# package carries the modules it declared (docs/hosting.md, "Modules are resolved before the
# host starts"), which is what a copied source tree could not, and why an application that
# declared one used to be refused at start.
#
#   tesseraql package --app . --out build/orders.tqlapp
#   unzip -q build/orders.tqlapp -d build/stack/orders
#   docker build -f deploy/Dockerfile \
#     --build-arg BASE=ghcr.io/ingcreators/tesseraql-host:0.19.0 \
#     --build-arg APP_DIR=build/stack/orders --build-arg APP_NAME=orders -t my-org/orders .
#
# To run several applications, COPY each package beside the first under /stack. BASE pins the
# framework version; the repository's own CI builds this file against the image it just built
# (BASE=tesseraql-host:local) with a packaged gallery application, and stops the container.
ARG BASE=ghcr.io/ingcreators/tesseraql-host:latest
FROM ${BASE}

# The unpacked package, and the name the application declares as tesseraql.app.name — the
# directory the host serves it from and the address it answers at (/<name>/).
ARG APP_DIR
ARG APP_NAME
COPY --chown=tesseraql ${APP_DIR} /stack/${APP_NAME}

# The work directory holds spools and the upload staging area; mount a volume over it so
# generated exports survive container replacement.
RUN mkdir -p /stack/${APP_NAME}/work
VOLUME /stack/${APP_NAME}/work

# A class-data-sharing archive trained on this application's routes (docs/jvm-baseline.md
# decision 4): time-to-ready -25%, resident memory -20%. Optional: a training run that cannot
# read the application must not fail the image build — the image simply starts without one, and
# the same flags on the base's ENTRYPOINT write one at the first exit where lib/ is writable.
RUN java -XX:+UseCompactObjectHeaders \
      -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=/opt/tesseraql/lib/cds.jsa \
      -cp 'lib/*' io.tesseraql.cli.TesseraqlHostCli routes --app /stack/${APP_NAME} > /dev/null 2>&1 \
    || echo 'CDS training run failed; this image starts without a shared archive'
