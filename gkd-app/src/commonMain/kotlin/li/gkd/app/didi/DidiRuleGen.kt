package li.gkd.app.didi

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * flow.js rule generator (layered config).
 * Layered: each layer has conditions + action, emits a validated flow.js; screenshot is one global switch.
 * 6 layers: 1 junk pickup 2 unreachable drop-off 3 too cheap 4 hour range (multi)
 *      5 near order (dist<X, price>Y) 6 high price; otherwise pass
 */
fun Routing.registerDidiRuleGenRoute() {
    get("/didi/rules") { call.respondText(ContentType.Text.Html) { RULE_GEN_HTML } }
}

private const val RULE_GEN_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>flow.js rule generator</title>
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
<div class="h"><b>flow.js rule generator (layered, first match wins)</b></div>

<div class="help">
<b>Notes</b><br>
&middot; multiple keywords separated by commas (half/full width, ideographic comma or space)<br>
&middot; hour range uses integers, e.g. <b>12-16</b>; multiple ranges <b>12-16,20-23</b><br>
&middot; empty layer = skipped (moves to next layer); numeric 0 also counts as skipped<br>
&middot; layer 4 needs <b>enabled</b> checked, otherwise the whole layer is skipped<br>
&middot; screenshot is a global switch: on = capture on any layer hit, off = never<br>
&middot; after a grab hit the flow waits a few seconds to confirm the result page
</div>

<div class="gshot"><input type="checkbox" id="gshot" checked> <label style="min-width:auto">screenshot switch (on = capture on any hit; off = never)</label></div>

<div class="ly"><h3><span class="n">1</span>junk pickup (addr contains keyword &rarr; action)</h3>
<div class="gshot" style="border:1px solid #444;border-radius:8px;padding:10px;margin:10px 0">
 <b style="color:#fbbf24">Rest window (mandatory rest, highest priority)</b>
 <div style="margin:4px 0 8px;color:#888;font-size:12px">
  syntax: <code>19+8</code> = start 19:00 for 8h (to 03:00 next day); or multi <code>19-24,0-3</code><br>
  while active, if the <b>departure time</b> or the <b>current time</b> falls in the window, skip.
 </div>
 <div class="row"><label>rest window</label>
 <input type="text" id="resthr" value="" placeholder="19+8 or 19-24,0-3"></div>
 <div class="row"><label style="min-width:auto"><input type="checkbox" id="reston" checked> enabled</label>
 <span style="color:#888;font-size:12px">action is fixed to skip</span></div>
</div>
<div class="row"><label>keywords</label><input type="text" id="l1kw" value=""></div>
<div class="row"><label>action</label><select id="l1act"><option value="no_grab">skip</option><option value="grab">grab</option><option value="pass">pass</option></select></div></div>

<div class="ly"><h3><span class="n">2</span>unreachable drop-off (addr contains keyword &rarr; action)</h3>
<div class="row"><label>keywords</label><input type="text" id="l2kw" value=""></div>
<div class="row"><label>action</label><select id="l2act"><option value="no_grab">skip</option><option value="grab">grab</option><option value="pass">pass</option></select></div></div>

<div class="ly"><h3><span class="n">3</span>too cheap (price &lt; threshold; 0 = skip)</h3>
<div class="row"><label>price &lt;</label><input type="number" id="l3p" value="80" step="1">
<select id="l3act"><option value="no_grab">skip</option><option value="grab">grab</option><option value="pass">pass</option></select></div></div>

<div class="ly"><h3><span class="n">4</span>hour range (same-day hours &rarr; action; multi-range)</h3>
<div class="row"><label>hour range</label><input type="text" id="l4hr" value="" placeholder="e.g. 12-16 or 12-16,20-23"></div>
<div class="row"><label>action</label><select id="l4act"><option value="no_grab">skip</option><option value="grab">grab</option><option value="pass">pass</option></select>
<label style="min-width:auto"><input type="checkbox" id="l4on">enabled</label></div></div>

