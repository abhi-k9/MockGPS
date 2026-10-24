#!/usr/bin/env python3
"""Security regressions for CI reuse: a known failure must never become a cached pass."""
import json,os,subprocess,tempfile
from pathlib import Path
from runtime_inputs import fingerprint

ROOT=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as folder:
    t=Path(folder);git=t/'repo';git.mkdir()
    def command(*args):return subprocess.check_output(args,cwd=git,text=True,stderr=subprocess.DEVNULL).strip()
    command('git','init');(git/'app').mkdir();(git/'app/code.kt').write_text('runtime')
    command('git','add','.');command('git','-c','user.name=CI test','-c','user.email=ci@example.invalid','commit','-m','fixture')
    sha=command('git','rev-parse','HEAD')
    fake=t/'gh'
    fake.write_text('#!/usr/bin/env python3\nimport json,sys,os\np=sys.argv[2]\nf=json.load(open(os.environ["FAKE_RESPONSES"]))\nif p not in f:sys.exit(1)\nprint(json.dumps(f[p]))\n');fake.chmod(0o755)
    repo='example/MockGPS';runpath=f'repos/{repo}/actions/workflows/android.yml/runs?per_page=40'
    def fixture(conclusion='success',tests=82,fork=False):
        return {runpath:{'workflow_runs':[dict(id=1,status='completed',event='push',head_sha=sha,conclusion=conclusion,
               html_url='https://example.invalid/run/1',head_repository={'full_name':'other/repo' if fork else repo})]},
          f'repos/{repo}/actions/runs/1/jobs':{'jobs':[dict(id=i,name=n,conclusion='failure' if conclusion=='failure' and i==3 else 'success') for i,n in enumerate(['build','instrumentation API 36','instrumentation API 37.0'],1)]},
          f'repos/{repo}/check-runs/1/annotations':[dict(title='Development candidate provenance',message=json.dumps(dict(unit_tests=dict(tests=tests,failures=0,errors=0,skipped=0))))]}
    def plan(data,force='false'):
        path=t/'responses.json';path.write_text(json.dumps(data));out=t/'out';out.write_text('')
        env=dict(os.environ,PATH=str(t)+os.pathsep+os.environ['PATH'],FAKE_RESPONSES=str(path),GITHUB_REPOSITORY=repo,
                 GITHUB_RUN_ID='999',GITHUB_OUTPUT=str(out),GITHUB_STEP_SUMMARY=str(t/'summary'),FORCE_ANDROID=force)
        subprocess.run(['python3',str(ROOT/'ci/plan.py')],cwd=git,env=env,check=True,capture_output=True)
        return dict(l.split('=',1) for l in out.read_text().splitlines())
    assert plan(fixture())['android_required']=='false'
    failed=plan(fixture('failure'));assert failed['android_required']=='false' and failed['blocked']=='true'
    assert plan(fixture(tests=79))['android_required']=='true'
    assert plan(fixture(fork=True))['android_required']=='true'
    assert plan({})['android_required']=='true'
    assert plan(fixture(),force='true')['android_required']=='true'
    (git/'README.md').write_text('docs');command('git','add','.');command('git','-c','user.name=CI test','-c','user.email=ci@example.invalid','commit','-m','docs')
    assert plan(fixture())['android_required']=='false'
    (git/'app/code.kt').write_text('changed runtime');command('git','add','.');command('git','-c','user.name=CI test','-c','user.email=ci@example.invalid','commit','-m','runtime')
    assert plan(fixture())['android_required']=='true'
print('CI reuse guards passed: successful/failing/missing/forked evidence, forced runs, docs and runtime changes')
