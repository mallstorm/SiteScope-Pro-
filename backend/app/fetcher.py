from __future__ import annotations
import time, socket, ssl
from urllib.parse import urlparse
import httpx
from .security import validate_public_url

MAX_BYTES = 2_000_000

async def fetch_url(url: str) -> tuple[httpx.Response, int, list[str]]:
    validate_public_url(url)
    chain=[]
    current=url
    started=time.perf_counter()
    async with httpx.AsyncClient(timeout=httpx.Timeout(12.0), headers={"User-Agent":"SiteScopeProBot/0.1 (+authorized-audit)"}, follow_redirects=False) as client:
        for _ in range(6):
            validate_public_url(current)
            response=await client.get(current)
            chain.append(str(response.url))
            if int(response.headers.get("content-length","0") or 0) > MAX_BYTES:
                raise ValueError("Ответ превышает лимит 2 МБ")
            if len(response.content) > MAX_BYTES:
                raise ValueError("Ответ превышает лимит 2 МБ")
            if response.status_code in {301,302,303,307,308} and response.headers.get("location"):
                current=str(response.url.join(response.headers["location"]))
                continue
            elapsed=int((time.perf_counter()-started)*1000)
            return response, elapsed, chain
    raise ValueError("Слишком длинная цепочка перенаправлений")

def dns_addresses(url: str) -> list[str]:
    host=urlparse(url).hostname
    if not host: return []
    return sorted({x[4][0] for x in socket.getaddrinfo(host, None)})

def tls_summary(url: str) -> dict | None:
    p=urlparse(url)
    if p.scheme != "https" or not p.hostname: return None
    ctx=ssl.create_default_context()
    with socket.create_connection((p.hostname, p.port or 443), timeout=6) as raw:
        with ctx.wrap_socket(raw, server_hostname=p.hostname) as s:
            cert=s.getpeercert()
            return {"version":s.version(),"cipher":s.cipher()[0] if s.cipher() else None,"subject":dict(x[0] for x in cert.get("subject",[])),"issuer":dict(x[0] for x in cert.get("issuer",[])),"notAfter":cert.get("notAfter")}
