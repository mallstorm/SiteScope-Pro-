package pro.sitescope.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Dependency-free, bounded SEO crawler for Android alpha builds.
 * It intentionally performs only read-only HTTP requests and honours the basic
 * User-agent:* Allow/Disallow directives from robots.txt.
 */
public final class CrawlerEngine {
    public static final int MAX_PAGES = 100;
    public static final int MAX_DEPTH = 5;
    private static final int MAX_BODY_BYTES = 1_500_000;
    private static final int MAX_SITEMAP_BYTES = 2_500_000;
    private static final int MAX_SITEMAP_URLS = 1000;
    private static final String UA = "SiteScopePro/0.8.0-alpha6 SEO-Crawler";

    private CrawlerEngine() { }

    public interface ProgressListener {
        void onProgress(int crawled, int maxPages, int discovered, String currentUrl);
        boolean isCancelled();
    }

    public static final class Config {
        public int maxPages = 25;
        public int maxDepth = 3;
        public int connectTimeoutMs = 8000;
        public int readTimeoutMs = 12000;
        public boolean respectRobots = true;
        public boolean includeSitemap = true;

        public void normalize() {
            maxPages = Math.max(1, Math.min(MAX_PAGES, maxPages));
            maxDepth = Math.max(0, Math.min(MAX_DEPTH, maxDepth));
            connectTimeoutMs = Math.max(1500, Math.min(20000, connectTimeoutMs));
            readTimeoutMs = Math.max(2500, Math.min(30000, readTimeoutMs));
        }
    }

    private static final class QueueItem {
        final String url;
        final int depth;
        QueueItem(String url, int depth) { this.url = url; this.depth = depth; }
    }

    private static final class Fetch {
        String requestedUrl = "";
        String finalUrl = "";
        int status;
        String contentType = "";
        String server = "";
        String html = "";
        long elapsedMs;
        boolean https;
        boolean tlsValid;
        String tlsExpires = "";
        Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    private static final class RobotsPolicy {
        final List<String> allow = new ArrayList<String>();
        final List<String> disallow = new ArrayList<String>();
        final List<String> sitemapUrls = new ArrayList<String>();
        boolean available;
        String summary = "robots.txt не найден";

        boolean allowed(String url) {
            try {
                String path = new URL(url).getPath();
                if (path == null || path.length() == 0) path = "/";
                int bestAllow = -1;
                int bestDisallow = -1;
                for (String rule : allow) if (matches(path, rule)) bestAllow = Math.max(bestAllow, rule.length());
                for (String rule : disallow) if (matches(path, rule)) bestDisallow = Math.max(bestDisallow, rule.length());
                return bestDisallow < 0 || bestAllow >= bestDisallow;
            } catch (Exception ignored) { return true; }
        }

        private static boolean matches(String path, String rule) {
            if (rule == null || rule.length() == 0) return false;
            boolean endAnchored = rule.endsWith("$");
            if (endAnchored) rule = rule.substring(0, rule.length() - 1);
            StringBuilder regex = new StringBuilder("^");
            String[] pieces = rule.split("\\*", -1);
            for (int i = 0; i < pieces.length; i++) {
                if (i > 0) regex.append(".*");
                regex.append(Pattern.quote(pieces[i]));
            }
            if (endAnchored) regex.append("$"); else regex.append(".*");
            return path.matches(regex.toString());
        }
    }

