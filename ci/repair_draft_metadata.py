#!/usr/bin/env python3
"""Narrow historical draft repair: omit a missing optional v4 sidecar, never touch APKs."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

REPO = 'abhi-k9/MockGPS'
RELEASE = 422548757
APK = 'MockGPS-0.4.0-beta.1.apk'
APK_SHA = '92dc58e65bb84ad480d3d1b1a22342c5d0d6fbafa823da7bddb9bd1e0bd11998'
SOURCE = 'b1a23cee4ed511046ebe33a6dc0f9a254d64f064'
HEAD = 'ffb2be066d425a6e1d3396ed19271ab21ad54468'
SIDECAR = APK + '.idsig'


def call(args):
    r = subprocess.run(args, capture_output=True, text=True, timeout=180)
    if r.returncode:
        raise RuntimeError('Draft metadata operation failed; tool messages/redirect URLs suppressed')
    return r.stdout


def api(path):
    return json.loads(call(['gh', 'api', f'repos/{REPO}/{path}']))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def draft():
    r = api(f'releases/{RELEASE}')
    assert r['draft'] and r['prerelease'] and r['tag_name'] == 'v0.4.0-beta.1' and r['target_commitish'] == HEAD
    assert next(a for a in r['assets'] if a['name'] == APK)['digest'] == 'sha256:' + APK_SHA
    return r


assert os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch'
assert os.environ['GITHUB_REPOSITORY'] == REPO
release = draft()
with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as folder:
    out = Path(folder)
    # The pinned APK was already independently downloaded/signature-verified.
    # Compare all immutable server digests; download only the two metadata files.
    assets = {a['name']: a for a in release['assets']}
    assert len(assets) == 15
    call(['gh', 'release', 'download', 'v0.4.0-beta.1', '--repo', REPO, '--dir', str(out),
          '--pattern', 'release-manifest.json', '--pattern', 'SHA256SUMS'])
    files = {p.name: p for p in out.iterdir()}
    assert set(files) == {'release-manifest.json', 'SHA256SUMS'}
    assert all(assets[n]['digest'] == 'sha256:' + sha(p) for n, p in files.items())
    sums = dict((line.split('  ', 1)[1], line.split('  ', 1)[0])
                for line in files['SHA256SUMS'].read_text().splitlines())
    assert set(sums) - set(assets) == {SIDECAR}, 'Only the known missing optional v4 sidecar may be repaired'
    assert set(assets) - set(sums) == {'SHA256SUMS'}
    assert all(assets[n]['digest'] == 'sha256:' + h for n, h in sums.items() if n != SIDECAR)
    manifest = json.loads(files['release-manifest.json'].read_text())
    assert manifest['source_commit'] == SOURCE and manifest['validated_head_commit'] == HEAD
    assert manifest['signing']['signed_apk_sha256'] == APK_SHA
    assert SIDECAR in {a['name'] for a in manifest['assets']}
    repair = dict(reason='Remove checksum/manifest entry for optional apksigner v4 sidecar that was never uploaded; embedded APK signature unchanged',
                  original_manifest_sha256=sha(files['release-manifest.json']),
                  original_checksums_sha256=sha(files['SHA256SUMS']),
                  unchanged_signed_apk_sha256=APK_SHA, source_commit=SOURCE,
                  workflow_commit=os.environ['GITHUB_SHA'],
                  run_url=f'https://github.com/{REPO}/actions/runs/{os.environ["GITHUB_RUN_ID"]}')
    manifest['assets'] = [a for a in manifest['assets'] if a['name'] != SIDECAR]
    manifest['metadata_repair'] = repair
    files['release-manifest.json'].write_text(json.dumps(manifest, indent=2) + '\n')
    digests = {n: a['digest'].removeprefix('sha256:') for n, a in assets.items()}
    digests['release-manifest.json'] = sha(files['release-manifest.json'])
    files['SHA256SUMS'].write_text(''.join(h + '  ' + n + '\n'
                                         for n, h in sorted(digests.items()) if n != 'SHA256SUMS'))
    draft()  # Recheck immediately before the only two writes; no signing secrets or SDK.
    call(['gh', 'release', 'upload', 'v0.4.0-beta.1', '--repo', REPO, '--clobber',
          str(files['release-manifest.json']), str(files['SHA256SUMS'])])
    remote = draft()
    expected = dict(digests, SHA256SUMS=sha(files['SHA256SUMS']))
    assert {a['name'] for a in remote['assets']} == set(expected)
    assert all(a['digest'] == 'sha256:' + expected[a['name']] for a in remote['assets'])
    repair.update(manifest_sha256=sha(files['release-manifest.json']), checksums_sha256=sha(files['SHA256SUMS']))
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as report:
        report.write('Historical private draft metadata repaired. APK/source/signature unchanged; current release gates remain blocked.\n')
    print('::notice title=Draft metadata repair::' + json.dumps(repair, separators=(',', ':')))
