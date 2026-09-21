#!/usr/bin/env bash
# The M10 proof on a two-replica kind cluster (docs/deployment-maturity.md decision 10): the
# four sentences of the milestone and the stop, each a phase the `two-node` job of
# kubernetes.yml runs as its own step, and each rehearsable on a developer's machine with the
# same binaries the runner has.
#
#   proof.sh cluster    the kind cluster, the database, the images loaded
#   proof.sh install    helm install with two replicas on two nodes, the administrator seeded
#   proof.sh rolling    zero non-200 through a rolling restart under `tesseraql bench` (c=8)
#   proof.sh firings    one execution per fire time of the fixed-delay job, from both replicas
#   proof.sh sessions   a session minted on pod A reads a browser route on pod B
#   proof.sh alerts     one TQL-OPS-9006 row across two pods after a channel dead-letters
#   proof.sh stop       a deleted pod finishes its slow request and exits 143 inside the grace
#   proof.sh logs       what the cluster says, for a red step
#   proof.sh teardown   the cluster gone
#
# Inputs, all with defaults for the runner: KIND, KUBECTL, HELM (the binaries), TQL_JAR (the
# developer CLI's fat jar), APP_DIR (the assembled probe application), IMAGE (the probe
# image), STACK_URL (the origin through the NodePort), WORK (where the phases leave their
# files). A rehearsal from inside a container reaches the control-plane node by its address on
# the kind network and passes it as STACK_URL.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
KIND="${KIND:-kind}"
KUBECTL="${KUBECTL:-kubectl}"
HELM="${HELM:-helm}"
TQL_JAR="${TQL_JAR:-$(ls "$REPO"/tesseraql-cli/target/tesseraql-cli-*-all.jar 2>/dev/null | head -1)}"
APP_DIR="${APP_DIR:-$REPO/stack-build/probe}"
IMAGE="${IMAGE:-tesseraql-probe:local}"
STACK_URL="${STACK_URL:-http://localhost:30080}"
WORK="${WORK:-$REPO/stack-build/proof}"
CLUSTER=two-node
RELEASE=tesseraql
SELECTOR="app.kubernetes.io/instance=$RELEASE"
MEMBER="$STACK_URL/user-admin"
# The drain bound the proof's values declare, and the grace the chart derives from it.
BOUND=$(sed -n 's/^shutdownTimeoutSeconds: *\([0-9]*\).*/\1/p' "$HERE/values.yaml")
GRACE=$((BOUND + 15))

mkdir -p "$WORK"
# Port-forwards, watches and the load run in the background; none outlives the phase.
trap 'kill $(jobs -p) 2> /dev/null || true' EXIT

fail() {
  echo "proof: $*" >&2
  exit 1
}

tql() {
  java -jar "$TQL_JAR" "$@"
}

psql_count() {
  "$KUBECTL" exec deploy/postgres -- psql -U user_admin -d user_admin -At -c "$1"
}

pods() {
  "$KUBECTL" get pods -l "$SELECTOR" -o json | jq -r '.items[]
    | select(.metadata.deletionTimestamp == null and .status.phase == "Running")
    | .metadata.name'
}

wait_for_port() {
  for _ in $(seq 1 30); do
    if curl -sf "http://localhost:$1/_tesseraql/health/live" > /dev/null; then
      return 0
    fi
    sleep 1
  done
  fail "nothing answered on localhost:$1"
}

phase_cluster() {
  "$KIND" version
  "$KUBECTL" version --client
  "$HELM" version --short
  # A cluster of the name that already exists is reused: a rehearsal's, between two runs.
  "$KIND" get clusters 2>/dev/null | grep -qx "$CLUSTER" \
    || "$KIND" create cluster --config "$HERE/kind.yaml" --wait 120s
  "$KUBECTL" get nodes -o wide
  docker pull -q postgres:16-alpine
  "$KIND" load docker-image postgres:16-alpine "$IMAGE" --name "$CLUSTER"
  "$KUBECTL" apply -f "$HERE/postgres.yaml"
  "$KUBECTL" rollout status deployment/postgres --timeout=180s
}

