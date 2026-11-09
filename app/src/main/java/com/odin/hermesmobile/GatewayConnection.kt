package com.odin.hermesmobile

import android.util.Log
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hermes gateway client: JSON-RPC over WebSocket.
 *
 * Wire contract (verified live against the Hermes desktop app's gateway, and against
 * hermes-agent source apps/desktop/src + hermes_cli/dashboard_auth):
 *   1. POST /auth/password-login {provider, username, password} -> session cookies
 *      (404 unknown provider, 401 bad credentials, 429 rate limited)
 *   2. POST /api/auth/ws-ticket (with cookies) -> {ticket, ttl_seconds}  (30 s, single use)
 *   3. WS <base>/api/ws?ticket=<t>
 *   4. client frames  {"jsonrpc":"2.0","id":N,"method":M,"params":P}
 *      replies        {"jsonrpc":"2.0","id":N,"result":R} / {"id":N,"error":{code,message}}
 *      events         {"jsonrpc":"2.0","method":"event","params":{type,session_id,payload,seq}}
 */
class GatewayConnection(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val listener: Listener,
) {
    interface Listener {
        /** One of STATE_DISCONNECTED / STATE_CONNECTING / STATE_CONNECTED / STATE_LOGIN_FAILED */
        fun onState(state: String, detail: String)
        fun onSessionList(rows: List<SessionRow>)
        fun onSessionOpened(storedId: String, runtimeId: String, messages: List<Msg>)
        fun onDelta(sid: String, text: String)
        fun onThinking(sid: String, text: String)
        fun onComplete(sid: String, text: String)
        fun onTitle(storedId: String, title: String)
        fun onSessionsChanged()
        fun onApproval(promptId: String, sid: String, command: String, description: String, choices: List<String>)
        fun onClarify(promptId: String, sid: String, question: String, choices: List<String>)
        fun onError(message: String)
        fun onWsClosed(reason: String)
    }

    data class SessionRow(val id: String, val title: String, val preview: String, val count: Int, val startedAt: Long)
    data class Msg(val role: String, val text: String)

    private val base = baseUrl.trimEnd('/')
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)       // WS lives on readTimeout(0)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val ids = AtomicLong(1)
    private val pending = HashMap<Long, Pending>()

    private class Pending(val latch: CountDownLatch, var result: Any? = null, var error: String? = null) {
        fun settle(timeoutMs: Long): Pair<Any?, String?> {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return result to error
        }
    }

    private var cookie: String? = null
    private var ws: WebSocket? = null

    @Volatile
    var connectionState = STATE_DISCONNECTED
        private set

    // ── auth ──────────────────────────────────────────────────────────────

    private fun providerName(): String {
        val req = Request.Builder().url("$base/api/status").build()
        http.newCall(req).execute().use { r ->
            val providers = JSONObject(r.body?.string() ?: "{}").optJSONArray("auth_providers") ?: return "basic"
            return if (providers.length() > 0) providers.getString(0) else "basic"
        }
    }

    /** POST /auth/password-login. Collects every Set-Cookie pair. */
    fun login(): String? {
        val provider = try { providerName() } catch (e: Exception) {
            listener.onState(STATE_LOGIN_FAILED, "Gateway unreachable: ${e.message}")
            return null
        }
        val body = JSONObject().put("provider", provider).put("username", username).put("password", password)
            .toString().toRequestBody(json)
        val req = Request.Builder().url("$base/auth/password-login").post(body)
            .header("Content-Type", "application/json").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (r.code != 200) {
                val msg = when (r.code) {
                    401 -> "Invalid username or password"
                    404 -> "Login form not supported by this gateway"
                    429 -> "Too many login attempts, try again shortly"
                    else -> "Login failed (HTTP ${r.code}): ${text.take(120)}"
                }
                listener.onState(STATE_LOGIN_FAILED, msg)
                return null
            }
            val out = StringBuilder()
            val seen = HashSet<String>()
            for (c in r.headers("Set-Cookie")) {
                val pair = c.split(";")[0]
                val name = pair.split("=")[0].trim()
                if (seen.add(name)) {
                    if (out.isNotEmpty()) out.append("; ")
                    out.append(pair)
                }
            }
            listener.onState(STATE_CONNECTING, "")
            return out.toString()
        }
    }

    /** POST /api/auth/ws-ticket -> one-use ticket. Fresh for every dial. */
    private fun wsTicket(): String? {
        val c = cookie ?: return null
        val req = Request.Builder().url("$base/api/auth/ws-ticket").post(byteArrayOf().toRequestBody(json))
            .header("Cookie", c).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) return null            // session expired -> caller re-logins
            if (r.code != 200) {
                listener.onState(STATE_LOGIN_FAILED, "ws-ticket failed (HTTP ${r.code})")
                return null
            }
            return JSONObject(r.body?.string() ?: "{}").optString("ticket")
        }
    }

    /** login -> ticket -> WS. Call from a background thread. */
    fun connect(): Boolean {
        synchronized(this) { if (ws != null) return true }
        setState(STATE_CONNECTING, "")
        cookie = login() ?: return false
        var ticket = wsTicket()
        if (ticket == null) {                        // cookie dead -> one fresh login
            cookie = login() ?: return false
            ticket = wsTicket() ?: return false
        }
        return openSocket(ticket)
    }

    private fun openSocket(ticket: String): Boolean {
        val url = "$base/api/ws?ticket=$ticket"
        val req = Request.Builder().url(url).build()
        val latched = CountDownLatch(1)
        var openedOk = false
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedOk = true
                latched.countDown()
                setState(STATE_CONNECTED, "")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = "Websocket dropped: ${t.message ?: t.javaClass.simpleName}"
                Log.w(TAG, detail)
                failPending(detail)
                latched.countDown()
                ws = null
                listener.onWsClosed(detail)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failPending("Websocket closed (code $code)")
                ws = null
                listener.onWsClosed("Websocket closed (code $code ${reason.take(60)})")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)
        })
        latched.await(20, TimeUnit.SECONDS)
        if (!openedOk) { ws = null; return false }
        return true
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        setState(STATE_DISCONNECTED, "")
    }

    private fun setState(s: String, detail: String) {
        connectionState = s
        listener.onState(s, detail)
    }

    // ── JSON-RPC ──────────────────────────────────────────────────────────

    private fun onFrame(text: String) {
        val frame = try { JSONObject(text) } catch (e: Exception) { return }
        val id = if (frame.has("id") && !frame.isNull("id")) frame.optLong("id", -1) else -1L
        if (id >= 0 && (frame.has("result") || frame.has("error"))) {
            val p = synchronized(pending) { pending.remove(id) } ?: return
            if (frame.has("error")) {
                p.error = frame.optJSONObject("error")?.optString("message") ?: "RPC error"
            } else {
                p.result = frame.opt("result")
            }
            p.latch.countDown()
            return
        }
        if (frame.optString("method") == "event") dispatch(frame.optJSONObject("params") ?: return)
    }

    private fun dispatch(params: JSONObject) {
        val type = params.optString("type")
        val sid = params.optString("session_id")
        val payload = params.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "thinking.delta", "message.delta", "message.complete", "session.title",
            "approval.request", "clarify.request" -> {}
            else -> {
                if (type == "sessions.changed") listener.onSessionsChanged()
                return
            }
        }
        when (type) {
            "thinking.delta" -> listener.onThinking(sid, payload.optString("text"))
            "message.delta" -> listener.onDelta(sid, payload.optString("text"))
            "message.complete" -> listener.onComplete(sid, payload.optString("text"))
            "session.title" -> listener.onTitle(payload.optString("session_id"), payload.optString("title"))
            "approval.request" -> {
                val id = payload.optString("prompt_id")
                listener.onApproval(id, sid, payload.optString("command"),
                    payload.optString("description"),
                    choices(payload))
            }
            "clarify.request" -> {
                val qs = payload.optJSONArray("questions")
                val q = if (qs != null && qs.length() > 0) qs.getJSONObject(0) else payload
                listener.onClarify(q.optString("qid", payload.optString("prompt_id")), sid,
                    q.optString("question"), choices(q))
            }
        }
    }

    private fun choices(o: JSONObject): List<String> {
        val arr = o.optJSONArray("choices") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun rpc(method: String, params: JSONObject, timeoutMs: Long = 30_000): Pair<Any?, String?> {
        val socket = ws ?: return null to "Not connected"
        val id = ids.getAndIncrement()
        val p = Pending(CountDownLatch(1))
        synchronized(pending) { pending[id] = p }
        socket.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString())
        val (result, error) = p.settle(timeoutMs)
        return result to error
    }

    private fun failPending(reason: String) {
        synchronized(pending) {
            for (p in pending.values) { p.error = reason; p.latch.countDown() }
            pending.clear()
        }
    }

    // ── RPC surface ───────────────────────────────────────────────────────

    fun sessionList() {
        Thread {
            val (r, e) = rpc("session.list", JSONObject())
            if (e != null) { listener.onError("session.list: $e"); return@Thread }
            val rows = ArrayList<SessionRow>()
            val arr = (r as? JSONObject)?.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rows.add(SessionRow(o.optString("id"), o.optString("title"), o.optString("preview"),
                    o.optInt("message_count"), (o.optDouble("started_at") * 1000).toLong()))
            }
            listener.onSessionList(rows)
        }.start()
    }

    fun openSession(storedId: String) {
        Thread {
            val (r, e) = rpc("session.resume", JSONObject().put("session_id", storedId))
            if (e != null) { listener.onError("Open session: $e"); return@Thread }
            val res = r as? JSONObject ?: return@Thread
            val msgs = ArrayList<Msg>()
            val arr = res.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                msgs.add(Msg(m.optString("role"), m.optString("text")))
            }
            listener.onSessionOpened(storedId, res.optString("session_id"), msgs)
        }.start()
    }

    fun newSession() {
        Thread {
            val (r, e) = rpc("session.create", JSONObject())
            if (e != null) { listener.onError("New chat: $e"); return@Thread }
            val res = r as? JSONObject ?: return@Thread
            listener.onSessionOpened(res.optString("stored_session_id"), res.optString("session_id"), emptyList())
        }.start()
    }

    fun submit(runtimeId: String, text: String, onAck: (String?) -> Unit) {
        Thread {
            val (r, e) = rpc("prompt.submit", JSONObject().put("session_id", runtimeId).put("text", text), 1_800_000)
            onAck(e)
            if (e != null) listener.onError("Send: $e")
        }.start()
    }

    fun approve(runtimeId: String, promptId: String, choice: String) {
        Thread { rpc("approval.respond", JSONObject()
            .put("session_id", runtimeId).put("request_id", promptId).put("choice", choice)) }.start()
    }

    fun clarify(runtimeId: String, promptId: String, answer: String) {
        Thread { rpc("clarify.respond", JSONObject()
            .put("session_id", runtimeId).put("request_id", promptId).put("answer", answer)) }.start()
    }

    companion object {
        private const val TAG = "GatewayConnection"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_LOGIN_FAILED = "login_failed"
    }
}
