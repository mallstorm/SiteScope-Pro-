package pro.sitescope.app.radio

internal data class StationKey(val rat: String, val mcc: String, val mnc: String, val area: Long, val cid: Long) {
    companion object {
        fun of(rat: String, mcc: String, mnc: String, area: Long?, cid: Long?): StationKey? {
            if (rat !in setOf("G", "W", "T", "L", "N", "C") || !mcc.matches(Regex("[0-9]{3}")) ||
                !mnc.matches(Regex("[0-9]{2,3}")) || area == null || area < 0 || cid == null || cid !in 0..68_719_476_735L) return null
            return StationKey(rat, mcc, mnc, area, cid)
        }
    }
}

internal fun RadioCell.stationKey() = StationKey.of(when (rat) {
    "LTE" -> "L"
    "NR" -> "N"
    "GSM" -> "G"
    "WCDMA" -> "W"
    "TD-SCDMA" -> "T"
    "CDMA" -> "C"
    else -> ""
}, mcc, mnc, area, cid)

internal fun Tower.stationKey() = StationKey.of(rat, mcc, mnc, area, cid)

enum class StationMarker { Serving, Secondary, Neighbor, Catalogue }

internal data class StationPosition(val tower: Tower, val online: Boolean = false, val samples: Int? = null)

internal data class StationOnMap(val id: String, val cell: RadioCell?, val position: StationPosition?, val marker: StationMarker, val fresh: Boolean = false) {
    val canDrawLink get() = fresh && (marker == StationMarker.Serving || marker == StationMarker.Secondary) && position != null
}

internal fun currentStationCells(frame: RadioFrame?): List<RadioCell> {
    if (frame == null) return emptyList()
    return frame.cells.sortedWith(compareBy<RadioCell> { !it.link.serving }
        .thenBy { it.sim != frame.activeDataSim }.thenBy { it.link.ordinal }.thenByDescending { it.measured })
        .distinctBy { listOf(it.sim, it.rat, it.mcc, it.mnc, it.area, it.cid, it.pci.takeIf { _ -> it.cid == null }) }
        .take(72)
}

internal fun localStation(key: StationKey, pci: Int?, candidates: List<Tower>): Tower? {
    val matches = candidates.filter { it.stationKey() == key && it.lat != null && it.lon != null && (pci == null || it.pci == null || it.pci == pci) }
    val precise = matches.filter { pci != null && it.pci == pci }.ifEmpty { matches }
    if (precise.map { it.lat to it.lon }.distinct().size != 1) return null
    return precise.minByOrNull { it.accuracy?.takeIf { accuracy -> accuracy > 0 } ?: Int.MAX_VALUE }
}

internal fun stationOnMap(cell: RadioCell, position: StationPosition?, now: Long): StationOnMap {
    val fresh = cell.measured > 0 && now - cell.measured in 0..60_000L
    val marker = when {
        !fresh || !cell.link.serving -> StationMarker.Neighbor
        cell.link == RadioLink.Secondary -> StationMarker.Secondary
        else -> StationMarker.Serving
    }
    return StationOnMap("radio:${cell.sim}:${cell.rat}:${cell.mcc}:${cell.mnc}:${cell.area}:${cell.cid ?: cell.pci}", cell, position, marker, fresh)
}
