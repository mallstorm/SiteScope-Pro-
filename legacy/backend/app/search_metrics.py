from __future__ import annotations

from dataclasses import dataclass, asdict
from datetime import date, timedelta
from typing import Any
from urllib.parse import quote

import httpx


TIMEOUT = 30.0
UA = "SiteScopePro/0.17.0-alpha15 ModernSearchMetrics"


@dataclass
class PageSpeedMetrics:
    strategy: str
    available: bool = False
    performance: int | None = None
    accessibility: int | None = None
    best_practices: int | None = None
    seo: int | None = None
    lcp_ms: float | None = None
    inp_ms: float | None = None
    cls: float | None = None
    fcp_ms: float | None = None
    ttfb_ms: float | None = None
    tbt_ms: float | None = None
    field_assessment: str = "NO_DATA"
    source: str = "Google PageSpeed Insights API / Lighthouse"
    error: str | None = None


@dataclass
class SearchConsoleMetrics:
    property: str
    date_from: str
    date_to: str
    available: bool = False
    clicks: float = 0
    impressions: float = 0
    ctr: float = 0
    position: float = 0
    source: str = "Google Search Console API"
    error: str | None = None


@dataclass
class YandexMetrics:
    available: bool = False
    host_id: str = ""
    host_url: str = ""
    verified: bool = False
    sqi: int | None = None
    sqi_date: str = ""
    searchable_pages: int | None = None
    fatal_problems: int | None = None
    critical_problems: int | None = None
    source: str = "Яндекс Вебмастер API · ИКС"
    error: str | None = None


def _category_score(categories: dict[str, Any], key: str) -> int | None:
    value = categories.get(key)
    score = value.get("score") if isinstance(value, dict) else None
    return round(score * 100) if isinstance(score, (float, int)) else None


def _percentile(metrics: dict[str, Any], key: str) -> float | None:
    item = metrics.get(key)
    value = item.get("percentile") if isinstance(item, dict) else None
    return float(value) if isinstance(value, (float, int)) else None


def _audit_numeric(audits: dict[str, Any], key: str) -> float | None:
    item = audits.get(key)
    value = item.get("numericValue") if isinstance(item, dict) else None
    return float(value) if isinstance(value, (float, int)) else None


def parse_pagespeed(payload: dict[str, Any], strategy: str) -> PageSpeedMetrics:
    out = PageSpeedMetrics(strategy=strategy)
    lighthouse = payload.get("lighthouseResult") or {}
    categories = lighthouse.get("categories") or {}
    audits = lighthouse.get("audits") or {}
    loading = payload.get("loadingExperience") or {}
    field = loading.get("metrics") or {}

    out.performance = _category_score(categories, "performance")
    out.accessibility = _category_score(categories, "accessibility")
    out.best_practices = _category_score(categories, "best-practices")
    out.seo = _category_score(categories, "seo")
    out.field_assessment = loading.get("overall_category") or "NO_DATA"
    out.lcp_ms = _percentile(field, "LARGEST_CONTENTFUL_PAINT_MS")
    out.inp_ms = _percentile(field, "INTERACTION_TO_NEXT_PAINT")
    out.cls = _percentile(field, "CUMULATIVE_LAYOUT_SHIFT_SCORE")
    if out.cls is not None and out.cls > 1:
        out.cls /= 100.0
    out.fcp_ms = _percentile(field, "FIRST_CONTENTFUL_PAINT_MS")
    out.ttfb_ms = _percentile(field, "EXPERIMENTAL_TIME_TO_FIRST_BYTE")
    out.lcp_ms = out.lcp_ms if out.lcp_ms is not None else _audit_numeric(audits, "largest-contentful-paint")
    out.cls = out.cls if out.cls is not None else _audit_numeric(audits, "cumulative-layout-shift")
    out.tbt_ms = _audit_numeric(audits, "total-blocking-time")
    out.available = any(v is not None for v in (out.performance, out.seo, out.lcp_ms))
    return out


def parse_search_console(payload: dict[str, Any], property_url: str, start: str, end: str) -> SearchConsoleMetrics:
    out = SearchConsoleMetrics(property=property_url, date_from=start, date_to=end, available=True)
    rows = payload.get("rows") or []
    if rows:
        row = rows[0]
        out.clicks = float(row.get("clicks", 0))
        out.impressions = float(row.get("impressions", 0))
        out.ctr = float(row.get("ctr", 0))
        out.position = float(row.get("position", 0))
    return out


