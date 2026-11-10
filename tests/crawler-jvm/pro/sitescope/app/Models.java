package pro.sitescope.app;
import java.util.ArrayList;
import java.util.List;
public final class Models {
 public static final class Issue { public String code="",severity="warning",title="",evidence="",explanation="",recommendation="",category="technical",affectedUrl=""; }
 public static final class PageAudit {
  public String requestedUrl="",finalUrl="",title="",description="",canonical="",robotsMeta="",contentType="",contentHash="";
  public int statusCode,depth,wordCount,h1Count,internalLinks,externalLinks,images,imagesMissingAlt; public long responseMs;
  public boolean indexable=true,blockedByRobots; public final List<String> discoveredLinks=new ArrayList<String>();
 }
 public static final class AuditResult {
  public String target="",finalUrl="",host="",tlsExpires="",server="",contentType="",title="",description="",canonical="",ipAddresses="",error="",robotsSummary="",sitemapSummary="";
  public long timestamp,durationMs,averageResponseMs; public int httpCode,score,passedChecks,wordCount,linksCount,imagesCount,imagesMissingAlt,h1Count,titleLength,descriptionLength,pagesCrawled,pagesDiscovered,sitemapUrlCount,robotsBlockedCount,brokenInternalLinks,redirectPages,noindexPages,duplicateTitles,duplicateDescriptions,duplicateContentGroups,canonicalProblems,orphanCandidates;
  public boolean https,robotsAvailable,sitemapAvailable,tlsValid,fullCrawl; public final List<PageAudit> pages=new ArrayList<PageAudit>(); public final List<Issue> issues=new ArrayList<Issue>();
 }
}
