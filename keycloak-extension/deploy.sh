#!/bin/bash
# From your machine: copies the extension jar and the server scripts to the Keycloak server, then runs
# install.sh there (see manual-keycloak.md). Build the jar first.
#
#   bash keycloak-extension/deploy.sh [root@keycloak-1] [https://test-api.finnsbali.com]
set -euo pipefail

server=${1:-root@keycloak-1}
core_url=${2:-https://test-api.finnsbali.com}
here=$(cd "$(dirname "$0")" && pwd)
jar="$here/target/finns-core-keycloak-1.0.0.jar"
[ -f "$jar" ] || { echo "No $jar: build it with ./mvnw -f keycloak-extension/pom.xml package" >&2; exit 1; }

scp "$jar" "$server:/root/finns-core-keycloak.jar"
scp "$here/install.sh" "$here/setup-realm.py" "$server:/root/"
ssh "$server" "FINNS_CORE_URL='$core_url' bash /root/install.sh /opt/keycloak"
