package com.animo.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.HindiCatalog
import com.animo.app.domain.repo.StreamRepository
import com.animo.app.ui.AnimoNavHost
import com.animo.app.ui.AppContainer
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.player.PipState
import com.animo.app.ui.theme.AnimoTheme

/**
 * Single activity host.
 *
 * The app is Compose-only: one activity, every screen is a navigation destination, and the manifest's
 * `configChanges` keeps rotation from recreating a live player surface.
 *
 * Nothing here reads the database or the network — the container is installed, the theme is applied,
 * and the nav host owns everything else.
 */
class MainActivity : ComponentActivity() {

    /**
     * Bridges the service graph into Compose without making `di` depend on `ui` (or vice versa):
     * the container is resolved lazily so the first frame never blocks on the database.
     */
    private val container: AppContainer = object : AppContainer {
        override val animeRepository: AnimeRepository get() = ServiceLocator.animeRepository
        override val streamRepository: StreamRepository get() = ServiceLocator.streamRepository
        override val hindiCatalog: HindiCatalog? get() = ServiceLocator.hindiCatalog
    }

    /**
     * PiP is entered from the player chrome. Publishing the flag here is what lets the Compose
     * overlay hide itself: inside the small window our seek bar and buttons are unusable, and the
     * system provides its own.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceLocator.install(applicationContext)
        enableEdgeToEdge()

        setContent {
            AnimoTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AnimoNavHost()
                }
            }
        }
    }
}
