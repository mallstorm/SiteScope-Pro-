package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Official-provider integration layer for current search metrics.
 * It deliberately exposes no fabricated "Google Rank" and no obsolete tIC.
 */
public final class SearchMetricsEngine {
    private static final int TIMEOUT = 25000;
    private static final String UA = "SiteScopePro/0.28.0-alpha26 ModernSearchMetrics";

    private SearchMetricsEngine() { }

    public static final class Config {
        public String targetUrl = "";
        public String pageSpeedKey = "";
        public String googleAccessToken = "";
        public String googleProperty = "";
        public String yandexAccessToken = "";
    }

    public static final class Result {
        public String targetUrl = "";
        public long timestamp;
        public PageSpeed mobile = new PageSpeed();
        public PageSpeed desktop = new PageSpeed();
        public SearchConsole google = new SearchConsole();
        public Yandex yandex = new Yandex();
        public int searchReadiness;
        public String readinessExplanation = "";
    }

    public static final class PageSpeed {
        public boolean requested;
        public boolean available;
        public String strategy = "";
        public int performance = -1;
        public int accessibility = -1;
        public int bestPractices = -1;
        public int seo = -1;
        public double lcpMs = -1;
        public double inpMs = -1;
        public double cls = -1;
        public double fcpMs = -1;
        public double ttfbMs = -1;
        public double tbtMs = -1;
        public String fieldAssessment = "NO_DATA";
        public String source = "Google PageSpeed Insights API / Lighthouse";
        public String error = "";
    }

    public static final class SearchConsole {
        public boolean requested;
        public boolean available;
        public String property = "";
        public String dateFrom = "";
        public String dateTo = "";
        public double clicks;
        public double impressions;
        public double ctr;
        public double position;
        public String source = "Google Search Console API";
        public String error = "";
    }

    public static final class Yandex {
        public boolean requested;
        public boolean available;
        public String hostId = "";
        public String hostUrl = "";
        public boolean verified;
        public long sqi = -1;
        public String sqiDate = "";
        public long searchablePages = -1;
        public int fatalProblems = -1;
        public int criticalProblems = -1;
        public String source = "Яндекс Вебмастер API · ИКС";
        public String error = "";
    }

    public static Result collect(Config config, Models.AuditResult audit) {
        Result result = new Result();
        result.targetUrl = normalizeUrl(config.targetUrl);
        result.timestamp = System.currentTimeMillis();
        result.mobile = pageSpeed(result.targetUrl, "mobile", config.pageSpeedKey);
        result.desktop = pageSpeed(result.targetUrl, "desktop", config.pageSpeedKey);
        if (notEmpty(config.googleAccessToken)) {
            result.google = searchConsole(config.googleAccessToken,
                notEmpty(config.googleProperty) ? config.googleProperty : result.targetUrl);
        }
        if (notEmpty(config.yandexAccessToken)) {
            result.yandex = yandex(config.yandexAccessToken, result.targetUrl);
        }
        result.searchReadiness = readiness(audit, result);
        result.readinessExplanation = "Расчётная метрика SiteScope Pro: техническое SEO, индексируемость, безопасность и PageSpeed. " +
            "Не является оценкой или фактором ранжирования Google/Яндекса.";
        return result;
    }