    public static Models.AuditResult crawl(String rawTarget, Config config, ProgressListener listener) {
        config.normalize();
        long started = System.currentTimeMillis();
        Models.AuditResult result = new Models.AuditResult();
        result.fullCrawl = true;
        result.timestamp = started;
        result.target = AuditEngine.normalizeTarget(rawTarget);
        Set<String> sitemapUrls = new LinkedHashSet<String>();
        Set<String> linkedUrls = new HashSet<String>();
        try {
            URL initial = new URL(result.target);
            result.host = initial.getHost();
            String rootOrigin = initial.getProtocol() + "://" + initial.getAuthority();
            result.ipAddresses = resolveAddresses(result.host);

            RobotsPolicy robots = fetchRobots(rootOrigin, config);
            result.robotsAvailable = robots.available;
            result.robotsSummary = robots.summary;
            if (config.includeSitemap) {
                List<String> candidates = new ArrayList<String>(robots.sitemapUrls);
                if (candidates.isEmpty()) candidates.add(rootOrigin + "/sitemap.xml");
                for (String sitemap : candidates) {
                    if (sitemapUrls.size() >= MAX_SITEMAP_URLS) break;
                    loadSitemap(sitemap, rootOrigin, config, sitemapUrls, 0);
                }
            }
            result.sitemapAvailable = !sitemapUrls.isEmpty();
            result.sitemapUrlCount = sitemapUrls.size();
            result.sitemapSummary = sitemapUrls.isEmpty() ? "Sitemap не найден или пуст" : "Найдено URL в sitemap: " + sitemapUrls.size();

            ArrayDeque<QueueItem> queue = new ArrayDeque<QueueItem>();
            Set<String> queued = new LinkedHashSet<String>();
            String normalizedRoot = canonicalize(result.target);
            queue.add(new QueueItem(normalizedRoot, 0));
            queued.add(normalizedRoot);
            // Sitemap is used as a secondary discovery source, while the normal link graph stays primary.
            for (String sitemapUrl : sitemapUrls) {
                if (queued.size() >= config.maxPages * 4) break;
                String normalized = canonicalize(sitemapUrl);
                if (sameHost(normalizedRoot, normalized) && queued.add(normalized)) queue.add(new QueueItem(normalized, 1));
            }

            long responseTotal = 0;
            while (!queue.isEmpty() && result.pages.size() < config.maxPages) {
                if (listener != null && listener.isCancelled()) break;
                QueueItem item = queue.removeFirst();
                if (item.depth > config.maxDepth || !sameHost(normalizedRoot, item.url)) continue;
                if (config.respectRobots && !robots.allowed(item.url)) {
                    result.robotsBlockedCount++;
                    continue;
                }
                if (listener != null) listener.onProgress(result.pages.size(), config.maxPages, queued.size(), item.url);
                Models.PageAudit page;
                try {
                    Fetch fetch = fetch(item.url, config, MAX_BODY_BYTES);
                    if (result.pages.isEmpty()) applyRootInfrastructure(fetch, result);
                    page = parsePage(fetch, item.depth, normalizedRoot);
                    responseTotal += page.responseMs;
                    result.pages.add(page);
                    applyPageRules(page, result);
                    if (page.statusCode >= 400) result.brokenInternalLinks++;
                    if (!page.requestedUrl.equals(page.finalUrl)) result.redirectPages++;
                    if (!page.indexable) result.noindexPages++;
                    if (canonicalProblem(page)) result.canonicalProblems++;
                    for (String link : page.discoveredLinks) {
                        linkedUrls.add(link);
                        if (item.depth < config.maxDepth && sameHost(normalizedRoot, link) && queued.add(link)) {
                            queue.addLast(new QueueItem(link, item.depth + 1));
                        }
                    }
                } catch (Exception error) {
                    page = new Models.PageAudit();
                    page.requestedUrl = item.url;
                    page.finalUrl = item.url;
                    page.depth = item.depth;
                    page.statusCode = 0;
                    result.pages.add(page);
                    add(result, "CRAWL_FETCH_ERROR", "important", "Не удалось получить страницу",
                        error.getClass().getSimpleName() + ": " + safe(error.getMessage()),
                        "Страница не была проверена, поэтому часть SEO-проблем может остаться незамеченной.",
                        "Проверьте URL, DNS, TLS, сетевые ограничения и повторите обход.", "technical", item.url);
                }
            }

            result.pagesCrawled = result.pages.size();
            result.pagesDiscovered = queued.size();
            result.averageResponseMs = result.pages.isEmpty() ? 0 : responseTotal / result.pages.size();
            if (!result.pages.isEmpty()) copyRootSummary(result, result.pages.get(0));
            analyzeDuplicates(result);
            for (String sitemapUrl : sitemapUrls) {
                String normalized = canonicalize(sitemapUrl);
                if (!normalized.equals(normalizedRoot) && !linkedUrls.contains(normalized)) result.orphanCandidates++;
            }
            if (result.orphanCandidates > 0) {
                add(result, "ORPHAN_CANDIDATES", "warning", "Возможные страницы-сироты",
                    result.orphanCandidates + " URL из sitemap не найдены во внутренних ссылках",
                    "Страницы без внутренних ссылок хуже обнаруживаются и получают меньше внутреннего веса.",
                    "Проверьте список URL и добавьте релевантные внутренние ссылки либо удалите устаревшие URL из sitemap.", "links", result.finalUrl);
            }
            if (result.robotsBlockedCount > 0) {
                add(result, "ROBOTS_BLOCKED", "warning", "Часть URL закрыта robots.txt",
                    "Пропущено URL: " + result.robotsBlockedCount,
                    "Закрытые URL не были проанализированы crawler и могут быть недоступны поисковым системам.",
                    "Проверьте, соответствует ли блокировка вашей стратегии индексации.", "indexing", result.finalUrl);
            }
            if (result.pagesDiscovered > result.pagesCrawled) {
                add(result, "CRAWL_LIMIT_REACHED", "warning", "Обход ограничен настройками",
                    "Обнаружено " + result.pagesDiscovered + ", проверено " + result.pagesCrawled,
                    "Часть сайта не вошла в текущий отчёт.",
                    "Увеличьте лимит страниц или глубину обхода и повторите аудит.", "technical", result.finalUrl);
            }
            result.score = score(result.issues);
            result.passedChecks = Math.max(0, result.pagesCrawled * 12 - result.issues.size());
        } catch (Exception error) {
            result.error = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
            add(result, "CRAWL_FATAL", "critical", "Обход сайта не запущен", result.error,
                "Crawler не смог подготовить корневой URL.",
                "Проверьте адрес, DNS, HTTPS и доступность сайта.", "technical", result.target);
            result.score = 0;
        }
        result.durationMs = System.currentTimeMillis() - started;
        if (listener != null) listener.onProgress(result.pagesCrawled, config.maxPages, result.pagesDiscovered, "Готово");
        return result;
    }