<div class="ly"><h3><span class="n">5</span>near order (dist &lt; X and price &gt; Y &rarr; grab; 0 = skip)</h3>
<div class="row"><label>dist &lt;</label><input type="number" id="l5d" value="0" step="1"><label>km, price &gt;</label><input type="number" id="l5p" value="0" step="1"></div>
<div class="row"><label>action</label><select id="l5act"><option value="grab">grab</option><option value="no_grab">skip</option><option value="pass">pass</option></select></div></div>

<div class="ly"><h3><span class="n">6</span>high price (price &gt; threshold &rarr; grab; 0 = skip)</h3>
<div class="row"><label>price &gt;</label><input type="number" id="l6p" value="0" step="1">
<select id="l6act"><option value="grab">grab</option><option value="no_grab">skip</option><option value="pass">pass</option></select></div></div>

<div class="ly"><h3>grab confirmation</h3>
<div class="row"><label>wait</label><input type="number" id="gwm" value="3000" step="500"><label>ms, then check result page</label></div></div>

<div class="btns">
<button class="btn" onclick="gen()">Generate</button>
<button class="btn y" onclick="valid()">Validate</button>
<button class="btn" onclick="save()">Save (applies next card)</button>
</div>
<div class="msg hide" id="msg"></div>
<p class="t">Output appears below. Review, then Save. Saving hot-reloads; a syntax error rolls back to the last good version.</p>
<textarea id="out" spellcheck="false" placeholder="click Generate to preview"></textarea>
<script>
function V(id){var e=document.getElementById(id);return e?(e.type==='checkbox'?e.checked:e.value):'';}
function msg(t,ok){var m=document.getElementById('msg');m.className='msg '+(ok?'ok':'err');m.textContent=t;}
function kws(id){return String(V(id)).split(/[,，、\s]+/).filter(function(s){return s.length>0;});}
function actOf(id){return V(id);}
function shotOn(){return V('gshot');}   // global screenshot switch

function validate(){
  var errs=[],warns=[];
  var p3=Number(V('l3p')),p5=Number(V('l5p')),p6=Number(V('l6p'));
  if(!(p3>0)&&actOf('l3act')!=='pass')errs.push('layer3 price threshold must be > 0 (or set action to pass)');
  if(p3>0&&p6>0&&p3>=p6&&actOf('l3act')==='no_grab'&&actOf('l6act')==='grab')
    warns.push('skip threshold ('+p3+') >= grab threshold ('+p6+'); middle range will pass');
  if(actOf('l5act')==='grab'&&!(Number(V('l5d'))>0&&Number(V('l5p'))>0))errs.push('layer5 grab needs dist and price > 0 (or set action to pass)');
  var hr=String(V('l4hr'));
  if(V('l4on')&&hr.trim()){
    var segs=hr.split(/[,，]+/);
    for(var i=0;i<segs.length;i++){
      var m=segs[i].trim().match(/^(\d{1,2})\s*-\s*(\d{1,2})/);
      if(!m){errs.push('layer4 hour-range invalid: '+segs[i]+' (use 12-16)');}
      else if(Number(m[1])>=Number(m[2]))errs.push('layer4 range inverted: '+segs[i]);
    }
  }
  return {errs:errs,warns:warns};
}

