package com.nero.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.media.RingtoneManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
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
import com.nero.assistant.data.Reminder
import com.nero.assistant.data.ReminderParser
import com.nero.assistant.data.ReminderStore
import com.nero.assistant.data.Role
import com.nero.assistant.ui.AuroraBackground
import com.nero.assistant.ui.FlowLayout
import com.nero.assistant.ui.N
import com.nero.assistant.ui.NeroFonts
import com.nero.assistant.ui.Shapes
import java.io.File
import java.util.Locale

private val SUGGESTIONS = listOf(
    "Planeje meu dia",
    "Me lembra amanhã às 9h de beber água",
    "O que tenho para esta semana?",
    "Escreva um e-mail profissional",
    "Explique algo de forma simples",
    "Ideias para um projeto",
    "Revise este texto",
)

class MainActivity : Activity() {

    private lateinit var chat: ChatController
    internal lateinit var fonts: NeroFonts

    private lateinit var root: FrameLayout
    private lateinit var mainView: LinearLayout
    private lateinit var settingsView: View
    private lateinit var agenda: AgendaPage
    private lateinit var remindersView: View
    private lateinit var modeLabel: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var errorBanner: TextView
    private lateinit var input: EditText
    private lateinit var deepButton: ImageView
    private lateinit var sendButton: ImageView
    private lateinit var micButton: ImageView
    private lateinit var attachmentRow: LinearLayout
    private lateinit var attachmentThumb: ImageView
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private lateinit var history: LinearLayout
    private lateinit var soundLabel: TextView

    private var drawerOpen = false
    private var streamingBody: TextView? = null
    private var cameraUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = N.OLIVE
        window.navigationBarColor = N.OLIVE_DEEP
        fonts = NeroFonts(this)
        ReminderAlarms.ensureChannel(this)
        chat = ChatController(
            ChatStore(this),
            ReminderStore(this),
            isOnline = { isOnline() },
            onReminderCreated = { reminder ->
                ReminderAlarms.schedule(this, reminder)
                askNotificationPermission()
            },
        )

