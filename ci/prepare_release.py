#!/usr/bin/env python3
"""Prepare a private draft from exact CI APK bytes before any signing secrets are bound."""
import datetime,hashlib,json,os,re,shutil,subprocess,tempfile,urllib.request
from pathlib import Path
from runtime_inputs import fingerprint
from release_gate import require_run, require_dependency_review, require_checkout
repo=os.environ['GITHUB_REPOSITORY'];head=os.environ['SOURCE_SHA'];run=os.environ['VALIDATION_RUN_ID']
assert re.fullmatch('[0-9a-f]{40}',head) and run.isdecimal()
tag='v0.4.0-beta.1'
def call(args):
    r=subprocess.run(args,capture_output=True,text=True)
    if r.returncode:raise RuntimeError(args[0]+' failed; redirect URLs/messages suppressed')
    return r.stdout
def api(path):return json.loads(call(['gh','api',path]))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
require_checkout(head,os.environ['GITHUB_SHA'])
validation,byname=require_run(api,repo,head,run)
dependency_url=require_dependency_review(api,repo,head)
jobs=list(byname.values())
devices=[]
for name in ('instrumentation API 36','instrumentation API 37.0'):
    job=byname[name];notes=api(f'repos/{repo}/check-runs/{job["id"]}/annotations')
    passed=next(n for n in notes if n.get('title')=='Actual emulator test results')
    m=re.fullmatch(r'tests=(\d+) failures=0 errors=0 skipped=0',passed['message']);assert m and int(m[1])>=17
    devices.append(dict(api=36 if name.endswith('36') else 37,status='PASS',tests=int(m[1]),failures=0,errors=0,skipped=0,job_id=job['id']))
