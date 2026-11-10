from __future__ import annotations
from bs4 import BeautifulSoup
from urllib.parse import urljoin, urlparse, urldefrag
import re
import hashlib

def parse_html(html: str, final_url: str) -> dict:
    soup=BeautifulSoup(html,"html.parser")
    title=soup.title.get_text(" ",strip=True) if soup.title else None
    desc=(soup.find("meta",attrs={"name":re.compile("^description$",re.I)}) or {}).get("content") if soup else None
    canonical_tag=soup.find("link",rel=lambda x:x and "canonical" in [str(i).lower() for i in (x if isinstance(x,list) else [x])])
    canonical=urljoin(final_url,canonical_tag.get("href")) if canonical_tag and canonical_tag.get("href") else None
    robots_tag=soup.find("meta",attrs={"name":re.compile("^robots$",re.I)})
    robots=robots_tag.get("content") if robots_tag else None
    html_tag=soup.find("html")
    lang=html_tag.get("lang") if html_tag else None
    viewport_tag=soup.find("meta",attrs={"name":re.compile("^viewport$",re.I)})
    viewport=viewport_tag.get("content") if viewport_tag else None
    headings={f"h{i}":len(soup.find_all(f"h{i}")) for i in range(1,7)}
    h1=[x.get_text(" ",strip=True) for x in soup.find_all("h1")]
    images=soup.find_all("img")
    missing_alt=sum(1 for i in images if not i.has_attr("alt"))
    base=urlparse(final_url)
    internal=[]; external=[]
    for a in soup.find_all("a",href=True):
        u=urldefrag(urljoin(final_url,a["href"]))[0]
        p=urlparse(u)
        if p.scheme not in {"http","https"}: continue
        (internal if p.netloc.lower()==base.netloc.lower() else external).append(u)
    for tag in soup(["script","style","noscript"]): tag.decompose()
    text=soup.get_text(" ",strip=True)
    words=re.findall(r"[\wÀ-žА-Яа-яЁё]+",text)
    normalized=re.sub(r"\d+","#",re.sub(r"\s+"," ",text.lower()).strip())
    content_hash=hashlib.sha256(normalized.encode("utf-8")).hexdigest() if normalized else ""
    technologies=[]
    low=html.lower()
    signatures={"WordPress":["wp-content","wp-includes"],"Drupal":["drupal-settings-json"],"Joomla":["com_content"],"React":["data-reactroot","__next_data__"],"Vue.js":["data-v-","__nuxt__"],"Google Analytics":["googletagmanager.com/gtag","google-analytics.com"]}
    for name,sigs in signatures.items():
        if any(s in low for s in sigs): technologies.append(name)
    indexable=not (robots and "noindex" in robots.lower())
    return {"title":title,"description":desc,"h1":h1,"headings":headings,"canonical":canonical,"robots":robots,"lang":lang,"viewport":viewport,"word_count":len(words),"content_hash":content_hash,"indexable":indexable,"images":len(images),"images_missing_alt":missing_alt,"internal_links":sorted(set(internal)),"external_links":sorted(set(external)),"technologies":technologies}
