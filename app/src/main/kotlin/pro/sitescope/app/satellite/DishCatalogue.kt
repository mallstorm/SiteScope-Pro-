package pro.sitescope.app.satellite

import java.util.Locale

data class DishEntry(val name: String, val orbital: Double, val channel: String = "", val provider: String = "", val tuning: String = "")
fun orbitalLabel(o: Double): String = "%.1f° %s".format(Locale.ROOT, kotlin.math.abs(o), if (o < 0) "W" else "E")

object DishCatalogue {
    // Orbital slots are independent of the spacecraft or bouquet presently occupying them.
    val slots = listOf(-30.0,-22.0,-15.0,-8.0,-5.0,-0.8,4.8,7.0,9.0,13.0,16.0,19.2,23.5,28.2,31.5,36.0,39.0,42.0,45.0,46.0,49.0,52.0,53.0,55.0,56.0,60.0,66.0,68.5,70.5,75.0,80.0,85.0,90.0,96.5,103.0,140.0)
        .map { DishEntry(orbitalLabel(it), it) }

    fun parse(lines: List<String>): List<DishEntry> {
        if (lines.firstOrNull()?.trim()?.removePrefix("\uFEFF") == "eDVB services /4/") return enigma(lines)
        return lines.mapNotNull { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.startsWith('#') || line.isEmpty()) return@mapNotNull null
            val fields = line.split(';')
            val position = fields.getOrNull(1)?.trim()?.replace(',', '.')?.toDoubleOrNull()
            if (position == null || position !in -180.0..180.0 || fields[0].isBlank()) null
            else DishEntry(fields[0].trim(), position, fields.getOrNull(2).orEmpty(), fields.getOrNull(3).orEmpty(), fields.getOrNull(4).orEmpty())
        }.distinct()
    }

    private fun enigma(lines: List<String>): List<DishEntry> {
        data class Transponder(val pos: Double, val parameters: String)
        val transponders = mutableMapOf<String, Transponder>()
        var section = ""
        var key = ""
        var i = 0
        val channels = mutableListOf<DishEntry>()
        while (i < lines.size) {
            val line = lines[i++].trim()
            when (line) {
                "transponders", "services" -> { section = line; continue }
                "end" -> { section = ""; continue }
            }
            if (section == "transponders") {
                if (line.count { it == ':' } == 2 && !line.contains(' ')) key = line.lowercase()
                else if (line.startsWith("s ") && key.isNotEmpty()) {
                    val f = line.drop(2).split(':')
                    val raw = f.getOrNull(4)?.toIntOrNull() ?: continue
                    val pos = (if (raw > 1800) raw - 3600 else raw) / 10.0
                    val freq = f[0].toIntOrNull() ?: continue
                    val rate = f[1].toIntOrNull() ?: continue
                    val pol = listOf("H","V","L","R").getOrNull(f[2].toIntOrNull() ?: -1).orEmpty()
                    if (pos in -180.0..180.0) transponders[key] = Transponder(pos, "${freq/1000.0} МГц · $pol · ${rate/1000} ксимв/с")
                }
            } else if (section == "services" && i + 1 < lines.size) {
                val f = line.lowercase().split(':')
                if (f.size < 5) continue
                val name = lines[i++].trim()
                val meta = lines[i++]
                val t = transponders[f.subList(1,4).joinToString(":")] ?: continue
                val provider = meta.split(',').firstOrNull { it.startsWith("p:") }?.drop(2).orEmpty()
                channels += DishEntry(orbitalLabel(t.pos), t.pos, name, provider, t.parameters)
            }
        }
        return channels.distinct()
    }
}

