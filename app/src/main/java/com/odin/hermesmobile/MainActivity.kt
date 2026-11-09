package com.odin.hermesmobile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hermes Mobile v1 — native client for a Hermes gateway (chat with streaming,
 * session list, command approvals, clarify answers). Single screen by design.
 */
class MainActivity : AppCompatActivity(), GatewayConnection.Listener {

    // ── colors (dark charcoal + orange accent) ────────────────────────────
    private val bg = 0xFF1C1C1E.toInt()
    private val surface = 0xFF2C2C2E.toInt()
    private val accent = 0xFFF5A623.toInt()
    private val fg = 0xFFF2F2F7.toInt()
    private val dim = 0xFF98989F.toInt()

    private val dp get() = resources.displayMetrics.density

    // ── UI ─────────────────────────────────────────────────────────────────
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var messagesView: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView

    // ── state ──────────────────────────────────────────────────────────────
    private var gw: GatewayConnection? = null
    private var storedId: String? = null     // persisted session id ("2026...x")
    private var runtimeId: String? = null    // live authority id ("318c2634")
    private var streamView: TextView? = null
    private var thinkingView: TextView? = null
    private val promptCards = HashMap<String, ViewGroup>()
    private val rows = ArrayList<GatewayConnection.SessionRow>()
    private val prefs by lazy { getSharedPreferences("hermes", Context.MODE_PRIVATE) }
    private var reconnectAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = bg
        window.statusBarColor = bg
        setContentView(buildUi())

