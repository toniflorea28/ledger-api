#!/usr/bin/env bash
# One script to build the service, run the tests against real MySQL and Kafka, and
# start the full stack (MySQL, Kafka, two ledger instances behind a gateway).
#
# Needs only Docker, Git and a JDK 17+ on the host. Maven is fetched by the wrapper.
#
#   ./run.sh              build, test, start stack, smoke-check
#   ./run.sh --skip-tests build and start only
#   ./run.sh --down       stop the stack and remove volumes
set -euo pipefail
cd "$(dirname "$0")"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
      /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
      /usr/lib/jvm/java-17-openjdk-amd64 \
      /usr/lib/jvm/temurin-17-jdk-amd64; do
    if [[ -x "$candidate/bin/java" ]]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
command -v java >/dev/null 2>&1 || die "no JDK found; install JDK 17+ or set JAVA_HOME"
java_major=$(java -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print v[1]}')
[[ "$java_major" -ge 17 ]] || die "JDK 17+ required, found $java_major"

command -v docker >/dev/null 2>&1 || die "docker not found"
docker info >/dev/null 2>&1 || die "docker daemon is not running"
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  die "docker compose (v2) not found"
fi

# Testcontainers ignores the docker CLI context; it needs DOCKER_HOST and, for the
# containers it starts itself, the socket path as seen inside the Docker VM.
if [[ -z "${DOCKER_HOST:-}" ]]; then
  ctx_host=$(docker context inspect --format '{{(index .Endpoints "docker").Host}}' 2>/dev/null || true)
  if [[ -n "$ctx_host" ]]; then export DOCKER_HOST="$ctx_host"; fi
fi
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-/var/run/docker.sock}"

SKIP_TESTS=false
case "${1:-}" in
  --down) log "Stopping stack"; "${COMPOSE[@]}" down -v --remove-orphans; exit 0 ;;
  --skip-tests) SKIP_TESTS=true ;;
  "") ;;
  *) die "unknown option: $1" ;;
esac

log "Java: $(java -version 2>&1 | head -1)"
log "Compose: $("${COMPOSE[@]}" version | head -1)"
log "Docker host: ${DOCKER_HOST}"

if [[ "$SKIP_TESTS" == false ]]; then
  log "Building and testing (unit tests, then integration tests on Testcontainers)"
  ./mvnw -B verify
else
  log "Building (tests skipped)"
  ./mvnw -B -DskipTests package
fi

log "Starting the stack (mysql, kafka, ledger-1, ledger-2, gateway)"
"${COMPOSE[@]}" up --build -d --wait --wait-timeout 180

log "Smoke check through the gateway"
for i in $(seq 1 30); do
  if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS http://localhost:8080/actuator/health | sed 's/^/  /'; echo

log "Ready. API at http://localhost:8080 (round-robin over ledger-1 and ledger-2)."
echo "  Logs:  ${COMPOSE[*]} logs -f ledger-1 ledger-2"
echo "  Stop:  ./run.sh --down"
