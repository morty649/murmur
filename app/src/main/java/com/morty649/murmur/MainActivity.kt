package com.morty649.murmur

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import kotlin.math.roundToInt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MurmurTheme {
                Surface(Modifier.fillMaxSize()) { MainScreen(resumeTick) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

@Composable
private fun MurmurTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun Context.granted(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun MainScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ModelManager(ctx) }

    var refresh by remember { mutableIntStateOf(0) }
    val tick = resumeTick + refresh // re-read permissions whenever we come back to the app

    var states by remember { mutableStateOf(ModelCatalog.all.associate { it.id to manager.poll(it) }) }
    var selectedId by remember { mutableStateOf(Prefs.selectedModel(ctx).id) }
    var cleanup by remember { mutableStateOf(Prefs.cleanup(ctx)) }
    var bubbleOn by remember { mutableStateOf(BubbleService.running) }
    var bubbleStyle by remember { mutableStateOf(Prefs.bubbleStyle(ctx)) }
    var transparency by remember { mutableIntStateOf(Prefs.transparency(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = ModelCatalog.all.associate { it.id to manager.poll(it) }
            states = now
            now.forEach { (id, s) ->
                if (s is ModelState.Downloaded) scope.launch { manager.verify(ModelCatalog.byId(id)) }
            }
            if (now[selectedId] != ModelState.Ready) {
                now.entries.firstOrNull { it.value == ModelState.Ready }?.let {
                    selectedId = it.key
                    Prefs.setSelectedModel(ctx, it.key)
                }
            }
            bubbleOn = BubbleService.running
            delay(700)
        }
    }

    val micOk = remember(tick) { ctx.granted(Manifest.permission.RECORD_AUDIO) }
    val notifOk = remember(tick) { Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS) }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val typingOk = remember(tick) { TypeService.isEnabled(ctx) }
    val modelOk = states[selectedId] == ModelState.Ready

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    var update by remember { mutableStateOf<Release?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    fun checkForUpdate(manual: Boolean) {
        scope.launch {
            if (manual) updateStatus = "Checking…"
            val r = runCatching { Updater.check(ctx) }
            updateStatus = when {
                r.isFailure -> if (manual) "Couldn't reach GitHub. Try again later." else null
                r.getOrNull() == null -> if (manual) "You're on the latest version." else null
                else -> "Version ${r.getOrNull()!!.versionName} is available."
            }
            r.getOrNull()?.let { if (manual || !Prefs.updateSnoozed(ctx, it.versionCode)) update = it }
        }
    }
    LaunchedEffect(Unit) { checkForUpdate(false) }
    update?.let { UpdateDialog(it, tick) { update = null } }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Murmur", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Tap the floating bubble, talk, tap again. Your words get typed into whatever box you're in, " +
                "and everything runs on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------------ 1. model
        Section("1 · Speech model") {
            if (states.values.none { it == ModelState.Ready }) {
                Text(
                    "Download a model once to get started. Wi-Fi recommended (~660 MB).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ModelCatalog.all.forEach { model ->
                ModelRow(
                    model = model,
                    state = states[model.id] ?: ModelState.NotDownloaded,
                    selected = model.id == selectedId,
                    onSelect = {
                        selectedId = model.id
                        Prefs.setSelectedModel(ctx, model.id)
                    },
                    onDownload = { manager.download(model); states = states + (model.id to manager.poll(model)) },
                    onCancel = { manager.cancel(model); states = states + (model.id to manager.poll(model)) },
                    onRetry = { manager.clearFailure(model); manager.download(model) },
                    onDelete = {
                        if (model.id == selectedId && BubbleService.running) BubbleService.stop(ctx)
                        manager.delete(model)
                        states = states + (model.id to manager.poll(model))
                    },
                )
            }
        }

        // ------------------------------------------------------------ 2. permissions
        Section("2 · Permissions") {
            PermissionRow("Microphone", "To hear you while the bubble is red", micOk) {
                askPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow("Notifications", "Shows that the bubble is running", notifOk) {
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            PermissionRow("Display over other apps", "To show the floating bubble", overlayOk) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                )
            }
            PermissionRow("Accessibility", "To type text into the box you're using", typingOk) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (!typingOk) {
                Text(
                    "Is Murmur greyed out in Accessibility? Android blocks it for apps installed from a browser. " +
                        "Open App info, tap ⋮ (top right), choose \"Allow restricted settings\", then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
                    )
                }) { Text("Open App info") }
            }
        }

        // ------------------------------------------------------------ 3. cleanup
        Section("3 · Cleanup") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = cleanup == CleanupMode.OFF,
                    onClick = { cleanup = CleanupMode.OFF; Prefs.setCleanup(ctx, CleanupMode.OFF) },
                    label = { Text("Off") },
                )
                FilterChip(
                    selected = cleanup == CleanupMode.LIGHT,
                    onClick = { cleanup = CleanupMode.LIGHT; Prefs.setCleanup(ctx, CleanupMode.LIGHT) },
                    label = { Text("Light") },
                )
            }
            Text(
                if (cleanup == CleanupMode.LIGHT) "Removes um/uh and tidies spacing and capitals. Instant."
                else "Exactly what the model heard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------------ 4. bubble
        Section("4 · Bubble") {
            val canStart = micOk && overlayOk && modelOk
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Floating bubble", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            bubbleOn -> "On. Red bars mean listening, amber bars mean it's typing."
                            !modelOk -> "Download and pick a model first"
                            !micOk || !overlayOk -> "Grant the permissions above first"
                            else -> "Off"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = bubbleOn,
                    enabled = bubbleOn || canStart,
                    onCheckedChange = { on ->
                        if (on) BubbleService.start(ctx) else BubbleService.stop(ctx)
                        bubbleOn = on
                    },
                )
            }
            Text("Style", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BubbleStyle.entries.forEach { st ->
                    StyleOption(st, selected = bubbleStyle == st, modifier = Modifier.weight(1f)) {
                        bubbleStyle = st
                        Prefs.setBubbleStyle(ctx, st)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Transparency", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("$transparency%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = transparency.toFloat(),
                onValueChange = { transparency = (it / 5).roundToInt() * 5 },
                onValueChangeFinished = { Prefs.setTransparency(ctx, transparency) },
                valueRange = 0f..Prefs.MAX_TRANSPARENCY.toFloat(),
                steps = Prefs.MAX_TRANSPARENCY / 5 - 1,
            )
            Text(
                "How see-through the bubble is while it waits. It turns solid while listening, " +
                    "so you always know when it's recording. Drag it to either edge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------------ 5. updates
        Section("5 · Updates") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Version ${remember { Updater.currentVersionName(ctx) }}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        updateStatus ?: "Checks GitHub each time you open Murmur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { checkForUpdate(true) }) { Text("Check now") }
            }
        }

        // ------------------------------------------------------------ try it
        Section("Try it") {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Tap here, then tap the bubble and talk") },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PermissionRow(title: String, why: String, ok: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok) {
            Text("✓ On", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        } else {
            OutlinedButton(onClick = onGrant) { Text("Allow") }
        }
    }
}

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

