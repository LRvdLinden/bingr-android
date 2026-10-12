package nl.lrvdlinden.bingr

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Doorgeefluik op de telefoon voor Chromecast (build 56), alleen als terugval.
 *
 * Waarom: de standaard-ontvanger speelt HLS via MSE en haalt de playlist en segmenten met XHR op. Daarvoor moet de
 * IPTV-server CORS-headers sturen (Access-Control-Allow-Origin), en veel Xtream-servers doen dat niet of weigeren de
 * user-agent van de Chromecast. Dan faalt casten, ook al is de stream gewoon HLS. Via dit doorgeefluik haalt de
 * telefoon de stream op (met de user-agent van de app) en geeft hem in het lokale netwerk door met CORS-headers;
 * adressen in HLS-playlists worden herschreven zodat ook de segmenten via de telefoon lopen.
 *
 * Build 57: ook ondertitels. De Chromecast toont alleen WebVTT en haalt het bestand met XHR op, dus met CORS-headers.
 * Een gekozen ondertitel (OpenSubtitles-download of ander bestand, of een los SRT/VTT-adres) staat hier als
 * `/<token>/sub/<n>.vtt`, omgezet naar WebVTT ([WebVTT]). Dat werkt ook als de stream zelf rechtstreeks gaat.
 *
 * Alleen bereikbaar met een willekeurig token in het pad, en alleen zolang er een Cast-sessie is.
 */
object CastRelay {
    private var server: ServerSocket? = null
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cast-relay").apply { isDaemon = true } }
    @Volatile private var host: String? = null

    /** Ondertitel die de Chromecast kan ophalen: een lokaal bestand of een los adres (SRT/VTT), met vertraging in seconden. */
    private class SubSource(val file: File?, val remote: String?, val shift: Double)
    private val subs = ConcurrentHashMap<String, SubSource>()
    private val subCounter = AtomicInteger(0)

    /** Begin van elk adres ("http://ip:poort/token"); start de server als dat nodig is. null = geen wifi-adres. */
    @Synchronized
    private fun base(): String? {
        val ip = localIPv4() ?: return null
        val s = server ?: runCatching { start() }.getOrNull() ?: return null
        host = "$ip:${s.localPort}"
        return "http://$host/$token"
    }

    /** Lokaal adres waarop de Chromecast [upstream] via de telefoon kan ophalen; null = geen wifi-adres. */
    fun url(upstream: String, ext: String): String? = base()?.let { "$it/${encode(upstream)}/s.$ext" }

    /**
     * Adres van een ondertitel als WebVTT voor de Chromecast: [file] (lokaal) of [remote] (http(s), SRT of VTT).
     * Elk aanroep geeft een nieuw adres, zodat de ontvanger nooit een oude versie uit zijn cache haalt.
     */
    fun subtitleUrl(file: File? = null, remote: String? = null, shiftSeconds: Double = 0.0): String? {
        if (file == null && remote.isNullOrBlank()) return null
        val b = base() ?: return null
        val id = subCounter.incrementAndGet().toString()
        // alleen de laatste paar bewaren
        if (subs.size > 8) subs.keys.mapNotNull { it.toIntOrNull() }.sorted().dropLast(4).forEach { subs.remove(it.toString()) }
        subs[id] = SubSource(file, remote, shiftSeconds)
        return "$b/sub/$id.vtt"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        subs.clear()
    }

