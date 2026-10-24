#!/usr/bin/env python3
"""Package an unsigned beta candidate, bounded CycloneDX inventory and honest evidence."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
p = argparse.ArgumentParser()
p.add_argument('--inventory', type=Path, required=True)
p.add_argument('--advisories', type=Path, required=True)
p.add_argument('--ci-evidence', type=Path, required=True)
p.add_argument('--output', type=Path, default=Path('work/release-review/candidate'))
a = p.parse_args()
source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
assert not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], text=True).strip(), 'Tracked source must be clean'
ci = json.loads(a.ci_evidence.read_text())
assert ci['head_sha'] == source and ci['conclusion'] == 'success', 'Require actual successful Android workflow for this exact source'
assert all(j['conclusion'] == 'success' for j in ci['jobs'])
assert {36, 37} <= {d['api'] for d in ci['device_results']}, 'Require API36 and API37 evidence'
assert all(d['tests'] >= 17 and d['failures'] == d['errors'] == d['skipped'] == 0 for d in ci['device_results'])
assert any(j['name'] == 'build' for j in ci['jobs'])
inv = json.loads(a.inventory.read_text())
advisories = json.loads(a.advisories.read_text())
assert advisories['inventory_sha256'] == hashlib.sha256(a.inventory.read_bytes()).hexdigest()
assert advisories['positive_control']['passed'] and advisories['source_commit'] == source
matches = [(m, v) for m in advisories['results'] for v in m['advisories'] if not v.get('withdrawn_at')]
assert not any(v['severity'] in ('high', 'critical') for _, v in matches), 'High/critical candidate gate'
apk = ROOT/'app/build/outputs/apk/release/app-release-unsigned.apk'
sdk = Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
badging = subprocess.check_output([str(sdk/'aapt'), 'dump', 'badging', str(apk)], text=True)
assert "package: name='dev.abhik.mockgps'" in badging
assert "versionCode='5'" in badging and "versionName='0.4.0-beta.1'" in badging
sig = subprocess.run([str(sdk/'apksigner'), 'verify', str(apk)], capture_output=True, text=True)
assert sig.returncode != 0, 'Expected unsigned release variant; do not relabel a debug-signed APK'
# An unsigned candidate must not contain a JAR signing certificate either.
import zipfile
with zipfile.ZipFile(apk) as z:
    assert not any(re.match(r'META-INF/.*\.(RSA|DSA|EC)$', n, re.I) for n in z.namelist())
suites = [E.parse(f).getroot() for f in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit = {k: sum(int(r.get(k, 0)) for r in suites) for k in ('tests','failures','errors','skipped')}
assert unit['tests'] >= 82 and unit['failures'] == unit['errors'] == unit['skipped'] == 0
out = a.output.resolve(); out.mkdir(parents=True, exist_ok=True)
name = 'MockGPS-0.4.0-beta.1-unsigned.apk'
shutil.copyfile(apk, out/name)
shutil.copyfile('tasker/MockGPS.prj.xml', out/'MockGPS.prj.xml')
shutil.copyfile('tasker/README.md', out/'TASKER_IMPORT.md')
shutil.copyfile('docs/BETA_RELEASE.md', out/'RELEASE_NOTES.md')
shutil.copyfile('LICENSE', out/'LICENSE.txt')
shutil.copyfile(a.inventory, out/'dependency-inventory.json')
shutil.copyfile(a.advisories, out/'advisory-report.json')
shutil.copyfile(a.ci_evidence, out/'ci-evidence.json')
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
bom = dict(bomFormat='CycloneDX', specVersion='1.6', version=1,
           metadata=dict(timestamp=now, properties=[dict(name='mockgps:source', value=source),
             dict(name='mockgps:coverage', value='Recorded resolved Maven scopes; excludes native/shaded/SDK/firmware/unknown advisories')]),
           components=[dict(type='library', group=m['name'].split(':')[0], name=m['name'].split(':')[1],
             version=m['version'], purl='pkg:maven/'+m['name'].replace(':','/')+'@'+m['version'],
             properties=[dict(name='mockgps:resolved-scopes',value=','.join(m['scopes']))]) for m in inv['modules']])
(out/'sbom.cdx.json').write_text(json.dumps(bom, indent=2)+'\n')
assets = [dict(name=f.name, bytes=f.stat().st_size, sha256=hashlib.sha256(f.read_bytes()).hexdigest())
          for f in sorted(out.iterdir()) if f.is_file() and f.name not in ('release-manifest.json','SHA256SUMS')]
manifest = dict(schema=1, version='0.4.0-beta.1', version_code=5, source_commit=source,
 application_id='dev.abhik.mockgps', source_namespace='dev.burixon.mockgps', created_at_utc=now,
 variant='unsigned release candidate', signing=dict(status='UNSIGNED', signer_certificate_sha256=None,
 reason='No authorized persistent release key securely provided; not installable until owner signs'),
 published=False, stable_production=False, tools=dict(java=subprocess.check_output([os.environ['JAVA_HOME']+'/bin/javac','-version'],text=True).strip(),
 gradle='9.6.0',agp='9.2.1',compile_sdk=36,target_sdk=36,min_sdk=29,build_tools='36.0.0'),
 unit_tests=unit,github_android=ci,dependencies=dict(coordinates=len(inv['modules']),
 findings=[dict(package=m['name'],version=m['version'],id=v['ghsa_id'],severity=v['severity']) for m,v in matches],
 limits=advisories['limitations']),
 tasker=dict(status='EXPERIMENTAL; actual Tasker import NOT RUN',schema_fixture='Captured MIT-licensed Java export; schema comparison and JS bridge model passed'),
 hardware=dict(samsung_matrix='24 cases NOT RUN as complete instrumented cases',user_reports='Partial manual acceptance recorded separately; no fresh isMock=false proof'),
 release_gates=dict(dependency_review='BLOCKED: repository dependency graph/permission support',persistent_signing='BLOCKED: no provided release credential',mandatory_hardware='BLOCKED: incomplete physical matrix'),
 assets=assets,source_url='https://github.com/abhi-k9/MockGPS/tree/'+source,
 attestation='Self-recorded build/CI evidence, not a cryptographic SLSA or publisher-signing attestation')
(out/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(out/'SHA256SUMS').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n'
 for f in sorted(out.iterdir()) if f.is_file() and f.name!='SHA256SUMS'))
print(json.dumps(dict(output=str(out),source_commit=source,apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),signing='UNSIGNED'),indent=2))
