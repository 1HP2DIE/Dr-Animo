package com.animo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.animo.app.di.ServiceLocator
import com.animo.app.ui.AnimoNavHost
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceLocator.install(applicationContext)
        enableEdgeToEdge()

        setContent {
            AnimoTheme {
                AnimoNavHost()
            }
        }
    }
}
