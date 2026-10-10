#!/bin/sh
# Copies the single-source fixtures from the Android domain module into the Swift test resources.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
cp "$HERE/../../domain/src/test/resources/parser-fixtures.json" \
   "$HERE/../VaultTasksCore/Tests/VaultTasksCoreTests/Resources/parser-fixtures.json"
echo "fixtures synced"