release=next(r for r in api(f'repos/{repo}/releases?per_page=100') if r['tag_name']==tag)
assert release['draft'] and release['prerelease'] and release['target_commitish']==head
artifact=next(a for a in api(f'repos/{repo}/actions/runs/{run}/artifacts?per_page=100')['artifacts'] if a['name']==f'android-build-and-reports-{head}-attempt{byname["build"].get("run_attempt",1)}' and not a['expired'])
with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as folder:
    folder=Path(folder);build=folder/'build';out=folder/'assets';out.mkdir()
    call(['gh','run','download',run,'--repo',repo,'--name',artifact['name'],'--dir',str(build)])
    identity=json.loads(next(build.rglob('build-identity.json')).read_text())
    assert identity['commit']==head, 'Require exact source, not a matching runtime fingerprint'
    assert identity['runtime_inputs_sha256']==fingerprint(head)
    assert identity['application_id']=='dev.abhik.mockgps' and identity['version_code']==5 and identity['version_name']=='0.4.0-beta.1'
    assert identity['unit_tests']['tests']>=82 and all(identity['unit_tests'][k]==0 for k in ('failures','errors','skipped'))
    assert identity['lint'].get('Error',0)==identity['lint'].get('Fatal',0)==0
    actual=identity['commit']
    assert re.fullmatch('[0-9a-f]{40}',actual)
    unsigned=next(build.rglob('app-release-unsigned.apk'));name='MockGPS-0.4.0-beta.1-unsigned.apk'
    shutil.copyfile(unsigned,out/name)
    badging=call([os.environ['ANDROID_HOME']+'/build-tools/36.0.0/aapt','dump','badging',str(out/name)])
    assert "package: name='dev.abhik.mockgps'" in badging and "versionCode='5'" in badging and "targetSdkVersion:'37'" in badging and 'application-debuggable' not in badging
    for src,dest in [('tasker/MockGPS.prj.xml','MockGPS.prj.xml'),('tasker/README.md','TASKER_IMPORT.md'),('docs/BETA_RELEASE.md','RELEASE_NOTES.md'),('LICENSE','LICENSE.txt')]:shutil.copyfile(src,out/dest)
    inv_path=Path('docs/evidence/beta/release-inventory.json');adv_path=Path('docs/evidence/beta/release-advisories.json')
    inv=json.loads(inv_path.read_text());adv=json.loads(adv_path.read_text())
    assert adv['inventory_sha256']==sha(inv_path) and adv['positive_control']['passed']
    assert fingerprint(adv['source_commit'])==identity['runtime_inputs_sha256']
    queried=datetime.datetime.fromisoformat(adv['queried_at_utc'])
    assert datetime.datetime.now(datetime.timezone.utc)-queried < datetime.timedelta(days=1), 'Refresh the advisory snapshot before signing'
    findings=[dict(package=m['name'],version=m['version'],id=v['ghsa_id'],severity=v['severity']) for m in adv['results'] for v in m['advisories'] if not v.get('withdrawn_at')]
    assert not any(f['severity'] in ('high','critical') for f in findings)
    shutil.copyfile(inv_path,out/'dependency-inventory.json');shutil.copyfile(adv_path,out/'advisory-report.json')
    source_name='MockGPS-source-'+actual+'.zip'
    with urllib.request.urlopen(f'https://api.github.com/repos/{repo}/zipball/{actual}',timeout=120) as response:(out/source_name).write_bytes(response.read())
    (out/'sbom.cdx.json').write_text(json.dumps(dict(bomFormat='CycloneDX',specVersion='1.6',version=1,
      metadata=dict(properties=[dict(name='mockgps:coverage',value='Recorded resolved Maven scopes only; excludes native/shaded/SDK/firmware/unknown advisories')]),
      components=[dict(type='library',group=m['name'].split(':')[0],name=m['name'].split(':')[1],version=m['version'],purl='pkg:maven/'+m['name'].replace(':','/')+'@'+m['version']) for m in inv['modules']]),indent=2)+'\n')
    evidence=dict(head_sha=head,artifact_source_commit=actual,runtime_inputs_sha256=identity['runtime_inputs_sha256'],url=validation['html_url'],conclusion=validation['conclusion'],jobs=[dict(id=j['id'],name=j['name'],conclusion=j['conclusion']) for j in jobs],device_results=devices,build_identity=identity,artifact_id=artifact['id'],artifact_digest=artifact.get('digest'))
    (out/'ci-evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
    manifest=dict(schema=1,version='0.4.0-beta.1',version_code=5,application_id='dev.abhik.mockgps',source_namespace='dev.burixon.mockgps',source_commit=actual,validated_head_commit=head,
      source_url=f'https://github.com/{repo}/tree/{actual}',source_archive=source_name,variant='unsigned foundation for private signed beta candidate',published=False,stable_production=False,
      tools=dict(java='21 Temurin (CI)',gradle='9.6.0',agp='9.2.1',compile_sdk=37,target_sdk=37,min_sdk=29,build_tools='36.0.0'),unit_tests=identity['unit_tests'],lint=identity['lint'],github_android=evidence,
      signing=dict(status='UNSIGNED',signer_certificate_sha256=None),dependencies=dict(coordinates=len(inv['modules']),findings=findings,inventory_sha256=sha(inv_path),query_source=adv['source_commit'],queried_at_utc=adv['queried_at_utc'],limits=adv['limitations']),
      tasker=dict(status='EXPERIMENTAL; actual import NOT RUN',source_commit=os.environ['GITHUB_SHA']),hardware=dict(samsung_matrix='24 complete cases NOT RUN; user-reported partial observations recorded separately'),
      release_gates=dict(dependency_review='PASS: '+dependency_url,api37='PASS' if devices[1]['status']=='PASS' else 'BLOCKED: guest platform failed before tests; check remains failed',mandatory_hardware='BLOCKED: incomplete physical matrix',persistent_signing='PENDING: isolated signing step'),
      attestation='Self-recorded CI/source receipt; runtime fingerprint binds validated PR head and actual merge-build source; not a cryptographic SLSA attestation')
    manifest['assets']=[dict(name=f.name,bytes=f.stat().st_size,sha256=sha(f)) for f in sorted(out.iterdir())]
    (out/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (out/'SHA256SUMS').write_text(''.join(sha(f)+'  '+f.name+'\n' for f in sorted(out.iterdir())))
    live=api(f'repos/{repo}/releases/{release["id"]}')
    assert live['draft'] and live['prerelease'] and live['target_commitish']==head
    call(['gh','release','upload',tag,'--repo',repo,'--clobber',*[str(f) for f in sorted(out.iterdir())]])
    print('Prepared verified CI APK/source/dependency/Tasker assets in the owner-only draft; no key material used')
