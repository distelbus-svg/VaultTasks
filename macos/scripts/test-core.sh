#!/bin/sh
# Swift Testing only; plain `swift test` also launches an empty XCTest runner that exits 1.
set -e
cd "$(dirname "$0")/../VaultTasksCore"
swift test --disable-xctest "$@"
