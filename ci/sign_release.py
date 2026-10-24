#!/usr/bin/env python3
"""Manually invoked, draft-only persistent signing; private material never becomes an asset."""
import base64,hashlib,json,os,re,subprocess,tempfile
from pathlib import Path
from release_gate import require_run, require_dependency_review, require_checkout

repo=os.environ['GITHUB_REPOSITORY']; source=os.environ['SOURCE_SHA']; run=os.environ['VALIDATION_RUN_ID']
assert re.fullmatch(r'[0-9a-f]{40}',source) and run.isdecimal(), 'Require exact source SHA/run ID'
tag='v0.4.0-beta.1'
public_env={k:v for k,v in os.environ.items() if not k.startswith('MOCKGPS_SIGNING_')}

def call(args,env=None):
    result=subprocess.run(args,capture_output=True,text=True,env=env or public_env)
    if result.returncode:raise RuntimeError(args[0]+' failed; sensitive arguments/messages and redirect URLs suppressed')
    return result.stdout

def api(path):return json.loads(call(['gh','api',path]))
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()

require_checkout(source,os.environ['GITHUB_SHA'])
validation,jobs=require_run(api,repo,source,run)
require_dependency_review(api,repo,source)
releases=[r for r in api(f'repos/{repo}/releases?per_page=100') if r['tag_name']==tag]
assert len(releases)==1, 'Require the one matching draft'
release=releases[0]
assert release['draft'] and release['prerelease'] and release['target_commitish']==source, 'Only the matching owner-reviewable draft may be updated'
secret_names=['MOCKGPS_SIGNING_KEYSTORE_BASE64','MOCKGPS_SIGNING_KEY_ALIAS','MOCKGPS_SIGNING_KEY_PASSWORD','MOCKGPS_SIGNING_STORE_PASSWORD']
assert all(os.environ.get(n) for n in secret_names), 'Required signing secret bindings are missing'
with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP'],prefix='mockgps-sign-') as folder:
    out=Path(folder)/'assets';out.mkdir()
    call(['gh','release','download',tag,'--repo',repo,'--dir',str(out)])
    for line in (out/'SHA256SUMS').read_text().splitlines():
        expected,name=line.split('  ',1);assert Path(name).name==name and re.fullmatch(r'[0-9a-f]{64}',expected)
        assert digest(out/name)==expected, 'Draft asset integrity mismatch'
    manifest=json.loads((out/'release-manifest.json').read_text())
    assert manifest['validated_head_commit']==manifest['source_commit']==source and manifest['version']=='0.4.0-beta.1'
    unsigned=out/'MockGPS-0.4.0-beta.1-unsigned.apk'; signed=out/'MockGPS-0.4.0-beta.1.apk'
    sdk=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
    badging=call([str(sdk/'aapt'),'dump','badging',str(unsigned)])
    assert "package: name='dev.abhik.mockgps'" in badging and "versionCode='5'" in badging and "versionName='0.4.0-beta.1'" in badging
    assert "targetSdkVersion:'37'" in badging and 'application-debuggable' not in badging
    keystore=Path(folder)/'private.keystore'
    aligned=Path(folder)/'aligned.apk'
    try:
        private_bytes=base64.b64decode(''.join(os.environ['MOCKGPS_SIGNING_KEYSTORE_BASE64'].split()),validate=True)
        descriptor=os.open(keystore,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(descriptor,'wb') as f:f.write(private_bytes)
        del private_bytes
        call([str(sdk/'zipalign'),'-P','16','-f','4',str(unsigned),str(aligned)])
        sign_env=public_env.copy()
        for n in ('MOCKGPS_SIGNING_KEY_PASSWORD','MOCKGPS_SIGNING_STORE_PASSWORD'):sign_env[n]=os.environ[n]
        call([str(sdk/'apksigner'),'sign','--ks',str(keystore),'--ks-key-alias',os.environ['MOCKGPS_SIGNING_KEY_ALIAS'],
              '--ks-pass','env:MOCKGPS_SIGNING_STORE_PASSWORD','--key-pass','env:MOCKGPS_SIGNING_KEY_PASSWORD',
              '--out',str(signed),str(aligned)],env=sign_env)
    finally:
        if keystore.exists():keystore.unlink()
        if aligned.exists():aligned.unlink()
    signature=call([str(sdk/'apksigner'),'verify','--verbose','--print-certs',str(signed)])
    assert 'Verifies' in signature and 'CN=Android Debug' not in signature, 'Refusing to label a debug certificate as release signing'
    certificate=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})',signature).group(1).lower()
    expected=json.loads(Path('ci/release-policy.json').read_text())['signer_certificate_sha256']
    assert certificate==expected, 'Signing certificate differs from the authorized persistent identity'
    call([str(sdk/'zipalign'),'-c','-P','16','4',str(signed)])
    (out/'signature.txt').write_text(signature)
    receipt=dict(source_commit=manifest['source_commit'],validated_head_commit=source,validation_url=validation['html_url'],unsigned_apk_sha256=digest(unsigned),
                 signed_apk_sha256=digest(signed),signer_certificate_sha256=certificate,
                 variant='non-debug release, signed with authorized repository keystore',draft=True,
                 signing_workflow_commit=os.environ['GITHUB_SHA'],signing_run_url=f'https://github.com/{repo}/actions/runs/{os.environ["GITHUB_RUN_ID"]}')
    (out/'signing-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    manifest.update(variant='signed beta release candidate',signing=dict(status='SIGNED',signer_certificate_sha256=certificate,
                    signed_apk_sha256=digest(signed),unsigned_apk_sha256=digest(unsigned),receipt='signing-receipt.json'))
    manifest['release_gates']['persistent_signing']='READY: authorized repository key used and signature verified'
    manifest['assets']=[dict(name=f.name,bytes=f.stat().st_size,sha256=digest(f)) for f in sorted(out.iterdir())
                        if f.is_file() and f.name not in ('release-manifest.json','SHA256SUMS')]
    (out/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (out/'SHA256SUMS').write_text(''.join(digest(f)+'  '+f.name+'\n' for f in sorted(out.iterdir()) if f.is_file() and f.name!='SHA256SUMS'))
    # Update only known draft assets. No release/tag publication operation exists in this workflow.
    updated=['MockGPS-0.4.0-beta.1.apk','signature.txt','signing-receipt.json','release-manifest.json','SHA256SUMS']
    live=api(f'repos/{repo}/releases/{release["id"]}')
    assert live['draft'] and live['prerelease'] and live['target_commitish']==source
    call(['gh','release','upload',tag,'--repo',repo,'--clobber',*[str(out/n) for n in updated]])
    remote=api(f'repos/{repo}/releases/{release["id"]}/assets')
    byasset={a['name']:a for a in remote}
    if all(byasset[n].get('digest') for n in updated):
        assert all(byasset[n]['digest']=='sha256:'+digest(out/n) for n in updated), 'Uploaded asset digest mismatch'
    else:
        verified=Path(folder)/'verified';verified.mkdir()
        args=['gh','release','download',tag,'--repo',repo,'--dir',str(verified)]
        for n in updated: args += ['--pattern',n]
        call(args)
        assert all(digest(verified/n)==digest(out/n) for n in updated), 'Downloaded asset integrity mismatch'
    receipt['draft_url']=release['html_url']
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f: f.write('Signed private draft candidate only. Automated exact-SHA gates passed; physical-device gates remain as recorded; no publication or merge.\n')
    print('::notice title=Signed beta candidate::'+json.dumps(receipt,separators=(',',':')))
