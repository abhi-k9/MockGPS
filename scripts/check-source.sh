#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
for script in scripts/*.sh; do bash -n "$script"; done
python3 - <<'PY'
from pathlib import Path
import ast,xml.etree.ElementTree as E
for p in Path('scripts').glob('*.py'): ast.parse(p.read_text(),filename=str(p))
for p in Path('app/src').rglob('*.xml'): E.parse(p)
PY
python3 scripts/generate-tasker-project.py
node scripts/test-tasker-dispatch.cjs
git diff --exit-code -- tasker/MockGPS.prj.xml
git diff --check