@Composable
private fun ModelRow(
    model: AsrModel,
    state: ModelState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, enabled = state == ModelState.Ready)
            Column(Modifier.weight(1f)) {
                Text(model.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "${model.description} · ${mb(model.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                ModelState.NotDownloaded -> Button(onClick = onDownload) { Text("Get") }
                ModelState.Ready -> TextButton(onClick = onDelete) { Text("Delete") }
                is ModelState.Failed -> Button(onClick = onRetry) { Text("Retry") }
                is ModelState.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
                ModelState.Downloaded, ModelState.Verifying ->
                    CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
            }
        }
        when (state) {
            is ModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp),
                )
                Text(
                    "${(state.fraction * 100).toInt()}% · ${mb(state.doneBytes)} of ${mb(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 48.dp, top = 2.dp),
                )
            }
            ModelState.Downloaded, ModelState.Verifying -> Text(
                "Checking the files…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 48.dp),
            )
            is ModelState.Failed -> Text(
                state.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 48.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun StyleOption(style: BubbleStyle, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            when (style) {
                BubbleStyle.GLASS -> Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(0xC016171E)),
                    contentAlignment = Alignment.Center,
                ) { Image(painterResource(R.drawable.bubble_mark), null, Modifier.size(width = 24.dp, height = 13.dp)) }
                BubbleStyle.EDGE -> Box(
                    Modifier
                        .padding(start = 12.dp)
                        .size(width = 28.dp, height = 40.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .background(Color(0xC016171E)),
                    contentAlignment = Alignment.Center,
                ) { Image(painterResource(R.drawable.bubble_mark), null, Modifier.size(width = 18.dp, height = 10.dp)) }
                BubbleStyle.ICON -> Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
            }
        }
        Text(style.label, style = MaterialTheme.typography.bodySmall)
    }
}

