package com.nero.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nero.assistant.R
import com.nero.assistant.data.Conversation
import com.nero.assistant.ui.theme.NeroEye
import com.nero.assistant.ui.theme.NeroGold
import com.nero.assistant.ui.theme.NeroMuted
import com.nero.assistant.ui.theme.NeroNight
import com.nero.assistant.ui.theme.NeroSurface
import com.nero.assistant.ui.theme.NeroTeal
import com.nero.assistant.ui.theme.NeroTealLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Screen { SPLASH, CHAT, SETTINGS }

@Composable
fun NeroApp(vm: ChatViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.SPLASH) }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) },
        label = "screen",
    ) { target ->
        when (target) {
            Screen.SPLASH -> SplashScreen { screen = Screen.CHAT }
            Screen.CHAT -> MainScreen(vm, onOpenSettings = { screen = Screen.SETTINGS })
            Screen.SETTINGS -> SettingsScreen(vm, onBack = { screen = Screen.CHAT })
        }
    }
}

@Composable
private fun SplashScreen(onDone: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, tween(700)) }
        scale.animateTo(1f, tween(900))
        delay(500)
        onDone()
    }
    Box(
        Modifier.fillMaxSize().background(NeroTeal),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.nero_logo),
                contentDescription = "Nero",
                modifier = Modifier.size(180.dp).scale(scale.value).alpha(alpha.value)
                    .clip(RoundedCornerShape(48.dp)),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "NERO",
                modifier = Modifier.alpha(alpha.value),
                color = NeroEye,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 10.sp,
            )
            Text(
                "seu assistente pessoal",
                modifier = Modifier.alpha(alpha.value),
                color = NeroMuted,
                fontSize = 14.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MainScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = vm.conversations,
                currentId = vm.current.id,
                onNew = { vm.newConversation(); scope.launch { drawerState.close() } },
                onOpen = { vm.open(it); scope.launch { drawerState.close() } },
                onDelete = vm::delete,
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            containerColor = NeroNight,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = NeroNight),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Image(
                                painter = painterResource(R.drawable.nero_logo),
                                contentDescription = "Histórico",
                                modifier = Modifier.size(34.dp).clip(CircleShape),
                            )
                        }
                    },
                    title = {
                        Column {
                            Text("Nero", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(
                                if (vm.deepMode) "Modo profundo" else "Modo rápido",
                                color = if (vm.deepMode) NeroGold else NeroMuted,
                                fontSize = 12.sp,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::newConversation) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Nova conversa")
                        }
                    },
                )
            },
        ) { padding ->
            ChatScreen(vm, Modifier.padding(padding).consumeWindowInsets(padding), onOpenSettings)
        }
    }
}

@Composable
private fun HistoryDrawer(
    conversations: List<Conversation>,
    currentId: String,
    onNew: () -> Unit,
    onOpen: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = NeroSurface) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.nero_logo),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Nero", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Premium", color = NeroGold, fontSize = 12.sp, letterSpacing = 2.sp)
            }
        }
        Button(
            onClick = onNew,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeroEye, contentColor = NeroNight),
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Nova conversa", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "RECENTES",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            color = NeroMuted,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
        )
        LazyColumn(Modifier.weight(1f)) {
            if (conversations.isEmpty()) {
                item {
                    Text(
                        "Suas conversas aparecem aqui.",
                        modifier = Modifier.padding(20.dp),
                        color = NeroMuted,
                        fontSize = 14.sp,
                    )
                }
            }
            items(conversations, key = { it.id }) { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (c.id == currentId) NeroTeal else NeroSurface)
                        .clickable { onOpen(c) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        tint = NeroMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        c.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                    )
                    IconButton(onClick = { onDelete(c) }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Apagar",
                            tint = NeroMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = NeroTealLight)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null, tint = NeroEye)
            Spacer(Modifier.width(12.dp))
            Text("Ajustes")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(vm.apiKey) }
    var name by rememberSaveable { mutableStateOf(vm.userName) }
    var saved by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NeroEye,
        unfocusedBorderColor = NeroTealLight,
        cursorColor = NeroEye,
    )

    Scaffold(
        containerColor = NeroNight,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NeroNight),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                title = { Text("Ajustes", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsCard("Seu nome") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; saved = false },
                    placeholder = { Text("Como o Nero deve te chamar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
            }
            SettingsCard("Chave de API da Anthropic") {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; saved = false },
                    placeholder = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Crie a sua em console.anthropic.com. Ela fica salva só neste aparelho.",
                    color = NeroMuted,
                    fontSize = 13.sp,
                )
            }
            SettingsCard("Modo profundo") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Raciocina mais antes de responder. Ideal para tarefas difíceis; um pouco mais lento.",
                        modifier = Modifier.weight(1f),
                        color = NeroMuted,
                        fontSize = 13.sp,
                    )
                    androidx.compose.material3.Switch(
                        checked = vm.deepMode,
                        onCheckedChange = { vm.toggleDeepMode() },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = NeroNight,
                            checkedTrackColor = NeroGold,
                        ),
                    )
                }
            }
            Button(
                onClick = { vm.saveSettings(key, name); saved = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeroEye, contentColor = NeroNight),
            ) {
                Text(if (saved) "Salvo ✓" else "Salvar", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Nero 1.0 · feito com Claude",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = NeroMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NeroSurface)
            .padding(16.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        content()
    }
}
