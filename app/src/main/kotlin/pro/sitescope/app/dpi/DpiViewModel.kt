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

class AppRow(val pkg: String, val label: String, val system: Boolean)

class DpiViewModel(private val app: Application) : AndroidViewModel(app) {
    var cfg by mutableStateOf(DpiStore.load(app)); private set
    var note by mutableStateOf("")
    var groups by mutableStateOf(setOf("youtube", "discord", "telegram"))
    var custom by mutableStateOf("")
    var parallel by mutableStateOf(4f)
    var timeout by mutableStateOf(5f)
    var fullList by mutableStateOf(false)
    var saved by mutableStateOf(DpiSearch.loadSaved(app))
    var searching by mutableStateOf(false); private set
    var requestStart by mutableStateOf(false)
    var apps by mutableStateOf<List<AppRow>>(emptyList()); private set
    var appsLoading by mutableStateOf(false); private set
    val search = DpiSearch(app)
    private var job: Job? = null

    fun update(n: DpiConfig) { cfg = n; DpiStore.save(app, n) }

    fun applyLine(line: String) { cfg = DpiStore.applyLine(app, line); note = "Стратегия применена" }

    fun startSearch(autoApply: Boolean) {
        if (searching) return
        val sites = (DpiSites.domainsOf(groups) + DpiSites.parse(custom)).distinct()
        val strategies = if (fullList) DpiStrategies.all else DpiStrategies.quick(48)
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
