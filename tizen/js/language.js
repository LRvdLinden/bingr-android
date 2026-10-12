/*
 * Taal van een titel (= ContentLanguage op iOS en Android, build 59).
 * IPTV-aanbieders zetten de taal in de naam of in de categorie: "Animal (IT)", "Animal Kingdom (NL)", "NL | Titel",
 * "NL: Titel", "|NL| Titel", "[NL] Titel", "NL - Titel", "Titel NL SUBS", "Titel MULTI", "Titel DUAL",
 * categorieën als "NL Films" of "VOD | DE". Alleen tussen haakjes of met een scheidingsteken ervoor/erachter
 * telt het als taal (een los woord "IT" in een titel niet). "(IN)" = Hindi.
 * Volgorde: titel, dan categorie, dan de originele taal uit TMDB (original_language, gecachet bij de leeftijdsclassificaties).
 * Uitkomst: {a: audiotaal (ISO 639-1) | null, s: ondertiteltaal | null, m: meerdere audiotalen}.
 */
(function () {
  'use strict';
  var B = window.B;
  var CL = B.clang = {};
  var L = function () { return B.L.apply(null, arguments); };

  // codes zoals ze in titels en categorieën staan (hoofdletters) → ISO 639-1
  var CODES = {
    NL: 'nl', NLD: 'nl', DUT: 'nl', VL: 'nl', EN: 'en', ENG: 'en', UK: 'en', GB: 'en', US: 'en', USA: 'en',
    DE: 'de', GER: 'de', DEU: 'de', FR: 'fr', FRA: 'fr', FRE: 'fr', VF: 'fr', ES: 'es', SPA: 'es', ESP: 'es', LAT: 'es',
    IT: 'it', ITA: 'it', PT: 'pt', POR: 'pt', BR: 'pt', PL: 'pl', POL: 'pl', TR: 'tr', TUR: 'tr', AR: 'ar', ARA: 'ar',
    IN: 'hi', HIN: 'hi', HI: 'hi', RU: 'ru', RUS: 'ru', SE: 'sv', SWE: 'sv', SV: 'sv', NO: 'no', NOR: 'no',
    DK: 'da', DAN: 'da', DA: 'da', FI: 'fi', FIN: 'fi', GR: 'el', GRE: 'el', EL: 'el', RO: 'ro', ROM: 'ro', HU: 'hu', HUN: 'hu',
    CZ: 'cs', CZE: 'cs', KR: 'ko', KOR: 'ko', JP: 'ja', JPN: 'ja', CN: 'zh', CHI: 'zh', TH: 'th', THA: 'th',
    FA: 'fa', IR: 'fa', HE: 'he', HEB: 'he', AL: 'sq', ALB: 'sq', BG: 'bg', BUL: 'bg', HR: 'hr', HRV: 'hr', RS: 'sr', SRB: 'sr',
    UA: 'uk', UKR: 'uk', TA: 'ta', TAM: 'ta'
  };
  // codes die ook een gewoon woord zijn ("de", "in", "it", "en", "no", "al"): in een categorie alleen met een scheidingsteken
  // of naast een woord als "Films"
  var AMBIG = { DE: 1, IN: 1, IT: 1, EN: 1, NO: 1, AL: 1, US: 1, HE: 1, DA: 1, TA: 1, HI: 1, EL: 1, SE: 1 };
  // taalnamen (kleine letters, zonder accenten)
  var NAMES = {
    dutch: 'nl', nederlands: 'nl', nederlandse: 'nl', vlaams: 'nl', vlaamse: 'nl', flemish: 'nl',
    english: 'en', german: 'de', deutsch: 'de', deutsche: 'de', duits: 'de', duitse: 'de',
    french: 'fr', francais: 'fr', francaise: 'fr', frans: 'fr', franse: 'fr', spanish: 'es', espanol: 'es', spaans: 'es', spaanse: 'es', latino: 'es',
    italian: 'it', italiano: 'it', italiana: 'it', italiaans: 'it', italiaanse: 'it', portuguese: 'pt', portugues: 'pt', portugees: 'pt', portugese: 'pt',
    polish: 'pl', polski: 'pl', poolse: 'pl', turkish: 'tr', turkce: 'tr', turkse: 'tr', arabic: 'ar', arabisch: 'ar', arabische: 'ar',
    hindi: 'hi', bollywood: 'hi', russian: 'ru', russisch: 'ru', russische: 'ru', swedish: 'sv', zweeds: 'sv', norwegian: 'no', noors: 'no',
    danish: 'da', deens: 'da', finnish: 'fi', fins: 'fi', greek: 'el', grieks: 'el', romanian: 'ro', hungarian: 'hu', czech: 'cs',
    korean: 'ko', koreaans: 'ko', japanese: 'ja', japans: 'ja', chinese: 'zh', chinees: 'zh', thai: 'th', persian: 'fa', farsi: 'fa',
    hebrew: 'he', albanian: 'sq', bulgarian: 'bg', croatian: 'hr', serbian: 'sr', ukrainian: 'uk', tamil: 'ta'
  };
  // ISO 639-2 en varianten (sporen in de stream) → ISO 639-1
  var ISO3 = {
    nld: 'nl', dut: 'nl', eng: 'en', deu: 'de', ger: 'de', fra: 'fr', fre: 'fr', spa: 'es', ita: 'it', por: 'pt', pol: 'pl', tur: 'tr',
    ara: 'ar', hin: 'hi', rus: 'ru', swe: 'sv', nor: 'no', nob: 'no', nno: 'no', dan: 'da', fin: 'fi', gre: 'el', ell: 'el', rum: 'ro', ron: 'ro',
    hun: 'hu', cze: 'cs', ces: 'cs', kor: 'ko', jpn: 'ja', chi: 'zh', zho: 'zh', tha: 'th', per: 'fa', fas: 'fa', heb: 'he', alb: 'sq', sqi: 'sq',
    bul: 'bg', hrv: 'hr', srp: 'sr', ukr: 'uk', tam: 'ta', vls: 'nl', nb: 'no', nn: 'no', iw: 'he'
  };
  var SUBWORDS = { SUB: 1, SUBS: 1, SUBBED: 1, SUBTITLED: 1, SUBTITLES: 1, ONDERTITELD: 1, ONDERTITELS: 1, OT: 1, ST: 1 };
  var MULTIWORDS = { MULTI: 1, DUAL: 1, MULTIAUDIO: 1, DUALAUDIO: 1 };
  // woorden die in een taal-tag mogen staan zonder iets te betekenen
  var FILLER = { AUDIO: 1, HD: 1, FHD: 1, UHD: 1, '4K': 1, SD: 1, HEVC: 1, '1080P': 1, '720P': 1, '2160P': 1, GESPROKEN: 1, DUBBED: 1, DUB: 1,
    VERSION: 1, VERSIE: 1, LANG: 1, LANGUAGE: 1, VOD: 1, TV: 1, VO: 1, OV: 1, OMU: 1 };
  // voorvoegsels die geen taal zijn maar wel vóór de taal kunnen staan ("4K | NL | Titel")
  var PREFIX_SKIP = { VOD: 1, '4K': 1, HD: 1, FHD: 1, UHD: 1, NEW: 1, NIEUW: 1, TOP: 1 };
  var MEDIA = /^(films?|filme|movies?|series?|serien|vod|kids|kinderen|tv|docu\w*|anime|cinema|kino|4k|hd|fhd|uhd|netflix|disney\+?|hbo|prime|apple|actie|action|drama|comedy|komedie|horror|thriller|top|new|nieuw|\d{4})$/i;

  function fold(s) { return B.fold(s); }

  /** Stream-taalcode (nl, nld, dut, nl-NL, "Dutch") → ISO 639-1, of '' als onbekend. */
  CL.norm = function (code) {
    if (code == null) return '';
    var c = fold(String(code)).trim();
    if (!c || c === 'und' || c === 'mis' || c === 'mul' || c === 'zxx' || c === 'qaa') return '';
    if (B.has(NAMES, c)) return NAMES[c];
    c = c.split(/[-_]/)[0];
    if (c.length === 2) return c;
    if (B.has(ISO3, c)) return ISO3[c];
    return c.length === 3 ? c : '';
  };

  /**
   * Losse woorden van een tag uitleggen. strict: codes alleen in hoofdletters.
   * Geeft {a, s, m} of null als er een woord in zit dat geen taal/vlag is (dan was het geen tag, bv. "(The Return)").
   */
  function readTokens(text, strict) {
    var toks = String(text).split(/[\s\-+\/,&|.·]+/).filter(function (t) { return t.length > 0; });
    if (!toks.length) return null;
    var audio = [], subs = null, multi = false, any = false;
    for (var i = 0; i < toks.length; i++) {
      var t = toks[i], U = t.toUpperCase(), f = fold(t);
      var code = null;
      if (B.has(CODES, U) && (!strict || t === U)) code = CODES[U];
      else if (B.has(NAMES, f)) code = NAMES[f];
      if (code) {
        // "NL SUBS": de code hoort bij het volgende woord
        var nx = toks[i + 1] ? toks[i + 1].toUpperCase() : '';
        if (B.has(SUBWORDS, nx)) { subs = subs || code; i++; } else if (audio.indexOf(code) < 0) audio.push(code);
        any = true; continue;
      }
      var m = /^([A-Z]{2,3})(SUBS?|SUBBED)$/.exec(U);
      if (m && B.has(CODES, m[1])) { subs = subs || CODES[m[1]]; any = true; continue; }
      if (U === 'VOSTFR' || U === 'VOST') { subs = subs || 'fr'; any = true; continue; }
      if (B.has(MULTIWORDS, U)) { multi = true; any = true; continue; }
      if (B.has(SUBWORDS, U) || U === 'MULTISUB' || U === 'MULTISUBS') { any = true; continue; }
      if (B.has(FILLER, U) || /^\d{1,4}$/.test(t)) continue;
      return null;
    }
    if (!any) return null;
    if (audio.length > 1) multi = true;
    return { a: audio[0] || null, s: subs, m: multi };
  }
  function merge(into, x) {
    if (!x) return into;
    if (!into) return { a: x.a, s: x.s, m: x.m };
    if (!into.a && x.a) into.a = x.a;
    else if (into.a && x.a && into.a !== x.a) into.m = true;
    if (!into.s && x.s) into.s = x.s;
    if (x.m) into.m = true;
    return into;
  }

  var PREFIX = /^\s*(?:\|\s*([A-Za-z0-9]{2,4})\s*\||\[\s*([A-Za-z0-9]{2,4})\s*\]|([A-Z0-9]{2,4})\s*[|:]\s*|([A-Z]{2,3})\s+-\s+)/;
  var BRACKET = /\s*[\(\[]([^\(\)\[\]]{1,32})[\)\]]/g;

  /** Titel ontleden: {info: {a, s, m} | null, title: titel zonder de taal-tags}. */
  CL.analyze = function (raw) {
    var s = String(raw || ''), info = null;
    // 1. voorvoegsels: "NL | ", "NL: ", "|NL| ", "[NL] ", "NL - " (ook "4K | NL | ")
    for (var r = 0; r < 3; r++) {
      var m = PREFIX.exec(s);
      if (!m) break;
      var tag = m[1] || m[2] || m[3] || m[4];
      var U = tag.toUpperCase();
      var rest = s.substring(m[0].length);
      if (B.has(PREFIX_SKIP, U)) { s = rest; continue; }
      if (!B.has(CODES, U) || (m[3] && tag !== U) || (m[4] && tag !== U)) break;
      // "IT: Chapter Two" en "IT: Welcome to Derry" zijn titels, geen Italiaanse versie
      if (U === 'IT' && /^(chapter|welcome to derry|hoofdstuk)\b/i.test(rest)) break;
      if (!rest.trim()) break;
      info = merge(info, { a: CODES[U], s: null, m: false });
      s = rest;
    }
    // 2. tussen haakjes, overal: "(IT)", "[NL]", "(NL SUBS)", "(Multi)", "(EN+NL)"
    s = s.replace(BRACKET, function (all, inner) {
      var x = readTokens(inner, true);
      if (!x) return all;
      info = merge(info, x);
      return '';
    });
    // 3. achter de titel: "NL SUBS", "NLSUBS", "MULTI", "DUAL", "DUAL AUDIO", of " - NL" / " | NL"
    var words = s.replace(/\s+$/, '').split(/\s+/);
    var cut = words.length;
    var tail = { a: null, s: null, m: false }, tailAny = false;
    while (cut > 1) {
      var w = words[cut - 1].replace(/^[\-|:]+|[\-|:]+$/g, ''), Uw = w.toUpperCase();
      if (!w) { cut--; continue; }
      var prev = words[cut - 2] || '';
      var mm = /^([A-Z]{2,3})(SUBS?)$/.exec(Uw);
      if (B.has(MULTIWORDS, Uw)) { tail.m = true; tailAny = true; cut--; continue; }
      if (Uw === 'AUDIO' && /^(MULTI|DUAL)$/i.test(prev)) { cut--; continue; }
      if (mm && B.has(CODES, mm[1])) { tail.s = tail.s || CODES[mm[1]]; tailAny = true; cut--; continue; }
      if (Uw === 'SUB' || Uw === 'SUBS') {
        if (/^[A-Z]{2,3}$/.test(prev) && B.has(CODES, prev)) { tail.s = tail.s || CODES[prev]; tailAny = true; cut -= 2; continue; }
        break;
      }
      // " - NL" of " | NL" helemaal aan het eind (met scheidingsteken ervoor)
      if (/^[A-Z]{2,3}$/.test(w) && w === words[cut - 1] && B.has(CODES, w) && (prev === '-' || prev === '|')) {
        tail.a = tail.a || CODES[w]; tailAny = true; cut -= 2; continue;
      }
      break;
    }
    if (tailAny) { info = merge(info, tail); s = words.slice(0, cut).join(' '); }
    s = s.replace(/\s{2,}/g, ' ').replace(/^[\s\-|:]+|[\s\-|:]+$/g, '');
    return { info: info, title: s || String(raw || '').trim() };
  };
  /** Alleen de taal uit een titel (of null). */
  CL.parse = function (raw) { return raw ? CL.analyze(raw).info : null; };
  /** Titel zonder taal-tags (voor weergave). */
  CL.strip = function (raw) {
    var s = String(raw || '');
    // snelle uitweg: geen haakjes, scheidingstekens of vlaggen → niets te doen
    if (!/[\(\[|:]|\s-\s|\b(SUBS?|MULTI|DUAL|AUDIO)\b|[A-Z]SUBS?\b/i.test(s)) return s;
    return CL.analyze(s).title;
  };

  /** Taal uit een categorienaam: "NL Films", "VOD | DE", "Films NL", "|NL| Series", "Nederlandse films", "Netflix (NL)". */
  CL.fromCategory = function (g) {
    if (!g) return null;
    var s = String(g).trim();
    var a = CL.analyze(s);
    if (a.info) return a.info;
    // "VOD | DE", "Films - NL", "Series: IT"
    var m = /[|:\-]\s*([A-Z]{2,3})\s*$/.exec(s);
    if (m && B.has(CODES, m[1])) return { a: CODES[m[1]], s: null, m: false };
    var w = s.split(/\s+/);
    if (w.length > 1) {
      // "NL Films", "DE Filme" (een dubbelzinnige code alleen vóór een woord als Films/Series)
      if (/^[A-Z]{2,3}$/.test(w[0]) && B.has(CODES, w[0]) && (!AMBIG[w[0]] || MEDIA.test(w[1]))) return { a: CODES[w[0]], s: null, m: false };
      // "Films NL", "Movies EN"
      var last = w[w.length - 1];
      if (/^[A-Z]{2,3}$/.test(last) && B.has(CODES, last) && (!AMBIG[last] || MEDIA.test(w[w.length - 2]))) return { a: CODES[last], s: null, m: false };
    }
    // taalnamen als los woord: "Nederlandse films", "Turkish Series", "Bollywood"
    var words = fold(s).split(/[^a-z0-9]+/);
    for (var i = 0; i < words.length; i++) if (B.has(NAMES, words[i])) return { a: NAMES[words[i]], s: null, m: false };
    if (/\bmulti\b/i.test(s)) return { a: null, s: null, m: true };
    return null;
  };

  // MARK: per titel (gecachet per bibliotheekversie)

  var memo = {}, memoVersion = -1;
  function tmdbLang(x, isShow) {
    var AR = B.ageRatings;
    var r = AR ? AR.cached(x.rawTitle || x.title, x.year, isShow) : null;
    return r && r.ol ? CL.norm(r.ol) : '';
  }
  /**
   * Taal van een film of serie: {a, s, m, from: 'title'|'category'|'tmdb'} of null.
   * Titel (x.cl van de aanbieder, die de tag voor het opschonen al las, of de titel zelf), dan de categorie, dan TMDB.
   */
  CL.forItem = function (x, isShow) {
    if (!x) return null;
    var ver = B.lib ? B.lib.version : 0;
    if (ver !== memoVersion) { memo = {}; memoVersion = ver; }
    var k = (isShow ? 's' : 'm') + x.id;
    var r = memo[k];
    if (r === undefined) {
      var t = x.cl || CL.parse(x.rawTitle || x.title);
      if (t) r = { a: t.a, s: t.s, m: !!t.m, from: 'title' };
      else {
        var c = CL.fromCategory(x.rawGroup || x.group);
        r = c ? { a: c.a, s: c.s, m: !!c.m, from: 'category' } : null;
      }
      memo[k] = r;
    }
    if (r && (r.a || r.m)) return r;
    // terugval: originele taal uit TMDB (niet onthouden: komt later binnen)
    var ol = tmdbLang(x, isShow);
    if (ol) return { a: ol, s: r ? r.s : null, m: false, from: 'tmdb' };
    return r;
  };
  CL.clear = function () { memo = {}; };

  // MARK: weergave

  var NAMES_UI = {
    nl: { nl: 'Nederlands', en: 'Engels', de: 'Duits', fr: 'Frans', es: 'Spaans', it: 'Italiaans', pt: 'Portugees', pl: 'Pools', tr: 'Turks',
      ar: 'Arabisch', hi: 'Hindi', ru: 'Russisch', sv: 'Zweeds', no: 'Noors', da: 'Deens', fi: 'Fins', el: 'Grieks', ro: 'Roemeens',
      hu: 'Hongaars', cs: 'Tsjechisch', ko: 'Koreaans', ja: 'Japans', zh: 'Chinees', th: 'Thai', fa: 'Perzisch', he: 'Hebreeuws',
      sq: 'Albanees', bg: 'Bulgaars', hr: 'Kroatisch', sr: 'Servisch', uk: 'Oekraïens', ta: 'Tamil' },
    en: { nl: 'Dutch', en: 'English', de: 'German', fr: 'French', es: 'Spanish', it: 'Italian', pt: 'Portuguese', pl: 'Polish', tr: 'Turkish',
      ar: 'Arabic', hi: 'Hindi', ru: 'Russian', sv: 'Swedish', no: 'Norwegian', da: 'Danish', fi: 'Finnish', el: 'Greek', ro: 'Romanian',
      hu: 'Hungarian', cs: 'Czech', ko: 'Korean', ja: 'Japanese', zh: 'Chinese', th: 'Thai', fa: 'Persian', he: 'Hebrew',
      sq: 'Albanian', bg: 'Bulgarian', hr: 'Croatian', sr: 'Serbian', uk: 'Ukrainian', ta: 'Tamil' },
    de: { nl: 'Niederländisch', en: 'Englisch', de: 'Deutsch', fr: 'Französisch', es: 'Spanisch', it: 'Italienisch', pt: 'Portugiesisch',
      pl: 'Polnisch', tr: 'Türkisch', ar: 'Arabisch', hi: 'Hindi', ru: 'Russisch' },
    fr: { nl: 'Néerlandais', en: 'Anglais', de: 'Allemand', fr: 'Français', es: 'Espagnol', it: 'Italien', pt: 'Portugais',
      pl: 'Polonais', tr: 'Turc', ar: 'Arabe', hi: 'Hindi', ru: 'Russe' },
    es: { nl: 'Neerlandés', en: 'Inglés', de: 'Alemán', fr: 'Francés', es: 'Español', it: 'Italiano', pt: 'Portugués',
      pl: 'Polaco', tr: 'Turco', ar: 'Árabe', hi: 'Hindi', ru: 'Ruso' },
    it: { nl: 'Olandese', en: 'Inglese', de: 'Tedesco', fr: 'Francese', es: 'Spagnolo', it: 'Italiano', pt: 'Portoghese',
      pl: 'Polacco', tr: 'Turco', ar: 'Arabo', hi: 'Hindi', ru: 'Russo' },
    pt: { nl: 'Neerlandês', en: 'Inglês', de: 'Alemão', fr: 'Francês', es: 'Espanhol', it: 'Italiano', pt: 'Português',
      pl: 'Polaco', tr: 'Turco', ar: 'Árabe', hi: 'Hindi', ru: 'Russo' },
    pl: { nl: 'Niderlandzki', en: 'Angielski', de: 'Niemiecki', fr: 'Francuski', es: 'Hiszpański', it: 'Włoski', pt: 'Portugalski',
      pl: 'Polski', tr: 'Turecki', ar: 'Arabski', hi: 'Hindi', ru: 'Rosyjski' },
    tr: { nl: 'Felemenkçe', en: 'İngilizce', de: 'Almanca', fr: 'Fransızca', es: 'İspanyolca', it: 'İtalyanca', pt: 'Portekizce',
      pl: 'Lehçe', tr: 'Türkçe', ar: 'Arapça', hi: 'Hintçe', ru: 'Rusça' },
    ar: { nl: 'الهولندية', en: 'الإنجليزية', de: 'الألمانية', fr: 'الفرنسية', es: 'الإسبانية', it: 'الإيطالية', pt: 'البرتغالية',
      pl: 'البولندية', tr: 'التركية', ar: 'العربية', hi: 'الهندية', ru: 'الروسية' },
    'zh-Hans': { nl: '荷兰语', en: '英语', de: '德语', fr: '法语', es: '西班牙语', it: '意大利语', pt: '葡萄牙语', pl: '波兰语', tr: '土耳其语',
      ar: '阿拉伯语', hi: '印地语', ru: '俄语' }
  };
  /** Naam van een taal in de taal van de app ("Italiaans"); onbekend: de code in hoofdletters. */
  CL.name = function (code) {
    var c = CL.norm(code);
    if (!c) return '';
    var t = NAMES_UI[B.lang] || NAMES_UI.en;
    return t[c] || NAMES_UI.en[c] || c.toUpperCase();
  };
  /** Tekst voor de strook onder op de poster: "NL", "IT", "EN · NL ondertitels", "Multi". */
  CL.badge = function (info) {
    if (!info) return '';
    var main = info.m ? L('Multi') : (info.a ? info.a.toUpperCase() : '');
    if (info.s && info.s !== info.a) return (main ? main + ' · ' : '') + L('%@ subtitles', info.s.toUpperCase());
    return main;
  };
  /** Regel op de detailpagina: "Taal: Italiaans" (+ " · Ondertitels: Nederlands"). */
  CL.line = function (info) {
    if (!info || (!info.a && !info.m && !info.s)) return '';
    var parts = [];
    if (info.m) parts.push(L('Language: %@', L('Multiple languages') + (info.a ? ' (' + CL.name(info.a) + ')' : '')));
    else if (info.a) parts.push(L('Language: %@', CL.name(info.a)));
    if (info.s && info.s !== info.a) parts.push(L('Subtitles: %@', CL.name(info.s)));
    return parts.join('  ·  ');
  };

  // MARK: voorkeuren (settings.shared, gedeeld met de andere apps)

  /** Kijkvoorkeur van het profiel (profile.<id>.prefs) gaat voor de gedeelde instelling (settings.shared), zoals op Android. */
  function pref(profileKey, sharedKey) {
    var v = B.data.profilePref ? B.data.profilePref(profileKey) : null;
    return v != null ? v : String(B.data.shared(sharedKey, '') || '');
  }
  /** Gewenste audiotaal: audioLanguage, standaard de taal van de app; "stream" = zoals de stream (niets kiezen → ''). */
  CL.preferredAudio = function () {
    var v = pref('audioLanguage', 'audioLanguage');
    if (v === 'stream') return '';
    return CL.norm(v) || CL.appLanguage();
  };
  /** Taal van de app als ISO 639-1 ("zh-Hans" → "zh"). */
  CL.appLanguage = function () { return CL.norm(B.lang) || 'en'; };
  /** Gewenste ondertiteltaal: subtitleLanguage (in prefs: streamSubtitleLanguage), anders de taal van de app. */
  CL.preferredSubtitle = function () {
    var raw = pref('streamSubtitleLanguage', 'subtitleLanguage');
    var v = raw === 'off' || raw === 'stream' ? '' : CL.norm(raw);
    return v || CL.appLanguage();
  };
  /** "off" / "whenNeeded" / "always"; null = onbekend (zelfde waarden als TrackPreferences.normalizedMode). */
  function mode(v) {
    var s = String(v == null ? '' : v).trim().toLowerCase();
    if (s === 'off' || s === 'none' || s === 'never') return 'off';
    if (s === 'whenneeded' || s === 'when_needed' || s === 'auto' || s === 'needed') return 'whenNeeded';
    if (s === 'always' || s === 'on' || s === 'true' || s === '1') return 'always';
    if (s === 'false' || s === '0') return 'whenNeeded';
    return null;
  }
  CL.autoSubtitleMode = mode;
  /**
   * Automatische ondertitels: "off" | "whenNeeded" | "always", standaard "whenNeeded".
   * Oudere apps zetten true/false: aan = "always", uit = de nieuwe standaard (zoals Android). Het oude
   * subtitleLanguage "off" betekent "off" als er nog geen stand is (ACCOUNT.md, build 59).
   */
  CL.autoSubtitles = function () {
    // profiel: een oude false telt niet als keuze (zoals Android)
    var pv = B.data.profilePref ? B.data.profilePref('autoSubtitles') : null;
    var pm = pv != null && pv !== 'false' && pv !== '0' ? mode(pv) : null;
    if (pm) return pm;
    var m = mode(B.data.shared('autoSubtitles', null));
    if (m) return m;
    var sl = pref('streamSubtitleLanguage', 'subtitleLanguage');
    return sl === 'off' ? 'off' : 'whenNeeded';
  };

  // MARK: andere taalversies (zelfde TMDB-id, of dezelfde opgeschoonde titel + jaar)

  function titleKey(x) {
    var t = B.parseTitle(CL.strip(x.rawTitle || x.title)).title;
    return fold(t).replace(/[^a-z0-9]+/g, '');
  }
  var vIndex = null, vVersion = -1;
  function index() {
    var Lib = B.lib;
    if (vIndex && vVersion === Lib.version) return vIndex;
    var ix = { movie: {}, series: {}, tmdbM: {}, tmdbS: {} };
    var AR = B.ageRatings;
    function add(list, byTitle, byTmdb, isShow) {
      (list || []).forEach(function (x) {
        var k = titleKey(x);
        if (k) (byTitle[k] = byTitle[k] || []).push(x);
        var r = AR ? AR.cached(x.rawTitle || x.title, x.year, isShow) : null;
        if (r && r.id != null) (byTmdb[r.id] = byTmdb[r.id] || []).push(x);
      });
    }
    var lib = Lib.lib || {};
    add(lib.movies, ix.movie, ix.tmdbM, false);
    add(lib.series, ix.series, ix.tmdbS, true);
    vIndex = ix; vVersion = Lib.version;
    return ix;
  }
  B.on('ageRatings', function () { vIndex = null; });
  /**
   * Andere taalversies van een film of serie: [{item, lang: {a, s, m}, label}], zonder de titel zelf
   * en zonder versies in dezelfde taal.
   */
  CL.versions = function (x, type) {
    if (!x || !B.lib || !B.lib.lib) return [];
    var isShow = type === 'series';
    var ix = index();
    var seen = {}, out = [];
    seen[x.id] = true;
    var mine = CL.forItem(x, isShow);
    var myKey = mine ? (mine.m ? 'multi' : (mine.a || '')) + '|' + (mine.s || '') : '';
    function consider(y) {
      if (seen[y.id]) return;
      seen[y.id] = true;
      if (x.year && y.year && Math.abs(x.year - y.year) > 1) return;
      var lg = CL.forItem(y, isShow);
      if (!lg || (!lg.a && !lg.m && !lg.s)) return;
      var key = (lg.m ? 'multi' : (lg.a || '')) + '|' + (lg.s || '');
      if (key === myKey) return;
      out.push({ item: y, lang: lg });
    }
    var AR = B.ageRatings;
    var r = AR ? AR.cached(x.rawTitle || x.title, x.year, isShow) : null;
    if (r && r.id != null) ((isShow ? ix.tmdbS : ix.tmdbM)[r.id] || []).forEach(consider);
    (ix[isShow ? 'series' : 'movie'][titleKey(x)] || []).forEach(consider);
    out.forEach(function (v) {
      var lg = v.lang;
      var name = lg.m ? L('Multiple languages') : CL.name(lg.a || lg.s);
      if (!lg.m && !lg.a && lg.s) name = L('Subtitles: %@', CL.name(lg.s));
      else if (lg.s && lg.s !== lg.a) name += ' · ' + L('Subtitles: %@', CL.name(lg.s));
      v.label = name;
    });
    out.sort(function (p, q) { return p.label < q.label ? -1 : p.label > q.label ? 1 : 0; });
    return out;
  };
  CL._titleKey = titleKey;
})();
