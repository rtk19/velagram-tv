package com.velagram.tv

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.foundation.Image
import org.json.JSONObject
import java.util.Locale

/** Product content stays real; layout modes share the same cinematic artwork and type system. */
data class HeroItem(val title: String, val metadata: String, val genres: String, val synopsis: String,
    val image: String, val poster: String, val primary: String, val saved: Boolean, val mode: String, val accent: Int)

object DetailHero {
    private fun model(item: JSONObject, store: Store, mode: String): HeroItem {
        val series = item.optString("kind") == "tv"
        val metadata = mutableListOf(Models.year(item), if (series) "Series" else "Movie")
        if (series && item.optInt("number_of_seasons") > 0) metadata += "${item.optInt("number_of_seasons")} seasons"
        if (!series && item.optInt("runtime") > 0) metadata += "${item.optInt("runtime")} min"
        if (item.optDouble("vote_average") > 0) metadata += "★ %.1f".format(Locale.US, item.optDouble("vote_average"))
        val path = item.optString("backdrop_path")
        return HeroItem(Models.title(item), metadata.filter { it.isNotBlank() }.joinToString("   ·   "),
            Models.list(item.optJSONArray("genres")).take(3).joinToString(" · ") { it.optString("name") }, item.optString("overview"),
            if (path.isBlank() || path == "null") "" else "https://image.tmdb.org/t/p/w1280$path", Models.image(item),
            if (mode == "home") "▷  Explore title" else if (series) "▷  Choose episode" else "▷  Find sources",
            store.contains("watchlist", item), mode, TvUi.accent(store))
    }
    @JvmStatic fun create(context: Context, item: JSONObject, details: Boolean, store: Store,
        play: Runnable, save: Runnable, more: Runnable): View {
        val state = mutableStateOf(model(item, store, if (details) "details" else "home"))
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setTag(R.id.hero_update, java.util.function.Consumer<JSONObject> { value -> state.value = model(value, store, if (details) "details" else "home") })
            setContent { Hero(state.value, { play.run() }, { save.run() }, { more.run() }) }
        }
    }
    @JvmStatic fun update(view: View, item: JSONObject, store: Store) {
        @Suppress("UNCHECKED_CAST")
        val update = view.getTag(R.id.hero_update) as? java.util.function.Consumer<JSONObject>
        update?.accept(item)
    }
    @JvmStatic fun compact(context: Context, item: JSONObject, store: Store, label: String): View = decoration(context) {
        CompactHero(model(item, store, "compact"), label)
    }
    @JvmStatic fun source(context: Context, item: JSONObject, store: Store, season: Int, episode: Int, metadata: JSONObject?): View = decoration(context) {
        SourceHeader(model(item, store, "source"), season, episode, metadata?.optString("name") ?: "",
            EpisodeDate.format(metadata?.optString("air_date"), context.resources.configuration.locales[0]), metadata?.optInt("runtime") ?: 0)
    }
    @JvmStatic fun tabs(context: Context, labels: Array<String>, actions: List<Runnable>, accent: Int): View = host(context) {
        var selected by remember { mutableIntStateOf(0) }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp).height(38.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            labels.forEachIndexed { index, label ->
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.onFocusChanged { focused = it.isFocused }.clickable { selected = index; actions[index].run() }.padding(top = 7.dp)) {
                    Text(label, color = if (selected == index || focused) Premium.Ink else Premium.Muted, fontSize = 13.sp, fontWeight = if (selected == index || focused) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(if (label.length > 8) 90.dp else 60.dp).height(2.dp).background(if (selected == index || focused) Color(accent) else Color.Transparent))
                }
            }
        }
    }
    private fun decoration(context: Context, content: @Composable () -> Unit): View = host(context, content).apply {
        setTag(R.id.focus_stop, false); isFocusable = false
        if (this is android.view.ViewGroup) descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
    private fun host(context: Context, content: @Composable () -> Unit): View = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent(content = content)
    }
}

