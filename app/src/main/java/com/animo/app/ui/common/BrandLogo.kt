package com.animo.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.animo.app.R

/**
 * Dr. Animo is the app mascot (source art: `assets/animo.jpg`).
 *
 * Generated assets (see the build notes in the repo):
 *  - `drawable/logo_dr_animo_ring.png` — 512 px circular avatar with a teal ring (drawer header)
 *  - `drawable/logo_dr_animo.png`      — 512 px circular avatar, no ring
 *  - `drawable/hero_dr_animo.jpg`      — 1600x900 dark-edged banner (home hero)
 *  - `mipmap-…/ic_launcher…`           — adaptive + legacy launcher icons
 */
@Composable
fun BrandAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    ring: Boolean = true,
) {
    Image(
        painter = painterResource(if (ring) R.drawable.logo_dr_animo_ring else R.drawable.logo_dr_animo),
        contentDescription = "Animo",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}

/** Wide banner used behind the home header; add your own scrim/title on top. */
@Composable
fun BrandHero(
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    cornerRadius: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(cornerRadius)),
    ) {
        Image(
            painter = painterResource(R.drawable.hero_dr_animo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF20D1113),
                        0.55f to Color(0x990D1113),
                        1f to Color(0x330D1113),
                    ),
                ),
        )
    }
}

/** Small square mark for top bars / empty states. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Image(
        painter = painterResource(R.drawable.logo_dr_animo_small),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4)),
    )
}

@Suppress("unused")
@Composable
private fun BrandPreviewHost() {
    Box(Modifier.background(Color(0xFF0D1113)), contentAlignment = Alignment.Center) {
        BrandAvatar(size = 80.dp)
    }
}
