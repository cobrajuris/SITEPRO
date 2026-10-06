package com.nero.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nero.assistant.data.ChatStore
import com.nero.assistant.data.OpenRouterService
import com.nero.assistant.data.Role

/** Paleta tirada da logo: fundo petróleo, gato preto, olhos brancos. */
private object C {
    val NIGHT = Color.parseColor("#0E1B1E")
    val TEAL = Color.parseColor("#1C353A")
    val TEAL_LIGHT = Color.parseColor("#2A4A51")
    val SURFACE = Color.parseColor("#152A2E")
    val EYE = Color.parseColor("#F4F1EA")
    val GOLD = Color.parseColor("#E6C77A")
    val MUTED = Color.parseColor("#8FA6AA")
    val BLACK = Color.parseColor("#050808")
    val ERROR_BG = Color.parseColor("#3A1F22")
    val ERROR_FG = Color.parseColor("#FFB4AB")
}

private val SUGGESTIONS = listOf(
    "Planeje meu dia de forma produtiva",
    "Escreva um e-mail profissional",
    "Explique um assunto de forma simples",
    "Me dê ideias criativas para um projeto",
    "Revise e melhore este texto",
    "Monte um treino rápido em casa",
)

class MainActivity : Activity() {

    private lateinit var chat: ChatController

    private lateinit var root: FrameLayout
    private lateinit var mainView: LinearLayout
    private lateinit var settingsView: View
    private lateinit var modeLabel: TextView
    private lateinit var content: FrameLayout
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var errorBanner: TextView
    private lateinit var input: EditText
    private lateinit var deepButton: ImageView
    private lateinit var sendButton: ImageView
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private lateinit var history: LinearLayout

    private var drawerOpen = false
    private var streamingBody: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.NIGHT
        window.navigationBarColor = C.NIGHT
        chat = ChatController(ChatStore(this))

