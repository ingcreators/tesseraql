#!/bin/bash
# The container's health check: liveness over bash's /dev/tcp, because the base image ships
# bash and no HTTP client — the curl the check used to call was never there, so every container
# read `unhealthy` from its second minute (docs/deployment-maturity.md, row 1). Kubernetes
# ignores HEALTHCHECK and probes over HTTP itself; this is for docker run, Compose and Kamal.
port="${TESSERAQL_PORT:-8080}"
exec 3<>"/dev/tcp/127.0.0.1/${port}" || exit 1
printf 'GET /_tesseraql/health/live HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r -t 3 status <&3 || exit 1
case "$status" in
  *" 200 "*) exit 0 ;;
  *) exit 1 ;;
esac