    public static PageSpeed pageSpeed(String url, String strategy, String apiKey) {
        PageSpeed out = new PageSpeed();
        out.requested = true;
        out.strategy = strategy;
        try {
            String endpoint = "https://www.googleapis.com/pagespeedonline/v5/runPagespeed?url=" + enc(normalizeUrl(url)) +
                "&strategy=" + enc(strategy) + "&category=performance&category=accessibility&category=best-practices&category=seo";
            if (notEmpty(apiKey)) endpoint += "&key=" + enc(apiKey.trim());
            JSONObject root = requestJson("GET", endpoint, null, null);
            JSONObject lighthouse = root.optJSONObject("lighthouseResult");
            JSONObject categories = lighthouse == null ? null : lighthouse.optJSONObject("categories");
            out.performance = categoryScore(categories, "performance");
            out.accessibility = categoryScore(categories, "accessibility");
            out.bestPractices = categoryScore(categories, "best-practices");
            out.seo = categoryScore(categories, "seo");

            JSONObject loading = root.optJSONObject("loadingExperience");
            JSONObject metrics = loading == null ? null : loading.optJSONObject("metrics");
            out.fieldAssessment = loading == null ? "NO_DATA" : loading.optString("overall_category", "NO_DATA");
            out.lcpMs = percentile(metrics, "LARGEST_CONTENTFUL_PAINT_MS");
            out.inpMs = percentile(metrics, "INTERACTION_TO_NEXT_PAINT");
            out.cls = percentile(metrics, "CUMULATIVE_LAYOUT_SHIFT_SCORE");
            if (out.cls >= 0 && out.cls > 1) out.cls = out.cls / 100.0;
            out.fcpMs = percentile(metrics, "FIRST_CONTENTFUL_PAINT_MS");
            out.ttfbMs = percentile(metrics, "EXPERIMENTAL_TIME_TO_FIRST_BYTE");

            JSONObject audits = lighthouse == null ? null : lighthouse.optJSONObject("audits");
            if (out.lcpMs < 0) out.lcpMs = auditNumeric(audits, "largest-contentful-paint");
            if (out.cls < 0) out.cls = auditNumeric(audits, "cumulative-layout-shift");
            out.tbtMs = auditNumeric(audits, "total-blocking-time");
            out.available = out.performance >= 0 || out.seo >= 0 || out.lcpMs >= 0;
        } catch (Exception e) {
            out.error = friendly(e);
        }
        return out;
    }

    public static SearchConsole searchConsole(String token, String property) {
        SearchConsole out = new SearchConsole();
        out.requested = true;
        out.property = normalizeProperty(property);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Date end = calendar.getTime();
        calendar.add(Calendar.DAY_OF_YEAR, -27);
        Date start = calendar.getTime();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        out.dateFrom = format.format(start);
        out.dateTo = format.format(end);
        try {
            JSONObject body = new JSONObject();
            body.put("startDate", out.dateFrom);
            body.put("endDate", out.dateTo);
            body.put("rowLimit", 1);
            body.put("dataState", "final");
            String endpoint = "https://searchconsole.googleapis.com/webmasters/v3/sites/" + enc(out.property) + "/searchAnalytics/query";
            JSONObject root = requestJson("POST", endpoint, body.toString(), "Bearer " + token.trim());
            JSONArray rows = root.optJSONArray("rows");
            if (rows != null && rows.length() > 0) {
                JSONObject row = rows.optJSONObject(0);
                out.clicks = row.optDouble("clicks", 0);
                out.impressions = row.optDouble("impressions", 0);
                out.ctr = row.optDouble("ctr", 0);
                out.position = row.optDouble("position", 0);
            }
            out.available = true;
        } catch (Exception e) {
            out.error = friendly(e);
        }
        return out;
    }

    public static Yandex yandex(String token, String targetUrl) {
        Yandex out = new Yandex();
        out.requested = true;
        try {
            String authorization = "OAuth " + token.trim();
            JSONObject user = requestJson("GET", "https://api.webmaster.yandex.net/v4/user", null, authorization);
            long userId = user.optLong("user_id", -1);
            if (userId < 0) throw new Exception("Яндекс API не вернул user_id");
            JSONObject hosts = requestJson("GET", "https://api.webmaster.yandex.net/v4/user/" + userId + "/hosts", null, authorization);
            JSONArray array = hosts.optJSONArray("hosts");
            String targetHost = new URI(normalizeUrl(targetUrl)).getHost();
            JSONObject selected = null;
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    String ascii = item.optString("ascii_host_url", "");
                    String unicode = item.optString("unicode_host_url", "");
                    if (hostEquals(ascii, targetHost) || hostEquals(unicode, targetHost)) { selected = item; break; }
                }
                if (selected == null && array.length() == 1) selected = array.optJSONObject(0);
            }
            if (selected == null) throw new Exception("Сайт не найден среди подтверждённых ресурсов Яндекс Вебмастера");
            out.hostId = selected.optString("host_id", "");
            out.hostUrl = selected.optString("unicode_host_url", selected.optString("ascii_host_url", ""));
            out.verified = selected.optBoolean("verified", false);
            if (!notEmpty(out.hostId)) throw new Exception("Яндекс API не вернул host_id");

