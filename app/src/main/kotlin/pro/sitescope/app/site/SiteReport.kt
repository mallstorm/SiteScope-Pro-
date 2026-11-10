package pro.sitescope.app.site

import pro.sitescope.app.Models
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Level { GOOD, WARN, BAD, INFO }

class Check(val title: String, val level: Level, val text: String, val fix: String = "", val tech: String = "")

class Section(val key: String, val title: String, val checks: List<Check>, val weight: Int) {
    val score: Int = (100 - checks.sumOf { when (it.level) { Level.BAD -> 25; Level.WARN -> 10; else -> 0 }.toInt() }).coerceIn(0, 100)
    val bad: Int get() = checks.count { it.level == Level.BAD }
    val warn: Int get() = checks.count { it.level == Level.WARN }
    val verdict: String get() = when {
        checks.isEmpty() -> "Нет данных"
        bad > 0 -> "Нужно исправить: $bad"
        warn > 0 -> "Можно улучшить: $warn"
        else -> "Всё в порядке"
    }
}

class Report(
    val target: String, val host: String, val timestamp: Long, val reachable: Boolean,
    val sections: List<Section>, val facts: List<Pair<String, String>>, val previousScore: Int?
) {
    val score: Int = if (!reachable) 0 else {
        val used = sections.filter { it.checks.isNotEmpty() && it.weight > 0 }
        if (used.isEmpty()) 0 else used.sumOf { it.score * it.weight } / used.sumOf { it.weight }
    }
    val grade: String get() = SiteReportBuilder.grade(score)
    val headline: String get() = when {
        !reachable -> "Сайт не открылся"
        score >= 90 -> "Отличное состояние"
        score >= 75 -> "Хорошо, есть мелочи"
        score >= 55 -> "Есть что улучшить"
        score >= 35 -> "Много проблем"
        else -> "Сайту нужна помощь"
    }
    /** Что делать в первую очередь: сначала серьёзное. */
    val tasks: List<Pair<Section, Check>> get() =
        sections.flatMap { s -> s.checks.filter { it.level == Level.BAD }.map { s to it } } +
        sections.flatMap { s -> s.checks.filter { it.level == Level.WARN }.map { s to it } }
    val delta: Int? get() = previousScore?.let { score - it }
}

object SiteReportBuilder {
    fun grade(score: Int): String = when { score >= 95 -> "A+"; score >= 85 -> "A"; score >= 72 -> "B"; score >= 58 -> "C"; score >= 40 -> "D"; else -> "F" }

    fun fmtBytes(b: Int): String = when { b >= 1_048_576 -> "%.1f МБ".format(b / 1_048_576.0); b >= 1024 -> "${b / 1024} КБ"; else -> "$b Б" }
    fun fmtMs(ms: Long): String = if (ms >= 1000) "%.1f с".format(ms / 1000.0) else "$ms мс"

    fun build(a: Models.AuditResult, x: SiteExtras.Data, previousScore: Int?): Report {
        val reachable = x.reachable && a.httpCode in 200..399 || (x.reachable && a.httpCode == 0)
        val sections = listOf(availability(a, x), speed(a, x), seo(a, x), security(a, x), domain(a), links(a, x), tech(a, x))
        val facts = ArrayList<Pair<String, String>>()
        if (a.title.isNotEmpty()) facts.add("Заголовок" to a.title)
        if (a.ipAddresses.isNotEmpty()) facts.add("IP-адрес" to a.ipAddresses)
        if (a.infrastructureProvider.isNotEmpty()) facts.add("Хостинг" to a.infrastructureProvider + if (a.infrastructureAsn.isNotEmpty()) " · ${a.infrastructureAsn}" else "")
        if (a.cmsName.isNotEmpty() && a.cmsName != "Не определена") facts.add("Система сайта" to a.cmsName)
        if (a.server.isNotEmpty()) facts.add("Веб-сервер" to a.server)
        if (a.domainRegistrar.isNotEmpty()) facts.add("Регистратор" to a.domainRegistrar)
        if (a.domainExpires.isNotEmpty()) facts.add("Домен оплачен до" to a.domainExpires)
        return Report(a.target.ifEmpty { x.finalUrl }, a.host, a.timestamp, reachable, sections, facts, previousScore)
    }

