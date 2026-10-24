#!/usr/bin/env python3
"""A failed/skipped/reused API37 run and different APK source cannot reach signing."""
import copy
import json
import unittest
from release_gate import require_run


class GateTest(unittest.TestCase):
    def fixture(self):
        root = 'repos/a/b'
        proof = dict(commit='a' * 40, validation_scope='full', unit_tests=dict(tests=82, failures=0, errors=0, skipped=0))
        data = {root + '/actions/runs/1': dict(head_sha='a' * 40, status='completed', conclusion='success', head_repository={'full_name': 'a/b'}),
                root + '/actions/runs/1/jobs?filter=all&per_page=100': {'jobs': [dict(id=i, name=n, conclusion='success') for i, n in enumerate(('build', 'instrumentation API 36', 'instrumentation API 37.0'), 1)]},
                root + '/check-runs/1/annotations': [dict(title='Development candidate provenance', message=json.dumps(proof))]}
        for i in (2, 3):
            data[root + f'/check-runs/{i}/annotations'] = [dict(title='Actual emulator test results', message='tests=17 failures=0 errors=0 skipped=0')]
        return data

    def test_exact_success(self):
        require_run(self.fixture().__getitem__, 'a/b', 'a' * 40, '1')

    def test_failures_skips_and_missing_device_evidence_block(self):
        for failure in ('failure', 'skipped', 'cancelled'):
            data = self.fixture()
            data['repos/a/b/actions/runs/1/jobs?filter=all&per_page=100']['jobs'][2]['conclusion'] = failure
            with self.assertRaises(AssertionError):
                require_run(data.__getitem__, 'a/b', 'a' * 40, '1')
        data = self.fixture()
        data['repos/a/b/check-runs/3/annotations'] = []
        with self.assertRaises(AssertionError):
            require_run(data.__getitem__, 'a/b', 'a' * 40, '1')

    def test_merge_source_or_wrong_head_cannot_sign(self):
        data = self.fixture()
        data['repos/a/b/check-runs/1/annotations'][0]['message'] = json.dumps(dict(commit='b' * 40, unit_tests={}))
        with self.assertRaises(AssertionError):
            require_run(data.__getitem__, 'a/b', 'a' * 40, '1')
        with self.assertRaises(AssertionError):
            require_run(self.fixture().__getitem__, 'a/b', 'b' * 40, '1')


if __name__ == '__main__':
    unittest.main()