            String base = "https://api.webmaster.yandex.net/v4/user/" + userId + "/hosts/" + enc(out.hostId);
            try {
                JSONObject history = requestJson("GET", base + "/sqi-history", null, authorization);
                JSONArray points = history.optJSONArray("points");
                if (points != null && points.length() > 0) {
                    JSONObject point = points.optJSONObject(points.length() - 1);
                    out.sqi = point.optLong("value", -1);
                    out.sqiDate = point.optString("date", "");
                }
            } catch (Exception ignored) { }
            try {
                JSONObject summary = requestJson("GET", base + "/summary", null, authorization);
                out.searchablePages = firstLong(summary, "searchable_pages_count", "searchable_pages", "pages_in_search");
                JSONObject problems = summary.optJSONObject("site_problems");
                if (problems != null) {
                    out.fatalProblems = problems.optInt("FATAL", problems.optInt("fatal", -1));
                    out.criticalProblems = problems.optInt("CRITICAL", problems.optInt("critical", -1));
                }
            } catch (Exception ignored) { }
            out.available = true;
        } catch (Exception e) {
            out.error = friendly(e);
        }
        return out;
    }

    private static int readiness(Models.AuditResult audit, Result metrics) {
        int technical = audit == null ? 50 : audit.score;
        int speed = 50;
        int count = 0;
        int total = 0;
        if (metrics.mobile.available && metrics.mobile.performance >= 0) { total += metrics.mobile.performance; count++; }
        if (metrics.mobile.available && metrics.mobile.seo >= 0) { total += metrics.mobile.seo; count++; }
        if (metrics.desktop.available && metrics.desktop.performance >= 0) { total += metrics.desktop.performance; count++; }
        if (count > 0) speed = total / count;
        int value = (technical * 65 + speed * 35) / 100;
        return Math.max(0, Math.min(100, value));
    }

    private static JSONObject requestJson(String method, String endpoint, String body, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", UA);
        if (notEmpty(authorization)) connection.setRequestProperty("Authorization", authorization);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = read(input, 4 * 1024 * 1024);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            String message = text;
            try {
                JSONObject error = new JSONObject(text).optJSONObject("error");
                if (error != null) message = error.optString("message", text);
                else message = new JSONObject(text).optString("error_message", text);
            } catch (Exception ignored) { }
            throw new Exception("HTTP " + code + ": " + trim(message, 300));
        }
        return new JSONObject(text.length() == 0 ? "{}" : text);
    }

    private static String read(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (total + read > limit) read = limit - total;
            if (read > 0) output.write(buffer, 0, read);
            total += read;
            if (total >= limit) break;
        }
        input.close();
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int categoryScore(JSONObject categories, String key) {
        if (categories == null) return -1;
        JSONObject item = categories.optJSONObject(key);
        if (item == null || !item.has("score")) return -1;
        return (int) Math.round(item.optDouble("score", -1) * 100.0);
    }

    private static double percentile(JSONObject metrics, String key) {
        if (metrics == null) return -1;
        JSONObject item = metrics.optJSONObject(key);
        return item == null ? -1 : item.optDouble("percentile", -1);
    }

    private static double auditNumeric(JSONObject audits, String key) {
        if (audits == null) return -1;
        JSONObject item = audits.optJSONObject(key);
        return item == null ? -1 : item.optDouble("numericValue", -1);
    }

    private static long firstLong(JSONObject value, String... keys) {
        for (String key : keys) if (value.has(key)) return value.optLong(key, -1);
        return -1;
    }

    private static boolean hostEquals(String url, String targetHost) {
        if (!notEmpty(url) || !notEmpty(targetHost)) return false;
        try {
            String host = new URI(url).getHost();
            return host != null && (host.equalsIgnoreCase(targetHost) || host.equalsIgnoreCase("www." + targetHost) || targetHost.equalsIgnoreCase("www." + host));
        } catch (Exception ignored) {
            return url.toLowerCase(Locale.US).contains(targetHost.toLowerCase(Locale.US));
        }
    }

    private static String normalizeProperty(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("sc-domain:")) return trimmed;
        return normalizeUrl(trimmed);
    }

    public static String normalizeUrl(String value) {
        String target = value == null ? "" : value.trim();
        if (!target.matches("(?i)^https?://.*")) target = "https://" + target;
        return target;
    }

    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static boolean notEmpty(String value) { return value != null && value.trim().length() > 0; }
    private static String friendly(Exception value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
    private static String trim(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "…"; }
}
