package li.gkd.app.didi

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * flow.js 规则生成器（傻瓜式分层配置）。
 * [stated] 用户：按经验分层，每层填条件+动作，自动生成已校验的 flow.js；截图用一个全局开关
 * 6层：①屏蔽垃圾出发地 ②屏蔽去不了的目的地 ③低价不抢 ④时间段过滤(多段)
 *     ⑤近单抢(距离<X且价格>Y) ⑥高价必抢；其余 pass
 */
fun Routing.registerDidiRuleGenRoute() {
    get("/didi/rules") { call.respondText(ContentType.Text.Html) { RULE_GEN_HTML } }
}

private const val RULE_GEN_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>flow.js 规则生成器</title>
<style>
*{box-sizing:border-box}
body{font-family:system-ui;margin:0;background:#0f1115;color:#e6e6e6;padding-bottom:80px}
.h{padding:14px 16px;background:#1a1d24;position:sticky;top:0;z-index:5}
.h b{font-size:17px}
.help{margin:10px 12px;padding:10px 12px;background:#12141a;border:1px solid #2a2e37;border-radius:8px;color:#8b93a7;font-size:12px;line-height:1.8}
.help b{color:#e6e6e6}
.ly{background:#1a1d24;border-radius:12px;padding:12px;margin:10px 12px}
.ly h3{margin:0 0 8px;font-size:14px;color:#4d8bff;display:flex;align-items:center;gap:6px}
.ly h3 .n{background:#2b6fff;color:#fff;border-radius:50%;width:20px;height:20px;display:inline-flex;align-items:center;justify-content:center;font-size:12px}
.row{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin:6px 0}
.row label{color:#8b93a7;font-size:13px;min-width:52px}
input[type=text],input[type=number]{background:#12141a;color:#e6e6e6;border:1px solid #2a2e37;border-radius:6px;padding:6px 8px;font-size:14px}
input[type=number]{width:70px}
input[type=text]{flex:1;min-width:120px}
select{background:#12141a;color:#e6e6e6;border:1px solid #2a2e37;border-radius:6px;padding:6px;font-size:14px}
.gshot{display:flex;align-items:center;gap:6px;margin:10px 12px;color:#e6e6e6;font-size:14px}
.btns{padding:12px;display:flex;gap:8px;flex-wrap:wrap}
.btn{background:#2b6fff;color:#fff;border:0;padding:10px 18px;border-radius:8px;font-size:15px;cursor:pointer}
.btn.y{background:#8a6d1f}
.msg{margin:0 12px;padding:10px;border-radius:8px;font-size:13px;line-height:1.6}
.msg.err{background:#3a1a1f;color:#ffb4c0}.msg.ok{background:#152a1c;color:#8fe0a8}.msg.hide{display:none}
textarea{width:calc(100% - 24px);margin:12px;height:38vh;background:#12141a;color:#e6e6e6;border:1px solid #2a2e37;border-radius:8px;padding:12px;font:12px/1.5 ui-monospace,Menlo,monospace;box-sizing:border-box}
.t{color:#8b93a7;font-size:12px;margin:0 12px}
</style></head>
<body>
<div class="h"><b>flow.js 规则生成器（分层，命中即停）</b></div>

<div class="help">
<b>使用说明</b><br>
· 关键词多个用逗号分隔，<b>中英文逗号都行</b>（也认顿号、空格）；时间同理<br>
· 时间段写<b>整数</b>，如 <b>12-16</b>；多段用逗号 <b>12-16,20-23</b><br>
· 某层<b>留空 = 该层跳过</b>（不判断，直接下一层）；数值层填 0 也算跳过<br>
· 第4层要<b>勾「启用」</b>才生效（不勾整层跳过）<br>
· <b>截图是全局开关</b>：开了则任意一层命中就截图，关了则都不截图（含抢单抢中截图）<br>
· 抢单层命中后会自动等几秒检测是否抢中（进入接乘客页）
</div>

<div class="gshot"><input type="checkbox" id="gshot" checked> <label style="min-width:auto">截图开关（开=任意层命中都截图；关=都不截图）</label></div>

<div class="ly"><h3><span class="n">1</span>屏蔽垃圾出发地（含任一关键词→动作）</h3>
<div class="gshot" style="border:1px solid #444;border-radius:8px;padding:10px;margin:10px 0">
 <b style="color:#fbbf24">强休时段（强制休息 · 优先级最高，先于所有其他规则）</b>
 <div style="margin:4px 0 8px;color:#888;font-size:12px">
  写法：<code>19+8</code> = 19点起休8小时 → 到次日3点；或多段 <code>19-24,0-3</code><br>
  休息期间：<b>预约单出发时间</b> 或 <b>当前时间</b> 任一落在区间内，一律「不抢」。
 </div>
 <div class="row"><label>强休时段</label>
 <input type="text" id="resthr" value="" placeholder="19+8 或 19-24,0-3"></div>
 <div class="row"><label style="min-width:auto"><input type="checkbox" id="reston" checked> 启用</label>
 <span style="color:#888;font-size:12px">动作固定为「不抢」</span></div>
</div>
<div class="row"><label>关键词</label><input type="text" id="l1kw" value="岛,隧道,当前位置,码头"></div>
<div class="row"><label>动作</label><select id="l1act"><option value="no_grab">不抢</option><option value="grab">抢</option><option value="pass">跳过</option></select></div></div>

<div class="ly"><h3><span class="n">2</span>屏蔽去不了的目的地（含任一关键词→动作）</h3>
<div class="row"><label>关键词</label><input type="text" id="l2kw" value="机场,客运站,邮轮,口岸"></div>
<div class="row"><label>动作</label><select id="l2act"><option value="no_grab">不抢</option><option value="grab">抢</option><option value="pass">跳过</option></select></div></div>

<div class="ly"><h3><span class="n">3</span>低价不抢（价格 &lt; 阈值，填0=跳过）</h3>
<div class="row"><label>价格&lt;</label><input type="number" id="l3p" value="80" step="1">
<select id="l3act"><option value="no_grab">不抢</option><option value="grab">抢</option><option value="pass">跳过</option></select></div></div>

<div class="ly"><h3><span class="n">4</span>时间段过滤（当天时段→动作，可多段）</h3>
<div class="row"><label>时段</label><input type="text" id="l4hr" value="" placeholder="如 12-16 或 12-16,20-23"></div>
<div class="row"><label>动作</label><select id="l4act"><option value="no_grab">不抢</option><option value="grab">抢</option><option value="pass">跳过</option></select>
<label style="min-width:auto"><input type="checkbox" id="l4on">启用</label></div></div>

<div class="ly"><h3><span class="n">5</span>近单抢（距我 &lt; X 且 价格 &gt; Y → 抢单；填0=跳过）</h3>
<div class="row"><label>距我&lt;</label><input type="number" id="l5d" value="20" step="1"><label>km，价格&gt;</label><input type="number" id="l5p" value="100" step="1"></div>
<div class="row"><label>动作</label><select id="l5act"><option value="grab">抢</option><option value="no_grab">不抢</option><option value="pass">跳过</option></select></div></div>

<div class="ly"><h3><span class="n">6</span>高价必抢（价格 &gt; 阈值 → 抢单；填0=跳过）</h3>
<div class="row"><label>价格&gt;</label><input type="number" id="l6p" value="200" step="1">
<select id="l6act"><option value="grab">抢</option><option value="no_grab">不抢</option><option value="pass">跳过</option></select></div></div>

<div class="ly"><h3>抢单后抢中检测</h3>
<div class="row"><label>等待</label><input type="number" id="gwm" value="3000" step="500"><label>毫秒后检测接乘客页</label></div></div>

<div class="btns">
<button class="btn" onclick="gen()">生成 JS</button>
<button class="btn y" onclick="valid()">校验矛盾</button>
<button class="btn" onclick="save()">保存(下一单生效)</button>
</div>
<div class="msg hide" id="msg"></div>
<p class="t">生成后自动填入下方，检查无误点「保存」。保存即热更新，下一单生效；语法错会自动回退上一版。</p>
<textarea id="out" spellcheck="false" placeholder="点「生成 JS」后在此预览"></textarea>
<script>
function V(id){var e=document.getElementById(id);return e?(e.type==='checkbox'?e.checked:e.value):'';}
function msg(t,ok){var m=document.getElementById('msg');m.className='msg '+(ok?'ok':'err');m.textContent=t;}
function kws(id){return String(V(id)).split(/[,，、\s]+/).filter(function(s){return s.length>0;});}
function actOf(id){return V(id);}
function shotOn(){return V('gshot');}   // 全局截图开关

function validate(){
  var errs=[],warns=[];
  var p3=Number(V('l3p')),p5=Number(V('l5p')),p6=Number(V('l6p'));
  if(!(p3>0)&&actOf('l3act')!=='pass')errs.push('第3层低价阈值需>0（或把动作设为"跳过"）');
  if(p3>0&&p6>0&&p3>=p6&&actOf('l3act')==='no_grab'&&actOf('l6act')==='grab')
    warns.push('低价不抢阈值('+p3+') >= 高价必抢阈值('+p6+')，中间区间可能无规则覆盖(会 pass)');
  if(actOf('l5act')==='grab'&&!(Number(V('l5d'))>0&&Number(V('l5p'))>0))errs.push('第5层"抢"需距我和价格都>0（或设为"跳过"）');
  var hr=String(V('l4hr'));
  if(V('l4on')&&hr.trim()){
    var segs=hr.split(/[,，]+/);
    for(var i=0;i<segs.length;i++){
      var m=segs[i].trim().match(/^(\d{1,2})\s*-\s*(\d{1,2})/);
      if(!m){errs.push('第4层时段格式错: '+segs[i]+' (应如 12-16)');}
      else if(Number(m[1])>=Number(m[2]))errs.push('第4层时段起止倒置: '+segs[i]);
    }
  }
  return {errs:errs,warns:warns};
}

// 强休时段解析(与生成出的 parseRest 同逻辑, 生成期用来校验格式)
function parseRestForGen(s){
  var out=[],segs=String(s||'').split(/[,，]+/);
  for(var i=0;i<segs.length;i++){
    var t=segs[i].trim(); if(!t)continue;
    var m=t.match(/^(\d{1,2})\s*\+\s*(\d{1,2})/);
    if(m){var a=+m[1],d=+m[2]; if(d>0)out.push([a,(a+d)%48]); continue;}
    m=t.match(/^(\d{1,2})\s*-\s*(\d{1,2})/);
    if(m){var a2=+m[1],b2=+m[2]; if(b2>a2)out.push([a2,b2]);}
  }
  return out;
}
function gen(){
  var v=validate();
  if(v.errs.length){msg('❌ '+v.errs.join('；'),false);return null;}
  // ★关键: p3/p5/p6 必须在本函数声明(之前漏了导致 ReferenceError, 输出框空白)
  var p3=Number(V('l3p')),p5=Number(V('l5p')),p6=Number(V('l6p'));
  var sh=shotOn();
  var warn = v.warns.length?('⚠️ '+v.warns.join('；')):'';
  var L=[];
  L.push('// 由规则生成器生成 '+new Date().toISOString().slice(0,19).replace('T',' '));
  L.push('function timeInfo(t){var e={day:"",hour:-1,min:-1,month:-1,dom:-1};if(!t)return e;var s=String(t);var m=s.match(/^(今天|明天|后天)[ ]*([0-9]{1,2}):([0-9]{2})/);if(m)return{day:m[1],hour:+m[2],min:+m[3],month:-1,dom:-1};m=s.match(/^([0-9]{1,2})月([0-9]{1,2})日[ ]*([0-9]{1,2}):([0-9]{2})/);if(m)return{day:"",hour:+m[3],min:+m[4],month:+m[1],dom:+m[2]};return e;}');
  L.push('function isToday(ti){if(ti.day==="今天")return true;if(ti.month>0){var n=new Date();return ti.month===(n.getMonth()+1)&&ti.dom===n.getDate();}return false;}');
  L.push('function parseRest(s){var out=[],segs=String(s||"").split(/[,，]+/);for(var i=0;i<segs.length;i++){var t=segs[i].trim();if(!t)continue;var m=t.match(/^(\\d{1,2})\\s*\\+\\s*(\\d{1,2})/);if(m){var a=+m[1],d=+m[2];if(d>0)out.push([a,(a+d)%48]);continue;}m=t.match(/^(\\d{1,2})\\s*-\\s*(\\d{1,2})/);if(m){var b=+m[2];if(b>+m[1])out.push([+m[1],b]);}}return out;}');
  L.push('function inRest(h,ws){if(!(h>=0)||!ws)return false;for(var i=0;i<ws.length;i++){var a=ws[i][0],b=ws[i][1];if(h>=a&&h<b)return true;if(h+24>=a&&h+24<b)return true;}return false;}');
  L.push('function grabCheck(card){wait('+Number(V('gwm'))+');var onPage=screenHas("接乘客");var mFrom=!!card.addrFrom&&screenHas(card.addrFrom);var mTo=!!card.addrTo&&screenHas(card.addrTo);if(onPage&&mFrom&&mTo){log("GRAB_OK "+card.timeText+" "+card.addrFrom+" -> "+card.addrTo);screenshot("grab_ok");}else{log("GRAB_MISS onPage="+onPage+" from="+mFrom+" to="+mTo);}}');
  // 抢单"点一次"状态: 同一张卡(出发地|目的地|时间)15秒内只点一次
  L.push('var __g={};');
  L.push('onCard(function(card){');
  L.push('  var ti=timeInfo(card.timeText);var price=Number(card.price);var dist=Number(card.distMe);');
  var restRaw=String(V('resthr')).trim();
  if(V('reston')&&restRaw){
    var restWin=parseRestForGen(restRaw);
    if(restWin.length){
      L.push('  var REST='+JSON.stringify(restWin)+';');
      L.push('  // 层R: 强休时段 '+restRaw+' —— 出发时间或当前时间任一命中即不抢(优先级最高)');
      L.push('  var __dep=ti.hour>=0?(ti.hour+ti.min/60):-1;');
      L.push('  var __n=new Date();var __nowH=__n.getHours()+__n.getMinutes()/60;');
      L.push('  if(inRest(__dep,REST)||inRest(__nowH,REST)){var a="no_grab";screenshot(a);'
        + 'log("rest dep="+card.timeText+" now="+__nowH.toFixed(1)+" 窗口='+restRaw+'");tap(a);return;}');
    } else {
      warn=(warn?warn+';':'')+'强休时段格式没认出来(支持 19+8 或 19-24,0-3)';
    }
  }
  var idx=1;
  function emitLayer(cond, action, shot, extra){
    // extra 形如 ' price="+price' (拼进 log 字符串), 无则空串
    var s = '  if(' + cond + '){var a="' + action + '";';
    if(shot) s += 'screenshot(a);';
    s += 'log("rule' + idx + ' ' + action + (extra ? extra + ');' : '");');
    if(action === 'grab'){
      // 抢单: ①同卡15秒内只点一次(actionCd:0会反复触发) ②检查点击结果(失败不做抢中检测, 防误报)
      s += 'var __k=(card.addrFrom||"")+"|"+(card.addrTo||"")+"|"+(card.timeText||"");';
      s += 'if(__g[__k]&&(Date.now()-__g[__k])<15000){log("rule'+idx+' already_tapped");return;}';
      // ★只有点击成功才标记"已点"——失败要允许重试(按钮只在短暂窗口可点, 08:26实测第3次才ok=true)
      s += 'if(tap(a)){__g[__k]=Date.now();grabCheck(card);}else{log("rule'+idx+' tap_failed");}';
    } else s += 'tap(a);';
    s += 'return;}';
    return s;
  }
  var k1=kws('l1kw');
  if(k1.length&&actOf('l1act')!=='pass'){
    L.push('  // 层'+idx+': 出发地屏蔽');
    L.push(emitLayer(k1.map(function(k){return 'String(card.addrFrom||"").indexOf("'+k+'")>=0';}).join('||'),actOf('l1act'),sh,''));
    idx++;
  }
  var k2=kws('l2kw');
  if(k2.length&&actOf('l2act')!=='pass'){
    L.push('  // 层'+idx+': 目的地屏蔽');
    L.push(emitLayer(k2.map(function(k){return 'String(card.addrTo||"").indexOf("'+k+'")>=0';}).join('||'),actOf('l2act'),sh,''));
    idx++;
  }
  if(p3>0&&actOf('l3act')!=='pass'){
    L.push('  // 层'+idx+': 低价 <'+p3);
    L.push(emitLayer('!isNaN(price)&&price<'+p3,actOf('l3act'),sh,' price="+price'));
    idx++;
  }
  if(V('l4on')&&String(V('l4hr')).trim()&&actOf('l4act')!=='pass'){
    var segs=String(V('l4hr')).split(/[,，]+/),cond=[];
    for(var i=0;i<segs.length;i++){var m=segs[i].trim().match(/^(\d{1,2})\s*-\s*(\d{1,2})/);if(m)cond.push('(ti.hour>='+m[1]+'&&ti.hour<'+m[2]+')');}
    if(cond.length){
      L.push('  // 层'+idx+': 当天时段 '+V('l4hr'));
      L.push(emitLayer('isToday(ti)&&('+cond.join('||')+')',actOf('l4act'),sh,' hour="+ti.hour'));
      idx++;
    }
  }
  var d5=Number(V('l5d'));
  if(d5>0&&p5>0&&actOf('l5act')!=='pass'){
    L.push('  // 层'+idx+': 近单 距<'+d5+'且价>'+p5);
    L.push(emitLayer('!isNaN(dist)&&!isNaN(price)&&dist<'+d5+'&&price>'+p5,actOf('l5act'),sh,' dist="+dist+" price="+price'));
    idx++;
  }
  if(p6>0&&actOf('l6act')!=='pass'){
    L.push('  // 层'+idx+': 高价 >'+p6);
    L.push(emitLayer('!isNaN(price)&&price>'+p6,actOf('l6act'),sh,' price="+price'));
    idx++;
  }
  L.push('  // 其余 pass(只记录不点)');
  L.push('  '+(sh?'screenshot("pass");':'')+'log("pass price="+price+" dist="+dist+" time="+card.timeText);');
  L.push('});');
  var js=L.join('\n');
  document.getElementById('out').value=js;
  msg((warn?'⚠️ 生成成功(有警告: '+warn+')':'✅ 生成成功')+'，共 '+(idx-1)+' 条规则，检查后点「保存」', !v.warns.length);
  return js;
}

function valid(){
  var v=validate();
  if(v.errs.length){msg('❌ 矛盾/错误：'+v.errs.join('；'),false);return;}
  if(v.warns.length){msg('⚠️ 警告：'+v.warns.join('；')+'（仍可生成，请确认符合预期）',false);return;}
  msg('✅ 校验通过，无矛盾',true);
}

function save(){
  var js=gen();
  if(!js)return;
  fetch('/api/flow_js',{method:'POST',body:js}).then(function(r){return r.text();}).then(function(t){msg('💾 已保存：'+t,true);});
}
</script>
</body></html>"""
