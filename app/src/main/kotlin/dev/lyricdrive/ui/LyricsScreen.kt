package dev.lyricdrive.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyricdrive.R
import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.model.PlaybackSnapshot
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.PlaybackClock
import dev.lyricdrive.ui.components.FluidBackground
import dev.lyricdrive.ui.components.KaraokeText
import kotlin.math.abs
import kotlin.math.min

private val LyricStyle = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)

@Composable
fun LyricsScreen(vm: LyricsViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        FluidBackground(ui.palette, ui.energy, ui.snapshot.isPlaying)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(ui)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val state = ui.lyrics) {
                    LyricsState.Idle -> CenterMessage(stringResource(R.string.nothing_playing), stringResource(R.string.nothing_playing_hint))
                    is LyricsState.Loading -> CenterMessage(state.track.title, stringResource(R.string.searching))
                    is LyricsState.NotFound -> CenterMessage(state.track.title, "${state.track.artist} — ${stringResource(R.string.no_lyrics)}")
                    is LyricsState.Unavailable -> CenterMessage(state.track.title, stringResource(R.string.offline))
                    is LyricsState.Ready -> when (val lyrics = state.lyrics) {
                        is Lyrics.Instrumental -> CenterMessage(state.track.title, stringResource(R.string.instrumental))
                        is Lyrics.Unsynced -> UnsyncedLyrics(lyrics.lines)
                        is Lyrics.Synced -> SyncedLyrics(
                            timeline = ui.timeline,
                            snapshot = ui.snapshot,
                            durationMs = state.track.durationMs,
                            following = following,
                            onUserScrollStarted = vm::onUserScrollStarted,
                            onUserScrollEnded = vm::onUserScrollEnded,
                            onResume = vm::resumeFollowing,
                            onLineTapped = { vm.onLineTapped(it.startMs) },
                        )
                    }
                }
            }
            Controls(ui.snapshot.isPlaying, vm::skipToPrevious, vm::togglePlayPause, vm::skipToNext)
        }
    }
}

/** Playback position updated every display frame (VSync) while playing. */
@Composable
private fun rememberPosition(snapshot: PlaybackSnapshot, durationMs: Long?): State<Long> {
    val position = remember { mutableLongStateOf(0L) }
    LaunchedEffect(snapshot, durationMs) {
        position.longValue = PlaybackClock.positionAt(snapshot, SystemClock.elapsedRealtime(), durationMs)
        while (snapshot.isPlaying) {
            withFrameMillis { position.longValue = PlaybackClock.positionAt(snapshot, SystemClock.elapsedRealtime(), durationMs) }
        }
    }
    return position
}

@Composable
private fun SyncedLyrics(
    timeline: LyricsTimeline,
    snapshot: PlaybackSnapshot,
    durationMs: Long?,
    following: Boolean,
    onUserScrollStarted: () -> Unit,
    onUserScrollEnded: () -> Unit,
    onResume: () -> Unit,
    onLineTapped: (LyricLine) -> Unit,
) {
    val position = rememberPosition(snapshot, durationMs)
    val active by remember(timeline) { derivedStateOf { timeline.activeIndex(position.value) } }
    val listState = rememberLazyListState()

    // Only real finger drags count as browsing; our own animated scrolls emit no interactions.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> onUserScrollStarted()
                is DragInteraction.Stop, is DragInteraction.Cancel -> onUserScrollEnded()
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val halfHeight = maxHeight / 2
        LaunchedEffect(active, following, timeline) {
            if (following) listState.centerOn(active.coerceAtLeast(0))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = halfHeight, bottom = halfHeight),
        ) {
            itemsIndexed(timeline.lines) { index, line ->
                LyricRow(
                    line = line,
                    distance = if (active < 0) index + 1 else index - active,
                    filled = { timeline.filledCharacters(index, position.value) },
                    progress = { timeline.lineProgress(index, position.value) },
                    onClick = { onLineTapped(line) },
                )
            }
        }

        AnimatedVisibility(
            visible = !following,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Surface(
                onClick = onResume,
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_follow), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.resume_auto_scroll), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Spring-scroll so the item's center lands on the viewport's optical center. */
private suspend fun LazyListState.centerOn(index: Int) {
    if (layoutInfo.totalItemsCount == 0) return
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        // Far away (e.g. after a seek): hop next to it, then let the spring do the last stretch.
        val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        scrollToItem((index + if (index > firstVisible) -2 else 2).coerceIn(0, layoutInfo.totalItemsCount - 1))
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    // Item offsets and viewport offsets share one coordinate space (content padding included).
    val delta = item.offset + item.size / 2f - viewportCenter
    animateScrollBy(delta, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow))
}

@Composable
private fun LyricRow(
    line: LyricLine,
    distance: Int,
    filled: () -> Float,
    progress: () -> Float,
    onClick: () -> Unit,
) {
    val isActive = distance == 0
    val springSpec = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    val scale by animateFloatAsState(if (isActive) 1f else 0.85f, springSpec, label = "scale")
    val alpha by animateFloatAsState(
        when {
            isActive -> 1f
            abs(distance) == 1 -> 0.5f
            else -> 0.4f
        },
        springSpec,
        label = "alpha",
    )
    val blur by animateDpAsState(if (isActive) 0.dp else (min(abs(distance), 3) * 1.5f).dp, label = "blur")

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick) // ripple feedback
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val content = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .blur(blur)
        if (line.isInterlude) {
            InterludeDots(isActive, progress, content)
        } else {
            KaraokeText(line.text, LyricStyle, active = isActive, filled = filled, modifier = content)
        }
    }
}

@Composable
private fun InterludeDots(active: Boolean, progress: () -> Float, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        // Each dot fills in turn over the instrumental break.
                        val lit = if (active) ((progress() * 3f) - i).coerceIn(0f, 1f) else 0f
                        this.alpha = 0.35f + 0.65f * lit
                        scaleX = 0.8f + 0.3f * lit
                        scaleY = scaleX
                    }
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun UnsyncedLyrics(lines: List<String>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        item {
            Text(
                stringResource(R.string.unsynced_notice),
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(lines) { _, line ->
            Text(
                line,
                style = LyricStyle.copy(fontSize = 24.sp, lineHeight = 30.sp),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CenterMessage(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = LyricStyle.copy(fontSize = 34.sp), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(12.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
    }
}

@Composable
private fun Header(ui: NowPlayingUi) {
    val track = ui.track ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ui.artwork?.let { art ->
            Image(
                bitmap = remember(art) { art.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Controls(playing: Boolean, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous), tint = Color.White, modifier = Modifier.size(32.dp))
        }
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                stringResource(if (playing) R.string.pause else R.string.play),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next), tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
