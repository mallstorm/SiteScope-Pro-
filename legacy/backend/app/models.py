from __future__ import annotations
from pydantic import BaseModel, Field, HttpUrl
from typing import Any, Literal

Severity = Literal["critical", "high", "medium", "low", "info"]

class TargetRequest(BaseModel):
    url: HttpUrl

class CrawlRequest(TargetRequest):
    max_pages: int = Field(default=25, ge=1, le=100)
    max_depth: int = Field(default=2, ge=0, le=5)

class Issue(BaseModel):
    code: str
    severity: Severity
    title: str
    why: str
    evidence: str | None = None
    recommendation: str
    affected_url: str

class PageResult(BaseModel):
    requested_url: str
    final_url: str
    status_code: int
    elapsed_ms: int
    content_type: str | None = None
    headers: dict[str, str] = {}
    title: str | None = None
    description: str | None = None
    h1: list[str] = []
    headings: dict[str, int] = {}
    canonical: str | None = None
    robots: str | None = None
    lang: str | None = None
    viewport: str | None = None
    word_count: int = 0
    content_hash: str = ""
    indexable: bool = True
    depth: int = 0
    images: int = 0
    images_missing_alt: int = 0
    internal_links: list[str] = []
    external_links: list[str] = []
    technologies: list[str] = []
    dns_addresses: list[str] = []
    tls: dict[str, Any] | None = None
    redirect_chain: list[str] = []
    issues: list[Issue] = []

class CrawlResult(BaseModel):
    root_url: str
    pages: list[PageResult]
    discovered: int
    errors: list[dict[str, str]] = []
    robots_available: bool = False
    robots_blocked: int = 0
    sitemap_urls: int = 0
    duplicate_titles: int = 0
    duplicate_descriptions: int = 0
    duplicate_content_groups: int = 0
    broken_urls: int = 0
    noindex_pages: int = 0
    orphan_candidates: int = 0

class AuditResult(BaseModel):
    target: str
    score: int
    category_scores: dict[str, int]
    issue_counts: dict[str, int]
    pages: list[PageResult]
    issues: list[Issue]
    limitations: list[str]
    crawl_summary: dict[str, int | bool] = {}

class ModernMetricsRequest(BaseModel):
    url: HttpUrl
    pagespeed_api_key: str = ""
    google_access_token: str = ""
    google_property: str = ""
    yandex_access_token: str = ""