        root = FrameLayout(this).apply { setBackgroundColor(C.NIGHT) }
        mainView = buildMain()
        settingsView = buildSettings().apply { visibility = View.GONE }
        scrim = View(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        drawer = buildDrawer()
        root.addView(mainView, match())
        root.addView(settingsView, match())
        root.addView(scrim, match())
        root.addView(drawer, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.82f).toInt(), MATCH))
        drawer.post { drawer.translationX = -drawer.width.toFloat() }

        val splash = buildSplash()
        root.addView(splash, match())
        setContentView(root)

        chat.onChange = { render() }
        render()

        splash.animate().setStartDelay(1400).alpha(0f).setDuration(400)
            .withEndAction { root.removeView(splash) }.start()
    }

    @Deprecated("Back simples para Activity sem AndroidX")
    override fun onBackPressed() {
        when {
            drawerOpen -> closeDrawer()
            settingsView.visibility == View.VISIBLE -> showSettings(false)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------- Telas ----------

    private fun buildSplash(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(C.TEAL)
        isClickable = true
        val logo = logo(180, 48)
        addView(logo)
        addView(text("NERO", 30f, C.EYE, bold = true).apply {
            letterSpacing = 0.35f
            setPadding(0, dp(24), 0, 0)
        })
        addView(text("seu assistente pessoal", 14f, C.MUTED))
        logo.scaleX = 0.85f; logo.scaleY = 0.85f; logo.alpha = 0f
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(900).start()
    }

    private fun buildMain(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        // Barra superior
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        bar.addView(logo(36, 18).apply {
            setOnClickListener { openDrawer() }
            contentDescription = "Histórico"
        })
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(text("Nero", 19f, C.EYE, bold = true))
        modeLabel = text("Modo rápido", 12f, C.MUTED)
        titles.addView(modeLabel)
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(iconButton(R.drawable.ic_edit, C.EYE, "Nova conversa") { chat.newConversation() })
        addView(bar)

        // Conteúdo
        content = FrameLayout(context)
        messages = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(messages)
        }
        content.addView(scroll, match())
        addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        errorBanner = text("", 14f, C.ERROR_FG).apply {
            background = rounded(C.ERROR_BG, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            setOnClickListener {
                val needsKey = chat.apiKey.isBlank()
                chat.clearError()
                if (needsKey) showSettings(true)
            }
        }
        addView(errorBanner, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(16), dp(4), dp(16), dp(4))
        })

        addView(buildComposer())
    }

    private fun buildComposer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        background = rounded(C.SURFACE, 28, stroke = C.TEAL_LIGHT)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), dp(6), dp(12), dp(12)) }

        deepButton = iconButton(R.drawable.ic_sparkle, C.MUTED, "Modo profundo") { chat.toggleDeepMode() }
        addView(deepButton)

        input = EditText(context).apply {
            hint = "Pergunte ao Nero…"
            setHintTextColor(C.MUTED)
            setTextColor(C.EYE)
            textSize = 16f
            background = null
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(6), dp(10), dp(6), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = updateSendButton()
            })
        }
        addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))

        sendButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = ColorStateList.valueOf(C.NIGHT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Enviar"
            setOnClickListener {
                if (chat.isBusy) {
                    chat.stop()
                } else {
                    val text = input.text.toString()
                    if (text.isNotBlank()) {
                        input.setText("")
                        chat.send(text)
                    }
                }
            }
        }
        addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun buildDrawer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C.SURFACE)
        isClickable = true
        elevation = dp(8).toFloat()

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        head.addView(logo(44, 14))
        val names = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        names.addView(text("Nero", 20f, C.EYE, bold = true))
        names.addView(text("PREMIUM", 11f, C.GOLD).apply { letterSpacing = 0.2f })
        head.addView(names)
        addView(head)

        addView(pillButton("Nova conversa") {
            chat.newConversation()
            closeDrawer()
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(dp(16), 0, dp(16), dp(16)) })

        addView(text("RECENTES", 11f, C.MUTED).apply {
            letterSpacing = 0.2f
            setPadding(dp(20), dp(4), dp(20), dp(6))
        })
        history = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(context).apply { addView(history) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        addView(View(context).apply { setBackgroundColor(C.TEAL_LIGHT) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        addView(text("Ajustes", 16f, C.EYE).apply {
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setOnClickListener {
                closeDrawer()
                showSettings(true)
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildSettings(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.NIGHT)
            isClickable = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(iconButton(R.drawable.ic_back, C.EYE, "Voltar") { showSettings(false) })
        bar.addView(text("Ajustes", 20f, C.EYE, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
        page.addView(bar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }

        val nameField = field("Como o Nero deve te chamar")
        body.addView(card("Seu nome", nameField))

        val keyField = field("sk-or-...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        body.addView(card(
            "Chave de API do OpenRouter",
            keyField,
            text("Crie a sua grátis em openrouter.ai/keys. Ela fica salva só neste aparelho.", 13f, C.MUTED)
                .apply { setPadding(0, dp(8), 0, 0) },
        ))

        val modelField = field(OpenRouterService.FREE_MODEL)
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            listOf(
                "Grátis" to OpenRouterService.FREE_MODEL,
                "Automático" to OpenRouterService.AUTO_MODEL,
            ).forEach { (label, id) ->
                addView(text(label, 14f, C.EYE).apply {
                    background = rounded(C.TEAL, 14)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { modelField.setText(id) }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            }
        }
        body.addView(card(
            "Modelo de IA",
            modelField,
            presets,
            text(
                "Grátis: usa um modelo gratuito disponível. Automático: o OpenRouter escolhe o melhor " +
                    "para cada pergunta (usa créditos). Você também pode digitar qualquer modelo, como " +
                    "anthropic/claude-sonnet-4.5. Se ele falhar, o Nero usa o grátis.",
                13f, C.MUTED,
            ).apply { setPadding(0, dp(10), 0, 0) },
        ))

        val deepSwitch = Switch(this).apply {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.NIGHT, C.MUTED),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.GOLD, C.TEAL_LIGHT),
            )
            setOnCheckedChangeListener { _, checked -> if (checked != chat.deepMode) chat.toggleDeepMode() }
        }
        val deepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                text("Raciocina mais antes de responder. Ideal para tarefas difíceis; um pouco mais lento.", 13f, C.MUTED),
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            addView(deepSwitch)
        }
        body.addView(card("Modo profundo", deepRow))

        body.addView(pillButton("Salvar") {
            chat.saveSettings(keyField.text.toString(), nameField.text.toString(), modelField.text.toString())
            hideKeyboard()
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
            showSettings(false)
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })

        body.addView(text("Nero 1.2 · movido pelo OpenRouter", 12f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        page.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        page.tag = { // atualiza os campos ao abrir
            nameField.setText(chat.userName)
            keyField.setText(chat.apiKey)
            modelField.setText(chat.model)
            deepSwitch.isChecked = chat.deepMode
        }
        return page
    }

    // ---------- Render ----------

    private fun render() {
        modeLabel.text = if (chat.deepMode) "Modo profundo" else "Modo rápido"
        modeLabel.setTextColor(if (chat.deepMode) C.GOLD else C.MUTED)
        deepButton.imageTintList = ColorStateList.valueOf(if (chat.deepMode) C.GOLD else C.MUTED)

        errorBanner.text = chat.error.orEmpty()
        errorBanner.visibility = if (chat.error == null) View.GONE else View.VISIBLE

        renderMessages()
        renderHistory()
        updateSendButton()
    }

    private fun renderMessages() {
        val turns = chat.current.turns
        val streaming = chat.streamingText

        // Durante o streaming só o último balão muda: atualiza sem reconstruir a lista.
        val live = streamingBody
        if (streaming != null && streaming.isNotEmpty() && live != null && messages.childCount == turns.size + 1) {
            live.text = streaming + " ▍"
            scrollToEnd()
            return
        }

        messages.removeAllViews()
        streamingBody = null

        if (turns.isEmpty() && streaming == null) {
            messages.addView(emptyState(), LinearLayout.LayoutParams(MATCH, MATCH))
            return
        }
        turns.forEachIndexed { i, turn ->
            if (turn.role == Role.USER) {
                messages.addView(userBubble(turn.text))
            } else {
                messages.addView(assistantMessage(turn.text, i == turns.lastIndex && !chat.isBusy))
            }
        }
        if (streaming != null) {
            val (row, body) = assistantRow()
            if (streaming.isEmpty()) {
                body.text = "Nero está pensando…"
                body.setTextColor(C.MUTED)
                body.animate().alpha(0.4f).setDuration(700).withEndAction {
                    body.animate().alpha(1f).setDuration(700).start()
                }.start()
            } else {
                body.text = streaming + " ▍"
            }
            streamingBody = if (streaming.isEmpty()) null else body
            messages.addView(row)
        }
        scrollToEnd()
    }

    private fun renderHistory() {
        history.removeAllViews()
        if (chat.conversations.isEmpty()) {
            history.addView(text("Suas conversas aparecem aqui.", 14f, C.MUTED).apply {
                setPadding(dp(20), dp(12), dp(20), dp(12))
            })
        }
        chat.conversations.forEach { c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, 0, 0)
                background = rounded(if (c.id == chat.current.id) C.TEAL else C.SURFACE, 12)
                setOnClickListener {
                    chat.open(c)
                    closeDrawer()
                }
            }
            row.addView(icon(R.drawable.ic_chat, C.MUTED, 18))
            row.addView(text(c.title, 15f, C.EYE).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(iconButton(R.drawable.ic_delete, C.MUTED, "Apagar", size = 18) { chat.delete(c) })
            history.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(dp(10), dp(2), dp(10), dp(2))
            })
        }
    }

    private fun updateSendButton() {
        val busy = chat.isBusy
        val ready = busy || input.text.isNotBlank()
        sendButton.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send)
        sendButton.contentDescription = if (busy) "Parar" else "Enviar"
        sendButton.background = oval(if (ready) C.EYE else C.TEAL)
        sendButton.isEnabled = ready
    }

    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(24), dp(8), dp(24))
        addView(logo(110, 32))
        val name = chat.userName
        addView(text(if (name.isBlank()) "Olá. Eu sou o Nero." else "Olá, $name.", 26f, C.EYE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(6))
        })
        addView(text("Em que posso ajudar hoje?", 16f, C.MUTED).apply { setPadding(0, 0, 0, dp(28)) })

        if (chat.apiKey.isBlank()) {
            val activate = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C.TEAL, 20)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                setOnClickListener { showSettings(true) }
                addView(text("Ative o Nero", 16f, C.GOLD, bold = true))
                addView(text("Toque aqui e cole sua chave do OpenRouter para começar. Dá para usar de graça.", 14f, C.EYE)
                    .apply { setPadding(0, dp(4), 0, 0) })
            }
            addView(activate, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            SUGGESTIONS.chunked(2).forEach { pair ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { i, s ->
                    row.addView(text(s, 14f, C.EYE).apply {
                        background = rounded(C.SURFACE, 16, stroke = C.TEAL_LIGHT)
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        setOnClickListener { chat.send(s) }
                    }, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        if (i == 0) marginEnd = dp(10)
                    })
                }
                addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(5), 0, dp(5)) })
            }
        }
    }

    private fun userBubble(message: String): View = FrameLayout(this).apply {
        val bubble = text(message, 16f, C.EYE).apply {
            setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setLineSpacing(0f, 1.2f)
            background = GradientDrawable().apply {
                setColor(C.TEAL)
                val r = dp(22).toFloat(); val s = dp(6).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, s, s, r, r)
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        addView(bubble, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(9), 0, dp(9)) }
    }

    private fun assistantRow(): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(9), 0, dp(9)) }
        }
        row.addView(logo(28, 14))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val body = text("", 16f, C.EYE).apply { setLineSpacing(0f, 1.25f) }
        column.addView(body)
        row.addView(column, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row to body
    }

    private fun assistantMessage(message: String, isLast: Boolean): View {
        val (row, body) = assistantRow()
        val column = body.parent as LinearLayout
        column.removeView(body)
        // Separa blocos de código (```) em caixas próprias.
        message.split("```").forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val code = part.substringAfter('\n', part).trimEnd()
                val box = HorizontalScrollView(this).apply {
                    background = rounded(C.BLACK, 12)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    addView(text(code, 13f, C.EYE).apply {
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                    })
                }
                column.addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
            } else if (part.isNotBlank()) {
                column.addView(text(part.trim(), 16f, C.EYE).apply {
                    setLineSpacing(0f, 1.25f)
                    setTextIsSelectable(true)
                })
            }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(iconButton(R.drawable.ic_copy, C.MUTED, "Copiar", size = 18) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Nero", message))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        })
        if (isLast) {
            actions.addView(iconButton(R.drawable.ic_refresh, C.MUTED, "Refazer", size = 18) { chat.regenerate() })
        }
        column.addView(actions)
        return row
    }

    // ---------- Navegação ----------

    private fun showSettings(show: Boolean) {
        if (show) {
            @Suppress("UNCHECKED_CAST")
            (settingsView.tag as? () -> Unit)?.invoke()
            settingsView.alpha = 0f
            settingsView.visibility = View.VISIBLE
            settingsView.animate().alpha(1f).setDuration(200).start()
        } else {
            hideKeyboard()
            settingsView.animate().alpha(0f).setDuration(180).withEndAction {
                settingsView.visibility = View.GONE
            }.start()
            render()
        }
    }

    private fun openDrawer() {
        hideKeyboard()
        renderHistory()
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(220).start()
        drawer.animate().translationX(0f).setDuration(260).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(240).start()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---------- Peças visuais ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun match() = FrameLayout.LayoutParams(MATCH, MATCH)

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun logo(sizeDp: Int, radiusDp: Int) = ImageView(this).apply {
        setImageResource(R.drawable.nero_logo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(C.TEAL, radiusDp)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun iconButton(res: Int, tint: Int, label: String, size: Int = 22, onClick: () -> Unit) =
        ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = label
            val pad = dp((44 - size) / 2)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }

    private fun pillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(C.NIGHT)
        gravity = Gravity.CENTER
        background = rounded(C.EYE, 16)
        setOnClickListener { onClick() }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(C.MUTED)
        setTextColor(C.EYE)
        textSize = 15f
        isSingleLine = true
        background = rounded(C.NIGHT, 10, stroke = C.TEAL_LIGHT)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun card(title: String, vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.SURFACE, 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(text(title, 15f, C.EYE, bold = true).apply { setPadding(0, 0, 0, dp(10)) })
        children.forEach { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
