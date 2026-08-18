package com.jumura.translate

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jumura.translate.core.Engine
import com.jumura.translate.core.UpdateInfo
import com.jumura.translate.core.Updater
import com.jumura.translate.service.CaptureService
import com.jumura.translate.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JumuraTheme {
                Surface(color = MaterialTheme.colorScheme.background) { JumuraScreen() }
            }
        }
    }
}

@Composable
private fun JumuraScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val cfg = Engine.config

    // --- État moteur (observé en direct) ---
    val lines by Engine.lines.collectAsStateWithLifecycle()
    val running by Engine.running.collectAsStateWithLifecycle()
    val status by Engine.status.collectAsStateWithLifecycle()
    val levelRaw by Engine.level.collectAsStateWithLifecycle()
    val level by animateFloatAsState(targetValue = levelRaw, label = "level")

    // --- Réglages locaux, miroir de Config (écrit à chaque changement) ---
    var groqKey by remember { mutableStateOf(cfg.groqKey) }
    var sourceLang by remember { mutableStateOf(cfg.sourceLang) }
    var micGain by remember { mutableFloatStateOf(cfg.micGain) }
    var showOriginal by remember { mutableStateOf(cfg.showOriginal) }
    var textSize by remember { mutableFloatStateOf(cfg.textSize) }
    var keepScreenOn by remember { mutableStateOf(cfg.keepScreenOn) }
    var sttModel by remember { mutableStateOf(cfg.sttModel) }
    var trModel by remember { mutableStateOf(cfg.translateModel) }
    val hasKey = groqKey.startsWith("gsk_")

    var showSettings by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateProgress by remember { mutableIntStateOf(-1) }

    // --- Permissions ---
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMic = granted
        if (granted) CaptureService.start(context)
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* facultatif */ }

    fun toggle() {
        if (running) {
            CaptureService.stop(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (hasMic) CaptureService.start(context)
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Garde l'écran allumé pendant l'écoute (lecture continue du prêche).
    LaunchedEffect(running, keepScreenOn) {
        val a = activity ?: return@LaunchedEffect
        if (running && keepScreenOn) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Vérifie les mises à jour au lancement.
    LaunchedEffect(Unit) {
        val info = Updater.check()
        if (info.available) update = info
    }

    Scaffold(
        containerColor = JumuraBg,
        bottomBar = {
            MicControls(
                running = running,
                level = level,
                status = status,
                canClear = lines.isNotEmpty(),
                onToggle = { toggle() },
                onClear = { Engine.clear() }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            Header(onSettings = { showSettings = true })

            update?.let { info ->
                UpdateBanner(
                    info = info,
                    progress = updateProgress,
                    onInstall = {
                        scope.launch {
                            Updater.downloadAndInstall(context, info.apkUrl) { updateProgress = it }
                        }
                    },
                    onDismiss = { update = null }
                )
            }

            if (!hasKey) {
                KeyWarning(onOpen = { showSettings = true })
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty()) {
                    EmptyState(running)
                } else {
                    Transcript(
                        lines = lines,
                        showOriginal = showOriginal,
                        textSize = textSize
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            groqKey = groqKey,
            onKey = { groqKey = it },
            onSaveKey = { cfg.groqKey = groqKey; groqKey = cfg.groqKey },
            sourceLang = sourceLang,
            onSourceLang = { sourceLang = it; cfg.sourceLang = it },
            micGain = micGain,
            onMicGain = { micGain = it; cfg.micGain = it },
            showOriginal = showOriginal,
            onShowOriginal = { showOriginal = it; cfg.showOriginal = it },
            textSize = textSize,
            onTextSize = { textSize = it; cfg.textSize = it },
            keepScreenOn = keepScreenOn,
            onKeepScreenOn = { keepScreenOn = it; cfg.keepScreenOn = it },
            sttModel = sttModel,
            onSttModel = { sttModel = it; cfg.sttModel = it },
            trModel = trModel,
            onTrModel = { trModel = it; cfg.translateModel = it },
            onCheckUpdate = {
                scope.launch {
                    val info = Updater.check()
                    update = if (info.available) info else null
                }
            },
            onClose = { showSettings = false }
        )
    }
}

/* ---------------- En-tête ---------------- */

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Jumura",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = JumuraGold
            )
            Text(
                "Comprends le prêche, en direct",
                fontSize = 13.sp,
                color = JumuraMuted
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Réglages", tint = JumuraText)
        }
    }
    HorizontalDivider(color = JumuraSurfaceHigh, thickness = 1.dp)
}

/* ---------------- Bannière de mise à jour ---------------- */

@Composable
private fun UpdateBanner(
    info: UpdateInfo,
    progress: Int,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = JumuraSurfaceHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = JumuraEmerald)
                Spacer(Modifier.width(10.dp))
                Text(info.message, color = JumuraText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            if (progress in 0..99) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    color = JumuraEmerald,
                    trackColor = JumuraSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$progress %", color = JumuraMuted, fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = JumuraEmerald, contentColor = Color(0xFF04140D))
                    ) { Text("Installer") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text("Plus tard", color = JumuraMuted) }
                }
            }
        }
    }
}

