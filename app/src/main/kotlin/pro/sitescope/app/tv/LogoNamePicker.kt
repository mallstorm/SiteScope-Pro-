package pro.sitescope.app.tv

/** Выбор названия канала из того, что прочитал OCR (чистая логика, покрыта тестами). */
object LogoNamePicker {
    class Reading(val text: String, val confidence: Int, val region: String)

    fun clean(raw: String): String =
        raw.replace('\n', ' ').filter { it.isLetterOrDigit() || it == '+' || it == '-' || it == '!' || it == '&' || it == '.' || it == ' ' }
            .replace(Regex("\\s+"), " ").trim().trim('-', '.')

    /**
     * Выбор названия из прочитанного. Известный канал принимается при умеренной уверенности (в том числе с опечаткой в одну букву);
     * произвольный текст — только если его прочитали минимум дважды и уверенность высокая. Иначе — пусто.
     */
    fun pickName(readings: List<Reading>): String {
        val good = readings.map { Reading(clean(it.text), it.confidence, it.region) }.filter { it.text.length >= 2 }
        val scores = HashMap<String, Int>()
        for (r in good) {
            val known = MaskSearch.canonicalFuzzy(r.text) ?: continue
            if (r.confidence >= 45) scores.merge(known, r.confidence) { a, b -> a + b }
        }
        scores.maxByOrNull { it.value }?.let { return it.key }
        val groups = good.filter { it.text.count { c -> c.isLetter() } >= 2 }
            .groupBy { MaskSearch.normalize(it.text) }.filterKeys { it.length >= 2 }
        val winner = groups.values.filter { it.size >= 2 && it.map { r -> r.confidence }.average() >= 70 }
            .maxByOrNull { g -> g.sumOf { r -> r.confidence } }
        return winner?.maxByOrNull { it.confidence }?.text?.uppercase() ?: ""
    }
}
