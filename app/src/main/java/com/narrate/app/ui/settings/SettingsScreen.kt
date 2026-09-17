package com.narrate.app.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
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
import com.narrate.app.ai.ModelPricing
import com.narrate.app.data.entity.UsageEntity
import com.narrate.app.data.prefs.AppSettings
import com.narrate.app.engine.UsageGroup
import com.narrate.app.engine.UsageRecorder
import com.narrate.app.engine.UsageSummary
import com.narrate.app.ui.components.*
import com.narrate.app.ui.theme.NarrateColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ModelRole(val label: String, val description: String) {
    NARRATION("Narration", "Writes the prose, plays every character, and runs the world."),
    SIMULATION("Simulation", "Compacts history into chapters and handles background work. Falls back to the narration model."),
    IMAGE("Images", "Draws scenes, portraits and places. Reference images keep faces consistent.")
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = application.container.settings
    private val repository = application.container.repository
    private val modelCache = application.container.modelCache

    val settings: StateFlow<AppSettings> = store.settings

    /** Discovered model lists, cached in the container so they outlive this view model. */
    val models: StateFlow<Map<ProviderId, List<ModelInfo>>> = modelCache.models

    private val _loadingModels = MutableStateFlow<ProviderId?>(null)
    val loadingModels: StateFlow<ProviderId?> = _loadingModels.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Every recorded call, newest first, across every world. */
    val usage: StateFlow<List<UsageEntity>> = repository.observeAllUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Deliberately no init block. A coroutine started while this object is still being
    // constructed runs before the properties below it exist, and anything it throws has no
    // handler to catch it - which is how opening Settings after a restart used to kill the
    // app. Start-up work belongs in refreshConfiguredProviders, called once the screen is
    // composed and the view model is fully built.

    fun apiKey(provider: ProviderId): String = store.apiKey(provider)

    fun setApiKey(provider: ProviderId, value: String) {
        store.setApiKey(provider, value)
        if (value.isNotBlank()) refreshModels(provider, announce = true)
    }

    /**
     * Called when the screen appears. Asks any provider that has a key, and has not already
     * answered this session, what it serves - so the picker opens on a current list.
     */
    fun refreshConfiguredProviders() {
        ProviderId.entries
            .filter { store.hasApiKey(it) && !modelCache.hasDiscovered(it) }
            .forEach { refreshModels(it) }
    }

    /** Ask the provider what it actually serves, so the list is never stale. */
    fun refreshModels(provider: ProviderId, announce: Boolean = false) {
        viewModelScope.launch {
            _loadingModels.value = provider
            val result = runCatching { ProviderRegistry.get(provider).listModels(store.apiKey(provider)) }
            _loadingModels.value = null
            result.onSuccess { list ->
                modelCache.put(provider, list)
                if (announce && list.isNotEmpty()) {
                    val text = list.count { it.supportsText }
                    val image = list.count { it.supportsImageGeneration }
                    _message.value = "${provider.displayName}: $text narration models, $image image models."
                }
            }.onFailure {
                if (announce) _message.value = it.message ?: "Could not list models."
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
    val usage by viewModel.usage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshConfiguredProviders() }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = { NarrateTopBar("Settings", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
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
                    onRefresh = { viewModel.refreshModels(provider, announce = true) }
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
                    loadingProvider = loading,
                    onSelect = { provider, model -> viewModel.select(role, provider, model) },
                    onRefresh = { viewModel.refreshModels(it, announce = true) }
                )
                Spacer(Modifier.height(14.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Prices are indicative list prices recorded on ${ModelPricing.AS_OF} and are not fetched " +
                    "live. Check your provider for current rates.",
                style = MaterialTheme.typography.labelSmall,
                color = NarrateColors.TextMuted
            )

            Spacer(Modifier.height(22.dp))
            UsagePanel(usage)

            Spacer(Modifier.height(22.dp))
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
                2048f..32000f,
                "Up to ${settings.maxTokens} tokens per turn. Narrate raises this on its own if a " +
                    "turn needs more room, so a long scene never loses its choices."
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
    loadingProvider: ProviderId?,
    onSelect: (ProviderId, String) -> Unit,
    onRefresh: (ProviderId) -> Unit
) {
    var openProvider by remember(role) { mutableStateOf(selectedProvider) }
    var query by remember(role) { mutableStateOf("") }
    var showAll by remember(role) { mutableStateOf(false) }
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
        ModelPricing.summary(selectedProvider, selectedModel)?.let { price ->
            Text(price, style = MaterialTheme.typography.labelSmall, color = NarrateColors.TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ProviderId.entries.toList()) { provider ->
                Pill(
                    text = provider.displayName + if (provider in configured) "" else " (no key)",
                    selected = openProvider == provider
                ) {
                    openProvider = provider
                    query = ""
                    showAll = false
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        val available = (models[openProvider].orEmpty()).filter {
            if (role == ModelRole.IMAGE) it.supportsImageGeneration else it.supportsText
        }
        val matching = available.filter {
            query.isBlank() || it.id.contains(query, true) || it.label.contains(query, true)
        }

        if (available.size > COLLAPSED_MODELS) {
            NarrateField(query, { query = it }, "Search ${openProvider.displayName} models")
            Spacer(Modifier.height(8.dp))
        }

        when {
            loadingProvider == openProvider -> Text(
                "Asking ${openProvider.displayName} what it serves...",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            available.isEmpty() -> Column {
                Text(
                    if (openProvider !in configured) {
                        "Add a ${openProvider.displayName} key above to see the models your account can use."
                    } else if (role == ModelRole.IMAGE) {
                        "${openProvider.displayName} listed no image models for this key."
                    } else {
                        "No models listed yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NarrateColors.TextMuted
                )
                if (openProvider in configured) {
                    Spacer(Modifier.height(6.dp))
                    SecondaryButton("Refresh model list") { onRefresh(openProvider) }
                }
            }
            matching.isEmpty() -> Text(
                "Nothing matches \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            else -> {
                val visible = if (showAll || query.isNotBlank()) matching else matching.take(COLLAPSED_MODELS)
                visible.forEach { model ->
                    ModelRow(
                        model = model,
                        selected = selectedProvider == openProvider && selectedModel == model.id,
                        onSelect = { onSelect(openProvider, model.id) }
                    )
                }
                if (!showAll && query.isBlank() && matching.size > COLLAPSED_MODELS) {
                    TextButton(onClick = { showAll = true }) {
                        Text(
                            "Show all ${matching.size} models",
                            color = NarrateColors.Accent,
                            style = MaterialTheme.typography.labelSmall
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
                onSelect(openProvider, customModel.trim())
                customModel = ""
            }
        }
    }
}

private const val COLLAPSED_MODELS = 8

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onSelect: () -> Unit) {
    val price = ModelPricing.summary(model.provider, model.id)
    val band = ModelPricing.band(model.provider, model.id)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
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
            if (model.label != model.id) {
                Text(
                    model.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = NarrateColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                price ?: "no published price on record",
                style = MaterialTheme.typography.labelSmall,
                color = if (price == null) NarrateColors.TextMuted else NarrateColors.TextSecondary,
                maxLines = 2
            )
            if (model.note.isNotBlank()) {
                Text(model.note, style = MaterialTheme.typography.labelSmall, color = NarrateColors.TextMuted)
            }
        }
        band?.let {
            Spacer(Modifier.width(8.dp))
            CostBadge(it)
        }
    }
}

@Composable
private fun CostBadge(band: String) {
    val color = when (band) {
        "CHEAPEST" -> NarrateColors.CallAccent
        "MID" -> NarrateColors.Gold
        "PREMIUM" -> NarrateColors.AccentSoft
        else -> NarrateColors.Accent
    }
    Text(
        band.lowercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** What the player has spent, overall and per model. */
@Composable
private fun UsagePanel(events: List<UsageEntity>) {
    val total = remember(events) { UsageRecorder.summarise(events) }
    val byModel = remember(events) { UsageRecorder.byModel(events) }
    val byPurpose = remember(events) { UsageRecorder.byPurpose(events) }

    Column(Modifier.fillMaxWidth()) {
        Text("Usage and cost", style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "Everything Narrate has asked a model to do, across every world. Token counts come from " +
                "the providers; cost is an estimate against list prices as of ${ModelPricing.AS_OF}.",
            style = MaterialTheme.typography.bodySmall,
            color = NarrateColors.TextMuted
        )
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            Text(
                "Nothing used yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = NarrateColors.TextMuted
            )
            return@Column
        }
        UsageTotals(total)
        Spacer(Modifier.height(14.dp))
        UsageBreakdown("By model", byModel)
        Spacer(Modifier.height(10.dp))
        UsageBreakdown("By activity", byPurpose)
    }
}

@Composable
fun UsageTotals(total: UsageSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatChip("Estimated", ModelPricing.money(total.cost))
        StatChip("Calls", total.calls.toString())
        StatChip("Tokens", compactNumber(total.totalTokens))
        if (total.images > 0) StatChip("Images", total.images.toString())
    }
    if (total.hasUnpricedCalls) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Some calls used models with no price on record, so the real total is higher.",
            style = MaterialTheme.typography.labelSmall,
            color = NarrateColors.Gold
        )
    }
}

@Composable
fun UsageBreakdown(title: String, groups: List<UsageGroup>) {
    if (groups.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
    Spacer(Modifier.height(6.dp))
    groups.take(8).forEach { group ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NarrateColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        group.detail.takeIf { it.isNotBlank() },
                        "${group.summary.calls} calls",
                        if (group.summary.totalTokens > 0) "${compactNumber(group.summary.totalTokens)} tokens" else null,
                        if (group.summary.images > 0) "${group.summary.images} images" else null
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.labelSmall,
                    color = NarrateColors.TextMuted
                )
            }
            Text(
                if (group.summary.hasUnpricedCalls && group.summary.cost == 0.0) "unpriced"
                else ModelPricing.money(group.summary.cost),
                style = MaterialTheme.typography.bodyMedium,
                color = NarrateColors.TextSecondary
            )
        }
    }
}

fun compactNumber(value: Long): String = when {
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1fk", value / 1_000.0)
    else -> value.toString()
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
