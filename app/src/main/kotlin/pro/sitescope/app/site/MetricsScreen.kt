@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.site

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.DataStore
import pro.sitescope.app.SearchMetricsEngine
import pro.sitescope.app.SecureStore
import pro.sitescope.app.ui.*
import java.util.Locale

/** Как сайт видят поисковики: скорость по Google PageSpeed, показы и клики из Search Console, ИКС из Яндекс Вебмастера. */
@Composable
fun MetricsScreen(startUrl: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecureStore(context.applicationContext) }
    var url by remember { mutableStateOf(startUrl ?: "") }
    var psKey by remember { mutableStateOf("") }
    var gToken by remember { mutableStateOf("") }
    var gProperty by remember { mutableStateOf("") }
    var yToken by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(true) }
    var showKeys by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SearchMetricsEngine.Result?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            psKey = try { secrets.get("pagespeed_key") ?: "" } catch (e: Exception) { "" }
            gToken = try { secrets.get("google_token") ?: "" } catch (e: Exception) { "" }
            gProperty = try { secrets.get("google_property") ?: "" } catch (e: Exception) { "" }
            yToken = try { secrets.get("yandex_token") ?: "" } catch (e: Exception) { "" }
            if (url.isEmpty()) url = try { DataStore(context.applicationContext).audits().firstOrNull()?.target ?: "" } catch (e: Exception) { "" }
        }
    }

    fun run() = scope.launch {
        running = true; result = null
        result = withContext(Dispatchers.IO) {
            val config = SearchMetricsEngine.Config()
            config.targetUrl = url.trim(); config.pageSpeedKey = psKey.trim(); config.googleAccessToken = gToken.trim()
            config.googleProperty = gProperty.trim(); config.yandexAccessToken = yToken.trim()
            if (keep) try {
                secrets.put("pagespeed_key", config.pageSpeedKey); secrets.put("google_token", config.googleAccessToken)
                secrets.put("google_property", config.googleProperty); secrets.put("yandex_token", config.yandexAccessToken)
            } catch (e: Exception) { }
            val normalized = SearchMetricsEngine.normalizeUrl(config.targetUrl).lowercase(Locale.US)
            val audit = try { DataStore(context.applicationContext).audits().firstOrNull { it.host.isNotEmpty() && normalized.contains(it.host.lowercase(Locale.US)) } } catch (e: Exception) { null }
            try { SearchMetricsEngine.collect(config, audit) } catch (e: Exception) { null }
        }
        running = false
        if (result == null) toast(context, "Не удалось получить данные: проверьте интернет")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        Spacer(Modifier.height(8.dp))
        BigField(url, { url = it }, "Адрес сайта: example.ru", onGo = { if (!running && url.isNotBlank()) run() }, leading = Icons.Rounded.Language)
        Button(onClick = { run() }, enabled = !running && url.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (running) "Получаем данные…" else "Получить метрики") }
        TextButton(onClick = { showKeys = !showKeys }) { Text(if (showKeys) "Скрыть ключи доступа" else "Ключи доступа (необязательно)") }
        if (showKeys) SoftCard {
            Text("Скорость по Google работает и без ключа, но с ограничением по числу запросов. Данные Search Console и Яндекс Вебмастера доступны только с вашими токенами — они хранятся в защищённом хранилище телефона и уходят напрямую в Google и Яндекс.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecretField(psKey, { psKey = it }, "Ключ PageSpeed Insights")
            SecretField(gToken, { gToken = it }, "Токен Google Search Console")
            SecretField(gProperty, { gProperty = it }, "Ресурс в Search Console: sc-domain:example.ru", secret = false)
            SecretField(yToken, { yToken = it }, "Токен Яндекс Вебмастера")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = keep, onCheckedChange = { keep = it }); Text("Запомнить на этом телефоне", style = MaterialTheme.typography.bodyMedium) }
        }
        if (running) { Spacer(Modifier.height(10.dp)); LoadingCard("Спрашиваем Google и Яндекс", "Это может занять до минуты") }

        result?.let { r ->
            Spacer(Modifier.height(12.dp))
            SoftCard(color = tones.siteSoft) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(r.searchReadiness, size = 92.dp, stroke = 9.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Готовность к поиску", style = MaterialTheme.typography.titleMedium)
                        Text("Наша оценка: техническое состояние, индексируемость, защита и скорость. Это не позиция в выдаче.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SpeedCard("Скорость на телефоне", r.mobile)
            SpeedCard("Скорость на компьютере", r.desktop)

            SectionTitle("Google: показы и клики")
            val g = r.google
            if (!g.requested) HintCard("Добавьте токен Search Console в «Ключи доступа», чтобы видеть показы, клики и среднюю позицию.")
            else if (!g.available) ErrorCard(friendly(g.error))
            else SoftCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Клики", g.clicks.toLong().toString(), Modifier.weight(1f)); Stat("Показы", g.impressions.toLong().toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Кликают, %", String.format(Locale.US, "%.1f", g.ctr * 100), Modifier.weight(1f)); Stat("Средняя позиция", String.format(Locale.US, "%.1f", g.position), Modifier.weight(1f))
                }
                Text("${g.dateFrom} — ${g.dateTo} · ${g.property}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
            }

            SectionTitle("Яндекс")
            val y = r.yandex
            if (!y.requested) HintCard("Добавьте токен Яндекс Вебмастера, чтобы видеть ИКС, число страниц в поиске и найденные проблемы.")
            else if (!y.available) ErrorCard(friendly(y.error))
            else SoftCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("ИКС", if (y.sqi >= 0) y.sqi.toString() else "—", Modifier.weight(1f)); Stat("Страниц в поиске", if (y.searchablePages >= 0) y.searchablePages.toString() else "—", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Фатальных проблем", if (y.fatalProblems >= 0) y.fatalProblems.toString() else "—", Modifier.weight(1f)); Stat("Критичных", if (y.criticalProblems >= 0) y.criticalProblems.toString() else "—", Modifier.weight(1f))
                }
                Text((if (y.verified) "Права подтверждены" else "Права не подтверждены") + " · " + y.hostUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun friendly(error: String): String = when {
    error.contains("401") || error.contains("403") -> "Доступ отклонён: токен устарел или у него нет прав на этот сайт."
    error.contains("429") -> "Слишком много запросов. Подождите минуту или добавьте свой ключ."
    error.isBlank() -> "Данных нет."
    else -> "Не удалось получить данные: $error"
}

@Composable
private fun HintCard(text: String) = SoftCard(color = MaterialTheme.colorScheme.surfaceContainer) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SecretField(value: String, onValue: (String) -> Unit, label: String, secret: Boolean = true) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label, maxLines = 1) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(14.dp), visualTransformation = if (secret && value.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
}

@Composable
private fun SpeedCard(title: String, p: SearchMetricsEngine.PageSpeed) {
    SectionTitle(title)
    if (!p.available) { ErrorCard(friendly(p.error)); return }
    SoftCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniScore("Скорость", p.performance); MiniScore("Удобство", p.accessibility); MiniScore("Качество", p.bestPractices); MiniScore("SEO", p.seo)
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Vital("Главное на экране появляется за", p.lcpMs, 2500.0, 4000.0, true)
        Vital("Отклик на нажатие", p.inpMs, 200.0, 500.0, true)
        Vital("Сдвиги страницы при загрузке", p.cls, 0.1, 0.25, false)
        Vital("Первое содержимое", p.fcpMs, 1800.0, 3000.0, true)
        Vital("Ответ сервера", p.ttfbMs, 800.0, 1800.0, true)
        Vital("Страница «зависает» на", p.tbtMs, 200.0, 600.0, true)
        val verdict = when (p.fieldAssessment) { "FAST" -> "У реальных посетителей сайт быстрый"; "AVERAGE" -> "У реальных посетителей скорость средняя"; "SLOW" -> "У реальных посетителей сайт медленный"; else -> "" }
        if (verdict.isNotEmpty()) Text(verdict, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun MiniScore(label: String, score: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (score >= 0) ScoreRing(score, size = 58.dp, stroke = 6.dp) else Text("—", style = MaterialTheme.typography.titleLarge, modifier = Modifier.height(58.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Vital(label: String, value: Double, good: Double, bad: Double, millis: Boolean) {
    if (value < 0) return
    val color: Color = when { value <= good -> tones.good; value <= bad -> tones.warn; else -> tones.bad }
    val text = if (!millis) String.format(Locale.US, "%.2f", value) else if (value >= 1000) String.format(Locale.US, "%.1f с", value / 1000) else "${value.toInt()} мс"
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color); Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
    }
}
