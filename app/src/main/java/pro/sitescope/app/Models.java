package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() { }

    public static final class Issue {
        public String code = "";
        public String severity = "warning";
        public String title = "";
        public String evidence = "";
        public String explanation = "";
        public String recommendation = "";
        public String category = "technical";
        public String affectedUrl = "";

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("code", code);
                object.put("severity", severity);
                object.put("title", title);
                object.put("evidence", evidence);
                object.put("explanation", explanation);
                object.put("recommendation", recommendation);
                object.put("category", category);
                object.put("affectedUrl", affectedUrl);
            } catch (Exception ignored) { }
            return object;
        }

        public static Issue fromJson(JSONObject object) {
            Issue issue = new Issue();
            issue.code = object.optString("code");
            issue.severity = object.optString("severity", "warning");
            issue.title = object.optString("title");
            issue.evidence = object.optString("evidence");
            issue.explanation = object.optString("explanation");
            issue.recommendation = object.optString("recommendation");
            issue.category = object.optString("category", "technical");
            issue.affectedUrl = object.optString("affectedUrl");
            return issue;
        }
    }

    public static final class AuditResult {
        public String target = "";
        public String finalUrl = "";
        public String host = "";
        public long timestamp;
        public long durationMs;
        public int httpCode;
        public int score;
        public int passedChecks;
        public int wordCount;
        public int linksCount;
        public int imagesCount;
        public int imagesMissingAlt;
        public int h1Count;
        public int titleLength;
        public int descriptionLength;
        public boolean https;
        public boolean robotsAvailable;
        public boolean sitemapAvailable;
        public boolean tlsValid;
        public String tlsExpires = "";
        public String server = "";
        public String contentType = "";
        public String title = "";
        public String description = "";
        public String canonical = "";
        public String ipAddresses = "";
        public String error = "";
        public boolean fullCrawl;
        public int pagesCrawled;
        public int pagesDiscovered;
        public int sitemapUrlCount;
        public int robotsBlockedCount;
        public int brokenInternalLinks;
        public int redirectPages;
        public int noindexPages;
        public int duplicateTitles;
        public int duplicateDescriptions;
        public int duplicateContentGroups;
        public int canonicalProblems;
        public int orphanCandidates;
        public long averageResponseMs;
        public String robotsSummary = "";
        public String sitemapSummary = "";
        public String cmsName = "Не определена";
        public int cmsConfidence;
        public String cmsEvidence = "";
        public String dnsStatus = "Не проверено";
        public String dnsResolver = "";
        public boolean dnsResolversAgree = true;
        public boolean dnsResolversCompared;
        public boolean dnssecPresent;
        public boolean spfPresent;
        public boolean dmarcPresent;
        public boolean caaPresent;
        public long dnsCheckedAt;
        public String domainRegistrar = "";
        public String domainRegistered = "";
        public String domainExpires = "";
        public String domainStatus = "";
        public String domainNameservers = "";
        public String infrastructureAsn = "";
        public String infrastructureProvider = "";
        public String reverseDns = "";
        public final List<TechnologyFinding> technologies = new ArrayList<TechnologyFinding>();
        public final List<DnsRecord> dnsRecords = new ArrayList<DnsRecord>();
        public final List<PageAudit> pages = new ArrayList<PageAudit>();
        public final List<Issue> issues = new ArrayList<Issue>();

        public int countSeverity(String severity) {
            int count = 0;
            for (Issue issue : issues) if (severity.equals(issue.severity)) count++;
            return count;
        }

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("target", target);
                object.put("finalUrl", finalUrl);
                object.put("host", host);
                object.put("timestamp", timestamp);
                object.put("durationMs", durationMs);
                object.put("httpCode", httpCode);
                object.put("score", score);
                object.put("passedChecks", passedChecks);
                object.put("wordCount", wordCount);
                object.put("linksCount", linksCount);
                object.put("imagesCount", imagesCount);
                object.put("imagesMissingAlt", imagesMissingAlt);
                object.put("h1Count", h1Count);
                object.put("titleLength", titleLength);
                object.put("descriptionLength", descriptionLength);
                object.put("https", https);
                object.put("robotsAvailable", robotsAvailable);
                object.put("sitemapAvailable", sitemapAvailable);
                object.put("tlsValid", tlsValid);
                object.put("tlsExpires", tlsExpires);
                object.put("server", server);
                object.put("contentType", contentType);
                object.put("title", title);
                object.put("description", description);
                object.put("canonical", canonical);
                object.put("ipAddresses", ipAddresses);
                object.put("error", error);
                object.put("fullCrawl", fullCrawl);
                object.put("pagesCrawled", pagesCrawled);
                object.put("pagesDiscovered", pagesDiscovered);
                object.put("sitemapUrlCount", sitemapUrlCount);
                object.put("robotsBlockedCount", robotsBlockedCount);
                object.put("brokenInternalLinks", brokenInternalLinks);
                object.put("redirectPages", redirectPages);
                object.put("noindexPages", noindexPages);
                object.put("duplicateTitles", duplicateTitles);
                object.put("duplicateDescriptions", duplicateDescriptions);
                object.put("duplicateContentGroups", duplicateContentGroups);
                object.put("canonicalProblems", canonicalProblems);
                object.put("orphanCandidates", orphanCandidates);
                object.put("averageResponseMs", averageResponseMs);
                object.put("robotsSummary", robotsSummary);
                object.put("sitemapSummary", sitemapSummary);
                object.put("cmsName", cmsName);
                object.put("cmsConfidence", cmsConfidence);
                object.put("cmsEvidence", cmsEvidence);
                object.put("dnsStatus", dnsStatus);
                object.put("dnsResolver", dnsResolver);
                object.put("dnsResolversAgree", dnsResolversAgree);
                object.put("dnsResolversCompared", dnsResolversCompared);
                object.put("dnssecPresent", dnssecPresent);
                object.put("spfPresent", spfPresent);
                object.put("dmarcPresent", dmarcPresent);
                object.put("caaPresent", caaPresent);
                object.put("dnsCheckedAt", dnsCheckedAt);
                object.put("domainRegistrar", domainRegistrar);
                object.put("domainRegistered", domainRegistered);
                object.put("domainExpires", domainExpires);
                object.put("domainStatus", domainStatus);
                object.put("domainNameservers", domainNameservers);
                object.put("infrastructureAsn", infrastructureAsn);
                object.put("infrastructureProvider", infrastructureProvider);
                object.put("reverseDns", reverseDns);
                JSONArray technologiesArray = new JSONArray();
                for (TechnologyFinding finding : technologies) technologiesArray.put(finding.toJson());
                object.put("technologies", technologiesArray);
                JSONArray dnsArray = new JSONArray();
                for (DnsRecord record : dnsRecords) dnsArray.put(record.toJson());
                object.put("dnsRecords", dnsArray);
                JSONArray pageArray = new JSONArray();
                for (PageAudit page : pages) pageArray.put(page.toJson());
                object.put("pages", pageArray);
                JSONArray array = new JSONArray();
                for (Issue issue : issues) array.put(issue.toJson());
                object.put("issues", array);
            } catch (Exception ignored) { }
            return object;
        }

        public static AuditResult fromJson(JSONObject object) {
            AuditResult result = new AuditResult();
            result.target = object.optString("target");
            result.finalUrl = object.optString("finalUrl");
            result.host = object.optString("host");
            result.timestamp = object.optLong("timestamp");
            result.durationMs = object.optLong("durationMs");
            result.httpCode = object.optInt("httpCode");
            result.score = object.optInt("score");
            result.passedChecks = object.optInt("passedChecks");
            result.wordCount = object.optInt("wordCount");
            result.linksCount = object.optInt("linksCount");
            result.imagesCount = object.optInt("imagesCount");
            result.imagesMissingAlt = object.optInt("imagesMissingAlt");
            result.h1Count = object.optInt("h1Count");
            result.titleLength = object.optInt("titleLength");
            result.descriptionLength = object.optInt("descriptionLength");
            result.https = object.optBoolean("https");
            result.robotsAvailable = object.optBoolean("robotsAvailable");
            result.sitemapAvailable = object.optBoolean("sitemapAvailable");
            result.tlsValid = object.optBoolean("tlsValid");
            result.tlsExpires = object.optString("tlsExpires");
            result.server = object.optString("server");
            result.contentType = object.optString("contentType");
            result.title = object.optString("title");
            result.description = object.optString("description");
            result.canonical = object.optString("canonical");
            result.ipAddresses = object.optString("ipAddresses");
            result.error = object.optString("error");
            result.fullCrawl = object.optBoolean("fullCrawl");
            result.pagesCrawled = object.optInt("pagesCrawled");
            result.pagesDiscovered = object.optInt("pagesDiscovered");
            result.sitemapUrlCount = object.optInt("sitemapUrlCount");
            result.robotsBlockedCount = object.optInt("robotsBlockedCount");
            result.brokenInternalLinks = object.optInt("brokenInternalLinks");
            result.redirectPages = object.optInt("redirectPages");
            result.noindexPages = object.optInt("noindexPages");
            result.duplicateTitles = object.optInt("duplicateTitles");
            result.duplicateDescriptions = object.optInt("duplicateDescriptions");
            result.duplicateContentGroups = object.optInt("duplicateContentGroups");
            result.canonicalProblems = object.optInt("canonicalProblems");
            result.orphanCandidates = object.optInt("orphanCandidates");
            result.averageResponseMs = object.optLong("averageResponseMs");
            result.robotsSummary = object.optString("robotsSummary");
            result.sitemapSummary = object.optString("sitemapSummary");
            result.cmsName = object.optString("cmsName", "Не определена");
            result.cmsConfidence = object.optInt("cmsConfidence");
            result.cmsEvidence = object.optString("cmsEvidence");
            result.dnsStatus = object.optString("dnsStatus", "Не проверено");
            result.dnsResolver = object.optString("dnsResolver");
            result.dnsResolversAgree = object.optBoolean("dnsResolversAgree", true);
            result.dnsResolversCompared = object.optBoolean("dnsResolversCompared");
            result.dnssecPresent = object.optBoolean("dnssecPresent");
            result.spfPresent = object.optBoolean("spfPresent");
            result.dmarcPresent = object.optBoolean("dmarcPresent");
            result.caaPresent = object.optBoolean("caaPresent");
            result.dnsCheckedAt = object.optLong("dnsCheckedAt");
            result.domainRegistrar = object.optString("domainRegistrar");
            result.domainRegistered = object.optString("domainRegistered");
            result.domainExpires = object.optString("domainExpires");
            result.domainStatus = object.optString("domainStatus");
            result.domainNameservers = object.optString("domainNameservers");
            result.infrastructureAsn = object.optString("infrastructureAsn");
            result.infrastructureProvider = object.optString("infrastructureProvider");
            result.reverseDns = object.optString("reverseDns");
            JSONArray technologiesArray = object.optJSONArray("technologies");
            if (technologiesArray != null) for (int i = 0; i < technologiesArray.length(); i++) {
                JSONObject item = technologiesArray.optJSONObject(i);
                if (item != null) result.technologies.add(TechnologyFinding.fromJson(item));
            }
            JSONArray dnsArray = object.optJSONArray("dnsRecords");
            if (dnsArray != null) for (int i = 0; i < dnsArray.length(); i++) {
                JSONObject item = dnsArray.optJSONObject(i);
                if (item != null) result.dnsRecords.add(DnsRecord.fromJson(item));
            }
            JSONArray pageArray = object.optJSONArray("pages");
            if (pageArray != null) {
                for (int i = 0; i < pageArray.length(); i++) {
                    JSONObject pageObject = pageArray.optJSONObject(i);
                    if (pageObject != null) result.pages.add(PageAudit.fromJson(pageObject));
                }
            }
            JSONArray array = object.optJSONArray("issues");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject issueObject = array.optJSONObject(i);
                    if (issueObject != null) result.issues.add(Issue.fromJson(issueObject));
                }
            }
            return result;
        }
    }

    public static final class TechnologyFinding {
        public String name = "";
        public String category = "";
        public int confidence;
        public String evidence = "";

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("name", name);
                object.put("category", category);
                object.put("confidence", confidence);
                object.put("evidence", evidence);
            } catch (Exception ignored) { }
            return object;
        }

        public static TechnologyFinding fromJson(JSONObject object) {
            TechnologyFinding finding = new TechnologyFinding();
            finding.name = object.optString("name");
            finding.category = object.optString("category");
            finding.confidence = object.optInt("confidence");
            finding.evidence = object.optString("evidence");
            return finding;
        }
    }

    public static final class DnsRecord {
        public String type = "";
        public String name = "";
        public String value = "";
        public long ttl;

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("type", type);
                object.put("name", name);
                object.put("value", value);
                object.put("ttl", ttl);
            } catch (Exception ignored) { }
            return object;
        }

        public static DnsRecord fromJson(JSONObject object) {
            DnsRecord record = new DnsRecord();
            record.type = object.optString("type");
            record.name = object.optString("name");
            record.value = object.optString("value");
            record.ttl = object.optLong("ttl");
            return record;
        }
    }

    public static final class PageAudit {
        public String requestedUrl = "";
        public String finalUrl = "";
        public String title = "";
        public String description = "";
        public String canonical = "";
        public String robotsMeta = "";
        public String contentType = "";
        public String contentHash = "";
        public int statusCode;
        public int depth;
        public int wordCount;
        public int h1Count;
        public int internalLinks;
        public int externalLinks;
        public int images;
        public int imagesMissingAlt;
        public long responseMs;
        public boolean indexable = true;
        public boolean blockedByRobots;
        public final List<String> discoveredLinks = new ArrayList<String>();

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("requestedUrl", requestedUrl);
                object.put("finalUrl", finalUrl);
                object.put("title", title);
                object.put("description", description);
                object.put("canonical", canonical);
                object.put("robotsMeta", robotsMeta);
                object.put("contentType", contentType);
                object.put("contentHash", contentHash);
                object.put("statusCode", statusCode);
                object.put("depth", depth);
                object.put("wordCount", wordCount);
                object.put("h1Count", h1Count);
                object.put("internalLinks", internalLinks);
                object.put("externalLinks", externalLinks);
                object.put("images", images);
                object.put("imagesMissingAlt", imagesMissingAlt);
                object.put("responseMs", responseMs);
                object.put("indexable", indexable);
                object.put("blockedByRobots", blockedByRobots);
                JSONArray links = new JSONArray();
                for (String link : discoveredLinks) links.put(link);
                object.put("discoveredLinks", links);
            } catch (Exception ignored) { }
            return object;
        }

        public static PageAudit fromJson(JSONObject object) {
            PageAudit page = new PageAudit();
            page.requestedUrl = object.optString("requestedUrl");
            page.finalUrl = object.optString("finalUrl");
            page.title = object.optString("title");
            page.description = object.optString("description");
            page.canonical = object.optString("canonical");
            page.robotsMeta = object.optString("robotsMeta");
            page.contentType = object.optString("contentType");
            page.contentHash = object.optString("contentHash");
            page.statusCode = object.optInt("statusCode");
            page.depth = object.optInt("depth");
            page.wordCount = object.optInt("wordCount");
            page.h1Count = object.optInt("h1Count");
            page.internalLinks = object.optInt("internalLinks");
            page.externalLinks = object.optInt("externalLinks");
            page.images = object.optInt("images");
            page.imagesMissingAlt = object.optInt("imagesMissingAlt");
            page.responseMs = object.optLong("responseMs");
            page.indexable = object.optBoolean("indexable", true);
            page.blockedByRobots = object.optBoolean("blockedByRobots");
            JSONArray links = object.optJSONArray("discoveredLinks");
            if (links != null) for (int i = 0; i < links.length(); i++) page.discoveredLinks.add(links.optString(i));
            return page;
        }
    }

    public static final class Project {
        public String type = "SEO";
        public String name = "";
        public String target = "";
        public String summary = "";
        public String notes = "";
        public String tags = "";
        public boolean pinned;
        public boolean archived;
        public boolean userNamed;
        public long updatedAt;
        public int score = -1;

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("type", type);
                object.put("name", name);
                object.put("target", target);
                object.put("summary", summary);
                object.put("notes", notes);
                object.put("tags", tags);
                object.put("pinned", pinned);
                object.put("archived", archived);
                object.put("userNamed", userNamed);
                object.put("updatedAt", updatedAt);
                object.put("score", score);
            } catch (Exception ignored) { }
            return object;
        }

        public static Project fromJson(JSONObject object) {
            Project project = new Project();
            project.type = object.optString("type", "SEO").toUpperCase();
            project.name = object.optString("name");
            project.target = object.optString("target");
            project.summary = object.optString("summary");
            project.notes = object.optString("notes");
            project.tags = object.optString("tags");
            project.pinned = object.optBoolean("pinned");
            project.archived = object.optBoolean("archived");
            project.userNamed = object.optBoolean("userNamed");
            project.updatedAt = object.optLong("updatedAt");
            project.score = object.optInt("score", -1);
            return project;
        }
    }

    public static final class PortResult {
        public NetworkScope scope;
        public DeviceIdentity identity = new DeviceIdentity();
        public String tlsFingerprint = "";
        public String tlsTrust = "not_applicable";
        public String target = "";
        public String host = "";
        public String ip = "";
        public int port;
        public boolean open;
        public long latencyMs;
        public String service = "Unknown TCP";
        public String resourceType = "Сетевой узел";
        public String hostname = "";
        public String banner = "";
        public String tlsSubject = "";
        public String evidence = "";
        public String confidence = "low";
        public String description = "";

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                if (scope != null) object.put("scope", scope.toJson());
                object.put("target", target);
                object.put("host", host);
                object.put("ip", ip);
                object.put("port", port);
                object.put("open", open);
                object.put("latencyMs", latencyMs);
                object.put("service", service);
                object.put("resourceType", resourceType);
                object.put("hostname", hostname);
                object.put("banner", banner);
                object.put("tlsSubject", tlsSubject);
                object.put("identity", identity.toJson());
                object.put("tlsTrust", tlsTrust);
                object.put("tlsFingerprint", tlsFingerprint);
                object.put("evidence", evidence);
                object.put("confidence", confidence);
                object.put("description", description);
            } catch (Exception ignored) { }
            return object;
        }

        public static PortResult fromJson(JSONObject object) {
            PortResult result = new PortResult();
            if (object.has("scope")) result.scope = NetworkScope.fromJson(object.optJSONObject("scope"), "result");
            result.target = object.optString("target");
            result.host = object.optString("host");
            result.ip = object.optString("ip");
            result.port = object.optInt("port");
            result.open = object.optBoolean("open");
            result.latencyMs = object.optLong("latencyMs");
            result.service = object.optString("service", "Unknown TCP");
            result.resourceType = object.optString("resourceType", "Сетевой узел");
            result.hostname = object.optString("hostname");
            result.banner = object.optString("banner");
            result.tlsSubject = object.optString("tlsSubject");
            result.identity = DeviceIdentity.fromJson(object.optJSONObject("identity"));
            result.tlsFingerprint = object.optString("tlsFingerprint");
            result.tlsTrust = object.optString("tlsTrust", result.tlsSubject.isEmpty() ? "not_applicable" : "not_checked");
            result.evidence = object.optString("evidence");
            result.confidence = object.optString("confidence", "low");
            result.description = object.optString("description");
            return result;
        }

        public String displayLine() {
            String address = ip.length() > 0 ? ip : target;
            return "✅ " + address + ":" + port + "  " + resourceType + "  " + service;
        }
    }

    public static final class ScanSession {
        public NetworkScope scope;
        public String targetExpression = "";
        public String portExpression = "";
        public long timestamp;
        public long durationMs;
        public int totalProbes;
        public int completedProbes;
        public boolean cancelled;
        public boolean recovered;
        public String error = "";
        public final List<PortResult> openResults = new ArrayList<PortResult>();

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("targetExpression", targetExpression);
                if (scope != null) object.put("scope", scope.toJson());
                object.put("portExpression", portExpression);
                object.put("timestamp", timestamp);
                object.put("durationMs", durationMs);
                object.put("totalProbes", totalProbes);
                object.put("completedProbes", completedProbes);
                object.put("cancelled", cancelled);
                object.put("recovered", recovered);
                object.put("error", error);
                JSONArray rows = new JSONArray();
                for (PortResult result : openResults) rows.put(result.toJson());
                object.put("openResults", rows);
            } catch (Exception ignored) { }
            return object;
        }

        public static ScanSession fromJson(JSONObject object) {
            ScanSession session = new ScanSession();
            session.targetExpression = object.optString("targetExpression");
            if (object.has("scope")) session.scope = NetworkScope.fromJson(object.optJSONObject("scope"), "scan");
            session.portExpression = object.optString("portExpression");
            session.timestamp = object.optLong("timestamp");
            session.durationMs = object.optLong("durationMs");
            session.totalProbes = object.optInt("totalProbes");
            session.completedProbes = object.optInt("completedProbes");
            session.cancelled = object.optBoolean("cancelled");
            session.recovered = object.optBoolean("recovered");
            session.error = object.optString("error");
            JSONArray rows = object.optJSONArray("openResults");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) session.openResults.add(PortResult.fromJson(row));
                }
            }
            return session;
        }
    }


    public static final class DiscoveredDevice {
        public NetworkScope scope;
        public String stableId = "";
        public DeviceIdentity identity = new DeviceIdentity();
        public String ip = "";
        public int port;
        public String protocol = "";
        public String deviceType = "Сетевое устройство";
        public String name = "";
        public String hostname = "";
        public String manufacturer = "";
        public String model = "";
        public String location = "";
        public String services = "";
        public String evidence = "";
        public String confidence = "medium";
        public long latencyMs;

        public String key() { return ip + "|" + protocol + "|" + port + "|" + stableId + "|" + (location.length() > 0 ? location : name) + "|" + services; }
        public String displayLine() {
            return "✅ " + ip + (port > 0 ? ":" + port : "") + "  " + deviceType + "  " + (name.length() > 0 ? name : protocol);
        }
    }

    public static final class IptvChannel {
        public String id = "";
        public String name = "";
        public String group = "";
        public String logoUrl = "";
        public String sourceUrl = "";
        public String playbackUrl = "";
        public String sourceType = "M3U";
        public String status = "не проверен";
        public String serviceName = "";
        public String providerName = "";
        public String videoCodec = "";
        public String audioCodec = "";
        public String container = "";
        public String evidence = "";
        public String error = "";
        public long latencyMs;
        public long bitrateKbps;
        public boolean playable;
        public boolean mpegTs;

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("name", name);
                object.put("group", group);
                object.put("logoUrl", logoUrl);
                object.put("sourceUrl", sourceUrl);
                object.put("playbackUrl", playbackUrl);
                object.put("sourceType", sourceType);
                object.put("status", status);
                object.put("serviceName", serviceName);
                object.put("providerName", providerName);
                object.put("videoCodec", videoCodec);
                object.put("audioCodec", audioCodec);
                object.put("container", container);
                object.put("evidence", evidence);
                object.put("error", error);
                object.put("latencyMs", latencyMs);
                object.put("bitrateKbps", bitrateKbps);
                object.put("playable", playable);
                object.put("mpegTs", mpegTs);
            } catch (Exception ignored) { }
            return object;
        }

        public static IptvChannel fromJson(JSONObject object) {
            IptvChannel channel = new IptvChannel();
            channel.id = object.optString("id");
            channel.name = object.optString("name");
            channel.group = object.optString("group");
            channel.logoUrl = object.optString("logoUrl");
            channel.sourceUrl = object.optString("sourceUrl");
            channel.playbackUrl = object.optString("playbackUrl");
            channel.sourceType = object.optString("sourceType", "M3U");
            channel.status = object.optString("status", "не проверен");
            channel.serviceName = object.optString("serviceName");
            channel.providerName = object.optString("providerName");
            channel.videoCodec = object.optString("videoCodec");
            channel.audioCodec = object.optString("audioCodec");
            channel.container = object.optString("container");
            channel.evidence = object.optString("evidence");
            channel.error = object.optString("error");
            channel.latencyMs = object.optLong("latencyMs");
            channel.bitrateKbps = object.optLong("bitrateKbps");
            channel.playable = object.optBoolean("playable");
            channel.mpegTs = object.optBoolean("mpegTs");
            return channel;
        }
    }


    public static final class IptvSnapshot {
        public long timestamp;
        public int channelCount;
        public int playableCount;
        public String source = "";

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("timestamp", timestamp);
                object.put("channelCount", channelCount);
                object.put("playableCount", playableCount);
                object.put("source", source);
            } catch (Exception ignored) { }
            return object;
        }

        public static IptvSnapshot fromJson(JSONObject object) {
            IptvSnapshot value = new IptvSnapshot();
            value.timestamp = object.optLong("timestamp");
            value.channelCount = object.optInt("channelCount");
            value.playableCount = object.optInt("playableCount");
            value.source = object.optString("source");
            return value;
        }
    }

    public static final class UdpxyEndpoint {
        public String host = "";
        public int port;
        public String baseUrl = "";
        public String statusUrl = "";
        public String server = "";
        public String version = "";
        public String evidence = "";
        public long latencyMs;
        public boolean confirmed;

        public String displayLine() {
            return (confirmed ? "✅ " : "⚠ ") + host + ":" + port + "  udpxy" + (version.length() > 0 ? " " + version : "");
        }
    }

}
