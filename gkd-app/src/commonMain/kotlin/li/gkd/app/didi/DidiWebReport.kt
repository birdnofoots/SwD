package li.gkd.app.didi

import io.ktor.http.ContentType
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receiveText
import java.io.File
import li.gkd.app.ui.didi.DidiReportData

/**
 * DidiGKD 网页报告 + flow.js 远程定制（挂到 GKD 自带 HTTP 服务上）。
 *   GET  /didi/report          网页报告(过滤/分页/删除)
 *   GET  /didi/flow            flow.js 网页编辑器
 *   GET  /api/didi_records     JSON 记录列表
 *   GET  /api/didi_shot?name=x 截图(全尺寸)
 *   POST /api/didi_del         删单条记录+截图 {name}
 *   POST /api/didi_cleanup     批量删 {days:30} 或 {before:"日期"}
 *   GET/POST /api/flow_js      读写 flow.js
 */
fun Routing.registerDidiWebRoutes() {
    get("/didi/report") { call.respondText(ContentType.Text.Html) { DIDI_REPORT_HTML } }
    get("/didi/flow") { call.respondText(ContentType.Text.Html) { FLOW_EDITOR_HTML } }
    get("/didi/control") { call.respondText(ContentType.Text.Html) { CONTROL_HTML } }
    get("/api/didi_records") {
        val sb = StringBuilder("[")
        var first = true
        for (r in DidiReportData.load()) {
            if (!first) sb.append(",")
            first = false
            sb.append("""{"ts":"${r.ts}","price":"${r.price}","kind":"${r.kind}","verdict":"${r.verdict}","shot":"${r.shot}","addrFrom":"${r.addrFrom}","addrTo":"${r.addrTo}","distMe":"${r.distMe}","distAll":"${r.distAll}","timeText":"${r.timeText}"}""")
        }
        sb.append("]")
        call.respondText(ContentType.Application.Json) { sb.toString() }
    }
    get("/api/didi_shot") {
        val name = call.request.queryParameters["name"] ?: ""
        if (name.contains("/") || name.contains("..") || name.isEmpty()) {
            call.respondText("bad name") { ContentType.Text.Plain }; return@get
        }
        val f = File(DidiReportData.capturesDir, name)
        if (f.exists()) call.respondFile(f) else call.respondText("not found") { ContentType.Text.Plain }
    }
    // 删单条: {name:"didi_xxx.jpg"} → 删截图 + 从 records.jsonl 移除匹配行
    post("/api/didi_del") {
        val body = call.receiveText()
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: ""
        if (name.isEmpty() || name.contains("/") || name.contains("..")) {
            call.respondText("bad name") { ContentType.Text.Plain }; return@post
        }
        val shot = File(DidiReportData.capturesDir, name)
        val shotDel = if (shot.exists()) shot.delete() else false
        var removed = 0
        val f = DidiReportData.recordsFile
        if (f.exists()) {
            val keep = f.readLines().filter { l ->
                val hit = l.contains("\"shot\":\"" + name + "\"")
                if (hit) removed++
                !hit
            }
            if (removed > 0) f.writeText(keep.joinToString("\n") + "\n")
        }
        call.respondText(ContentType.Text.Plain) { "removed=" + removed + " shotDel=" + shotDel }
    }
    // 状态查询: 当前是否真实点击
    get("/api/didi_status") {
        call.respondText(ContentType.Text.Plain) {
            "tap_enabled=" + li.gkd.app.didi.DidiConfig.tapEnabled.value
        }
    }
    get("/api/flow_js") {
        val f = DidiReportData.flowJsFile
        val txt = if (f.exists()) f.readText() else "// flow.js 尚未生成"
        call.respondText(ContentType.Text.Plain) { txt }
    }
    post("/api/flow_js") {
        val body = call.receiveText()
        val f = DidiReportData.flowJsFile
        try {
            f.parentFile?.mkdirs()
            f.writeText(body)
        } catch (e: Exception) {
            // EACCES: adb push 创建的 flow.js owner=shell, App 只有读权限 → 删除重建(owner=App)
            try { f.delete() } catch (_: Exception) {}
            f.parentFile?.mkdirs()
            f.writeText(body)
        }
        call.respondText(ContentType.Text.Plain) { "saved " + body.length + " bytes, 下一单生效" }
    }
}

