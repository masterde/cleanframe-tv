package io.github.mdrzn.cleanframetv

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * "Upload photos": a small page on the local network, open only while the TV shows its address and code.
 * Every request needs the 6-digit code; only private-network clients are answered; it closes itself after
 * [OPEN_MS] or [MAX_FAILURES] wrong codes. Uploaded files are decoded and re-encoded by PhotoMaker, never stored as sent.
 */
class UploadServer(private val context: Context, private val photos: Photos, private val pexels: PexelsFeed, private val onUpdated: () -> Unit) {
    data class Session(val url: String, val code: String)

    private val workers = Executors.newFixedThreadPool(2)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var code = ""
    @Volatile private var closesAt = 0L
    @Volatile private var failures = 0

    @Synchronized fun start(): Session? {
        stop()
        val address = lanAddress() ?: return null
        val server = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } } catch (e: Exception) {
            try { ServerSocket(0) } catch (e2: Exception) { return null }
        }
        socket = server
        code = (100000 + SecureRandom().nextInt(900000)).toString()
        closesAt = System.currentTimeMillis() + OPEN_MS
        failures = 0
        Thread({ accept(server) }, "cleanframe-upload").apply { isDaemon = true }.start()
        return Session("http://${address.hostAddress}:${server.localPort}", code)
    }

    @Synchronized fun stop() {
        socket?.let { try { it.close() } catch (e: Exception) {} }
        socket = null
    }

    private fun accept(server: ServerSocket) {
        server.soTimeout = 1_000
        while (!server.isClosed) {
            if (System.currentTimeMillis() > closesAt) { stop(); break }
            val client = try { server.accept() } catch (e: java.net.SocketTimeoutException) { continue } catch (e: Exception) { break }
            workers.execute { client.use { handle(it) } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        try {
            val remote = client.inetAddress
            if (!(remote.isSiteLocalAddress || remote.isLinkLocalAddress || remote.isLoopbackAddress)) { reply(out, 403, "text/plain", "Forbidden"); return }
            val lines = readHead(input) ?: return
            val parts = lines.first().split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
            if (method == "GET" && path == "/") { reply(out, 200, "text/html; charset=utf-8", page()); return }
            if (method != "POST") { reply(out, 404, "text/plain", "Not found"); return }
            if (!codeMatches(headers["x-code"].orEmpty())) {
                if (++failures >= MAX_FAILURES) stop()
                reply(out, 401, "application/json", """{"error":"Wrong code. Check the code on the TV."}"""); return
            }
            val length = headers["content-length"]?.toLongOrNull() ?: -1L
            when (path) {
                "/keywords" -> {
                    if (length !in 1..2048) { reply(out, 400, "application/json", """{"error":"Keywords are too long."}"""); return }
                    val text = String(readBody(input, length.toInt()), Charsets.UTF_8)
                    if (PexelsFeed.parseKeywords(text).isEmpty()) { reply(out, 400, "application/json", """{"error":"Enter at least one keyword."}"""); return }
                    pexels.setKeywords(text)
                    reply(out, 200, "application/json", """{"keywords":${Catalog.quote(pexels.keywords)}}""")
                }
                "/upload" -> {
                    if (length !in 1..MAX_UPLOAD) { reply(out, 413, "application/json", """{"error":"Photos must be under 30 MB."}"""); return }
                    val id = "$PREFIX${System.currentTimeMillis()}"
                    val file = File(context.cacheDir, "$id.upload")
                    try {
                        file.outputStream().use { copy(input, it, length) }
                        val name = headers["x-name"].orEmpty().let { java.net.URLDecoder.decode(it, "UTF-8") }
                            .substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), " ").trim().take(80).ifEmpty { "My photo" }
                        val entry = PhotoMaker.make(file, photos.root, id).entryJson(id, name, "", MINE)
                        photos.update(PREFIX, KEEP) { it[id] = entry; true }
                        onUpdated()
                        Log.i(AndroidCatalog.TAG, "Uploaded photo ready: $id")
                        reply(out, 200, "application/json", """{"ok":true}""")
                    } catch (e: Exception) {
                        reply(out, 415, "application/json", """{"error":"That file isn’t a photo the TV can read (use JPEG, PNG or WebP)."}""")
                    } catch (e: OutOfMemoryError) {
                        reply(out, 413, "application/json", """{"error":"That photo is too large for the TV to process."}""")
                    } finally {
                        file.delete()
                    }
                }
                else -> reply(out, 404, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            Log.w(AndroidCatalog.TAG, "Upload request failed: ${e.message}")
        }
    }

    private fun codeMatches(given: String) = code.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), code.toByteArray())

    /** Request line and headers, at most 8 KB. */
    private fun readHead(input: InputStream): List<String>? {
        val bytes = java.io.ByteArrayOutputStream()
        var last4 = 0
        while (bytes.size() < 8192) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return String(bytes.toByteArray(), Charsets.ISO_8859_1).trim().split("\r\n")
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(buffer, read, length - read); if (n < 0) break; read += n }
        return buffer.copyOf(read)
    }

    private fun copy(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException()
            output.write(buffer, 0, n); left -= n
        }
    }

    private fun reply(out: OutputStream, status: Int, type: String, body: String) {
        val data = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; else -> "Bad Request" }
        out.write(("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.flush()
    }

    private fun lanAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    } catch (e: Exception) { null }

    private fun page(): String = PAGE.replace("{{keywords}}", pexels.keywords.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"))

    companion object {
        const val MINE = "mine"
        const val PREFIX = "mine-"
        const val KEEP = 60
        private const val PORT = 8080
        private const val OPEN_MS = 10L * 60 * 1000
        private const val MAX_FAILURES = 10
        private const val MAX_UPLOAD = 30L shl 20

        private val PAGE = """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CleanFrame TV</title>
<style>
:root{color-scheme:light dark;--bg:#f5f5f2;--fg:#1c1c1a;--muted:#6b6b66;--line:#d9d9d3;--card:#fff;--accent:#1c1c1a;--accent-fg:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#121211;--fg:#f0f0ec;--muted:#9a9a94;--line:#2c2c2a;--card:#1c1c1b;--accent:#f0f0ec;--accent-fg:#121211}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 -apple-system,system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:40px 16px}h1{font-size:28px;margin:0 0 4px}p{color:var(--muted);margin:0 0 24px}
section{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:20px;margin:0 0 16px}
h2{font-size:17px;margin:0 0 4px}label{display:block;font-size:14px;color:var(--muted);margin:12px 0 6px}
input[type=text],input[inputmode]{width:100%;font:inherit;padding:12px;border:1px solid var(--line);border-radius:10px;background:transparent;color:inherit}
button{font:inherit;font-weight:600;min-height:44px;padding:0 20px;border:0;border-radius:10px;background:var(--accent);color:var(--accent-fg);margin-top:12px;cursor:pointer}
.drop{display:block;border:2px dashed var(--line);border-radius:12px;padding:28px;text-align:center;color:var(--muted);cursor:pointer;margin-top:12px}
.drop.on{border-color:var(--fg)}#log{font-size:14px;color:var(--muted);margin-top:12px;white-space:pre-line}
</style>
<main><h1>CleanFrame TV</h1><p>Enter the code shown on the TV, then add photos or change the Pexels keywords.</p>
<section><label for="code">Code from the TV</label><input id="code" inputmode="numeric" autocomplete="off" maxlength="6" placeholder="6 digits"></section>
<section><h2>Upload photos</h2><p style="margin:0">JPEG, PNG or WebP, up to 30 MB each. They appear under Uploads in the TV’s Background settings.</p>
<label class="drop" id="drop">Drop photos here or choose files<input id="files" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden></label><div id="log"></div></section>
<section><h2>Pexels keywords</h2><p style="margin:0">Separate keywords with commas. The TV cycles through them.</p>
<label for="kw">Keywords</label><input id="kw" type="text" value="{{keywords}}"><button id="save">Save keywords</button><div id="kwlog" style="font-size:14px;color:var(--muted);margin-top:8px"></div></section>
</main>
<script>
var log=document.getElementById('log'),drop=document.getElementById('drop'),files=document.getElementById('files');
function code(){return document.getElementById('code').value.trim();}
function send(path,body,headers){headers=headers||{};headers['X-Code']=code();return fetch(path,{method:'POST',headers:headers,body:body}).then(function(r){return r.json().then(function(j){if(!r.ok)throw new Error(j.error||'Failed');return j;});});}
function upload(list){var i=0;(function next(){if(i>=list.length)return;var f=list[i++];log.textContent+='\n'+f.name+'… ';
send('/upload',f,{'X-Name':encodeURIComponent(f.name)}).then(function(){log.textContent+='added';}).catch(function(e){log.textContent+=e.message;}).then(next);})();}
files.onchange=function(){upload(files.files);files.value='';};
drop.ondragover=function(e){e.preventDefault();drop.classList.add('on');};drop.ondragleave=function(){drop.classList.remove('on');};
drop.ondrop=function(e){e.preventDefault();drop.classList.remove('on');upload(e.dataTransfer.files);};
document.getElementById('save').onclick=function(){var k=document.getElementById('kwlog');k.textContent='Saving…';
send('/keywords',document.getElementById('kw').value,{'Content-Type':'text/plain;charset=utf-8'}).then(function(j){k.textContent='Saved: '+j.keywords;}).catch(function(e){k.textContent=e.message;});};
</script></html>"""
    }
}
