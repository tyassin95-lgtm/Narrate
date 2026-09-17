package com.narrate.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.ProviderId
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.ui.settings.ModelRole
import com.narrate.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings has to survive the app being closed and reopened with a key already saved.
 *
 * That is the state the crash needed: on a fresh install nothing is stored, so opening
 * Settings is harmless, and it is only the second launch - with configuration on disk -
 * that takes the path that used to fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRestartTest {

    private lateinit var application: Application
    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // A coroutine launched from a view model has no exception handler, so anything it
        // throws lands here. On a device that is what terminates the app, and a JVM test
        // would otherwise swallow it and report a false pass.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        uncaught.clear()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught += throwable }
        // viewModelScope runs on Dispatchers.Main.immediate, which starts a coroutine body
        // synchronously when it is already on the main thread. An unconfined main dispatcher
        // reproduces that; a queueing one would hide construction-order bugs entirely.
        Dispatchers.setMain(Dispatchers.Unconfined)
        // Whatever a previous session left behind.
        application.getSharedPreferences("narrate_settings", 0).edit().clear().commit()
        application.getSharedPreferences("narrate_secure", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun assertNothingCrashed() {
        val failure = uncaught.firstOrNull()
        if (failure != null) {
            throw AssertionError("Opening settings crashed the app: $failure", failure)
        }
    }

    /**
     * The app has exactly one settings store, created when the process starts. Reading it
     * back through a fresh instance is what a real restart does.
     */
    private val store: SettingsStore get() = application.container.settings

    /** Stand-in for the next launch: a new store, reading what is on disk. */
    private fun reload(): SettingsStore = SettingsStore(application)

    @Test
    fun `opening settings on a fresh install works`() {
        val viewModel = SettingsViewModel(application)
        assertTrue(viewModel.models.value.isNotEmpty())
        assertNothingCrashed()
    }

    @Test
    fun `opening settings after a restart with a saved key does not crash`() {
        // First session: the player enters a key and picks a model.
        store.setApiKey(ProviderId.OPENAI, "sk-test-key")
        store.setNarrationModel(ProviderId.OPENAI, "gpt-5.4")

        // Next launch: the configuration is read back off disk before Settings is opened.
        assertTrue("the key must still be on disk", reload().hasApiKey(ProviderId.OPENAI))

        // Opening Settings with configuration already present is what used to crash.
        val viewModel = SettingsViewModel(application)
        assertEquals("gpt-5.4", viewModel.settings.value.narration.model)
        assertTrue(viewModel.models.value.isNotEmpty())
        assertEquals(null, viewModel.loadingModels.value)
        assertNothingCrashed()
    }

    @Test
    fun `settings survives a restart with every provider configured`() {
        ProviderId.entries.forEach { store.setApiKey(it, "key-for-${it.name}") }
        store.setImageModel(ProviderId.GOOGLE, "gemini-3.1-flash-image")
        store.setSimulationModel(ProviderId.ANTHROPIC, "claude-haiku-4-5")
        store.setTemperature(1.1f)
        store.setMaxTokens(12_000)

        val viewModel = SettingsViewModel(application)
        val settings = viewModel.settings.value
        assertEquals(ProviderId.entries.toSet(), settings.configuredProviders)
        assertEquals("gemini-3.1-flash-image", settings.image.model)
        assertEquals("claude-haiku-4-5", settings.simulation.model)
        assertEquals(1.1f, settings.temperature, 0.001f)
        assertEquals(12_000, settings.maxTokens)
        assertNothingCrashed()
    }

    @Test
    fun `reopening settings repeatedly is safe`() {
        store.setApiKey(ProviderId.OPENAI, "sk-test-key")
        repeat(3) {
            val viewModel = SettingsViewModel(application)
            assertTrue(viewModel.models.value.isNotEmpty())
            assertNothingCrashed()
        }
    }

    @Test
    fun `configuration survives the restart intact and is not reset by the fix`() = runBlocking {
        store.setApiKey(ProviderId.ANTHROPIC, "sk-ant-secret")
        store.setNarrationModel(ProviderId.ANTHROPIC, "claude-opus-5")
        store.setUseReferenceImages(false)
        store.setStrictContinuity(false)
        store.setRecentTurnWindow(14)

        val reopened = reload()
        assertEquals("sk-ant-secret", reopened.apiKey(ProviderId.ANTHROPIC))
        assertEquals("claude-opus-5", reopened.current.narration.model)
        assertEquals(ProviderId.ANTHROPIC, reopened.current.narration.provider)
        assertEquals(false, reopened.current.useReferenceImages)
        assertEquals(false, reopened.current.strictContinuity)
        assertEquals(14, reopened.current.recentTurnWindow)

        // And the view model reports the same thing the store does.
        val viewModel = SettingsViewModel(application)
        assertEquals("claude-opus-5", viewModel.settings.value.narration.model)
        assertEquals("sk-ant-secret", viewModel.apiKey(ProviderId.ANTHROPIC))
        assertNothingCrashed()
    }

    @Test
    fun `selecting a model after a restart still writes through`() {
        store.setApiKey(ProviderId.XAI, "xai-key")
        val viewModel = SettingsViewModel(application)
        viewModel.select(ModelRole.NARRATION, ProviderId.XAI, "grok-4.6")
        assertEquals("grok-4.6", reload().current.narration.model)
        assertNothingCrashed()
    }
}