    private static void applyRootInfrastructure(Fetch fetch, Models.AuditResult result) {
        result.server = fetch.server;
        result.contentType = fetch.contentType;
        result.https = fetch.https;
        result.tlsValid = fetch.tlsValid;
        result.tlsExpires = fetch.tlsExpires;
        Map<String, String> lower = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : fetch.headers.entrySet()) lower.put(entry.getKey().toLowerCase(Locale.US), entry.getValue());
        String affected = fetch.finalUrl.length() > 0 ? fetch.finalUrl : fetch.requestedUrl;
        if (!fetch.https) add(result, "HTTPS_MISSING", "critical", "Не используется HTTPS", affected,
            "Незащищённое соединение снижает безопасность и доверие.",
            "Установите действующий TLS-сертификат и настройте единый 301 на HTTPS.", "security", affected);
        else if (!fetch.tlsValid) add(result, "TLS_INVALID", "critical", "TLS-сертификат не подтверждён", fetch.tlsExpires,
            "Ошибки сертификата могут блокировать доступ к сайту.",
            "Проверьте срок действия, цепочку доверия и соответствие имени домена.", "security", affected);
        if (fetch.https && !lower.containsKey("strict-transport-security")) add(result, "HSTS_MISSING", "warning", "HSTS не настроен", "Заголовок отсутствует",
            "HSTS снижает риск перехода на незашифрованное соединение.",
            "После проверки HTTPS добавьте Strict-Transport-Security.", "security", affected);
        if (!lower.containsKey("content-security-policy")) add(result, "CSP_MISSING", "warning", "Content Security Policy не настроена", "Заголовок отсутствует",
            "CSP уменьшает последствия внедрения нежелательного контента.",
            "Начните с Content-Security-Policy-Report-Only и постепенно внедрите рабочую политику.", "security", affected);
        if (!lower.containsKey("x-content-type-options")) add(result, "NOSNIFF_MISSING", "warning", "Отсутствует X-Content-Type-Options", "Заголовок отсутствует",
            "Браузер может неверно определить тип содержимого.",
            "Добавьте X-Content-Type-Options: nosniff.", "security", affected);
    }

    private static void copyRootSummary(Models.AuditResult result, Models.PageAudit page) {
        result.finalUrl = page.finalUrl;
        result.httpCode = page.statusCode;
        result.title = page.title;
        result.description = page.description;
        result.canonical = page.canonical;
        result.titleLength = page.title.length();
        result.descriptionLength = page.description.length();
        result.h1Count = page.h1Count;
        result.wordCount = page.wordCount;
        result.linksCount = page.internalLinks + page.externalLinks;
        result.imagesCount = page.images;
        result.imagesMissingAlt = page.imagesMissingAlt;
        try {
            URL url = new URL(page.finalUrl);
            result.https = "https".equalsIgnoreCase(url.getProtocol());
        } catch (Exception ignored) { }
    }