        if (prefs.getString(KEY_URL, null) == null) {
            // First run: prefill the one known gateway.
            prefs.edit().putString(KEY_URL, "https://asgard.tailbd5ca8.ts.net:9119").apply()
            showSettings(firstRun = true)
        } else {
            connect()
        }
    }

    override fun onDestroy() {
        gw?.disconnect()
        super.onDestroy()
    }

    // ── UI construction ────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // top bar: status + title + buttons
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt())
            background = circle(dim)
        }
        bar.addView(statusDot)
        statusText = TextView(this).apply {
            setPadding((8 * dp).toInt(), 0, 0, 0)
            setTextColor(dim)
            textSize = 13f
        }
        bar.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(barButton("☰") { showSessions() })
        bar.addView(barButton("⚙") { showSettings(false) })
        root.addView(bar)

        // chat area
        messagesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(messagesView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(chatScroll)

        // input bar
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        input = EditText(this).apply {
            hint = "Message Hermes"
            setHintTextColor(dim)
            setTextColor(fg)
            setBackgroundColor(surface)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            textSize = 15f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        inputBar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (8 * dp).toInt()
        })
        sendBtn = TextView(this).apply {
            text = "Send"
            setTextColor(accent)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { send() }
        }
        inputBar.addView(sendBtn, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(inputBar)
        return root
    }

    private fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(fg)
        textSize = 20f
        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        setOnClickListener { onClick() }
    }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun bubble(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * dp }

    // ── chat rendering ─────────────────────────────────────────────────────

    private fun clearChat() {
        messagesView.removeAllViews()
        streamView = null
        thinkingView = null
        promptCards.clear()
    }

    private fun addBubble(text: String, mine: Boolean, sameAsPrevious: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(2f, 1f)
            if (mine) { setTextColor(0xFF1C1C1E.toInt()); background = bubble(accent, 16f) }
            else { setTextColor(fg); background = bubble(surface, 16f) }
            setPadding((12 * dp).toInt(), (9 * dp).toInt(), (12 * dp).toInt(), (9 * dp).toInt())
            maxWidth = (300 * dp).toInt()
            setTextIsSelectable(true)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = if (sameAsPrevious) (3 * dp).toInt() else (8 * dp).toInt()
        lp.gravity = if (mine) Gravity.END else Gravity.START
        messagesView.addView(tv, lp)
        scrollToBottom()
        return tv
    }

    private fun scrollToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideThinking() {
        thinkingView?.let { messagesView.removeView(it); thinkingView = null }
    }

    private fun showThinking(sid: String) {
        if (sid != runtimeId) return
        if (thinkingView == null) {
            val tv = TextView(this).apply {
                text = "thinking…"
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                setTextColor(dim)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * dp).toInt()
            lp.gravity = Gravity.START
            messagesView.addView(tv, lp)
            thinkingView = tv
            scrollToBottom()
        }
    }

    private fun endStream() {
        hideThinking()
        streamView = null
    }

    // ── GatewayConnection.Listener (all callbacks arrive on worker threads) ─

    private fun onUi(body: () -> Unit) { runOnUiThread { body() } }

    override fun onState(state: String, detail: String) {
        onUi {
            val (color, label) = when (state) {
                GatewayConnection.STATE_CONNECTED -> 0xFF34C759.toInt() to "connected"
                GatewayConnection.STATE_CONNECTING -> accent to "connecting…"
                GatewayConnection.STATE_LOGIN_FAILED -> 0xFFFF453A.toInt() to (detail.ifBlank { "login failed" })
                else -> dim to "disconnected"
            }
            statusDot.background = circle(color)
            statusText.text = label
            if (state == GatewayConnection.STATE_CONNECTED) { reconnectAttempts = 0; gw?.sessionList(); openLastSession() }
            if (state == GatewayConnection.STATE_LOGIN_FAILED) Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
        }
    }

    private fun openLastSession() {
        val last = prefs.getString(KEY_LAST_SESSION, null)
        if (last != null) gw?.openSession(last) else gw?.newSession()
    }

    override fun onSessionList(newRows: List<GatewayConnection.SessionRow>) {
        onUi { rows.clear(); rows.addAll(newRows) }
    }

    override fun onSessionOpened(stored: String, runtime: String, msgs: List<GatewayConnection.Msg>) {
        onUi {
            storedId = stored
            runtimeId = runtime
            prefs.edit().putString(KEY_LAST_SESSION, stored).apply()
            clearChat()
            var prevRole = ""
            for (m in msgs) {
                if (m.text.isBlank()) continue
                addBubble(m.text, m.role == "user", m.role == prevRole)
                prevRole = m.role
            }
            if (msgs.isEmpty()) {
                val hint = TextView(this).apply {
                    text = "New chat. Say something."
                    setTextColor(dim)
                    textSize = 14f
                    gravity = Gravity.CENTER
                }
                messagesView.addView(hint, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (24 * dp).toInt()
                })
            }
        }
    }

    override fun onDelta(sid: String, text: String) = onUi {
        if (sid != runtimeId) return@onUi
        if (text.isEmpty()) return@onUi
        hideThinking()
        if (streamView == null) {
            streamView = addBubble("", mine = false, sameAsPrevious = false)
        }
        streamView?.append(text)
        scrollToBottom()
    }

    override fun onThinking(sid: String, text: String) = onUi { showThinking(sid) }

    override fun onComplete(sid: String, text: String) = onUi {
        if (sid != runtimeId) return@onUi
        endStream()
        if (text.isNotBlank()) addBubble(text, mine = false, sameAsPrevious = false)
    }

    override fun onTitle(stored: String, title: String) = onUi {
        if (stored == storedId) {
            // Session titles arrive as they are generated; nothing to render in v1.
        }
    }

    private var lastListRefresh = 0L

    override fun onSessionsChanged() {
        // sessions.changed fires several times per turn; throttle to 1 refresh / 5 s.
        onUi {
            val now = System.currentTimeMillis()
            if (now - lastListRefresh > 5000) {
                lastListRefresh = now
                gw?.sessionList()
            }
        }
    }

    override fun onApproval(promptId: String, sid: String, command: String, description: String, choices: List<String>) {
        onUi {
            if (sid != runtimeId || promptCards.containsKey(promptId)) return@onUi
            val card = promptCard("Command approval", command, description, choices) { choice ->
                promptCards.remove(promptId)?.let { messagesView.removeView(it) }
                runtimeId?.let { gw?.approve(it, promptId, choice) }
                addBubble(choice, mine = true, sameAsPrevious = false)
            }
            promptCards[promptId] = card
            messagesView.addView(card)
            scrollToBottom()
        }
    }

    override fun onClarify(promptId: String, sid: String, question: String, choices: List<String>) {
        onUi {
            if (sid != runtimeId || promptCards.containsKey(promptId)) return@onUi
            val card = promptCard("Quick question", question, "", choices) { answer ->
                promptCards.remove(promptId)?.let { messagesView.removeView(it) }
                runtimeId?.let { gw?.clarify(it, promptId, answer) }
                addBubble(answer, mine = true, sameAsPrevious = false)
            }
            promptCards[promptId] = card
            messagesView.addView(card)
            scrollToBottom()
        }
    }

    private fun promptCard(
        title: String, bodyText: String, detail: String, choices: List<String>,
        onPick: (String) -> Unit,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bubble(surface, 16f).apply { setStroke((1 * dp).toInt(), accent) }
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (6 * dp).toInt())
        })
        if (bodyText.isNotBlank()) card.addView(TextView(this).apply {
            text = bodyText
            setTextColor(fg)
            textSize = 14f
            setPadding(0, 0, 0, (4 * dp).toInt())
            setTextIsSelectable(true)
        })
        if (detail.isNotBlank()) card.addView(TextView(this).apply {
            text = detail
            setTextColor(dim)
            textSize = 13f
            setPadding(0, 0, 0, (6 * dp).toInt())
            setTextIsSelectable(true)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, choice) in choices.withIndex()) {
            val b = TextView(this).apply {
                text = choice
                setTextColor(0xFF1C1C1E.toInt())
                background = bubble(accent, 12f)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener { onPick(choice) }
            }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = if (i < choices.size - 1) (8 * dp).toInt() else 0
            })
        }
        card.addView(row)
        val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        wrap.topMargin = (10 * dp).toInt()
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(card, wrap) }
    }

    override fun onWsClosed(reason: String) {
        onUi {
            if (reconnectAttempts >= 5) {
                onState(GatewayConnection.STATE_DISCONNECTED, reason)
                Toast.makeText(this, "Disconnected: $reason", Toast.LENGTH_LONG).show()
                return@onUi
            }
            reconnectAttempts++
            onState(GatewayConnection.STATE_CONNECTING, "")
            Thread {
                try { Thread.sleep((2000L * reconnectAttempts)) } catch (_: InterruptedException) {}
                gw?.disconnect()
                gw = newGateway()
                gw?.connect()
            }.start()
        }
    }

    override fun onError(message: String) = onUi {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ── actions ────────────────────────────────────────────────────────────

    private fun newGateway(): GatewayConnection {
        val url = prefs.getString(KEY_URL, "") ?: ""
        return GatewayConnection(url,
            prefs.getString(KEY_USER, "") ?: "", prefs.getString(KEY_PASS, "") ?: "", this)
    }

    private fun connect() {
        reconnectAttempts = 0
        Thread {
            gw = newGateway()
            gw?.connect()
        }.start()
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val rt = runtimeId
        if (rt == null || gw?.connectionState != GatewayConnection.STATE_CONNECTED) {
            Toast.makeText(this, "Not connected yet", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        addBubble(text, mine = true, sameAsPrevious = false)
        gw?.submit(rt, text) { err ->
            if (err != null) onUi {
                Toast.makeText(this, "Send failed: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── dialogs ────────────────────────────────────────────────────────────

    private fun showSessions() {
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val labels = ArrayList<String>()
        for (r in rows) {
            labels.add("${r.title}\n${fmt.format(Date(r.startedAt))} · ${r.count} msgs")
        }
        AlertDialog.Builder(this)
            .setTitle("Sessions")
            .setItems(labels.toTypedArray()) { _, which -> gw?.openSession(rows[which].id) }
            .setNeutralButton("New chat") { _, _ ->
                clearChat()
                gw?.newSession()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showSettings(firstRun: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        fun field(hint: String, value: String, secret: Boolean): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                setTextColor(fg)
                setHintTextColor(dim)
                if (secret) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                textSize = 14f
            }
        val urlF = field("Gateway URL", prefs.getString(KEY_URL, "") ?: "", false)
        val userF = field("Username", prefs.getString(KEY_USER, "") ?: "", false)
        val passF = field("Password", prefs.getString(KEY_PASS, "") ?: "", true)
        for (f in listOf(urlF, userF, passF)) box.addView(f, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
        })
        box.addView(TextView(this).apply {
            text = "Stored on this phone only. Reachable over your tailnet."
            setTextColor(dim); textSize = 12f
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Gateway settings")
            .setView(box)
            .setPositiveButton("Save & connect") { _, _ ->
                prefs.edit()
                    .putString(KEY_URL, urlF.text.toString().trim())
                    .putString(KEY_USER, userF.text.toString().trim())
                    .putString(KEY_PASS, passF.text.toString())
                    .apply()
                gw?.disconnect()
                connect()
            }
            .setNegativeButton(if (firstRun) "Quit" else "Cancel") { _, _ -> if (firstRun) finish() }
            .show()
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_LAST_SESSION = "last_session"
    }
}
