package li.gkd.app.didi.flow

/**
 * Built-in default flow.js template (shipped in APK).
 *
 * Ships exactly one rule by design: price below threshold -> tap "no_grab".
 * All other layers are left to the operator, tuned through the web editor.
 * Written to the external file on the first card, then hot-reloaded.
 */
object DefaultFlow {
    const val CODE = """// ==========================================================================
// SWD default flow.js  (built-in template, generated on first card)
//
// Single rule by design: price below minPrice -> tap "no_grab".
// Everything else is left to the operator. Tune via the web editor.
//
// Primitives: onCard / tap / screenshot / log / wait / screenHas / readText
// card fields: price, distMe, distAll, timeText, addrFrom, addrTo, kind
// ==========================================================================

var TH = {
  minPrice: 80,        // below this -> tap no_grab
  tapOnceMs: 15000     // same card: tap at most once per window
};

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

var __g = {};   // card signature -> last successful tap ts

function doSkip(card, layer, why) {
  var a = "no_grab";
  var k = (card.addrFrom || "") + "|" + (card.addrTo || "") + "|" + (card.timeText || "");
  if (__g[k] && (Date.now() - __g[k]) < TH.tapOnceMs) {
    log(layer + " already_tapped");
    return;
  }
  screenshot(a);
  log(layer + " skip " + why);
  // mark as tapped only on success — a failed tap must be retried
  if (tap(a)) { __g[k] = Date.now(); }
  else { log(layer + " tap_failed"); }
}

onCard(function (card) {
  var price = Number(card.price);

  // Realtime card has NO skip/grab button — there is nothing to tap.
  // Record it and stop. Any decision here would be a no-op at best.
  if (card.kind === "实时单") {
    screenshot("realtime");
    log("realtime p=" + price + " d=" + card.distMe + " (no action, card has no buttons)");
    return;
  }

  // L1: price below threshold -> no_grab
  if (!isNaN(price) && price < TH.minPrice) {
    doSkip(card, "price", "p=" + price);
    return;
  }

  log("pass p=" + price + " d=" + card.distMe);
});
"""
}