phase_install() {
  "$HELM" install "$RELEASE" "$REPO/deploy/helm/tesseraql" -f "$HERE/values.yaml" \
    --wait --timeout 5m
  "$KUBECTL" rollout status "deployment/$RELEASE" --timeout=300s
  "$KUBECTL" get pods -o wide
  # Two replicas, on two different nodes: the chart's anti-affinity is preferred, and here it
  # has two workers to prefer between.
  local nodes
  nodes=$("$KUBECTL" get pods -l "$SELECTOR" -o jsonpath='{.items[*].spec.nodeName}' \
    | tr ' ' '\n' | sort -u | wc -l)
  [ "$nodes" = "2" ] || fail "the two replicas share a node"
  [ "$(pods | wc -l)" = "2" ] || fail "expected two running replicas"
  curl -sf "$STACK_URL/_tesseraql/health/ready" | grep -q '"UP"' \
    || fail "the origin did not answer ready through the NodePort"
  curl -sf "$MEMBER/_tesseraql/health/ready" > /dev/null \
    || fail "the member did not answer ready through the NodePort"
  # The administrator, after the host migrated the framework schema: the managed IAM schema is
  # applied idempotently and the account seeded, from a pod, with the password in a Secret.
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$WORK/admin.pw"
  "$KUBECTL" create secret generic proof-admin --from-file=password="$WORK/admin.pw"
  "$KUBECTL" apply -f "$HERE/identity-schema.yaml"
  "$KUBECTL" wait --for=jsonpath='{.status.phase}'=Succeeded pod/identity-schema --timeout=180s \
    || { "$KUBECTL" logs identity-schema || true; fail "identity-schema did not succeed"; }
  "$KUBECTL" logs identity-schema
}

# Rolling deploys without dropped requests: eight workers in a closed loop against the origin
# through the NodePort, a rolling restart in the middle, and not one answer that is not a 200 —
# no 502, no reset, no 503 of either kind. The load must outlive the rollout, or the check
# would be about the wrong window.
phase_rolling() {
  tql bench --app "$APP_DIR" --url "$MEMBER" --route probe.fast --concurrency 8 \
    --duration 150s --no-scrape --format json --expect 'errors<=0,refused<=0' \
    > "$WORK/bench.json" 2> "$WORK/bench.err" &
  local bench=$!
  sleep 10
  local started rolled ended
  started=$(date +%s)
  "$KUBECTL" rollout restart "deployment/$RELEASE"
  "$KUBECTL" rollout status "deployment/$RELEASE" --timeout=240s
  rolled=$(date +%s)
  "$KUBECTL" get pods -o wide
  local rc=0
  wait $bench || rc=$?
  ended=$(date +%s)
  cat "$WORK/bench.err" >&2 || true
  [ "$rc" = "0" ] || { cat "$WORK/bench.json"; fail "bench exited $rc"; }
  [ "$ended" -ge "$rolled" ] \
    || fail "the load ended $((rolled - ended)) s before the rollout did; lengthen --duration"
  local requests statuses errors refused
  requests=$(jq -r '.requests' "$WORK/bench.json")
  statuses=$(jq -c '.statuses' "$WORK/bench.json")
  errors=$(jq -r '.errors' "$WORK/bench.json")
  refused=$(jq -c '.refused' "$WORK/bench.json")
  echo "rolling: $requests requests, statuses $statuses, errors $errors, refused $refused," \
    "rollout $((rolled - started)) s, load $((ended - started + 10)) s"
  [ "$requests" -ge 1000 ] || fail "too few requests to mean anything: $requests"
  [ "$(jq -r '.statuses | keys | join(",")' "$WORK/bench.json")" = "200" ] \
    || fail "an answer through the rollout was not a 200: $statuses"
  [ "$errors" = "0" ] || fail "$errors request(s) failed through the rollout"
  [ "$refused" = "{}" ] || fail "requests were refused through the rollout: $refused"
}

