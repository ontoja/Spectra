package app.spectra.wallet.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.spectra.wallet.ui.theme.Radii
import app.spectra.wallet.ui.theme.Spectra

/** Glass text field. [sensitive] disables suggestions/learning (recovery phrases, addresses). */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String = placeholder,
    singleLine: Boolean = true,
    minLines: Int = 1,
    sensitive: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Spectra.colors
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).glassSurface(RoundedCornerShape(Radii.field + 4.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Spectra.type.body, color = c.textDisabled)
            BasicTextField(value, onValueChange, Modifier.fillMaxWidth().semantics { contentDescription = label }, textStyle = Spectra.type.body.copy(color = c.text),
                singleLine = singleLine, minLines = minLines, cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = !sensitive, keyboardType = if (sensitive && keyboardType == KeyboardType.Text) KeyboardType.Password else keyboardType, imeAction = ImeAction.Done))
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}
