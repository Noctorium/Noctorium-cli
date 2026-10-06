package app.noctorium.cli.web

import app.noctorium.cli.CliParts
import app.noctorium.cli.TerminalBridge
import app.noctorium.cli.tui.WebSwitch
import app.noctorium.cli.update.CliUpdates
import app.noctorium.core.AppState
import app.noctorium.playback.MpvPlaybackEngine
import app.noctorium.settings.AppDirectories
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * `noctorium web`: the whole of Noctorium in a browser, served by this computer to the ones around it.
 *
 * There is no Noctorium server anywhere else involved, and nobody else's account on this one. The page is
 * this program's own, and so is everything it shows: the same AppState as the terminal player, with the
 * library and queue and likes of whoever signed in here. That is the only way a web player can work and
 * keep Noctorium's promise -- that a session never leaves the device it was made on -- and the only way it
 * works at all: YouTube refuses streams to the addresses of the big hosting companies, and serves this
 * house's address happily.
 *
 * Getting in takes a key, made once and kept, carried in the link the terminal prints and the code it
 * shows; the page swaps it for a cookie on first visit. Anybody on the network without it gets the page and
 * nothing else.
 */
class WebPlayer(
    private val state: AppState,
    private val parts: CliParts,
    private val player: SwitchingEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wire = Wire(state, player)
    private val proxy = AudioProxy(player.browser, parts.backend)
    private val commands = WebCommands(state, player, scope, checkForUpdates = ::checkForUpdates) { notice(it) }
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private var server: EmbeddedServer<*, *>? = null
    private var stopped = CountDownLatch(1)

    /** The address a browser elsewhere on the network opens, with the key; null while it is off. */
    @Volatile
    var address: String? = null
        private set

    /** The same, for a browser on this computer. */
    @Volatile
    var localAddress: String? = null
        private set

    val key: String by lazy {
        val file = AppDirectories.resolve("web.key")
        file?.takeIf(Files::isRegularFile)?.let { runCatching { Files.readString(it).trim() }.getOrNull() }?.takeIf { it.length >= 24 }
            ?: run {
                val bytes = ByteArray(18).also(SecureRandom()::nextBytes)
                val made = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                file?.let { runCatching { Files.createDirectories(it.parent); Files.writeString(it, made) } }
                made
            }
    }

    /** One open page. */
    private inner class Connection(val session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        val dirty = ConcurrentHashMap.newKeySet<String>()
        val signal = Channel<Unit>(Channel.CONFLATED)
        val sent = ConcurrentHashMap<String, String>()

        fun send(text: String): Boolean = session.outgoing.trySend(Frame.Text(text)).isSuccess

        fun mark(part: String) {
            dirty += part
            signal.trySend(Unit)
        }
    }

    fun start(preferredPort: Int = 7300, network: Boolean = true): String? {
        if (server != null) return null
        val host = if (network) "0.0.0.0" else "127.0.0.1"
        var lastProblem: Throwable? = null
        for (port in preferredPort until preferredPort + 10) {
            val attempt = runCatching {
                embeddedServer(CIO, port = port, host = host) { configure() }.start(wait = false)
            }
            attempt.onSuccess { started ->
                server = started
                stopped = CountDownLatch(1)
                localAddress = "http://127.0.0.1:$port/?key=$key"
                address = if (network) lanAddress()?.let { "http://$it:$port/?key=$key" } ?: localAddress else localAddress
                watch()
                return null
            }
            lastProblem = attempt.exceptionOrNull()
        }
        return "The web player could not start: ${lastProblem?.message ?: "no free port from $preferredPort"}"
    }

    fun stop() {
        server?.let { running ->
            runCatching { running.stop(500, 1500) }
        }
        server = null
        address = null
        localAddress = null
        player.browser.sink = null
        stopped.countDown()
    }

    fun join() = stopped.await()

    fun asSwitch(): WebSwitch = object : WebSwitch {
        override val address: String? get() = this@WebPlayer.address
        override fun start(): String? = this@WebPlayer.start()
        override fun stop() = this@WebPlayer.stop()
    }

    private var watching = false

    /** Every part of the state that changes marks itself on every open page; each page sends what differs. */
    private fun watch() {
        if (watching) return
        watching = true
        fun Flow<*>.marks(vararg parts: String) = scope.launch { collect { connections.forEach { c -> parts.forEach(c::mark) } } }
        state.playback.marks("playback")
        player.output.marks("playback")
        state.sleepTimer.marks("playback")
        state.queue.state.marks("queue")
        // The queue's part says what autoplay is doing, which depends on the setting as well as on the queue.
        state.settings.map { it.preferences.autoplay }.distinctUntilChanged().marks("queue")
        state.ui.marks("home", "search")
        state.library.marks("library")
        state.likes.marks("likes")
        state.lyrics.marks("lyrics")
        state.settings.marks("settings")
        state.downloadState.marks("downloads")
        state.connect.marks("connect")
        state.signInTransfer.marks("signIn")
        state.account.marks("account")
        // While something plays in a browser, the next track's stream is found ahead of time.
        scope.launch {
            state.playback.map { it.status to it.track?.queueKey }.distinctUntilChanged().collect { (status, _) ->
                if (status != app.noctorium.playback.PlaybackStatus.PLAYING || player.output.value != Output.BROWSER) return@collect
                val queue = state.queue.state.value
                // At the queue's end, whatever an advance would play there: its first again, or autoplay's first.
                (queue.tracks.getOrNull(queue.currentIndex + 1) ?: queue.upcoming?.takeIf { it.queueKey != queue.current?.queueKey })
                    ?.let(player.browser::prefetch)
            }
        }
        // Messages core wants shown, passed to every page as a passing notice.
        scope.launch { state.likes.collect { it.message?.let { m -> notice(m) } } }
        scope.launch { state.library.collect { it.notice?.let { m -> notice(m) } } }
        scope.launch { state.downloadState.collect { it.message?.let { m -> notice(m) } } }
        scope.launch { state.connect.collect { it.message?.let { m -> notice(m) } } }
        scope.launch { state.settings.collect { it.message?.let { m -> notice(m) } } }
        val previous = parts.bridge.notices
        parts.bridge.notices = { n ->
            previous(n)
            when (n) {
                is TerminalBridge.Notice.OpenLink -> broadcast(buildJsonObject { put("kind", "open"); put("url", n.url) })
                is TerminalBridge.Notice.Copied -> broadcast(buildJsonObject { put("kind", "copy"); put("text", n.text) })
            }
        }
    }

    private val recentNotices = ConcurrentHashMap<String, Long>()

    /** Something every open page should show in passing: `good`, `bad`, or `normal`. */
    fun announce(text: String, tone: String = "normal") = notice(text, tone)

    /**
     * A page asked whether there is a newer Noctorium CLI. The answer, whatever it is, comes back to every
     * page, and when there is one this copy can install, it is installed: the page's button is the same
     * question as the settings row in the terminal.
     */
    private fun checkForUpdates() {
        scope.launch(Dispatchers.IO) {
            notice("Looking for a newer Noctorium CLI…")
            val result = parts.updates.run(install = true)
            parts.updates.headline(result, asked = true)?.let { line ->
                notice(line, if (result is CliUpdates.Result.Failed) "bad" else "normal")
            }
        }
    }

    private fun notice(text: String, tone: String = "normal") {
        val now = System.currentTimeMillis()
        // The same message from two flows in the same moment is one message.
        if ((recentNotices[text] ?: 0) > now - 1500) return
        recentNotices[text] = now
        broadcast(buildJsonObject { put("kind", "notice"); put("text", text); put("tone", tone) })
    }

    private fun broadcast(message: JsonObject) {
        val text = message.toString()
        connections.forEach { it.send(text) }
    }

    private fun io.ktor.server.application.Application.configure() {
        install(WebSockets) { pingPeriod = 20.seconds }
        routing {
            get("/api/session") {
                if (!authorised(call)) return@get call.respondText("""{"ok":false}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
            get("/api/state") {
                if (!authorised(call)) return@get call.respondText("Unauthorised", status = HttpStatusCode.Unauthorized)
                val all = buildJsonObject { wire.all().forEach { (name, part) -> put(name, part()) } }
                call.respondText(all.toString(), ContentType.Application.Json)
            }
            get("/api/audio/{token}") {
                if (!authorised(call)) return@get call.respondText("Unauthorised", status = HttpStatusCode.Unauthorized)
                proxy.serve(call, call.parameters["token"].orEmpty(), call.request.queryParameters["part"])
            }
            get("/api/qr") {
                if (!authorised(call)) return@get call.respondText("Unauthorised", status = HttpStatusCode.Unauthorized)
                val text = call.request.queryParameters["text"]?.takeIf { it.length in 1..1024 }
                    ?: return@get call.respondText("Nothing to encode", status = HttpStatusCode.BadRequest)
                call.respondText(qrSvg(text), ContentType.parse("image/svg+xml"))
            }
            get("/api/address") {
                if (!authorised(call)) return@get call.respondText("Unauthorised", status = HttpStatusCode.Unauthorized)
                call.respondText(buildJsonObject { put("address", address); put("local", localAddress) }.toString(), ContentType.Application.Json)
            }
            webSocket("/api/live") {
                // Only from the page this program served: a cookie alone could be sent along by another site.
                val origin = call.request.headers[HttpHeaders.Origin]
                val hostHeader = call.request.headers[HttpHeaders.Host]
                if (!authorised(call) || (origin != null && hostHeader != null && !origin.endsWith("//$hostHeader"))) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No key"))
                    return@webSocket
                }
                val connection = Connection(this)
                connections += connection
                wire.all().keys.forEach(connection::mark)
                val pusher = launch {
                    for (signal in connection.signal) {
                        delay(30)
                        val names = connection.dirty.toList()
                        connection.dirty.removeAll(names.toSet())
                        for (name in names) {
                            val part = wire.all()[name] ?: continue
                            val json = runCatching { part().toString() }.getOrNull() ?: continue
                            if (connection.sent[name] == json) continue
                            connection.sent[name] = json
                            connection.send("""{"kind":"part","name":"$name","data":$json}""")
                        }
                    }
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                        received(connection, message)
                    }
                } finally {
                    pusher.cancel()
                    connections -= connection
                    if (sinkOwner == connection) {
                        sinkOwner = null
                        player.browser.sink = null
                        player.browser.report("gone", 0, null, null, null)
                    }
                }
            }
            get("/{path...}") { page(call) }
            get("/") { page(call) }
        }
    }

    @Volatile
    private var sinkOwner: Connection? = null

    /** The page that plays is the one that last asked something to play, or said so outright. */
    private fun claim(connection: Connection) {
        if (sinkOwner == connection) return
        sinkOwner?.send("""{"kind":"audio","command":"release"}""")
        sinkOwner = connection
        player.browser.sink = { message -> connection.send(message.toString()) }
    }

    private val starts = setOf("play", "playPlaylist", "playLocal", "playDownloads", "playSuggestion", "toggle", "jump", "next", "previous", "seek", "openLink")

    private fun received(connection: Connection, message: JsonObject) {
        when (message["kind"]?.jsonPrimitive?.contentOrNull) {
            "command" -> {
                val id = message["id"]?.jsonPrimitive?.intOrNull ?: 0
                val command = message["command"] as? JsonObject ?: return
                val type = command["type"]?.jsonPrimitive?.contentOrNull
                if (player.output.value == Output.BROWSER && type in starts) claim(connection)
                if (type == "output" && command["to"]?.jsonPrimitive?.contentOrNull == "browser") claim(connection)
                val problem = runCatching { commands.run(command) }.getOrElse { it.message ?: "That did not work" }
                connection.send(buildJsonObject { put("kind", "reply"); put("id", id); problem?.let { put("error", it) } }.toString())
            }
            "claim" -> claim(connection)
            "audio" -> {
                if (connection != sinkOwner) return
                player.browser.report(
                    event = message["event"]?.jsonPrimitive?.contentOrNull ?: return,
                    load = message["generation"]?.jsonPrimitive?.intOrNull ?: -1,
                    positionMs = message["positionMs"]?.jsonPrimitive?.longOrNull,
                    durationMs = message["durationMs"]?.jsonPrimitive?.longOrNull,
                    message = message["message"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    private fun authorised(call: ApplicationCall): Boolean {
        val given = call.request.cookies["noctorium_key"] ?: call.request.headers["X-Noctorium-Key"] ?: call.request.queryParameters["key"]
        return given != null && constantTimeEquals(given, key)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** The page, and the files it is made of, from inside this program's jar. */
    private suspend fun page(call: ApplicationCall) {
        // Arriving with the key: keep it as a cookie, and take it out of the address bar and the history.
        call.request.queryParameters["key"]?.let { given ->
            if (constantTimeEquals(given, key)) {
                call.response.cookies.append(
                    Cookie(
                        "noctorium_key", key, path = "/", httpOnly = true, maxAge = 60 * 60 * 24 * 365,
                        extensions = mapOf("SameSite" to "Strict"),
                    ),
                )
                return call.respondRedirect(call.request.path().ifBlank { "/" })
            }
        }
        val path = call.request.path().removePrefix("/").ifBlank { "index.html" }
        if (".." in path) return call.respondText("No", status = HttpStatusCode.BadRequest)
        val direct = WebPlayer::class.java.getResourceAsStream("/web/$path")
        // Anything that is not one of the page's files is a place inside it -- /library, /playlist/<key>,
        // whatever a key contains -- and is the page itself. Only a missing asset is really missing.
        val isRoute = direct == null && !path.startsWith("assets/") && !path.startsWith("api/")
        val resource = direct ?: if (isRoute) WebPlayer::class.java.getResourceAsStream("/web/index.html") else null
        if (resource == null) {
            if (isRoute || path == "index.html") return call.respondText(MISSING_PAGE, ContentType.Text.Html)
            return call.respondText("Not found", status = HttpStatusCode.NotFound)
        }
        val bytes = resource.use { it.readBytes() }
        val served = if (isRoute) "index.html" else path
        call.response.header(HttpHeaders.CacheControl, if (served.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.respondBytes(bytes, contentTypeOf(served))
    }

    private fun contentTypeOf(path: String): ContentType = when (path.substringAfterLast('.').lowercase()) {
        "html" -> ContentType.Text.Html
        "js", "mjs" -> ContentType.parse("text/javascript")
        "css" -> ContentType.Text.CSS
        "svg" -> ContentType.parse("image/svg+xml")
        "png" -> ContentType.Image.PNG
        "ico" -> ContentType.parse("image/x-icon")
        "json", "webmanifest" -> ContentType.parse("application/manifest+json")
        "woff2" -> ContentType.parse("font/woff2")
        "txt" -> ContentType.Text.Plain
        else -> ContentType.Application.OctetStream
    }

    private fun qrSvg(text: String): String {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val path = StringBuilder()
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) path.append("M$x,${y}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }

    /** This computer's address on the home network: a private IPv4 address, and not a VPN's or a VM's if there is a choice. */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface ->
                val name = (iface.displayName + " " + iface.name).lowercase()
                if (listOf("vmware", "virtualbox", "vethernet", "wsl", "hyper-v", "docker", "tailscale", "zerotier", "vpn", "tun", "tap").any { it in name }) 1 else 0
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        fun engineFor(parts: CliParts, preferBrowser: Boolean): SwitchingEngine = SwitchingEngine(
            computer = MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile),
            browser = BrowserEngine(parts.backend, parts.downloads::localFile),
            start = if (preferBrowser) Output.BROWSER else Output.COMPUTER,
        )

        private const val MISSING_PAGE = """<!doctype html><meta charset="utf-8"><title>Noctorium</title>
<body style="background:#000;color:#f8f4ff;font:16px system-ui;display:grid;place-items:center;height:100vh;margin:0">
<div style="max-width:32rem;text-align:center"><h1 style="color:#b47cff">Noctorium</h1>
<p>This build of the terminal player was made without the web player's page. Build it from Noctorium-cli with noctorium-web-player beside it.</p></div>"""
    }
}
