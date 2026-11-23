package pro.sitescope.app.keys

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Живые счётчики группы ключей: найдено → проверено → рабочих. */
class StageStat {
    val found = AtomicInteger(); val checked = AtomicInteger(); val alive = AtomicInteger(); val slow = AtomicInteger()
    fun view() = StatView(found.get(), checked.get(), alive.get(), slow.get())
}

data class StatView(val found: Int = 0, val checked: Int = 0, val alive: Int = 0, val slow: Int = 0)

class PipelineConfig(
    val tcpThreads: Int, val tcpTimeoutMs: Int, val xrayThreads: Int, val singThreads: Int,
    val proxyThreads: Int, val proxyTimeoutMs: Int, val proxyLimit: Int, val tls: Boolean
)

/**
 * Конвейер «сбор → проверка»: найденный адрес уходит на проверку сразу, не дожидаясь конца сбора.
 * Дорожки по типам, у каждой свои потоки:
 *  • TCP-предпроверка (общая для Xray и sing-box): один раз на уникальный host:port;
 *  • Xray-ключи → реальный HTTPS через ядро Xray (до 16 одновременно);
 *  • sing-box (Hysteria2, TUIC, WireGuard, OpenVPN, AnyTLS) → HTTPS через отдельные процессы;
 *  • прокси SOCKS/HTTP → запрос через сам прокси (выходной IP, HTTPS, анонимность).
 * Между этапами нет барьеров: освободившийся поток сразу берёт следующий адрес.
 */
