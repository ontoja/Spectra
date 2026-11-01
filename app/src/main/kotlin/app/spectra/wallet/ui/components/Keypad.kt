package app.spectra.wallet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spectra.wallet.ui.theme.Motion
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.theme.spectraTween

enum class PinState { IDLE, ERROR, OK }

@Composable
fun PinDots(filled: Int, state: PinState, length: Int = 6) {
    val c = Spectra.colors
    Row(
        Modifier.glassSurface(RoundedCornerShape(26.dp)).padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics { contentDescription = "Wpisano $filled z $length cyfr"; liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(length) { i ->
            val target = when { state == PinState.ERROR -> c.negativeStrong; state == PinState.OK -> c.positive; i < filled -> c.primary; else -> Color.Transparent }
            val color by animateColorAsState(target, spectraTween(Motion.FAST), label = "dot")
            Box(Modifier.size(14.dp).clip(CircleShape).background(color)
                .then(if (target == Color.Transparent) Modifier.border(2.dp, c.text.copy(alpha = .22f), CircleShape) else Modifier))
        }
    }
}

sealed interface KeypadKey {
    data class Digit(val d: Char) : KeypadKey
    data object Delete : KeypadKey
    data object Biometric : KeypadKey
    data object Decimal : KeypadKey
    data object Empty : KeypadKey
}

/**
 * Custom numeric keypad from the references. [glass] renders 76dp glass circles (PIN); otherwise plain keys (amount entry).
 */
@Composable
fun NumericKeypad(onKey: (KeypadKey) -> Unit, leftKey: KeypadKey = KeypadKey.Empty, glass: Boolean = true, keySize: Dp = 76.dp, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
    val haptics = LocalHapticFeedback.current
    val press = { k: KeypadKey -> if (enabled) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(k) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (glass) 14.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(if (glass) Modifier else Modifier.fillMaxWidth(), horizontalArrangement = if (glass) Arrangement.spacedBy(30.dp) else Arrangement.SpaceEvenly) {
                row.forEach { d -> Key(KeypadKey.Digit(d), glass, keySize, press) }
            }
        }
        Row(if (glass) Modifier else Modifier.fillMaxWidth(), horizontalArrangement = if (glass) Arrangement.spacedBy(30.dp) else Arrangement.SpaceEvenly) {
            Key(leftKey, glass, keySize, press)
            Key(KeypadKey.Digit('0'), glass, keySize, press)
            Key(KeypadKey.Delete, glass, keySize, press)
        }
    }
}

@Composable
private fun Key(key: KeypadKey, glass: Boolean, size: Dp, onPress: (KeypadKey) -> Unit) {
    val c = Spectra.colors
    val label = when (key) {
        is KeypadKey.Digit -> key.d.toString()
        KeypadKey.Delete -> "Usuń cyfrę"
        KeypadKey.Biometric -> "Odblokuj biometrią"
        KeypadKey.Decimal -> "Przecinek"
        KeypadKey.Empty -> ""
    }
    val isDigit = key is KeypadKey.Digit
    val base = if (glass) Modifier.size(size) else Modifier.width(96.dp).height(56.dp)
    if (key == KeypadKey.Empty) { Box(base); return }
    Box(
        base.then(if (glass && isDigit) Modifier.glassSurface(CircleShape) else Modifier).clip(if (glass) CircleShape else RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onPress(key) }.semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is KeypadKey.Digit -> Text(key.d.toString(), style = if (glass) Spectra.type.keypad else Spectra.type.keypad.copy(fontSize = Spectra.type.headline.fontSize), color = c.text)
            KeypadKey.Decimal -> Text(",", style = Spectra.type.keypad.copy(fontSize = Spectra.type.headline.fontSize), color = c.text)
            KeypadKey.Delete -> Icon(SpectraIcons.Backspace, null, Modifier.size(26.dp), tint = c.text)
            KeypadKey.Biometric -> Icon(SpectraIcons.FaceScan, null, Modifier.size(26.dp), tint = c.primary)
            KeypadKey.Empty -> Unit
        }
    }
}