# Exactly-once scheduled firings: the probe job runs every ten seconds on every replica, the
# claim gives each window to one of them, and the ops API lists the executions. Every ten-second
# window since the install has at most one, there are enough of them to have spanned the
# rollout, and both replicas' schedulers were running.
phase_firings() {
  tql token --app "$APP_DIR" --sub proof --permission tql.app.use.user-admin \
    --permission tql.ops.view.user-admin > "$WORK/token"
  printf 'Authorization: Bearer %s\n' "$(cat "$WORK/token")" > "$WORK/auth.hdr"
  curl -sf -H @"$WORK/auth.hdr" "$MEMBER/_tesseraql/ops/batch/executions" \
    > "$WORK/executions.json" || fail "the ops API did not list executions"
  jq -r '.[] | select(.jobId == "probe.tick") | .startTime' "$WORK/executions.json" \
    > "$WORK/tick-starts.txt"
  local count
  count=$(wc -l < "$WORK/tick-starts.txt")
  echo "firings: $count executions of probe.tick listed"
  [ "$count" -ge 12 ] || fail "too few executions to have spanned the rollout: $count"
  # Each start bucketed into its ten-second window; a window with two executions is a firing
  # that ran twice.
  : > "$WORK/tick-windows.txt"
  while read -r start; do
    echo $(( $(date -d "$start" +%s) / 10 )) >> "$WORK/tick-windows.txt"
  done < "$WORK/tick-starts.txt"
  local doubled
  doubled=$(sort "$WORK/tick-windows.txt" | uniq -d | wc -l)
  [ "$doubled" = "0" ] || fail "$doubled fire time(s) ran on both replicas"
  local windows
  windows=$(sort -u "$WORK/tick-windows.txt" | wc -l)
  echo "firings: $windows distinct fire times, none doubled"
  for pod in $(pods); do
    "$KUBECTL" logs "$pod" | grep -q "Scheduled job probe.tick" \
      || fail "$pod never scheduled probe.tick"
  done
  echo "firings: both replicas scheduled the job"
}

