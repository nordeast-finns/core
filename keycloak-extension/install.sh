#!/bin/bash
# Installs or updates core's Keycloak extension in a Docker Compose Keycloak laid out like keycloak-1's:
# a directory (default /opt/keycloak) with a Dockerfile whose builder stage runs `kc.sh build`, compose.yaml
# with a `keycloak` service, and .env. Run it there as root, with finns-core-keycloak.jar next to this script.
# Safe to run again: it only adds what's missing, and backs up what it changes.
#
#   FINNS_CORE_URL=https://test-api.finnsbali.com bash install.sh /opt/keycloak
#
# FINNS_CORE_URL (core's origin) is only needed the first time; it's kept in .env with a new random
# FINNS_CORE_TOKEN, which core needs as FINNS_KEYCLOAK_WEBHOOK_TOKEN.
set -euo pipefail

dir=${1:-/opt/keycloak}
jar="$(dirname "$(readlink -f "$0")")/finns-core-keycloak.jar"
[ -f "$jar" ] || { echo "No $jar" >&2; exit 1; }
cd "$dir"

ts=$(date +%Y%m%d%H%M%S)
for f in Dockerfile compose.yaml .env; do cp -p "$f" "$f.bak-$ts"; done
echo "Backed up Dockerfile, compose.yaml and .env as *.bak-$ts"

mkdir -p providers
install -m 644 "$jar" providers/finns-core-keycloak.jar

# The builder stage bakes providers/ into the optimized build, and the keycloak service gets the
# extension's options from .env, next to its other KC_ variables.
python3 - <<'PY'
import re

dockerfile = open("Dockerfile").read()
if "\nCOPY providers/ " not in dockerfile:
    dockerfile = re.sub(r"^(RUN /opt/keycloak/bin/kc\.sh build)$", r"COPY providers/ /opt/keycloak/providers/\n\1",
                        dockerfile, count=1, flags=re.M)
    open("Dockerfile", "w").write(dockerfile)

compose = open("compose.yaml").read()
if "KC_SPI_EVENTS_LISTENER__FINNS_CORE__URL" not in compose:
    compose = re.sub(r"^( *)(KC_PROXY_HEADERS: .*)$",
                     r"\1\2\n\1KC_SPI_EVENTS_LISTENER__FINNS_CORE__URL: ${FINNS_CORE_URL}"
                     r"\n\1KC_SPI_EVENTS_LISTENER__FINNS_CORE__TOKEN: ${FINNS_CORE_TOKEN}",
                     compose, count=1, flags=re.M)
    open("compose.yaml", "w").write(compose)
PY
if ! grep -q '^FINNS_CORE_URL=' .env; then
	: "${FINNS_CORE_URL:?set FINNS_CORE_URL to the origin of core, e.g. https://test-api.finnsbali.com}"
	echo "FINNS_CORE_URL=$FINNS_CORE_URL" >> .env
fi
grep -q '^FINNS_CORE_TOKEN=' .env || echo "FINNS_CORE_TOKEN=$(openssl rand -hex 32)" >> .env

if ! grep -q '^COPY providers/ ' Dockerfile || ! grep -q 'KC_SPI_EVENTS_LISTENER__FINNS_CORE__TOKEN' compose.yaml; then
	echo "Couldn't add the extension to Dockerfile or compose.yaml; nothing restarted. See the .bak-$ts files." >&2
	exit 1
fi

docker compose build keycloak
docker compose up -d keycloak

echo "Waiting for Keycloak to be healthy..."
status=starting
for _ in $(seq 60); do
	status=$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q keycloak)")
	[ "$status" = healthy ] && break
	sleep 5
done
echo "Keycloak: $status"
docker compose logs --since 10m keycloak | grep 'finns-core' \
	|| { echo "No finns-core line in Keycloak's log: the extension isn't loaded." >&2; exit 1; }