    private fun availability(a: Models.AuditResult, x: SiteExtras.Data): Section {
        val c = ArrayList<Check>()
        if (!x.reachable && a.error.isNotEmpty()) {
            c.add(Check("Сайт не открывается", Level.BAD, "Не удалось получить страницу. Возможно, сайт выключен, адрес введён с ошибкой или его блокирует ваша сеть.",
                "Проверьте адрес и откройте сайт в браузере. Если не открывается и там — обратитесь к хостингу.", a.error.ifEmpty { x.error }))
            return Section("availability", "Доступность", c, 15)
        }
        val code = if (a.httpCode != 0) a.httpCode else x.chain.lastOrNull()?.code ?: 0
        when {
            code in 200..299 -> c.add(Check("Сайт открывается", Level.GOOD, "Сервер ответил нормально.", tech = "HTTP $code"))
            code in 400..499 -> c.add(Check("Страница отвечает ошибкой $code", Level.BAD, "Посетители видят ошибку вместо страницы.", "Проверьте адрес страницы и настройки сайта.", "HTTP $code"))
            code >= 500 -> c.add(Check("Сбой на сервере ($code)", Level.BAD, "Сервер не смог показать страницу.", "Посмотрите журнал ошибок на хостинге.", "HTTP $code"))
        }
        val hops = x.chain.size - 1
        when {
            hops <= 0 -> { }
            hops <= 2 -> c.add(Check("Переадресаций: $hops", Level.GOOD, "Короткая цепочка переходов до нужной страницы.", tech = x.chain.joinToString(" → ") { "${it.code} ${it.url}" }))
            else -> c.add(Check("Длинная цепочка переадресаций: $hops", Level.WARN, "Каждый лишний переход замедляет открытие сайта.",
                "Настройте переход сразу на конечный адрес.", x.chain.joinToString(" → ") { "${it.code} ${it.url}" }))
        }
        if (a.robotsAvailable) c.add(Check("Файл robots.txt на месте", Level.GOOD, "Поисковики знают, что можно обходить.", tech = a.robotsSummary))
        else c.add(Check("Нет файла robots.txt", Level.WARN, "Поисковым роботам не дали правил обхода.", "Добавьте robots.txt в корень сайта."))
        if (a.sitemapAvailable) c.add(Check("Карта сайта найдена", Level.GOOD, "Поисковикам проще находить страницы.", tech = a.sitemapSummary))
        else c.add(Check("Нет карты сайта (sitemap.xml)", Level.WARN, "Новые страницы могут дольше попадать в поиск.", "Создайте sitemap.xml и укажите его в robots.txt."))
        return Section("availability", "Доступность", c, 15)
    }