# Shared sessions: a session minted through pod A's origin sign-in reads a browser route on pod
# B, port-forwarded by pod so the request cannot land anywhere else; without the cookie the same
# route is not a 200, so the read was the session's.
phase_sessions() {
  local a b
  a=$(pods | sed -n 1p)
  b=$(pods | sed -n 2p)
  [ -n "$a" ] && [ -n "$b" ] || fail "two running replicas are needed"
  "$KUBECTL" port-forward "pod/$a" 18081:8080 > "$WORK/pf-a.log" 2>&1 &
  "$KUBECTL" port-forward "pod/$b" 18082:8080 > "$WORK/pf-b.log" 2>&1 &
  wait_for_port 18081
  wait_for_port 18082
  jq -n --rawfile pw "$WORK/admin.pw" '{loginId: "admin", password: ($pw | rtrimstr("\n"))}' \
    > "$WORK/login.json"
  local signed
  signed=$(curl -s -o "$WORK/login.out" -w '%{http_code}' -c "$WORK/cookies.txt" \
    -H 'Content-Type: application/json' --data @"$WORK/login.json" \
    http://localhost:18081/_tesseraql/login)
  [ "$signed" = "200" ] || { cat "$WORK/login.out"; fail "sign-in on $a answered $signed"; }
  local read anonymous
  read=$(curl -s -o "$WORK/table.html" -w '%{http_code}' -b "$WORK/cookies.txt" \
    http://localhost:18082/user-admin/users/fragments/table)
  anonymous=$(curl -s -o /dev/null -w '%{http_code}' \
    http://localhost:18082/user-admin/users/fragments/table)
  echo "sessions: signed in on $a, read on $b answered $read, anonymous read answered $anonymous"
  [ "$read" = "200" ] || { cat "$WORK/table.html"; fail "the session from $a did not carry to $b"; }
  [ "$anonymous" != "200" ] || fail "the route answered 200 without a session; the read proves nothing"
}

# Alerts delivered, once per cluster: the probe job's notification to an unreachable channel
# dead-letters after maxAttempts on whichever replica dispatched it; both replicas' alert sweeps
# see the dead letter in the shared database; one of them claims TQL-OPS-9006 and pages it
# through the alerts channel — the application's own webhook route, which stores it. One row.
phase_alerts() {
  local dead rows
  for _ in $(seq 1 45); do
    dead=$(psql_count "select count(*) from tql_outbox_event where status = 'DEAD'")
    rows=$(psql_count "select count(*) from probe_alerts where code = 'TQL-OPS-9006'")
    if [ "${dead:-0}" -ge 1 ] && [ "${rows:-0}" -ge 1 ]; then
      break
    fi
    sleep 2
  done
  [ "${dead:-0}" -ge 1 ] || fail "no outbox event dead-lettered"
  [ "${rows:-0}" -ge 1 ] || fail "TQL-OPS-9006 never reached the alerts channel"
  # Three more check intervals on both replicas, and still one row.
  sleep 20
  rows=$(psql_count "select count(*) from probe_alerts where code = 'TQL-OPS-9006'")
  local node
  node=$(psql_count "select node from probe_alerts where code = 'TQL-OPS-9006' limit 1")
  echo "alerts: $dead dead-lettered event(s), $rows TQL-OPS-9006 row(s), paged by $node"
  [ "$rows" = "1" ] || fail "TQL-OPS-9006 was paged $rows times across two replicas"
  # The pod that paged may have been rolled since; the name is a replica's shape either way.
  [[ "$node" =~ ^$RELEASE-[a-z0-9]+-[a-z0-9]+$ ]] || fail "the alert names '$node', which is not a replica"
}

# The stop: a slow request in flight on the pod being deleted completes, and the container's
# last state is exit 143 — the drain finished and the process ended on its own, inside the
# grace the chart derived.
phase_stop() {
  local victim
  victim=$(pods | sed -n 1p)
  [ -n "$victim" ] || fail "a running replica is needed"
  "$KUBECTL" port-forward "pod/$victim" 18083:8080 > "$WORK/pf-victim.log" 2>&1 &
  "$KUBECTL" get pod "$victim" -o json -w > "$WORK/watch.json" 2>&1 &
  wait_for_port 18083
  curl -s -o "$WORK/slow.out" -w '%{http_code}' \
    'http://localhost:18083/user-admin/api/probe/slow?seconds=12' > "$WORK/slow.code" &
  local slow=$!
  sleep 3
  local deleted gone
  deleted=$(date +%s)
  "$KUBECTL" delete pod "$victim" --wait=false
  wait $slow || true
  local code
  code=$(cat "$WORK/slow.code")
  "$KUBECTL" wait --for=delete "pod/$victim" --timeout=90s
  gone=$(date +%s)
  local exit_code
  exit_code=$(jq -r 'select(.status.containerStatuses != null)
    | .status.containerStatuses[0].state.terminated.exitCode // empty' "$WORK/watch.json" | tail -1)
  echo "stop: the slow request answered $code, $victim exited ${exit_code:-unknown}" \
    "$((gone - deleted)) s after the delete (grace $GRACE s)"
  [ "$code" = "200" ] || { cat "$WORK/slow.out"; fail "the request in flight was cut"; }
  grep -q '"slept_seconds"' "$WORK/slow.out" || fail "the slow answer carried no body"
  [ "$exit_code" = "143" ] || fail "the container's last state was exit '${exit_code:-unknown}', not 143"
  [ $((gone - deleted)) -le "$GRACE" ] || fail "the stop outlived the grace"
  "$KUBECTL" rollout status "deployment/$RELEASE" --timeout=180s
}

phase_logs() {
  "$KUBECTL" get pods -o wide || true
  "$KUBECTL" get events --sort-by=.lastTimestamp | tail -40 || true
  for pod in $("$KUBECTL" get pods -l "$SELECTOR" -o jsonpath='{.items[*].metadata.name}'); do
    echo "===== $pod"
    "$KUBECTL" logs "$pod" --tail=200 || true
  done
  echo "===== postgres"
  "$KUBECTL" logs deploy/postgres --tail=50 || true
}

phase_teardown() {
  "$KIND" delete cluster --name "$CLUSTER"
}

case "${1:-}" in
  cluster|install|rolling|firings|sessions|alerts|stop|logs|teardown) "phase_$1" ;;
  all)
    for phase in cluster install rolling firings sessions alerts stop; do
      echo "=== $phase"
      "phase_$phase"
    done
    ;;
  *) fail "usage: proof.sh cluster|install|rolling|firings|sessions|alerts|stop|logs|teardown|all" ;;
esac
