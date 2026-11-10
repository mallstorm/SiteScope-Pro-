package pro.sitescope.app;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Weighted, evidence-based technology detection. It never probes login/admin endpoints. */
public final class TechnologyAnalyzer {
    private TechnologyAnalyzer() { }

    private static final class Candidate {
        final String name;
        final String category;
        int score;
        final List<String> evidence = new ArrayList<String>();
        Candidate(String name, String category) { this.name = name; this.category = category; }
    }

    public static void analyze(Models.AuditResult result, HttpURLConnection connection, String html) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (connection != null && connection.getHeaderFields() != null) {
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                headers.put(entry.getKey().toLowerCase(Locale.US), join(entry.getValue(), "; "));
            }
        }
        analyze(result, headers, html);
    }

    public static void analyze(Models.AuditResult result, Map<String, String> headers, String html) {
        result.technologies.clear();
        Map<String, String> normalizedHeaders = new LinkedHashMap<String, String>();
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null) normalizedHeaders.put(entry.getKey().toLowerCase(Locale.US), entry.getValue() == null ? "" : entry.getValue());
        }
        headers = normalizedHeaders;
        Map<String, Candidate> candidates = new LinkedHashMap<String, Candidate>();
        String source = html == null ? "" : html;
        String lower = source.toLowerCase(Locale.US);
        String headerText = flatten(headers).toLowerCase(Locale.US);
        String generator = metaGenerator(source);
        String generatorLower = generator.toLowerCase(Locale.US);
        String cookies = value(headers, "set-cookie").toLowerCase(Locale.US);
        String server = value(headers, "server");
        String powered = value(headers, "x-powered-by");

        // CMS and commerce systems.
        signal(candidates, "WordPress", "CMS", generatorLower.contains("wordpress"), 75, "meta generator: " + generator);
        signal(candidates, "WordPress", "CMS", lower.contains("/wp-content/") || lower.contains("wp-content/"), 38, "пути wp-content");
        signal(candidates, "WordPress", "CMS", lower.contains("/wp-includes/") || lower.contains("wp-json"), 26, "пути wp-includes/wp-json");
        signal(candidates, "WordPress", "CMS", cookies.contains("wordpress_") || cookies.contains("wp-settings"), 30, "WordPress cookie");
        signal(candidates, "WooCommerce", "Интернет-магазин", lower.contains("woocommerce") || cookies.contains("woocommerce_"), 72, "WooCommerce signature");

        signal(candidates, "Joomla", "CMS", generatorLower.contains("joomla"), 80, "meta generator: " + generator);
        signal(candidates, "Joomla", "CMS", lower.contains("option=com_") || lower.contains("/media/system/js/"), 36, "Joomla component paths");
        signal(candidates, "Drupal", "CMS", generatorLower.contains("drupal"), 80, "meta generator: " + generator);
        signal(candidates, "Drupal", "CMS", lower.contains("drupalsettings") || lower.contains("/sites/default/files/"), 45, "Drupal HTML paths");
        signal(candidates, "1C-Битрикс", "CMS", lower.contains("/bitrix/") || lower.contains("bitrix_sm_"), 68, "Bitrix path/cookie");
        signal(candidates, "1C-Битрикс", "CMS", cookies.contains("bitrix_sm_"), 35, "BITRIX_SM cookie");
        signal(candidates, "OpenCart", "CMS", lower.contains("catalog/view/theme/") || lower.contains("route=common/home"), 70, "OpenCart paths");
        signal(candidates, "MODX", "CMS", generatorLower.contains("modx"), 78, "meta generator: " + generator);
        signal(candidates, "MODX", "CMS", lower.contains("/assets/components/") && lower.contains("modx"), 42, "MODX assets");
        signal(candidates, "PrestaShop", "CMS", lower.contains("prestashop") || cookies.contains("prestashop-"), 75, "PrestaShop signature");
        signal(candidates, "Magento", "CMS", lower.contains("mage/cookies") || lower.contains("magento_") || lower.contains("/static/version"), 70, "Magento assets");
        signal(candidates, "Shopify", "SaaS/CMS", lower.contains("cdn.shopify.com") || lower.contains("shopify.theme") || headerText.contains("x-shopid"), 82, "Shopify CDN/header");
        signal(candidates, "Tilda", "Конструктор", lower.contains("static.tildacdn") || lower.contains("tilda-blocks"), 85, "Tilda CDN/assets");
        signal(candidates, "Wix", "Конструктор", lower.contains("wixstatic.com") || headerText.contains("x-wix-request-id"), 85, "Wix static/header");
        signal(candidates, "Webflow", "Конструктор", lower.contains("data-wf-page") || lower.contains("webflow.js"), 82, "Webflow HTML/assets");
        signal(candidates, "Ghost", "CMS", generatorLower.contains("ghost") || lower.contains("ghost.io"), 80, "Ghost generator/assets");
        signal(candidates, "DataLife Engine", "CMS", lower.contains("dle-content") || lower.contains("/engine/ajax/"), 65, "DLE HTML paths");

        // Front-end frameworks and server-side platforms.
        signal(candidates, "Next.js", "Frontend", lower.contains("__next_data__") || lower.contains("/_next/static/"), 88, "Next.js bootstrap/assets");
        signal(candidates, "Nuxt", "Frontend", lower.contains("__nuxt__") || lower.contains("/_nuxt/"), 85, "Nuxt bootstrap/assets");
        signal(candidates, "React", "Frontend", lower.contains("data-reactroot") || lower.contains("react-dom") || lower.contains("react.production.min.js"), 62, "React markers");
        signal(candidates, "Vue.js", "Frontend", lower.contains("vue.runtime") || lower.contains("vue.global") || lower.contains("data-v-"), 58, "Vue markers");
        signal(candidates, "Angular", "Frontend", lower.contains("ng-version=") || lower.contains("ng-app="), 82, "Angular attributes");
        signal(candidates, "Laravel", "Backend", cookies.contains("laravel_session") || powered.toLowerCase(Locale.US).contains("laravel"), 88, "Laravel cookie/header");
        signal(candidates, "Django", "Backend", cookies.contains("csrftoken") && cookies.contains("sessionid"), 68, "Django-style cookies");
        signal(candidates, "ASP.NET", "Backend", cookies.contains("asp.net_sessionid") || headerText.contains("x-aspnet-version") || lower.contains("__viewstate"), 86, "ASP.NET cookie/header/VIEWSTATE");
        signal(candidates, "PHP", "Backend", powered.toLowerCase(Locale.US).contains("php") || cookies.contains("phpsessid"), 72, powered.length() > 0 ? "X-Powered-By: " + powered : "PHPSESSID cookie");

        // Infrastructure and analytics.
        if (server.length() > 0) add(candidates, cleanProduct(server), "Веб-сервер", 92, "Server: " + server);
        signal(candidates, "Cloudflare", "CDN/WAF", headerText.contains("cf-ray") || headerText.contains("server: cloudflare") || lower.contains("cdnjs.cloudflare.com"), 92, "Cloudflare header/CDN");
        signal(candidates, "Google Analytics 4", "Аналитика", lower.contains("gtag('config'") || lower.contains("googletagmanager.com/gtag/js"), 84, "gtag.js");
        signal(candidates, "Google Tag Manager", "Теги", lower.contains("googletagmanager.com/gtm.js") || lower.contains("gtm-"), 80, "GTM container");
        signal(candidates, "Яндекс Метрика", "Аналитика", lower.contains("mc.yandex.ru/metrika") || lower.contains("ym("), 82, "Yandex Metrica script");
        signal(candidates, "jQuery", "JavaScript", lower.contains("jquery") && (lower.contains(".js") || lower.contains("jquery.min")), 65, "jQuery asset");
        signal(candidates, "Bootstrap", "UI framework", lower.contains("bootstrap.min.css") || lower.contains("bootstrap.bundle"), 72, "Bootstrap assets");

        List<Candidate> ordered = new ArrayList<Candidate>(candidates.values());
        Collections.sort(ordered, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) { return b.score - a.score; }
        });
        Candidate bestCms = null;
        for (Candidate candidate : ordered) {
            if (candidate.score < 28) continue;
            Models.TechnologyFinding finding = new Models.TechnologyFinding();
            finding.name = candidate.name;
            finding.category = candidate.category;
            finding.confidence = Math.min(99, candidate.score);
            finding.evidence = join(candidate.evidence, "; ");
            result.technologies.add(finding);
            if (("CMS".equals(candidate.category) || "SaaS/CMS".equals(candidate.category) || "Конструктор".equals(candidate.category)) && bestCms == null) bestCms = candidate;
            if (result.technologies.size() >= 18) break;
        }
        if (bestCms != null) {
            result.cmsName = bestCms.name;
            result.cmsConfidence = Math.min(99, bestCms.score);
            result.cmsEvidence = join(bestCms.evidence, "; ");
        } else {
            result.cmsName = "Не определена";
            result.cmsConfidence = 0;
            result.cmsEvidence = "Надёжных сигнатур CMS не найдено";
        }
    }

    private static void signal(Map<String, Candidate> map, String name, String category, boolean condition, int score, String evidence) {
        if (condition) add(map, name, category, score, evidence);
    }

    private static void add(Map<String, Candidate> map, String name, String category, int score, String evidence) {
        String key = name.toLowerCase(Locale.US);
        Candidate candidate = map.get(key);
        if (candidate == null) { candidate = new Candidate(name, category); map.put(key, candidate); }
        candidate.score = Math.min(99, candidate.score + score);
        if (evidence != null && evidence.trim().length() > 0 && !candidate.evidence.contains(evidence)) candidate.evidence.add(evidence);
    }

    private static String metaGenerator(String html) {
        Matcher tags = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(html == null ? "" : html);
        while (tags.find()) {
            String tag = tags.group();
            String name = attribute(tag, "name");
            if ("generator".equalsIgnoreCase(name)) return attribute(tag, "content");
        }
        return "";
    }

    private static String attribute(String tag, String name) {
        Matcher quoted = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*(['\"])(.*?)\\1").matcher(tag);
        if (quoted.find()) return quoted.group(2).trim();
        Matcher plain = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*([^\\s>]+)").matcher(tag);
        return plain.find() ? plain.group(1).trim() : "";
    }

    private static String value(Map<String, String> headers, String key) {
        if (headers == null) return "";
        String value = headers.get(key.toLowerCase(Locale.US));
        return value == null ? "" : value;
    }

    private static String flatten(Map<String, String> headers) {
        StringBuilder out = new StringBuilder();
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) out.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        return out.toString();
    }

    private static String cleanProduct(String server) {
        String value = server == null ? "" : server.trim();
        if (value.length() == 0) return "Веб-сервер";
        int space = value.indexOf(' ');
        return space > 0 ? value.substring(0, space) : value;
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().length() == 0) continue;
            if (out.length() > 0) out.append(delimiter);
            out.append(value.trim());
        }
        return out.toString();
    }
}
