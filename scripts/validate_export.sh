#!/usr/bin/env bash
# Builds a production package with the JVM unit tests and checks it with the web validator.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :app:testDebugUnitTest --no-daemon -q
work="$(mktemp -d)"
cp contracts/validate-package.mjs contracts/production-package.schema.json "$work/"
(cd "$work" && npm install --silent --no-save ajv@8 ajv-formats@3 >/dev/null)
node "$work/validate-package.mjs" app/build/test-production-package.json