    private fun speed(a: Models.AuditResult, x: SiteExtras.Data): Section {
        val c = ArrayList<Check>()
        if (!x.reachable) return Section("speed", "Скорость", c, 20)
        when {
            x.ttfbMs <= 600 -> c.add(Check("Сервер отвечает быстро", Level.GOOD, "Первый ответ пришёл за ${fmtMs(x.ttfbMs)}.", tech = "TTFB ${x.ttfbMs} мс, DNS ${x.dnsMs} мс"))
            x.ttfbMs <= 1500 -> c.add(Check("Сервер отвечает не сразу", Level.WARN, "Первый ответ пришёл за ${fmtMs(x.ttfbMs)} — посетитель это заметит.",
                "Включите кеширование страниц на сервере или в системе сайта.", "TTFB ${x.ttfbMs} мс, DNS ${x.dnsMs} мс"))
            else -> c.add(Check("Сервер отвечает медленно", Level.BAD, "Первый ответ шёл ${fmtMs(x.ttfbMs)}. Часть людей уйдёт, не дождавшись.",
                "Проверьте нагрузку на хостинг, включите кеш, подумайте о более быстром тарифе или CDN.", "TTFB ${x.ttfbMs} мс, DNS ${x.dnsMs} мс"))
        }
        val text = x.headers["content-type"]?.contains("text") != false
        if (x.compression.isNotEmpty()) c.add(Check("Сжатие включено", Level.GOOD, "Страница передаётся в сжатом виде: ${fmtBytes(x.transferBytes)} вместо ${fmtBytes(x.htmlBytes)}.", tech = "Content-Encoding: ${x.compression}"))
        else if (text && x.htmlBytes > 2048) c.add(Check("Сжатие выключено", Level.WARN, "Страница передаётся без сжатия и весит ${fmtBytes(x.htmlBytes)}.",
            "Включите gzip или brotli на веб-сервере.", "Нет заголовка Content-Encoding"))
        when {
            x.htmlBytes > 1_500_000 -> c.add(Check("Очень тяжёлая страница", Level.BAD, "Код страницы весит ${fmtBytes(x.htmlBytes)}.", "Уберите лишний встроенный код и данные из HTML."))
            x.htmlBytes > 500_000 -> c.add(Check("Тяжёлая страница", Level.WARN, "Код страницы весит ${fmtBytes(x.htmlBytes)}.", "Сократите встроенные скрипты и стили."))
            else -> c.add(Check("Вес страницы в норме", Level.GOOD, "Код страницы весит ${fmtBytes(x.htmlBytes)}."))
        }
        val p = x.page
        if (p.blockingScripts > 3) c.add(Check("Скрипты задерживают показ страницы", Level.WARN, "В начале страницы ${p.blockingScripts} скриптов, которые браузер обязан выполнить до показа.",
            "Добавьте скриптам атрибут defer или async.", "Блокирующих <script> в <head>: ${p.blockingScripts}"))
        if (p.images >= 8 && p.lazyImages == 0) c.add(Check("Картинки грузятся все сразу", Level.WARN, "На странице ${p.images} изображений, отложенная загрузка не используется.",
            "Добавьте loading=\"lazy\" картинкам ниже первого экрана."))
        if (p.images >= 4 && p.imagesNoSize * 2 > p.images) c.add(Check("У картинок не заданы размеры", Level.WARN, "Страница может «прыгать» при загрузке.",
            "Укажите width и height у изображений.", "Без размеров: ${p.imagesNoSize} из ${p.images}"))
        val cache = x.headers["cache-control"] ?: ""
        if (cache.isNotEmpty()) c.add(Check("Правила кеширования заданы", Level.INFO, "Браузеру сообщили, как хранить страницу.", tech = "Cache-Control: $cache"))
        c.add(Check("Состав страницы", Level.INFO, "Скриптов: ${p.scripts}, стилей: ${p.stylesheets}, картинок: ${p.images}."))
        return Section("speed", "Скорость", c, 20)
    }

    private val seoCodes = setOf("content", "seo", "indexing")

