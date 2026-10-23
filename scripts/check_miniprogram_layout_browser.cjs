// Geometry regression for the native WXSS with representative list fixtures.
// Chrome models the WebView layout only; it is not a WeChat renderer/device test.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const root=path.resolve(__dirname,'..'), out=path.join(root,'.local/native-layout');
const postcss=require(path.join(root,'frontend/node_modules/postcss'));
fs.mkdirSync(out,{recursive:true});
function readStyle(relative, seen=new Set()) {
  const absolute=path.resolve(root,'miniprogram',relative);
  assert(!seen.has(absolute),'Cyclic WXSS import');
  const next=new Set(seen);next.add(absolute);
  return fs.readFileSync(absolute,'utf8').replace(/@import\s+["']([^"']+)["'];/g,(_,imported)=>
    readStyle(path.relative(path.join(root,'miniprogram'),path.resolve(path.dirname(absolute),imported)),next));
}
const component=readStyle('components/business-surface/index.wxss');
const componentTemplate=fs.readFileSync(path.join(root,'miniprogram/components/business-surface/index.wxml'),'utf8');
const formTextarea=componentTemplate.match(/<textarea\s+wx:elif="\{\{item.type==='textarea'\}\}"([\s\S]*?)\/>/);
const autoHeight=formTextarea&&/auto-height="\{\{true\}\}"/.test(formTextarea[1]);
const tabBar=readStyle('custom-tab-bar/index.wxss');
function css(source,width,inset,scope) {
  const parsed=postcss.parse(source.replace(/constant\(safe-area-inset-bottom\)|env\(safe-area-inset-bottom\)/g,inset+'px')
    .replace(/([\d.]+)rpx/g,(_,value)=>(Number(value)*width/750)+'px'));
  // Native components have isolated styles; model the same boundary in this fixture.
  parsed.walkRules(rule=>{rule.selectors=rule.selectors.map(s=>scope+' '+s.replace(/\bview\b/g,':is(div,main,nav)'));});
  return parsed.toString();
}
function markup(rows,total) {
  return `<div class="native-business-root"><main class="business"><div class="heading">校园发现</div><div class="toolbar" id="scopes">
    ${['校园广场','我的发布','收藏','发布 / 申请','刷新'].map((name,i)=>`<button class="${i===1?'active':''}">${name}</button>`).join('')}
    </div>${rows===0?'<div class="muted">暂无记录</div>':Array.from({length:rows},(_,i)=>`<div class="card"><div class="row-title">校园帖子${i+1}</div><div class="muted">已发布 · 2026/10/23 09:38:18</div><div class="copy">合成浏览器帖子</div><div class="toolbar"><button>查看详情</button></div></div>`).join('')}
    <div class="toolbar pager"><button ${rows===0?'disabled':''}>上一页</button><span class="pager-summary">${rows===0?1:2} 页 · ${total} 条</span><button ${rows===0?'disabled':''}>下一页</button></div></main></div>
    <div class="native-tabbar-root"><nav class="word-mobile-tabs">${['首页','发现','＋','私信','个人'].map((name,i)=>`<button class="${i===2?'word-tab-publish':''}"><span>${i===2?'＋':'○'}</span>${i===2?'':`<span>${name}</span>`}</button>`).join('')}</nav></div>`;
}
const longText='移动端完整显示：任务说明、商品成色与交接方式均应换行展示。'.repeat(12);
const longToken='CampusPiMobileLayoutWithoutSpaces0123456789'.repeat(3);
function textMarkup(module,stage) {
  const task=module==='E', title=task?'悬赏任务':'校园二手';
  const content=stage==='form'?`<div class="card">
    <div class="field"><label>${task?'任务名称':'商品名称'}（长标题与移动端输入）</label><input value="校园${task?'悬赏任务':'二手商品'}中文输入 Aa123"/></div>
    <div class="field"><label>${task?'任务说明':'商品描述'}</label><textarea ${autoHeight?'data-auto-height':''}>${longText}</textarea></div>
    <div class="field"><label>认证校区</label><div class="picker">浙江财经大学东方学院 / ${longToken} ▾</div></div>
    <div class="field"><label>${task?'奖励积分（如10.00）':'价格（元）'}</label><input type="number" value="10.00"/></div>
    <div class="toolbar"><button class="primary">确认提交</button><button>取消</button></div></div>`:
    `<div class="toolbar search-toolbar"><input placeholder="搜索当前模块"/><button>搜索</button></div>
    <div class="card"><div class="row-title">${title}：${longToken}</div><div class="muted">发布者：${longToken} · 2026/10/23 20:16:43</div>
    <div class="price">10.00</div><div class="copy summary">${longText}</div><div class="toolbar"><button>查看详情</button></div></div>`;
  return `<div class="native-business-root"><main class="business"><div class="heading">${title}</div>
    <div class="toolbar" id="scopes">${[task?'任务广场':'在售商品','我的发布','发布 / 申请','刷新'].map(name=>`<button>${name}</button>`).join('')}</div>
    ${content}</main></div>`;
}
(async()=>{
  const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  const page=await browser.newPage(), checks=[], textChecks=[];let complete=false;
  try{
    for(const [width,height] of [[320,568],[360,740],[375,812],[390,844],[414,896],[430,932]]){
      for(const inset of [0,20,34]){
        for(const [rows,total] of [[0,'0'],[20,'38'],[20,'9999999999999999999']]){
          await page.setViewportSize({width,height});
          await page.setContent(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>
            html,body{margin:0;padding:0;font-family:Arial,"Microsoft YaHei",sans-serif}
            /* A constrained platform button width reproduces the reported wrap bug. */
            button{display:block;width:184px;margin:0 auto;font-family:inherit}button::after{border:0}
            ${css(component,width,inset,'.native-business-root')}${css(tabBar,width,inset,'.native-tabbar-root')}
          </style>${markup(rows,total)}</html>`);
          const measured=await page.evaluate(()=>{
            const rect=e=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};};
            const scope=Array.from(document.querySelectorAll('#scopes button'),rect);
            const pager=Array.from(document.querySelector('.pager').children,rect);
            window.scrollTo(0,document.documentElement.scrollHeight);
            return {scope,pager,overflow:document.documentElement.scrollWidth>innerWidth,
              pagerBottom:document.querySelector('.pager').getBoundingClientRect().bottom,
              barTop:document.querySelector('nav').getBoundingClientRect().top};
          });
          const name=`${width}x${height}; safe=${inset}; rows=${rows}; total=${total}`;
          assert(!measured.overflow,name+': no horizontal overflow');
          assert(Math.abs(measured.scope[0].top-measured.scope[1].top)<1,name+': filter buttons share a row');
          for(let i=0;i<measured.scope.length;i++) {
            const box=measured.scope[i];assert(box.left>=0 && box.right<=width+.5,name+': filter in viewport');
            for(let j=0;j<i;j++) {const other=measured.scope[j];assert(box.left>=other.right-.5||box.right<=other.left+.5||box.top>=other.bottom-.5||box.bottom<=other.top+.5,name+': filters do not overlap');}
          }
          assert(measured.pager[0].right<=measured.pager[1].left+.5 && measured.pager[1].right<=measured.pager[2].left+.5,name+': three distinct pagination columns');
          assert(Math.abs(measured.pager[0].width-measured.pager[2].width)<1,name+': previous/next equal widths');
          assert(Math.abs(measured.pager[0].top-measured.pager[2].top)<1,name+': previous/next share a row');
          assert(measured.pagerBottom<=measured.barTop+1,name+': pagination clears fixed bar at maximum scroll');
          checks.push({width,height,inset,rows,total,status:'PASS',pager_clearance:Math.round((measured.barTop-measured.pagerBottom)*100)/100});
          if(inset===34 && total==='38')await page.screenshot({path:path.join(out,`list-${width}.png`)});
          if(inset===34 && rows===0)await page.screenshot({path:path.join(out,`empty-${width}.png`)});
        }
      }
    }
    for(const [width,height] of [[320,568],[360,740],[375,812],[390,844],[414,896],[430,932]]) {
      for(const fontScale of [1,1.3,1.6]) {
        for(const module of ['C','E']) {
          for(const stage of ['list','form']) {
            await page.setViewportSize({width,height});
            // Model a fixed native input box separately from font scaling: padding
            // must not consume the text area as in the reported clipped placeholder.
            const scaled=postcss.parse(css(component,width,34,'.native-business-root'));
            scaled.walkDecls('font-size',decl=>{decl.value=decl.value.replace(/([\d.]+)px/g,(_,v)=>Number(v)*fontScale+'px');});
            await page.setContent(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>
              html,body{margin:0;padding:0;font-family:Arial,"Microsoft YaHei",sans-serif}
              button{display:block;width:184px;margin:0 auto;font-family:inherit}input{height:1.4rem}input,textarea{font-family:inherit}
              ${scaled.toString()}
            </style>${textMarkup(module,stage)}</html>`);
            // Model the native auto-height option present in the actual template.
            // Browser textarea does not implement that WeChat property itself.
            await page.evaluate(()=>document.querySelectorAll('textarea[data-auto-height]').forEach(e=>{
              e.style.height='auto';
              const s=getComputedStyle(e);
              e.style.height=(e.scrollHeight+parseFloat(s.borderTopWidth)+parseFloat(s.borderBottomWidth))+'px';
            }));
            const measured=await page.evaluate(()=>{
              const rect=e=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};};
              const inputs=Array.from(document.querySelectorAll('input'),e=>{
                const s=getComputedStyle(e), box=rect(e), font=parseFloat(s.fontSize);
                return {...box,font,available:box.height-parseFloat(s.paddingTop)-parseFloat(s.paddingBottom)-parseFloat(s.borderTopWidth)-parseFloat(s.borderBottomWidth),
                  line:parseFloat(s.lineHeight)||font*1.5};
              });
              const text=Array.from(document.querySelectorAll('.row-title,.muted,.summary,.picker,.field label,button,textarea'),e=>
                ({...rect(e),selector:e.className||e.tagName,clipped:e.scrollHeight>e.clientHeight+2||e.scrollWidth>e.clientWidth+2}));
              const search=document.querySelector('.search-toolbar');
              return {inputs,text,overflow:document.documentElement.scrollWidth>innerWidth,
                search:search?Array.from(search.children,rect):null};
            });
            const name=`${module}/${stage}; width=${width}; font=${fontScale}`;
            assert(!measured.overflow,name+': no horizontal overflow');
            for(const input of measured.inputs) {
              assert(input.available>=input.line-1,name+': complete input text line fits without clipping');
              assert(input.height>=40,name+': input remains usable on narrow screens');
              assert(input.left>=0&&input.right<=width+.5,name+': input in viewport');
            }
            for(const text of measured.text) {
              assert(!text.clipped,name+': no clipped '+text.selector);
              assert(text.left>=0&&text.right<=width+.5,name+': text in viewport');
            }
            if(measured.search)assert(Math.abs(measured.search[0].top-measured.search[1].top)<1&&Math.abs(measured.search[0].height-measured.search[1].height)<1,name+': search input and button align');
            textChecks.push({width,height,module,stage,font_scale:fontScale,status:'PASS',inputs:measured.inputs});
            if(fontScale===1&&(width===320||width===375))await page.screenshot({path:path.join(out,`${module}-${stage}-${width}.png`),fullPage:true});
          }
        }
      }
    }
    complete=true;console.log(JSON.stringify({status:'PASS',scenarios:checks.length+textChecks.length,list_geometry_checks:checks.length,text_checks:textChecks.length,widths:[320,360,375,390,414,430],safe_areas:[0,20,34],font_scales:[1,1.3,1.6],native_renderer_verified:false}));
  }finally{
    if(!complete)await page.screenshot({path:path.join(out,'incomplete.png')});
    fs.writeFileSync(path.join(out,'geometry.json'),JSON.stringify({status:complete?'PASS':'FAIL',scope:'Actual WXSS geometry in a browser fixture with simulated platform defaults; not native rendering',checks,text_checks:textChecks,native_renderer_verified:false,device_run:false},null,2));
    await browser.close();
  }
})().catch(error=>{console.error(error.message);process.exitCode=1});
