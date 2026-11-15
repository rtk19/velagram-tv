package com.velagram.tv

import android.app.Activity
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text

/** Shared Compose composition; native transport views retain the proven remote/controller boundary. */
object PlayerOverlay {
    @JvmStatic fun create(activity: Activity, title: String, subtitle: String, back: View, source: View?,
        play: View, rewind: View, forward: View, seek: View, elapsed: View, remaining: View, status: View,
        episode: View, actions: Map<String, View>): View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0xd9080a10), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .40f to Color.Transparent, .74f to Color(0x78080a10), 1f to Color(0xf9080a10))))
                Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 48.dp, top = 24.dp), verticalAlignment = Alignment.Top) {
                    Native(back, Modifier.size(36.dp))
                    Column(Modifier.weight(1f).padding(start = 15.dp, top = 1.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Image(painterResource(R.drawable.vela_logo), null, Modifier.size(17.dp))
                            Text("Velagram", color = Premium.Ink.copy(alpha = .88f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(title, color = Premium.Ink, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp, end = 24.dp))
                        if (subtitle.isNotBlank() && subtitle != "Velagram player") Text(subtitle, color = Premium.Ink.copy(alpha = .68f), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                    }
                    if (source != null) Native(source, Modifier.width(112.dp).height(34.dp))
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 24.dp)) {
                    Native(status, Modifier.fillMaxWidth().height(18.dp))
                    Row(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Native(rewind, Modifier.size(52.dp).padding(4.dp))
                        Spacer(Modifier.width(18.dp))
                        Native(play, Modifier.size(76.dp).padding(6.dp))
                        Spacer(Modifier.width(18.dp))
                        Native(forward, Modifier.size(52.dp).padding(4.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Native(seek, Modifier.fillMaxWidth().height(24.dp))
                    Row(Modifier.fillMaxWidth().height(20.dp)) {
                        Native(elapsed, Modifier.weight(1f).fillMaxHeight())
                        Native(remaining, Modifier.width(100.dp).fillMaxHeight())
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        actions.forEach { (label, view) ->
                            Native(view, Modifier.width(when (label) { "Subtitles" -> 146.dp; "Options" -> 52.dp; else -> 116.dp }).height(44.dp))
                        }
                    }
                }
                Native(episode, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 239.dp).widthIn(min = 120.dp, max = 250.dp).height(40.dp))
            }
        }
    }
}

@Composable private fun Native(view: View, modifier: Modifier) {
    AndroidView(factory = { view }, modifier = modifier)
}
