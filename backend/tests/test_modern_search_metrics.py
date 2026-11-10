from app.search_metrics import parse_pagespeed, parse_search_console, parse_yandex


def test_pagespeed_parser_current_cwv():
    payload = {
        "loadingExperience": {
            "overall_category": "AVERAGE",
            "metrics": {
                "LARGEST_CONTENTFUL_PAINT_MS": {"percentile": 2400},
                "INTERACTION_TO_NEXT_PAINT": {"percentile": 180},
                "CUMULATIVE_LAYOUT_SHIFT_SCORE": {"percentile": 12},
                "EXPERIMENTAL_TIME_TO_FIRST_BYTE": {"percentile": 650},
            },
        },
        "lighthouseResult": {
            "categories": {
                "performance": {"score": 0.91},
                "accessibility": {"score": 0.88},
                "best-practices": {"score": 0.95},
                "seo": {"score": 1.0},
            },
            "audits": {"total-blocking-time": {"numericValue": 120}},
        },
    }
    result = parse_pagespeed(payload, "mobile")
    assert result.available
    assert result.performance == 91
    assert result.seo == 100
    assert result.lcp_ms == 2400
    assert result.inp_ms == 180
    assert result.cls == 0.12
    assert result.tbt_ms == 120


def test_search_console_uses_real_performance_metrics():
    payload = {"rows": [{"clicks": 125, "impressions": 5000, "ctr": 0.025, "position": 8.4}]}
    result = parse_search_console(payload, "sc-domain:example.com", "2026-07-01", "2026-07-28")
    assert result.available
    assert result.clicks == 125
    assert result.impressions == 5000
    assert result.ctr == 0.025
    assert result.position == 8.4


def test_yandex_parser_uses_sqi_not_tic():
    host = {
        "host_id": "https:example.com:443",
        "unicode_host_url": "https://example.com/",
        "verified": True,
    }
    sqi = {"points": [{"date": "2026-07-01T00:00:00+0300", "value": 340}]}
    summary = {
        "searchable_pages_count": 1250,
        "site_problems": {"FATAL": 0, "CRITICAL": 2},
    }
    result = parse_yandex(host, sqi, summary)
    assert result.available
    assert result.sqi == 340
    assert result.searchable_pages == 1250
    assert result.critical_problems == 2
