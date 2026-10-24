#!/usr/bin/env python3
"""Read-only RELEASE preflight; no secret access, builds, dispatch, signing or publication."""
import json
import os
from pathlib import Path
import subprocess
import sys
from release_gate import require_run, require_dependency_review


def api(path):
    r = subprocess.run(['gh', 'api', path], capture_output=True, text=True, timeout=30)
    if r.returncode:
        raise RuntimeError('GitHub release metadata unavailable')
    return json.loads(r.stdout)


try:
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], text=True).strip():
        raise RuntimeError('Commit tracked changes before exact-source RELEASE preflight')
    run = os.environ.get('VALIDATION_RUN_ID', '')
    if not run.isdecimal():
        raise RuntimeError('Set VALIDATION_RUN_ID to a successful exact-SHA full Android run; this command never launches CI')
    repo = 'abhi-k9/MockGPS'
    require_run(api, repo, source, run)
    dependency_url = require_dependency_review(api, repo, source)
    release = next(r for r in api(f'repos/{repo}/releases?per_page=100') if r['tag_name'] == 'v0.4.0-beta.1')
    assert release['draft'] and release['prerelease'] and release['target_commitish'] == source
    print('Automated exact-SHA gates passed:', source, dependency_url)
    print('Use the isolated manual signing lane. Verify downloaded assets/signature against the pinned certificate afterward; phone gates still apply.')
except (RuntimeError, AssertionError, KeyError, StopIteration) as error:
    print(str(error) or 'Release evidence is incomplete; signing remains blocked', file=sys.stderr)
    sys.exit(2)
