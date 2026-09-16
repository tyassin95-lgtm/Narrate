package com.narrate.app.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.narrate.app.ui.album.AlbumScreen
import com.narrate.app.ui.album.ImageDetailScreen
import com.narrate.app.ui.codex.CharacterDetailScreen
import com.narrate.app.ui.codex.CodexScreen
import com.narrate.app.ui.codex.CodexViewModel
import com.narrate.app.ui.codex.LocationDetailScreen
import com.narrate.app.ui.create.CreateScreen
import com.narrate.app.ui.create.CreateViewModel
import com.narrate.app.ui.home.HomeScreen
import com.narrate.app.ui.home.HomeViewModel
import com.narrate.app.ui.play.PlayScreen
import com.narrate.app.ui.play.PlayViewModel
import com.narrate.app.ui.settings.SettingsScreen
import com.narrate.app.ui.settings.SettingsViewModel

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CREATE = "create"
    const val PLAY = "play/{worldId}"
    const val CODEX = "codex/{worldId}"
    const val ALBUM = "album/{worldId}"
    const val CHARACTER = "character/{worldId}/{characterId}"
    const val LOCATION = "location/{worldId}/{locationId}"
    const val IMAGE = "image/{worldId}/{imageId}"

    fun play(worldId: String) = "play/$worldId"
    fun codex(worldId: String) = "codex/$worldId"
    fun album(worldId: String) = "album/$worldId"
    fun character(worldId: String, characterId: String) = "character/$worldId/$characterId"
    fun location(worldId: String, locationId: String) = "location/$worldId/$locationId"
    fun image(worldId: String, imageId: String) = "image/$worldId/$imageId"
}

/**
 * Every view model in the app is constructed explicitly, with the application it needs and
 * (where relevant) the world it belongs to. Nothing relies on implicit factory resolution.
 */
private class NarrateViewModelFactory(
    private val application: Application,
    private val worldId: String = ""
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(PlayViewModel::class.java) -> PlayViewModel(application, worldId) as T
        modelClass.isAssignableFrom(CodexViewModel::class.java) -> CodexViewModel(application, worldId) as T
        modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(application) as T
        modelClass.isAssignableFrom(CreateViewModel::class.java) -> CreateViewModel(application) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(application) as T
        else -> throw IllegalArgumentException("Unknown view model ${modelClass.name}")
    }
}

@Composable
fun NarrateApp(application: Application) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val viewModel: HomeViewModel = viewModel(factory = NarrateViewModelFactory(application))
            HomeScreen(
                viewModel = viewModel,
                onPlay = { navController.navigate(Routes.play(it)) },
                onCreateWorld = { navController.navigate(Routes.CREATE) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onAlbum = { navController.navigate(Routes.album(it)) },
                onCodex = { navController.navigate(Routes.codex(it)) },
                onImage = { imageId ->
                    val worldId = viewModel.state.value.images.firstOrNull { it.id == imageId }?.worldId
                    if (worldId != null) navController.navigate(Routes.image(worldId, imageId))
                }
            )
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = NarrateViewModelFactory(application))
            SettingsScreen(viewModel) { navController.popBackStack() }
        }

        composable(Routes.CREATE) {
            val viewModel: CreateViewModel = viewModel(factory = NarrateViewModelFactory(application))
            CreateScreen(
                viewModel = viewModel,
                onFinished = { worldId ->
                    navController.navigate(Routes.play(worldId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(
            Routes.PLAY,
            arguments = listOf(navArgument("worldId") { type = NavType.StringType })
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId").orEmpty()
            val viewModel: PlayViewModel = viewModel(factory = NarrateViewModelFactory(application, worldId))
            PlayScreen(
                viewModel = viewModel,
                onOpenCodex = { navController.navigate(Routes.codex(worldId)) },
                onOpenAlbum = { navController.navigate(Routes.album(worldId)) },
                onOpenCharacter = { navController.navigate(Routes.character(worldId, it)) },
                onOpenImage = { navController.navigate(Routes.image(worldId, it)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.CODEX,
            arguments = listOf(navArgument("worldId") { type = NavType.StringType })
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId").orEmpty()
            val viewModel: CodexViewModel = viewModel(factory = NarrateViewModelFactory(application, worldId))
            CodexScreen(
                viewModel = viewModel,
                onCharacter = { navController.navigate(Routes.character(worldId, it)) },
                onLocation = { navController.navigate(Routes.location(worldId, it)) },
                onAlbum = { navController.navigate(Routes.album(worldId)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.ALBUM,
            arguments = listOf(navArgument("worldId") { type = NavType.StringType })
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId").orEmpty()
            val viewModel: CodexViewModel = viewModel(factory = NarrateViewModelFactory(application, worldId))
            AlbumScreen(
                viewModel = viewModel,
                onImage = { navController.navigate(Routes.image(worldId, it)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.CHARACTER,
            arguments = listOf(
                navArgument("worldId") { type = NavType.StringType },
                navArgument("characterId") { type = NavType.StringType }
            )
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId").orEmpty()
            val characterId = entry.arguments?.getString("characterId").orEmpty()
            val viewModel: CodexViewModel = viewModel(factory = NarrateViewModelFactory(application, worldId))
            CharacterDetailScreen(
                viewModel = viewModel,
                characterId = characterId,
                onImage = { navController.navigate(Routes.image(worldId, it)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.LOCATION,
            arguments = listOf(
                navArgument("worldId") { type = NavType.StringType },
                navArgument("locationId") { type = NavType.StringType }
            )
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId").orEmpty()
            val locationId = entry.arguments?.getString("locationId").orEmpty()
            val viewModel: CodexViewModel = viewModel(factory = NarrateViewModelFactory(application, worldId))
            LocationDetailScreen(
                viewModel = viewModel,
                locationId = locationId,
                onImage = { navController.navigate(Routes.image(worldId, it)) },
                onCharacter = { navController.navigate(Routes.character(worldId, it)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.IMAGE,
            arguments = listOf(
                navArgument("worldId") { type = NavType.StringType },
                navArgument("imageId") { type = NavType.StringType }
            )
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId").orEmpty()
            val imageId = entry.arguments?.getString("imageId").orEmpty()
            val viewModel: CodexViewModel = viewModel(factory = NarrateViewModelFactory(application, worldId))
            ImageDetailScreen(viewModel = viewModel, imageId = imageId) { navController.popBackStack() }
        }
    }
}