    private static Models.PageAudit parsePage(Fetch fetch, int depth, String rootUrl) throws Exception {
        Models.PageAudit page = new Models.PageAudit();
        page.requestedUrl = canonicalize(fetch.requestedUrl);
        page.finalUrl = canonicalize(fetch.finalUrl.length() > 0 ? fetch.finalUrl : fetch.requestedUrl);
        page.statusCode = fetch.status;
        page.depth = depth;
        page.contentType = fetch.contentType;
        page.responseMs = fetch.elapsedMs;
        if (!fetch.contentType.toLowerCase(Locale.US).contains("html") || fetch.html.length() == 0) return page;
        String html = fetch.html;
        page.title = clean(first(html, "(?is)<title[^>]*>(.*?)</title>"));
        page.description = metaContent(html, "description");
        page.robotsMeta = metaContent(html, "robots");
        page.canonical = absoluteUrl(page.finalUrl, linkHref(html, "canonical"));
        page.h1Count = count(html, "(?is)<h1\\b");
        page.images = count(html, "(?is)<img\\b");
        page.imagesMissingAlt = countImagesMissingAlt(html);
        String visible = visibleText(html);
        page.wordCount = visible.length() == 0 ? 0 : visible.split("\\s+").length;
        page.contentHash = sha256(normalizeContent(visible));
        String robots = page.robotsMeta.toLowerCase(Locale.US);
        page.indexable = !robots.contains("noindex") && page.statusCode >= 200 && page.statusCode < 400;

        Matcher matcher = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*(['\"])(.*?)\\1").matcher(html);
        Set<String> internal = new LinkedHashSet<String>();
        Set<String> external = new LinkedHashSet<String>();
        while (matcher.find()) {
            String link = absoluteUrl(page.finalUrl, clean(matcher.group(2)));
            if (link.length() == 0 || !isHttp(link)) continue;
            link = canonicalize(link);
            if (sameHost(rootUrl, link)) internal.add(link); else external.add(link);
        }
        page.discoveredLinks.addAll(internal);
        page.internalLinks = internal.size();
        page.externalLinks = external.size();
        return page;
    }

    private static void applyPageRules(Models.PageAudit page, Models.AuditResult result) {
        String url = page.finalUrl.length() > 0 ? page.finalUrl : page.requestedUrl;
        if (page.statusCode == 0) return;
        if (page.statusCode >= 500) add(result, "HTTP_5XX", "critical", "Серверная ошибка", "HTTP " + page.statusCode,
            "Страница недоступна поисковым роботам и пользователям.", "Исправьте сервер или приложение и добейтесь стабильного ответа 200.", "technical", url);
        else if (page.statusCode >= 400) add(result, "HTTP_4XX", "important", "Неработающая внутренняя страница", "HTTP " + page.statusCode,
            "Битые URL ухудшают обход сайта и пользовательский опыт.", "Исправьте внутренние ссылки, восстановите страницу или настройте релевантный 301.", "technical", url);
        if (page.contentType.toLowerCase(Locale.US).contains("html")) {
            if (page.title.length() == 0) add(result, "TITLE_MISSING", "important", "Отсутствует Title", "Тег <title> не найден",
                "Title является основным сигналом темы страницы.", "Добавьте уникальный информативный Title длиной около 30–60 символов.", "content", url);
            else if (page.title.length() < 20 || page.title.length() > 70) add(result, "TITLE_LENGTH", "warning", "Неоптимальная длина Title", page.title.length() + " символов: " + page.title,
                "Слишком короткий Title малоинформативен, а слишком длинный может быть обрезан.", "Сформулируйте Title длиной примерно 30–60 символов.", "content", url);
            if (page.description.length() == 0) add(result, "DESCRIPTION_MISSING", "warning", "Отсутствует meta description", "description не найден",
                "Поисковик сформирует сниппет самостоятельно.", "Добавьте уникальное описание пользы страницы.", "content", url);
            if (page.h1Count == 0) add(result, "H1_MISSING", "important", "Отсутствует H1", "H1: 0",
                "Главный заголовок помогает понять назначение страницы.", "Добавьте один видимый H1, соответствующий содержанию.", "content", url);
            else if (page.h1Count > 1) add(result, "H1_MULTIPLE", "warning", "Несколько H1", "H1: " + page.h1Count,
                "Несколько главных заголовков усложняют структуру документа.", "Оставьте один главный H1, остальные оформите как H2/H3.", "content", url);
            if (page.canonical.length() == 0) add(result, "CANONICAL_MISSING", "warning", "Canonical не указан", "link rel=canonical не найден",
                "Дубли и параметрические URL могут конкурировать между собой.", "Добавьте self-referencing canonical на предпочтительный URL.", "indexing", url);
            else if (!sameHost(url, page.canonical)) add(result, "CANONICAL_EXTERNAL", "important", "Canonical указывает на другой домен", page.canonical,
                "Страница может передавать поисковые сигналы внешнему ресурсу.", "Проверьте корректность canonical и оставьте его только при осознанной междоменной канонизации.", "indexing", url);
            if (!page.indexable) add(result, "NOINDEX", "warning", "Страница закрыта от индексации", page.robotsMeta,
                "Страница не должна появляться в поисковой выдаче.", "Убедитесь, что noindex установлен намеренно и URL не включён в sitemap.", "indexing", url);
            if (page.wordCount < 80) add(result, "THIN_CONTENT", "warning", "Мало основного текста", page.wordCount + " слов",
                "Страница может недостаточно раскрывать пользовательскую задачу.", "Добавьте полезный уникальный контент либо объедините слабые страницы.", "content", url);
            if (page.imagesMissingAlt > 0) add(result, "IMAGE_ALT_MISSING", "warning", "Изображения без alt", "Без alt: " + page.imagesMissingAlt,
                "Alt помогает доступности и пониманию изображений.", "Добавьте содержательный alt, а декоративным изображениям — пустой alt.", "accessibility", url);
        }
    }

    private static void analyzeDuplicates(Models.AuditResult result) {
        Map<String, List<Models.PageAudit>> titles = new HashMap<String, List<Models.PageAudit>>();
        Map<String, List<Models.PageAudit>> descriptions = new HashMap<String, List<Models.PageAudit>>();
        Map<String, List<Models.PageAudit>> content = new HashMap<String, List<Models.PageAudit>>();
        for (Models.PageAudit page : result.pages) {
            group(titles, normalize(page.title), page);
            group(descriptions, normalize(page.description), page);
            if (page.wordCount >= 40) group(content, page.contentHash, page);
        }
        result.duplicateTitles = duplicateCount(titles);
        result.duplicateDescriptions = duplicateCount(descriptions);
        result.duplicateContentGroups = duplicateGroups(content);
        if (result.duplicateTitles > 0) add(result, "DUPLICATE_TITLES", "important", "Дублирующиеся Title",
            result.duplicateTitles + " страниц входят в группы дублей",
            "Одинаковые Title мешают различать назначение страниц в поиске.",
            "Сделайте Title уникальными и отражающими конкретное содержание URL.", "content", result.finalUrl);
        if (result.duplicateDescriptions > 0) add(result, "DUPLICATE_DESCRIPTIONS", "warning", "Дублирующиеся descriptions",
            result.duplicateDescriptions + " страниц входят в группы дублей",
            "Одинаковые descriptions снижают управляемость поисковых сниппетов.",
            "Подготовьте уникальные descriptions для индексируемых страниц.", "content", result.finalUrl);
        if (result.duplicateContentGroups > 0) add(result, "DUPLICATE_CONTENT", "important", "Обнаружены группы одинакового текста",
            "Групп полного совпадения: " + result.duplicateContentGroups,
            "Дубли расходуют обходной бюджет и размывают сигналы релевантности.",
            "Объедините страницы, настройте canonical/redirect или сделайте контент уникальным.", "content", result.finalUrl);
    }

    private static void group(Map<String, List<Models.PageAudit>> map, String key, Models.PageAudit page) {
        if (key == null || key.length() == 0) return;
        List<Models.PageAudit> list = map.get(key);
        if (list == null) { list = new ArrayList<Models.PageAudit>(); map.put(key, list); }
        list.add(page);
    }

    private static int duplicateCount(Map<String, List<Models.PageAudit>> map) {
        int count = 0;
        for (List<Models.PageAudit> list : map.values()) if (list.size() > 1) count += list.size();
        return count;
    }

    private static int duplicateGroups(Map<String, List<Models.PageAudit>> map) {
        int count = 0;
        for (List<Models.PageAudit> list : map.values()) if (list.size() > 1) count++;
        return count;
    }

    private static boolean canonicalProblem(Models.PageAudit page) {
        if (page.contentType.toLowerCase(Locale.US).contains("html") && page.canonical.length() == 0) return true;
        return page.canonical.length() > 0 && !sameHost(page.finalUrl, page.canonical);
    }

    private static RobotsPolicy fetchRobots(String rootOrigin, Config config) {
        RobotsPolicy policy = new RobotsPolicy();
        try {
            Fetch response = fetch(rootOrigin + "/robots.txt", config, 512_000);
            if (response.status < 200 || response.status >= 400) return policy;
            policy.available = true;
            boolean active = false;
            int directives = 0;
            String[] lines = response.html.replace("\r", "").split("\n");
            for (String raw : lines) {
                String line = raw;
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                if ("user-agent".equals(name)) active = "*".equals(value) || value.toLowerCase(Locale.US).contains("sitescope");
                else if ("allow".equals(name) && active) { policy.allow.add(value); directives++; }
                else if ("disallow".equals(name) && active) { if (value.length() > 0) policy.disallow.add(value); directives++; }
                else if ("sitemap".equals(name) && value.length() > 0) policy.sitemapUrls.add(value);
            }
            policy.summary = "Директив: " + directives + " · sitemap: " + policy.sitemapUrls.size();
        } catch (Exception ignored) { }
        return policy;
    }

    private static void loadSitemap(String sitemapUrl, String rootOrigin, Config config, Set<String> urls, int level) {
        if (level > 2 || urls.size() >= MAX_SITEMAP_URLS) return;
        try {
            Fetch fetch = fetch(sitemapUrl, config, MAX_SITEMAP_BYTES);
            if (fetch.status < 200 || fetch.status >= 400) return;
            Matcher matcher = Pattern.compile("(?is)<loc[^>]*>(.*?)</loc>").matcher(fetch.html);
            List<String> found = new ArrayList<String>();
            while (matcher.find() && found.size() < MAX_SITEMAP_URLS) {
                String value = decodeEntities(clean(matcher.group(1)));
                if (isHttp(value)) found.add(canonicalize(value));
            }
            boolean index = fetch.html.toLowerCase(Locale.US).contains("<sitemapindex");
            for (String value : found) {
                if (urls.size() >= MAX_SITEMAP_URLS) break;
                if (index) loadSitemap(value, rootOrigin, config, urls, level + 1);
                else if (sameHost(rootOrigin, value)) urls.add(value);
            }
        } catch (Exception ignored) { }
    }

    private static Fetch fetch(String url, Config config, int limit) throws Exception {
        long started = System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(config.connectTimeoutMs);
        connection.setReadTimeout(config.readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,text/xml,text/plain,*/*;q=0.3");
        connection.setRequestProperty("Accept-Encoding", "identity");
        Fetch fetch = new Fetch();
        fetch.requestedUrl = url;
        try {
            fetch.status = connection.getResponseCode();
            fetch.finalUrl = connection.getURL().toString();
            fetch.contentType = safe(connection.getContentType());
            fetch.server = safe(connection.getHeaderField("Server"));
            fetch.https = connection instanceof HttpsURLConnection;
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) fetch.headers.put(entry.getKey(), entry.getValue().get(0));
            }
            if (connection instanceof HttpsURLConnection) {
                try {
                    Certificate[] certificates = ((HttpsURLConnection) connection).getServerCertificates();
                    if (certificates.length > 0 && certificates[0] instanceof X509Certificate) {
                        X509Certificate certificate = (X509Certificate) certificates[0];
                        certificate.checkValidity();
                        fetch.tlsValid = true;
                        fetch.tlsExpires = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(certificate.getNotAfter());
                    }
                } catch (Exception ignored) { fetch.tlsValid = false; }
            }
            BufferedInputStream input;
            try { input = new BufferedInputStream(connection.getInputStream()); }
            catch (Exception error) {
                if (connection.getErrorStream() == null) throw error;
                input = new BufferedInputStream(connection.getErrorStream());
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0 && total < limit) {
                int allowed = Math.min(read, limit - total);
                output.write(buffer, 0, allowed);
                total += allowed;
            }
            input.close();
            String charset = "UTF-8";
            Matcher charsetMatcher = Pattern.compile("(?i)charset=([\\w\\-]+)").matcher(fetch.contentType);
            if (charsetMatcher.find()) charset = charsetMatcher.group(1);
            try { fetch.html = output.toString(charset); }
            catch (Exception ignored) { fetch.html = output.toString("UTF-8"); }
        } finally {
            fetch.elapsedMs = System.currentTimeMillis() - started;
            connection.disconnect();
        }
        return fetch;
    }

    private static int score(List<Models.Issue> issues) {
        int value = 100;
        for (Models.Issue issue : issues) {
            if ("critical".equals(issue.severity)) value -= 18;
            else if ("important".equals(issue.severity)) value -= 8;
            else if ("warning".equals(issue.severity)) value -= 2;
        }
        return Math.max(0, value);
    }

    private static void add(Models.AuditResult result, String code, String severity, String title, String evidence,
                            String explanation, String recommendation, String category, String affectedUrl) {
        if (result.issues.size() >= 250) return;
        Models.Issue issue = new Models.Issue();
        issue.code = code;
        issue.severity = severity;
        issue.title = title;
        issue.evidence = safe(evidence);
        issue.explanation = explanation;
        issue.recommendation = recommendation;
        issue.category = category;
        issue.affectedUrl = safe(affectedUrl);
        result.issues.add(issue);
    }

    private static String resolveAddresses(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            List<String> values = new ArrayList<String>();
            for (InetAddress address : addresses) if (!values.contains(address.getHostAddress())) values.add(address.getHostAddress());
            return join(values, ", ");
        } catch (Exception ignored) { return ""; }
    }

    private static String first(String value, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String metaContent(String html, String name) {
        Matcher tagMatcher = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(html);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            String attrName = attribute(tag, "name");
            if (name.equalsIgnoreCase(attrName)) return clean(attribute(tag, "content"));
        }
        return "";
    }

    private static String linkHref(String html, String rel) {
        Matcher tagMatcher = Pattern.compile("(?is)<link\\b[^>]*>").matcher(html);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            String attrRel = attribute(tag, "rel");
            if (attrRel.toLowerCase(Locale.US).contains(rel.toLowerCase(Locale.US))) return clean(attribute(tag, "href"));
        }
        return "";
    }

    private static String attribute(String tag, String name) {
        Matcher quoted = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*(['\"])(.*?)\\1").matcher(tag);
        if (quoted.find()) return quoted.group(2);
        Matcher plain = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*([^\\s>]+)").matcher(tag);
        return plain.find() ? plain.group(1) : "";
    }

    private static int count(String value, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(value == null ? "" : value);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static int countImagesMissingAlt(String html) {
        Matcher matcher = Pattern.compile("(?is)<img\\b[^>]*>").matcher(html);
        int missing = 0;
        while (matcher.find()) if (!matcher.group().matches("(?is).*\\balt\\s*=.*")) missing++;
        return missing;
    }

    private static String visibleText(String html) {
        return decodeEntities(html.replaceAll("(?is)<script\\b.*?</script>", " ")
            .replaceAll("(?is)<style\\b.*?</style>", " ")
            .replaceAll("(?is)<noscript\\b.*?</noscript>", " ")
            .replaceAll("(?s)<[^>]+>", " ")
            .replaceAll("\\s+", " ").trim());
    }

    private static String normalizeContent(String text) {
        return normalize(text).replaceAll("\\d+", "#");
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    private static String clean(String value) {
        return decodeEntities(safe(value).replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim());
    }

    private static String decodeEntities(String value) {
        return safe(value).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    private static String absoluteUrl(String base, String value) {
        if (value == null || value.trim().length() == 0) return "";
        try { return new URL(new URL(base), value.trim()).toString(); }
        catch (Exception ignored) { return ""; }
    }

    private static boolean sameHost(String left, String right) {
        try {
            String a = new URL(left).getHost().toLowerCase(Locale.US);
            String b = new URL(right).getHost().toLowerCase(Locale.US);
            return a.equals(b);
        } catch (Exception ignored) { return false; }
    }

    private static boolean isHttp(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static String canonicalize(String raw) {
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.US);
            String host = uri.getHost();
            if (host == null) return raw.trim();
            int port = uri.getPort();
            String path = uri.getRawPath();
            if (path == null || path.length() == 0) path = "/";
            while (path.contains("//")) path = path.replace("//", "/");
            String query = uri.getRawQuery();
            return new URI(scheme, null, host.toLowerCase(Locale.US), port, path, query, null).toString();
        } catch (Exception ignored) { return raw == null ? "" : raw.trim(); }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(Charset.forName("UTF-8")));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception ignored) { return String.valueOf(value.hashCode()); }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
