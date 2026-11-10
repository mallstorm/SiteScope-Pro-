from __future__ import annotations
import csv,io,json,html

def audit_to_markdown(audit:dict)->str:
    out=[f"# SiteScope Pro — отчёт для {audit.get('target','')}",f"Оценка: **{audit.get('score',0)}/100**",'','## Проблемы']
    for i in audit.get('issues',[]): out += [f"### [{i.get('severity','').upper()}] {i.get('title','')}",i.get('why',''),f"**Исправление:** {i.get('recommendation','')}",'']
    if not audit.get('issues'): out.append('Проблемы не обнаружены в пределах выполненных проверок.')
    return '\n'.join(out)
def audit_to_html(audit:dict)->str:
    md=audit_to_markdown(audit)
    return '<!doctype html><meta charset="utf-8"><title>SiteScope Report</title><pre>'+html.escape(md)+'</pre>'
def scan_to_csv(rows:list[dict])->str:
    buf=io.StringIO(); fields=['host','port','open','service','latency_ms','banner','confidence']; w=csv.DictWriter(buf,fieldnames=fields,extrasaction='ignore');w.writeheader();w.writerows(rows);return buf.getvalue()
