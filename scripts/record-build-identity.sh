#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
output=app/build/development-candidate
mkdir -p "$output"
apk=app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$apk" > "$output/signature.txt"
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$apk" > "$output/apk-badging.txt"
python3 - <<'PY'
from pathlib import Path
import hashlib,json,re,subprocess
import xml.etree.ElementTree as E
from collections import Counter
apk=Path('app/build/outputs/apk/debug/app-debug.apk')
out=Path('app/build/development-candidate')
badging=(out/'apk-badging.txt').read_text()
package=re.search(r"package: name='([^']+)'",badging).group(1)
version_code=int(re.search(r"versionCode='(\d+)'",badging).group(1))
version_name=re.search(r"versionName='([^']+)'",badging).group(1)
assert package=='dev.abhik.mockgps',package
assert (version_code,version_name)==(5,'0.4.0-beta.1'),(version_code,version_name)
signature=(out/'signature.txt').read_text()
assert 'Verifies' in signature
cert=re.search(r'Signer #1 certificate SHA-256 digest: (\S+)',signature).group(1)
identity=dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
    tracked_worktree_clean=not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],text=True).strip(),
    application_id=package,version_code=version_code,version_name=version_name,
    apk=str(apk),bytes=apk.stat().st_size,
    sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),signer_certificate_sha256=cert,
    signing='Android development/debug certificate; unsigned release is not an installable release')
assert identity['tracked_worktree_clean'],'Build identity must describe a clean reviewed source commit'
suites=[E.parse(p).getroot() for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
identity['unit_tests']={k:sum(int(r.get(k,0)) for r in suites) for k in ['tests','failures','errors','skipped']}
assert identity['unit_tests']['tests']>0 and all(identity['unit_tests'][k]==0 for k in ['failures','errors','skipped'])
issues=E.parse('app/build/reports/lint-results-debug.xml').getroot().findall('issue')
identity['lint']=dict(Counter(i.get('severity') for i in issues))
identity['lint_issues']=[dict(id=i.get('id'),severity=i.get('severity'),message=i.get('message')) for i in issues]
assert identity['lint'].get('Error',0)==identity['lint'].get('Fatal',0)==0
(out/'build-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
print(json.dumps(identity,indent=2))
# Public provenance only; no private key, credential, genuine coordinate or signed download URL.
if __import__('os').environ.get('GITHUB_ACTIONS')=='true':
 payload=json.dumps(identity,separators=(',',':')).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
 print('::notice title=Development candidate provenance::'+payload)
PY
