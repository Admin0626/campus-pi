"""Check tracked files without printing credential values or reading ignored private config."""
from pathlib import Path
import json,re,subprocess,sys
root=Path(__file__).resolve().parents[1]
names=[name.decode('utf-8') for name in subprocess.check_output(['git','ls-files','-z'],cwd=root).split(b'\0') if name]
private={'miniprogram/config.js','miniprogram/project.config.json','miniprogram/project.private.config.json'}
issues=[]
for name in names:
 if name in private or name.startswith('.local/'):
  issues.append({'file':name,'rule':'PRIVATE_CONFIG_TRACKED'});continue
 file=root/name
 if not file.is_file():continue
 data=file.read_bytes()
 if re.search(rb'wx[0-9a-fA-F]{16}',data):issues.append({'file':name,'rule':'LITERAL_WECHAT_APPID'})
 try:text=data.decode('utf-8')
 except UnicodeDecodeError:continue
 if re.search(r'''(?i)(?:appsecret|wechat_secret|wx_secret)["']?\s*[:=]\s*["'][^"'\s]+["']''',text):
  issues.append({'file':name,'rule':'LITERAL_WECHAT_SECRET'})
template=json.loads((root/'miniprogram/project.config.example.json').read_text(encoding='utf-8'))
if template.get('appid')!='':issues.append({'file':'miniprogram/project.config.example.json','rule':'TEMPLATE_APPID_NOT_EMPTY'})
source=(root/'miniprogram/config.example.js').read_text(encoding='utf-8')
for name in ['apiBaseUrl','devtoolsApiBaseUrl']:
 if not re.search(r'\b'+name+r'''\s*:\s*(?:""|'')''',source):issues.append({'file':'miniprogram/config.example.js','rule':'TEMPLATE_API_HOST_NOT_EMPTY'})
print(json.dumps({'status':'FAIL' if issues else 'PASS','tracked_files_checked':len(names),'issues':issues,'credential_values_printed':False}))
sys.exit(1 if issues else 0)
