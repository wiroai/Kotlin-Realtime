package ai.wiro.realtime.ui.shared

import ai.wiro.realtime.models.ModelFieldDefinition
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun ModelParameterForm(
    fields: List<ModelFieldDefinition>,
    value: (String) -> String,
    onValueChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    optionTitle: (String) -> String = { it },
    enabled: Boolean = true,
) {
    fields.forEach { field ->
        ModelParameterField(
            field = field,
            text = value(field.id),
            onTextChange = { onValueChange(field.id, it) },
            optionTitle = optionTitle,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelParameterField(
    field: ModelFieldDefinition,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    optionTitle: (String) -> String = { it },
    enabled: Boolean = true,
) {
    val tag = Modifier.testTag("field-${field.id}")
    when (val control = field.control) {
        ModelFieldControl.Picker -> {
            var expanded by remember { mutableStateOf(false) }
            val selected = field.options
                .firstOrNull { it.value == text }
                ?.value
                ?: field.options.firstOrNull()?.value
                ?: text
            ExposedDropdownMenuBox(
                expanded = expanded && enabled,
                onExpandedChange = { expanded = it && enabled },
                modifier = modifier.then(tag),
            ) {
                OutlinedTextField(
                    value = optionTitle(selected),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(field.label) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = expanded,
                        )
                    },
                    modifier = Modifier
                        .menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                        )
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded && enabled,
                    onDismissRequest = { expanded = false },
                ) {
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionTitle(option.value)) },
                            onClick = {
                                onTextChange(option.value)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        is ModelFieldControl.TextField -> {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                label = { Text(field.label) },
                minLines = if (control.isMultiline) 4 else 1,
                maxLines = if (control.isMultiline) 6 else 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (control.keyboard) {
                        ModelFieldKeyboard.Number -> KeyboardType.Number
                        ModelFieldKeyboard.Decimal -> KeyboardType.Decimal
                        ModelFieldKeyboard.Default -> KeyboardType.Text
                    },
                ),
                modifier = modifier.then(tag),
            )
        }

        is ModelFieldControl.Toggle -> {
            androidx.compose.foundation.layout.Row(
                modifier = modifier.then(tag),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = field.label,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = text == control.on,
                    onCheckedChange = { checked ->
                        onTextChange(if (checked) control.on else control.off)
                    },
                    enabled = enabled,
                )
            }
        }
    }
}
