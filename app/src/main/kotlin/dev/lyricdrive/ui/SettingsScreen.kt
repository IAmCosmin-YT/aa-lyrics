package dev.lyricdrive.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyricdrive.BuildConfig
import dev.lyricdrive.LyricDriveApp
import dev.lyricdrive.car.CarDiagnostics
import dev.lyricdrive.car.InstallSource
import dev.lyricdrive.R
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.data.audio.OutputRoute
import dev.lyricdrive.data.settings.AndroidAutoMode
import dev.lyricdrive.data.settings.availableAndroidAutoModes
import dev.lyricdrive.data.settings.isCarVariant
import dev.lyricdrive.data.settings.AnimationStyle
import dev.lyricdrive.data.settings.DEFAULT_LYRICS_PLUS_SERVER
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.data.settings.CarLayout
import dev.lyricdrive.data.settings.HighlightColor
import dev.lyricdrive.data.settings.NightMode
import dev.lyricdrive.render.LiveSceneSource
import dev.lyricdrive.render.LyricsController
import dev.lyricdrive.render.PreviewSceneSource
import dev.lyricdrive.render.complementary
import dev.lyricdrive.ui.components.LyricsCanvas
import dev.lyricdrive.ui.components.ThemeBackground

private val CardColor = Color.White.copy(alpha = 0.06f)
private val Muted = Color.White.copy(alpha = 0.62f)

private data class Choice<T>(val value: T, @StringRes val label: Int, @StringRes val hint: Int? = null)

private val WORD_CHOICES = listOf(
    Choice(WordHighlight.AUTO, R.string.word_auto, R.string.word_auto_desc),
    Choice(WordHighlight.TIMED_ONLY, R.string.word_timed_short, R.string.word_timed_desc),
    Choice(WordHighlight.OFF, R.string.word_off, R.string.word_off_desc),
)

private val ANIMATION_CHOICES = listOf(
    Choice(AnimationStyle.BEAUTIFUL, R.string.anim_beautiful, R.string.anim_beautiful_desc),
    Choice(AnimationStyle.FLUID, R.string.anim_fluid, R.string.anim_fluid_desc),
    Choice(AnimationStyle.KARAOKE, R.string.anim_karaoke, R.string.anim_karaoke_desc),
    Choice(AnimationStyle.BOUNCING_BALL, R.string.anim_ball, R.string.anim_ball_desc),
    Choice(AnimationStyle.TYPEWRITER, R.string.anim_typewriter, R.string.anim_typewriter_desc),
    Choice(AnimationStyle.CINEMATIC, R.string.anim_cinematic, R.string.anim_cinematic_desc),
    Choice(AnimationStyle.GLANCE, R.string.anim_glance, R.string.anim_glance_desc),
    Choice(AnimationStyle.SINGLE_LINE, R.string.anim_single, R.string.anim_single_desc),
)

private val THEME_CHOICES = listOf(
    Choice(BackgroundMode.COVER_BLEND, R.string.bg_cover_blend),
    Choice(BackgroundMode.ALBUM_FLUID, R.string.bg_album_fluid),
    Choice(BackgroundMode.ALBUM_ART, R.string.bg_album_art),
    Choice(BackgroundMode.ALBUM_GRADIENT, R.string.bg_album_gradient),
    Choice(BackgroundMode.SPECTRUM, R.string.bg_spectrum),
    Choice(BackgroundMode.LOFI, R.string.bg_lofi),
    Choice(BackgroundMode.DISCO, R.string.bg_disco),
    Choice(BackgroundMode.BLACK, R.string.bg_black),
)

private val HIGHLIGHT_CHOICES = listOf(
    Choice(HighlightColor.AUTO, R.string.hl_auto),
    Choice(HighlightColor.COMPLEMENTARY, R.string.hl_complementary),
    Choice(HighlightColor.WHITE, R.string.hl_white),
    Choice(HighlightColor.AMBER, R.string.hl_amber),
    Choice(HighlightColor.CRIMSON, R.string.hl_crimson),
    Choice(HighlightColor.ICE_BLUE, R.string.hl_ice),
    Choice(HighlightColor.EMERALD, R.string.hl_emerald),
)

private val AA_MODE_CHOICES = listOf(
    Choice(AndroidAutoMode.BESIDE_MAPS, R.string.aa_mode_beside, R.string.aa_mode_beside_desc),
    Choice(AndroidAutoMode.FULL_SCREEN, R.string.aa_mode_full, R.string.aa_mode_full_desc),
    Choice(AndroidAutoMode.MEDIA_PLAYER, R.string.aa_mode_media, R.string.aa_mode_media_desc),
).filter { it.value in availableAndroidAutoModes() }

