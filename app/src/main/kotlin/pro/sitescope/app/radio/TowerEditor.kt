package pro.sitescope.app.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun TowerEditor(initial: Tower?, onSave: (Tower) -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    val labels = listOf("Тип G / W / L / N / C / T", "MCC", "MNC", "LAC / TAC", "CID / NCI", "PCI / PSC", "Канал",
        "Широта", "Долгота", "Точность, м", "Название", "Азимут сектора, °")
    val fields = remember(initial) {
        initial?.clf()?.split(';') ?: listOf("L", "", "", "", "", "", "", "", "", "", "", "")
    }
    val values = remember(initial) { fields.map { mutableStateOf(it) } }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "Добавить станцию" else "Станция") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEachIndexed { index, label ->
                    OutlinedTextField(values[index].value, { values[index].value = it.replace(';', ',') },
                        label = { Text(label) }, singleLine = true)
                }
                if (error) Text("Проверьте обязательные поля MCC, MNC, LAC/TAC, CID и координаты.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton({
            val tower = parseTower(values.joinToString(";") { it.value.trim() })
            if (tower == null) error = true else onSave(tower)
        }) { Text("Сохранить") } },
        dismissButton = { Row {
            if (onDelete != null) TextButton(onDelete) { Text("Удалить") }
            TextButton(onClose) { Text("Отмена") }
        } }
    )
}