    private fun seo(a: Models.AuditResult, x: SiteExtras.Data): Section {
        val c = ArrayList<Check>()
        if (!x.reachable) return Section("seo", "Поиск и содержание", c, 25)
        if (a.title.isNotEmpty() && a.issues.none { it.code.startsWith("TITLE") }) c.add(Check("Заголовок страницы в порядке", Level.GOOD, "«${a.title}»", tech = "Длина Title: ${a.titleLength}"))
        if (a.description.isNotEmpty() && a.issues.none { it.code.startsWith("DESCRIPTION") }) c.add(Check("Описание для поиска есть", Level.GOOD, a.description, tech = "Длина description: ${a.descriptionLength}"))
        if (a.h1Count == 1) c.add(Check("Главный заголовок один", Level.GOOD, "На странице ровно один H1 — так и нужно."))
        for (i in a.issues) if (i.category in seoCodes || i.code in setOf("VIEWPORT_MISSING", "LANG_MISSING", "H1_MISSING", "H1_MULTIPLE", "CANONICAL_MISSING", "CANONICAL_EXTERNAL")) c.add(fromIssue(i))
        val p = x.page
        if (p.ogTitle && p.ogImage) c.add(Check("Красивая карточка в соцсетях", Level.GOOD, "При отправке ссылки в мессенджер появятся заголовок и картинка.", tech = "Open Graph: og:title, og:image"))
        else c.add(Check("Нет карточки для соцсетей", Level.WARN, "Ссылка в мессенджерах будет выглядеть как голый адрес.", "Добавьте метатеги og:title, og:description и og:image.", "Open Graph неполный"))
        if (p.jsonLd.isNotEmpty()) c.add(Check("Разметка для поисковиков есть", Level.GOOD, "Поиск может показывать расширенные сниппеты.", tech = "schema.org: " + p.jsonLd.take(8).joinToString(", ")))
        else c.add(Check("Нет структурированной разметки", Level.INFO, "Разметка schema.org помогает получить звёзды, цены и другие детали в выдаче.", "Добавьте JSON-LD для организации, товаров или статей."))
        if (!p.favicon) c.add(Check("Не найден значок сайта", Level.WARN, "Во вкладке браузера и в поиске будет пустая иконка.", "Добавьте favicon."))
        if (p.hreflang > 0) c.add(Check("Языковые версии указаны", Level.INFO, "Найдено версий: ${p.hreflang}.", tech = "hreflang"))
        c.add(Check("Объём текста", Level.INFO, "Слов на странице: ${a.wordCount}, ссылок: ${a.linksCount}, картинок: ${a.imagesCount}."))
        return Section("seo", "Поиск и содержание", dedupe(c), 25)
    }

