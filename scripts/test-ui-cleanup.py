from pathlib import Path
root=Path(__file__).resolve().parents[1]
app=root/"android-direct/src/pro/sitescope/app"
main=(app/"MainActivity.java").read_text()
assert "homeHeader" in main
assert "headerGradient" in main
assert "Единый центр анализа" in main
assert "Инженерный центр анализа" in main
assert "0.39 α" in main
assert "homeHeroCard" not in main
assert "Архитектура 0.28" not in main
checks={
    "SeoActivity.java":["SEO-раздел работает только"],
    "IpEngineeringActivity.java":["больше не смешиваются","Stable:","Beta:"],
    "ProjectsActivity.java":["Проекты хранятся в SQLite"],
    "HistoryActivity.java":["История хранится в SQLite"],
    "ScannerActivity.java":["Connect-only диагностика разрешённых узлов"],
    "DiscoveryActivity.java":["Пассивные и read-only запросы"],
}
for filename, forbidden in checks.items():
    text=(app/filename).read_text()
    for token in forbidden:
        assert token not in text, f"{filename}: {token}"
print("UI_HEADER_CLEANUP_PASS")