@Composable private fun Backdrop(item: HeroItem) {
    PremiumArtwork(item.image, null, contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd, modifier = Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Premium.Background, .20f to Premium.Background.copy(alpha = .96f), .48f to Premium.Background.copy(alpha = .80f), .64f to Premium.Background.copy(alpha = .64f), .88f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Premium.Background.copy(alpha = .08f), .67f to Color.Transparent, 1f to Premium.Background)))
}

@Composable private fun Hero(item: HeroItem, play: () -> Unit, save: () -> Unit, more: () -> Unit) {
    val details = item.mode == "details"
    var saved by remember(item.title) { mutableStateOf(item.saved) }
    Box(Modifier.fillMaxWidth().height(if (details) 290.dp else 260.dp).background(Premium.Background)) {
        Backdrop(item)
        Row(Modifier.align(Alignment.CenterStart).padding(start = 28.dp, end = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (details) PremiumArtwork(item.poster, item.title, contentScale = ContentScale.Crop,
                modifier = Modifier.width(132.dp).height(198.dp).premiumFocus(false, Color(item.accent)).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.width(if (details) 450.dp else 410.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Image(painterResource(R.drawable.vela_logo), null, Modifier.size(18.dp))
                    Text("VELAGRAM", color = Premium.Ink.copy(alpha = .8f), fontSize = 10.sp, letterSpacing = 1.8.sp)
                    if (!details) Text("   IN THE SPOTLIGHT", color = Premium.Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                Text(item.title, color = Premium.Ink, fontSize = if (details) 29.sp else 38.sp,
                    lineHeight = if (details) 34.sp else 41.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                if (item.genres.isNotBlank() && details) Text(item.genres, color = Premium.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                Text(item.metadata, color = Premium.Ink.copy(alpha = .78f), fontSize = 11.sp, maxLines = 1)
                Text(item.synopsis, color = Premium.Ink.copy(alpha = .82f), fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = if (details) 3 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 13.dp, bottom = 18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeroAction(item.primary, true, Color(item.accent), play)
                    HeroAction(if (saved) "✓  My list" else "＋  My list", false, Color(item.accent)) { save(); saved = !saved }
                    if (details) HeroAction("···", false, Color(item.accent), more)
                }
            }
        }
    }
}

@Composable private fun CompactHero(item: HeroItem, label: String) {
    Box(Modifier.fillMaxWidth().height(162.dp).background(Premium.Background)) {
        Backdrop(item)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 28.dp).width(480.dp)) {
            Text(label.uppercase(Locale.ROOT), color = Color(item.accent), fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 8.dp))
            Text(item.title, color = Premium.Ink, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.metadata + if (item.genres.isNotBlank()) "   ·   ${item.genres}" else "", color = Premium.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
            Text(item.synopsis, color = Premium.Ink.copy(alpha = .78f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable private fun HeroAction(label: String, primary: Boolean, accent: Color, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bright = accent.luminance() > .27f
    Box(Modifier.height(38.dp).onFocusChanged { focused = it.isFocused }
        .premiumFocus(focused, accent)
        .background(if (primary) Brush.verticalGradient(listOf(accent.copy(alpha = if (bright) .96f else .84f), accent.copy(alpha = if (bright) .84f else .60f))) else Brush.verticalGradient(listOf(Color(0xd52a2d38), Color(0xd5161922))), RoundedCornerShape(8.dp))
        .clickable(onClick = click).padding(horizontal = if (label == "···") 14.dp else 18.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary && bright) Premium.Background else Premium.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable private fun SourceHeader(item: HeroItem, season: Int, episode: Int, episodeTitle: String, date: String, runtime: Int) {
    Box(Modifier.fillMaxWidth().height(176.dp).background(Premium.Background)) {
        Backdrop(item)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 28.dp).width(500.dp)) {
            Text(item.title, color = Premium.Ink, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (episode > 0) "Season $season · Episode $episode" else item.metadata, color = Premium.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
            if (episodeTitle.isNotBlank()) Text(episodeTitle, color = Premium.Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            val details = listOf(date, if (runtime > 0) "$runtime min" else "").filter { it.isNotBlank() }.joinToString("   ·   ")
            if (details.isNotBlank()) Text(details, color = Premium.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            Text("▷  Choose a source to play", color = Premium.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 18.dp))
        }
    }
}
