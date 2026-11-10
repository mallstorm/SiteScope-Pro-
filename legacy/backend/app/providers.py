from __future__ import annotations
from dataclasses import dataclass, asdict
import os

@dataclass
class ProviderStatus:
    id:str; name:str; category:str; configured:bool; status:str; source:str

class ProviderRegistry:
    def statuses(self):
        items=[
            ProviderStatus("pagespeed","Google PageSpeed Insights · Core Web Vitals","performance",bool(os.getenv("PAGESPEED_API_KEY")),"ready" if os.getenv("PAGESPEED_API_KEY") else "not_configured","official_api"),
            ProviderStatus("gsc","Google Search Console","search_console",bool(os.getenv("GOOGLE_OAUTH_CLIENT_ID")),"oauth_required" if os.getenv("GOOGLE_OAUTH_CLIENT_ID") else "not_configured","official_api"),
            ProviderStatus("yandex_webmaster","Яндекс Вебмастер · ИКС","search_console",bool(os.getenv("YANDEX_OAUTH_CLIENT_ID")),"oauth_required" if os.getenv("YANDEX_OAUTH_CLIENT_ID") else "not_configured","official_api"),
            ProviderStatus("backlinks","Внешний ссылочный провайдер","backlinks",bool(os.getenv("BACKLINKS_API_KEY")),"ready" if os.getenv("BACKLINKS_API_KEY") else "not_configured","licensed_api")]
        return [asdict(x) for x in items]
