package com.nero.assistant

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nero.assistant.data.Reminder
import com.nero.assistant.data.ReminderParser
import com.nero.assistant.data.ReminderStore
import com.nero.assistant.data.Repeat
import com.nero.assistant.data.LEAD_OPTIONS
import com.nero.assistant.data.leadLabel
import com.nero.assistant.ui.FlowLayout
import com.nero.assistant.ui.N
import com.nero.assistant.ui.Shapes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Agenda do Nero: calendário do mês com pontinhos nos dias que têm lembrete, a lista do dia
 * escolhido e um editor para criar, mudar, repetir ou apagar lembretes à mão.
 */
class AgendaPage(
    private val a: MainActivity,
    private val store: ReminderStore,
    /** Avisa o resto do app que a agenda mudou. */
    private val onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    private val fonts = a.fonts
    private val content = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(32))
    }

    /** Primeiro dia do mês mostrado. */
    private val month: Calendar = startOfDay(System.currentTimeMillis()).apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var selected: Calendar = startOfDay(System.currentTimeMillis())

    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        addView(a.pageHeader("Agenda", onBack))
        addView(ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    fun showToday() {
        selected = startOfDay(System.currentTimeMillis())
        month.timeInMillis = selected.timeInMillis
        month.set(Calendar.DAY_OF_MONTH, 1)
        render()
    }

    fun render() {
        content.removeAllViews()
        content.addView(monthCard())
        content.addView(dayPanel())
        content.addView(upcomingPanel())
    }

    // ---------- Mês ----------

    private fun monthCard(): View = panel().apply {
        setPadding(a.dp(12), a.dp(14), a.dp(12), a.dp(14))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(a.roundButton(R.drawable.ic_back, "Mês anterior", size = 40) { shiftMonth(-1) })
            addView(a.dots(format("MMMM yyyy", month.timeInMillis).uppercase(PT_BR), 20f, N.TEXT).apply {
                gravity = Gravity.CENTER
                contentDescription = "Voltar para hoje"
                setOnClickListener { showToday() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(a.roundButton(R.drawable.ic_back, "Próximo mês", size = 40) { shiftMonth(1) }.apply { rotation = 180f })
        })

        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, a.dp(14), 0, a.dp(4))
            WEEKDAYS.forEach { d ->
                addView(a.dots(d, 12f, N.FAINT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        })

        // Quantos lembretes caem em cada dia do mês (já contando as repetições).
        val from = month.timeInMillis
        val to = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        val perDay = IntArray(32)
        val cal = Calendar.getInstance()
        store.occurrencesBetween(from, to).forEach { (_, t) ->
            cal.timeInMillis = t
            perDay[cal.get(Calendar.DAY_OF_MONTH)]++
        }

        val offset = month.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val days = month.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = startOfDay(System.currentTimeMillis())
        val rows = (offset + days + 6) / 7
        for (row in 0 until rows) {
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                for (col in 0 until 7) {
                    val day = row * 7 + col - offset + 1
                    val cell = if (day in 1..days) {
                        val date = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }
                        dayCell(day, perDay[day], sameDay(date, selected), sameDay(date, today)) {
                            selected = date
                            render()
                        }
                    } else View(a)
                    addView(cell, LinearLayout.LayoutParams(0, a.dp(50), 1f))
                }
            })
        }
    }

    private fun dayCell(day: Int, count: Int, isSelected: Boolean, isToday: Boolean, onClick: () -> Unit): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = "Dia $day" + if (count > 0) ", $count lembrete(s)" else ""
            setOnClickListener { onClick() }
            addView(a.label(day.toString(), 15f, if (isSelected) N.INK else N.TEXT, if (isSelected) fonts.semibold else fonts.regular).apply {
                gravity = Gravity.CENTER
                background = when {
                    isSelected -> Shapes.auroraCircle()
                    isToday -> Shapes.circle(Color.TRANSPARENT, N.LILAC, a)
                    else -> null
                }
            }, LinearLayout.LayoutParams(a.dp(36), a.dp(36)))
            addView(View(a).apply {
                background = Shapes.circle(N.LILAC)
                visibility = if (count > 0) View.VISIBLE else View.INVISIBLE
            }, LinearLayout.LayoutParams(a.dp(5), a.dp(5)).apply { topMargin = a.dp(3) })
        }

    private fun shiftMonth(delta: Int) {
        month.add(Calendar.MONTH, delta)
        selected = (month.clone() as Calendar).apply {
            val today = startOfDay(System.currentTimeMillis())
            if (sameMonth(this, today)) set(Calendar.DAY_OF_MONTH, today.get(Calendar.DAY_OF_MONTH))
        }
        render()
    }

    // ---------- Dia escolhido ----------

    private fun dayPanel(): View = panel().apply {
        val from = selected.timeInMillis
        val to = (selected.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val items = store.occurrencesBetween(from, to)
        val title = when (dayDiff(selected)) {
            0 -> "HOJE"
            1 -> "AMANHÃ"
            -1 -> "ONTEM"
            else -> format("EEEE, d 'de' MMMM", from).uppercase(PT_BR)
        }
        addView(a.dots(title, 18f, N.TEXT).apply { setPadding(a.dp(4), 0, 0, a.dp(14)) })
        if (items.isEmpty()) {
            addView(a.label("Nada neste dia.", 15f, N.MUTED, fonts.light).apply {
                setPadding(a.dp(4), 0, 0, a.dp(14))
            })
        }
        items.forEach { (reminder, time) -> addView(row(reminder, time)) }
        addView(a.pill("Novo lembrete", filled = true) { openEditor(null) }, LinearLayout.LayoutParams(MATCH, a.dp(52)).apply {
            topMargin = a.dp(6)
        })
    }

    private fun row(reminder: Reminder, time: Long): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Shapes.solid(a, 28, Color.argb(0x55, 0, 0, 0))
        setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = a.dp(8) }
        setOnClickListener { openEditor(reminder) }

        addView(android.widget.ImageView(a).apply {
            contentDescription = if (reminder.done) "Desmarcar lembrete" else "Concluir lembrete"
            if (reminder.done) markChecked() else background = Shapes.circle(Color.TRANSPARENT, N.MUTED, a)
            setOnClickListener { toggleDone(reminder, this) }
        }, LinearLayout.LayoutParams(a.dp(30), a.dp(30)))

        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(14), 0, a.dp(8), 0)
            addView(a.label(reminder.title, 16f, if (reminder.done) N.FAINT else N.TEXT, fonts.regular).apply {
                if (reminder.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            })
            val repeat = if (reminder.repeat != Repeat.NONE) " · ${reminder.repeat.label}" else ""
            val alert = when {
                !reminder.alarm -> ""
                reminder.leadMinutes > 0 -> " · Alarme ${leadLabel(reminder.leadMinutes).lowercase(PT_BR)}"
                else -> " · Alarme"
            }
            addView(a.dots((format("HH:mm", time) + repeat + alert).uppercase(PT_BR), 11f, if (reminder.done) N.FAINT else N.LILAC))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        addView(a.roundButton(R.drawable.ic_event, "Adicionar à agenda do celular", size = 40) {
            a.addToCalendar(reminder.title, time)
        })
    }

    private fun android.widget.ImageView.markChecked() {
        setImageResource(R.drawable.ic_check)
        imageTintList = ColorStateList.valueOf(N.INK)
        background = Shapes.circle(N.LILAC)
        setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
    }

    private fun toggleDone(reminder: Reminder, check: android.widget.ImageView) {
        if (reminder.done) {
            ReminderAlarms.save(a, store, reminder.copy(done = false))
            changed()
            return
        }
        check.markChecked()
        check.postDelayed({
            val updated = ReminderAlarms.complete(a, store, reminder.id)
            if (updated != null && updated.repeat != Repeat.NONE) {
                toast("Feito! Próxima vez: ${ReminderParser.describe(updated.timeMillis)}")
            }
            changed()
        }, 300)
    }

    // ---------- Próximos ----------

    private fun upcomingPanel(): View = panel().apply {
        addView(a.dots("PRÓXIMOS", 18f, N.TEXT).apply { setPadding(a.dp(4), 0, 0, a.dp(12)) })
        val next = store.upcoming().take(6)
        if (next.isEmpty()) {
            addView(a.label(
                "Nada agendado. Toque em um dia e em \"Novo lembrete\", ou peça no chat: " +
                    "\"me lembra toda segunda às 8h de levar o lixo\".",
                15f, N.MUTED, fonts.light,
            ).apply { setLineSpacing(0f, 1.3f) })
        }
        next.forEach { r ->
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(10))
                setOnClickListener {
                    selected = startOfDay(r.timeMillis)
                    month.timeInMillis = selected.timeInMillis
                    month.set(Calendar.DAY_OF_MONTH, 1)
                    render()
                }
                addView(a.label(r.title, 16f, N.TEXT, fonts.regular))
                addView(a.dots(ReminderParser.describe(r).uppercase(PT_BR), 11f, N.LILAC))
            })
        }
    }

    // ---------- Editor ----------

    private fun openEditor(existing: Reminder?) {
        val time = Calendar.getInstance().apply {
            if (existing != null) {
                timeInMillis = existing.timeMillis
            } else {
                timeInMillis = selected.timeInMillis
                val now = Calendar.getInstance()
                if (sameDay(this, now) && now.get(Calendar.HOUR_OF_DAY) >= 8) {
                    // Hoje: próxima hora cheia (ou 23:59 se já for tarde).
                    if (now.get(Calendar.HOUR_OF_DAY) >= 23) {
                        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                    } else set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY) + 1)
                } else set(Calendar.HOUR_OF_DAY, 9)
            }
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var repeat = existing?.repeat ?: Repeat.NONE
        var alarm = existing?.alarm ?: store.alarmByDefault
        var lead = existing?.leadMinutes ?: 0

        val box = panel().apply { setPadding(a.dp(20), a.dp(22), a.dp(20), a.dp(18)) }
        val dialog = AlertDialog.Builder(a).setView(ScrollView(a).apply { addView(box) }).create()

        box.addView(a.dots(if (existing == null) "NOVO LEMBRETE" else "EDITAR LEMBRETE", 18f, N.TEXT).apply {
            setPadding(0, 0, 0, a.dp(14))
        })
        val title = a.field("O que devo lembrar?").apply {
            isSingleLine = false
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(existing?.title.orEmpty())
        }
        box.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))

        val dateChip = a.chip("") {}
        val timeChip = a.chip("") {}
        fun refreshChips() {
            dateChip.text = format("EEE, d 'de' MMM", time.timeInMillis).replaceFirstChar { it.uppercase() }
            timeChip.text = format("HH:mm", time.timeInMillis)
        }
        refreshChips()
        dateChip.setOnClickListener {
            DatePickerDialog(a, PICKER_THEME, { _, y, m, d ->
                time.set(y, m, d)
                refreshChips()
            }, time.get(Calendar.YEAR), time.get(Calendar.MONTH), time.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeChip.setOnClickListener {
            TimePickerDialog(a, PICKER_THEME, { _, h, min ->
                time.set(Calendar.HOUR_OF_DAY, h)
                time.set(Calendar.MINUTE, min)
                refreshChips()
            }, time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE), true).show()
        }
        box.addView(a.dots("QUANDO", 12f, N.MUTED).apply { setPadding(0, a.dp(18), 0, a.dp(8)) })
        box.addView(FlowLayout(a, a.dp(8)).apply {
            addView(dateChip)
            addView(timeChip)
        })

        box.addView(a.dots("REPETIR", 12f, N.MUTED).apply { setPadding(0, a.dp(18), 0, a.dp(8)) })
        val repeatChips = mutableMapOf<Repeat, TextView>()
        fun refreshRepeat() = repeatChips.forEach { (r, chip) -> select(chip, r == repeat) }
        box.addView(FlowLayout(a, a.dp(8)).apply {
            Repeat.entries.forEach { r ->
                val chip = a.chip(r.label) {
                    repeat = r
                    refreshRepeat()
                }
                repeatChips[r] = chip
                addView(chip)
            }
        })
        refreshRepeat()

        // Como avisar: alarme do Nero (som + tela cheia) ou só notificação, e com quanta antecedência.
        box.addView(a.dots("AVISO", 12f, N.MUTED).apply { setPadding(0, a.dp(18), 0, a.dp(8)) })
        val alertChips = mutableMapOf<Boolean, TextView>()
        val leadChips = mutableMapOf<Int, TextView>()
        fun refreshAlert() {
            alertChips.forEach { (on, chip) -> select(chip, on == alarm) }
            leadChips.forEach { (m, chip) -> select(chip, m == lead) }
        }
        box.addView(FlowLayout(a, a.dp(8)).apply {
            listOf(true to "Alarme com som", false to "Só notificação").forEach { (on, title) ->
                val chip = a.chip(title) {
                    alarm = on
                    refreshAlert()
                }
                alertChips[on] = chip
                addView(chip)
            }
        })
        box.addView(FlowLayout(a, a.dp(8)).apply {
            setPadding(0, a.dp(8), 0, 0)
            (LEAD_OPTIONS + listOfNotNull(lead.takeIf { it !in LEAD_OPTIONS })).forEach { m ->
                val chip = a.chip(leadLabel(m)) {
                    lead = m
                    refreshAlert()
                }
                leadChips[m] = chip
                addView(chip)
            }
        })
        refreshAlert()

        box.addView(a.pill("Salvar", filled = true) {
            val text = title.text.toString().trim()
            when {
                text.isEmpty() -> toast("Escreva o que devo lembrar.")
                repeat == Repeat.NONE && time.timeInMillis <= System.currentTimeMillis() ->
                    toast("Esse horário já passou. Escolha outro.")
                else -> {
                    val saved = if (existing == null) {
                        store.add(text, time.timeInMillis, repeat, alarm, lead).also { ReminderAlarms.schedule(a, it) }
                    } else {
                        existing.copy(
                            title = text, timeMillis = time.timeInMillis, repeat = repeat, done = false,
                            alarm = alarm, leadMinutes = lead,
                        )
                            .also { ReminderAlarms.save(a, store, it) }
                    }
                    a.askNotificationPermission()
                    selected = startOfDay(saved.timeMillis)
                    month.timeInMillis = selected.timeInMillis
                    month.set(Calendar.DAY_OF_MONTH, 1)
                    dialog.dismiss()
                    changed()
                }
            }
        }, LinearLayout.LayoutParams(MATCH, a.dp(52)).apply { topMargin = a.dp(24) })

        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, a.dp(10), 0, 0)
            if (existing != null) {
                addView(a.label("Apagar", 15f, N.ERROR, fonts.regular).apply {
                    setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10))
                    setOnClickListener {
                        ReminderAlarms.delete(a, store, existing.id)
                        dialog.dismiss()
                        toast("Lembrete apagado")
                        changed()
                    }
                })
            }
            addView(a.label("Cancelar", 15f, N.MUTED, fonts.regular).apply {
                setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10))
                setOnClickListener { dialog.dismiss() }
            })
        })

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    // ---------- Apoio ----------

    private fun changed() {
        render()
        onChanged()
    }

    private fun panel() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = Shapes.glass(a, 30, N.GLASS_DARKER)
        setPadding(a.dp(16), a.dp(20), a.dp(16), a.dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = a.dp(14) }
    }

    private fun select(chip: TextView, on: Boolean) {
        chip.background = if (on) Shapes.aurora(a, 20) else Shapes.glass(a, 20)
        chip.setTextColor(if (on) N.INK else N.TEXT)
    }

    private fun toast(message: String) = Toast.makeText(a, message, Toast.LENGTH_SHORT).show()

    private fun format(pattern: String, time: Long) = SimpleDateFormat(pattern, PT_BR).format(Date(time))

    private fun startOfDay(time: Long) = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun sameDay(x: Calendar, y: Calendar) =
        x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)

    private fun sameMonth(x: Calendar, y: Calendar) =
        x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.MONTH) == y.get(Calendar.MONTH)

    private fun dayDiff(day: Calendar): Int =
        Math.round((day.timeInMillis - startOfDay(System.currentTimeMillis()).timeInMillis) / 86_400_000.0).toInt()

    private companion object {
        val PT_BR = Locale("pt", "BR")
        val WEEKDAYS = listOf("D", "S", "T", "Q", "Q", "S", "S")
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        @Suppress("DEPRECATION")
        val PICKER_THEME = AlertDialog.THEME_DEVICE_DEFAULT_DARK
    }
}
