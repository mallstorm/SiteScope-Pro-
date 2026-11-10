from __future__ import annotations
from urllib.parse import urlparse, urljoin, urldefrag
from collections import deque, Counter, defaultdict
import asyncio
import re
from .fetcher import fetch_url, dns_addresses, tls_summary
from .parser import parse_html
from .rules import evaluate, score
from .models import PageResult, CrawlResult, AuditResult

MAX_SITEMAP_URLS = 1000

async def analyze_page(url: str, depth: int = 0) -> PageResult:
    response,elapsed,chain=await fetch_url(url)
    ctype=response.headers.get("content-type")
    parsed={"title":None,"description":None,"h1":[],"headings":{},"canonical":None,"robots":None,"lang":None,"viewport":None,"word_count":0,"content_hash":"","indexable":True,"images":0,"images_missing_alt":0,"internal_links":[],"external_links":[],"technologies":[]}
    if ctype and "html" in ctype.lower(): parsed.update(parse_html(response.text,str(response.url)))
    try: dns=await asyncio.to_thread(dns_addresses,str(response.url))
    except Exception: dns=[]
    try: tls=await asyncio.to_thread(tls_summary,str(response.url))
    except Exception as e: tls={"error":str(e)}
    page=PageResult(requested_url=url,final_url=str(response.url),status_code=response.status_code,elapsed_ms=elapsed,content_type=ctype,headers=dict(response.headers),dns_addresses=dns,tls=tls,redirect_chain=chain,depth=depth,**parsed)
    page.issues=evaluate(page)
    return page

class RobotsPolicy:
    def __init__(self):
        self.available=False
        self.allow:list[str]=[]
        self.disallow:list[str]=[]
        self.sitemaps:list[str]=[]
    def allowed(self,url:str)->bool:
        path=urlparse(url).path or "/"
        best_allow=max((len(x) for x in self.allow if _robots_match(path,x)),default=-1)
        best_disallow=max((len(x) for x in self.disallow if _robots_match(path,x)),default=-1)
        return best_disallow < 0 or best_allow >= best_disallow

def _robots_match(path:str,rule:str)->bool:
    if not rule:return False
    end=rule.endswith("$")
    if end: rule=rule[:-1]
    pattern="^"+re.escape(rule).replace(r"\*",".*")+("$" if end else ".*")
    return bool(re.match(pattern,path))

async def _robots(origin:str)->RobotsPolicy:
    policy=RobotsPolicy()
    try:
        response,_,_=await fetch_url(origin.rstrip("/")+"/robots.txt")
        if not 200<=response.status_code<400:return policy
        policy.available=True
        active=False
        for raw in response.text.splitlines():
            line=raw.split("#",1)[0].strip()
            if ":" not in line:continue
            key,value=[x.strip() for x in line.split(":",1)]
            key=key.lower()
            if key=="user-agent":active=value=="*" or "sitescope" in value.lower()
            elif key=="allow" and active:policy.allow.append(value)
            elif key=="disallow" and active and value:policy.disallow.append(value)
            elif key=="sitemap" and value:policy.sitemaps.append(value)
    except Exception:
        pass
    return policy

async def _sitemap_urls(url:str,root_host:str,level:int=0,seen:set[str]|None=None)->set[str]:
    if level>2:return set()
    seen=seen or set()
    if url in seen:return set()
    seen.add(url)
    try:
        response,_,_=await fetch_url(url)
        if not 200<=response.status_code<400:return set()
        locs=[x.strip() for x in re.findall(r"(?is)<loc[^>]*>(.*?)</loc>",response.text)]
        if "<sitemapindex" in response.text.lower():
            out:set[str]=set()
            for child in locs[:50]:
                out.update(await _sitemap_urls(child,root_host,level+1,seen))
                if len(out)>=MAX_SITEMAP_URLS:break
            return set(list(out)[:MAX_SITEMAP_URLS])
        return {u for u in locs[:MAX_SITEMAP_URLS] if urlparse(u).netloc.lower()==root_host}
    except Exception:
        return set()

def _dupe_count(groups:dict[str,list[PageResult]])->int:
    return sum(len(v) for v in groups.values() if len(v)>1)

def _dupe_groups(groups:dict[str,list[PageResult]])->int:
    return sum(1 for v in groups.values() if len(v)>1)

