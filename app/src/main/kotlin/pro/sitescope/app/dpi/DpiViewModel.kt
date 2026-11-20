package pro.sitescope.app.dpi

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class AppRow(val pkg: String, val label: String, val system: Boolean)

class DpiViewModel(private val app: Application) : AndroidViewModel(app) {
    var cfg by mutableStateOf(DpiStore.load(app)); private set
    var note by mutableStateOf("")
    var groups by mutableStateOf(loadGroups()); private set
    private fun loadGroups(): Set<String> {
        val p = app.getSharedPreferences("dpi_ui", android.content.Context.MODE_PRIVATE)
        val saved = p.getString("groups", null)?.split(',')?.filter { id -> DpiSites.groups.any { it.id == id } }?.toSet()
        return saved ?: setOf("youtube")
    }
    fun toggleGroup(id: String) {
        if (searching) return
        groups = if (id in groups) groups - id else groups + id
        app.getSharedPreferences("dpi_ui", android.content.Context.MODE_PRIVATE).edit().putString("groups", groups.joinToString(",")).apply()
    }
    var custom by mutableStateOf("")
    var parallel by mutableStateOf(4f)
    var timeout by mutableStateOf(5f)
    var fullList by mutableStateOf(false)
    var saved by mutableStateOf(DpiSearch.loadSaved(app))
    var searching by mutableStateOf(false); private set
    var requestStart by mutableStateOf(false)
    var apps by mutableStateOf<List<AppRow>>(emptyList()); private set
    var appsLoading by mutableStateOf(false); private set
    var health by mutableStateOf<List<DpiProbe.Result>>(emptyList()); private set
    var checking by mutableStateOf(false); private set
    val search = DpiSearch(app)
    private var job: Job? = null

    fun update(n: DpiConfig) { cfg = n; DpiStore.save(app, n) }

    /** Делает строку активной; если обход уже работает — сразу перезапускает движок с ней. */
    fun applyLine(line: String) {
        cfg = DpiStore.applyLine(app, line)
        note = if (DpiRuntime.running) { DpiRuntime.reload(app); "Стратегия применена, обход перезапущен" } else "Стратегия применена"
    }

    fun applyPreset(p: DpiPresets.Preset) = applyLine(p.line)

    /** Применяет изменённые настройки (строка, SNI, список доменов) к уже работающему обходу. */
    fun restartNow() {
        if (DpiRuntime.running) { DpiRuntime.reload(app); note = "Обход перезапущен с новыми настройками" } else note = "Обход не запущен — настройки применятся при запуске"
    }

    /** Проверка «работает ли сейчас»: открывает выбранные сайты через запущенный обход и показывает результат по каждому. */
    fun runHealth() {
        if (checking) return
        val port = DpiRuntime.port
        if (port <= 0) { note = "Сначала запустите обход"; return }
        val chosen = (DpiSites.domainsOf(groups) + DpiSites.parse(custom)).distinct()
        val sites = DpiScoring.sample(chosen, 8) + DpiSites.control.first()
        checking = true
        health = emptyList()
        viewModelScope.launch {
            health = withContext(Dispatchers.IO) {
                val pool = Executors.newFixedThreadPool(sites.size.coerceAtLeast(1))
                try { sites.map { h -> pool.submit<DpiProbe.Result> { DpiProbe.https(h, port, 6000) } }.map { it.get() } } finally { pool.shutdownNow() }
            }
            checking = false
        }
    }

    /** Пробный запуск движка с текущей строкой: ловит неверные ключи до реального включения. */
    fun validateLine() {
        val line = cfg.effectiveLine()
        note = "Проверяю строку…"
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                val e = DpiEngine(app)
                try { e.start(DpiArgs.build(line, DpiEngine.freePort(), cfg.sni), 1500) } finally { e.stop() }
            }
            note = err ?: "Строка принята byedpi"
        }
    }

    fun startSearch(autoApply: Boolean) {
        if (searching) return
        val sites = (DpiSites.domainsOf(groups) + DpiSites.parse(custom)).distinct()
        val strategies = if (fullList) DpiStrategies.all else DpiStrategies.quick()
        searching = true
        job = viewModelScope.launch {
            try {
                search.run(DpiSearch.Config(sites, strategies, parallel.toInt(), timeout.toInt(), 8, cfg.sni))
                saved = search.results.value.filter { it.full }.ifEmpty { DpiSearch.loadSaved(app) }
                if (autoApply) {
                    val best = search.results.value.firstOrNull { it.full && it.controlOk && it.ok > 0 }
                    if (best != null) { applyLine(best.line); requestStart = true } else note = "Рабочая стратегия не найдена — запуск не выполнен"
                }
            } finally { searching = false }
        }
    }

    fun stopSearch() { job?.cancel() }

    fun loadApps(force: Boolean = false) {
        if ((apps.isNotEmpty() && !force) || appsLoading) return
        appsLoading = true
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                list.mapNotNull { r ->
                    val ai = r.activityInfo?.applicationInfo ?: return@mapNotNull null
                    if (ai.packageName == app.packageName) null
                    else AppRow(ai.packageName, r.loadLabel(pm).toString(), (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            }
            appsLoading = false
        }
    }

    fun toggleApp(pkg: String) {
        val n = if (pkg in cfg.apps) cfg.apps - pkg else cfg.apps + pkg
        update(cfg.copy(apps = n))
    }

    fun refreshSaved() { saved = DpiSearch.loadSaved(app) }
}
