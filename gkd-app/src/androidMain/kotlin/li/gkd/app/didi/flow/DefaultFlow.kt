package li.gkd.app.didi.flow

/**
 * Built-in default flow.js template (shipped in APK).
 *
 * Written to the external file on the first card, then hot-reloaded.
 * Layer order puts price first, then junk-address keywords (product decision).
 * User edits go through the web editor; this constant only seeds fresh installs.
 */
object DefaultFlow {
    const val CODE = """// ==========================================================================
// SWD default flow.js  (built-in template, generated on first card)
//
// Layer order (price first, per product decision):
//   R  rest window      - mandatory rest, highest priority, never grab
//   1  price < min      - too cheap, skip
//   2  skip keywords    - junk pickup / drop-off address, skip
//   3  near + price     - close enough and pays enough, grab
//   4  price > high     - unconditionally grab
//
// Primitives: onCard / tap / screenshot / log / wait / screenHas / readText
// card fields: price, distMe, distAll, timeText, addrFrom, addrTo, kind
// Edit TH only. Saved via the web editor; syntax error falls back to last good.
// ==========================================================================

var TH = {
  restWindows: "",        // e.g. "19+8" or "19-24,0-3"; empty = disabled
  minPrice:    80,        // below this -> skip
  skipKeywords: ["\u5c9b", "\u96a7\u9053", "\u5f53\u524d\u4f4d\u7f6e"],  // matched against addrFrom (CJK keywords, unicode-escaped to keep this file ASCII)
  grabPrice:   100,       // near-order price floor
  grabDist:    20,        // near-order max distance (km)
  highPrice:   200,       // unconditional grab above this
  tapOnceMs:   15000      // same card: tap at most once per window
};

// ---- rest window ----
function parseRest(s) {
  var out = [], segs = String(s || "").split(/[,，]+/);
  for (var i = 0; i < segs.length; i++) {
    var t = segs[i].trim(); if (!t) continue;
    var m = t.match(/^(\d{1,2})\s*\+\s*(\d{1,2})/);      // 19+8
    if (m) { var a = +m[1], d = +m[2]; if (d > 0) out.push([a, (a + d) % 48]); continue; }
    m = t.match(/^(\d{1,2})\s*-\s*(\d{1,2})/);            // 19-24
    if (m) { var b = +m[2]; if (b > +m[1]) out.push([+m[1], b]); }
  }
  return out;
}
// end may exceed 24 to cross midnight; check h and h+24
function inRest(h, ws) {
  if (!(h >= 0) || !ws) return false;
  for (var i = 0; i < ws.length; i++) {
    var a = ws[i][0], b = ws[i][1];
    if (h >= a && h < b) return true;
    if (h + 24 >= a && h + 24 < b) return true;
  }
  return false;
}

// ---- time text: "today 18:30" / "tomorrow 09:00" / "10-30 18:30" ----
function timeInfo(t) {
  var e = { day: "", hour: -1, min: -1, month: -1, dom: -1 };
  if (!t) return e;
  var s = String(t);
  var m = s.match(/^(今天|明天|后天)[ ]*([0-9]{1,2}):([0-9]{2})/);
  if (m) return { day: m[1], hour: +m[2], min: +m[3], month: -1, dom: -1 };
  m = s.match(/^([0-9]{1,2})月([0-9]{1,2})日[ ]*([0-9]{1,2}):([0-9]{2})/);
  if (m) return { day: "", hour: +m[3], min: +m[4], month: +m[1], dom: +m[2] };
  return e;
}

// ---- grab confirmation: result page + both endpoints on screen ----
function grabCheck(card) {
  wait(3000);
  var onPage = screenHas("接乘客");
  var mFrom = !!card.addrFrom && screenHas(card.addrFrom);
  var mTo   = !!card.addrTo   && screenHas(card.addrTo);
  if (onPage && mFrom && mTo) {
    log("GRAB_OK " + card.timeText + " " + card.addrFrom + " -> " + card.addrTo);
    screenshot("grab_ok");
  } else {
    log("GRAB_MISS onPage=" + onPage + " from=" + mFrom + " to=" + mTo);
  }
}

var __g = {};   // card signature -> last successful tap ts

function doGrab(card, layer) {
  var a = "grab";
  screenshot(a);
  log(layer + " grab price=" + card.price + " dist=" + card.distMe);
  var k = (card.addrFrom || "") + "|" + (card.addrTo || "") + "|" + (card.timeText || "");
  if (__g[k] && (Date.now() - __g[k]) < TH.tapOnceMs) {
    log(layer + " already_tapped");
    return;
  }
  // mark as tapped only on success — a failed tap must be retried
  if (tap(a)) { __g[k] = Date.now(); grabCheck(card); }
  else { log(layer + " tap_failed"); }
}

function doSkip(card, layer, why) {
  var a = "no_grab";
  screenshot(a);
  log(layer + " skip " + why);
  tap(a);
}

onCard(function (card) {
  var price = Number(card.price);
  var dist  = Number(card.distMe);
  var from  = String(card.addrFrom || "");
  var REST  = parseRest(TH.restWindows);

  // L-R: rest window (mandatory rest) — highest priority
  if (REST.length) {
    var ti = timeInfo(card.timeText);
    var dep = ti.hour >= 0 ? (ti.hour + ti.min / 60) : -1;
    var now = new Date();
    var nowH = now.getHours() + now.getMinutes() / 60;
    if (inRest(dep, REST) || inRest(nowH, REST)) {
      doSkip(card, "rest", "dep=" + card.timeText + " now=" + nowH.toFixed(1));
      return;
    }
  }

  // L1: price first
  if (!isNaN(price) && price < TH.minPrice) {
    doSkip(card, "price", "p=" + price);
    return;
  }

  // L2: junk address keywords
  for (var i = 0; i < TH.skipKeywords.length; i++) {
    if (from.indexOf(TH.skipKeywords[i]) >= 0) {
      doSkip(card, "addr", "kw=" + TH.skipKeywords[i]);
      return;
    }
  }

  // L3: near order — close and pays enough
  if (!isNaN(price) && !isNaN(dist) && dist < TH.grabDist && price > TH.grabPrice) {
    doGrab(card, "near");
    return;
  }

  // L4: high price — unconditionally
  if (!isNaN(price) && price > TH.highPrice) {
    doGrab(card, "high");
    return;
  }

  log("pass p=" + price + " d=" + dist);
});
"""
}
