package com.velagram.tv

import android.content.Context
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.json.JSONObject
import java.util.Locale
import java.util.function.Consumer

/** Immutable presentation values. JSON and persistence stay in the Java adapter. */
data class ArtworkItem(val key: String, val title: String, val metadata: String,
    val image: String, val progress: Float, val badge: String)
data class EpisodeItem(val key: String, val title: String, val date: String,
    val synopsis: String, val image: String, val runtime: String, val watched: Boolean, val progress: Float,
    val identity: String, val remaining: String)

private val Ink = Color(0xfff3f5fa)
private val Muted = Color(0xff969eae)
private val Surface = Color(0xff141822)

object BrowseComponents {
    @JvmStatic fun shelf(context: Context, values: List<JSONObject>, poster: Boolean,
        click: Consumer<JSONObject>, menu: Consumer<JSONObject>): View {
        val models = values.map { item ->
            val visual = item.optJSONObject("catalog") ?: item
            val history = item.has("catalog")
            val image = when {
                history && item.optString("episodeStill").isNotBlank() -> "https://image.tmdb.org/t/p/w780" + item.optString("episodeStill")
                !poster && visual.optString("backdrop_path").let { it.isNotBlank() && it != "null" } -> "https://image.tmdb.org/t/p/w780" + visual.optString("backdrop_path")
                else -> Models.image(visual)
            }
            val metadata = if (history) {
                (if (Store.episode(item)) "S${item.optInt("season")} · E${item.optInt("episode")}   " else "") + CinemaControls.time(item.optLong("position"))
            } else Models.year(visual) + (if (visual.optDouble("vote_average") > 0) "   ★ %.1f".format(Locale.US, visual.optDouble("vote_average")) else "")
            ArtworkItem(Store.progressKey(item).ifBlank { Models.key(visual) }, Models.title(visual), metadata,
                image, if (history) (item.optDouble("position") / item.optDouble("duration", 1.0).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) else 0f,
                if (history && Store.episode(item)) item.optString("episodeTitle", "Episode ${item.optInt("episode")}") else "")
        }
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LazyRow(contentPadding = PaddingValues(start = 28.dp, end = 48.dp, top = 10.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().focusRestorer()) {
                    items(models.indices.toList(), key = { "${models[it].key}:$it" }) { index ->
                        Artwork(models[index], poster, Color(TvUi.accent(Store(context))), { click.accept(values[index]) }, { menu.accept(values[index]) })
                    }
                }
            }
        }
    }

    @JvmStatic fun episodes(context: Context, values: List<JSONObject>, requests: List<JSONObject>, store: Store,
        click: Consumer<JSONObject>, menu: Consumer<JSONObject>): View {
        val refresh = {
            values.mapIndexed { index, value ->
                val request = requests[index]
                val saved = store.progressFor(request)
                val path = value.optString("still_path")
                EpisodeItem(Store.progressKey(request), "${value.optInt("episode_number")}. ${value.optString("name")}",
                    EpisodeDate.format(value.optString("air_date"), context.resources.configuration.locales[0]), value.optString("overview"),
                    if (path.isBlank() || path == "null") "" else "https://image.tmdb.org/t/p/w300$path",
                    if (value.optInt("runtime") > 0) "${value.optInt("runtime")} min" else "", store.isWatched(request),
                    if (saved == null) 0f else (saved.optDouble("position") / saved.optDouble("duration", 1.0).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f), "", "")
            }
        }
        val models = mutableStateOf(refresh())
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setTag(R.id.presentation_refresh, Runnable { models.value = refresh() })
            setContent {
                LazyRow(contentPadding = PaddingValues(start = 28.dp, end = 48.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().focusRestorer()) {
                    items(models.value.indices.toList(), key = { models.value[it].key }) { index ->
                        EpisodePoster(models.value[index], Color(TvUi.accent(store)), { click.accept(requests[index]) }, { menu.accept(requests[index]) })
                    }
                }
            }
        }
    }

    @JvmStatic fun source(context: Context, value: JSONObject, provider: String, click: Runnable): View {
        val metadata = listOf(value.optString("quality"), value.optString("language"), value.optString("container"))
            .filter { it.isNotBlank() && it != "null" }.distinct().joinToString("   ·   ")
        val model = SourceItem(value.optString("id", value.optString("url")), Models.title(value), metadata,
            provider, value.optString("group", value.optString("channel")),
            if (value.optLong("size") > 0) "%.1f GB".format(Locale.US, value.optLong("size") / 1073741824.0) else "", TvUi.accent(Store(context)))
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Source(model) { click.run() } }
        }
    }

    @JvmStatic fun episode(context: Context, value: JSONObject, request: JSONObject, store: Store,
        click: Runnable, menu: Runnable): View {
        val saved = store.progressFor(request)
        val path = value.optString("still_path")
        val model = EpisodeItem(Store.progressKey(request), "${value.optInt("episode_number")}. ${value.optString("name")}",
            EpisodeDate.format(value.optString("air_date"), context.resources.configuration.locales[0]), value.optString("overview"),
            if (path.isBlank() || path == "null") "" else "https://image.tmdb.org/t/p/w300$path",
            if (value.optInt("runtime") > 0) "${value.optInt("runtime")} min" else "", store.isWatched(request),
            if (saved == null) 0f else (saved.optDouble("position") / saved.optDouble("duration", 1.0).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
            "S${request.optInt("season")} E${request.optInt("episode")}",
            if (saved != null && saved.optLong("position") > 0 && saved.optLong("duration") > saved.optLong("position")) "${(saved.optLong("duration") - saved.optLong("position")) / 60000} min left" else "")
        val state = mutableStateOf(model)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setTag(R.id.presentation_refresh, Runnable {
                val progress = store.progressFor(request)
                state.value = state.value.copy(watched = store.isWatched(request), progress =
                    if (progress == null) 0f else (progress.optDouble("position") / progress.optDouble("duration", 1.0).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
                    remaining = if (progress != null && progress.optLong("duration") > progress.optLong("position")) "${(progress.optLong("duration") - progress.optLong("position")) / 60000} min left" else "")
            })
            setContent { Episode(state.value, Color(TvUi.accent(store)), { click.run() }, { menu.run() }) }
        }
    }

    @JvmStatic fun refresh(view: View) {
        (view.getTag(R.id.presentation_refresh) as? Runnable)?.run()
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) refresh(view.getChildAt(index))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun Artwork(item: ArtworkItem, poster: Boolean, accent: Color, click: () -> Unit, menu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(175), label = "artwork-focus")
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.width(if (poster) 126.dp else 202.dp).onFocusChanged { focused = it.isFocused }
        .combinedClickable(interactionSource = interaction, indication = null, onClick = click, onLongClick = menu)
        .padding(6.dp)) {
        Box(Modifier.fillMaxWidth().height(if (poster) 170.dp else 108.dp)
            .graphicsLayer { scaleX = if (poster) scale else 1f; scaleY = if (poster) scale else 1f }
            .premiumFocus(focused, accent).clip(RoundedCornerShape(7.dp)).background(Surface)) {
            PremiumArtwork(item.image, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { if (!poster) { scaleX = scale; scaleY = scale } })
            if (!poster) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xf2080a10)))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 13.dp)) {
                    Text(item.title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (item.badge.isNotBlank()) item.metadata.substringBefore("   ") + " · " + item.badge else item.metadata,
                        color = Ink.copy(alpha = .78f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .23f))) {
                    if (item.progress > 0) Box(Modifier.fillMaxWidth(item.progress).fillMaxHeight().background(accent))
                }
            }
        }
        if (poster) {
            Spacer(Modifier.height(9.dp))
            Text(item.title, color = Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.metadata, color = Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun Episode(item: EpisodeItem, accent: Color, click: () -> Unit, menu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 28.dp, vertical = 4.dp)
        .onFocusChanged { focused = it.isFocused }.premiumSurface(focused, accent)
        .combinedClickable(onClick = click, onLongClick = menu).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.width(174.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xff202735))) {
            PremiumArtwork(item.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .28f)))))
            if (item.watched) Text("✓", color = Ink, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
            else if (focused) Text("▷", color = Ink, fontSize = 24.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(item.identity, color = if (focused) accent.copy(red = (accent.red + .3f).coerceAtMost(1f), green = (accent.green + .3f).coerceAtMost(1f), blue = (accent.blue + .3f).coerceAtMost(1f)) else Muted, fontSize = 9.sp)
            Text(item.title.substringAfter(". ", item.title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(listOf(item.date, item.runtime).filter { it.isNotBlank() }.joinToString("   ·   ") { "\u2068$it\u2069" },
                style = TextStyle(textDirection = TextDirection.Ltr), color = Ink.copy(alpha = .75f), fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
            Text(item.synopsis, style = TextStyle(textDirection = TextDirection.ContentOrLtr,
                textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left), fontSize = 11.sp, lineHeight = 14.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
        Column(Modifier.width(92.dp).align(Alignment.CenterVertically).padding(end = 8.dp), horizontalAlignment = Alignment.End) {
            if (item.watched) Text("✓  Watched", color = accent.copy(alpha = .9f), fontSize = 11.sp)
            else if (item.progress > 0) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxWidth(item.progress).fillMaxHeight().background(accent))
                }
                if (item.remaining.isNotBlank()) Text(item.remaining, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
    }
}

data class SourceItem(val key: String, val title: String, val metadata: String, val provider: String, val origin: String, val size: String, val accent: Int)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun Source(item: SourceItem, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = Color(item.accent)
    Row(Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp)
        .onFocusChanged { focused = it.isFocused }.premiumSurface(focused, accent)
        .combinedClickable(onClick = click).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(25.dp).background(accent.copy(alpha = .18f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            val context = LocalContext.current
            AndroidView(factory = { TvUi.Icon(context, if (item.provider.contains("Telegram", true)) "Telegram" else "Sources", TvUi.TEXT) }, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.width(108.dp).padding(start = 10.dp, end = 10.dp)) {
            Text(item.provider, color = Ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.origin.isNotBlank()) Text(item.origin, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(.5.dp).height(30.dp).background(Premium.Hairline))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, color = Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.metadata.isNotBlank()) Text(item.metadata, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        if (item.size.isNotBlank()) Text(item.size, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.size(28.dp).background(if (focused) accent else Color(0xff282d3b), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Text("▷", color = Ink, fontSize = 18.sp)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun EpisodePoster(item: EpisodeItem, accent: Color, click: () -> Unit, menu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(175), label = "episode-focus")
    Box(Modifier.width(208.dp).height(124.dp).padding(5.dp).onFocusChanged { focused = it.isFocused }
        .combinedClickable(onClick = click, onLongClick = menu)
        .premiumFocus(focused, accent).clip(RoundedCornerShape(7.dp)).background(Surface)) {
        PremiumArtwork(item.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xf5080a10)))))
        if (item.runtime.isNotBlank()) Text(item.runtime, fontSize = 9.sp, color = Ink,
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).background(Color(0xc0080a10), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 3.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text((if (item.watched) "✓  " else "") + item.title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.date.isNotBlank()) Text(item.date, color = Ink.copy(alpha = .75f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (item.progress > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(item.progress).height(3.dp).background(accent))
    }
}
