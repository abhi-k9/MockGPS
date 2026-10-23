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
apk=Path('app/build/outputs/apk/debug/app-debug.apk')
out=Path('app/build/development-candidate')
package=re.search(r"package: name='([^']+)'",(out/'apk-badging.txt').read_text()).group(1)
assert package=='dev.abhik.mockgps',package
signature=(out/'signature.txt').read_text()
assert 'Verifies' in signature
cert=re.search(r'Signer #1 certificate SHA-256 digest: (\S+)',signature).group(1)
identity=dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
    tracked_worktree_clean=not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],text=True).strip(),
    application_id=package,apk=str(apk),bytes=apk.stat().st_size,
    sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),signer_certificate_sha256=cert,
    signing='Android development/debug certificate; unsigned release is not an installable release')
assert identity['tracked_worktree_clean'],'Build identity must describe a clean reviewed source commit'
(out/'build-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
print(json.dumps(identity,indent=2))
PY