    private fun security(a: Models.AuditResult, x: SiteExtras.Data): Section {
        val c = ArrayList<Check>()
        if (!x.reachable) return Section("security", "Безопасность", c, 20)
        val https = x.finalUrl.startsWith("https://")
        if (!https) c.add(Check("Сайт работает без шифрования", Level.BAD, "Данные посетителей передаются открыто, браузеры помечают сайт как небезопасный.",
            "Установите сертификат (например, бесплатный Let's Encrypt) и переведите сайт на https://."))
        x.tls?.let { t ->
            if (!t.valid) c.add(Check("Проблема с сертификатом", Level.BAD, "Браузер покажет предупреждение о небезопасном соединении.", "Перевыпустите сертификат для этого домена.", t.error))
            else {
                when {
                    t.daysLeft < 0 -> c.add(Check("Сертификат просрочен", Level.BAD, "Срок действия истёк.", "Срочно продлите сертификат."))
                    t.daysLeft <= 14 -> c.add(Check("Сертификат скоро истечёт", Level.BAD, "Осталось дней: ${t.daysLeft}.", "Продлите сертификат и проверьте автопродление.", "${t.issuer} · до ${date(t.notAfter)}"))
                    t.daysLeft <= 30 -> c.add(Check("Сертификат истекает через ${t.daysLeft} дн.", Level.WARN, "Пора убедиться, что автопродление работает.", "Проверьте автопродление сертификата.", "${t.issuer} · до ${date(t.notAfter)}"))
                    else -> c.add(Check("Сертификат действителен", Level.GOOD, "Действует ещё ${t.daysLeft} дн., выдан ${t.issuer}.", tech = "${t.subject} · до ${date(t.notAfter)}"))
                }
                if (t.protocol == "TLSv1" || t.protocol == "TLSv1.1" || t.protocol.startsWith("SSL"))
                    c.add(Check("Устаревший протокол шифрования", Level.BAD, "Сервер согласился на ${t.protocol}, который считается небезопасным.", "Оставьте на сервере только TLS 1.2 и 1.3.", t.cipher))
                else c.add(Check("Современное шифрование", Level.GOOD, "Соединение защищено протоколом ${t.protocol.replace("TLSv", "TLS ")}.", tech = t.cipher))
            }
        }
        when (x.httpUpgrades) {
            true -> c.add(Check("Автопереход на защищённую версию", Level.GOOD, "Адрес http:// сам переводит на https://."))
            false -> if (https) c.add(Check("Нет перехода с http на https", Level.WARN, "По старой ссылке http:// сайт открывается без защиты.", "Настройте постоянную переадресацию (301) на https://."))
            null -> { }
        }
        val h = x.headers
        fun header(key: String, good: String, missTitle: String, missText: String, fix: String, level: Level = Level.WARN) {
            val v = h[key]
            if (v != null) c.add(Check(good, Level.GOOD, "Защита включена.", tech = "$key: ${v.take(160)}"))
            else c.add(Check(missTitle, level, missText, fix, "Нет заголовка $key"))
        }
        if (https) header("strict-transport-security", "Браузер всегда использует https", "Не включён режим «только https»",
            "Браузер может попытаться открыть сайт без шифрования.", "Добавьте заголовок Strict-Transport-Security.")
        header("content-security-policy", "Задана политика загрузки содержимого", "Нет защиты от внедрения чужого кода",
            "Если на сайт попадёт вредоносный скрипт, браузер его не остановит.", "Настройте заголовок Content-Security-Policy.")
        header("x-content-type-options", "Запрещён подбор типа файлов", "Браузеру разрешено угадывать тип файлов",
            "Это упрощает некоторые атаки.", "Добавьте X-Content-Type-Options: nosniff.")
        if (h["x-frame-options"] != null || h["content-security-policy"]?.contains("frame-ancestors") == true)
            c.add(Check("Защита от встраивания в чужие страницы", Level.GOOD, "Сайт нельзя незаметно показать внутри другого сайта."))
        else c.add(Check("Сайт можно встроить в чужую страницу", Level.WARN, "Мошенники могут показать ваш сайт в рамке и перехватывать нажатия.", "Добавьте X-Frame-Options: SAMEORIGIN или frame-ancestors в CSP."))
        if (h["referrer-policy"] == null) c.add(Check("Не задана политика передачи адреса", Level.INFO, "Другие сайты могут видеть, с какой страницы пришёл посетитель.", "Добавьте Referrer-Policy: strict-origin-when-cross-origin."))
        if (x.page.mixedContent > 0) c.add(Check("Часть содержимого грузится без защиты", Level.BAD, "На защищённой странице есть ${x.page.mixedContent} ресурсов по http:// — браузер может их заблокировать.", "Замените ссылки на https://.", "Mixed content: ${x.page.mixedContent}"))
        if (x.page.insecureForms > 0) c.add(Check("Форма отправляет данные без шифрования", Level.BAD, "Введённые данные можно перехватить.", "Укажите в action адрес с https://."))
        if (x.cookiesTotal > 0) {
            if (x.cookiesInsecure == 0) c.add(Check("Cookie защищены", Level.GOOD, "Все ${x.cookiesTotal} cookie имеют флаги Secure и HttpOnly."))
            else c.add(Check("Cookie без защитных флагов", Level.WARN, "${x.cookiesInsecure} из ${x.cookiesTotal} cookie можно прочитать скриптом или перехватить.", "Добавьте флаги Secure, HttpOnly и SameSite."))
        }
        val server = h["server"] ?: ""; val powered = h["x-powered-by"] ?: ""
        if (Regex("\\d+\\.\\d+").containsMatchIn(server + powered)) c.add(Check("Сервер раскрывает свою версию", Level.WARN, "Зная точную версию, злоумышленнику проще подобрать уязвимость.",
            "Скройте версию в настройках сервера.", listOf(server, powered).filter { it.isNotEmpty() }.joinToString(" · ")))
        if (x.securityTxt) c.add(Check("Есть контакт для сообщений об уязвимостях", Level.GOOD, "Файл security.txt найден."))
        return Section("security", "Безопасность", c, 20)
    }

