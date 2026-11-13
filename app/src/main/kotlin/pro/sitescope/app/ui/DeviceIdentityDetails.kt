package pro.sitescope.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import pro.sitescope.app.DeviceIdentity

@Composable
fun DeviceIdentityDetails(identity: DeviceIdentity, tlsTrust: String = "not_applicable") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Распознавание оборудования", style = MaterialTheme.typography.titleMedium)
        if (identity.conflicting) Text("Получены несовпадающие признаки. Производитель и модель не выбраны.", color = MaterialTheme.colorScheme.error)
        InfoRow("Производитель", identity.manufacturer.ifEmpty { "Не определён" })
        InfoRow("Семейство", identity.family.ifEmpty { "Не определено" })
        InfoRow("Модель", identity.model.ifEmpty { "Не определена" })
        if (identity.deviceType.isNotEmpty()) InfoRow("Тип", identity.deviceType)
        InfoRow("Уверенность в оборудовании", identity.confidenceLabel())
        if (identity.observedAt > 0) InfoRow("Получено", SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(identity.observedAt)))
        if (tlsTrust == "not_checked") InfoRow("Доверие сертификату TLS", "Не проверялось")
        if (identity.findings.isEmpty()) Text("Ответ не содержит поддерживаемых признаков. Один номер порта не определяет производителя или модель.", style = MaterialTheme.typography.bodySmall)
        identity.findings.forEach { f ->
            val field = when (f.field) {
                "manufacturer" -> "Производитель"; "family" -> "Семейство"; "model" -> "Модель"
                "deviceType" -> "Тип"; "hardware" -> "Аппаратный идентификатор"; else -> f.field
            }
            Text("$field: ${f.value}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            val confidence = when (f.confidence) { "high" -> "высокая"; "medium" -> "средняя"; else -> "низкая" }
            Text("${f.source} · уверенность: $confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.detail.isNotEmpty()) Text(f.detail, style = MaterialTheme.typography.bodySmall)
        }
        Text("Это признаки из ответов сервиса, а не удостоверение подлинности устройства.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}
