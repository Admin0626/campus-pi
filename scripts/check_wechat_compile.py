"""Compile native templates/styles with the installed official DevTools binaries.

This verifies syntax and generated code, not an authenticated IDE or device run.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--compiler-dir', default=os.environ.get('WECHAT_COMPILER_DIR', r'D:\微信web开发者工具\code\package.nw\node_modules\wcc-exec'))
args = parser.parse_args()
compilers = Path(args.compiler_dir)
source = ROOT / 'miniprogram'
out = ROOT / '.local/auth-dev/wechat-compile'
out.mkdir(parents=True, exist_ok=True)
pages = json.loads((source / 'app.json').read_text(encoding='utf-8'))['pages']
templates = [page + '.wxml' for page in pages]
styles = sorted(str(p.relative_to(source)).replace('\\','/') for p in source.rglob('*.wxss'))
checks = []
for name in ['wcc.exe', 'wcsc.exe']:
    if not (compilers / name).is_file():
        raise SystemExit('Official compiler unavailable; specify --compiler-dir. No AppID will be invented.')
for compiler, inputs, output, extra in [
    ('wcc.exe', templates, 'templates.js', []),
    ('wcsc.exe', styles, 'styles.js', ['-js']),
]:
    result = subprocess.run([str(compilers / compiler), *extra, '-o', str(out / output), *inputs], cwd=source, capture_output=True, timeout=30)
    if result.returncode:
        (out / 'compiler-error.txt').write_bytes(result.stdout + result.stderr)
        raise SystemExit(compiler + ' failed; see private compile diagnostics')
    assert (out / output).stat().st_size > 0
    subprocess.run(['node', '--check', str(out / output)], check=True, capture_output=True, timeout=10)
    checks.append({'compiler': compiler, 'inputs': inputs, 'result': 'PASS', 'generated_js_syntax': 'PASS'})
config_path = source / 'project.config.json'
if not config_path.exists(): config_path = source / 'project.config.example.json'
configured_appid = json.loads(config_path.read_text(encoding='utf-8-sig')).get('appid', '')
report = {
    'status': 'PASS',
    'scope': 'Installed official DevTools standalone WXML/WXSS compiler only',
    'pages': len(pages),
    'checks': checks,
    'source_hashes': {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in [*templates, *styles]},
    'AppID_configured': bool(re.fullmatch(r'wx[0-9a-f]{16}', configured_appid)),
    'AppID_access_verified': False,
    'authenticated_IDE_run': False,
    'device_run': False,
}
(out / 'compile-acceptance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'status': 'PASS', 'WXML_pages': len(pages), 'WXSS_files': len(styles), 'device_run': False}))
