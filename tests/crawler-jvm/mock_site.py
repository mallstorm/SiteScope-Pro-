from http.server import BaseHTTPRequestHandler,HTTPServer
PORT=18765
PAGES={
'/':'<html><head><title>Главная страница тестового сайта</title><meta name="description" content="Уникальное описание главной страницы для тестирования автономного SEO crawler приложения SiteScope Pro."><link rel="canonical" href="/"></head><body><h1>Главная</h1><p>'+('полезный контент '*50)+'</p><a href="/a">A</a><a href="/b">B</a><a href="/broken">Broken</a><img src="x.jpg"></body></html>',
'/a':'<html><head><title>Одинаковый заголовок страницы</title><meta name="description" content="Одинаковое описание двух страниц для обнаружения дублей в отчете crawler."><link rel="canonical" href="/a"></head><body><h1>A</h1><p>'+('одинаковый текст дубля '*30)+'</p><a href="/noindex">N</a></body></html>',
'/b':'<html><head><title>Одинаковый заголовок страницы</title><meta name="description" content="Одинаковое описание двух страниц для обнаружения дублей в отчете crawler."><link rel="canonical" href="/b"></head><body><h1>B</h1><p>'+('одинаковый текст дубля '*30)+'</p></body></html>',
'/noindex':'<html><head><title>Noindex test page title</title><meta name="robots" content="noindex,nofollow"></head><body><h1>Noindex</h1><p>small</p></body></html>',
'/orphan':'<html><head><title>Страница сирота из карты сайта</title><link rel="canonical" href="/orphan"></head><body><h1>Orphan</h1><p>'+('orphan text '*20)+'</p></body></html>',
'/private':'<html><head><title>Blocked</title></head><body><h1>Blocked</h1></body></html>'}
class H(BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path=='/robots.txt':
   self.send_response(200); self.send_header('Content-Type','text/plain'); self.end_headers(); self.wfile.write(b'User-agent: *\nDisallow: /private\nSitemap: http://127.0.0.1:18765/sitemap.xml\n'); return
  if self.path=='/sitemap.xml':
   body='''<?xml version="1.0"?><urlset>'''+''.join(f'<url><loc>http://127.0.0.1:{PORT}{p}</loc></url>' for p in ['/', '/a','/b','/noindex','/orphan','/private'])+'</urlset>'
   self.send_response(200); self.send_header('Content-Type','application/xml'); self.end_headers(); self.wfile.write(body.encode()); return
  if self.path=='/broken': self.send_response(404); self.send_header('Content-Type','text/html'); self.end_headers(); self.wfile.write(b'not found'); return
  body=PAGES.get(self.path)
  if body is None: self.send_response(404); self.end_headers(); return
  self.send_response(200); self.send_header('Content-Type','text/html; charset=utf-8'); self.send_header('Server','MockSEO'); self.end_headers(); self.wfile.write(body.encode())
 def log_message(self,*a): pass
HTTPServer(('127.0.0.1',PORT),H).serve_forever()
