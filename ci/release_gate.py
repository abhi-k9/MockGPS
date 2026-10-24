"""Mandatory exact-source release gates. Fingerprint reuse is for PR feedback only."""
import json
import re
import subprocess


def latest_jobs(jobs):
    return {j['name']: j for j in sorted(jobs, key=lambda j: (
        j.get('run_attempt', 1), j.get('started_at', ''), j['id']))}


def require_run(api, repo, source, run):
    validation = api(f'repos/{repo}/actions/runs/{run}')
    assert validation['head_sha'] == source and validation['status'] == 'completed'
    assert validation['conclusion'] == 'success', 'Full Android validation must pass before signing'
    assert validation['head_repository']['full_name'] == repo
    jobs = latest_jobs(api(f'repos/{repo}/actions/runs/{run}/jobs?filter=all&per_page=100')['jobs'])
    for name in ('build', 'instrumentation API 36', 'instrumentation API 37.0'):
        assert jobs[name]['conclusion'] == 'success', f'Mandatory job did not pass: {name}'
    for name in ('instrumentation API 36', 'instrumentation API 37.0'):
        notes = api(f'repos/{repo}/check-runs/{jobs[name]["id"]}/annotations')
        assert any(n.get('title') == 'Actual emulator test results' and re.fullmatch(
            r'tests=(?:1[7-9]|[2-9]\d|\d{3,}) failures=0 errors=0 skipped=0', n['message'])
            for n in notes), 'Require actual complete device-test evidence, not a skipped/cached job'
    notes = api(f'repos/{repo}/check-runs/{jobs["build"]["id"]}/annotations')
    proof = next(json.loads(n['message']) for n in notes if n.get('title') == 'Development candidate provenance')
    assert proof['commit'] == source, 'Release APK must come from the exact SHA; PR merge/fingerprint equivalence is insufficient'
    assert proof.get('validation_scope') == 'full', 'Ordinary CHECK cannot replace the clean FULL release lane'
    assert proof['unit_tests']['tests'] >= 82 and all(proof['unit_tests'][k] == 0 for k in ('failures', 'errors', 'skipped'))
    return validation, jobs


def require_dependency_review(api, repo, source):
    runs = api(f'repos/{repo}/actions/workflows/dependency-review.yml/runs?head_sha={source}&status=completed&per_page=30')['workflow_runs']
    for run in runs:
        if run['head_sha'] != source or run['conclusion'] != 'success' or run['head_repository']['full_name'] != repo:
            continue
        jobs = api(f'repos/{repo}/actions/runs/{run["id"]}/jobs?filter=all&per_page=100')['jobs']
        if any(j['conclusion'] == 'success' and any(
            'actions/dependency-review-action@' in s['name'] and s['conclusion'] == 'success'
            for s in j.get('steps', [])) for j in latest_jobs(jobs).values()):
            return run['html_url']
    raise AssertionError('Exact-SHA Dependency Review action has not passed')


def require_checkout(source, workflow_sha):
    assert re.fullmatch('[0-9a-f]{40}', source)
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == source == workflow_sha, \
        'Signing workflow, checkout and validated source must be the same SHA'