async def crawl(url: str,max_pages:int,max_depth:int) -> CrawlResult:
    root_parsed=urlparse(url)
    origin=f"{root_parsed.scheme}://{root_parsed.netloc}"
    policy=await _robots(origin)
    sitemap_candidates=policy.sitemaps or [origin+"/sitemap.xml"]
    sitemap:set[str]=set()
    for candidate in sitemap_candidates[:10]:
        sitemap.update(await _sitemap_urls(candidate,root_parsed.netloc.lower()))
        if len(sitemap)>=MAX_SITEMAP_URLS:break

    pages:list[PageResult]=[]
    seen:set[str]=set()
    queued:set[str]=set()
    linked:set[str]=set()
    q=deque([(url,0)])
    queued.add(url)
    for item in list(sitemap)[:max_pages*4]:
        if item not in queued:q.append((item,1));queued.add(item)
    errors=[];robots_blocked=0
    root_host=root_parsed.netloc.lower()
    while q and len(pages)<max_pages:
        current,depth=q.popleft()
        current=urldefrag(current)[0]
        if current in seen or depth>max_depth or urlparse(current).netloc.lower()!=root_host:continue
        seen.add(current)
        if not policy.allowed(current):robots_blocked+=1;continue
        try:
            p=await analyze_page(current,depth);pages.append(p)
            for link in p.internal_links:
                linked.add(link)
                if link not in seen and link not in queued and depth<max_depth:
                    q.append((link,depth+1));queued.add(link)
        except Exception as e:errors.append({"url":current,"error":str(e)})

    titles:dict[str,list[PageResult]]=defaultdict(list)
    descriptions:dict[str,list[PageResult]]=defaultdict(list)
    content:dict[str,list[PageResult]]=defaultdict(list)
    for p in pages:
        if p.title:titles[p.title.strip().lower()].append(p)
        if p.description:descriptions[p.description.strip().lower()].append(p)
        if p.content_hash and p.word_count>=40:content[p.content_hash].append(p)
    orphans=sum(1 for u in sitemap if u!=url and u not in linked)
    return CrawlResult(
        root_url=pages[0].final_url if pages else url,pages=pages,discovered=len(queued),errors=errors,
        robots_available=policy.available,robots_blocked=robots_blocked,sitemap_urls=len(sitemap),
        duplicate_titles=_dupe_count(titles),duplicate_descriptions=_dupe_count(descriptions),
        duplicate_content_groups=_dupe_groups(content),broken_urls=sum(1 for p in pages if p.status_code>=400),
        noindex_pages=sum(1 for p in pages if not p.indexable),orphan_candidates=orphans)

async def audit(url: str,max_pages:int,max_depth:int) -> AuditResult:
    cr=await crawl(url,max_pages,max_depth)
    issues=[i for p in cr.pages for i in p.issues]
    counts=Counter(i.severity for i in issues)
    overall=score(issues)
    cats={"technical":overall,"content":max(0,100-sum(8 for i in issues if i.code in {"TITLE_MISSING","DESCRIPTION_MISSING","H1_MISSING","THIN_CONTENT"})),"security":max(0,100-sum(10 for i in issues if i.code.endswith("MISSING") and i.code in {"HTTPS_MISSING","HSTS_MISSING","CSP_MISSING","XCTO_MISSING"}))}
    summary={
        "pages_crawled":len(cr.pages),"pages_discovered":cr.discovered,"robots_available":cr.robots_available,
        "robots_blocked":cr.robots_blocked,"sitemap_urls":cr.sitemap_urls,"broken_urls":cr.broken_urls,
        "noindex_pages":cr.noindex_pages,"duplicate_titles":cr.duplicate_titles,
        "duplicate_descriptions":cr.duplicate_descriptions,"duplicate_content_groups":cr.duplicate_content_groups,
        "orphan_candidates":cr.orphan_candidates}
    return AuditResult(target=url,score=overall,category_scores=cats,issue_counts=dict(counts),pages=cr.pages,issues=issues,crawl_summary=summary,limitations=["JavaScript rendering и полевые Core Web Vitals не включены в текущую автономную alpha-версию.","Позиции, обратные ссылки и официальные поисковые данные требуют OAuth/API соответствующих провайдеров."])
