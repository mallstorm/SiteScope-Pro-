package pro.sitescope.app.net

import org.json.JSONObject
import pro.sitescope.app.ExposureStore
import pro.sitescope.app.ScannerEngine
import java.util.Locale

internal object ExposureWatch {
    enum class State { Open, Refused, NoResponse }

    data class Endpoint(
        val host: String,
        val address: String,
        val port: Int,
        val source: String,
        val transport: String,
        val external: Boolean,
    )

    data class Observation(
        val endpoint: Endpoint,
        val title: String,
        val state: State,
        val time: Long,
        val run: JSONObject,
    ) {
        fun matches(terms: List<String>): Boolean {
            val fields = listOf(title, endpoint.host, endpoint.address, endpoint.source, endpoint.port.toString())
            return terms.all { term ->
                val value = term.substringAfter(':')
                when (term.substringBefore(':', "").lowercase(Locale.ROOT)) {
                    "host" -> value.isNotEmpty() && endpoint.host.contains(value, ignoreCase = true)
                    "ip" -> value.isNotEmpty() && endpoint.address.equals(value, ignoreCase = true)
                    "port" -> endpoint.port == value.toIntOrNull()
                    else -> fields.any { it.contains(term, ignoreCase = true) }
                }
            }
        }
    }

    data class Event(val current: Observation, val previous: Observation)
    data class Timeline(val latest: List<Observation>, val events: List<Event>)

    fun summarize(targets: List<ExposureStore.Target>, runs: List<JSONObject>): Timeline {
        val saved = targets.groupBy { it.host.lowercase(Locale.ROOT) }.mapValues { (_, profiles) ->
            buildMap {
                for (profile in profiles) {
                    for (port in ScannerEngine.parsePorts(profile.ports, 64)) {
                        putIfAbsent(port, profile.name.ifBlank { profile.host })
                    }
                }
            }
        }
        val latest = mutableMapOf<Endpoint, Observation>()
        val definitive = mutableMapOf<Endpoint, Observation>()
        val events = mutableListOf<Event>()
        val chronological = runs.sortedWith(compareBy({ it.optLong("finishedAt", it.optLong("time")) }, { it.optString("id") }))
        for (run in chronological) {
            val host = run.optString("host").lowercase(Locale.ROOT)
            val ports = saved[host] ?: continue
            if (run.optBoolean("cancelled") || run.optString("error").isNotEmpty()) continue
            val rows = run.optJSONArray("results") ?: continue
            if (run.has("total") && run.optInt("total") != rows.length()) continue
            val source = run.optString("source")
            val transport = run.optString("transport")
            val time = run.optLong("finishedAt", run.optLong("time"))
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val port = row.optInt("port")
                val title = ports[port] ?: continue
                val address = row.optString("address").lowercase(Locale.ROOT)
                if (address.isEmpty()) continue
                val state = when (row.optString("state")) {
                    "open" -> State.Open
                    "refused" -> State.Refused
                    "timeout", "network_error", "unresolved" -> State.NoResponse
                    else -> continue
                }
                val external = transport == "cellular" && run.optBoolean("externalPoint") &&
                    ExposureStore.publicAddress(address)
                val endpoint = Endpoint(host, address, port, source, transport, external)
                val current = Observation(endpoint, title, state, time, run)
                latest[endpoint] = current
                if (state == State.NoResponse) continue
                val previous = definitive.put(endpoint, current)
                if (previous != null && previous.state != state) events += Event(current, previous)
            }
        }
        return Timeline(latest.values.sortedByDescending { it.time }, events.asReversed())
    }
}
