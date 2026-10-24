#!/usr/bin/env python3
"""Read emulator host diagnostics from one failed API37 run; no app logcat or credentials."""
import json,os,subprocess,tempfile
from pathlib import Path
run=os.environ['DIAGNOSTICS_RUN_ID'];assert run.isdecimal()
repo=os.environ['GITHUB_REPOSITORY']
with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as folder:
    r=subprocess.run(['gh','run','download',run,'--repo',repo,'--pattern','android-instrumentation-reports-API37.0-*','--dir',folder],capture_output=True,text=True)
    if r.returncode: raise RuntimeError('Diagnostic artifact download failed; redirect URLs suppressed')
    for p in Path(folder).rglob('emulator.log'):
        lines=p.read_text(errors='replace').splitlines()
        wanted=[l for l in lines if any(w in l.lower() for w in ('fatal','panic','crash','error','memory','ram','failed','killed','vulkan','swiftshader'))]
        print('Emulator host log (filtered):')
        for l in wanted[-100:]: print(l[:500])
    for p in Path(folder).rglob('user-readiness.txt'): print('User readiness:',p.read_text().strip())

    for p in Path(folder).rglob('guest-crash-sanitized.json'):
        print('Sanitized guest crash:',p.read_text())
