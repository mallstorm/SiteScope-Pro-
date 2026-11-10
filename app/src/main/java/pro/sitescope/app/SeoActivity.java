package pro.sitescope.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

public class SeoActivity extends BaseSiteScopeActivity {
    private DataStore store;
    private String pendingTarget = "";
    private Models.AuditResult visibleResult;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DataStore(this);
        pendingTarget = getIntent().getStringExtra("target") == null ? "" : getIntent().getStringExtra("target");
        setRootScreen(seoScreen(), "HOME");
        long timestamp = getIntent().getLongExtra("audit_timestamp", -1L);
        if (timestamp > 0) {
            for (Models.AuditResult audit : store.audits()) {
                if (audit.timestamp == timestamp) { showAuditResult(audit); break; }
            }
        }
    }

    private void showSeoHome() {
        visibleResult = null;
        replaceContent(seoScreen());
    }

    private View seoScreen() {
        LinearLayout page = page();
        page.addView(appHeader("SEO-аудит", "Сайт, индексация, скорость и поисковые сигналы", true));

        LinearLayout form = card(18);
        form.addView(label("Адрес ресурса", 13, NAVY, true));
        final EditText target = input("https://example.com");
        if (pendingTarget.length() > 0) {
            target.setText(pendingTarget);
            pendingTarget = "";
        }
        form.addView(target, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, 8, 0, 12));
        form.addView(infoStrip("Быстрая проверка анализирует одну страницу. Полный обход следует внутренним ссылкам, учитывает robots.txt и sitemap, ищет битые URL, noindex, canonical-проблемы, дубли Title/Description и одинаковый контент."));

        LinearLayout crawlSettings = row();
        LinearLayout pagesBox = new LinearLayout(this);
        pagesBox.setOrientation(LinearLayout.VERTICAL);
        pagesBox.addView(label("Лимит страниц", 11, MUTED, true));
        final EditText maxPages = input("25");
        maxPages.setText("25");
        maxPages.setInputType(InputType.TYPE_CLASS_NUMBER);
        pagesBox.addView(maxPages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        LinearLayout depthBox = new LinearLayout(this);
        depthBox.setOrientation(LinearLayout.VERTICAL);
        depthBox.addView(label("Глубина", 11, MUTED, true));
        final EditText maxDepth = input("3");
        maxDepth.setText("3");
        maxDepth.setInputType(InputType.TYPE_CLASS_NUMBER);
        depthBox.addView(maxDepth, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        crawlSettings.addView(pagesBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        crawlSettings.addView(space(10));
        crawlSettings.addView(depthBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(crawlSettings, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

        final CheckBox respectRobots = new CheckBox(this);
        respectRobots.setText("Соблюдать robots.txt");
        respectRobots.setTextColor(NAVY);
        respectRobots.setChecked(true);
        form.addView(respectRobots, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        final CheckBox includeSitemap = new CheckBox(this);
        includeSitemap.setText("Использовать sitemap как источник URL");
        includeSitemap.setTextColor(NAVY);
        includeSitemap.setChecked(true);
        form.addView(includeSitemap);

        LinearLayout actions = row();
        TextView quick = outlineButton("Быстрый аудит");
        TextView full = primaryButton("Полный обход");
        actions.addView(quick, new LinearLayout.LayoutParams(0, dp(50), 1f));
        actions.addView(space(8));
        actions.addView(full, new LinearLayout.LayoutParams(0, dp(50), 1f));
        form.addView(actions, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        TextView modernMetrics = outlineButton("Search Console · Core Web Vitals · Яндекс ИКС");
        modernMetrics.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(SeoActivity.this, SearchMetricsActivity.class);
                startActivity(intent);
            }
        });
        form.addView(modernMetrics, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0, 10, 0, 0));
        form.addView(infoStrip("Google Rank и тИЦ не используются. Приложение получает официальные данные Search Console, PageSpeed/Core Web Vitals и текущий ИКС Яндекса для подтверждённых сайтов."), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        page.addView(form);

        quick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String value = target.getText().toString().trim();
                if (value.length() == 0) { target.setError("Введите адрес сайта"); return; }
                startAudit(value);
            }
        });
        full.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String value = target.getText().toString().trim();
                if (value.length() == 0) { target.setError("Введите адрес сайта"); return; }
                CrawlerEngine.Config config = new CrawlerEngine.Config();
                config.maxPages = parsePositiveInt(maxPages.getText().toString(), 25);
                config.maxDepth = parsePositiveInt(maxDepth.getText().toString(), 3);
                config.respectRobots = respectRobots.isChecked();
                config.includeSitemap = includeSitemap.isChecked();
                config.normalize();
                startFullCrawl(value, config);
            }
        });

        page.addView(sectionTitle("Что проверяет полный обход", "Автономный этап 3–4 без внешних платных API"));
        String[][] checks = {
            {"Обход и индексация", "robots.txt, sitemap, noindex, глубина и лимиты"},
            {"Статусы и редиректы", "4xx/5xx, перенаправленные внутренние URL и время ответа"},
            {"Метаданные", "Title, description, H1, canonical и их дубли"},
            {"Контент", "Тонкие страницы и группы одинакового нормализованного текста"},
            {"Внутренние ссылки", "Обнаруженные URL, битые страницы и кандидаты в страницы-сироты"},
            {"Изображения", "Количество изображений и отсутствие alt"},
            {"Технологии сайта", "CMS, frontend/backend, CDN, аналитика и веб-сервер с оценкой уверенности"},
            {"DNS и домен", "A/AAAA/CNAME/MX/NS/TXT/SOA/CAA/SRV, SPF, DMARC, DNSSEC, RDAP и ASN"}
        };
        for (String[] check : checks) page.addView(checkRow(check[0], check[1]));

        return scroll(page);
    }

    private void startAudit(final String target) {
        screenContent.removeAllViews();
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(34), dp(34), dp(34), dp(34));
        ProgressBar progress = new ProgressBar(this);
        loading.addView(progress, new LinearLayout.LayoutParams(dp(58), dp(58)));
        loading.addView(label("Анализируем ресурс", 22, NAVY, true), marginParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 22, 0, 0));
        loading.addView(label(AuditEngine.normalizeTarget(target), 14, MUTED, false));
        loading.addView(label("HTTP → DNS → TLS → CMS/технологии → SEO-правила", 13, PRIMARY, true), marginParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));
        screenContent.addView(loading);
        new Thread(new Runnable() {
            @Override public void run() {
                final Models.AuditResult result = AuditEngine.run(target);
                store.saveAudit(result);
                runOnUiThread(new Runnable() {
                    @Override public void run() { showAuditResult(result); }
                });
            }
        }).start();
    }

    private void startFullCrawl(final String target, final CrawlerEngine.Config config) {
        screenContent.removeAllViews();
        final LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(28), dp(34), dp(28), dp(34));
        final ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(config.maxPages);
        progress.setProgress(0);
        loading.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
        loading.addView(label("Многостраничный SEO-обход", 22, NAVY, true), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 22, 0, 0));
        loading.addView(label(AuditEngine.normalizeTarget(target), 14, MUTED, false));
        final TextView status = label("Подготавливаем robots.txt и sitemap…", 13, PRIMARY, true);
        loading.addView(status, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));
        loading.addView(infoStrip("Лимит: " + config.maxPages + " страниц · глубина: " + config.maxDepth + ". Обход выполняет только read-only HTTP-запросы и не запускает JavaScript."), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        screenContent.addView(loading);
        new Thread(new Runnable() {
            @Override public void run() {
                final Models.AuditResult result = CrawlerEngine.crawl(target, config, new CrawlerEngine.ProgressListener() {
                    @Override public void onProgress(final int crawled, final int maxPages, final int discovered, final String currentUrl) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                progress.setMax(Math.max(1, maxPages));
                                progress.setProgress(Math.min(maxPages, crawled));
                                status.setText("Проверено " + crawled + " из " + maxPages + " · обнаружено " + discovered + "\n" + currentUrl);
                            }
                        });
                    }
                    @Override public boolean isCancelled() { return false; }
                });
                store.saveAudit(result);
                runOnUiThread(new Runnable() {
                    @Override public void run() { showAuditResult(result); }
                });
            }
        }).start();
    }

    private void showAuditResult(final Models.AuditResult result) {
        visibleResult = result;
        screenContent.removeAllViews();
        LinearLayout page = page();
        LinearLayout top = row();
        TextView back = outlineButton("‹ Назад");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showSeoHome(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(96), dp(44)));
        Space spacer = new Space(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView rerun = primaryButton("Повторить");
        rerun.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (result.fullCrawl) {
                    CrawlerEngine.Config config = new CrawlerEngine.Config();
                    config.maxPages = Math.max(25, result.pagesCrawled);
                    config.maxDepth = 3;
                    startFullCrawl(result.target, config);
                } else startAudit(result.target);
            }
        });
        top.addView(rerun, new LinearLayout.LayoutParams(dp(112), dp(44)));
        page.addView(top);
        page.addView(label(result.host.length() > 0 ? result.host : result.target, 25, NAVY, true), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));
        page.addView(label("Проверено " + formatDate(result.timestamp) + " · " + result.durationMs + " мс", 13, MUTED, false));

        LinearLayout scoreCard = card(20);
        LinearLayout scoreRow = row();
        TextView score = label(String.valueOf(result.score), 42, scoreColor(result.score), true);
        score.setGravity(Gravity.CENTER);
        score.setBackground(round(scoreSoftColor(result.score), 999, scoreColor(result.score), 2));
        scoreRow.addView(score, new LinearLayout.LayoutParams(dp(92), dp(92)));
        LinearLayout scoreText = new LinearLayout(this);
        scoreText.setOrientation(LinearLayout.VERTICAL);
        scoreText.setPadding(dp(18), dp(5), 0, 0);
        scoreText.addView(label(scoreLabel(result.score), 21, NAVY, true));
        scoreText.addView(label(result.error.length() > 0 ? result.error : result.issues.size() + " проблем · " + result.passedChecks + " проверок пройдено", 14, MUTED, false));
        scoreText.addView(label(result.finalUrl.length() > 0 ? result.finalUrl : result.target, 12, PRIMARY, false));
        scoreRow.addView(scoreText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scoreCard.addView(scoreRow);
        LinearLayout counters = row();
        counters.addView(metricBox(String.valueOf(result.countSeverity("critical")), "Критично", RED, RED_SOFT), new LinearLayout.LayoutParams(0, dp(72), 1f));
        counters.addView(space(8));
        counters.addView(metricBox(String.valueOf(result.countSeverity("important")), "Важно", AMBER, AMBER_SOFT), new LinearLayout.LayoutParams(0, dp(72), 1f));
        counters.addView(space(8));
        counters.addView(metricBox(String.valueOf(result.countSeverity("warning")), "Замечания", PRIMARY, PRIMARY_SOFT), new LinearLayout.LayoutParams(0, dp(72), 1f));
        scoreCard.addView(counters, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));
        page.addView(scoreCard, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));

        page.addView(sectionTitle("Сводка", result.fullCrawl ? "Итоги многостраничного обхода" : "Измеренные параметры главной страницы"));
        LinearLayout summary = card(16);
        summary.addView(summaryRow("HTTP", result.httpCode == 0 ? "—" : String.valueOf(result.httpCode), result.httpCode >= 200 && result.httpCode < 400));
        summary.addView(divider());
        summary.addView(summaryRow("HTTPS / TLS", result.https ? (result.tlsValid ? "PASS · до " + value(result.tlsExpires) : "Требует проверки") : "Нет HTTPS", result.https && result.tlsValid));
        summary.addView(divider());
        summary.addView(summaryRow("Title", result.titleLength + " символов", result.titleLength >= 20 && result.titleLength <= 70));
        summary.addView(divider());
        summary.addView(summaryRow("Description", result.descriptionLength + " символов", result.descriptionLength >= 70 && result.descriptionLength <= 190));
        summary.addView(divider());
        summary.addView(summaryRow("H1", String.valueOf(result.h1Count), result.h1Count == 1));
        summary.addView(divider());
        summary.addView(summaryRow("Контент", result.wordCount + " слов · " + result.linksCount + " ссылок", result.wordCount >= 120));
        summary.addView(divider());
        summary.addView(summaryRow("Изображения", result.imagesCount + " · без alt: " + result.imagesMissingAlt, result.imagesMissingAlt == 0));
        summary.addView(divider());
        summary.addView(summaryRow("robots / sitemap", (result.robotsAvailable ? "robots ✓" : "robots —") + " · " + (result.sitemapAvailable ? "sitemap ✓" : "sitemap —"), result.robotsAvailable && result.sitemapAvailable));
        summary.addView(divider());
        summary.addView(summaryRow("CMS", result.cmsConfidence > 0 ? result.cmsName + " · " + result.cmsConfidence + "%" : "Не определена", result.cmsConfidence >= 60));
        summary.addView(divider());
        summary.addView(summaryRow("DNS", value(result.dnsStatus), !result.dnsRecords.isEmpty() && result.dnsResolversAgree));
        page.addView(summary);

        if (result.fullCrawl) {
            page.addView(sectionTitle("Покрытие сайта", "Crawler, sitemap, дубли и внутренняя структура"));
            LinearLayout crawlSummary = card(16);
            crawlSummary.addView(summaryRow("Страницы", result.pagesCrawled + " проверено · " + result.pagesDiscovered + " обнаружено", result.pagesCrawled >= Math.min(10, result.pagesDiscovered)));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Средний ответ", result.averageResponseMs + " мс", result.averageResponseMs > 0 && result.averageResponseMs < 1000));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Ошибочные URL", String.valueOf(result.brokenInternalLinks), result.brokenInternalLinks == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Редиректы", String.valueOf(result.redirectPages), result.redirectPages == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Noindex", String.valueOf(result.noindexPages), result.noindexPages == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Дубли Title", String.valueOf(result.duplicateTitles), result.duplicateTitles == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Дубли description", String.valueOf(result.duplicateDescriptions), result.duplicateDescriptions == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Дубли контента", result.duplicateContentGroups + " групп", result.duplicateContentGroups == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Canonical-проблемы", String.valueOf(result.canonicalProblems), result.canonicalProblems == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(summaryRow("Кандидаты-сироты", String.valueOf(result.orphanCandidates), result.orphanCandidates == 0));
            crawlSummary.addView(divider());
            crawlSummary.addView(keyValue("robots.txt", value(result.robotsSummary)));
            crawlSummary.addView(divider());
            crawlSummary.addView(keyValue("sitemap", value(result.sitemapSummary)));
            page.addView(crawlSummary);

            page.addView(sectionTitle("Проверенные страницы", result.pages.size() + " строк · показаны первые 50"));
            int pageLimit = Math.min(50, result.pages.size());
            for (int i = 0; i < pageLimit; i++) page.addView(pageAuditCard(result.pages.get(i)));
        }

        if (result.ipAddresses.length() > 0) {
            page.addView(sectionTitle("Инфраструктура", "Данные, полученные без внешних платных API"));
            LinearLayout infra = card(16);
            infra.addView(keyValue("IP", result.ipAddresses));
            infra.addView(divider());
            infra.addView(keyValue("Server", value(result.server)));
            infra.addView(divider());
            infra.addView(keyValue("Content-Type", value(result.contentType)));
            infra.addView(divider());
            infra.addView(keyValue("Canonical", value(result.canonical)));
            page.addView(infra);
        }

        page.addView(sectionTitle("Технологии сайта", result.cmsConfidence > 0 ? "CMS определена по совокупности независимых признаков" : "CMS не утверждается без достаточных доказательств"));
        LinearLayout cms = card(16);
        LinearLayout cmsTop = row();
        LinearLayout cmsText = new LinearLayout(this);
        cmsText.setOrientation(LinearLayout.VERTICAL);
        cmsText.addView(label(result.cmsName, 21, NAVY, true));
        cmsText.addView(label(result.cmsEvidence.length() > 0 ? result.cmsEvidence : "Надёжные сигнатуры не найдены", 12, MUTED, false));
        cmsTop.addView(cmsText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView confidence = badge(result.cmsConfidence > 0 ? result.cmsConfidence + "%" : "—", result.cmsConfidence >= 75 ? GREEN : (result.cmsConfidence >= 45 ? AMBER : MUTED), result.cmsConfidence >= 75 ? GREEN_SOFT : (result.cmsConfidence >= 45 ? AMBER_SOFT : Color.rgb(241, 244, 248)));
        cmsTop.addView(confidence);
        cms.addView(cmsTop);
        if (result.technologies.isEmpty()) {
            cms.addView(label("Технологии не обнаружены либо сайт скрывает сигнатуры.", 13, MUTED, false), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        } else {
            for (Models.TechnologyFinding finding : result.technologies) {
                cms.addView(divider());
                cms.addView(technologyRow(finding));
            }
        }
        page.addView(cms);

        page.addView(sectionTitle("DNS домена", result.dnsCheckedAt > 0 ? result.dnsStatus + " · " + formatDate(result.dnsCheckedAt) : result.dnsStatus));
        LinearLayout dnsSummary = card(16);
        dnsSummary.addView(summaryRow("Резолверы", result.dnsResolversCompared ? (result.dnsResolversAgree ? "Cloudflare и Google согласованы" : "Ответы различаются") : "Сравнение не завершено", result.dnsResolversCompared && result.dnsResolversAgree));
        dnsSummary.addView(divider());
        boolean hasMx = hasDnsType(result, "MX");
        dnsSummary.addView(summaryRow("DNSSEC", result.dnssecPresent ? "DNSKEY/DS или AD-флаг обнаружены" : "Криптографическая защита не подтверждена", result.dnssecPresent));
        dnsSummary.addView(divider());
        dnsSummary.addView(summaryRow("SPF", result.spfPresent ? "Настроен" : (hasMx ? "Не найден" : "MX не найден · не требуется"), result.spfPresent || !hasMx));
        dnsSummary.addView(divider());
        dnsSummary.addView(summaryRow("DMARC", result.dmarcPresent ? "Настроен" : (hasMx ? "Не найден" : "MX не найден · не требуется"), result.dmarcPresent || !hasMx));
        dnsSummary.addView(divider());
        dnsSummary.addView(summaryRow("CAA", result.caaPresent ? "Ограничение центров сертификации задано" : "Не задана · необязательно", true));
        dnsSummary.addView(divider());
        dnsSummary.addView(keyValue("DKIM", hasMx ? "Нужен selector; автоматически не утверждается" : "MX не найден"));
        dnsSummary.addView(divider());
        dnsSummary.addView(keyValue("DNS-источник", value(result.dnsResolver)));
        page.addView(dnsSummary);
        if (result.dnsRecords.isEmpty()) {
            page.addView(emptyState("DNS-записи не получены", "Проверьте доступ к UDP/53 или повторите аудит в другой сети."));
        } else {
            int dnsLimit = Math.min(60, result.dnsRecords.size());
            for (int i = 0; i < dnsLimit; i++) page.addView(dnsRecordCard(result.dnsRecords.get(i)));
            if (result.dnsRecords.size() > dnsLimit) page.addView(infoStrip("Показаны первые " + dnsLimit + " из " + result.dnsRecords.size() + " DNS-записей."));
        }

        page.addView(sectionTitle("Домен и сетевая инфраструктура", "RDAP домена и уже существующее IP/ASN-ядро SiteScope Pro"));
        LinearLayout domain = card(16);
        domain.addView(keyValue("Регистратор", value(result.domainRegistrar)));
        domain.addView(divider());
        domain.addView(keyValue("Зарегистрирован", value(result.domainRegistered)));
        domain.addView(divider());
        domain.addView(keyValue("Истекает", value(result.domainExpires)));
        domain.addView(divider());
        domain.addView(keyValue("Статусы домена", value(result.domainStatus)));
        domain.addView(divider());
        domain.addView(keyValue("NS", value(result.domainNameservers)));
        domain.addView(divider());
        domain.addView(keyValue("ASN", value(result.infrastructureAsn)));
        domain.addView(divider());
        domain.addView(keyValue("Сеть / провайдер", value(result.infrastructureProvider)));
        domain.addView(divider());
        domain.addView(keyValue("Reverse DNS", value(result.reverseDns)));
        page.addView(domain);

        page.addView(sectionTitle("Ошибки и рекомендации", result.issues.isEmpty() ? "Критичных проблем не обнаружено" : "Нажмите на карточку для подробного решения"));
        if (result.issues.isEmpty()) {
            page.addView(emptyState("Проверки пройдены", result.fullCrawl ? "В пределах текущего лимита crawler не обнаружил значимых нарушений." : "Для расширенного анализа запустите полный многостраничный обход."));
        } else {
            for (final Models.Issue issue : result.issues) {
                View issueView = issueCard(issue);
                issueView.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { issueDialog(issue); }
                });
                page.addView(issueView);
            }
        }
        screenContent.addView(scroll(page));
    }

    private boolean hasDnsType(Models.AuditResult result, String type) {
        for (Models.DnsRecord record : result.dnsRecords) if (type.equals(record.type)) return true;
        return false;
    }

    private LinearLayout technologyRow(Models.TechnologyFinding finding) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout top = row();
        top.addView(label(finding.name, 14, NAVY, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(badge(finding.category + " · " + finding.confidence + "%", finding.confidence >= 75 ? GREEN : PRIMARY, finding.confidence >= 75 ? GREEN_SOFT : PRIMARY_SOFT));
        row.addView(top);
        if (finding.evidence.length() > 0) row.addView(label(finding.evidence, 11, MUTED, false), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 5, 0, 0));
        return row;
    }

    private LinearLayout dnsRecordCard(Models.DnsRecord record) {
        LinearLayout card = card(13);
        LinearLayout top = row();
        TextView type = badge(record.type, PRIMARY, PRIMARY_SOFT);
        top.addView(type);
        TextView name = label(record.name, 12, NAVY, true);
        name.setTextIsSelectable(true);
        top.addView(name, marginParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0, 1f));
        top.addView(label("TTL " + record.ttl, 11, MUTED, false));
        card.addView(top);
        TextView value = label(record.value, 12, NAVY, false);
        value.setTextIsSelectable(true);
        card.addView(value, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));
        return card;
    }

    private void issueDialog(Models.Issue issue) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(4), dp(22), dp(8));
        body.addView(badge(severityTitle(issue.severity), severityColor(issue.severity), severitySoft(issue.severity)));
        if (issue.affectedUrl.length() > 0) {
            body.addView(label("Затронутый URL", 13, NAVY, true), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
            TextView affected = label(issue.affectedUrl, 13, PRIMARY, false);
            affected.setTextIsSelectable(true);
            body.addView(affected);
        }
        body.addView(label("Доказательство", 13, NAVY, true), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
        body.addView(label(value(issue.evidence), 14, MUTED, false));
        body.addView(label("Почему это важно", 13, NAVY, true), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
        body.addView(label(issue.explanation, 14, MUTED, false));
        body.addView(label("Как исправить", 13, NAVY, true), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
        TextView recommendation = label(issue.recommendation, 14, NAVY, false);
        recommendation.setPadding(dp(14), dp(12), dp(14), dp(12));
        recommendation.setBackground(round(PRIMARY_SOFT, 12, Color.TRANSPARENT, 0));
        body.addView(recommendation);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
            .setTitle(issue.title)
            .setView(scroll)
            .setPositiveButton("Готово", null)
            .show();
    }

    private LinearLayout pageAuditCard(Models.PageAudit pageAudit) {
        LinearLayout card = card(13);
        LinearLayout top = row();
        boolean ok = pageAudit.statusCode >= 200 && pageAudit.statusCode < 400 && pageAudit.indexable;
        TextView status = label(pageAudit.statusCode == 0 ? "ERR" : String.valueOf(pageAudit.statusCode), 12, ok ? GREEN : AMBER, true);
        status.setGravity(Gravity.CENTER);
        status.setBackground(round(ok ? GREEN_SOFT : AMBER_SOFT, 10, Color.TRANSPARENT, 0));
        top.addView(status, new LinearLayout.LayoutParams(dp(50), dp(40)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(10), 0, 0, 0);
        String title = pageAudit.title.length() > 0 ? pageAudit.title : "Без Title";
        text.addView(label(title, 14, NAVY, true));
        TextView url = label(pageAudit.finalUrl.length() > 0 ? pageAudit.finalUrl : pageAudit.requestedUrl, 11, PRIMARY, false);
        url.setTextIsSelectable(true);
        text.addView(url);
        text.addView(label("Глубина " + pageAudit.depth + " · " + pageAudit.wordCount + " слов · " + pageAudit.responseMs + " мс · H1: " + pageAudit.h1Count, 11, MUTED, false));
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);
        String flags = (pageAudit.indexable ? "indexable" : "noindex") + " · внутренних ссылок: " + pageAudit.internalLinks + " · изображений без alt: " + pageAudit.imagesMissingAlt;
        card.addView(label(flags, 11, pageAudit.indexable ? GREEN : AMBER, false), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));
        return card;
    }

    private LinearLayout issueCard(Models.Issue issue) {
        LinearLayout card = card(15);
        LinearLayout top = row();
        top.addView(badge(severityTitle(issue.severity), severityColor(issue.severity), severitySoft(issue.severity)));
        top.addView(label(issue.code, 11, MUTED, true), marginParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0, 1f));
        card.addView(top);
        card.addView(label(issue.title, 16, NAVY, true), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        if (issue.affectedUrl.length() > 0) card.addView(label(issue.affectedUrl, 11, PRIMARY, false), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 7, 0, 0));
        card.addView(label(issue.evidence, 13, MUTED, false));
        TextView hint = label("Открыть решение  ›", 13, PRIMARY, true);
        card.addView(hint, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        return card;
    }

    private LinearLayout summaryRow(String key, String value, boolean pass) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView state = label(pass ? "✓" : "!", 14, pass ? GREEN : AMBER, true);
        state.setGravity(Gravity.CENTER);
        state.setBackground(round(pass ? GREEN_SOFT : AMBER_SOFT, 999, Color.TRANSPARENT, 0));
        row.addView(state, new LinearLayout.LayoutParams(dp(30), dp(30)));
        row.addView(label(key, 14, NAVY, true), marginParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0, 1f));
        TextView valueView = label(value, 13, pass ? GREEN : MUTED, false);
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setPadding(0, dp(7), 0, dp(7));
        return row;
    }

    @Override public void onBackPressed() {
        if (visibleResult != null) { showSeoHome(); return; }
        super.onBackPressed();
    }
}