    private fun start(): ServerSocket {
        val s = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }, "cast-relay-accept").apply { isDaemon = true }.start()
        return s
    }

    private fun encode(u: String) = Base64.encodeToString(u.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(s: String) = runCatching { String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrNull()

    /** IPv4-adres van de telefoon in het lokale netwerk (wifi/ethernet, geen mobiele data of VPN als het kan). */
    private fun localIPv4(): String? {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        val cands = all.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { ni.name to it } }
        val best = cands.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: cands.firstOrNull { !it.first.startsWith("rmnet") && !it.first.startsWith("tun") }
        return best?.second?.hostAddress
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        val input = BufferedInputStream(sock.getInputStream())
        val reqLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
        }
        val parts = reqLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1) ?: return
        val out = sock.getOutputStream()
        if (method == "OPTIONS") { writeHead(out, 204, "No Content", emptyMap()); return }
        val segs = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
        if (segs.size < 2 || segs[0] != token) { writeHead(out, 404, "Not Found", emptyMap()); return }
        if (segs[1] == "sub") { serveSubtitle(out, method, segs.getOrNull(2)?.substringBefore('.')); return }
        val target = decode(segs[1]) ?: run { writeHead(out, 400, "Bad Request", emptyMap()); return }
        // build 60: een download (bestand op de telefoon) casten; de Chromecast kan geen file://-adres openen
        if (target.startsWith("file:", true)) { serveFile(out, method, target, headers["range"]); return }
        val upstream = target.takeIf { it.startsWith("http", true) } ?: run { writeHead(out, 400, "Bad Request", emptyMap()); return }

        val rb = Request.Builder().url(upstream).header("User-Agent", Http.USER_AGENT)
        headers["range"]?.let { rb.header("Range", it) }
        if (method == "HEAD") rb.head()
        val resp = try { Http.client.newCall(rb.build()).execute() } catch (e: Exception) {
            writeHead(out, 502, "Bad Gateway", emptyMap()); return
        }
        resp.use { r ->
            val finalUrl = r.request.url.toString()
            val ctype = r.header("Content-Type").orEmpty()
            val isPlaylist = ctype.contains("mpegurl", true) || finalUrl.substringBefore('?').lowercase(Locale.ROOT).endsWith(".m3u8")
            if (isPlaylist && r.isSuccessful && method == "GET") {
                val text = r.body?.string().orEmpty()
                val body = rewritePlaylist(text, finalUrl).toByteArray()
                writeHead(out, 200, "OK", mapOf(
                    "Content-Type" to "application/vnd.apple.mpegurl",
                    "Content-Length" to body.size.toString(),
                    "Cache-Control" to "no-cache",
                ))
                out.write(body); out.flush()
                return
            }
            val h = LinkedHashMap<String, String>()
            h["Content-Type"] = ctype.ifEmpty { "video/mp2t" }
            r.header("Content-Length")?.let { h["Content-Length"] = it }
            r.header("Content-Range")?.let { h["Content-Range"] = it }
            r.header("Accept-Ranges")?.let { h["Accept-Ranges"] = it }
            writeHead(out, r.code, r.message.ifEmpty { "OK" }, h)
            if (method == "HEAD") return
            val src = r.body?.byteStream() ?: return
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            } catch (_: SocketTimeoutException) {
            } catch (_: java.io.IOException) {
                // Chromecast heeft de verbinding gesloten (zappen/stoppen): normaal
            }
        }
    }

    /**
     * Lokaal bestand (download, build 60) met HTTP Range, zodat de Chromecast kan spoelen. Alleen bestanden in de
     * eigen app-mappen; al het andere geeft 404.
     */
    private fun serveFile(out: OutputStream, method: String, uri: String, range: String?) {
        val f = runCatching { File(java.net.URI(uri)).canonicalFile }.getOrNull()
        if (f == null || !f.isFile || !allowedFile(f)) { writeHead(out, 404, "Not Found", emptyMap()); return }
        val len = f.length()
        val type = when (f.extension.lowercase(Locale.ROOT)) {
            "mp4", "m4v", "mov" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            else -> "application/octet-stream"
        }
        var start = 0L
        var end = len - 1
        var partial = false
        Regex("bytes=(\\d*)-(\\d*)").find(range.orEmpty())?.let { m ->
            val a = m.groupValues[1].toLongOrNull()
            val b = m.groupValues[2].toLongOrNull()
            when {
                a != null -> { start = a; if (b != null) end = minOf(b, len - 1) }
                b != null -> start = maxOf(0L, len - b)
            }
            partial = true
        }
        if (len <= 0L || start >= len || start > end) {
            writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$len")); return
        }
        val count = end - start + 1
        val h = linkedMapOf("Content-Type" to type, "Content-Length" to count.toString(), "Accept-Ranges" to "bytes")
        if (partial) h["Content-Range"] = "bytes $start-$end/$len"
        writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", h)
        if (method == "HEAD") return
        try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var left = count
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.flush()
            }
        } catch (_: java.io.IOException) {
            // Chromecast heeft de verbinding gesloten (spoelen/stoppen): normaal
        }
    }

    private fun allowedFile(f: File): Boolean {
        val ctx = AppState.appContext
        val roots = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        return roots.any { f.path.startsWith(it + File.separator) }
    }

    /** Ondertitel als WebVTT (UTF-8) met CORS-headers; de ontvanger weigert tekstsporen zonder Access-Control-Allow-Origin. */
    private fun serveSubtitle(out: OutputStream, method: String, id: String?) {
        val src = id?.let { subs[it] } ?: run { writeHead(out, 404, "Not Found", emptyMap()); return }
        val raw: ByteArray? = when {
            src.file != null -> runCatching { src.file.readBytes() }.getOrNull()
            src.remote != null -> runCatching {
                Http.client.newCall(Request.Builder().url(src.remote).header("User-Agent", Http.USER_AGENT).build()).execute()
                    .use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }.getOrNull()
            else -> null
        }
        if (raw == null) { writeHead(out, 502, "Bad Gateway", emptyMap()); return }
        val body = WebVTT.from(SRT.decode(raw), src.shift).toByteArray(Charsets.UTF_8)
        writeHead(out, 200, "OK", mapOf(
            "Content-Type" to "text/vtt; charset=utf-8",
            "Content-Length" to body.size.toString(),
            "Cache-Control" to "no-cache",
        ))
        if (method != "HEAD") { out.write(body); out.flush() }
    }

    /** Elk adres in een HLS-playlist (regels en URI="…") omzetten naar een adres via het doorgeefluik. */
    internal fun rewritePlaylist(text: String, base: String): String {
        val b = base.toHttpUrlOrNull() ?: return text
        fun relay(ref: String): String {
            val abs = b.resolve(ref.trim())?.toString() ?: return ref
            val ext = abs.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() && it.length <= 5 } ?: "ts"
            return "http://$host/$token/${encode(abs)}/s.$ext"
        }
        val uriAttr = Regex("URI=\"([^\"]+)\"")
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> if (t.contains("URI=\"")) uriAttr.replace(line) { "URI=\"" + relay(it.groupValues[1]) + "\"" } else line
                else -> relay(t)
            }
        }
    }

    private fun writeHead(out: OutputStream, code: Int, msg: String, h: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\n")
        h.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray()); out.flush()
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
