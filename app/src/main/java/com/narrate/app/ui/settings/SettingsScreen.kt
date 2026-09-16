package com.narrate.app.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.container
import com.narrate.app.data.prefs.AppSettings
import com.narrate.app.ui.components.*
import com.narrate.app.ui.theme.NarrateColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ModelRole(val label: String, val description: String) {
    NARRATION("Narration", "Writes the prose, plays every character, and runs the world."),
    SIMULATION("Simulation", "Compacts history into chapters and handles background work. Falls back to the narration model."),
    IMAGE("Images", "Draws scenes, portraits and places. Reference images keep faces consistent.")
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.settings

    val settings: StateFlow<AppSettings> = store.settings

    private val _models = MutableStateFlow<Map<ProviderId, List<ModelInfo>>>(
        ProviderId.entries.associateWith { ProviderRegistry.get(it).catalog() }
    )
    val models: StateFlow<Map<ProviderId, List<ModelInfo>>> = _models.asStateFlow()

    private val _loadingModels = MutableStateFlow<ProviderId?>(null)
    val loadingModels: StateFlow<ProviderId?> = _loadingModels.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun apiKey(provider: ProviderId): String = store.apiKey(provider)

    fun setApiKey(provider: ProviderId, value: String) {
        store.setApiKey(provider, value)
        if (value.isNotBlank()) refreshModels(provider)
    }

    /** Ask the provider what it actually serves, so the list is never stale. */
    fun refreshModels(provider: ProviderId) {
        viewModelScope.launch {
            _loadingModels.value = provider
            val result = runCatching { ProviderRegistry.get(provider).listModels(store.apiKey(provider)) }
            _loadingModels.value = null
            result.onSuccess { list ->
                if (list.isNotEmpty()) {
                    _models.value = _models.value + (provider to list)
                    _message.value = "${provider.displayName}: ${list.size} models available."
                }
            }.onFailure {
                _message.value = it.message ?: "Could not list models."
            }
        }
    }

    fun select(role: ModelRole, provider: ProviderId, model: String) {
        when (role) {
            ModelRole.NARRATION -> store.setNarrationModel(provider, model)
            ModelRole.SIMULATION -> store.setSimulationModel(provider, model)
            ModelRole.IMAGE -> store.setImageModel(provider, model)
        }
    }

    fun setTemperature(value: Float) = store.setTemperature(value)
    fun setMaxTokens(value: Int) = store.setMaxTokens(value)
    fun setRecentTurnWindow(value: Int) = store.setRecentTurnWindow(value)
    fun setMemoryCount(value: Int) = store.setMemoryRetrievalCount(value)
    fun setUseReferenceImages(value: Boolean) = store.setUseReferenceImages(value)
    fun setAutoImages(value: Boolean) = store.setAutoGenerateSceneImages(value)
    fun setSimulateOffscreen(value: Boolean) = store.setSimulateOffscreenWorld(value)
    fun setStrictContinuity(value: Boolean) = store.setStrictContinuity(value)
    fun clearMessage() { _message.value = null }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val loading by viewModel.loadingModels.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = { NarrateTopBar("Settings", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            ErrorBanner(message) { viewModel.clearMessage() }

            Text("Your API keys", style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Keys are encrypted with a hardware-backed key on this device and are sent only to the " +
                    "provider they belong to. Everything generated in Narrate comes from the models you choose here.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            Spacer(Modifier.height(12.dp))
            ProviderId.entries.forEach { provider ->
                ApiKeyRow(
                    provider = provider,
                    initial = viewModel.apiKey(provider),
                    configured = provider in settings.configuredProviders,
                    loading = loading == provider,
                    onSave = { viewModel.setApiKey(provider, it) },
                    onRefresh = { viewModel.refreshModels(provider) }
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text("Which model does what", style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
            Spacer(Modifier.height(10.dp))

            ModelRole.entries.forEach { role ->
                val current = when (role) {
                    ModelRole.NARRATION -> settings.narration
                    ModelRole.SIMULATION -> settings.simulation
                    ModelRole.IMAGE -> settings.image
                }
                ModelPicker(
                    role = role,
                    models = models,
                    selectedProvider = current.provider,
                    selectedModel = current.model,
                    configured = settings.configuredProviders,
                    onSelect = { provider, model -> viewModel.select(role, provider, model) }
                )
                Spacer(Modifier.height(14.dp))
            }

            Spacer(Modifier.height(10.dp))
            Text("World behaviour", style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
            Spacer(Modifier.height(8.dp))

            SettingToggle(
                "Simulate the world offscreen",
                "NPCs follow their routines and threads advance while you are elsewhere.",
                settings.simulateOffscreenWorld,
                viewModel::setSimulateOffscreen
            )
            SettingToggle(
                "Strict continuity",
                "Feed detected contradictions back to the narrator so it corrects itself on the next turn.",
                settings.strictContinuity,
                viewModel::setStrictContinuity
            )
            SettingToggle(
                "Use reference images",
                "Send previously generated images back to the image model so faces and places stay the same.",
                settings.useReferenceImages,
                viewModel::setUseReferenceImages
            )
            SettingToggle(
                "Illustrate every turn",
                "Generate a scene image automatically after each turn. Uses far more image credits.",
                settings.autoGenerateSceneImages,
                viewModel::setAutoImages
            )

            Spacer(Modifier.height(16.dp))
            SliderRow(
                "Narration temperature",
                settings.temperature,
                0.1f..1.5f,
                "Higher is more surprising, lower is more consistent."
            ) { viewModel.setTemperature(it) }

            SliderRow(
                "Response length limit",
                settings.maxTokens.toFloat(),
                1024f..16000f,
                "Maximum tokens per turn: ${settings.maxTokens}."
            ) { viewModel.setMaxTokens(it.toInt()) }

            SliderRow(
                "Verbatim turns in context",
                settings.recentTurnWindow.toFloat(),
                4f..20f,
                "The last ${settings.recentTurnWindow} turns are replayed in full; older ones are compacted into chapters."
            ) { viewModel.setRecentTurnWindow(it.toInt()) }

            SliderRow(
                "Memories retrieved per turn",
                settings.memoryRetrievalCount.toFloat(),
                8f..60f,
                "${settings.memoryRetrievalCount} remembered facts are selected for each turn, plus everything pinned."
            ) { viewModel.setMemoryCount(it.toInt()) }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ApiKeyRow(
    provider: ProviderId,
    initial: String,
    configured: Boolean,
    loading: Boolean,
    onSave: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = NarrateColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (configured) {
                Icon(Icons.Default.CheckCircle, null, tint = NarrateColors.CallAccent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(provider.keyHint, color = NarrateColors.TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        "Toggle visibility",
                        tint = NarrateColors.TextMuted
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NarrateColors.Accent,
                unfocusedBorderColor = NarrateColors.Divider,
                focusedContainerColor = NarrateColors.SurfaceElevated,
                unfocusedContainerColor = NarrateColors.SurfaceElevated,
                focusedTextColor = NarrateColors.TextPrimary,
                unfocusedTextColor = NarrateColors.TextPrimary,
                cursorColor = NarrateColors.Accent
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save key", Modifier.weight(1f)) { onSave(value) }
            SecondaryButton(if (loading) "Loading..." else "List models", Modifier.weight(1f), enabled = !loading) {
                onRefresh()
            }
        }
    }
}

@Composable
private fun ModelPicker(
    role: ModelRole,
    models: Map<ProviderId, List<ModelInfo>>,
    selectedProvider: ProviderId,
    selectedModel: String,
    configured: Set<ProviderId>,
    onSelect: (ProviderId, String) -> Unit
) {
    var expandedProvider by remember(role) { mutableStateOf(selectedProvider) }
    var customModel by remember(role) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, NarrateColors.Divider, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(role.label, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
        Text(role.description, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        Text(
            if (selectedModel.isBlank()) "Nothing selected" else "${selectedProvider.displayName} - $selectedModel",
            style = MaterialTheme.typography.bodyMedium,
            color = if (selectedModel.isBlank()) NarrateColors.TextMuted else NarrateColors.Accent
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ProviderId.entries.toList()) { provider ->
                Pill(
                    text = provider.displayName + if (provider in configured) "" else " (no key)",
                    selected = expandedProvider == provider
                ) { expandedProvider = provider }
            }
        }
        Spacer(Modifier.height(10.dp))
        val candidates = (models[expandedProvider].orEmpty()).filter {
            if (role == ModelRole.IMAGE) it.supportsImageGeneration else it.supportsText
        }
        if (candidates.isEmpty()) {
            Text(
                if (role == ModelRole.IMAGE) "${expandedProvider.displayName} has no image models listed. Add a key and tap List models."
                else "No models listed. Add a key and tap List models.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
        }
        candidates.take(40).forEach { model ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(expandedProvider, model.id) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedProvider == expandedProvider && selectedModel == model.id,
                    onClick = { onSelect(expandedProvider, model.id) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = NarrateColors.Accent,
                        unselectedColor = NarrateColors.TextMuted
                    )
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        model.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NarrateColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val notes = listOfNotNull(
                        model.note.takeIf { it.isNotBlank() },
                        if (model.supportsImageReferences) "reference images" else null
                    )
                    if (notes.isNotEmpty()) {
                        Text(
                            notes.joinToString(" - "),
                            style = MaterialTheme.typography.labelSmall,
                            color = NarrateColors.TextMuted
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                NarrateField(customModel, { customModel = it }, "Or type a model id")
            }
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Use", enabled = customModel.isNotBlank()) {
                onSelect(expandedProvider, customModel.trim())
                customModel = ""
            }
        }
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NarrateColors.Accent,
                checkedTrackColor = NarrateColors.Accent.copy(alpha = 0.35f),
                uncheckedThumbColor = NarrateColors.TextMuted,
                uncheckedTrackColor = NarrateColors.SurfaceHigh
            )
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    subtitle: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = NarrateColors.Accent,
                activeTrackColor = NarrateColors.Accent,
                inactiveTrackColor = NarrateColors.SurfaceHigh
            )
        )
    }
}