@Composable
private fun KeyWarning(onOpen: () -> Unit) {
    Surface(
        color = Color(0xFF2A2010),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable { onOpen() }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔑", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Ajoute ta clé Groq (gratuite) dans les réglages pour activer la traduction.",
                color = JumuraGoldSoft,
                fontSize = 14.sp
            )
        }
    }
}

/* ---------------- Transcript ---------------- */

@Composable
private fun Transcript(
    lines: List<Engine.Line>,
    showOriginal: Boolean,
    textSize: Float
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        items(lines, key = { it.id }) { line ->
            LineCard(line, showOriginal, textSize)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LineCard(line: Engine.Line, showOriginal: Boolean, textSize: Float) {
    val arabic = isArabic(line.original, line.lang)
    Surface(
        color = JumuraSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            if (showOriginal && line.original.isNotBlank()) {
                Text(
                    text = line.original,
                    color = JumuraArabic.copy(alpha = 0.85f),
                    fontSize = (textSize * 0.66f).sp,
                    textAlign = if (arabic) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = JumuraSurfaceHigh, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))
            }
            when (line.status) {
                Engine.Status.TRANSLATING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = JumuraEmerald,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Traduction…", color = JumuraMuted, fontSize = 14.sp)
                }
                Engine.Status.ERROR -> Text(
                    line.french,
                    color = JumuraRed,
                    fontSize = textSize.sp
                )
                else -> Text(
                    line.french,
                    color = JumuraText,
                    fontSize = textSize.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = (textSize * 1.35f).sp
                )
            }
        }
    }
}

@Composable
private fun EmptyState(running: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("☾", fontSize = 64.sp, color = JumuraGold)
        Spacer(Modifier.height(20.dp))
        Text(
            if (running) "À l'écoute de l'imam…" else "Prêt à traduire",
            color = JumuraText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Appuie sur le micro et pose le téléphone face à l'imam.\nLa traduction française s'affiche ici, en direct.",
            color = JumuraMuted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

/* ---------------- Contrôles (bas d'écran) ---------------- */

@Composable
private fun MicControls(
    running: Boolean,
    level: Float,
    status: String,
    canClear: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit
) {
    Surface(color = JumuraBg) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                status,
                color = if (running) JumuraEmerald else JumuraMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(120.dp).fillMaxWidth()) {
                // Halo réactif au niveau sonore
                if (running) {
                    val ring = (0.42f + level * 0.55f)
                    Box(
                        Modifier
                            .size((132 * ring).dp)
                            .clip(CircleShape)
                            .background(JumuraEmerald.copy(alpha = 0.10f))
                    )
                    Box(
                        Modifier
                            .size((104 * ring).dp)
                            .clip(CircleShape)
                            .background(JumuraEmerald.copy(alpha = 0.16f))
                    )
                }
                // Bouton principal
                Box(
                    Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = if (running)
                                    listOf(JumuraRed, Color(0xFFB91C1C))
                                else
                                    listOf(JumuraEmerald, JumuraEmeraldDeep),
                                start = Offset(0f, 0f),
                                end = Offset(0f, 220f)
                            )
                        )
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (running) "Arrêter" else "Démarrer",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Effacer (à droite)
                if (canClear) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 34.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(JumuraSurfaceHigh)
                            .clickable { onClear() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Effacer", tint = JumuraMuted)
                    }
                }
            }
        }
    }
}

