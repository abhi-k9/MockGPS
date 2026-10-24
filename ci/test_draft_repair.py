#!/usr/bin/env python3
"""Exercise the actual repair against archived real metadata, with no network/writes to GitHub."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class RepairTest(unittest.TestCase):
    def exercise(self, invalid=None):
        fixture = ROOT / 'docs/evidence/beta'
        files = {'release-manifest.json': (fixture / 'release-manifest.json').read_bytes(),
                 'SHA256SUMS': (fixture / 'original-SHA256SUMS').read_bytes()}
        digest = lambda data: hashlib.sha256(data).hexdigest()
        sums = dict((line.split('  ', 1)[1], line.split('  ', 1)[0])
                    for line in files['SHA256SUMS'].decode().splitlines())
        assets = {n: 'sha256:' + h for n, h in sums.items() if not n.endswith('.idsig')}
        assets['SHA256SUMS'] = 'sha256:' + digest(files['SHA256SUMS'])
        if invalid == 'missing':
            del assets['LICENSE.txt']
        uploaded = []

        def fake(args, **kwargs):
            if args[:2] == ['gh', 'api']:
                data = dict(draft=True, prerelease=True, tag_name='v0.4.0-beta.1',
                            target_commitish=('0' * 40 if invalid == 'source' else 'ffb2be066d425a6e1d3396ed19271ab21ad54468'),
                            assets=[dict(name=n, digest=h) for n, h in assets.items()])
                return subprocess.CompletedProcess(args, 0, json.dumps(data), '')
            if args[:3] == ['gh', 'release', 'download']:
                out = Path(args[args.index('--dir') + 1])
                for n, data in files.items():
                    (out / n).write_bytes(data)
            elif args[:3] == ['gh', 'release', 'upload']:
                paths = [Path(p) for p in args[-2:]]
                self.assertEqual({'release-manifest.json', 'SHA256SUMS'}, {p.name for p in paths})
                for p in paths:
                    uploaded.append(p.name)
                    files[p.name] = p.read_bytes()
                    assets[p.name] = 'sha256:' + digest(files[p.name])
            else:
                self.fail('Unexpected mutation/tool: ' + str(args[:3]))
            return subprocess.CompletedProcess(args, 0, '', '')

        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            env = dict(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REPOSITORY='abhi-k9/MockGPS',
                       RUNNER_TEMP=folder, GITHUB_SHA='1' * 40, GITHUB_RUN_ID='1',
                       GITHUB_STEP_SUMMARY=str(Path(folder) / 'summary'))
            with patch.dict(os.environ, env), patch('subprocess.run', side_effect=fake), contextlib.redirect_stdout(io.StringIO()):
                if invalid:
                    with self.assertRaises(AssertionError):
                        runpy.run_path(str(ROOT / 'ci/repair_draft_metadata.py'))
                    self.assertFalse(uploaded)
                else:
                    runpy.run_path(str(ROOT / 'ci/repair_draft_metadata.py'))
        return files, assets

    def test_real_historical_sidecar_entry_repaired_without_apk_write(self):
        files, assets = self.exercise()
        manifest = json.loads(files['release-manifest.json'])
        self.assertEqual('b1a23cee4ed511046ebe33a6dc0f9a254d64f064', manifest['source_commit'])
        self.assertFalse(any(a['name'].endswith('.idsig') for a in manifest['assets']))
        self.assertEqual('17ec8d34e24c0fea8b474f0ee2a9b24273a60b006b316909b5c626ccafa80a77',
                         manifest['metadata_repair']['original_manifest_sha256'])
        sums = dict((line.split('  ', 1)[1], line.split('  ', 1)[0]) for line in files['SHA256SUMS'].decode().splitlines())
        self.assertEqual(set(assets) - {'SHA256SUMS'}, set(sums))
        self.assertTrue(all(assets[n] == 'sha256:' + h for n, h in sums.items()))

    def test_unexpected_source_or_missing_asset_cannot_write(self):
        self.exercise('source')
        self.exercise('missing')


if __name__ == '__main__':
    unittest.main()