private val CAR_LAYOUT_CHOICES = listOf(
    Choice(CarLayout.PHONE, R.string.car_layout_phone, R.string.car_layout_phone_desc),
    Choice(CarLayout.COMPACT, R.string.car_layout_compact, R.string.car_layout_compact_desc),
)

private val NIGHT_CHOICES = listOf(
    Choice(NightMode.AUTO, R.string.night_auto, R.string.night_auto_desc),
    Choice(NightMode.LIGHT_SENSOR, R.string.night_sensor, R.string.night_sensor_desc),
    Choice(NightMode.ALWAYS, R.string.night_always, R.string.night_always_desc),
    Choice(NightMode.NEVER, R.string.night_never, R.string.night_never_desc),
)

@Composable
fun SettingsScreen(vm: LyricsViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val update = vm::updateSettings
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0814))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = Color.White)
                }
                Text(stringResource(R.string.settings), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            LivePreview(vm, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(190.dp))

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section(R.string.section_theme) {
                    Grid(THEME_CHOICES, columns = 3) { choice ->
                        ThemeTile(choice, selected = settings.backgroundMode == choice.value, palette = ui.palette) {
                            update { it.copy(backgroundMode = choice.value) }
                        }
                    }
                }
                Section(R.string.section_animation) {
                    Grid(ANIMATION_CHOICES, columns = 2) { choice ->
                        OptionCard(choice, selected = settings.animationStyle == choice.value) {
                            update { it.copy(animationStyle = choice.value) }
                        }
                    }
                }
                Section(R.string.section_highlight) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HIGHLIGHT_CHOICES.forEach { choice ->
                            Swatch(choice, selected = settings.highlightColor == choice.value, albumPrimary = ui.palette.primary) {
                                update { it.copy(highlightColor = choice.value) }
                            }
                        }
                    }
                }
                Section(R.string.section_word) {
                    Segmented(WORD_CHOICES, settings.wordHighlight) { v -> update { it.copy(wordHighlight = v) } }
                }
                Section(R.string.section_sync) {
                    val route by vm.outputRoute.collectAsStateWithLifecycle()
                    val delay by vm.routeDelayMs.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.audio_delay), color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.audio_delay_route, stringResource(routeLabel(route))), color = Muted, fontSize = 12.sp)
                        }
                        Text(formatOffset(delay.toLong()), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Slider(
                        value = delay.toFloat(),
                        onValueChange = { v -> vm.setRouteDelay(Math.round(v / 25f) * 25) },
                        valueRange = -500f..1000f,
                    )
                    Text(stringResource(R.string.audio_delay_desc), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Section(R.string.section_sources) {
                    Text(stringResource(R.string.sources_desc), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.lyricsPlusServer,
                        onValueChange = { v -> update { it.copy(lyricsPlusServer = v.trim()) } },
                        label = { Text(stringResource(R.string.lyrics_plus_server)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = { update { it.copy(lyricsPlusServer = DEFAULT_LYRICS_PLUS_SERVER) } }) { Text(stringResource(R.string.reset), color = Color.White) }
                        TextButton(onClick = { update { it.copy(lyricsPlusServer = "") } }) { Text(stringResource(R.string.disable), color = Color.White) }
                    }
                }
                Section(R.string.section_night) {
                    Segmented(NIGHT_CHOICES, settings.nightMode) { v -> update { it.copy(nightMode = v) } }
                }
                Section(R.string.section_car) {
                    Text(stringResource(R.string.aa_mode), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (AA_MODE_CHOICES.size > 1) {
                        Segmented(AA_MODE_CHOICES, settings.androidAutoMode) { v -> update { it.copy(androidAutoMode = v) } }
                    } else {
                        Text(stringResource(R.string.aa_mode_media_only), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    val fromPlay = remember { InstallSource.isFromPlay(context) }
                    if (isCarVariant && !fromPlay) {
                        Text(
                            stringResource(R.string.aa_full_needs_play),
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!isCarVariant) SwitchRow(
                        title = stringResource(R.string.aa_art_card),
                        hint = stringResource(R.string.aa_art_card_desc),
                        checked = settings.lyricsArtCard,
                    ) { v -> update { it.copy(lyricsArtCard = v) } }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.car_layout), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Segmented(CAR_LAYOUT_CHOICES, settings.carLayout) { v -> update { it.copy(carLayout = v) } }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        title = stringResource(R.string.car_full_screen),
                        hint = stringResource(R.string.car_full_screen_desc),
                        checked = settings.fullScreenWhenParked,
                    ) { v -> update { it.copy(fullScreenWhenParked = v) } }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.car_driving_note), color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    CarDiagnosticsCard()
                }
                Text(
                    stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Live preview of the current choices using the same renderer as the car. */
@Composable
private fun LivePreview(vm: LyricsViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val graph = (context.applicationContext as LyricDriveApp).graph
    val density = LocalDensity.current.density
    val dark by rememberUpdatedState(isSystemInDarkTheme())
    val controller = remember(density) {
        LyricsController(
            context = context,
            source = PreviewSceneSource(LiveSceneSource(graph.nowPlaying)) { vm.settings.value.wordHighlight },
            settings = { vm.settings.value },
            density = density,
            nightSignal = { dark },
            ambientSensor = { graph.ambientLight.level.value },
            textScale = 0.72f,
        )
    }
    Box(modifier.clip(RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))) {
        LyricsCanvas(controller, Modifier.fillMaxSize())
        Text(
            stringResource(R.string.preview),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Surface(color = CardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun <T> Grid(items: List<T>, columns: Int, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThemeTile(choice: Choice<BackgroundMode>, selected: Boolean, palette: dev.lyricdrive.ui.theme.LyricPalette, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(14.dp))
                .border(if (selected) BorderStroke(2.5.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(14.dp)),
        ) {
            ThemeBackground(choice.value, palette, Modifier.fillMaxSize(), animate = false)
            if (selected) CheckBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(
            stringResource(choice.label),
            color = if (selected) Color.White else Muted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun OptionCard(choice: Choice<AnimationStyle>, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        Column {
            Text(stringResource(choice.label), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            choice.hint?.let { Text(stringResource(it), color = Muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) CheckBadge(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Swatch(choice: Choice<HighlightColor>, selected: Boolean, albumPrimary: Int, onClick: () -> Unit) {
    val fill: Brush = when (choice.value) {
        HighlightColor.AUTO -> Brush.sweepGradient(listOf(Color(0xFFFF5E8A), Color(0xFF5CF6FF), Color(0xFFFFE3B0), Color(0xFFFF5E8A)))
        HighlightColor.COMPLEMENTARY -> Brush.linearGradient(listOf(Color(albumPrimary), Color(complementary(albumPrimary))))
        else -> SolidColor(Color(choice.value.argb!!))
    }
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape).padding(5.dp) else Modifier)
                .clip(CircleShape)
                .background(fill),
        )
        Text(
            stringResource(choice.label),
            color = if (selected) Color.White else Muted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun <T> Segmented(choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        choices.forEach { choice ->
            val isSelected = choice.value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .selectable(isSelected, role = Role.Tab) { onSelect(choice.value) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(choice.label),
                    color = if (isSelected) Color.Black else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    lineHeight = 15.sp,
                )
            }
        }
    }
    choices.firstOrNull { it.value == selected }?.hint?.let {
        Text(stringResource(it), color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(hint, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CheckBadge(modifier: Modifier) {
    Box(modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_check), null, tint = Color.Black, modifier = Modifier.size(14.dp))
    }
}

/** "+150 ms" / "−300 ms" / "0 ms". */
fun formatOffset(ms: Long): String = when {
    ms > 0 -> "+$ms ms"
    ms < 0 -> "−${-ms} ms"
    else -> "0 ms"
}

private fun routeLabel(route: OutputRoute): Int = when (route) {
    OutputRoute.ANDROID_AUTO -> R.string.route_android_auto
    OutputRoute.BLUETOOTH -> R.string.route_bluetooth
    OutputRoute.WIRED -> R.string.route_wired
    OutputRoute.SPEAKER -> R.string.route_speaker
}

/** What happened the last time Android Auto tried to open the full-screen app, copyable for support. */
@Composable
private fun CarDiagnosticsCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CarDiagnostics.report(context)) }
    Text(stringResource(R.string.diagnostics_title), color = Color.White, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.diagnostics_desc), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        report,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 30,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(10.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LyricDrive diagnostics", report))
        }) { Text(stringResource(R.string.diagnostics_copy), color = Color.White) }
        TextButton(onClick = { report = CarDiagnostics.report(context) }) { Text(stringResource(R.string.diagnostics_refresh), color = Color.White) }
        TextButton(onClick = { CarDiagnostics.clear(context); report = CarDiagnostics.report(context) }) { Text(stringResource(R.string.reset), color = Color.White) }
    }
}