/* ---------------- Réglages ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    groqKey: String,
    onKey: (String) -> Unit,
    onSaveKey: () -> Unit,
    sourceLang: String,
    onSourceLang: (String) -> Unit,
    micGain: Float,
    onMicGain: (Float) -> Unit,
    showOriginal: Boolean,
    onShowOriginal: (Boolean) -> Unit,
    textSize: Float,
    onTextSize: (Float) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    sttModel: String,
    onSttModel: (String) -> Unit,
    trModel: String,
    onTrModel: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = JumuraSurface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 34.dp)
        ) {
            Text("Réglages", color = JumuraText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))

            // Clé Groq
            SectionLabel("Clé Groq (gratuite — console.groq.com)")
            OutlinedTextField(
                value = groqKey,
                onValueChange = onKey,
                singleLine = true,
                placeholder = { Text("gsk_…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSaveKey,
                colors = ButtonDefaults.buttonColors(containerColor = JumuraEmerald, contentColor = Color(0xFF04140D))
            ) { Text("Enregistrer la clé") }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Langue de l'imam")
            Row {
                LangChip("Auto", sourceLang == "", { onSourceLang("") })
                Spacer(Modifier.width(8.dp))
                LangChip("Arabe / Darija", sourceLang == "ar", { onSourceLang("ar") })
                Spacer(Modifier.width(8.dp))
                LangChip("Français", sourceLang == "fr", { onSourceLang("fr") })
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Sensibilité micro (imam éloigné) — boost jusqu'à ${(micGain * 10).toInt()}×")
            Slider(
                value = micGain,
                onValueChange = onMicGain,
                valueRange = 1f..5f,
                steps = 7,
                colors = sliderColors()
            )

            Spacer(Modifier.height(10.dp))
            SectionLabel("Taille du texte — ${textSize.toInt()}")
            Slider(
                value = textSize,
                onValueChange = onTextSize,
                valueRange = 16f..34f,
                steps = 8,
                colors = sliderColors()
            )

            Spacer(Modifier.height(14.dp))
            ToggleRow("Afficher le texte original (arabe/français)", showOriginal, onShowOriginal)
            ToggleRow("Garder l'écran allumé pendant l'écoute", keepScreenOn, onKeepScreenOn)

            Spacer(Modifier.height(22.dp))
            SectionLabel("Avancé — modèles Groq")
            OutlinedTextField(
                value = sttModel,
                onValueChange = onSttModel,
                singleLine = true,
                label = { Text("Modèle transcription") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = trModel,
                onValueChange = onTrModel,
                singleLine = true,
                label = { Text("Modèle traduction") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )

            Spacer(Modifier.height(22.dp))
            OutlinedButton(
                onClick = onCheckUpdate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = JumuraEmerald)
                Spacer(Modifier.width(8.dp))
                Text("Vérifier les mises à jour", color = JumuraText)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Jumura ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                color = JumuraMuted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = JumuraMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = JumuraText, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = JumuraEmerald,
                uncheckedTrackColor = JumuraSurfaceHigh
            )
        )
    }
}

@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) JumuraEmerald else JumuraSurfaceHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF04140D) else JumuraText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JumuraEmerald,
    unfocusedBorderColor = JumuraSurfaceHigh,
    focusedTextColor = JumuraText,
    unfocusedTextColor = JumuraText,
    cursorColor = JumuraEmerald,
    focusedLabelColor = JumuraEmerald,
    unfocusedLabelColor = JumuraMuted
)

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = JumuraEmerald,
    activeTrackColor = JumuraEmerald,
    inactiveTrackColor = JumuraSurfaceHigh
)

/* ---------------- Utilitaires ---------------- */

private fun isArabic(text: String, lang: String): Boolean {
    if (lang.lowercase().startsWith("ar")) return true
    // Bloc arabe U+0600–U+06FF et supplément arabe U+0750–U+077F
    return text.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }
}
