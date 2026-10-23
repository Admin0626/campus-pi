"""Run completed local features serially; keep fault fixtures and secrets out of shared artifacts.
Requires the existing isolated local DB, real scanner, packaged app, Playwright and test fixtures.
--stage core runs ordinary suites. --stage notification-off/on follows the documented worker restart.
Does not enable external payment, real WeChat exchange, production, or device acceptance.
"""
from pathlib import Path
import argparse,datetime,json,os,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--stage',choices=['core','notification-off','notification-on'],required=True);args=parser.parse_args()
OUT=ROOT/'.local/testing';OUT.mkdir(parents=True,exist_ok=True)
if args.stage=='core':
    cases=[('auth-http','py','check_auth_http.py'),('auth-browser','js','check_auth_browser.cjs'),
    ('content-http','py','check_admin_content_http.py'),('content-browser','js','check_admin_content_browser.cjs'),
    ('security-http','py','check_admin_security_http.py'),('security-browser','js','check_admin_security_browser.cjs'),
    ('task-http','py','check_admin_tasks_http.py'),('task-browser','js','check_admin_tasks_browser.cjs'),
    ('trade-http','py','check_admin_trade_http.py'),('trade-browser','js','check_admin_trade_browser.cjs'),
    ('dispute-http','py','check_task_disputes_http.py'),('dispute-browser','js','check_task_disputes_browser.cjs'),
    ('batch-http','py','check_admin_batches_http.py'),('batch-browser','js','check_admin_batches_browser.cjs'),
    ('analytics-browser','js','check_analytics_browser.cjs'),('layout-browser','js','check_admin_layout_browser.cjs'),
    ('admin-links-browser','js','check_admin_entry_links_browser.cjs'),('entries-browser','js','check_page_entries.cjs'),
    ('asset-updates-browser','js','check_frontend_updates_browser.cjs'),
    ('prepare-member','py','prepare_member_acceptance.py'),('member-browser','js','check_member_browser.cjs'),
    ('chat-isolation-browser','js','check_chat_isolation_browser.cjs'),('native-business-adapter','js','check_member_native_adapter.cjs'),
    ('native-account-adapter','js','check_miniprogram_adapter.cjs'),('native-components','js','check_miniprogram_components.cjs'),
    ('native-compile','py','check_wechat_compile.py')]
else:
    phase='prepare' if args.stage=='notification-off' else 'resume'
    cases=[('notification-'+phase,'py','check_admin_notifications_http.py','--phase',phase),('publication-'+phase,'py','check_notification_publication_http.py','--phase',phase)]
    if phase=='resume':cases += [('notification-browser','js','check_admin_notifications_browser.cjs'),('publication-browser','js','check_notification_publication_browser.cjs')]
report={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'stage':args.stage,'results':[],'scope':'Verified local isolated DB; synthetic fixtures; no production or device claim'}
env={**os.environ,'PYTHONIOENCODING':'utf-8'}
for name,kind,script,*extra in cases:
    log=OUT/(name+'.log');command=[sys.executable if kind=='py' else 'node',str(ROOT/'scripts'/script),*extra]
    try:
        with log.open('wb') as handle:r=subprocess.run(command,cwd=ROOT,env=env,stdout=handle,stderr=subprocess.STDOUT,timeout=240)
        status='PASS' if r.returncode==0 else 'FAIL';code=r.returncode
    except subprocess.TimeoutExpired:status='BLOCKED_TIMEOUT';code=None
    report['results'].append({'suite':name,'status':status,'exit_code':code,'private_log':log.name})
    (OUT/('blackbox-'+args.stage+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'suite':name,'status':status}),flush=True)
report['status']='PASS' if all(r['status']=='PASS' for r in report['results']) else 'NEEDS_REVIEW'
(OUT/('blackbox-'+args.stage+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
sys.exit(0 if report['status']=='PASS' else 1)
