#!/usr/bin/env python3
"""Reuse only actual matching completed Android results; retain known failures, fail closed."""
import json,os,subprocess
from pathlib import Path
from runtime_inputs import fingerprint

def api(path):
    r=subprocess.run(['gh','api',path],capture_output=True,text=True)
    if r.returncode: raise RuntimeError('CI metadata unavailable')
    return json.loads(r.stdout)

repo=os.environ['GITHUB_REPOSITORY']
current=fingerprint()
outputs={'runtime_fingerprint':current,'android_required':'true','blocked':'false','evidence_url':''}
if os.environ.get('FORCE_ANDROID')!='true':
    try:
        runs=api(f'repos/{repo}/actions/workflows/android.yml/runs?per_page=40')['workflow_runs']
        for run in runs:
            if run['id']==int(os.environ['GITHUB_RUN_ID']) or run['status']!='completed': continue
            if run.get('head_repository',{}).get('full_name')!=repo:continue
            jobs=api(f'repos/{repo}/actions/runs/{run["id"]}/jobs')['jobs']
            byname={j['name']:j for j in jobs}
            if not {'build','instrumentation API 36','instrumentation API 37.0'} <= byname.keys(): continue
            build=byname['build']
            proof=None
            if build['conclusion']=='success':
                for note in api(f'repos/{repo}/check-runs/{build["id"]}/annotations'):
                    if note.get('title')=='Development candidate provenance': proof=json.loads(note['message'])
            matched=bool(proof and proof.get('runtime_inputs_sha256')==current)
            # Bootstrap older push runs which checked out their exact SHA. Never guess PR merge trees.
            if not matched and run['event']=='push':
                exists=subprocess.run(['git','cat-file','-e',run['head_sha']+'^{commit}'],capture_output=True).returncode==0
                matched=exists and fingerprint(run['head_sha'])==current
            if not matched:continue
            success=run['conclusion']=='success' and all(byname[n]['conclusion']=='success' for n in ('build','instrumentation API 36','instrumentation API 37.0'))
            if success:
                assert proof and proof['unit_tests']['tests']>=82
                assert all(proof['unit_tests'][k]==0 for k in ('failures','errors','skipped'))
            outputs.update(android_required='false',blocked='false' if success else 'true',evidence_url=run['html_url'])
            break
    except Exception:
        # Missing/unreadable provenance cannot justify skipping validation.
        outputs.update(android_required='true',blocked='false',evidence_url='')
with open(os.environ['GITHUB_OUTPUT'],'a') as f:
    for k,v in outputs.items(): f.write(k+'='+v+'\n')
summary='Full Android validation required.' if outputs['android_required']=='true' else (
    ('Reusing successful identical runtime inputs: ' if outputs['blocked']=='false' else
     'Identical runtime inputs have a failed validation; fix it or explicitly force a retry: ')+outputs['evidence_url'])
print(summary)
with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write(summary+'\n\nNo new device execution is claimed when reused.\n')
