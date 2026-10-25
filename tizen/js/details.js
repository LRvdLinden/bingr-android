/*
 * build 102: details van een film of serie (= DetailsService op iOS en js/details.js op bingr.online) en de pagina van
 * een acteur/actrice (= PersonView/PersonService), voor de afstandsbediening.
 *
 * Bronnen, in deze volgorde:
 *  1. de afspeellijst zelf (Xtream get_vod_info / get_series_info: beschrijving, genres, regisseur, cast, duur);
 *  2. TMDB met de sleutel van de server (zelfde opzoeking als leeftijden en trailers): beschrijving in de app-taal,
 *     genres, regisseur, speelduur, cast met foto's en aanbevelingen.
 * Aanbevelingen die in je bibliotheek staan, openen de titel; de rest opent Zoeken met de titel. Profielen met een
 * leeftijdsgrens zien alleen aanbevelingen uit hun eigen (gefilterde) bibliotheek, zoals op iOS en bingr.online.
 * Alles wordt in het geheugen gecachet (Promises per TMDB-id en taal).
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, Lib = B.lib, S = B.screens, AR = B.ageRatings;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };
  var DT = B.details = {};

  // build 102: geldige TMDB-locales (nl-NL, en-US, ar-SA, zh-CN …), zie AR.tmdbLang
  function tmdbLang() { return AR.tmdbLang(); }
  DT.tmdbLang = tmdbLang;
  function img(path, size) { return path ? 'https://image.tmdb.org/t/p/' + size + path : null; }
  function str(v) { v = v == null ? '' : String(v).trim(); return v || null; }
  function names(s) {
    if (Array.isArray(s)) return s.map(function (x) { return String(x).trim(); }).filter(Boolean);
    return String(s || '').split(/[,/]/).map(function (x) { return x.trim(); }).filter(Boolean);
  }
  function ageLimited() { var p = B.app && B.app.profile; return !!(p && D.ageLimit(p) != null); }
  DT.ageLimited = ageLimited;
  /** Speelduur uit de afspeellijst: duration_secs, episode_run_time of "1:52:00". */
  function runtimeOf(info) {
    var secs = B.int(info.duration_secs), ert = B.int(info.episode_run_time);
    if (secs > 0) return L('%d min', Math.round(secs / 60));
    if (ert > 0) return L('%d min', ert);
    var m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(String(info.duration || '').trim());
    if (m) {
      var mins = m[3] != null ? (+m[1]) * 60 + (+m[2]) : (+m[1]);
      if (mins > 0) return L('%d min', mins);
    }
    return null;
  }

  // MARK: TMDB

  var cache = {};   // "m|id|taal" → Promise
  function tmdbID(x, isShow, info) {
    var hint = info && B.int(isShow ? (info.tmdb || info.tmdb_id) : (info.tmdb_id || info.tmdb));
    if (hint) return Promise.resolve(hint);
    if (!AR.hasKey()) return Promise.resolve(null);
    return AR.lookup(x, isShow).then(function (r) { return r && r.found !== false && r.id != null ? r.id : null; }, function () { return null; });
  }
  function tmdbDetails(id, isShow) {
    var lang = tmdbLang(), k = (isShow ? 's|' : 'm|') + id + '|' + lang;
    if (!cache[k]) {
      // build 102: ook external_ids (IMDb-id voor de beoordelingen) en images (achtergronden voor de Ken Burns-kop)
      cache[k] = AR.tmdb((isShow ? '/tv/' : '/movie/') + id, [['language', lang],
        ['append_to_response', isShow ? 'aggregate_credits,recommendations,external_ids,images' : 'credits,recommendations,external_ids,images'],
        ['include_image_language', String(B.lang || 'en').slice(0, 2) + ',en,null']]).catch(function () { return null; });
      cache[k].then(function (r) { if (!r) delete cache[k]; });
    }
    return cache[k];
  }

  /**
   * Alles voor de detailpagina. `info` = wat de afspeellijst gaf (of null). Geeft altijd een object:
   * {overview, genres[], director, runtime, cast[{name, character, photo, personID}], recommendations[{title, original, year, poster, isShow}]}
   */
  DT.load = function (x, isShow, info) {
    info = info || {};
    var d = {
      overview: str(info.plot) || str(info.description) || str(x.plot),
      genres: names(info.genre || x.genres),
      director: str(info.director),
      runtime: runtimeOf(info),
      cast: names(info.cast || info.actors).slice(0, 20).map(function (n) { return { name: n, character: null, photo: null, personID: null }; }),
      recommendations: [], backdrops: [], tmdbRating: null, imdbID: x.imdbID || null
    };
    return tmdbID(x, isShow, info).then(function (id) {
      if (!id) return d;
      return tmdbDetails(id, isShow).then(function (f) {
        if (!f) return d;
        d.tmdbID = id;
        var o = str(f.overview);
        if (o && (B.lang !== 'en' || !d.overview)) d.overview = o;
        if (!d.genres.length) d.genres = (f.genres || []).map(function (g) { return g && g.name; }).filter(Boolean);
        if (!d.director && !isShow) {
          var dir = ((f.credits && f.credits.crew) || []).filter(function (c) { return c.job === 'Director'; }).map(function (c) { return c.name; });
          if (dir.length) d.director = dir.slice(0, 2).join(', ');
        }
        if (!d.runtime) {
          var rt = isShow ? (f.episode_run_time || [])[0] : f.runtime;
          if (rt > 0) d.runtime = L('%d min', rt);
        }
        var cast = ((isShow ? f.aggregate_credits : f.credits) || {}).cast || [];
        if (cast.length) d.cast = cast.slice(0, 20).map(function (c) {
          return { name: c.name, character: c.character || (c.roles && c.roles[0] && c.roles[0].character) || null, photo: img(c.profile_path, 'w185'), personID: c.id };
        });
        // build 102: TMDB-score, IMDb-id en achtergronden (= TitleDetails.ratings/backdrops op tvOS)
        if (f.vote_average > 0 && (f.vote_count == null || f.vote_count > 0)) d.tmdbRating = Math.round(f.vote_average * 10) / 10;
        d.imdbID = (f.external_ids && f.external_ids.imdb_id) || f.imdb_id || null;
        var bd = [];
        if (f.backdrop_path) bd.push(f.backdrop_path);
        ((f.images && f.images.backdrops) || []).forEach(function (b) { if (b && b.file_path && bd.indexOf(b.file_path) < 0) bd.push(b.file_path); });
        d.backdrops = bd.slice(0, 6).map(function (p2) { return img(p2, 'w1280'); });
        d.recommendations = ((f.recommendations && f.recommendations.results) || []).slice(0, 15).map(function (r) {
          var date = r.release_date || r.first_air_date || '';
          return { title: r.title || r.name || '?', original: r.original_title || r.original_name || null, year: B.int(date.slice(0, 4)) || null,
            poster: img(r.poster_path, 'w342'), isShow: isShow };
        });
        return d;
      });
    }).catch(function () { return d; });
  };

  // MARK: build 102: beoordelingen (= ratingsSection op tvOS: IMDb via OMDb, TMDB, Trakt en de afspeellijst)

  var omdb = {};
  /** OMDb (sleutel van de server): {imdbRating, genre, runtime} of null. */
  DT.omdb = function (imdb, x, isShow) {
    var key = B.account.serverKey('omdbAPIKey');
    if (!key) return Promise.resolve(null);
    var params = [['apikey', key]];
    if (imdb) params.push(['i', imdb]);
    else {
      var p = B.parseTitle(x.rawTitle || x.title);
      params.push(['t', (p && p.title) || x.title], ['type', isShow ? 'series' : 'movie']);
      if (x.year) params.push(['y', String(x.year)]);
    }
    var k = params.map(function (q) { return q[1]; }).join('|');
    if (!omdb[k]) {
      omdb[k] = B.http(B.query('https://www.omdbapi.com/', params), { timeout: 15000 }).then(function (r) {
        var j = r.json;
        if (!j || j.Response !== 'True') return null;
        function clean(v) { return v == null || v === 'N/A' ? null : String(v); }
        return { imdbID: clean(j.imdbID), imdbRating: clean(j.imdbRating), genre: clean(j.Genre), runtime: clean(j.Runtime) };
      }, function () { delete omdb[k]; return null; });
    }
    return omdb[k];
  };
  /** Alle beoordelingen: [{source, value}] in de volgorde IMDb, TMDB, Trakt, afspeellijst. */
  DT.ratings = function (x, isShow, d) {
    d = d || {};
    return DT.omdb(d.imdbID, x, isShow).then(function (o) {
      var imdb = d.imdbID || (o && o.imdbID) || null;
      var tr = B.trakt.rating(imdb, isShow);
      return tr.then(function (t) {
        var out = [];
        if (o && o.imdbRating) out.push({ source: 'IMDb', value: o.imdbRating });
        if (d.tmdbRating) out.push({ source: 'TMDB', value: d.tmdbRating.toFixed(1) });
        if (t) out.push({ source: 'Trakt', value: Math.round(t * 10) + '%' });
        if (x.rating > 0) out.push({ source: L('Playlist'), value: (Math.round(x.rating * 10) / 10).toFixed(1) });
        return out;
      });
    }).catch(function () { return []; });
  };
  /** Blok "Beoordelingen" (logo + bron + score). */
  DT.ratingsBlock = function (list) {
    if (!list || !list.length) return null;
    return h('div.dt-ratings', [h('div.dt-sec', L('Ratings')), h('div.dt-rating-row', list.map(function (r) {
      var logo = r.source === 'IMDb' ? h('span.imdb-badge', 'IMDb') : r.source === 'Trakt' ? h('span.trakt-badge', '✓') : h('span.star-badge', '★');
      return h('div.dt-rating', [h('div.dt-rating-src', [logo, h('span', r.source)]), h('div.dt-rating-val', r.value)]);
    }))]);
  };

  // MARK: koppelen aan de bibliotheek (= matchRecommendations op iOS, DT.match op bingr.online)

  /** Titelindex lui en in stukjes (B.chunked), per bibliotheekversie (Lib.version); tot hij klaar is geeft DT.match null. */
  var idx = null, idxVer = -1, idxP = null, idxPVer = -1, idxJob = null;
  function norm(t) { var p = B.parseTitle(t); return B.fold((p && p.title) || t).replace(/[^a-z0-9]/g, ''); }
  function indexNow() { return idx && idxVer === Lib.version ? idx : null; }
  DT.indexReady = function () {
    if (indexNow()) return Promise.resolve(idx);
    var ver = Lib.version;
    if (idxP && idxPVer === ver) return idxP;
    if (idxJob) { idxJob.cancel(); idxJob = null; }
    var lib = Lib.lib || {};
    var next = { movie: Object.create(null), series: Object.create(null) };
    var p = idxP = Promise.resolve().then(function () {
      if (idxP !== p) throw new Error('cancelled');
      idxJob = B.chunked(lib.movies || [], function (m) { var n = norm(m.rawTitle || m.title); if (n) (next.movie[n] = next.movie[n] || []).push(m); });
      return idxJob;
    }).then(function () {
      if (idxP !== p) throw new Error('cancelled');
      idxJob = B.chunked(lib.series || [], function (s) { var n = norm(s.rawTitle || s.title); if (n && !next.series[n]) next.series[n] = [s]; });
      return idxJob;
    }).then(function () {
      if (idxP !== p) throw new Error('cancelled');
      idxJob = null;
      if (Lib.version === ver) { idx = next; idxVer = ver; }
      return next;
    });
    idxPVer = ver;
    p.catch(function () { if (idxP === p) { idxP = null; idxPVer = -1; } });
    return p;
  };
  /** build 102: is de titelindex van deze bibliotheekversie klaar? */
  DT.indexReadyNow = function () { return !!indexNow(); };
  B.on('wipe', function () { idx = null; idxVer = -1; cache = {}; });
  /** Titel uit je bibliotheek bij een TMDB-titel (titel in de app-taal of origineel, met hetzelfde jaar als dat bekend is). */
  DT.match = function (r) {
    var ix = indexNow();
    if (!ix) { DT.indexReady().catch(B.noop); return null; }
    var tab = r.isShow ? ix.series : ix.movie;
    var keys = [r.title, r.original].filter(Boolean).map(norm);
    for (var i = 0; i < keys.length; i++) {
      var list = tab[keys[i]];
      if (!list) continue;
      var hit = r.isShow ? list[0] : list.filter(function (m) { return !r.year || !m.year || m.year === r.year; })[0];
      if (hit) return hit;
    }
    return null;
  };

  // MARK: bouwstenen

  function initials(n) {
    return String(n || '?').split(/\s+/).filter(Boolean).slice(0, 2).map(function (w) { return w.charAt(0).toUpperCase(); }).join('') || '?';
  }
  /** Ronde foto (w185) met de initialen als terugval (ook als de foto niet laadt). */
  function roundPhoto(url, name, cls, eager) {
    return h('div.round-pic' + (cls ? '.' + cls : ''), [U.img(url, 'round-img', initials(name), eager)]);
  }
  function castCard(c) {
    var card = h('div.cast-card.f', [roundPhoto(c.photo, c.name, 'cast-pic'), h('div.cast-name', c.name),
      c.character ? h('div.cast-role', c.character) : null]);
    card.onEnter = function () { U.push(S.person(c)); };
    card._key = 'cast:' + (c.personID || c.name);
    return card;
  }
  /** Poster van een titel die niet in je afspeellijsten staat: OK zoekt op de titel. */
  function outCard(t) {
    // vergrootglas rechtsboven: staat niet in je afspeellijsten, OK zoekt
    var card = h('div.card.poster.rec-out.f', [U.img(t.poster, 'poster-img', t.title + (t.year ? ' (' + t.year + ')' : '')),
      h('div.card-out', [U.icon('search')])]);
    card.onEnter = function () { S.searchFor(t.title); };
    card._key = 'out:' + (t.isShow ? 's:' : 'm:') + t.title + ':' + (t.year || '');
    return card;
  }

  /** Genres en regisseur onder de beschrijving (creditsBlock op iOS). */
  DT.credits = function (d) {
    var lines = [];
    if (d.genres.length) lines.push(h('div.dt-credit', [h('span.k', L('Genres') + ': '), d.genres.join(', ')]));
    if (d.director) lines.push(h('div.dt-credit', [h('span.k', L('Director') + ': '), d.director]));
    return lines;
  };
  /** Rijen "Acteurs" en "Aanbevelingen" (onderaan de detailpagina) in `box`. */
  DT.sections = function (d, box) {
    if (d.cast.length) {
      var r = S.rowBlock(L('Actors'), d.cast.map(castCard), 'dt-cast');
      if (r) box.appendChild(r);
    }
    if (!d.recommendations.length) return;
    // met de titelindex koppelen; is die nog niet klaar, dan de rij invullen zodra hij er is
    var slot = h('div.dt-recs-slot');
    box.appendChild(slot);
    function fill() {
      var row = recsRow(d);
      if (row) slot.appendChild(row);
    }
    if (indexNow()) fill();
    else DT.indexReady().then(function () { if (document.body.contains(slot)) fill(); }, B.noop);
  };
  function recsRow(d) {
    var recs = d.recommendations.map(function (r) { return { r: r, hit: DT.match(r) }; });
    if (ageLimited()) recs = recs.filter(function (x) { return x.hit; });
    if (!recs.length) return null;
    return S.rowBlock(L('Recommendations'), recs.map(function (x) {
      return x.hit ? S.posterCard(x.hit, x.r.isShow ? 'series' : 'movie') : outCard(x.r);
    }), 'dt-recs');
  }

  // MARK: Zoeken met een titel (aanbeveling of film van een acteur die niet in je afspeellijsten staat)

  /** Zoekscherm als eigen laag met de titel al ingevuld; Terug gaat terug naar waar je was. */
  S.searchFor = function (q) {
    var pg = S.pages.search();
    var el = h('div.layer.search-layer', [pg.el]);
    var s = {
      el: el,
      onBack: function () { U.pop(); return true; },
      destroy: function () { if (pg.destroy) pg.destroy(); U.forgetImgs(el); }
    };
    U.push(s);
    if (pg.setQuery) pg.setQuery(q);
    // eerste resultaat kiezen; geen resultaten: het toetsenbord
    var first = el.querySelector('.search-results .f');
    if (first) U.focus(first); else if (pg.focusIn) pg.focusIn();
    return s;
  };

  // MARK: pagina van een acteur/actrice

  function person(id) {
    var lang = tmdbLang(), k = 'p|' + id + '|' + lang;
    if (!cache[k]) {
      cache[k] = Promise.all([
        AR.tmdb('/person/' + id, [['language', lang]]),
        AR.tmdb('/person/' + id + '/combined_credits', [['language', lang]])
      ]).then(function (r) {
        var p = r[0], cr = r[1];
        if (!p) return null;
        var bio = str(p.biography);
        var enBio = bio || lang === 'en-US' ? Promise.resolve(bio) : AR.tmdb('/person/' + id, [['language', 'en-US']]).then(function (e) { return e && str(e.biography); }, function () { return null; });
        return enBio.then(function (b) { return { p: p, bio: b, credits: (cr && cr.cast) || [] }; });
      }).catch(function () { return null; });
      cache[k].then(function (r) { if (!r) delete cache[k]; });
    }
    return cache[k];
  }
  var names2id = {};
  function resolveName(name) {
    if (!name || !AR.hasKey()) return Promise.resolve(null);
    if (B.has(names2id, name)) return Promise.resolve(names2id[name]);
    return AR.tmdb('/search/person', [['query', name], ['include_adult', 'false'], ['language', tmdbLang()]]).then(function (r) {
      var list = (r && r.results) || [];
      var p = list.filter(function (x) { return x.known_for_department === 'Acting'; })[0] || list[0];
      names2id[name] = p ? p.id : null;
      return names2id[name];
    }, function () { return null; });
  }
  // talkshows, nieuws en optredens als zichzelf weglaten (= PersonService.credits); populairste eerst
  var SKIP_GENRES = [10763, 10767, 99];
  function credits(list) {
    var seen = {}, out = [];
    list.forEach(function (c) {
      if (!c || (c.media_type !== 'tv' && c.media_type !== 'movie')) return;
      var key = c.media_type + c.id;
      if (seen[key]) return;
      if ((c.genre_ids || []).some(function (g) { return SKIP_GENRES.indexOf(g) >= 0; })) return;
      if (/^(himself|herself|self|themselves)\b/i.test(c.character || '')) return;
      seen[key] = true;
      var date = c.release_date || c.first_air_date || '';
      out.push({ title: c.title || c.name || '?', original: c.original_title || c.original_name || null, year: B.int(date.slice(0, 4)) || null,
        poster: img(c.poster_path, 'w342'), isShow: c.media_type === 'tv', character: c.character || null,
        pop: (c.popularity || 0) + (c.vote_count || 0) / 100 });
    });
    return out.sort(function (a, b) { return b.pop - a.pop; });
  }
  function dept(k) {
    var m = { Acting: L('Acting'), Directing: L('Directing'), Writing: L('Writing'), Production: L('Production') };
    return B.has(m, k) ? m[k] : k;
  }
  function fmtDate(s) {
    if (!s || s.length < 10) return null;
    try { return B.fmtDate(new Date(s + 'T12:00:00').getTime(), B.lang === 'zh-Hans' ? 'zh-CN' : B.lang, { day: 'numeric', month: 'long', year: 'numeric' }); } catch (e) { return s; }
  }

  var BIO_LONG = 420;
  /**
   * Scherm van een acteur/actrice. c = {name, personID, photo} uit de castrij. Ronde foto, naam, bekend van/geboren/
   * overleden, biografie (OK klapt uit; omhoog/omlaag scrollt de uitgeklapte tekst), "Beschikbaar in Bingr" en (zonder
   * leeftijdsgrens) "Niet in je afspeellijsten". Terug gaat naar de detailpagina, met de focus weer op dezelfde acteur.
   */
  S.person = function (c) {
    var body = S.vScroller('.ps-body', { 'data-pad': '70' });
    var el = h('div.layer.person', [h('div.ps-shade'), body]);
    var alive = true;
    var s = {
      el: el,
      onBack: function () { U.pop(); return true; },
      destroy: function () { alive = false; U.forgetImgs(el); }
    };
    body.track.appendChild(h('div.state', [U.spinner()]));
    var id = B.int(c.personID);
    (id ? Promise.resolve(id) : resolveName(c.name)).then(function (pid) { return pid ? person(pid) : null; }).then(function (res) {
      // eerst de titelindex (in stukjes), dan pas "Beschikbaar in Bingr" koppelen
      return DT.indexReady().then(function () { return res; }, function () { return res; });
    }).then(function (res) {
      if (!alive) return;
      render(res);
    }).catch(function (e) {
      B.log('persoon', e && e.message);
      if (!alive) return;
      U.clear(body.track);
      body.track.appendChild(h('div.state', [h('h2', c.name || ''), h('div.dim', L('Something went wrong. Please try again.'))]));
    });

    function render(res) {
      U.clear(body.track);
      var p = res && res.p, nm = (p && p.name) || c.name || '';
      var photo = (p && img(p.profile_path, 'w342')) || c.photo || null;
      var text = h('div.ps-text', [h('h1.ps-name', nm)]);
      if (p) {
        var facts = [];
        if (p.known_for_department) facts.push(L('Known for') + ': ' + dept(p.known_for_department));
        var born = fmtDate(p.birthday);
        if (born) facts.push(L('Born %@', born) + (p.place_of_birth ? '  ·  ' + p.place_of_birth : ''));
        else if (p.place_of_birth) facts.push(p.place_of_birth);
        var died = fmtDate(p.deathday);
        if (died) facts.push(L('Died %@', died));
        if (facts.length) text.appendChild(h('div.ps-facts', facts.map(function (f) { return h('div', f); })));
        if (res.bio) text.appendChild(bioBox(res.bio));
      }
      body.track.appendChild(h('div.ps-head', [roundPhoto(photo, nm, 'ps-pic', true), text]));
      var list = res ? credits(res.credits) : [];
      var have = [], rest = [];
      list.forEach(function (cr) { var hit = DT.match(cr); if (hit) have.push({ c: cr, hit: hit }); else rest.push(cr); });
      var first = null;
      if (have.length) {
        var r1 = S.rowBlock(L('Available in Bingr'), have.map(function (x) { return S.posterCard(x.hit, x.c.isShow ? 'series' : 'movie'); }), 'ps-have');
        body.track.appendChild(r1);
        first = r1.querySelector('.f');
      }
      if (rest.length && !ageLimited()) {
        var r2 = S.rowBlock(L('Not in your playlists'), rest.slice(0, 40).map(outCard), 'ps-rest');
        body.track.appendChild(r2);
        first = first || r2.querySelector('.f');
      }
      if (!p && !have.length) body.track.appendChild(h('div.ps-none.dim', L('No information found for this person.')));
      body.track.appendChild(h('div.page-end'));
      // focus: eerste titel in een rij, anders de biografie; alleen als dit scherm nog bovenaan staat
      if (U.top() === s && (!U.focused || !el.contains(U.focused))) {
        if (first) { U.focus(first); U.scrollTo(body, 0); } else U.focusFirst(el);
      }
    }
    return s;
  };

  /** Biografie: lange tekst ingeklapt; OK = meer/minder; uitgeklapt scrollen omhoog/omlaag door de tekst. */
  function bioBox(bio) {
    var txt = h('div.ps-bio-text', bio);
    var scroll = h('div.ps-bio', [txt]);
    if (bio.length <= BIO_LONG) return h('div.ps-biobox', [h('div.ps-sec', L('Biography')), scroll]);
    var more = h('div.ps-more', L('Show more'));
    var box = h('div.ps-biobox.long.f', [h('div.ps-sec', L('Biography')), scroll, more]);
    var pos = 0;
    function setPos(v) {
      var max = Math.max(0, txt.offsetHeight - scroll.clientHeight);
      pos = B.clamp(v, 0, max);
      txt.style.transform = 'translate3d(0,' + (-pos) + 'px,0)';
      return max;
    }
    box.onEnter = function () {
      var open = box.classList.toggle('open');
      more.textContent = open ? L('Show less') : L('Show more');
      setPos(0);
      U.reveal(box);
    };
    box.onKey = function (name) {
      if (!box.classList.contains('open') || (name !== 'up' && name !== 'down')) return false;
      var before = pos;
      setPos(pos + (name === 'down' ? 160 : -160));
      return pos !== before;
    };
    box.onBlur = function () { if (box.classList.contains('open')) setPos(0); };
    return box;
  }
})();
