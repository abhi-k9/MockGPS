#!/usr/bin/env bash
# Local feedback only. SDK provisioning and hosted CI are deliberately separate.
set -euo pipefail
exec python3 "$(dirname -- "${BASH_SOURCE[0]}")/dev.py" "$@"