class KeyPipeline(
    private val context: Context,
    private val cfg: PipelineConfig,
    private val say: (String) -> Unit,
    private val onSettled: (KeyItem) -> Unit
) {
    val byOrigin = ConcurrentHashMap<String, StageStat>()
    val byType = ConcurrentHashMap<String, StageStat>()
    val found = AtomicInteger(); val settled = AtomicInteger(); val alive = AtomicInteger(); val slow = AtomicInteger(); val deferred = AtomicInteger()

    private val proxyRouted = AtomicInteger()
    private val pre = Channel<KeyItem>(Channel.UNLIMITED)
    private val xrayDeep = Channel<KeyItem>(Channel.UNLIMITED)
    private val singDeep = Channel<KeyItem>(Channel.UNLIMITED)
    private val proxyIn = Channel<KeyItem>(Channel.UNLIMITED)

    private class Probe(val ms: Long, val ip: String)
    private val tcpCache = ConcurrentHashMap<String, CompletableDeferred<Probe>>()
    private val nativePool = Executors.newCachedThreadPool { r -> Thread(r, "xray-native").apply { isDaemon = true } }
    private val leaked = AtomicInteger()
    @Volatile private var vpnWarned = false

    /** Отдать адрес на проверку (можно вызывать из любого потока). */
    fun route(k: KeyItem, origin: String) {
        k.origin = origin
        byOrigin.getOrPut(origin) { StageStat() }.found.incrementAndGet()
        byType.getOrPut(k.type) { StageStat() }.found.incrementAndGet()
        found.incrementAndGet()
        if (k.proxy) {
            if (proxyRouted.incrementAndGet() > cfg.proxyLimit) {
                if (deferred.incrementAndGet() == 1) say("Прокси сверх лимита ждут в резерве")
                return
            }
            proxyIn.trySend(k)
        } else pre.trySend(k)
    }

    private fun settle(k: KeyItem) {
        val ok = k.alive
        val isSlow = !ok && k.ms >= KeyQualityPolicy.MAX_WORKING_MS
        for (s in listOfNotNull(byOrigin[k.origin], byType[k.type])) {
            s.checked.incrementAndGet()
            if (ok) s.alive.incrementAndGet()
            if (isSlow) s.slow.incrementAndGet()
        }
        settled.incrementAndGet()
        if (ok) alive.incrementAndGet()
        if (isSlow) slow.incrementAndGet()
        onSettled(k)
    }

    private fun fail(k: KeyItem, note: String) {
        k.ms = -2; k.checkedAt = System.currentTimeMillis()
        k.validation = XrayKeyValidator.INCONCLUSIVE; k.validationNote = note
        settle(k)
    }

    /** Запускает потоки проверки, вызывает [feed] (сбор) и ждёт, пока вся очередь будет проверена. */
    suspend fun execute(feed: suspend (route: (KeyItem, String) -> Unit) -> Unit) = coroutineScope {
        val first = ArrayList<Job>(); val second = ArrayList<Job>()
        repeat(cfg.tcpThreads) { first += launch(Dispatchers.IO) { for (k in pre) preflight(k) } }
        repeat(cfg.proxyThreads) { first += launch(Dispatchers.IO) { for (k in proxyIn) proxyCheck(k) } }
        repeat(cfg.xrayThreads) { second += launch(Dispatchers.IO) { for (k in xrayDeep) xrayCheck(k) } }
        repeat(cfg.singThreads) { second += launch(Dispatchers.IO) { for (k in singDeep) singCheck(k) } }
        try {
            feed { k, origin -> route(k, origin) }
        } finally {
            pre.close(); proxyIn.close()
        }
        first.forEach { it.join() }
        xrayDeep.close(); singDeep.close()
        second.forEach { it.join() }
        nativePool.shutdown()
    }

    private suspend fun preflight(k: KeyItem) {
        try {
            ensureActive()
            val timeout = cfg.tcpTimeoutMs
            if (k.udp) {
                KeyChecker.quickCheck(k, timeout)
            } else {
                // Несколько ключей на одном host:port проверяются одним TCP-подключением.
                val ep = "tcp|" + k.host.lowercase() + ":" + k.port
                val mine = CompletableDeferred<Probe>()
                val other = tcpCache.putIfAbsent(ep, mine)
                if (other != null) {
                    val p = other.await()
                    k.ms = p.ms; k.ip = p.ip; k.checkedAt = System.currentTimeMillis()
                } else {
                    try {
                        val r = withTimeoutOrNull(timeout * 4L + 4000L) { KeyChecker.quickCheck(k, timeout) }
                        if (r == null) { k.ms = -2; k.checkedAt = System.currentTimeMillis() }
                    } finally { mine.complete(Probe(k.ms, k.ip)) }
                }
            }
            val singKey = k.type in SingBoxConfig.types
            val pass = if (k.udp) singKey && k.ms == -3L else KeyQualityPolicy.fastEnough(k.ms)
            if (!pass) { settle(k); return }
            if (singKey) singDeep.trySend(k) else xrayDeep.trySend(k)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { fail(k, "Ошибка предпроверки") }
    }

    private suspend fun proxyCheck(k: KeyItem) {
        try {
            ensureActive()
            val done = withTimeoutOrNull(cfg.proxyTimeoutMs * 6L + 6000L) { KeyChecker.check(k, cfg.proxyTimeoutMs, cfg.tls) }
            if (done == null) { k.ms = -2; k.checkedAt = System.currentTimeMillis() }
            settle(k)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { fail(k, "Ошибка проверки прокси") }
    }

    private suspend fun xrayCheck(k: KeyItem) {
        try {
            ensureActive()
            val r = nativeDelay(k)
            k.validation = r.status; k.validationMs = r.delayMs
            k.validationAt = System.currentTimeMillis(); k.validationNote = r.reason
            if (!vpnWarned && r.reason.contains("остановите VPN", ignoreCase = true)) {
                vpnWarned = true; say("Включён VPN SiteScope: Xray-ключи нельзя проверить. Выключите его")
            }
            settle(k)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { fail(k, "Ошибка проверки Xray") }
    }

    private suspend fun singCheck(k: KeyItem) {
        try {
            ensureActive()
            val r = SingBoxValidator.check(context, k.link, 4500)
            k.validation = r.status; k.validationMs = r.delayMs
            k.validationAt = System.currentTimeMillis(); k.validationNote = r.reason
            if (!vpnWarned && r.reason.contains("VPN", ignoreCase = true) && r.status == XrayKeyValidator.INCONCLUSIVE) {
                vpnWarned = true; say("Включён VPN: ключи sing-box нельзя проверить. Отключите его")
            }
            settle(k)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { fail(k, "Ошибка проверки sing-box") }
    }

    /** Нативный вызов Xray с защитой: зависший вызов не блокирует поток навсегда. */
    private suspend fun nativeDelay(k: KeyItem): XrayKeyValidator.Result {
        if (leaked.get() >= 10) return XrayKeyValidator.Result(XrayKeyValidator.INCONCLUSIVE, -1, "Нативная проверка Xray зависла — перезапустите приложение")
        val fut = nativePool.submit(Callable { XrayKeyValidator.checkNativeDelay(context, k.link) })
        return try {
            runInterruptible(Dispatchers.IO) { fut.get(14, TimeUnit.SECONDS) }
        } catch (e: TimeoutException) {
            fut.cancel(true); leaked.incrementAndGet()
            XrayKeyValidator.Result(XrayKeyValidator.INCONCLUSIVE, -1, "Таймаут проверки Xray")
        } catch (e: ExecutionException) {
            XrayKeyValidator.Result(XrayKeyValidator.INCONCLUSIVE, -1, "Ошибка проверки Xray")
        } catch (e: CancellationException) {
            fut.cancel(true); throw e
        }
    }
}
