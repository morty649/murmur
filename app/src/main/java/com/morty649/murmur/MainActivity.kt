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
                            bubbleOn -> "On. Blue means ready, red means listening, amber means typing."
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