    private fun domain(a: Models.AuditResult): Section {
        val c = ArrayList<Check>()
        if (a.domainExpires.isNotEmpty()) {
            val days = daysUntil(a.domainExpires)
            when {
                days == null -> c.add(Check("Домен оплачен до ${a.domainExpires}", Level.INFO, "Регистратор: ${a.domainRegistrar.ifEmpty { "не указан" }}."))
                days < 0 -> c.add(Check("Срок регистрации домена истёк", Level.BAD, "Сайт может перестать открываться в любой момент.", "Срочно продлите домен у регистратора."))
                days <= 30 -> c.add(Check("Домен скоро нужно продлить", Level.BAD, "Осталось дней: $days.", "Продлите домен и включите автопродление.", a.domainExpires))
                days <= 60 -> c.add(Check("Домен истекает через $days дн.", Level.WARN, "Лучше продлить заранее.", "Продлите домен.", a.domainExpires))
                else -> c.add(Check("Домен оплачен", Level.GOOD, "До ${a.domainExpires.take(10)} (ещё $days дн.). Регистратор: ${a.domainRegistrar.ifEmpty { "не указан" }}.", tech = a.domainStatus))
            }
        }
        if (a.domainRegistered.isNotEmpty()) c.add(Check("Домен зарегистрирован ${a.domainRegistered.take(10)}", Level.INFO, "Возраст домена учитывается поисковиками и почтовыми сервисами."))
        if (a.dnsRecords.isNotEmpty() || a.dnsStatus != "Не проверено") {
            if (a.spfPresent) c.add(Check("Почта защищена от подделки (SPF)", Level.GOOD, "Указано, какие серверы могут слать письма от имени домена."))
            else c.add(Check("Письма от имени домена можно подделать", Level.WARN, "Нет записи SPF — мошенники могут рассылать письма «от вас», а ваши письма чаще попадают в спам.", "Добавьте TXT-запись SPF.", "SPF не найден"))
            if (a.dmarcPresent) c.add(Check("Политика для поддельных писем задана (DMARC)", Level.GOOD, "Почтовые сервисы знают, что делать с подделками."))
            else c.add(Check("Не задана политика DMARC", Level.WARN, "Почтовые сервисы не знают, как поступать с письмами-подделками.", "Добавьте TXT-запись _dmarc.", "DMARC не найден"))
            if (a.dnssecPresent) c.add(Check("Адрес домена защищён подписью (DNSSEC)", Level.GOOD, "Подменить адрес сайта сложнее."))
            else c.add(Check("DNSSEC не включён", Level.INFO, "Дополнительная защита от подмены адреса сайта.", "Включите DNSSEC у регистратора, если он это поддерживает."))
            if (a.caaPresent) c.add(Check("Ограничен список центров сертификации (CAA)", Level.GOOD, "Чужой сертификат на ваш домен выпустить сложнее."))
            if (a.dnsResolversCompared) {
                if (a.dnsResolversAgree) c.add(Check("Адрес сайта везде одинаковый", Level.GOOD, "Разные DNS-службы дают один и тот же ответ.", tech = a.dnsResolver))
                else c.add(Check("DNS-службы дают разные ответы", Level.WARN, "Возможно, записи недавно менялись или используется геораспределение.", "Проверьте записи домена.", a.dnsResolver))
            }
            if (a.domainNameservers.isNotEmpty()) c.add(Check("Серверы имён", Level.INFO, a.domainNameservers))
        }
        for (i in a.issues) if (i.category == "dns") c.add(fromIssue(i))
        return Section("domain", "Домен и почта", dedupe(c), 10)
    }