// rest-window parser (same logic as generated parseRest), used to validate input
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
  // p3/p5/p6 must be declared here (missing them once caused ReferenceError / blank output)
  var p3=Number(V('l3p')),p5=Number(V('l5p')),p6=Number(V('l6p'));
  var sh=shotOn();
  var warn = v.warns.length?('⚠️ '+v.warns.join('；')):'';
  var L=[];
  L.push('// generated by rule generator '+new Date().toISOString().slice(0,19).replace('T',' '));
  L.push('function timeInfo(t){var e={day:"",hour:-1,min:-1,month:-1,dom:-1};if(!t)return e;var s=String(t);var m=s.match(/^(今天|明天|后天)[ ]*([0-9]{1,2}):([0-9]{2})/);if(m)return{day:m[1],hour:+m[2],min:+m[3],month:-1,dom:-1};m=s.match(/^([0-9]{1,2})月([0-9]{1,2})日[ ]*([0-9]{1,2}):([0-9]{2})/);if(m)return{day:"",hour:+m[3],min:+m[4],month:+m[1],dom:+m[2]};return e;}');
  L.push('function isToday(ti){if(ti.day==="今天")return true;if(ti.month>0){var n=new Date();return ti.month===(n.getMonth()+1)&&ti.dom===n.getDate();}return false;}');
  L.push('function parseRest(s){var out=[],segs=String(s||"").split(/[,，]+/);for(var i=0;i<segs.length;i++){var t=segs[i].trim();if(!t)continue;var m=t.match(/^(\\d{1,2})\\s*\\+\\s*(\\d{1,2})/);if(m){var a=+m[1],d=+m[2];if(d>0)out.push([a,(a+d)%48]);continue;}m=t.match(/^(\\d{1,2})\\s*-\\s*(\\d{1,2})/);if(m){var b=+m[2];if(b>+m[1])out.push([+m[1],b]);}}return out;}');
  L.push('function inRest(h,ws){if(!(h>=0)||!ws)return false;for(var i=0;i<ws.length;i++){var a=ws[i][0],b=ws[i][1];if(h>=a&&h<b)return true;if(h+24>=a&&h+24<b)return true;}return false;}');
  L.push('function grabCheck(card){wait('+Number(V('gwm'))+');var onPage=screenHas("接乘客");var mFrom=!!card.addrFrom&&screenHas(card.addrFrom);var mTo=!!card.addrTo&&screenHas(card.addrTo);if(onPage&&mFrom&&mTo){log("GRAB_OK "+card.timeText+" "+card.addrFrom+" -> "+card.addrTo);screenshot("grab_ok");}else{log("GRAB_MISS onPage="+onPage+" from="+mFrom+" to="+mTo);}}');
  // tap-once state: same card (from|to|time), once per window
  L.push('var __g={};');
  L.push('onCard(function(card){');
  L.push('  var ti=timeInfo(card.timeText);var price=Number(card.price);var dist=Number(card.distMe);');
  // 实时单界面没有「不抢/抢单」按钮 —— 只记录，不做任何点击决策
  L.push('  if(card.kind==="\u5b9e\u65f6\u5355"){screenshot("realtime");' +
         'log("realtime p="+price+" d="+dist+" (no action)");return;}');
  var restRaw=String(V('resthr')).trim();
  if(V('reston')&&restRaw){
    var restWin=parseRestForGen(restRaw);
    if(restWin.length){
      L.push('  var REST='+JSON.stringify(restWin)+';');
      L.push('  // LR: rest window '+restRaw+' — departure or now in window -> skip');
      L.push('  var __dep=ti.hour>=0?(ti.hour+ti.min/60):-1;');
      L.push('  var __n=new Date();var __nowH=__n.getHours()+__n.getMinutes()/60;');
      L.push('  if(inRest(__dep,REST)||inRest(__nowH,REST)){var a="no_grab";screenshot(a);'
        + 'log("rest dep="+card.timeText+" now="+__nowH.toFixed(1));tap(a);return;}');
    } else {
      warn=(warn?warn+';':'')+'rest window unparsed (use 19+8 or 19-24,0-3)';
    }
  }
  var idx=1;
  function emitLayer(cond, action, shot, extra){
    // extra like ' price="+price' appended into the log string; empty = none
    var s = '  if(' + cond + '){var a="' + action + '";';
    if(shot) s += 'screenshot(a);';
    s += 'log("rule' + idx + ' ' + action + (extra ? extra + ');' : '");');
    if(action === 'grab'){
      // grab: (1) once per card window (actionCd:0 re-fires) (2) check tap result
      s += 'var __k=(card.addrFrom||"")+"|"+(card.addrTo||"")+"|"+(card.timeText||"");';
      s += 'if(__g[__k]&&(Date.now()-__g[__k])<15000){log("rule'+idx+' already_tapped");return;}';
      // mark only on success; failed tap must be retried (button briefly tappable)
      s += 'if(tap(a)){__g[__k]=Date.now();grabCheck(card);}else{log("rule'+idx+' tap_failed");}';
    } else s += 'tap(a);';
    s += 'return;}';
    return s;
  }
  var k1=kws('l1kw');
  if(k1.length&&actOf('l1act')!=='pass'){
L.push('  // L'+idx+': junk pickup');
    L.push(emitLayer(k1.map(function(k){return 'String(card.addrFrom||"").indexOf("'+k+'")>=0';}).join('||'),actOf('l1act'),sh,''));
    idx++;
  }
  var k2=kws('l2kw');
  if(k2.length&&actOf('l2act')!=='pass'){
L.push('  // L'+idx+': unreachable drop-off');
    L.push(emitLayer(k2.map(function(k){return 'String(card.addrTo||"").indexOf("'+k+'")>=0';}).join('||'),actOf('l2act'),sh,''));
    idx++;
  }
  if(p3>0&&actOf('l3act')!=='pass'){
L.push('  // L'+idx+': price < '+p3);
    L.push(emitLayer('!isNaN(price)&&price<'+p3,actOf('l3act'),sh,' price="+price'));
    idx++;
  }
  if(V('l4on')&&String(V('l4hr')).trim()&&actOf('l4act')!=='pass'){
    var segs=String(V('l4hr')).split(/[,，]+/),cond=[];
    for(var i=0;i<segs.length;i++){var m=segs[i].trim().match(/^(\d{1,2})\s*-\s*(\d{1,2})/);if(m)cond.push('(ti.hour>='+m[1]+'&&ti.hour<'+m[2]+')');}
    if(cond.length){
      L.push('  // L'+idx+': hour range '+V('l4hr'));
      L.push(emitLayer('isToday(ti)&&('+cond.join('||')+')',actOf('l4act'),sh,' hour="+ti.hour'));
      idx++;
    }
  }
  var d5=Number(V('l5d'));
  if(d5>0&&p5>0&&actOf('l5act')!=='pass'){
L.push('  // L'+idx+': near dist<'+d5+' price>' + p5);
    L.push(emitLayer('!isNaN(dist)&&!isNaN(price)&&dist<'+d5+'&&price>'+p5,actOf('l5act'),sh,' dist="+dist+" price="+price'));
    idx++;
  }
  if(p6>0&&actOf('l6act')!=='pass'){
L.push('  // L'+idx+': price > '+p6);
    L.push(emitLayer('!isNaN(price)&&price>'+p6,actOf('l6act'),sh,' price="+price'));
    idx++;
  }
  L.push('  // otherwise: pass (record only)');
  L.push('  '+(sh?'screenshot("pass");':'')+'log("pass price="+price+" dist="+dist+" time="+card.timeText);');
  L.push('});');
  var js=L.join('\n');
  document.getElementById('out').value=js;
msg((warn?'generated with warnings: '+warn:'generated OK')+' — '+(idx-1)+' rules, review then Save', !v.warns.length);
  return js;
}

function valid(){
  var v=validate();
if(v.errs.length){msg('error: '+v.errs.join('; '),false);return;}
if(v.warns.length){msg('warning: '+v.warns.join('; ')+' (still generatable, confirm intent)',false);return;}
msg('validation OK, no conflicts',true);
}

function save(){
  var js=gen();
  if(!js)return;
msg('saved: '+t,true);
}
</script>
</body></html>"""
