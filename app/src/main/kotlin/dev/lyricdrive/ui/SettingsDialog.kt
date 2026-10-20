package dev.lyricdrive.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricdrive.R
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.data.settings.AnimationStyle
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.data.settings.LyricSettings
import dev.lyricdrive.data.settings.NightMode

private class Option<T>(val value: T, @StringRes val label: Int, @StringRes val description: Int? = null)

private val WORD_OPTIONS = listOf(
    Option(WordHighlight.AUTO, R.string.word_auto, R.string.word_auto_desc),
    Option(WordHighlight.TIMED_ONLY, R.string.word_timed, R.string.word_timed_desc),
    Option(WordHighlight.OFF, R.string.word_off, R.string.word_off_desc),
)

private val ANIMATION_OPTIONS = listOf(
    Option(AnimationStyle.FLUID, R.string.anim_fluid, R.string.anim_fluid_desc),
    Option(AnimationStyle.KARAOKE, R.string.anim_karaoke, R.string.anim_karaoke_desc),
    Option(AnimationStyle.FOCUS, R.string.anim_focus, R.string.anim_focus_desc),
    Option(AnimationStyle.BOUNCY, R.string.anim_bouncy, R.string.anim_bouncy_desc),
    Option(AnimationStyle.MINIMAL, R.string.anim_minimal, R.string.anim_minimal_desc),
)

private val BACKGROUND_OPTIONS = listOf(
    Option(BackgroundMode.ALBUM_FLUID, R.string.bg_album_fluid),
    Option(BackgroundMode.ALBUM_ART, R.string.bg_album_art),
    Option(BackgroundMode.ALBUM_GRADIENT, R.string.bg_album_gradient),
    Option(BackgroundMode.AURORA, R.string.bg_aurora),
    Option(BackgroundMode.SUNSET, R.string.bg_sunset),
    Option(BackgroundMode.OCEAN, R.string.bg_ocean),
    Option(BackgroundMode.BLACK, R.string.bg_black),
)

private val NIGHT_OPTIONS = listOf(
    Option(NightMode.AUTO, R.string.night_auto, R.string.night_auto_desc),
    Option(NightMode.ALWAYS, R.string.night_always),
    Option(NightMode.NEVER, R.string.night_never),
)

@Composable
fun SettingsDialog(settings: LyricSettings, onChange: ((LyricSettings) -> LyricSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section(R.string.section_word, WORD_OPTIONS, settings.wordHighlight) { v -> onChange { it.copy(wordHighlight = v) } }
                Section(R.string.section_animation, ANIMATION_OPTIONS, settings.animationStyle) { v -> onChange { it.copy(animationStyle = v) } }
                Section(R.string.section_background, BACKGROUND_OPTIONS, settings.backgroundMode) { v -> onChange { it.copy(backgroundMode = v) } }
                Section(R.string.section_night, NIGHT_OPTIONS, settings.nightMode) { v -> onChange { it.copy(nightMode = v) } }
            }
        },
    )
}

@Composable
private fun <T> Section(@StringRes title: Int, options: List<Option<T>>, selected: T, onSelect: (T) -> Unit) {
    Text(
        stringResource(title),
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    options.forEach { option ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .selectable(selected = option.value == selected, role = Role.RadioButton) { onSelect(option.value) }
                .heightIn(min = 48.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option.value == selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(option.label))
                option.description?.let {
                    Text(stringResource(it), fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f))
                }
            }
        }
    }
    Spacer(Modifier.size(4.dp))
}
