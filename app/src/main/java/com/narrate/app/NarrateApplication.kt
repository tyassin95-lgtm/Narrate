package com.narrate.app

import android.app.Application
import android.content.Context
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.ImageDirector
import com.narrate.app.engine.TurnDirector
import com.narrate.app.engine.WorldForge

/** Hand-rolled container: the app has one graph and it is small enough to read. */
class AppContainer(context: Context) {
    val repository = WorldRepository(context)
    val settings = SettingsStore(context)
    val turnDirector = TurnDirector(repository, settings)
    val imageDirector = ImageDirector(repository, settings)
    val worldForge = WorldForge(repository, settings)
}

class NarrateApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as NarrateApplication).container