        root = FrameLayout(this)
        root.addView(AuroraBackground(this), match())
        mainView = buildMain()
        settingsView = buildSettings().apply { visibility = View.GONE }
        agenda = AgendaPage(this, chat.reminders, onChanged = { chat.remindersChanged() }) { showPage(remindersView, false) }
        remindersView = agenda.view.apply { visibility = View.GONE }
        scrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 8, 8, 6))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        drawer = buildDrawer()
        root.addView(mainView, match())
        root.addView(settingsView, match())
        root.addView(remindersView, match())
        root.addView(scrim, match())
        root.addView(drawer, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.84f).toInt(), MATCH))
        drawer.post { drawer.translationX = -drawer.width.toFloat() }

        val splash = buildSplash()
        root.addView(splash, match())
        setContentView(root)

        chat.onChange = {
            render()
            if (remindersView.visibility == View.VISIBLE) agenda.render()
        }
        render()

        splash.animate().setStartDelay(1500).alpha(0f).setDuration(450)
            .withEndAction { root.removeView(splash) }.start()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Tocar na notificação de um lembrete abre direto a agenda. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_AGENDA, false) == true) {
            intent.removeExtra(EXTRA_OPEN_AGENDA)
            if (drawerOpen) closeDrawer()
            if (settingsView.visibility == View.VISIBLE) settingsView.visibility = View.GONE
            agenda.showToday()
            if (remindersView.visibility != View.VISIBLE) showPage(remindersView, true)
        }
    }

    override fun onResume() {
        super.onResume()
        // Um lembrete pode ter sido concluído pela notificação enquanto o app estava fechado.
        if (::agenda.isInitialized && remindersView.visibility == View.VISIBLE) agenda.render()
    }

    @Deprecated("Back simples para Activity sem AndroidX")
    override fun onBackPressed() {
        when {
            drawerOpen -> closeDrawer()
            settingsView.visibility == View.VISIBLE -> showPage(settingsView, false)
            remindersView.visibility == View.VISIBLE -> showPage(remindersView, false)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------- Abertura ----------

    private fun buildSplash(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.parseColor("#161512"))
        isClickable = true
        addView(AuroraBackground(context).apply { alpha = 0.55f }, match())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val logo = logo(120, 40)
        column.addView(logo)
        column.addView(dots("NERO", 40f, N.TEXT).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(28), 0, dp(6))
        })
        column.addView(label("seu assistente pessoal", 15f, N.MUTED, fonts.light))
        addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        logo.scaleX = 0.82f; logo.scaleY = 0.82f; logo.alpha = 0f
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(900).start()
    }

    // ---------- Chat ----------

    private fun buildMain(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        bar.addView(FrameLayout(context).apply {
            background = Shapes.glass(context, 24)
            contentDescription = "Abrir menu"
            setOnClickListener { openDrawer() }
            addView(logo(34, 17), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(label("Nero", 20f, N.TEXT, fonts.semibold))
        modeLabel = dots("MODO RÁPIDO", 11f, N.MUTED).apply { letterSpacing = 0.08f }
        titles.addView(modeLabel)
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(roundButton(R.drawable.ic_edit, "Nova conversa") { chat.newConversation() })
        addView(bar)

        messages = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(messages)
        }
        addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        errorBanner = label("", 14f, N.ERROR, fonts.regular).apply {
            background = Shapes.glass(context, 18, N.GLASS_DARK, Color.argb(0x55, 0xFF, 0xB4, 0xA8))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            setOnClickListener {
                val needsKey = !chat.hasValidApiKey
                chat.clearError()
                if (needsKey) showPage(settingsView, true)
            }
        }
        addView(errorBanner, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        attachmentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.glass(context, 22, N.GLASS_DARK)
            setPadding(dp(8), dp(8), dp(6), dp(8))
            visibility = View.GONE
            attachmentThumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Shapes.solid(context, 16, N.OLIVE)
                clipToOutline = true
            }
            addView(attachmentThumb, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(dots("FOTO ANEXADA", 11f, N.LILAC))
                addView(label("Escreva uma pergunta ou só envie.", 13f, N.MUTED, fonts.regular))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(roundButton(R.drawable.ic_close, "Remover foto", size = 40) { chat.clearImage() })
        }
        addView(attachmentRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(6), dp(16), 0) })

        addView(buildComposer())
    }

    /** Barra de escrita: pílula escura com botão de IA, campo, anexo e microfone/enviar. */
    private fun buildComposer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Shapes.glass(context, 34, N.GLASS_DARKER, Color.argb(0x22, 0xFF, 0xFF, 0xFF))
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), dp(8), dp(12), dp(14)) }

        deepButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_sparkle)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            contentDescription = "Modo profundo"
            setOnClickListener {
                chat.toggleDeepMode()
                Toast.makeText(context, if (chat.deepMode) "Modo profundo ligado" else "Modo rápido", Toast.LENGTH_SHORT).show()
            }
        }
        addView(deepButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        input = EditText(context).apply {
            hint = "Pergunte qualquer coisa…"
            setHintTextColor(N.FAINT)
            setTextColor(N.TEXT)
            typeface = fonts.regular
            textSize = 16f
            background = null
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(10), dp(4), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = updateComposer()
            })
        }
        addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))

        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_attach)
            imageTintList = ColorStateList.valueOf(N.MUTED)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "Anexar foto"
            setOnClickListener { chooseImageSource() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))

        micButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = ColorStateList.valueOf(N.TEXT)
            background = Shapes.circle(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Falar"
            setOnClickListener { startVoiceInput() }
        }
        addView(micButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        sendButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = ColorStateList.valueOf(N.INK)
            background = Shapes.circle(N.CREAM)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            contentDescription = "Enviar"
            setOnClickListener {
                if (chat.isBusy) {
                    chat.stop()
                } else {
                    val text = input.text.toString()
                    if (text.isNotBlank() || chat.pendingImage != null) {
                        input.setText("")
                        chat.send(text)
                    }
                }
            }
        }
        addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    // ---------- Menu lateral ----------

    private fun buildDrawer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val r = dp(28).toFloat()
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F21C1A16"), Color.parseColor("#F2262A18")),
        ).apply { cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) }
        isClickable = true
        elevation = dp(10).toFloat()

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(20))
        }
        head.addView(logo(48, 18))
        head.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(dots("NERO", 24f, N.TEXT))
            addView(label("seu assistente pessoal", 13f, N.MUTED, fonts.light))
        })
        addView(head)

        addView(pill("Nova conversa", filled = true) {
            chat.newConversation()
            closeDrawer()
        }, LinearLayout.LayoutParams(MATCH, dp(50)).apply { setMargins(dp(18), 0, dp(18), dp(18)) })

        addView(dots("RECENTES", 12f, N.FAINT).apply {
            letterSpacing = 0.1f
            setPadding(dp(24), dp(4), dp(24), dp(8))
        })
        history = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(history)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        addView(menuRow(R.drawable.ic_alarm, "Agenda") {
            closeDrawer()
            showPage(remindersView, true)
        })
        addView(menuRow(R.drawable.ic_edit, "Ajustes") {
            closeDrawer()
            showPage(settingsView, true)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) })
    }

    private fun menuRow(icon: Int, title: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(14), dp(24), dp(14))
        setOnClickListener { onClick() }
        addView(icon(icon, N.LILAC, 20))
        addView(label(title, 16f, N.TEXT, fonts.regular).apply { setPadding(dp(14), 0, 0, 0) })
    }

    // ---------- Ajustes ----------

    private fun buildSettings(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
        }
        page.addView(pageHeader("Ajustes") { showPage(settingsView, false) })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }

        val nameField = field("Como o Nero deve te chamar")
        body.addView(card("SEU NOME", nameField))

        val currentKeyLabel = label("", 13f, N.LILAC, fonts.regular).apply { setPadding(0, 0, 0, dp(10)) }
        val keyField = field("sk-or-...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        body.addView(card(
            "CHAVE DO OPENROUTER",
            currentKeyLabel,
            keyField,
            hint("Crie a sua grátis em openrouter.ai/keys. O Nero confere a chave antes de salvar e a guarda " +
                "criptografada só neste aparelho. Deixe em branco para manter a atual."),
        ))

        val modelField = field(OpenRouterService.FREE_MODEL)
        val presets = FlowLayout(this, dp(8)).apply {
            setPadding(0, dp(12), 0, 0)
            listOf("Grátis" to OpenRouterService.FREE_MODEL, "Automático" to OpenRouterService.AUTO_MODEL)
                .forEach { (title, id) -> addView(chip(title) { modelField.setText(id) }) }
        }
        body.addView(card(
            "MODELO DE IA",
            modelField,
            presets,
            hint("Grátis: um modelo gratuito disponível. Automático: o OpenRouter escolhe o melhor para cada " +
                "pergunta (usa créditos). Ou digite qualquer modelo, como anthropic/claude-sonnet-4.5. " +
                "Se ele falhar, o Nero usa o grátis."),
        ))

        val promptField = field("").apply {
            isSingleLine = false
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        body.addView(card(
            "PERSONALIDADE",
            promptField,
            FlowLayout(this, dp(8)).apply {
                setPadding(0, dp(12), 0, 0)
                addView(chip("Restaurar padrão") { promptField.setText(OpenRouterService.DEFAULT_PROMPT) })
            },
            hint("Instruções que o Nero segue em todas as conversas: jeito de falar, idioma, foco."),
        ))

        val deepSwitch = neroSwitch().apply {
            setOnCheckedChangeListener { _, checked -> if (checked != chat.deepMode) chat.toggleDeepMode() }
        }
        body.addView(card("MODO PROFUNDO", LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hint("Raciocina mais antes de responder. Ideal para tarefas difíceis; um pouco mais lento."),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(deepSwitch)
        }))

        val alarmSwitch = neroSwitch().apply {
            isChecked = chat.reminders.alarmByDefault
            setOnCheckedChangeListener { _, checked -> chat.reminders.alarmByDefault = checked }
        }
        soundLabel = label("", 14f, N.LILAC, fonts.regular).apply { setPadding(0, dp(14), 0, 0) }
        refreshSoundLabel()
        body.addView(card(
            "ALARME",
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(hint("Na hora do compromisso o Nero toca alarme com som, vibra e acende a tela. " +
                    "Desligado, os lembretes novos só mandam notificação."), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(alarmSwitch)
            },
            soundLabel,
            FlowLayout(this, dp(8)).apply {
                setPadding(0, dp(12), 0, 0)
                addView(chip("Escolher som") { pickAlarmSound() })
                addView(chip("Testar alarme") {
                    askNotificationPermission()
                    AlarmService.test(this@MainActivity)
                })
                if (Build.VERSION.SDK_INT >= 34 &&
                    !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
                ) {
                    addView(chip("Permitir tela cheia") {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                        }
                    })
                }
            },
        ))

        lateinit var saveButton: TextView
        saveButton = pill("Salvar", filled = true) {
            hideKeyboard()
            if (keyField.text.isNotBlank()) {
                saveButton.isEnabled = false
                saveButton.text = "Conferindo chave…"
            }
            chat.saveSettings(
                keyField.text.toString(),
                nameField.text.toString(),
                modelField.text.toString(),
                promptField.text.toString(),
            ) { ok, message ->
                saveButton.isEnabled = true
                saveButton.text = "Salvar"
                Toast.makeText(this, message, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                if (ok) showPage(settingsView, false)
            }
        }
        body.addView(saveButton, LinearLayout.LayoutParams(MATCH, dp(56)).apply { topMargin = dp(6) })

        body.addView(dots("NERO 1.7 · OPENROUTER", 11f, N.FAINT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        page.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        page.tag = {
            nameField.setText(chat.userName)
            keyField.setText("")
            currentKeyLabel.text = chat.apiKey.takeIf { chat.hasValidApiKey }
                ?.let { "Chave atual: sk-or-…${it.takeLast(4)}" } ?: "Nenhuma chave configurada"
            promptField.setText(chat.systemPrompt)
            modelField.setText(chat.model)
            deepSwitch.isChecked = chat.deepMode
        }
        return page
    }

    // ---------- Render ----------

    private fun render() {
        modeLabel.text = if (chat.deepMode) "MODO PROFUNDO" else "MODO RÁPIDO"
        modeLabel.setTextColor(if (chat.deepMode) N.LILAC else N.MUTED)
        deepButton.background = if (chat.deepMode) Shapes.auroraCircle() else Shapes.circle(N.CREAM)
        deepButton.imageTintList = ColorStateList.valueOf(N.INK)

        errorBanner.text = chat.error.orEmpty()
        errorBanner.visibility = if (chat.error == null) View.GONE else View.VISIBLE

        renderMessages()
        renderHistory()
        updateComposer()
    }

    private fun renderMessages() {
        val turns = chat.current.turns
        val streaming = chat.streamingText

        // Durante o streaming só o último bloco muda: atualiza sem reconstruir a lista.
        val live = streamingBody
        val shown = streaming?.let(ReminderParser::hidePartial)
        if (shown != null && shown.isNotEmpty() && live != null && messages.childCount == turns.size + 1) {
            live.text = "$shown ▍"
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
                messages.addView(userBubble(turn.text, turn.imagePath))
            } else {
                messages.addView(assistantMessage(turn.text, i == turns.lastIndex && !chat.isBusy))
            }
        }
        if (streaming != null) {
            val (panel, body) = assistantPanel()
            if (shown.isNullOrEmpty()) {
                body.typeface = fonts.dots
                body.textSize = 13f
                body.letterSpacing = 0.08f
                body.text = "NERO ESTÁ PENSANDO"
                body.setTextColor(N.LILAC)
                body.animate().alpha(0.35f).setDuration(650).withEndAction {
                    body.animate().alpha(1f).setDuration(650).start()
                }.start()
            } else {
                body.text = "$shown ▍"
            }
            streamingBody = if (shown.isNullOrEmpty()) null else body
            messages.addView(panel)
        }
        scrollToEnd()
    }

    private fun renderHistory() {
        history.removeAllViews()
        if (chat.conversations.isEmpty()) {
            history.addView(label("Suas conversas aparecem aqui.", 14f, N.FAINT, fonts.light).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
            })
        }
        chat.conversations.forEach { c ->
            val selected = c.id == chat.current.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Shapes.solid(context, 22, if (selected) Color.argb(0x2E, 0xFF, 0xFF, 0xFF) else Color.TRANSPARENT)
                setPadding(dp(14), 0, 0, 0)
                setOnClickListener {
                    chat.open(c)
                    closeDrawer()
                }
            }
            row.addView(icon(R.drawable.ic_chat, if (selected) N.LILAC else N.FAINT, 16))
            row.addView(label(c.title, 15f, N.TEXT, fonts.regular).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = ColorStateList.valueOf(N.FAINT)
                setPadding(dp(13), dp(13), dp(13), dp(13))
                contentDescription = "Apagar conversa"
                setOnClickListener { chat.delete(c) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            history.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
        }
    }

    private fun updateComposer() {
        val busy = chat.isBusy
        val hasContent = input.text.isNotBlank() || chat.pendingImage != null
        // Sem nada escrito, aparece o microfone (como em apps de mensagem).
        val showMic = !busy && !hasContent
        micButton.visibility = if (showMic) View.VISIBLE else View.GONE
        sendButton.visibility = if (showMic) View.GONE else View.VISIBLE
        sendButton.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send)
        sendButton.contentDescription = if (busy) "Parar" else "Enviar"

        val image = chat.pendingImage
        attachmentRow.visibility = if (image == null) View.GONE else View.VISIBLE
        if (image != null && attachmentThumb.tag != image) {
            attachmentThumb.setImageBitmap(BitmapFactory.decodeFile(image))
            attachmentThumb.tag = image
        }
    }

    /** Tela inicial: saudação grande com cursor e o cartão do assistente em degradê. */
    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(4), dp(24), dp(4), dp(8))

        val name = chat.userName
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(if (name.isBlank()) "Olá" else "Olá, $name", 44f, N.TEXT, fonts.light).apply {
                letterSpacing = -0.02f
            })
            addView(View(context).apply {
                background = Shapes.solid(context, 2, N.LILAC)
                animate().alpha(0f).setDuration(600).withEndAction { animate().alpha(1f).setDuration(600).start() }.start()
            }, LinearLayout.LayoutParams(dp(3), dp(44)).apply { marginStart = dp(10) })
        })
        addView(label("Em que posso ajudar hoje?", 17f, N.MUTED, fonts.light).apply {
            setPadding(0, dp(4), 0, dp(24))
        })

        // O próximo lembrete fica à vista na tela inicial; tocar abre a agenda.
        chat.reminders.upcoming().firstOrNull()?.let { next ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Shapes.glass(context, 26, N.GLASS_DARK)
                setPadding(dp(12), dp(10), dp(16), dp(10))
                setOnClickListener { showPage(remindersView, true) }
                addView(icon(R.drawable.ic_alarm, N.LILAC, 20))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(dots("PRÓXIMO · " + ReminderParser.describe(next).uppercase(Locale("pt", "BR")), 11f, N.LILAC))
                    addView(label(next.title, 15f, N.TEXT, fonts.regular).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Shapes.aurora(context, 34)
            setPadding(dp(20), dp(22), dp(20), dp(20))
            elevation = dp(4).toFloat()
        }
        if (!chat.hasValidApiKey) {
            card.addView(dots("ATIVE O NERO", 26f, Color.argb(0xCC, 0xFF, 0xFF, 0xFF)))
            card.addView(label(
                "Cole sua chave do OpenRouter em Ajustes para começar. Dá para usar de graça.",
                15f, N.INK, fonts.regular,
            ).apply { setPadding(0, dp(12), 0, dp(16)) })
            card.addView(pill("Abrir ajustes", filled = false, dark = true) { showPage(settingsView, true) },
                LinearLayout.LayoutParams(MATCH, dp(52)))
        } else {
            card.addView(dots("ASSISTENTE", 28f, Color.argb(0xCC, 0xFF, 0xFF, 0xFF)))
            card.addView(FlowLayout(context, dp(8)).apply {
                setPadding(0, dp(16), 0, 0)
                SUGGESTIONS.forEach { s ->
                    addView(label("$s  +", 13f, N.INK, fonts.regular).apply {
                        background = Shapes.solid(context, 18, Color.argb(0x8C, 0xFF, 0xFF, 0xFF))
                        setPadding(dp(14), dp(9), dp(14), dp(9))
                        setOnClickListener { chat.send(s) }
                    })
                }
            })
        }
        addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun userBubble(message: String, imagePath: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(40), dp(8), 0, dp(8)) }
        if (imagePath != null) {
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Shapes.solid(context, 26, N.OLIVE)
                clipToOutline = true
                runCatching { setImageBitmap(BitmapFactory.decodeFile(imagePath)) }
            }, LinearLayout.LayoutParams(dp(210), dp(210)).apply { bottomMargin = dp(6) })
        }
        addView(label(message, 16f, N.INK, fonts.regular).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            background = Shapes.solid(context, 20, N.LILAC)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** Painel de vidro escuro onde o Nero responde. */
    private fun assistantPanel(): Pair<LinearLayout, TextView> {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Shapes.glass(context, 28, N.GLASS_DARK)
            setPadding(dp(18), dp(16), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), dp(12), dp(8)) }
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(logo(22, 11))
            addView(dots("NERO", 12f, N.MUTED).apply { setPadding(dp(8), 0, 0, 0) })
        })
        val body = label("", 16f, N.TEXT, fonts.regular).apply { setLineSpacing(0f, 1.3f) }
        panel.addView(body)
        return panel to body
    }

    private fun assistantMessage(message: String, isLast: Boolean): View {
        val (panel, body) = assistantPanel()
        panel.removeView(body)
        // Separa blocos de código (```) em caixas próprias.
        ReminderParser.stripTokens(message).split("```").forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val code = part.substringAfter('\n', part).trimEnd()
                val box = HorizontalScrollView(this).apply {
                    background = Shapes.solid(context, 16, Color.argb(0xCC, 0x0C, 0x0B, 0x09))
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    isHorizontalScrollBarEnabled = false
                    addView(label(code, 13f, N.CREAM, Typeface.MONOSPACE).apply { setTextIsSelectable(true) })
                }
                panel.addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, dp(6)) })
            } else if (part.isNotBlank()) {
                panel.addView(label(part.trim(), 16f, N.TEXT, fonts.regular).apply {
                    setLineSpacing(0f, 1.3f)
                    setTextIsSelectable(true)
                })
            }
        }
        ReminderParser.tokenIds(message).forEach { id ->
            panel.addView(reminderCard(id, chat.reminders.get(id)), LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, dp(12), 0, dp(4))
            })
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        actions.addView(smallAction(R.drawable.ic_copy, "Copiar") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Nero", ReminderParser.stripTokens(message)))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        })
        if (isLast) actions.addView(smallAction(R.drawable.ic_refresh, "Refazer") { chat.regenerate() })
        panel.addView(actions)
        return panel
    }

    /** Cartão do lembrete criado pela IA, no degradê do assistente. */
    private fun reminderCard(id: Long, reminder: Reminder?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Shapes.aurora(context, 24)
        setPadding(dp(16), dp(14), dp(16), dp(12))
        if (reminder == null) {
            addView(dots("LEMBRETE REMOVIDO", 13f, Color.argb(0xAA, 0x15, 0x13, 0x0F)))
            return@apply
        }
        addView(dots("LEMBRETE", 13f, Color.argb(0xAA, 0x15, 0x13, 0x0F)))
        addView(label(reminder.title, 18f, N.INK, fonts.semibold).apply { setPadding(0, dp(4), 0, dp(2)) })
        addView(dots(ReminderParser.describe(reminder).uppercase(Locale("pt", "BR")), 13f, N.INK))
        addView(FlowLayout(context, dp(8)).apply {
            setPadding(0, dp(12), 0, 0)
            addView(label("Adicionar à agenda", 13f, N.TEXT, fonts.regular).apply {
                background = Shapes.solid(context, 18, Color.argb(0xD9, 0x1E, 0x1B, 0x17))
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { addToCalendar(reminder.title, reminder.timeMillis) }
            })
            addView(label("Cancelar", 13f, N.INK, fonts.regular).apply {
                background = Shapes.solid(context, 18, Color.argb(0x73, 0xFF, 0xFF, 0xFF))
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener {
                    ReminderAlarms.delete(this@MainActivity, chat.reminders, id)
                    chat.remindersChanged()
                    Toast.makeText(this@MainActivity, "Lembrete cancelado", Toast.LENGTH_SHORT).show()
                }
            })
        })
    }

    internal fun addToCalendar(title: String, timeMillis: Long) {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, timeMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, timeMillis + 30 * 60_000)
            .putExtra(CalendarContract.Events.DESCRIPTION, "Criado pelo Nero")
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app de agenda encontrado neste celular.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Navegação ----------

    private fun showPage(page: View, show: Boolean) {
        if (show) {
            if (page === settingsView) {
                @Suppress("UNCHECKED_CAST")
                (settingsView.tag as? () -> Unit)?.invoke()
            }
            if (page === remindersView) agenda.render()
            page.alpha = 0f
            page.translationY = dp(24).toFloat()
            page.visibility = View.VISIBLE
            page.animate().alpha(1f).translationY(0f).setDuration(220).withEndAction {
                mainView.visibility = View.INVISIBLE
            }.start()
        } else {
            hideKeyboard()
            mainView.visibility = View.VISIBLE
            page.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(180).withEndAction {
                page.visibility = View.GONE
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

    // ---------- Voz e imagem ----------

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o Nero")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Reconhecimento de voz indisponível. Instale o app Google.", Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseImageSource() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Anexar foto")
            .setItems(arrayOf("Tirar foto", "Escolher da galeria")) { _, which ->
                if (which == 0) openCamera() else openGallery()
            }
            .show()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_GALLERY)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhuma galeria encontrada.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera() {
        // A foto vai para a galeria do celular; daí o Nero lê e reduz o tamanho.
        val uri = runCatching {
            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "nero_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                },
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "Não consegui abrir a câmera. Use a galeria.", Toast.LENGTH_LONG).show()
            return
        }
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app de câmera encontrado.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity sem AndroidX usa o callback clássico")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_VOICE -> {
                val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                input.setText(if (input.text.isBlank()) spoken else "${input.text} $spoken")
                input.setSelection(input.text.length)
            }
            REQ_GALLERY -> data?.data?.let(::importImage)
            REQ_CAMERA -> cameraUri?.let(::importImage)
            REQ_SOUND -> {
                @Suppress("DEPRECATION")
                val picked = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                // "Padrão" volta como o URI genérico de alarme: guarda vazio para seguir o do celular.
                chat.reminders.alarmSound = picked?.takeIf { !RingtoneManager.isDefault(it) }?.toString().orEmpty()
                refreshSoundLabel()
            }
        }
    }

    /** Reduz a foto (no máximo 1280 px) e guarda uma cópia no app, fora da thread principal. */
    private fun importImage(uri: Uri) {
        Thread {
            val path = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
                val bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("imagem ilegível")
                val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else bitmap
                val dir = File(filesDir, "images").apply { mkdirs() }
                val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                file.absolutePath
            }.getOrNull()
            runOnUiThread {
                if (path != null) chat.attachImage(path)
                else Toast.makeText(this, "Não consegui abrir essa imagem.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    internal fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---------- Som do alarme ----------

    private fun pickAlarmSound() {
        val current = chat.reminders.alarmSound.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Som do alarme do Nero")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SOUND)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Este celular não tem seletor de sons.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshSoundLabel() {
        val uri = chat.reminders.alarmSound.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        val name = uri?.let { runCatching { RingtoneManager.getRingtone(this, it)?.getTitle(this) }.getOrNull() }
        soundLabel.text = "Som: " + (name ?: "alarme padrão do celular")
    }

    // ---------- Peças visuais ----------

    private fun neroSwitch() = Switch(this).apply {
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(N.CREAM, N.MUTED),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(N.LILAC_DEEP, Color.argb(0x55, 0xFF, 0xFF, 0xFF)),
        )
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun match() = FrameLayout.LayoutParams(MATCH, MATCH)

    internal fun label(value: String, sizeSp: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = face
    }

    /** Texto em matriz de pontos, usado em títulos curtos e horários. */
    internal fun dots(value: String, sizeSp: Float, color: Int) = label(value, sizeSp, color, fonts.dots)

    private fun hint(value: String) = label(value, 13f, N.MUTED, fonts.light).apply {
        setLineSpacing(0f, 1.25f)
        setPadding(0, dp(10), 0, 0)
    }

    private fun logo(sizeDp: Int, radiusDp: Int) = ImageView(this).apply {
        setImageResource(R.drawable.nero_logo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Shapes.solid(context, radiusDp, N.OLIVE)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Botão redondo de vidro, como os das barras superiores das referências. */
    internal fun roundButton(res: Int, description: String, size: Int = 48, onClick: () -> Unit) =
        ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(N.TEXT)
            background = Shapes.circle(N.GLASS, N.GLASS_STROKE, context)
            val pad = dp(size / 4 + 1)
            setPadding(pad, pad, pad, pad)
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            setOnClickListener { onClick() }
        }

    private fun smallAction(res: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(N.MUTED)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener { onClick() }
    }

    /** Pílula principal: creme cheia (ação principal), escura ou de vidro. */
    internal fun pill(title: String, filled: Boolean, dark: Boolean = false, onClick: () -> Unit) =
        label(title, 16f, if (filled) N.INK else N.TEXT, fonts.semibold).apply {
            gravity = Gravity.CENTER
            background = when {
                filled -> Shapes.solid(context, 28, N.CREAM)
                dark -> Shapes.solid(context, 28, Color.argb(0xE0, 0x1E, 0x1B, 0x17))
                else -> Shapes.glass(context, 28)
            }
            setOnClickListener { onClick() }
        }

    internal fun chip(title: String, onClick: () -> Unit) = label(title, 14f, N.TEXT, fonts.regular).apply {
        background = Shapes.glass(context, 20)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        setOnClickListener { onClick() }
    }

    internal fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(N.FAINT)
        setTextColor(N.TEXT)
        typeface = fonts.regular
        textSize = 15f
        isSingleLine = true
        background = Shapes.glass(context, 18, Color.argb(0x1A, 0xFF, 0xFF, 0xFF))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun card(title: String, vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Shapes.glass(context, 28, N.GLASS_DARK)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(dots(title, 13f, N.MUTED).apply {
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(12))
        })
        children.forEach { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
    }

    /** Cabeçalho das páginas: botão voltar de vidro e título grande com cursor. */
    internal fun pageHeader(title: String, onBack: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(12))
        addView(roundButton(R.drawable.ic_back, "Voltar") { onBack() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(18), 0, 0)
            addView(label(title, 40f, N.TEXT, fonts.light))
            addView(View(context).apply { background = Shapes.solid(context, 2, N.LILAC) },
                LinearLayout.LayoutParams(dp(3), dp(40)).apply { marginStart = dp(10) })
        })
    }

    companion object {
        /** Extra da notificação que pede para abrir a agenda. */
        const val EXTRA_OPEN_AGENDA = "abrir_agenda"
        private const val REQ_VOICE = 1
        private const val REQ_GALLERY = 2
        private const val REQ_CAMERA = 3
        private const val REQ_NOTIFICATIONS = 4
        private const val REQ_SOUND = 5
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
