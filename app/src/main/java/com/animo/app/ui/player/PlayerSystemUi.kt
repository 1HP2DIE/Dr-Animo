package com.animo.app.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.animo.app.ui.common.findActivity
import com.animo.app.ui.theme.LocalIsDarkTheme

/**
 * Immersive video presentation: hide both system bars and hold the activity in landscape while the
 * player is full screen ([immersive]); keep the screen awake whenever video is on screen at all. Everything is restored on dispose, so leaving the player returns the app to
 * its previous orientation and chrome.
 *
 * The activity declares `configChanges=orientation|screenSize|…` in the manifest, so the locked
 * rotation never recreates the surface underneath a live ExoPlayer.
 */
@Composable
internal fun ImmersiveVideoEffect(enabled: Boolean, immersive: Boolean = true) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // A 22-minute episode is longer than the screen timeout, and waking the phone by touching the
    // video also toggles the controls — the flag is the only correct fix. Inline playback needs it
    // just as much as full screen.
    DisposableEffect(activity, enabled) {
        if (activity == null || !enabled) return@DisposableEffect onDispose { }
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // The bars float over the video while it is up, so their icons must stay light whatever the
    // app theme is; they only follow the theme again once the player is gone.
    val lightBars = !LocalIsDarkTheme.current
    DisposableEffect(activity, enabled && immersive) {
        if (activity == null || !enabled || !immersive) {
            return@DisposableEffect onDispose { }
        }
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehaviour = insetsController.systemBarsBehavior
        val previousOrientation = activity.requestedOrientation
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            // Always back to portrait when the previous state was not a real choice: leaving PiP
            // hands the activity back while it is still in landscape, and every other screen is
            // portrait-first.
            activity.requestedOrientation =
                if (previousOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                    previousOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                ) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    previousOrientation
                }
            insetsController.systemBarsBehavior = previousBehaviour
            insetsController.isAppearanceLightStatusBars = lightBars
            insetsController.isAppearanceLightNavigationBars = lightBars
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Picture-in-picture, at the video's own aspect ratio.
 *
 * Only meaningful from API 26; on anything older the button does nothing rather than crashing, and
 * the manifest flag is simply ignored.
 */
@Composable
internal fun rememberPipAction(aspectWidth: Int = 16, aspectHeight: Int = 9): () -> Unit {
    val context = LocalContext.current
    return remember(context, aspectWidth, aspectHeight) {
        {
            val activity = context.findActivity()
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    activity.enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(aspectWidth, aspectHeight))
                            .build(),
                    )
                }
            }
        }
    }
}

/**
 * True while the activity is drawn in the system's PiP window.
 *
 * Fed by `MainActivity.onPictureInPictureModeChanged` rather than by a listener on the Activity
 * object: the listener overloads changed across API levels, while the callback has existed since
 * API 26 and runs on the main thread exactly once per transition.
 */
internal object PipState {
    val inPip = mutableStateOf(false)
}

@Composable
internal fun rememberInPictureInPicture(): Boolean = PipState.inPip.value

