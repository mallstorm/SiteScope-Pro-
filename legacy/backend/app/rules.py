from __future__ import annotations
from .models import Issue, PageResult

def issue(code,severity,title,why,recommendation,url,evidence=None):
    return Issue(code=code,severity=severity,title=title,why=why,recommendation=recommendation,affected_url=url,evidence=evidence)

def evaluate(page: PageResult) -> list[Issue]:
    u=page.final_url; out=[]
    if page.status_code >= 500: out.append(issue("HTTP_5XX","critical","Серверная ошибка", "Страница недоступна поисковому роботу и пользователям.","Исправьте приложение/прокси и добейтесь стабильного ответа 200.",u,str(page.status_code)))
    elif page.status_code >= 400: out.append(issue("HTTP_4XX","high","Ошибка клиента", "URL не отдаёт индексируемый документ.","Исправьте ссылку, восстановите страницу или настройте релевантный 301.",u,str(page.status_code)))
    if not u.startswith("https://"): out.append(issue("HTTPS_MISSING","high","Не используется HTTPS","Незащищённое соединение снижает доверие и безопасность.","Установите сертификат и настройте единый 301 на HTTPS.",u))
    if len(page.redirect_chain)>2: out.append(issue("REDIRECT_CHAIN","medium","Длинная цепочка перенаправлений","Цепочки замедляют обход и загрузку.","Ссылайтесь сразу на конечный URL и сократите цепочку до одного перехода.",u," → ".join(page.redirect_chain)))
    if not page.title: out.append(issue("TITLE_MISSING","high","Отсутствует title","Title — основной сигнал темы страницы и заголовок сниппета.","Добавьте уникальный и описательный title.",u))
    elif len(page.title)<15 or len(page.title)>65: out.append(issue("TITLE_LENGTH","medium","Неоптимальная длина title","Слишком короткий title слаб, слишком длинный может обрезаться.","Сформируйте содержательный title примерно 30–60 символов.",u,page.title))
    if not page.description: out.append(issue("DESCRIPTION_MISSING","medium","Отсутствует meta description","Поисковик может сформировать менее управляемый сниппет.","Добавьте уникальное описание пользы страницы.",u))
    if len(page.h1)==0: out.append(issue("H1_MISSING","high","Отсутствует H1","Основной заголовок помогает понять назначение страницы.","Добавьте один видимый H1, соответствующий содержанию.",u))
    elif len(page.h1)>1: out.append(issue("H1_MULTIPLE","medium","Несколько H1","Несколько главных заголовков усложняют структуру документа.","Оставьте один главный H1, остальные переведите в H2/H3.",u,str(page.h1)))
    if not page.canonical: out.append(issue("CANONICAL_MISSING","medium","Canonical не указан","Параметрические и дублирующиеся URL могут конкурировать.","Добавьте self-referencing canonical на предпочтительный URL.",u))
    if page.images_missing_alt: out.append(issue("IMAGE_ALT_MISSING","medium","Изображения без alt","Alt помогает доступности и пониманию изображения.","Добавьте содержательный alt; декоративным изображениям задайте пустой alt.",u,str(page.images_missing_alt)))
    if not page.lang: out.append(issue("HTML_LANG_MISSING","low","Не указан язык документа","Язык помогает доступности и обработке контента.","Укажите корректный атрибут lang у элемента html.",u))
    if not page.viewport: out.append(issue("VIEWPORT_MISSING","high","Не указан viewport","Страница может отображаться некорректно на мобильных устройствах.","Добавьте meta viewport width=device-width, initial-scale=1.",u))
    if page.word_count < 80: out.append(issue("THIN_CONTENT","medium","Мало основного текста","Страница может недостаточно раскрывать пользовательскую задачу.","Проверьте назначение страницы и добавьте полезный уникальный контент без воды.",u,str(page.word_count)))
    required={"strict-transport-security":"HSTS_MISSING","content-security-policy":"CSP_MISSING","x-content-type-options":"XCTO_MISSING"}
    lower={k.lower():v for k,v in page.headers.items()}
    for h,code in required.items():
        if h not in lower: out.append(issue(code,"low",f"Отсутствует заголовок {h}","Защитные заголовки уменьшают риск атак браузерного уровня.",f"Настройте {h} после тестирования совместимости.",u))
    return out

def score(issues:list[Issue]) -> int:
    weights={"critical":25,"high":12,"medium":5,"low":2,"info":0}
    return max(0,100-sum(weights[i.severity] for i in issues))
