package com.nero.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nero.assistant.R
import com.nero.assistant.data.ChatTurn
import com.nero.assistant.data.Role
import com.nero.assistant.ui.theme.NeroBlack
import com.nero.assistant.ui.theme.NeroEye
import com.nero.assistant.ui.theme.NeroGold
import com.nero.assistant.ui.theme.NeroMuted
import com.nero.assistant.ui.theme.NeroNight
import com.nero.assistant.ui.theme.NeroSurface
import com.nero.assistant.ui.theme.NeroTeal
import com.nero.assistant.ui.theme.NeroTealLight

private val Suggestions = listOf(
    "Planeje meu dia de forma produtiva",
    "Escreva um e-mail profissional",
    "Explique um assunto de forma simples",
    "Me dê ideias criativas para um projeto",
    "Revise e melhore este texto",
    "Monte um treino rápido em casa",
)

@Composable
fun ChatScreen(vm: ChatViewModel, modifier: Modifier, onOpenSettings: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val turns = vm.current.turns
    val streaming = vm.streamingText

    LaunchedEffect(turns.size, streaming?.length) {
        val count = turns.size + (if (streaming != null) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            if (turns.isEmpty() && streaming == null) {
                EmptyState(
                    userName = vm.userName,
                    needsKey = vm.apiKey.isBlank(),
                    onSuggestion = { vm.send(it) },
                    onOpenSettings = onOpenSettings,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(turns) { index, turn ->
                        if (turn.role == Role.USER) {
                            UserBubble(turn.text)
                        } else {
                            AssistantMessage(
                                text = turn.text,
                                isLast = index == turns.lastIndex,
                                busy = vm.isBusy,
                                onRegenerate = vm::regenerate,
                            )
                        }
                    }
                    if (streaming != null) {
                        item { StreamingMessage(streaming) }
                    }
                }
            }
        }

        vm.error?.let { message ->
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF3A1F22))
                    .clickable { vm.clearError(); if (vm.apiKey.isBlank()) onOpenSettings() }
                    .padding(14.dp),
            ) {
                Text(message, color = Color(0xFFFFB4AB), fontSize = 14.sp)
            }
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            busy = vm.isBusy,
            deepMode = vm.deepMode,
            onToggleDeep = vm::toggleDeepMode,
            onSend = { vm.send(input); input = "" },
            onStop = vm::stop,
        )
    }
}

@Composable
private fun EmptyState(
    userName: String,
    needsKey: Boolean,
    onSuggestion: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.nero_logo),
            contentDescription = null,
            modifier = Modifier.size(110.dp).clip(RoundedCornerShape(32.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            if (userName.isBlank()) "Olá. Eu sou o Nero." else "Olá, $userName.",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = NeroEye,
        )
        Spacer(Modifier.height(6.dp))
        Text("Em que posso ajudar hoje?", color = NeroMuted, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))

        if (needsKey) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(NeroTeal)
                    .clickable(onClick = onOpenSettings)
                    .padding(18.dp),
            ) {
                Text("Ative o Nero", fontWeight = FontWeight.Bold, color = NeroGold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Toque aqui e cole sua chave de API da Anthropic para começar.",
                    color = NeroEye,
                    fontSize = 14.sp,
                )
            }
        } else {
            Suggestions.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { s ->
                        Text(
                            s,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(NeroSurface)
                                .border(1.dp, NeroTealLight, RoundedCornerShape(16.dp))
                                .clickable { onSuggestion(s) }
                                .padding(14.dp),
                            color = NeroEye,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(NeroTeal)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = NeroEye,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

@Composable
private fun NeroAvatar() {
    Image(
        painter = painterResource(R.drawable.nero_logo),
        contentDescription = null,
        modifier = Modifier.size(28.dp).clip(CircleShape),
    )
}

@Composable
private fun AssistantMessage(text: String, isLast: Boolean, busy: Boolean, onRegenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row {
        NeroAvatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RichText(text)
            Row(Modifier.padding(top = 2.dp)) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Copiar", tint = NeroMuted, modifier = Modifier.size(18.dp))
                }
                if (isLast && !busy) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Refresh, "Refazer", tint = NeroMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingMessage(text: String) {
    Row {
        NeroAvatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (text.isEmpty()) ThinkingDots() else RichText(text + " ▍")
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = i * 180, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(8.dp)
                    .alpha(a)
                    .clip(CircleShape)
                    .background(NeroEye)
            )
        }
        Text("Nero está pensando…", color = NeroMuted, fontSize = 13.sp)
    }
}

/** Renderiza texto com blocos de código (```), destacando-os em caixas. */
@Composable
private fun RichText(text: String) {
    val parts = text.split("```")
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEachIndexed { i, part ->
                if (i % 2 == 1) {
                    val code = part.substringAfter('\n', part).trimEnd()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(NeroBlack)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = NeroEye)
                    }
                } else if (part.isNotBlank()) {
                    Text(part.trim(), color = NeroEye, fontSize = 16.sp, lineHeight = 24.sp)
                }
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    deepMode: Boolean,
    onToggleDeep: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .padding(12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(NeroSurface)
            .border(1.dp, NeroTealLight, RoundedCornerShape(28.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onToggleDeep) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = "Modo profundo",
                tint = if (deepMode) NeroGold else NeroMuted,
            )
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Pergunte ao Nero…", color = NeroMuted) },
            maxLines = 6,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = NeroEye,
            ),
        )
        val canSend = value.isNotBlank()
        IconButton(
            onClick = if (busy) onStop else onSend,
            enabled = busy || canSend,
            modifier = Modifier
                .clip(CircleShape)
                .background(if (busy || canSend) NeroEye else NeroTeal),
        ) {
            Icon(
                if (busy) Icons.Rounded.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (busy) "Parar" else "Enviar",
                tint = NeroNight,
            )
        }
    }
}