def parse_yandex(host: dict[str, Any], sqi_payload: dict[str, Any] | None, summary: dict[str, Any] | None) -> YandexMetrics:
    out = YandexMetrics(
        available=True,
        host_id=host.get("host_id", ""),
        host_url=host.get("unicode_host_url") or host.get("ascii_host_url") or "",
        verified=bool(host.get("verified", False)),
    )
    points = (sqi_payload or {}).get("points") or []
    if points:
        point = points[-1]
        out.sqi = point.get("value")
        out.sqi_date = point.get("date", "")
    summary = summary or {}
    for key in ("searchable_pages_count", "searchable_pages", "pages_in_search"):
        if key in summary:
            out.searchable_pages = summary[key]
            break
    problems = summary.get("site_problems") or {}
    out.fatal_problems = problems.get("FATAL", problems.get("fatal"))
    out.critical_problems = problems.get("CRITICAL", problems.get("critical"))
    return out


async def pagespeed(url: str, strategy: str, api_key: str = "") -> dict[str, Any]:
    params: list[tuple[str, str]] = [
        ("url", url), ("strategy", strategy),
        ("category", "performance"), ("category", "accessibility"),
        ("category", "best-practices"), ("category", "seo"),
    ]
    if api_key:
        params.append(("key", api_key))
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT, headers={"User-Agent": UA}) as client:
            response = await client.get("https://www.googleapis.com/pagespeedonline/v5/runPagespeed", params=params)
            response.raise_for_status()
            return asdict(parse_pagespeed(response.json(), strategy))
    except Exception as exc:
        value = PageSpeedMetrics(strategy=strategy, error=str(exc))
        return asdict(value)


async def search_console(token: str, property_url: str) -> dict[str, Any]:
    end = date.today()
    start = end - timedelta(days=27)
    result = SearchConsoleMetrics(property=property_url, date_from=str(start), date_to=str(end))
    endpoint = f"https://searchconsole.googleapis.com/webmasters/v3/sites/{quote(property_url, safe='')}/searchAnalytics/query"
    payload = {"startDate": str(start), "endDate": str(end), "rowLimit": 1, "dataState": "final"}
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT, headers={"User-Agent": UA, "Authorization": f"Bearer {token}"}) as client:
            response = await client.post(endpoint, json=payload)
            response.raise_for_status()
            return asdict(parse_search_console(response.json(), property_url, str(start), str(end)))
    except Exception as exc:
        result.error = str(exc)
        return asdict(result)


async def yandex_webmaster(token: str, target_host: str) -> dict[str, Any]:
    headers = {"User-Agent": UA, "Authorization": f"OAuth {token}"}
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT, headers=headers) as client:
            user_response = await client.get("https://api.webmaster.yandex.net/v4/user")
            user_response.raise_for_status()
            user_id = user_response.json()["user_id"]
            hosts_response = await client.get(f"https://api.webmaster.yandex.net/v4/user/{user_id}/hosts")
            hosts_response.raise_for_status()
            hosts = hosts_response.json().get("hosts", [])
            target = target_host.lower().removeprefix("www.")
            selected = None
            for host in hosts:
                value = (host.get("unicode_host_url") or host.get("ascii_host_url") or "").lower()
                if target in value:
                    selected = host
                    break
            if selected is None and len(hosts) == 1:
                selected = hosts[0]
            if selected is None:
                raise ValueError("Сайт не найден среди подтверждённых ресурсов Яндекс Вебмастера")
            host_id = quote(selected["host_id"], safe="")
            base = f"https://api.webmaster.yandex.net/v4/user/{user_id}/hosts/{host_id}"
            sqi_payload = None
            summary = None
            sqi_response = await client.get(base + "/sqi-history")
            if sqi_response.is_success:
                sqi_payload = sqi_response.json()
            summary_response = await client.get(base + "/summary")
            if summary_response.is_success:
                summary = summary_response.json()
            return asdict(parse_yandex(selected, sqi_payload, summary))
    except Exception as exc:
        return asdict(YandexMetrics(error=str(exc)))
