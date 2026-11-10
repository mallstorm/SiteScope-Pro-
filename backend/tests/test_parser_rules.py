from app.parser import parse_html
from app.models import PageResult
from app.rules import evaluate, score

def test_parser_extracts_core_fields():
    html = '<html lang="ru"><head><title>Тестовая полезная страница проекта</title><meta name="description" content="Описание"><meta name="viewport" content="width=device-width"><link rel="canonical" href="/page"></head><body><h1>Главный</h1><img src="x"><a href="/next">Next</a><p>' + ('слово ' * 100) + '</p></body></html>'
    d=parse_html(html,"https://example.com/page")
    assert d["title"].startswith("Тестовая")
    assert d["canonical"]=="https://example.com/page"
    assert d["images_missing_alt"]==1
    assert d["internal_links"]==["https://example.com/next"]

def test_rules_are_actionable():
    p=PageResult(requested_url="http://example.com",final_url="http://example.com",status_code=200,elapsed_ms=1,headers={})
    issues=evaluate(p)
    codes={i.code for i in issues}
    assert "HTTPS_MISSING" in codes
    assert "TITLE_MISSING" in codes
    assert all(i.recommendation for i in issues)
    assert score(issues)<100

def test_parser_content_hash_and_noindex():
    html='<html><head><meta name="robots" content="noindex"></head><body><h1>X</h1><p>'+('same text '*50)+'</p></body></html>'
    data=parse_html(html,'https://example.com/x')
    assert len(data['content_hash'])==64
    assert data['indexable'] is False
