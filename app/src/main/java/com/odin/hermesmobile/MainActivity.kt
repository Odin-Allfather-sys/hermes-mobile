package com.odin.hermesmobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hermes Mobile v2 — native client for a Hermes gateway. Slide-out session
 * drawer, markdown chat bubbles, moss avatar, streaming, approvals, clarify.
 */
class MainActivity : AppCompatActivity(), GatewayConnection.Listener {

    // ── colors (dark charcoal + orange accent) ────────────────────────────
    private val bg = 0xFF1C1C1E.toInt()
    private val surface = 0xFF2C2C2E.toInt()
    private val surfaceHi = 0xFF3A3A3C.toInt()
    private val accent = 0xFFF5A623.toInt()
    private val fg = 0xFFF2F2F7.toInt()
    private val dim = 0xFF98989F.toInt()

    private val dp get() = resources.displayMetrics.density
    private fun dpx(v: Int) = (v * dp).toInt()

    // ── UI ─────────────────────────────────────────────────────────────────
    private lateinit var titleText: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var messagesView: LinearLayout
    private lateinit var input: EditText
    private lateinit var emptyHint: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var sessionList: LinearLayout
    private lateinit var drawerStatus: TextView

    // ── state ──────────────────────────────────────────────────────────────
    private var gw: GatewayConnection? = null
    private var storedId: String? = null
    private var runtimeId: String? = null
    private var streamBubble: TextView? = null
    private var streamRaw = StringBuilder()
    private var thinkingView: TextView? = null
    private val promptCards = HashMap<String, ViewGroup>()
    private val rows = ArrayList<GatewayConnection.SessionRow>()
    private val prefs by lazy { getSharedPreferences("hermes", Context.MODE_PRIVATE) }
    private var reconnectAttempts = 0
    private var drawerOpen = false
    private val thinkHandler = Handler(Looper.getMainLooper())
    private var thinkTick = 0
    private val thinkRunnable = object : Runnable {
        override fun run() {
            thinkingView?.let {
                thinkTick = (thinkTick + 1) % 4
                it.text = "thinking" + ".".repeat(thinkTick)
                thinkHandler.postDelayed(this, 400)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = bg
        window.statusBarColor = bg
        setContentView(buildUi())
        buildEmptyHint()
        messagesView.addView(emptyHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyHint.visibility = View.VISIBLE

        if (prefs.getString(KEY_URL, null) == null) {
            prefs.edit().putString(KEY_URL, "https://asgard.tailbd5ca8.ts.net:9119").apply()
            showSettings(firstRun = true)
        } else {
            connect()
        }
    }

    override fun onDestroy() {
        thinkHandler.removeCallbacksAndMessages(null)
        gw?.disconnect()
        super.onDestroy()
    }

    // ── UI construction ────────────────────────────────────────────────────

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun bubble(color: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radiusDp * dp
            if (stroke > 0) setStroke(dpx(1), stroke)
        }

    private fun mossAvatar(sizeDp: Int): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpx(sizeDp), dpx(sizeDp))
        val src = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.moss_avatar)
        setImageDrawable(RoundedBitmapDrawableFactory.create(resources, src).apply { isCircular = true })
    }

