// Check every available placeholder and direct page entry against the packaged app.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright')
const fs=require('node:fs'),path=require('node:path')
const root=path.resolve(__dirname,'..'),out=path.join(root,'.local/auth-dev')
const local=JSON.parse(fs.readFileSync(path.join(out,'private.json'),'utf8'))
;(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true})
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),checks=[],errors=[]
 page.on('pageerror',error=>errors.push(String(error)))
 function ok(value,name){if(!value)throw Error(name);checks.push({name,passed:true})}
 const adminEntries=[['内容审核','content'],['商品管理','products'],['悬赏任务','tasks'],['订单管理','orders'],['通知管理','notices'],['数据分析','analytics']]
 const userEntries=[['商品与分类','C'],['校园帖子','D'],['悬赏任务','E'],['任务验收与结算','F'],['订单与售后','G'],['私信与举报','H'],['通知与投递','I']]
 const routes=['/login','/register','/forgot-password','/home','/me','/me/profile','/me/password','/admin','/admin/users','/admin/users/1','/modules/B',...adminEntries.map(([,code])=>'/admin/modules/'+code),...userEntries.map(([,code])=>'/modules/'+code)]
 for(const route of routes){const response=await page.request.get('http://127.0.0.1:8080'+route);ok(response.status()===200&&(await response.text()).includes('<div id="app">'),route+' serves the packaged app')}
 await page.goto('http://127.0.0.1:8080/admin/users',{waitUntil:'networkidle'})
 await page.waitForURL('**/login');ok(true,'unauthenticated direct admin entry requires sign in')
 await page.getByLabel('账号',{exact:true}).fill(local.admin_username)
 await page.getByLabel('密码',{exact:true}).fill(local.admin_password)
 await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/admin')
 for(const [name,code]of adminEntries){await page.getByRole('button',{name,exact:true}).click();await page.getByRole('heading',{name:name+'还在开发',exact:true}).waitFor();ok(page.url().endsWith('/admin/modules/'+code),'admin '+name+' placeholder reachable')}
 await page.getByRole('button',{name:'用户端',exact:true}).click();await page.waitForURL('**/home')
 for(const [name,code]of userEntries){await page.getByRole('button',{name:name+' 还在开发',exact:false}).click();await page.getByRole('heading',{name:name+'还在开发',exact:true}).waitFor();ok(page.url().endsWith('/modules/'+code),'user '+name+' placeholder reachable');await page.getByRole('button',{name:'返回校园生活',exact:false}).click()}
 await page.getByRole('button',{name:'我的账号',exact:true}).first().click()
 await page.getByRole('button',{name:'查看认证',exact:true}).click();await page.getByRole('heading',{name:'实名与校园认证还在开发',exact:true}).waitFor();ok(true,'verification placeholder has its own name')
 await page.getByRole('button',{name:new RegExp(local.admin_username+' 退出登录')}).click();await page.waitForURL('**/login')
 await page.getByRole('button',{name:'忘记密码？',exact:true}).click();await page.getByRole('heading',{name:'密码找回还在开发',exact:true}).waitFor();ok(true,'password recovery accurately unavailable')
 ok(errors.length===0,'page entries contain no browser script errors')
 fs.writeFileSync(path.join(out,'page-entry-acceptance.json'),JSON.stringify({status:'PASS',checks,errors},null,2))
 console.log(JSON.stringify({status:'PASS',checks:checks.length}))
 await browser.close()
})().catch(error=>{console.error(error);process.exit(1)})