    private fun links(a: Models.AuditResult, x: SiteExtras.Data): Section {
        val c = ArrayList<Check>()
        if (x.linksChecked > 0) {
            val broken = x.links.filter { it.code == -1 || it.code == 404 || it.code == 410 || it.code >= 500 }
            if (broken.isEmpty()) c.add(Check("Проверенные ссылки работают", Level.GOOD, "Проверено ссылок: ${x.linksChecked}."))
            else {
                val inner = broken.count { it.internal }
                c.add(Check("Битые ссылки: ${broken.size}", if (inner > 0) Level.BAD else Level.WARN,
                    "Из ${x.linksChecked} проверенных ссылок ${broken.size} ведут в никуда" + (if (inner > 0) ", из них $inner — на ваш же сайт." else "."),
                    "Исправьте или уберите эти ссылки.", broken.take(12).joinToString("\n") { "${if (it.code == -1) "нет ответа" else it.code.toString()}  ${it.url}" }))
            }
        }
        if (a.fullCrawl) {
            c.add(Check("Обойдено страниц: ${a.pagesCrawled}", Level.INFO, "Найдено адресов: ${a.pagesDiscovered}, в карте сайта: ${a.sitemapUrlCount}. Средний ответ: ${fmtMs(a.averageResponseMs)}."))
            if (a.brokenInternalLinks > 0) c.add(Check("Неработающие страницы: ${a.brokenInternalLinks}", Level.BAD, "Внутренние ссылки ведут на страницы с ошибкой.", "Исправьте ссылки или верните страницы."))
            if (a.duplicateTitles > 0) c.add(Check("Одинаковые заголовки: ${a.duplicateTitles}", Level.WARN, "Поисковику сложнее различать страницы.", "Сделайте заголовки уникальными."))
            if (a.duplicateContentGroups > 0) c.add(Check("Страницы-двойники: ${a.duplicateContentGroups} групп", Level.WARN, "Одинаковый текст на разных адресах.", "Объедините дубли или укажите canonical."))
            if (a.orphanCandidates > 0) c.add(Check("Страницы без входящих ссылок: ${a.orphanCandidates}", Level.WARN, "Есть в карте сайта, но на них никто не ссылается.", "Добавьте ссылки на эти страницы."))
            if (a.noindexPages > 0) c.add(Check("Скрыто от поиска: ${a.noindexPages}", Level.INFO, "Страницы с запретом индексации."))
            if (a.redirectPages > 0) c.add(Check("Страниц с переадресацией: ${a.redirectPages}", Level.INFO, "Внутренние ссылки лучше вести сразу на конечный адрес."))
        }
        for (i in a.issues) if (i.category == "links") c.add(fromIssue(i))
        return Section("links", "Ссылки и страницы", dedupe(c), 10)
    }

    private fun tech(a: Models.AuditResult, x: SiteExtras.Data): Section {
        val c = ArrayList<Check>()
        if (a.cmsName.isNotEmpty() && a.cmsName != "Не определена")
            c.add(Check("Сайт работает на ${a.cmsName}", Level.INFO, "Уверенность: ${a.cmsConfidence}%.", tech = a.cmsEvidence))
        for (t in a.technologies) if (t.name != a.cmsName) c.add(Check(t.name, Level.INFO, t.category.ifEmpty { "Технология" } + " · уверенность ${t.confidence}%", tech = t.evidence))
        if (a.infrastructureProvider.isNotEmpty()) c.add(Check("Размещение: ${a.infrastructureProvider}", Level.INFO, listOf(a.infrastructureAsn, a.ipAddresses, a.reverseDns).filter { it.isNotEmpty() }.joinToString(" · ")))
        val alt = x.headers["alt-svc"] ?: ""
        if (alt.contains("h3")) c.add(Check("Поддерживается HTTP/3", Level.INFO, "Самый быстрый современный протокол.", tech = "alt-svc: ${alt.take(80)}"))
        return Section("tech", "Технологии", c, 0)
    }

    private fun fromIssue(i: Models.Issue): Check {
        val level = when (i.severity) { "critical", "important" -> Level.BAD; "warning" -> Level.WARN; else -> Level.INFO }
        return Check(i.title, level, i.explanation.ifEmpty { i.evidence }, i.recommendation,
            listOf(i.evidence, i.affectedUrl).filter { it.isNotEmpty() }.joinToString(" · "))
    }

