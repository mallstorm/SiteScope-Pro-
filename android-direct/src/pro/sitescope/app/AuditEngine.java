package pro.sitescope.app;

import android.util.Patterns;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public final class AuditEngine {
    private static final int MAX_HTML_BYTES = 1_500_000;
    private static final String UA = "SiteScopePro/0.34.0-alpha32 Android";

    private AuditEngine() { }

    public static Models.AuditResult run(String rawTarget) {
        Models.AuditResult result = new Models.AuditResult();
        result.target = normalizeTarget(rawTarget);
        result.timestamp = System.currentTimeMillis();
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(result.target);
            result.host = url.getHost();
            result.ipAddresses = resolveAddresses(result.host);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            result.httpCode = connection.getResponseCode();
            result.finalUrl = connection.getURL().toString();
            result.https = "https".equalsIgnoreCase(connection.getURL().getProtocol());
            result.server = safe(connection.getHeaderField("Server"));
            result.contentType = safe(connection.getContentType());
            String hsts = connection.getHeaderField("Strict-Transport-Security");
            String csp = connection.getHeaderField("Content-Security-Policy");
            String xContent = connection.getHeaderField("X-Content-Type-Options");
            String frame = connection.getHeaderField("X-Frame-Options");

            if (connection instanceof HttpsURLConnection) inspectTls((HttpsURLConnection) connection, result);
            String html = readBody(connection);
            analyzeHtml(html, connection.getURL(), result);
            DomainAnalysisEngine.enrich(result, connection, html);
            result.robotsAvailable = endpointAvailable(connection.getURL(), "/robots.txt", "text/plain");
            result.sitemapAvailable = endpointAvailable(connection.getURL(), "/sitemap.xml", "xml");
            applyRules(result, hsts, csp, xContent, frame, html);
        } catch (Exception error) {
            result.error = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
            add(result, "HTTP_UNREACHABLE", "critical", "Сайт недоступен для анализа", result.error,
                "Приложение не смогло установить соединение или получить корректный HTTP-ответ.",
                "Проверьте адрес, DNS, доступность сервера, TLS-сертификат и ограничения сети.", "technical");
            result.score = 0;
        } finally {
            if (connection != null) connection.disconnect();
            result.durationMs = System.currentTimeMillis() - started;
        }
        return result;
    }

    public static String normalizeTarget(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) return "";
        if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        return value;
    }

    public static String hostFrom(String raw) {
        try { return new URL(normalizeTarget(raw)).getHost(); }
        catch (Exception ignored) { return raw == null ? "" : raw.trim(); }
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
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
        while ((read = input.read(buffer)) >= 0 && total < MAX_HTML_BYTES) {
            int allowed = Math.min(read, MAX_HTML_BYTES - total);
            output.write(buffer, 0, allowed);
            total += allowed;
        }
        input.close();
        String charset = "UTF-8";
        String contentType = connection.getContentType();
        if (contentType != null) {
            Matcher matcher = Pattern.compile("(?i)charset=([\\w\\-]+)").matcher(contentType);
            if (matcher.find()) charset = matcher.group(1);
        }
        try { return output.toString(charset); }
        catch (Exception ignored) { return output.toString("UTF-8"); }
    }

    private static void inspectTls(HttpsURLConnection connection, Models.AuditResult result) {
        try {
            connection.connect();
            Certificate[] certificates = connection.getServerCertificates();
            if (certificates.length > 0 && certificates[0] instanceof X509Certificate) {
                X509Certificate certificate = (X509Certificate) certificates[0];
                certificate.checkValidity();
                result.tlsValid = true;
                result.tlsExpires = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(certificate.getNotAfter());
            }
        } catch (Exception error) {
            result.tlsValid = false;
        }
    }

    private static void analyzeHtml(String html, URL baseUrl, Models.AuditResult result) {
        if (html == null) html = "";
        result.title = clean(first(html, "(?is)<title[^>]*>(.*?)</title>"));
        result.description = metaContent(html, "description");
        result.canonical = linkHref(html, "canonical");
        result.titleLength = result.title.length();
        result.descriptionLength = result.description.length();
        result.h1Count = count(html, "(?is)<h1\\b");
        result.imagesCount = count(html, "(?is)<img\\b");
        result.imagesMissingAlt = countImagesMissingAlt(html);
        result.linksCount = count(html, "(?is)<a\\b[^>]*href\\s*=");
        String visible = html.replaceAll("(?is)<script.*?</script>", " ")
            .replaceAll("(?is)<style.*?</style>", " ")
            .replaceAll("(?s)<[^>]+>", " ")
            .replaceAll("&[a-zA-Z#0-9]+;", " ")
            .replaceAll("\\s+", " ").trim();
        result.wordCount = visible.length() == 0 ? 0 : visible.split("\\s+").length;
    }

    private static void applyRules(Models.AuditResult result, String hsts, String csp, String xContent, String frame, String html) {
        int score = 100;
        int passed = 0;
        if (result.httpCode < 200 || result.httpCode >= 400) {
            score -= 18;
            add(result, "HTTP_STATUS", "critical", "Некорректный HTTP-статус", "HTTP " + result.httpCode,
                "Поисковые роботы и пользователи могут не получить содержимое страницы.",
                "Верните HTTP 200 для рабочей страницы или настройте корректный постоянный редирект.", "technical");
        } else passed++;
        if (!result.https) {
            score -= 15;
            add(result, "HTTPS_MISSING", "critical", "Страница открывается без HTTPS", result.finalUrl,
                "Незашифрованное соединение снижает безопасность и доверие.",
                "Установите действующий TLS-сертификат и перенаправляйте HTTP на HTTPS кодом 301.", "security");
        } else passed++;
        if (result.https && result.tlsValid) passed++;
        else if (result.https) {
            score -= 12;
            add(result, "TLS_INVALID", "critical", "TLS-сертификат не подтверждён", result.tlsExpires,
                "Ошибки сертификата могут полностью блокировать доступ к сайту.",
                "Проверьте срок действия, цепочку доверия и соответствие сертификата имени домена.", "security");
        }
        if (result.titleLength == 0) {
            score -= 12;
            add(result, "TITLE_MISSING", "important", "Отсутствует Title", "Тег <title> не найден",
                "Title помогает поисковой системе понять тему страницы и формирует заголовок сниппета.",
                "Добавьте уникальный информативный Title примерно 30–60 символов.", "content");
        } else if (result.titleLength < 20 || result.titleLength > 70) {
            score -= 5;
            add(result, "TITLE_LENGTH", "warning", "Неоптимальная длина Title", result.titleLength + " символов",
                "Слишком короткий Title малоинформативен, а слишком длинный может быть обрезан.",
                "Сформулируйте уникальный Title длиной около 30–60 символов без переспама.", "content");
        } else passed++;
        if (result.descriptionLength == 0) {
            score -= 8;
            add(result, "DESCRIPTION_MISSING", "important", "Отсутствует meta description", "description не найден",
                "Поисковая система может сформировать менее управляемый сниппет.",
                "Добавьте уникальное описание страницы примерно 120–170 символов.", "content");
        } else if (result.descriptionLength < 70 || result.descriptionLength > 190) {
            score -= 3;
            add(result, "DESCRIPTION_LENGTH", "warning", "Неоптимальная длина description", result.descriptionLength + " символов",
                "Описание может быть недостаточно информативным или обрезаться.",
                "Сократите или расширьте description, сохранив естественное описание содержания.", "content");
        } else passed++;
        if (result.h1Count == 0) {
            score -= 8;
            add(result, "H1_MISSING", "important", "Отсутствует H1", "Найдено H1: 0",
                "Основной заголовок помогает структурировать содержимое страницы.",
                "Добавьте один видимый H1, точно описывающий основную тему страницы.", "content");
        } else if (result.h1Count > 1) {
            score -= 4;
            add(result, "H1_MULTIPLE", "warning", "Несколько заголовков H1", "Найдено H1: " + result.h1Count,
                "Множественные основные заголовки могут размывать структуру документа.",
                "Оставьте один основной H1, остальные заголовки оформите H2–H3.", "content");
        } else passed++;
        if (result.canonical.length() == 0) {
            score -= 5;
            add(result, "CANONICAL_MISSING", "warning", "Не указан canonical", "rel=canonical не найден",
                "Canonical помогает управлять дублями и параметрическими URL.",
                "Добавьте абсолютную самоссылочную canonical-ссылку в <head>.", "technical");
        } else passed++;
        if (!containsMeta(html, "viewport")) {
            score -= 6;
            add(result, "VIEWPORT_MISSING", "important", "Не задан мобильный viewport", "meta viewport не найден",
                "Страница может некорректно отображаться на мобильных устройствах.",
                "Добавьте <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">.", "mobile");
        } else passed++;
        if (!Pattern.compile("(?is)<html[^>]+lang\\s*=").matcher(html).find()) {
            score -= 3;
            add(result, "LANG_MISSING", "warning", "Не указан язык документа", "Атрибут lang не найден",
                "Язык документа важен для доступности и корректной обработки содержимого.",
                "Добавьте атрибут lang к элементу html, например lang=\"ru\".", "accessibility");
        } else passed++;
        if (result.imagesMissingAlt > 0) {
            int deduction = Math.min(8, result.imagesMissingAlt * 2);
            score -= deduction;
            add(result, "IMAGE_ALT_MISSING", "warning", "У изображений отсутствует alt",
                result.imagesMissingAlt + " из " + result.imagesCount,
                "Alt-текст помогает поиску изображений и пользователям экранных дикторов.",
                "Добавьте содержательный alt значимым изображениям; декоративным оставьте пустой alt.", "content");
        } else if (result.imagesCount > 0) passed++;
        if (result.wordCount < 120) {
            score -= 5;
            add(result, "THIN_CONTENT", "warning", "Мало текстового содержания", result.wordCount + " слов",
                "Страница может недостаточно полно отвечать на пользовательский запрос.",
                "Проверьте назначение страницы и дополните её полезным, уникальным содержанием.", "content");
        } else passed++;
        if (hsts == null && result.https) {
            score -= 4;
            add(result, "HSTS_MISSING", "warning", "Не настроен HSTS", "Заголовок отсутствует",
                "HSTS снижает риск перехода пользователя на незашифрованную версию сайта.",
                "После проверки HTTPS добавьте Strict-Transport-Security с безопасным max-age.", "security");
        } else if (result.https) passed++;
        if (csp == null) {
            score -= 3;
            add(result, "CSP_MISSING", "recommendation", "Не настроена Content Security Policy", "Заголовок отсутствует",
                "CSP уменьшает последствия внедрения нежелательных скриптов.",
                "Начните с Content-Security-Policy-Report-Only, затем внедрите рабочую политику.", "security");
        } else passed++;
        if (xContent == null) {
            score -= 2;
            add(result, "NOSNIFF_MISSING", "recommendation", "Отсутствует X-Content-Type-Options", "Заголовок отсутствует",
                "Браузер может пытаться определить тип содержимого самостоятельно.",
                "Добавьте заголовок X-Content-Type-Options: nosniff.", "security");
        } else passed++;
        if (frame == null && csp == null) {
            score -= 2;
            add(result, "FRAME_POLICY_MISSING", "recommendation", "Не задана защита от встраивания", "X-Frame-Options/CSP не найдены",
                "Страница может быть встроена на сторонний ресурс без ограничений.",
                "Настройте CSP frame-ancestors либо X-Frame-Options согласно требованиям проекта.", "security");
        } else passed++;
        if (!result.robotsAvailable) {
            score -= 3;
            add(result, "ROBOTS_UNAVAILABLE", "warning", "robots.txt не найден", "/robots.txt недоступен",
                "Файл используется для управления обходом сайта поисковыми роботами.",
                "Создайте корректный robots.txt и добавьте ссылку на sitemap.", "indexing");
        } else passed++;
        if (!result.sitemapAvailable) {
            score -= 3;
            add(result, "SITEMAP_UNAVAILABLE", "warning", "sitemap.xml не найден", "/sitemap.xml недоступен",
                "Sitemap ускоряет обнаружение важных URL и сообщает об их обновлениях.",
                "Сформируйте XML sitemap и укажите его в robots.txt и панелях вебмастеров.", "indexing");
        } else passed++;
        result.passedChecks = passed;
        result.score = Math.max(0, Math.min(100, score));
    }

    private static boolean endpointAvailable(URL base, String path, String expected) {
        HttpURLConnection connection = null;
        try {
            URL url = new URI(base.getProtocol(), null, base.getHost(), base.getPort(), path, null, null).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", UA);
            int code = connection.getResponseCode();
            String type = safe(connection.getContentType()).toLowerCase(Locale.US);
            return code >= 200 && code < 400 && (expected.length() == 0 || type.contains(expected));
        } catch (Exception ignored) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static void add(Models.AuditResult result, String code, String severity, String title,
                            String evidence, String explanation, String recommendation, String category) {
        Models.Issue issue = new Models.Issue();
        issue.code = code;
        issue.severity = severity;
        issue.title = title;
        issue.evidence = evidence;
        issue.explanation = explanation;
        issue.recommendation = recommendation;
        issue.category = category;
        result.issues.add(issue);
    }

    private static String resolveAddresses(String host) {
        StringBuilder value = new StringBuilder();
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (value.length() > 0) value.append(", ");
                value.append(address.getHostAddress());
            }
        } catch (Exception ignored) { }
        return value.toString();
    }

    private static String metaContent(String html, String name) {
        Matcher matcher = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            String key = attribute(tag, "name");
            if (name.equalsIgnoreCase(key)) return clean(attribute(tag, "content"));
        }
        return "";
    }

    private static boolean containsMeta(String html, String name) {
        return metaContent(html, name).length() > 0;
    }

    private static String linkHref(String html, String rel) {
        Matcher matcher = Pattern.compile("(?is)<link\\b[^>]*>").matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            String key = attribute(tag, "rel");
            if (key.toLowerCase(Locale.US).contains(rel.toLowerCase(Locale.US))) return clean(attribute(tag, "href"));
        }
        return "";
    }

    private static String attribute(String tag, String name) {
        Matcher quoted = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*([\"'])(.*?)\\1").matcher(tag);
        if (quoted.find()) return quoted.group(2);
        Matcher plain = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*([^\\s>]+)").matcher(tag);
        return plain.find() ? plain.group(1) : "";
    }

    private static int countImagesMissingAlt(String html) {
        int count = 0;
        Matcher matcher = Pattern.compile("(?is)<img\\b[^>]*>").matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            if (!Pattern.compile("(?is)\\balt\\s*=").matcher(tag).find()) count++;
        }
        return count;
    }

    private static int count(String value, String regex) {
        int count = 0;
        Matcher matcher = Pattern.compile(regex).matcher(value);
        while (matcher.find()) count++;
        return count;
    }

    private static String first(String value, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("(?s)<[^>]+>", " ")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replaceAll("\\s+", " ").trim();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
