package com.animo.app.ui.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animo.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where support goes. Kept here, in one place, so a changed address is a one-line edit. */
private object SupportLinks {
    const val TELEGRAM_HANDLE = "@dr_animo_apk"
    const val TELEGRAM_URL = "https://t.me/dr_animo_apk"
    const val BEP20_ADDRESS = "0xd293ecb0009d1ee041810c2584e879c784060002"
    const val BINANCE_PAY_ID = "1010167458"
}

/**
 * Support page: the Telegram channel first (free, and where builds land), then two ways to send
 * crypto. Every value copies with one tap; nothing is opened or paid from inside the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedTemplate = stringResource(R.string.ui_support_copied)

    fun copy(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(copiedTemplate.format(label))
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_support_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.ui_action_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // One flag, one stagger: the page assembles instead of appearing all at once, and the
            // donation cards land last, which is where the eye should go.
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }

            Staggered(shown, order = 0) { Intro() }

            Staggered(shown, order = 1) {
                SectionLabel(stringResource(R.string.ui_support_community))
            }
            Staggered(shown, order = 2) {
                TelegramCard(
                    onOpen = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(SupportLinks.TELEGRAM_URL)),
                            )
                        }
                    },
                    onCopy = {
                        copy(context.getString(R.string.ui_support_telegram), SupportLinks.TELEGRAM_URL)
                    },
                )
            }

            Staggered(shown, order = 3) {
                SectionLabel(stringResource(R.string.ui_support_crypto))
            }
            Staggered(shown, order = 4) {
                CopyCard(
                    glow = true,
                    title = stringResource(R.string.ui_support_bep20_title),
                    badge = "BEP20",
                    hint = stringResource(R.string.ui_support_bep20_hint),
                    value = SupportLinks.BEP20_ADDRESS,
                    mono = true,
                    warning = stringResource(R.string.ui_support_bep20_warning),
                    onCopy = {
                        copy(context.getString(R.string.ui_support_bep20_title), SupportLinks.BEP20_ADDRESS)
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, SupportLinks.BEP20_ADDRESS)
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    },
                )
            }
            Staggered(shown, order = 5) {
                CopyCard(
                    glow = true,
                    title = stringResource(R.string.ui_support_binance_title),
                    badge = "Pay ID",
                    hint = stringResource(R.string.ui_support_binance_hint),
                    value = SupportLinks.BINANCE_PAY_ID,
                    mono = true,
                    onCopy = {
                        copy(context.getString(R.string.ui_support_binance_title), SupportLinks.BINANCE_PAY_ID)
                    },
                )
            }

            Text(
                text = stringResource(R.string.ui_support_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

/**
 * Fades and lifts a section in, a beat after the one above it.
 *
 * The space is reserved up front (alpha + offset only, never `AnimatedVisibility`), so the page
 * never reflows while it is assembling — a list that shifts under a thumb that is already moving is
 * worse than no animation at all.
 */
@Composable
private fun Staggered(shown: Boolean, order: Int, content: @Composable () -> Unit) {
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = 320,
            delayMillis = order * 55,
            easing = FastOutSlowInEasing,
        ),
        label = "support-stagger-$order",
    )
    Box(
        Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 14.dp.toPx()
        },
    ) {
        content()
    }
}

@Composable
private fun Intro() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.primary.copy(alpha = 0.10f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.ui_support_intro_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.ui_support_intro_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun Card(glow: Boolean = false, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    var frame = Modifier.fillMaxWidth()
    if (glow) {
        val pulse = rememberDonationPulse()
        val halo = MaterialTheme.colorScheme.primary
        frame = frame.drawBehind { drawHalo(pulse, halo, shape) }
    }
    Column(
        modifier = frame
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun TelegramCard(onOpen: () -> Unit, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = SupportLinks.TELEGRAM_HANDLE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.ui_support_telegram_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            CopyButton(onCopy = onCopy)
        }
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ui_support_telegram_open))
        }
    }
}

@Composable
private fun CopyCard(
    title: String,
    badge: String,
    hint: String,
    value: String,
    mono: Boolean,
    onCopy: () -> Unit,
    warning: String? = null,
    onShare: (() -> Unit)? = null,
    /** Donation cards breathe a little: they are the one thing on this page worth finding. */
    glow: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Card(glow = glow) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        // The value itself is the big tap target: tapping anywhere on it copies.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(colors.surfaceContainerHigh)
                .clickable(onClick = onCopy)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            onShare?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.ui_support_share),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            CopyButton(onCopy = onCopy)
        }
        warning?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Copy icon that turns into a tick for a moment, so the copy is confirmed where the finger is. */
@Composable
private fun CopyButton(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    IconButton(onClick = {
        onCopy()
        copied = true
    }) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() },
            label = "copy",
        ) { done ->
            Icon(
                imageVector = if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.ui_support_copy),
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}


/**
 * The donation halo's slow breath, shared by both cards.
 *
 * One infinite transition per card, two values, no blur: a `Modifier.blur` here would allocate a
 * render layer every frame for a ring that only needs three strokes.
 */
@Composable
private fun rememberDonationPulse(): Float {
    val transition = rememberInfiniteTransition(label = "donation-glow")
    val pulse by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "donation-pulse",
    )
    return pulse
}

/** Three stacked strokes fading outwards, which reads as a glow at card size. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHalo(
    pulse: Float,
    color: Color,
    shape: androidx.compose.ui.graphics.Shape,
) {
    val corner = (shape as? androidx.compose.foundation.shape.RoundedCornerShape)
        ?.topStart
        ?.toPx(size, this)
        ?: 0f
    val stroke = 1.2.dp.toPx()
    for (ring in 1..3) {
        val grow = stroke * ring
        drawRoundRect(
            color = color.copy(alpha = pulse * 0.20f / ring),
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + grow * 2f, size.height + grow * 2f),
            cornerRadius = CornerRadius(corner + grow),
            style = Stroke(width = stroke),
        )
    }
}