    private fun buildUi(): View {
        val root = android.widget.FrameLayout(this)

        // ── main content column ──
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(6), dpx(10), dpx(6), dpx(10))
        }
        bar.addView(iconButton("☰") { toggleDrawer() })
        titleText = TextView(this).apply {
            text = "Elder Moss"
            setTextColor(fg); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton("＋") { newChat() })
        content.addView(bar)

        // status line
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(18), 0, dpx(18), dpx(4))
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpx(8), dpx(8))
            background = circle(dim)
        }
        statusRow.addView(statusDot)
        statusRow.addView(TextView(this).apply {
            text = "connecting…"; setTextColor(dim); textSize = 12f
            setPadding(dpx(6), 0, 0, 0)
        }.also { statusText = it })
        content.addView(statusRow)

        // chat
        messagesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(12), dpx(4), dpx(12), dpx(12))
        }
        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(messagesView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(chatScroll)

        // input bar
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(10), dpx(6), dpx(10), dpx(10))
        }
        input = EditText(this).apply {
            hint = "Message Elder Moss"
            setHintTextColor(dim); setTextColor(fg)
            background = bubble(surface, 22f)
            setPadding(dpx(16), dpx(11), dpx(16), dpx(11))
            textSize = 15f; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        inputBar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dpx(8)
        })
        val send = TextView(this).apply {
            text = "↑"; setTextColor(0xFF1C1C1E.toInt()); textSize = 18f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = circle(accent)
            layoutParams = LinearLayout.LayoutParams(dpx(42), dpx(42))
            setOnClickListener { send() }
        }
        inputBar.addView(send)
        content.addView(inputBar)

        root.addView(content, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ── drawer scrim + slide-in ──
        scrim = View(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
            setOnClickListener { toggleDrawer() }
        }
        root.addView(scrim, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val dw = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF232326.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(dw, ViewGroup.LayoutParams.MATCH_PARENT)
            translationX = -dw.toFloat()
        }

        // drawer header
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(16), dpx(18), dpx(16), dpx(14))
        }
        head.addView(mossAvatar(40))
        val headTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headTxt.addView(TextView(this).apply {
            text = "Elder Moss"; setTextColor(fg); textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpx(10), 0, 0, 0)
        })
        drawerStatus = TextView(this).apply {
            text = "connecting…"; setTextColor(dim); textSize = 12f
            setPadding(dpx(10), dpx(2), 0, 0)
        }
        headTxt.addView(drawerStatus)
        head.addView(headTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        drawer.addView(head)

        // new chat button
        drawer.addView(TextView(this).apply {
            text = "＋  New chat"; setTextColor(0xFF1C1C1E.toInt()); textSize = 14f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = bubble(accent, 18f)
            setOnClickListener { newChat(); toggleDrawer() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dpx(16); marginEnd = dpx(16); bottomMargin = dpx(10)
            height = dpx(44)
        })

        // sessions list
        val listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(sessionList, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        drawer.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // footer
        drawer.addView(View(this).apply { setBackgroundColor(0xFF3A3A3C.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpx(1)))
        drawer.addView(TextView(this).apply {
            text = "⚙  Settings"; setTextColor(dim); textSize = 14f
            setPadding(dpx(18), dpx(14), dpx(18), dpx(16))
            setOnClickListener { toggleDrawer(); showSettings(false) }
        })
        root.addView(drawer)

        return root
    }

    /** Built once, re-attached by clearChat. */
    private fun buildEmptyHint() {
        emptyHint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dpx(60), 0, 0)
        }
        emptyHint.addView(mossAvatar(64), LinearLayout.LayoutParams(dpx(64), dpx(64)).apply {
            gravity = Gravity.CENTER; bottomMargin = dpx(14)
        })
        emptyHint.addView(TextView(this).apply {
            text = "What are we building?"
            setTextColor(dim); textSize = 15f; gravity = Gravity.CENTER
        })
    }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(fg); textSize = 18f
        gravity = Gravity.CENTER
        setPadding(dpx(12), dpx(6), dpx(12), dpx(6))
        setOnClickListener { onClick() }
    }

    // ── drawer ─────────────────────────────────────────────────────────────

    private fun toggleDrawer() {
        drawerOpen = !drawerOpen
        if (drawerOpen) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(180).start()
            drawer.animate().translationX(0f).setDuration(200).start()
        } else {
            scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
            drawer.animate().translationX(-drawer.width.toFloat()).setDuration(200).start()
        }
    }

    private fun populateSessions() {
        sessionList.removeAllViews()
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        for (r in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpx(18), dpx(10), dpx(18), dpx(10))
                setBackgroundColor(if (r.id == storedId) 0xFF2C2C2E.toInt() else 0x00000000)
                setOnClickListener {
                    toggleDrawer()
                    gw?.openSession(r.id)
                }
            }
            row.addView(TextView(this).apply {
                text = r.title.ifBlank { "Untitled" }
                setTextColor(if (r.id == storedId) accent else fg)
                textSize = 14f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            })
            row.addView(TextView(this).apply {
                text = "${r.preview.replace('\n', ' ').take(60)}  ·  ${r.count} msgs  ·  ${fmt.format(Date(r.startedAt))}"
                setTextColor(dim); textSize = 12f; maxLines = 1
            })
            sessionList.addView(row)
        }
    }

    // ── chat rendering ─────────────────────────────────────────────────────

    private fun clearChat() {
        messagesView.removeAllViews()
        streamBubble = null
        streamRaw = StringBuilder()
        hideThinking()
        promptCards.clear()
        messagesView.addView(emptyHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyHint.visibility = View.VISIBLE
    }

    private fun scrollToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatRow(bubble: View, mine: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            if (!mine) {
                addView(mossAvatar(28), LinearLayout.LayoutParams(dpx(28), dpx(28)).apply {
                    marginEnd = dpx(8); topMargin = dpx(2)
                })
            }
            addView(bubble)
        }

    private fun userBubble(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f; setTextColor(0xFF1C1C1E.toInt())
        setLineSpacing(2f, 1f)
        background = bubble(accent, 18f)
        setPadding(dpx(13), dpx(9), dpx(13), dpx(9))
        maxWidth = (290 * dp).toInt()
    }

    private fun addUser(text: String, sameAsPrevious: Boolean) {
        val tv = userBubble(text)
        attachCopy(tv)
        messagesView.addView(chatRow(tv, mine = true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (sameAsPrevious) dpx(3) else dpx(8)
        })
        emptyHint.visibility = View.GONE
        scrollToBottom()
    }

    /** Render a full assistant message: markdown blocks inside one row with one avatar. */
    private fun addAssistant(text: String) {
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (b in Markdown.blocks(text)) {
            if (b.code) {
                val tv = TextView(this).apply {
                    setText(b.text); textSize = 13f; setTextColor(fg)
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dpx(10), dpx(9), dpx(10), dpx(9))
                    background = bubble(surfaceHi, 12f)
                }
                val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                hs.addView(tv)
                attachCopy(tv)
                stack.addView(hs, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (stack.childCount > 0) dpx(4) else 0
                })
            } else {
                val tv = TextView(this).apply {
                    this.text = b.text
                    textSize = 15f; setTextColor(fg)
                    setLineSpacing(2f, 1f)
                    movementMethod = LinkMovementMethod.getInstance()
                    setTextIsSelectable(true)
                    background = bubble(surface, 18f)
                    setPadding(dpx(13), dpx(9), dpx(13), dpx(9))
                }
                attachCopy(tv)
                stack.addView(tv, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (stack.childCount > 0) dpx(4) else 0
                })
            }
        }
        if (stack.childCount == 0) return
        val row = chatRow(stack, mine = false)
        messagesView.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpx(8)
        })
        emptyHint.visibility = View.GONE
        scrollToBottom()
    }

    private fun attachCopy(tv: TextView) {
        tv.setOnLongClickListener {
            val txt = tv.text.toString()
            AlertDialog.Builder(this)
                .setItems(arrayOf("Copy")) { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", txt))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                .show()
            true
        }
    }

    private fun hideThinking() {
        thinkHandler.removeCallbacks(thinkRunnable)
        thinkingView?.let { messagesView.removeView(it); thinkingView = null }
    }

    private fun showThinking() {
        if (thinkingView == null) {
            val tv = TextView(this).apply {
                text = "thinking"; textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                setTextColor(dim)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dpx(8)
            lp.marginStart = dpx(36)
            messagesView.addView(tv, lp)
            thinkingView = tv
            thinkTick = 0
            thinkHandler.removeCallbacks(thinkRunnable)
            thinkHandler.postDelayed(thinkRunnable, 400)
            scrollToBottom()
        }
    }

    private fun ensureStreamBubble() {
        if (streamBubble == null) {
            hideThinking()
            val tv = TextView(this).apply {
                textSize = 15f; setTextColor(fg)
                setLineSpacing(2f, 1f)
                background = bubble(surface, 18f)
                setPadding(dpx(13), dpx(9), dpx(13), dpx(9))
                maxWidth = (290 * dp).toInt()
            }
            streamBubble = tv
            streamRaw = StringBuilder()
            messagesView.addView(chatRow(tv, mine = false), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpx(8)
            })
            emptyHint.visibility = View.GONE
        }
    }

    private fun endStream(text: String) {
        hideThinking()
        streamBubble?.let { (it.parent as? ViewGroup)?.removeView(it) }
        streamBubble = null
        val final = text.ifBlank { streamRaw.toString() }
        if (final.isNotBlank()) addAssistant(final)
        streamRaw = StringBuilder()
    }

    // ── GatewayConnection.Listener (callbacks arrive on worker threads) ──

    private fun onUi(body: () -> Unit) { runOnUiThread { body() } }

    override fun onState(state: String, detail: String) {
        onUi {
            val (color, label) = when (state) {
                GatewayConnection.STATE_CONNECTED -> 0xFF34C759.toInt() to "connected · asgard"
                GatewayConnection.STATE_CONNECTING -> accent to "connecting…"
                GatewayConnection.STATE_LOGIN_FAILED -> 0xFFFF453A.toInt() to (detail.ifBlank { "login failed" })
                else -> dim to "disconnected"
            }
            statusDot.background = circle(color)
            statusText.text = label
            drawerStatus.text = label
            if (state == GatewayConnection.STATE_CONNECTED) {
                reconnectAttempts = 0
                gw?.sessionList(); openLastSession()
            }
            if (state == GatewayConnection.STATE_LOGIN_FAILED) Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
        }
    }

    private fun openLastSession() {
        val last = prefs.getString(KEY_LAST_SESSION, null)
        if (last != null) gw?.openSession(last) else gw?.newSession()
    }

    override fun onSessionList(newRows: List<GatewayConnection.SessionRow>) {
        onUi { rows.clear(); rows.addAll(newRows); populateSessions() }
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
                if (m.role == "user") addUser(m.text, m.role == prevRole)
                else addAssistant(m.text)
                prevRole = m.role
            }
            val known = rows.firstOrNull { it.id == stored }
            titleText.text = known?.title?.ifBlank { null } ?: "New chat"
            populateSessions()
        }
    }

    override fun onDelta(sid: String, text: String) = onUi {
        if (sid != runtimeId || text.isEmpty()) return@onUi
        ensureStreamBubble()
        streamRaw.append(text)
        streamBubble?.text = streamRaw.toString()
        scrollToBottom()
    }

    override fun onThinking(sid: String, text: String) = onUi { if (sid == runtimeId) showThinking() }

    override fun onComplete(sid: String, text: String) = onUi {
        if (sid == runtimeId) endStream(text)
    }

    override fun onTitle(stored: String, title: String) = onUi {
        if (stored == storedId && title.isNotBlank()) {
            titleText.text = title
            rows.firstOrNull { it.id == stored }?.title = title
            populateSessions()
        }
    }

    private var lastListRefresh = 0L

    override fun onSessionsChanged() {
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
                addUser(choice, sameAsPrevious = false)
            }
            promptCards[promptId] = card
            messagesView.addView(card)
            emptyHint.visibility = View.GONE
            scrollToBottom()
        }
    }

    override fun onClarify(promptId: String, sid: String, question: String, choices: List<String>) {
        onUi {
            if (sid != runtimeId || promptCards.containsKey(promptId)) return@onUi
            val card = promptCard("Quick question", question, "", choices) { answer ->
                promptCards.remove(promptId)?.let { messagesView.removeView(it) }
                runtimeId?.let { gw?.clarify(it, promptId, answer) }
                addUser(answer, sameAsPrevious = false)
            }
            promptCards[promptId] = card
            messagesView.addView(card)
            emptyHint.visibility = View.GONE
            scrollToBottom()
        }
    }

    private fun promptCard(
        title: String, bodyText: String, detail: String, choices: List<String>,
        onPick: (String) -> Unit,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bubble(surface, 16f, accent)
            setPadding(dpx(14), dpx(12), dpx(14), dpx(12))
        }
        card.addView(TextView(this).apply {
            text = title; setTextColor(accent); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpx(6))
        })
        if (bodyText.isNotBlank()) card.addView(TextView(this).apply {
            text = bodyText; setTextColor(fg); textSize = 14f
            setPadding(0, 0, 0, dpx(4)); setTextIsSelectable(true)
        })
        if (detail.isNotBlank()) card.addView(TextView(this).apply {
            text = detail; setTextColor(dim); textSize = 13f
            setPadding(0, 0, 0, dpx(6)); setTextIsSelectable(true)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, choice) in choices.withIndex()) {
            val b = TextView(this).apply {
                text = choice; setTextColor(0xFF1C1C1E.toInt())
                background = bubble(accent, 12f)
                typeface = Typeface.DEFAULT_BOLD; textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpx(14), dpx(8), dpx(14), dpx(8))
                setOnClickListener { onPick(choice) }
            }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = if (i < choices.size - 1) dpx(8) else 0
            })
        }
        card.addView(row)
        val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        wrap.topMargin = dpx(10)
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

    private fun newChat() {
        clearChat()
        titleText.text = "New chat"
        gw?.newSession()
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
        addUser(text, sameAsPrevious = false)
        gw?.submit(rt, text) { err ->
            if (err != null) onUi {
                Toast.makeText(this, "Send failed: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── settings dialog ────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun showSettings(firstRun: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpx(20), dpx(8), dpx(20), 0) }
        fun field(hint: String, value: String, secret: Boolean): EditText =
            EditText(this).apply {
                this.hint = hint; setText(value)
                setTextColor(fg); setHintTextColor(dim)
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                textSize = 14f
            }
        val urlF = field("Gateway URL", prefs.getString(KEY_URL, "") ?: "", false)
        val userF = field("Username", prefs.getString(KEY_USER, "") ?: "", false)
        val passF = field("Password", prefs.getString(KEY_PASS, "") ?: "", true)
        for (f in listOf(urlF, userF, passF)) box.addView(f, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpx(8)
        })
        box.addView(TextView(this).apply {
            text = "Stored on this phone only. Reachable over your tailnet."
            setTextColor(dim); textSize = 12f
            setPadding(0, dpx(12), 0, 0)
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
