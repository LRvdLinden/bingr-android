/*
 * Build 150 (Samsung TV en, via deze map, LG webOS): pure logica, gelijk aan features/Build150Logic.kt op Android en
 * getest in test/build150_test.js met dezelfde gevallen.
 *
 * - X.cat: taal-, land- en kwaliteitsaanduidingen weg uit categorienamen, in elke combinatie en volgorde, voorin en
 *   achterin ("| NL | NETFLIX" → "Netflix", "| MULTI / LINGO | NETFLIX" → "Netflix", "NETFLIX (NL)" → "Netflix"), met
 *   nette hoofdletters ("BIOSCOOP" → "Bioscoop", "HBO MAX" → "HBO Max"). Wat weggehaald is, komt terug als label
 *   (tags: "NL", "Multi"). Zoeken (B.b149.search.key) gebruikt dezelfde opschoning (X.cat.strip).
 * - X.merge: categorieën met dezelfde opgeschoonde naam samenvoegen (één tegel/rij), inhoud ontdubbeld.
 * - X.logos: welk TMDB-logo bij een categorienaam hoort (betere alias-tabel).
 * ES5 (oude tv's).
 */
(function () {
  'use strict';
  var B = window.B = window.B || {};
  var X = B.b150 = {};
  function set(s) { var o = {}; s.split(' ').forEach(function (k) { if (k) o[k] = true; }); return o; }
  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }

  var SEPS = '|¦│:–—•·★»«[](){}';
  function isSepChar(c) { return SEPS.indexOf(c) >= 0; }
  function isSpace(c) { return c === ' ' || c === '\t' || c === ' ' || c === '\n' || c === '\r'; }

  var ISO639 = set('aa ab ae af ak am an ar as av ay az ba be bg bh bi bm bn bo br bs ca ce ch co cr cs cu cv cy da de dv dz ' +
    'ee el en eo es et eu fa ff fi fj fo fr fy ga gd gl gn gu gv ha he hi ho hr ht hu hy hz ia id ie ig ii ik io is it iu ja ' +
    'jv ka kg ki kj kk kl km kn ko kr ks ku kv kw ky la lb lg li ln lo lt lu lv mg mh mi mk ml mn mr ms mt my na nb nd ne ng ' +
    'nl nn no nr nv ny oc oj om or os pa pi pl ps pt qu rm rn ro ru rw sa sc sd se sg si sk sl sm sn so sq sr ss st su sv sw ' +
    'ta te tg th ti tk tl tn to tr ts tt tw ty ug uk ur uz ve vi vo wa wo xh yi yo za zh zu');
  var ISO3166 = set('ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bl bm bn bo bq br bs bt bv bw ' +
    'by bz ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee eg eh er es et fi fj fk fm fo fr ' +
    'ga gb gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk hm hn hr ht hu id ie il im in io iq ir is it je jm jo jp ke ' +
    'kg kh ki km kn kp kr kw ky kz la lb lc li lk lr ls lt lu lv ly ma mc md me mf mg mh mk ml mm mn mo mp mq mr ms mt mu mv ' +
    'mw mx my mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro rs ru rw sa sb sc ' +
    'sd se sg sh si sj sk sl sm sn so sr ss st sv sx sy sz tc td tf tg th tj tk tl tm tn to tr tt tw tz ua ug um us uy ' +
    'uz va vc ve vg vi vn vu wf ws ye yt za zm zw uk eu');
  var COMMON = set('nl be vl en uk us gb de at ch fr es it pt br tr pl ar ru se dk no fi gr jp kr cn al ro hu cz il ir pk ' +
    'in th vn ku ua bg hr rs ma dz tn eg sa ae lat nld dut eng ger deu fra fre spa esp ita por tur ara pol rus swe dan nor ' +
    'fin jpn kor chi alb hin usa nlbe ptbr exyu yu');
  var BARE_SKIP = set('no at he el da th ch it in hi be al ma me am to so or is us');
  var NEVER = set('tv go f1 dc uf');
  var NAMES = {};
  function addNames(code, list) { list.split(' ').forEach(function (k) { NAMES[k] = code; }); }
  addNames('NL', 'nederlands nederlandse dutch nederland netherlands holland hollands');
  addNames('BE', 'vlaams vlaamse flemish belgie belgië belgium belgisch');
  addNames('EN', 'engels engelse english britain british american amerikaans');
  addNames('DE', 'duits duitse german deutsch duitsland germany deutschland');
  addNames('FR', 'frans franse french francais français frankrijk france');
  addNames('ES', 'spaans spaanse spanish espanol español spanje spain latino castellano');
  addNames('IT', 'italiaans italiaanse italian italiano italie italië italy');
  addNames('PT', 'portugees portugese portuguese portugues português brazil brasil braziliaans');
  addNames('TR', 'turks turkse turkish turkce türkçe turkije turkey');
  addNames('AR', 'arabisch arabische arabic arab');
  addNames('PL', 'pools poolse polish polski polen poland');
  addNames('RU', 'russisch russische russian rusland russia');
  addNames('HI', 'hindi indian indiaas');
  addNames('SV', 'zweeds swedish');
  addNames('DA', 'deens danish');
  addNames('NO', 'noors norwegian');
  addNames('EL', 'grieks greek');
  addNames('AL', 'albanees albanian shqip');
  addNames('KU', 'koerdisch kurdish');
  addNames('RO', 'roemeens romanian');
  addNames('UK', 'oekraïens ukrainian');
  addNames('Intl', 'international internationaal world wereld');
  var MULTI = set('multi multilang multilingo multilanguage multiaudio multisub multisubs lingo lang dual dualaudio multilingual');
  var SUBDUB = set('sub subs subbed subtitled dub dubbed vostfr vost vose vo vf vff');
  var QUALITY = set('4k uhd fhd hd sd hevc h265 h264 x265 hdr hdr10 dv 1080p 720p 2160p 4kuhd uhd4k 8k 3d atmos raw 60fps 50fps');
  var KIND = set('vod series serie serien films film movies movie shows');
  var SUBRE = /^([a-z]{2,3})(sub|subs|dub)$/;

  var C = X.cat = {};
  /** Soort label (null = gewoon woord): 'code', 'name', 'multi', 'sub', 'quality', 'kind'. */
  C.kindOf = function (word, sep, front) {
    var w = word.toLowerCase();
    var n = w.replace(/[-_.]/g, '');
    if (!n || has(NEVER, n)) return null;
    if (has(QUALITY, n)) return 'quality';
    if (has(MULTI, n)) return 'multi';
    if (has(SUBDUB, n)) return 'sub';
    var m = SUBRE.exec(n);
    if (m && (has(COMMON, m[1]) || has(ISO639, m[1]))) return 'sub';
    var upper = word === word.toUpperCase();
    if (/^[a-z]+$/.test(n) && (n.length === 2 || n.length === 3 || n === 'nlbe' || n === 'ptbr' || n === 'exyu')) {
      if (sep) {
        if (has(COMMON, n) && (upper || n.length === 2)) return 'code';
        if (upper && n.length === 2 && (has(ISO639, n) || has(ISO3166, n))) return 'code';
      } else if (upper && has(COMMON, n) && !has(BARE_SKIP, n)) return 'code';
    }
    if (sep && has(NAMES, w)) return 'name';
    if (sep && front && has(KIND, n)) return 'kind';
    return null;
  };
  function tagOf(word, kind) {
    var up = word.toUpperCase();
    if (kind === 'code') return up === 'VL' ? 'BE' : up === 'GB' ? 'UK' : up;
    if (kind === 'name') return NAMES[word.toLowerCase()] || null;
    if (kind === 'multi') return 'Multi';
    if (kind === 'sub') {
      var m = SUBRE.exec(word.toLowerCase().replace(/-/g, ''));
      if (m) return m[1].toUpperCase() + ' ' + m[2].toUpperCase();
      return up === 'VOSTFR' ? 'VOSTFR' : null;
    }
    return null;
  }
  function words(s) {
    var out = [], i = 0, n = s.length;
    function sepAt(j) {
      var c = s.charAt(j);
      if (isSepChar(c)) return true;
      if (c === '-' || c === '/') {
        var before = j === 0 || isSpace(s.charAt(j - 1)) || isSepChar(s.charAt(j - 1));
        var after = j === n - 1 || isSpace(s.charAt(j + 1)) || isSepChar(s.charAt(j + 1));
        return before || after;
      }
      return false;
    }
    while (i < n) {
      if (isSpace(s.charAt(i)) || sepAt(i)) { i++; continue; }
      var st = i;
      while (i < n && !isSpace(s.charAt(i)) && !sepAt(i)) i++;
      out.push({ start: st, end: i, text: s.slice(st, i) });
    }
    return out;
  }
  function gapHasSep(s, from, to) {
    for (var j = from; j < to; j++) if (!isSpace(s.charAt(j))) return true;
    return false;
  }
  function trimSeps(s) {
    var i = 0, j = s.length;
    function edge(c) { return isSpace(c) || isSepChar(c) || c === '-' || c === '/'; }
    while (i < j && edge(s.charAt(i))) i++;
    while (j > i && edge(s.charAt(j - 1))) j--;
    return s.slice(i, j) || s.trim();
  }
  function count(s, c) { var n = 0; for (var i = 0; i < s.length; i++) if (s.charAt(i) === c) n++; return n; }

  /** Alleen weghalen (hoofdletters blijven): {name, tags}. Voor zoeken en vergelijken. */
  C.strip = function (raw) {
    var s = String(raw == null ? '' : raw).trim();
    if (!s) return { name: '', tags: [] };
    var ws = words(s);
    if (!ws.length) return { name: s, tags: [] };
    var a = 0, b = ws.length - 1, front = [], back = [], k, t, w, sep;
    while (a < b) {
      w = ws[a];
      sep = gapHasSep(s, a === 0 ? 0 : ws[a - 1].end, w.start) || gapHasSep(s, w.end, ws[a + 1].start);
      k = C.kindOf(w.text, sep, true);
      if (!k) break;
      t = tagOf(w.text, k); if (t) front.push(t);
      a++;
    }
    while (b > a) {
      w = ws[b];
      sep = gapHasSep(s, ws[b - 1].end, w.start) || gapHasSep(s, w.end, s.length);
      k = C.kindOf(w.text, sep, false);
      if (!k) break;
      t = tagOf(w.text, k); if (t) back.unshift(t);
      b--;
    }
    if (a === b && (a > 0 || b < ws.length - 1)) {
      var rest = C.kindOf(ws[a].text, true, false);
      if (rest === 'code' || rest === 'name' || rest === 'multi' || rest === 'sub') return { name: trimSeps(s), tags: [] };
    }
    var st = ws[a].start, en = ws[b].end, mid = s.slice(st, en);
    [['(', ')'], ['[', ']'], ['{', '}']].forEach(function (p) {
      var open = count(mid, p[0]), close = count(mid, p[1]), j;
      if (open > close) { j = en; while (j < s.length && isSpace(s.charAt(j))) j++; if (j < s.length && s.charAt(j) === p[1]) en = j + 1; }
      if (close > open) { j = st - 1; while (j >= 0 && isSpace(s.charAt(j))) j--; if (j >= 0 && s.charAt(j) === p[0]) st = j; }
    });
    var tags = [];
    front.concat(back).forEach(function (x) { if (tags.indexOf(x) < 0) tags.push(x); });
    return { name: s.slice(st, en).trim(), tags: tags };
  };

  var KEEP = set('HBO BBC NPO RTL SBS VTM ESPN TV F1 GO CNN MTV NBA NFL NHL MLB UFC WWE MMA DAZN XXX VRT VPRO KRO NOS BNN TLC AMC ' +
    'FX HGTV CBS ABC NBC ITV USA UK DC UEFA FIFA KPN TF1 M6 ZDF ARD SVT NRK TVE RAI TRT ATV OSN MBC BVN AVRO EO NTR WNL ' +
    'VTV VIER VIJF ZES NPO1 NPO2 NPO3 SBS6 SBS9 RTL4 RTL5 RTL7 RTL8 RTLZ ID TNT AXN DTV AT5 OUTTV MAX+ PSG PSV AFC AZ ATP WTA ' +
    'PGA NASCAR DTM WRC MOTOGP F2 F3 GP BTS KPOP K-POP DJ MTV2 BET CW HD UHD 4K VOD');
  var BRANDS = {
    skyshowtime: 'SkyShowtime', appletvplus: 'Apple TV+', appletv: 'Apple TV', youtube: 'YouTube', hbomax: 'HBO Max',
    disneyplus: 'Disney+', primevideo: 'Prime Video', amazonprimevideo: 'Amazon Prime Video', paramountplus: 'Paramount+',
    npostart: 'NPO Start', npoplus: 'NPO Plus', vtmgo: 'VTM GO', streamz: 'Streamz', videoland: 'Videoland', viaplay: 'Viaplay',
    netflix: 'Netflix', kijk: 'KIJK', discoveryplus: 'discovery+', ziggo: 'Ziggo', espn: 'ESPN', dazn: 'DAZN'
  };
  function cap(word) {
    if (!word || has(KEEP, word) || word.length <= 2 || /[0-9]/.test(word)) return word;
    var out = '', startOfPart = true;
    for (var i = 0; i < word.length; i++) {
      var c = word.charAt(i);
      out += startOfPart ? c.toUpperCase() : c.toLowerCase();
      startOfPart = c === '-' || c === '/';
    }
    return out;
  }
  function hasLetter(s) { return s.toUpperCase() !== s.toLowerCase(); }
  /** Alleen als de hele naam in hoofdletters staat; één kort woord (≤ 3) blijft staan ("XXX"). */
  C.tidyCase = function (name) {
    if (!hasLetter(name) || name !== name.toUpperCase()) return name;
    var b = BRANDS[X.merge.key(name)];
    if (b) return b;
    var parts = name.split(' ');
    if (parts.length === 1 && name.length <= 3) return name;
    return parts.map(cap).join(' ');
  };
  /** {name, tags}: de naam zoals de app hem toont, plus de weggehaalde labels. */
  C.clean = function (raw) {
    var st = C.strip(raw);
    return { name: C.tidyCase(st.name || String(raw == null ? '' : raw).trim()), tags: st.tags };
  };
  var memo = {}, memoN = 0;
  C.cached = function (raw) {
    raw = String(raw == null ? '' : raw);
    if (has(memo, raw)) return memo[raw];
    if (memoN > 20000) { memo = {}; memoN = 0; }
    memoN++;
    return (memo[raw] = C.clean(raw));
  };
  C.display = function (raw) { return C.cached(raw).name; };

  // MARK: samenvoegen

  var M = X.merge = {};
  M.key = function (name) {
    var s = String(name || '').toLowerCase(), out = '';
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === '+') out += 'plus';
      else if (c === '&') out += 'and';
      else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out += c;
      else if (c.toUpperCase() !== c.toLowerCase()) out += c;
    }
    return out;
  };
  M.groupKey = function (raw) { return M.key(C.cached(raw).name) || String(raw || '').toLowerCase(); };
  function lowerCount(s) { var n = 0; for (var i = 0; i < s.length; i++) { var c = s.charAt(i); if (c !== c.toUpperCase()) n++; } return n; }
  /** [{key, name, sources, tags}] in de volgorde van de eerste bron. */
  M.groups = function (names) {
    var by = {}, order = [];
    (names || []).forEach(function (n) {
      if (n == null) return;
      var k = String(n).trim() ? M.groupKey(n) : '';
      if (!has(by, k)) { by[k] = []; order.push(k); }
      if (by[k].indexOf(n) < 0) by[k].push(n);
    });
    return order.map(function (k) {
      var src = by[k], tags = [], best = null, bestN = -1;
      src.forEach(function (s) {
        var c = C.cached(s);
        c.tags.forEach(function (t) { if (tags.indexOf(t) < 0) tags.push(t); });
        var ln = lowerCount(c.name);
        if (ln > bestN) { bestN = ln; best = c.name; }
      });
      return { key: k, name: best || src[0], sources: src, tags: tags };
    });
  };
  M.chips = function (tags, max) { return (tags || []).slice(0, max || 3).join(' · '); };
  /** Ontdubbelen: zelfde id (id(x)) of anders genormaliseerde titel + jaar maar één keer; de eerste wint. */
  M.dedupe = function (items, id, title, year) {
    if (!items || items.length < 2) return items || [];
    var seenId = {}, seenT = {}, out = [];
    var key = (B.b149 && B.b149.search) ? B.b149.search.key : function (t) { return String(t || '').toLowerCase(); };
    for (var i = 0; i < items.length; i++) {
      var x = items[i], d = id(x), y = year(x);
      var t = key(title(x)).replace(/ /g, '') + '|' + (y == null ? '' : y);
      if (d) { if (seenId[d]) continue; seenId[d] = true; if (t.length > 1 && seenT[t]) continue; seenT[t] = true; }
      else { if (t.length > 1 && seenT[t]) continue; seenT[t] = true; }
      out.push(x);
    }
    return out;
  };
  /** Bronvolgorde bij het afspelen: eerst een bron met een label uit preferred (app-taal, audiotaal). */
  M.preferSources = function (sources, preferred) {
    if (!preferred || !preferred.length) return sources.slice();
    var p = preferred.map(function (x) { return String(x).toUpperCase(); });
    function rank(s) {
      var tags = C.cached(s).tags.map(function (x) { return x.toUpperCase(); });
      for (var i = 0; i < p.length; i++) if (tags.indexOf(p[i]) >= 0 || (p[i] === 'NL' && tags.indexOf('BE') >= 0)) return i;
      return p.length + (tags.indexOf('MULTI') >= 0 ? 0 : 1);
    }
    return sources.map(function (s, i) { return { s: s, i: i, r: rank(s) }; })
      .sort(function (a, b) { return a.r - b.r || a.i - b.i; }).map(function (x) { return x.s; });
  };

  // MARK: zoeken in verborgen categorieën

  /**
   * Treffers in de compacte index van niet ingelezen items (lib.hiddenIndex = {movie|series: {n: [naam], g: [nr], gs:
   * [categorie]}}, js/providers.js): [{type, title, group}], beste eerst, hooguit [limit]. Zoeksleutels één keer per
   * index berekend (idx._k). Zelfde ranking als B.b149.search.
   */
  X.hiddenSearch = function (index, q, limit) {
    var SE = B.b149 && B.b149.search;
    if (!SE || !index) return [];
    var w = SE.words(q);
    if (!w.length) return [];
    var phrase = w.join(' '), hits = [];
    ['movie', 'series'].forEach(function (type) {
      var ix = index[type];
      if (!ix || !ix.n || !ix.n.length) return;
      if (!ix._k) { ix._k = new Array(ix.n.length); for (var i = 0; i < ix.n.length; i++) ix._k[i] = SE.key(ix.n[i]); }
      for (var j = 0; j < ix._k.length; j++) {
        var sc = SE.score(ix._k[j], w, phrase);
        if (sc > 0) hits.push({ s: sc, i: hits.length, type: type, raw: ix.n[j], group: ix.gs[ix.g[j]] });
      }
    });
    hits.sort(function (a, b) { return b.s - a.s || a.i - b.i; });
    var out = [], seen = {};
    for (var k = 0; k < hits.length && out.length < (limit || 30); k++) {
      var x = hits[k], t = (B.parseTitle ? B.parseTitle(x.raw).title : x.raw) || x.raw, key = x.type + '|' + SE.key(t) + '|' + x.group;
      if (seen[key]) continue;
      seen[key] = true;
      out.push({ type: x.type, title: t, group: x.group });
    }
    return out;
  };

  // MARK: logo's

  var LG = X.logos = {};
  LG.ALIASES = {
    netflix: ['netflix', 'netflixstandardwithads', 'netflixbasicwithads'], netflixkids: ['netflixkids', 'netflix'],
    videoland: ['videoland'], disney: ['disneyplus'], disneyplus: ['disneyplus'],
    hbo: ['hbomax', 'max'], hbomax: ['hbomax', 'max'], max: ['max', 'hbomax'],
    prime: ['amazonprimevideo', 'primevideo', 'amazonvideo'], primevideo: ['amazonprimevideo', 'primevideo', 'amazonvideo'],
    amazon: ['amazonprimevideo', 'primevideo', 'amazonvideo'], amazonprime: ['amazonprimevideo', 'primevideo'],
    amazonprimevideo: ['amazonprimevideo', 'primevideo'],
    skyshowtime: ['skyshowtime'], sky: ['skyshowtime'], showtime: ['skyshowtime'], viaplay: ['viaplay'],
    apple: ['appletvplus', 'appletv'], appletv: ['appletvplus', 'appletv'], appletvplus: ['appletvplus', 'appletv'],
    paramount: ['paramountplus'], paramountplus: ['paramountplus'],
    npo: ['npostart', 'npoplus'], npostart: ['npostart', 'npoplus'], npoplus: ['npoplus', 'npostart'],
    vtmgo: ['vtmgo'], streamz: ['streamz'], pickx: ['pickx'], kijk: ['kijk'],
    discovery: ['discoveryplus'], discoveryplus: ['discoveryplus'], mubi: ['mubi'],
    pathe: ['pathethuis'], pathethuis: ['pathethuis'], canal: ['canalplus'], canalplus: ['canalplus']
  };
  var TAIL = /(films?|movies?|series|serie|shows?|tv|kids|nl|be|en|4k|uhd|fhd|hd|originals?|vod|new|nieuw|collectie|collection|exclusive|exclusief|docu|documentaires?)+$/;
  LG.providerFor = function (category, providers) {
    providers = providers || {};
    var k = M.key(C.cached(category).name);
    if (!k) return null;
    var cands = [k], t = k, i, j;
    for (var r = 0; r < 3; r++) { t = t.replace(TAIL, ''); if (t && cands.indexOf(t) < 0) cands.push(t); }
    for (i = 0; i < cands.length; i++) {
      var c = cands[i];
      if (c !== '_at' && has(providers, c)) return c;
      var list = LG.ALIASES[c];
      if (!list) continue;
      for (j = 0; j < list.length; j++) if (has(providers, list[j])) return list[j];
      for (j = 0; j < list.length; j++) for (var p in providers) if (has(providers, p) && p.indexOf(list[j]) === 0) return p;
    }
    return null;
  };
})();