private sealed interface UpdatePhase {
    data object Ask : UpdatePhase
    data class Downloading(val progress: Float) : UpdatePhase
    data object NeedsPermission : UpdatePhase
    data object Installing : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

/** "Version X is available": Update now (download, verify, install) or Remind me later (24 h). */
@Composable
private fun UpdateDialog(release: Release, resumeTick: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Ask) }
    var apk by remember { mutableStateOf<java.io.File?>(null) }

    fun installNow(file: java.io.File) {
        if (!Updater.canInstall(ctx)) {
            phase = UpdatePhase.NeedsPermission
            return
        }
        phase = UpdatePhase.Installing
        scope.launch {
            runCatching { Updater.install(ctx, file) }.onFailure { phase = UpdatePhase.Failed(it.message ?: "Install failed") }
        }
    }

    // Back from Android's "Install unknown apps" screen: carry on if it's now allowed.
    LaunchedEffect(resumeTick) {
        val file = apk
        if (phase == UpdatePhase.NeedsPermission && file != null && Updater.canInstall(ctx)) installNow(file)
    }

    val busy = phase is UpdatePhase.Downloading || phase == UpdatePhase.Installing
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val p = phase) {
                    UpdatePhase.Ask -> Text(
                        "Murmur ${release.versionName} is ready (you have ${Updater.currentVersionName(ctx)}). " +
                            "It installs over this version and keeps your downloaded model. " +
                            "${release.size / 1_000_000} MB download.",
                    )
                    is UpdatePhase.Downloading -> {
                        Text("Downloading ${(p.progress * 100).toInt()}%…")
                        LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    UpdatePhase.NeedsPermission -> Text(
                        "Android needs your OK once: allow Murmur to install apps, then come back here.",
                    )
                    UpdatePhase.Installing -> Text(
                        "Installing… Android may ask you to confirm. Murmur closes when it's done; open it again " +
                            "to turn the bubble back on.",
                    )
                    is UpdatePhase.Failed -> Text(p.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (phase) {
                UpdatePhase.Ask, is UpdatePhase.Failed -> Button(onClick = {
                    phase = UpdatePhase.Downloading(0f)
                    scope.launch {
                        runCatching { Updater.download(ctx, release) { f -> phase = UpdatePhase.Downloading(f) } }
                            .onSuccess { apk = it; installNow(it) }
                            .onFailure { phase = UpdatePhase.Failed(it.message ?: "Download failed") }
                    }
                }) { Text(if (phase == UpdatePhase.Ask) "Update now" else "Try again") }
                UpdatePhase.NeedsPermission -> Button(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
                    )
                }) { Text("Allow") }
                else -> Unit
            }
        },
        dismissButton = {
            if (!busy) {
                TextButton(onClick = {
                    Prefs.snoozeUpdate(ctx, release.versionCode, 24 * 60 * 60 * 1000L)
                    onClose()
                }) { Text("Remind me later") }
            }
        },
    )
}
