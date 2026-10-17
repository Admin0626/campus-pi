const {chromium}=require('C:/Users/wlky0/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs');
const assert=require('assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try {
  const page=await browser.newPage({viewport:{width:1280,height:900}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:5187/');
  await page.getByRole('heading',{name:'校园Π开发起始页'}).waitFor();
  assert.equal(await page.locator('main li').count(),9);
  assert.match(await page.locator('.status').textContent(),/112项接口仍待开发/);
  const response=await page.request.get('http://127.0.0.1:5187/api/v1/products');
  assert.equal(response.status(),404);
  const body=await response.json();
  assert.deepEqual(Object.keys(body).sort(),['code','message','data','request_id','errors'].sort());
  assert.equal(body.code,'NOT_FOUND');assert.equal(body.data,null);assert.deepEqual(body.errors,[]);
  assert.ok(body.request_id);assert.ok(body.message);
  await page.screenshot({path:'.local/desktop.png',fullPage:true});
  await page.setViewportSize({width:320,height:800});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.screenshot({path:'.local/mobile.png',fullPage:true});
  assert.deepEqual(errors,[]);
  const result={status:'PASS',vue_app_rendered:true,module_labels:9,mobile_width:320,horizontal_overflow:false,
   development_proxy_to_spring_boot:true,unimplemented_route_http_status:404,error_contract:'PASS',browser_errors:errors,
   business_apis_implemented:0,database_connected:false};
  fs.writeFileSync('.local/frontend-smoke.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
