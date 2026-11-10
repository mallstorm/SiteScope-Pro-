package pro.sitescope.app;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
public final class CrawlerHarness {
 public static void main(String[] args) throws Exception {
  final int port=18765;
  final Map<String,String> pages=new HashMap<String,String>();
  pages.put("/","<html><head><title>Главная страница тестового сайта</title><meta name=\"description\" content=\"Уникальное описание главной страницы для тестирования автономного SEO crawler приложения SiteScope Pro.\"><link rel=\"canonical\" href=\"/\"></head><body><h1>Главная</h1><p>"+repeat("полезный контент ",50)+"</p><a href=\"/a\">A</a><a href=\"/b\">B</a><a href=\"/broken\">Broken</a><img src=\"x.jpg\"></body></html>");
  pages.put("/a","<html><head><title>Одинаковый заголовок страницы</title><meta name=\"description\" content=\"Одинаковое описание двух страниц для обнаружения дублей в отчете crawler.\"><link rel=\"canonical\" href=\"/a\"></head><body><h1>Same</h1><p>"+repeat("одинаковый текст дубля ",30)+"</p><a href=\"/noindex\">N</a></body></html>");
  pages.put("/b","<html><head><title>Одинаковый заголовок страницы</title><meta name=\"description\" content=\"Одинаковое описание двух страниц для обнаружения дублей в отчете crawler.\"><link rel=\"canonical\" href=\"/b\"></head><body><h1>Same</h1><p>"+repeat("одинаковый текст дубля ",30)+"</p><a href=\"/noindex\">N</a></body></html>");
  pages.put("/noindex","<html><head><title>Noindex test page title</title><meta name=\"robots\" content=\"noindex,nofollow\"></head><body><h1>Noindex</h1><p>small</p></body></html>");
  pages.put("/orphan","<html><head><title>Страница сирота из карты сайта</title><link rel=\"canonical\" href=\"/orphan\"></head><body><h1>Orphan</h1><p>"+repeat("orphan text ",20)+"</p></body></html>");
  pages.put("/private","<html><head><title>Blocked</title></head><body><h1>Blocked</h1></body></html>");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
  server.createContext("/",new HttpHandler(){public void handle(HttpExchange x){try{
    String path=x.getRequestURI().getPath(); String body; int code=200; String type="text/html; charset=utf-8";
    if("/robots.txt".equals(path)){type="text/plain";body="User-agent: *\nDisallow: /private\nSitemap: http://127.0.0.1:"+port+"/sitemap.xml\n";}
    else if("/sitemap.xml".equals(path)){type="application/xml"; body="<?xml version=\"1.0\"?><urlset>"; for(String p:new String[]{"/","/a","/b","/noindex","/orphan","/private"}) body+="<url><loc>http://127.0.0.1:"+port+p+"</loc></url>"; body+="</urlset>";}
    else if("/broken".equals(path)){code=404;body="not found";}
    else {body=pages.get(path); if(body==null){code=404;body="not found";}}
    byte[] data=body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().add("Content-Type",type); x.getResponseHeaders().add("Server","MockSEO"); x.sendResponseHeaders(code,data.length); x.getResponseBody().write(data); x.close();
  }catch(Exception e){e.printStackTrace();}}});
  server.start();
  try {
   CrawlerEngine.Config c=new CrawlerEngine.Config(); c.maxPages=20; c.maxDepth=4; c.connectTimeoutMs=2000; c.readTimeoutMs=3000;
   Models.AuditResult r=CrawlerEngine.crawl("http://127.0.0.1:"+port+"/",c,null);
   require(r.pagesCrawled>=5,"pagesCrawled="+r.pagesCrawled);
   require(r.sitemapUrlCount>=6,"sitemap="+r.sitemapUrlCount);
   require(r.robotsBlockedCount>=1,"robotsBlocked="+r.robotsBlockedCount);
   require(r.brokenInternalLinks>=1,"broken="+r.brokenInternalLinks);
   require(r.noindexPages>=1,"noindex="+r.noindexPages);
   require(r.duplicateTitles>=2,"duplicateTitles="+r.duplicateTitles);
   require(r.duplicateDescriptions>=2,"duplicateDescriptions="+r.duplicateDescriptions);
   require(r.duplicateContentGroups>=1,"duplicateContentGroups="+r.duplicateContentGroups);
   require(r.orphanCandidates>=1,"orphans="+r.orphanCandidates);
   boolean affected=false; for(Models.Issue i:r.issues) if(i.affectedUrl!=null && i.affectedUrl.length()>0) affected=true;
   require(affected,"affectedUrl missing");
   System.out.println("PASS pages="+r.pagesCrawled+" discovered="+r.pagesDiscovered+" blocked="+r.robotsBlockedCount+" issues="+r.issues.size()+" score="+r.score);
  } finally {server.stop(0);} 
 }
 private static String repeat(String s,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(s);return b.toString();}
 private static void require(boolean v,String m){if(!v) throw new IllegalStateException(m);}
}
