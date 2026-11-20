package nl.lrvdlinden.bingr

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URI
import java.security.MessageDigest

/**
 * Build 179, punt 16: WebDAV als bron (docs/ACCOUNT.md "Build 179"). Doorbladeren met PROPFIND (Depth: 1), Basic of
 * Digest (na 401), tot 6 niveaus diep en hooguit 20.000 bestanden. Films en series herkennen uit map- en bestandsnamen
 * ([Build179Logic.WebDAV.parse]); metadata komt daarna van TMDB zoals bij mediaservers. Geen live tv, geen downloads.
 * Het wachtwoord staat alleen in de privé-opslag (`playlist.<id>.password`), zoals bij de andere bronnen.
 */
class WebDAVClient(private val source: PlaylistSource, private val password: String) {
    private val base: String = source.serverURL.trim().let { if (it.endsWith("/")) it else "$it/" }
    private var digest: Map<String, String>? = null
    private var nc = 0

    companion object {
        const val MAX_DEPTH = 6
        const val MAX_FILES = 20_000
        private const val BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** WWW-Authenticate: Digest realm="…", nonce="…", qop="auth", opaque="…" */
        fun parseDigest(h: String): Map<String, String>? {
            if (!h.trim().startsWith("Digest", ignoreCase = true)) return null
            val out = HashMap<String, String>()
            Regex("(\\w+)=(\"([^\"]*)\"|([^,\\s]*))").findAll(h.substringAfter(' ')).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[3].ifEmpty { m.groupValues[4] }
            }
            return out.takeIf { it.containsKey("nonce") }
        }

        /**
         * Afspeel-URL met de inloggegevens van de WebDAV-bron waar [url] onder valt (VLC leest gebruiker en wachtwoord uit
         * het adres, ook voor Digest); null als het geen WebDAV-adres is of er al gegevens in staan.
         */
        fun playURL(url: String): String? {
            val src = Store.playlists.firstOrNull { it.kind == PlaylistKind.WEBDAV && it.serverURL.isNotBlank() &&
                url.startsWith(it.serverURL.trim().trimEnd('/'), ignoreCase = true) } ?: return null
            val u = runCatching { URI(url) }.getOrNull() ?: return null
            if (u.rawUserInfo != null || src.username.isBlank()) return null
            val pw = Store.secret(src.passwordKey) ?: ""
            fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            val info = enc(src.username) + ":" + enc(pw)
            return url.replaceFirst("://", "://$info@")
        }
    }

    private fun auth(method: String, uri: String): String? {
        if (source.username.isBlank()) return null
        val d = digest ?: return "Basic " + android.util.Base64.encodeToString("${source.username}:$password".toByteArray(), android.util.Base64.NO_WRAP)
        val realm = d["realm"] ?: ""; val nonce = d["nonce"] ?: ""
        val ha1 = md5("${source.username}:$realm:$password")
        val ha2 = md5("$method:$uri")
        val qop = d["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        nc++
        val ncs = String.format(java.util.Locale.ROOT, "%08x", nc)
        val cnonce = md5(System.nanoTime().toString()).take(16)
        val resp = if (qop != null) md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"${source.username}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\"")
            d["opaque"]?.let { append(", opaque=\"$it\"") }
            if (qop != null) append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
            d["algorithm"]?.let { append(", algorithm=$it") }
        }
    }

    /** PROPFIND Depth 1 op [url]: (href, map) per item. */
    private suspend fun list(url: String): List<Pair<String, Boolean>> = withContext(Dispatchers.IO) {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: "/"
        fun call(): okhttp3.Response {
            val b = Request.Builder().url(url).method("PROPFIND", BODY.toRequestBody(null))
                .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8").header("User-Agent", Http.USER_AGENT)
            auth("PROPFIND", path)?.let { b.header("Authorization", it) }
            return Http.client.newCall(b.build()).execute()
        }
        var r = call()
        if (r.code == 401 && digest == null) {
            val d = r.headers("WWW-Authenticate").firstNotNullOfOrNull { parseDigest(it) }
            r.close()
            if (d != null) { digest = d; r = call() }
        }
        r.use {
            if (it.code == 401) throw ServiceException(L("Wrong username or password."))
            if (it.code !in 200..299) throw Net.statusError(it.code, "")
            parse(it.body?.string() ?: "")
        }
    }

    private fun parse(xml: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        var href: String? = null
        var collection = false
        var inHref = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name.lowercase()) {
                    "response" -> { href = null; collection = false }
                    "href" -> inHref = true
                    "collection" -> collection = true
                }
                XmlPullParser.TEXT -> if (inHref) href = (href ?: "") + p.text.trim()
                XmlPullParser.END_TAG -> when (p.name.lowercase()) {
                    "href" -> inHref = false
                    "response" -> href?.let { out += it to collection }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun absolute(href: String): String = runCatching { URI(base).resolve(href).toString() }.getOrDefault(href)

    /** Inloggen testen (de hoofdmap lezen). */
    suspend fun test() { list(base) }

    suspend fun library(): Library {
        val files = ArrayList<String>()
        val seen = HashSet<String>()
        suspend fun walk(url: String, depth: Int) {
            if (depth > MAX_DEPTH || files.size >= MAX_FILES || !seen.add(url.trimEnd('/'))) return
            val items = runCatching { list(url) }.getOrElse { e -> if (depth == 0) throw e else return }
            val self = url.trimEnd('/')
            for ((href, dir) in items) {
                val abs = absolute(href)
                if (abs.trimEnd('/') == self) continue
                if (dir) walk(if (abs.endsWith("/")) abs else "$abs/", depth + 1)
                else if (files.size < MAX_FILES) files += abs
            }
        }
        walk(base, 0)
        val basePath = runCatching { URI(base).rawPath }.getOrNull() ?: "/"
        val movies = ArrayList<Movie>()
        val series = LinkedHashMap<String, Pair<Series, MutableList<Episode>>>()
        for (url in files) {
            val path = runCatching { URI(url).rawPath }.getOrNull() ?: continue
            val rel = if (path.startsWith(basePath)) "/" + path.removePrefix(basePath) else path
            val r = Build179Logic.WebDAV.parse(rel)
            val group = Build179Logic.WebDAV.decode(rel).split('/').filter { it.isNotEmpty() }.firstOrNull()
                ?.takeIf { rel.count { it == '/' } > 1 } ?: "WebDAV"
            when (r.kind) {
                "movie" -> movies += Movie(id = "dav:" + path, title = r.title ?: continue, year = r.year, group = group, streamURL = url)
                "episode" -> {
                    val title = r.title?.takeIf { it.isNotBlank() } ?: continue
                    val sid = "davs:" + Build179Logic.norm(title).trim().replace(Regex(" +"), "-")
                    val entry = series.getOrPut(sid) { Series(id = sid, title = title, group = group) to ArrayList() }
                    entry.second += Episode(id = "dav:" + path, seriesID = sid, season = r.season ?: 1, number = r.episode ?: 1,
                        title = L("Episode %d", r.episode ?: 1), streamURL = url)
                }
            }
        }
        val list = series.values.map { (s, eps) -> s.copy(episodes = eps.sortedWith(compareBy({ it.season }, { it.number }))) }
        return Library(movies = movies, series = list)
    }
}
