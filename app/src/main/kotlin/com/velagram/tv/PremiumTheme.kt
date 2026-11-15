package com.velagram.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object Premium {
    val Background = Color(0xff080a10)
    val Surface = Color(0xff141822)
    val Ink = Color(0xfff3f5fa)
    val Muted = Color(0xff969eae)
    val Hairline = Color(0xff2c3240)
}

/** Halo lives inside reserved card padding; it never changes a lazy-list slot. */
internal fun Modifier.premiumFocus(focused: Boolean, accent: Color, radius: Int = 8): Modifier = this
    .shadow(if (focused) 9.dp else 0.dp, RoundedCornerShape(radius.dp), clip = false,
        ambientColor = accent.copy(alpha = .25f), spotColor = accent.copy(alpha = .4f))
    .border(if (focused) 1.5.dp else .5.dp, if (focused) accent.copy(alpha = .95f) else Premium.Hairline.copy(alpha = .55f), RoundedCornerShape(radius.dp))

internal fun Modifier.premiumSurface(focused: Boolean, accent: Color, radius: Int = 8): Modifier = this
    .premiumFocus(focused, accent, radius)
    .background(Brush.horizontalGradient(listOf(if (focused) accent.copy(alpha = .20f) else Color(0xff161b26), Color(0xff10141e))), RoundedCornerShape(radius.dp))

@Composable internal fun PremiumArtwork(url: String, description: String?, modifier: Modifier = Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop,
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.Center) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url, context) { coil.request.ImageRequest.Builder(context).data(url).crossfade(240).build() }
    coil.compose.AsyncImage(request, description, modifier = modifier, contentScale = contentScale, alignment = alignment)
}
