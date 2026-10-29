package li.gkd.app.didi.flow

/**
 * 内置默认 flow.js（**基础版**，公开发行版）。
 *
 * 首次收到卡片时由 [FlowLoader] 写到外部文件，之后走热更新（改完不重启，下一单生效）。
 * 与旧版区别：阈值集中到顶部 `TH` 配置块，带使用说明，方便分发用户自己改。
 * 用户改过之后这个常量就不再生效（外部文件已存在）——它是"出厂模板"。
 */
object DefaultFlow {
    const val CODE = """// ==========================================================================
// DidiGKD 默认控制流 flow.js（基础版）
//
// 这个文件由 App 在第一次收到卡片时自动生成，之后支持热更新：
//   改完保存 → 下一单自动生效；语法写错会自动回退到上一版好的。
//   编辑入口：网页 http://<手机IP>:8488/didi/flow   或 App 内设置页
//
// 可用原语：
//   onCard(fn)                收到卡片时调用，fn(card)
//   tap(action)               点击，'grab'=抢 / 'no_grab'=不抢；返回是否成功
//   screenshot(tag)           截一张图（一卡一图，同签名有去重）
//   log(msg)                  写日志
//   wait(ms)                  等待
//   screenHas(text)           当前屏幕是否含某文本
//   readText(vid)             读某 viewId 的文本
//
// card 字段：price 价格 / distMe 距我(km) / distAll 行程(km)
//            timeText 时间 / addrFrom 出发地 / addrTo 目的地 / kind 卡种
//
// ⚠️ 改阈值只改下面的 TH，别动结构。
// ==========================================================================

var TH = {
  // 强休时段（强制休息）—— 优先级最高，先于下面所有规则
  //   "19+8"  = 19点起休 8 小时 → 到次日 3 点（跨天）
  //   "19-24,0-3" = 多段；也可写白班 "09-18"
  // 休息期间：预约单的**出发时间** 或 **当前时间** 任一落在区间内 → 一律不抢
  restWindows: "",   // 强休时段，默认关闭；按需填 "19+8" 或 "19-24,0-3"
  minPrice:    80,                      // 低于此价 → 不抢
  grabPrice:   100,                     // 近单：价格高于此
  grabDist:    20,                      // 近单：距我小于此(km)
  highPrice:   200,                     // 高于此价 → 无条件抢
  skipKeywords: ['岛', '隧道', '当前位置'],  // 出发地含关键词 → 不抢(假单屏蔽)
  tapOnceMs:   15000                    // 同一张卡点击成功后，多少毫秒内不再点
};

// ---- 强休时段 ----
function parseRest(s){
  var out=[], segs=String(s||"").split(/[,，]+/);
  for(var i=0;i<segs.length;i++){
    var t=segs[i].trim(); if(!t) continue;
    var m=t.match(/^(\d{1,2})\s*\+\s*(\d{1,2})/);      // 19+8
    if(m){ var a=+m[1], d=+m[2]; if(d>0) out.push([a,(a+d)%48]); continue; }
    m=t.match(/^(\d{1,2})\s*-\s*(\d{1,2})/);            // 19-24
    if(m){ var b=+m[2]; if(b>+m[1]) out.push([+m[1],b]); }
  }
  return out;
}
// h = 小时(可带小数)；段上界可 >24 表示跨到次日，用 h+24 覆盖"昨天的段延伸到今天"
function inRest(h, ws){
  if(!(h>=0) || !ws) return false;
  for(var i=0;i<ws.length;i++){
    var a=ws[i][0], b=ws[i][1];
    if(h>=a && h<b) return true;
    if(h+24>=a && h+24<b) return true;
  }
  return false;
}

// ---- 可选工具：解析"今天18:30 / 明天09:00 / 10月30日18:30" ----
function timeInfo(t) {
  var e = {day: '', hour: -1, min: -1, month: -1, dom: -1};
  if (!t) return e;
  var s = String(t);
  var m = s.match(/^(今天|明天|后天)[ ]*([0-9]{1,2}):([0-9]{2})/);
  if (m) return {day: m[1], hour: +m[2], min: +m[3], month: -1, dom: -1};
  m = s.match(/^([0-9]{1,2})月([0-9]{1,2})日[ ]*([0-9]{1,2}):([0-9]{2})/);
  if (m) return {day: '', hour: +m[3], min: +m[4], month: +m[1], dom: +m[2]};
  return e;
}
function isToday(ti) {
  if (ti.day === '今天') return true;
  if (ti.month > 0) {
    var n = new Date();
    return ti.month === (n.getMonth() + 1) && ti.dom === n.getDate();
  }
  return false;
}

// ---- 抢中检测：接乘客页 AND 出发地 AND 目的地 三者都匹配才算成功 ----
function grabCheck(card) {
  wait(1000);
  var onPage = screenHas('接乘客');
  var mFrom = !!card.addrFrom && screenHas(card.addrFrom);
  var mTo   = !!card.addrTo   && screenHas(card.addrTo);
  if (onPage && mFrom && mTo) {
    log('GRAB_OK ' + card.timeText + ' ' + card.addrFrom + ' -> ' + card.addrTo);
    screenshot('grab_ok');
  } else {
    log('GRAB_MISS onPage=' + onPage + ' from=' + mFrom + ' to=' + mTo);
  }
}

var __g = {};   // 点击记录：卡签名 → 上次点击成功的时间戳

function doGrab(card, layer) {
  var a = 'grab';
  screenshot(a);
  log(layer + ' grab price=' + card.price + ' dist=' + card.distMe);
  var k = (card.addrFrom || '') + '|' + (card.addrTo || '') + '|' + (card.timeText || '');
  if (__g[k] && (Date.now() - __g[k]) < TH.tapOnceMs) {
    log(layer + ' already_tapped');
    return;
  }
  // 只有点击成功才标记 —— 失败必须允许重试(抢单按钮只在短暂窗口可点)
  if (tap(a)) {
    __g[k] = Date.now();
    grabCheck(card);
  } else {
    log(layer + ' tap_failed');
  }
}

function doNoGrab(card, layer, why) {
  var a = 'no_grab';
  screenshot(a);
  log(layer + ' no_grab ' + why);
  tap(a);
}

onCard(function (card) {
  var price = Number(card.price);
  var dist = Number(card.distMe);
  var from = String(card.addrFrom || '');

  // 层R：强休时段 —— 最高优先级，命中即不抢
  var REST = parseRest(TH.restWindows);
  if (REST.length) {
    var ti0 = timeInfo(card.timeText);
    var depH = ti0.hour >= 0 ? (ti0.hour + ti0.min / 60) : -1;
    var now = new Date();
    var nowH = now.getHours() + now.getMinutes() / 60;
    if (inRest(depH, REST) || inRest(nowH, REST)) {
      doNoGrab(card, 'rest', 'dep=' + card.timeText + ' now=' + nowH.toFixed(1) + ' 窗口=' + TH.restWindows);
      return;
    }
  }

  // 层1：假单屏蔽 —— 出发地关键词
  for (var i = 0; i < TH.skipKeywords.length; i++) {
    if (from.indexOf(TH.skipKeywords[i]) >= 0) {
      doNoGrab(card, 'rule1', 'addrFrom=' + from);
      return;
    }
  }
  // 层2：低价不抢
  if (!isNaN(price) && price < TH.minPrice) {
    doNoGrab(card, 'rule2', 'price=' + price);
    return;
  }
  // 层3：近单抢单（距我近 且 价格够）
  if (!isNaN(price) && !isNaN(dist) && dist < TH.grabDist && price > TH.grabPrice) {
    doGrab(card, 'rule3');
    return;
  }
  // 层4：高价无条件抢单
  if (!isNaN(price) && price > TH.highPrice) {
    doGrab(card, 'rule4');
    return;
  }
  // 其余：只记录不点
  screenshot('pass');
  log('pass price=' + price + ' dist=' + dist + ' time=' + card.timeText);
});
"""
}
