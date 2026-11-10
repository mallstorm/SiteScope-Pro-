from fastapi import FastAPI, HTTPException
from .models import TargetRequest, CrawlRequest
from .service import analyze_page, crawl, audit
from .providers import ProviderRegistry
from .security import UnsafeTarget

app=FastAPI(title="SiteScope Pro API",version="0.17.0-alpha15",description="SEO audit baseline with guarded public-target fetching")

@app.get("/health")
def health(): return {"status":"ok","version":"0.17.0-alpha15"}

@app.post("/api/v1/analyze/quick")
async def quick(req:TargetRequest):
    try: return await analyze_page(str(req.url))
    except UnsafeTarget as e: raise HTTPException(400,str(e))
    except Exception as e: raise HTTPException(502,f"Анализ не выполнен: {e}")

@app.post("/api/v1/crawl")
async def crawl_endpoint(req:CrawlRequest):
    try: return await crawl(str(req.url),req.max_pages,req.max_depth)
    except UnsafeTarget as e: raise HTTPException(400,str(e))
    except Exception as e: raise HTTPException(502,f"Обход не выполнен: {e}")

@app.post("/api/v1/audit")
async def audit_endpoint(req:CrawlRequest):
    try: return await audit(str(req.url),req.max_pages,req.max_depth)
    except UnsafeTarget as e: raise HTTPException(400,str(e))
    except Exception as e: raise HTTPException(502,f"Аудит не выполнен: {e}")

@app.get("/api/v1/providers")
def providers(): return ProviderRegistry().statuses()


# Stage 7-12 endpoints
from .network_tools import dns_lookup, scan as network_scan
from .scanner_policy import expand_targets, parse_ports_expression, validate_scan_budget
from .reports import audit_to_markdown, audit_to_html, scan_to_csv
from .ai_assistant import EvidenceBoundAssistant
from pydantic import BaseModel, Field
from fastapi.responses import PlainTextResponse, HTMLResponse
class DnsRequest(BaseModel): host:str
class ScanRequest(BaseModel):
    target: str
    ports: list[int] = Field(default_factory=list)
    ports_expression: str = ''
    authorized: bool = False
    extended_private: bool = False
    concurrency: int = 16
    timeout_ms: int = 800
class ReportRequest(BaseModel): audit:dict
@app.post('/api/v1/network/dns')
def network_dns(req:DnsRequest): return {'host':req.host,'records':dns_lookup(req.host)}
@app.post('/api/v1/network/scan')
async def network_scan_api(req:ScanRequest):
    targets = expand_targets(req.target, req.authorized, req.extended_private)
    ports = sorted(set(req.ports)) if req.ports else parse_ports_expression(req.ports_expression, req.extended_private)
    total = validate_scan_budget(targets, ports, req.extended_private)
    results = []
    for host in targets:
        results.extend(await network_scan(host, ports, True, req.concurrency, timeout_ms=req.timeout_ms))
    return {'authorized': True, 'extended_private': req.extended_private, 'targets': targets, 'ports': ports, 'total_probes': total, 'results': results}
@app.post('/api/v1/reports/markdown',response_class=PlainTextResponse)
def report_markdown(req:ReportRequest): return audit_to_markdown(req.audit)
@app.post('/api/v1/reports/html',response_class=HTMLResponse)
def report_html(req:ReportRequest): return audit_to_html(req.audit)
@app.post('/api/v1/assistant/plan')
def assistant_plan(req:ReportRequest): return EvidenceBoundAssistant().build_plan(req.audit)

# Modern search metrics: no fabricated Google Rank and no obsolete tIC.
from urllib.parse import urlparse
from .models import ModernMetricsRequest
from .search_metrics import pagespeed as get_pagespeed, search_console as get_search_console, yandex_webmaster as get_yandex_webmaster

@app.post('/api/v1/search-metrics')
async def modern_search_metrics(req: ModernMetricsRequest):
    url = str(req.url)
    output = {
        'target': url,
        'metric_model': {
            'google_rank': 'not_available',
            'google': ['Search Console clicks/impressions/CTR/average position', 'Core Web Vitals LCP/INP/CLS', 'Lighthouse categories'],
            'yandex_tic': 'obsolete',
            'yandex': ['ИКС/SQI', 'indexing', 'diagnostics', 'search queries'],
        },
        'pagespeed': {
            'mobile': await get_pagespeed(url, 'mobile', req.pagespeed_api_key),
            'desktop': await get_pagespeed(url, 'desktop', req.pagespeed_api_key),
        },
    }
    if req.google_access_token:
        output['google_search_console'] = await get_search_console(
            req.google_access_token,
            req.google_property or url,
        )
    if req.yandex_access_token:
        output['yandex_webmaster'] = await get_yandex_webmaster(
            req.yandex_access_token,
            urlparse(url).hostname or '',
        )
    return output