    private fun dedupe(list: List<Check>): List<Check> { val seen = HashSet<String>(); return list.filter { seen.add(it.title) } }

    private fun date(ms: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(ms))

    fun daysUntil(text: String): Int? {
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "dd.MM.yyyy")) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US); f.isLenient = false
                val d = f.parse(text.take(pattern.replace("'", "").length)) ?: continue
                return ((d.time - System.currentTimeMillis()) / 86_400_000L).toInt()
            } catch (e: Exception) { }
        }
        return null
    }

    // ---------- Экспорт ----------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toHtml(r: Report): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Отчёт: ").append(esc(r.host)).append("</title><style>")
        sb.append("body{font-family:system-ui,sans-serif;max-width:760px;margin:0 auto;padding:20px;color:#2b2622;background:#fbf7f2}h1{font-size:22px}h2{font-size:17px;margin-top:28px}")
        sb.append(".score{font-size:44px;font-weight:700}.c{background:#fff;border-radius:14px;padding:12px 14px;margin:8px 0;border-left:5px solid #bbb}")
        sb.append(".GOOD{border-color:#2e9e6b}.WARN{border-color:#e0962a}.BAD{border-color:#d9483b}.INFO{border-color:#8aa0b8}.t{font-weight:600}.f{color:#5b4b8a;margin-top:4px}.m{color:#857a70;font-size:12px;margin-top:4px;white-space:pre-wrap;word-break:break-all}table{border-collapse:collapse}td{padding:3px 12px 3px 0;vertical-align:top}")
        sb.append("</style></head><body><h1>Отчёт о сайте ").append(esc(r.host)).append("</h1><div class=\"score\">").append(r.score).append(" <small>/ 100 · ").append(r.grade).append("</small></div><p>").append(esc(r.headline))
        r.delta?.let { sb.append(" · изменение с прошлой проверки: ").append(if (it >= 0) "+" else "").append(it) }
        sb.append("</p><p>").append(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(r.timestamp))).append(" · ").append(esc(r.target)).append("</p><table>")
        for ((k, v) in r.facts) sb.append("<tr><td>").append(esc(k)).append("</td><td>").append(esc(v)).append("</td></tr>")
        sb.append("</table>")
        if (r.tasks.isNotEmpty()) {
            sb.append("<h2>Что сделать в первую очередь</h2><ol>")
            for ((_, t) in r.tasks.take(10)) sb.append("<li><b>").append(esc(t.title)).append("</b>").append(if (t.fix.isNotEmpty()) " — " + esc(t.fix) else "").append("</li>")
            sb.append("</ol>")
        }
        for (s in r.sections) {
            if (s.checks.isEmpty()) continue
            sb.append("<h2>").append(esc(s.title)); if (s.weight > 0) sb.append(" — ").append(s.score).append("/100"); sb.append("</h2>")
            for (c in s.checks) {
                sb.append("<div class=\"c ").append(c.level.name).append("\"><div class=\"t\">").append(esc(c.title)).append("</div><div>").append(esc(c.text)).append("</div>")
                if (c.fix.isNotEmpty()) sb.append("<div class=\"f\">Как исправить: ").append(esc(c.fix)).append("</div>")
                if (c.tech.isNotEmpty()) sb.append("<div class=\"m\">").append(esc(c.tech)).append("</div>")
                sb.append("</div>")
            }
        }
        sb.append("<p class=\"m\">Создано в SiteScope Pro</p></body></html>")
        return sb.toString()
    }

    fun toText(r: Report): String {
        val sb = StringBuilder("${r.host}: ${r.score}/100 (${r.grade}) — ${r.headline}\n")
        for (s in r.sections) if (s.checks.isNotEmpty() && s.weight > 0) sb.append("• ${s.title}: ${s.score}/100\n")
        if (r.tasks.isNotEmpty()) { sb.append("\nЧто исправить:\n"); r.tasks.take(8).forEachIndexed { i, (_, t) -> sb.append("${i + 1}. ${t.title}\n") } }
        return sb.toString().trim()
    }
}