private const val DIDI_REPORT_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DidiGKD 决策报告</title>
<style>
*{box-sizing:border-box}
body{font-family:system-ui;margin:0;background:#0f1115;color:#e6e6e6}
.h{padding:12px 16px;background:#1a1d24;position:sticky;top:0;z-index:5}
.h .t{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}
.h b{font-size:17px}
.bar{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.f{background:#2a2e37;color:#c8cedd;border:0;padding:5px 10px;border-radius:6px;font-size:13px;cursor:pointer}
.f.on{background:#2b6fff;color:#fff}
.purge{background:#7a2330;color:#ffb4c0;border:0;padding:5px 10px;border-radius:6px;font-size:13px;cursor:pointer;margin-left:auto}
.c{padding:10px;display:flex;flex-direction:column;gap:8px;max-width:760px;margin:0 auto}
.card{background:#1a1d24;border-radius:12px;padding:10px;display:flex;gap:12px;align-items:center;position:relative}
.card img{width:96px;height:96px;object-fit:cover;border-radius:8px;background:#2a2e37;flex:none;cursor:zoom-in}
.meta{flex:1;min-width:0}
.ts{color:#8b93a7;font-size:12px}.kind{color:#4d8bff;font-size:12px;margin-left:8px}
.price{font-size:17px;font-weight:700;margin:2px 0}
.grab{color:#4d8bff}.no_grab{color:#c58bff}.pass{color:#8b93a7}
.to{color:#c8cedd;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.lbl{display:inline-block;width:18px;color:#8b93a7}
.tm{color:#c58bff;font-size:12px;margin-left:8px}
.del{position:absolute;top:8px;right:8px;background:transparent;border:0;color:#8b93a7;font-size:16px;cursor:pointer;padding:2px 6px}
.del:hover{color:#ff6b6b}
.pg{display:flex;justify-content:center;align-items:center;gap:14px;padding:14px}
.pg button{background:#2a2e37;color:#e6e6e6;border:0;padding:8px 16px;border-radius:8px;font-size:14px;cursor:pointer}
.n{color:#8b93a7;text-align:center;padding:40px}
#lb{display:none;position:fixed;inset:0;background:rgba(0,0,0,.94);z-index:99;justify-content:center;align-items:center;cursor:zoom-out}
#lb img{max-width:100%;max-height:100%;object-fit:contain}
</style></head>
<body>
<div class="h">
  <div class="t"><b>SWD Report</b><button class="f" onclick="load()">刷新</button></div>
  <div class="bar" id="bar"></div>
</div>
<div class="c" id="c"><div class="n">加载中...</div></div>
<div id="lb" onclick="this.style.display='none'"><img id="lbi"></div>
<script>
var all=[],filt={kind:'all',day:'all'},page=0,PER=30;
function pad(n){return n<10?'0'+n:''+n;}
function today(){var d=new Date();return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());}
function openLb(s){document.getElementById('lbi').src=s;document.getElementById('lb').style.display='flex';}
function setF(k,v){filt[k]=v;page=0;render();}
function prev(){if(page>0){page--;render();}}
function next(){var p=Math.ceil(filtered().length/PER);if(page<p-1){page++;render();}}
function filtered(){return all.filter(function(r){
  if(filt.kind!=='all'&&r.kind!==filt.kind)return false;
  if(filt.day==='today'&&r.ts.indexOf(today())!==0)return false;
  return true;});}
function fbtn(k,v,label){var on=filt[k]===v?' on':'';return '<button class="f'+on+'" onclick="setF(\''+k+'\',\''+v+'\')">'+label+'</button>';}
function bar(){
  var h=fbtn('kind','all','全部')+fbtn('kind','预约单','预约单')+fbtn('kind','实时单','实时单');
  h+=fbtn('day','all','全部日期')+fbtn('day','today','今天');
  h+='<button class="purge" onclick="purge30()">删30天前</button>';
  document.getElementById('bar').innerHTML=h;}
async function del(n){
  if(!confirm('删除这条记录+截图?'))return;
  await fetch('/api/didi_del',{method:'POST',body:JSON.stringify({name:n})});
  await load();}
async function purge30(){
  if(!confirm('删除30天前的所有记录+截图?'))return;
  var r=await fetch('/api/didi_cleanup',{method:'POST',body:JSON.stringify({days:30})});
  alert(await r.text());await load();}
async function load(){
  var c=document.getElementById('c');
  try{all=await (await fetch('/api/didi_records')).json();}catch(e){c.innerHTML='<div class=n>err '+e+'</div>';return;}
  page=0;render();}
function render(){
  bar();
  var c=document.getElementById('c'),list=filtered();
  if(!list.length){c.innerHTML='<div class=n>无匹配记录</div>';return;}
  var pages=Math.max(1,Math.ceil(list.length/PER));
  if(page>=pages)page=pages-1;
  var cur=list.slice(page*PER,page*PER+PER),h='';
  for(var i=0;i<cur.length;i++){
    var r=cur[i];
    var vc=r.verdict==='grab'?'grab':(r.verdict==='no_grab'?'no_grab':'pass');
    var img='';
    if(r.shot){var u='/api/didi_shot?name='+encodeURIComponent(r.shot);
      img='<img src="'+u+'" loading="lazy" onclick="openLb(this.src)">';}
    var delbtn=r.shot?'<button class="del" onclick="del(\''+r.shot+'\')" title="删除">&#128465;</button>':'';
    h+='<div class="card">'+delbtn+img+'<div class="meta">'
      +'<div><span class="ts">'+r.ts+'</span><span class="kind">'+r.kind+'</span>'
      +(r.timeText?'<span class="tm"> \u4e0a\u8f66 '+r.timeText+'</span>':'')+'</div>'
      +'<div class="price '+vc+'">'+String.fromCharCode(165)+r.price+' \u00b7 '+(r.verdict||'-')
      +(r.distMe?' \u00b7 \u8ddd\u6211'+r.distMe+'km':'')
      +(r.distAll?' \u00b7 \u884c\u7a0b'+r.distAll+'km':'')+'</div>'
      +'<div class="to"><span class="lbl">\u4ece</span>'+(r.addrFrom||'-')+'</div>'
      +'<div class="to"><span class="lbl">\u5230</span>'+(r.addrTo||'-')+'</div></div></div>';
  }
  h+='<div class="pg"><button onclick="prev()">prev</button><span>'+String.fromCharCode(160)+list.length+'条 \u00b7 第'+(page+1)+'/'+pages+'页</span><button onclick="next()">下一页</button></div>';
  c.innerHTML=h;}
load();
</script>
</body></html>"""

private const val FLOW_EDITOR_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>flow.js 定制</title>
<style>
body{font-family:system-ui;margin:0;background:#0f1115;color:#e6e6e6}
.h{padding:12px 16px;background:#1a1d24;display:flex;justify-content:space-between;align-items:center}
.h b{font-size:16px}
button{background:#2b6fff;color:#fff;border:0;padding:8px 14px;border-radius:8px;font-size:14px;margin-left:8px}
textarea{width:calc(100% - 24px);margin:12px;height:62vh;background:#12141a;color:#e6e6e6;border:1px solid #2a2e37;border-radius:8px;padding:12px;font:13px/1.5 ui-monospace,Menlo,monospace;box-sizing:border-box}
.tip{margin:0 12px;color:#8b93a7;font-size:13px;line-height:1.7}
.tip b{color:#e6e6e6}
#msg{color:#4d8bff}
</style></head>
<body>
<div class="h"><b>flow.js editor</b><span><span id="msg"></span><button onclick="load()">reload</button><button onclick="save()">保存(下一单生效)</button></span></div>
<p class="tip">
四个关键字段(在 <b>card</b> 上): <b>price</b>价格 / <b>distMe</b>距我(km) / <b>timeText</b>时间 / <b>addrFrom</b>出发地<br>
时间: <b>timeInfo(card.timeText)</b> 返回 {day:'今天'|'明天'|'后天', hour, min}<br>
常量集中在 <b>TH</b>: minPrice不抢价 / grabPrice抢单价 / grabPrice2+grabDist2近单抢 / skipHours当天时间段不抢 / skipAddr出发地关键词不抢<br>
原语: <b>tap('no_grab'|'grab')</b> 点击 / <b>screenshot(v)</b> 截图存档 / <b>log(m)</b> 日志<br>
例: 当天12~16点不抢 = skipHours:[12,16]；北站/机场不抢 = skipAddr:['北站','机场']；改完保存下一单生效，语法错自动回退上一版。
</p>
<textarea id="t" spellcheck="false"></textarea>
<script>
async function load(){var t=document.getElementById('t');t.value=await (await fetch('/api/flow_js')).text();document.getElementById('msg').textContent='';}
async function save(){var t=document.getElementById('t');var r=await fetch('/api/flow_js',{method:'POST',body:t.value});document.getElementById('msg').textContent=await r.text();}
load();
</script></body></html>"""


private const val CONTROL_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DidiGKD 控制面板</title>
<style>
*{box-sizing:border-box}
body{font-family:system-ui;margin:0;background:#0f1115;color:#e6e6e6;padding-bottom:60px}
.h{padding:14px 16px;background:#1a1d24;position:sticky;top:0;z-index:5}
.h b{font-size:17px}
.card{background:#1a1d24;border-radius:12px;padding:14px;margin:10px 12px}
.card h3{margin:0 0 10px;font-size:14px;color:#4d8bff}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:8px 0}
.row label{color:#8b93a7;font-size:13px;min-width:60px}
input[type=number]{background:#12141a;color:#e6e6e6;border:1px solid #2a2e37;border-radius:6px;padding:6px 8px;font-size:14px;width:90px}
button{background:#2b6fff;color:#fff;border:0;padding:9px 16px;border-radius:8px;font-size:14px;cursor:pointer}
button.r{background:#7a2330}
button.g{background:#1f7a3d}
.msg{margin:10px 12px;padding:10px;border-radius:8px;font-size:13px;background:#152a1c;color:#8fe0a8;min-height:18px}
pre{margin:10px 12px;background:#12141a;border:1px solid #2a2e37;border-radius:8px;padding:10px;font:12px/1.5 ui-monospace,monospace;color:#8b93a7;white-space:pre-wrap;word-break:break-all}
</style></head>
<body>
<div class="h"><b>DidiGKD 控制面板</b></div>
<div class="msg" id="msg">就绪</div>

<div class="card"><h3>真实点击开关</h3>
<div class="row">
<button class="g" onclick="tap(true)">开(真点击)</button>
<button class="r" onclick="tap(false)">关(影子模式)</button>
<button onclick="stat()">查状态</button>
</div>
<div class="row" style="color:#8b93a7;font-size:12px">关=只决策+存档不点击(P4对账用)</div>
</div>

<div class="card"><h3>存档清理</h3>
<div class="row"><label>delete older than N days</label><input type="number" id="days" value="30"><button class="r" onclick="clean()">执行清理</button></div>
<div class="row" style="color:#8b93a7;font-size:12px">截图+记录联动删(不留孤儿)</div>
</div>

<div class="card"><h3>raw response</h3><pre id="out">(尚未请求)</pre></div>

<script>
function show(t,m){document.getElementById('msg').textContent=t;if(m)document.getElementById('out').textContent=m;}
async function tap(en){
  show('切换中...');
  var r=await fetch('/api/didi_tap',{method:'POST',body:JSON.stringify({enabled:en})});
  show(en?'已开真实点击':'已切影子模式', await r.text());
}
async function clean(){
  var d=document.getElementById('days').value||'30';
  if(!confirm('删除 '+d+' 天前的所有记录+截图?'))return;
  show('清理中...');
  var r=await fetch('/api/didi_cleanup',{method:'POST',body:JSON.stringify({days:Number(d)})});
  show('已清理', await r.text());
}
async function stat(){
  show('查询中...');
  var r=await fetch('/api/didi_status');
  show('当前状态', await r.text());
}
</script>
</body></html>"""
