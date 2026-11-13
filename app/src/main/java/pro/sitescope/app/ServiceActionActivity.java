package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Protocol-aware action center for discovered network services. */
public final class ServiceActionActivity extends Activity {
    private static final int NAVY = Color.rgb(43, 38, 34);
    private static final int MUTED = Color.rgb(133, 122, 112);
    private static final int PRIMARY = Color.rgb(217, 83, 43);
    private static final int PRIMARY_SOFT = Color.rgb(255, 232, 222);
    private static final int GREEN = Color.rgb(20, 145, 93);
    private static final int GREEN_SOFT = Color.rgb(228, 247, 238);
    private static final int AMBER = Color.rgb(192, 116, 8);
    private static final int AMBER_SOFT = Color.rgb(255, 245, 222);
    private static final int BORDER = Color.rgb(233, 223, 212);
    private static final int SURFACE = Color.rgb(248, 243, 235);

    private ServiceEndpoint endpoint;
    private LinearLayout body;
    private LinearLayout protocolDetails;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        endpoint = ServiceEndpoint.from(getIntent());
        buildUi();
        renderEndpoint();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = row();
        header.setPadding(dp(14), dp(12), dp(14), dp(10));
        TextView back = text("‹", 30, PRIMARY, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { finish(); } });
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Центр подключения", 22, NAVY, true));
        titles.addView(text("Подтверждённый сервис и доступные клиенты", 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = text("☆", 25, PRIMARY, false);
        save.setGravity(Gravity.CENTER);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                ConnectionProfileStore.save(ServiceActionActivity.this, endpoint);
                save.setText("★");
                Toast.makeText(ServiceActionActivity.this, "Подключение сохранено", Toast.LENGTH_SHORT).show();
            }
        });
        header.addView(save, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(header);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(4), dp(18), dp(28));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void renderEndpoint() {
        body.removeAllViews();
        LinearLayout hero = card(18);
        LinearLayout top = row();
        TextView icon = text(protocolIcon(endpoint.protocol), iconSize(endpoint.protocol), PRIMARY, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(PRIMARY_SOFT, 18, Color.TRANSPARENT, 0));
        top.addView(icon, new LinearLayout.LayoutParams(dp(70), dp(70)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(14), 0, 0, 0);
        identity.addView(text(displayName(), 20, NAVY, true));
        identity.addView(text(endpoint.protocol + " · " + FeatureReadiness.label(endpoint.protocol) + " · " + "протокол: " + confidenceTitle(endpoint.confidence), 13, "BETA".equals(FeatureReadiness.protocolStatus(endpoint.protocol)) ? AMBER : GREEN, true));
        if (endpoint.resourceType.length() > 0) identity.addView(text(endpoint.resourceType, 13, MUTED, false));
        top.addView(identity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(top);
        hero.addView(infoRow("Endpoint", endpoint.endpointText()));
        if (endpoint.description.length() > 0) hero.addView(infoRow("Назначение", endpoint.description));
        if (endpoint.latencyMs > 0) hero.addView(infoRow("Задержка", endpoint.latencyMs + " мс"));
        if (endpoint.tlsSubject.length() > 0) hero.addView(infoRow("TLS subject", endpoint.tlsSubject));
        hero.addView(infoRow("Оборудование", endpoint.identity.summary()));
        hero.addView(infoRow("Уверенность в оборудовании", endpoint.identity.confidenceLabel()));
        if ("not_checked".equals(endpoint.tlsTrust)) hero.addView(infoRow("Доверие сертификату TLS", "Не проверялось"));
        body.addView(hero);
        if ("BETA".equals(FeatureReadiness.protocolStatus(endpoint.protocol))) {
            TextView beta = text("Бета-клиент: компонент компилируется и поддерживает базовый сценарий, но совместимость с конкретным сервером должна быть подтверждена на реальном оборудовании.", 12, Color.rgb(117,77,10), false);
            beta.setPadding(dp(14),dp(12),dp(14),dp(12)); beta.setBackground(round(AMBER_SOFT,13,Color.TRANSPARENT,0));
            body.addView(beta, margin(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,8,0,0));
        }

        body.addView(sectionTitle("Действия", primaryActionSubtitle()));
        renderActions();

        protocolDetails = new LinearLayout(this);
        protocolDetails.setOrientation(LinearLayout.VERTICAL);
        body.addView(protocolDetails);
        renderProtocolDetails();

        body.addView(sectionTitle("Технические сведения", "Результаты пассивного определения и протокольного рукопожатия"));
        LinearLayout details = card(15);
        if (endpoint.services.length() > 0) details.addView(infoRow("Сервисы", endpoint.services));
        if (endpoint.evidence.length() > 0) details.addView(infoRow("Признак протокола", endpoint.evidence));
        for (DeviceIdentity.Finding finding : endpoint.identity.findings) {
            details.addView(infoRow(finding.source, finding.value + " · " + finding.detail));
        }
        if (endpoint.banner.length() > 0) {
            TextView banner = text(endpoint.banner, 12, NAVY, false);
            banner.setTypeface(Typeface.MONOSPACE);
            banner.setTextIsSelectable(true);
            banner.setPadding(dp(12), dp(10), dp(12), dp(10));
            banner.setBackground(round(Color.rgb(246, 248, 252), 10, BORDER, 1));
            details.addView(banner, margin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        }
        TextView copy = outlineButton("Копировать адрес сервиса");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { ProtocolRouter.copy(ServiceActionActivity.this, endpoint.endpointText()); Toast.makeText(ServiceActionActivity.this, "Адрес скопирован", Toast.LENGTH_SHORT).show(); }
        });
        details.addView(copy, margin(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0, 12, 0, 0));
        body.addView(details);

        TextView safety = text("Подключение выполняется только по вашему действию. Приложение не подбирает пароли, не принимает неизвестные SSH fingerprint и не игнорирует ошибки TLS автоматически.", 12, Color.rgb(117, 77, 10), false);
        safety.setPadding(dp(14), dp(12), dp(14), dp(12));
        safety.setBackground(round(AMBER_SOFT, 13, Color.TRANSPARENT, 0));
        body.addView(safety, margin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
    }

    private void renderActions() {
        LinearLayout actionCard = card(14);
        TextView primary = primaryButton(primaryActionTitle());
        primary.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { performPrimaryAction(); }
        });
        actionCard.addView(primary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        LinearLayout secondary = row();
        TextView external = outlineButton("Внешний клиент");
        TextView remember = outlineButton("По умолчанию");
        secondary.addView(external, new LinearLayout.LayoutParams(0, dp(46), 1f));
        secondary.addView(space(8));
        secondary.addView(remember, new LinearLayout.LayoutParams(0, dp(46), 1f));
        external.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ProtocolRouter.openExternal(ServiceActionActivity.this, endpoint); } });
        remember.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showHandlerChoice(); } });
        actionCard.addView(secondary, margin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        if ("SSH".equals(endpoint.protocol)) {
            LinearLayout sshActions = row();
            TextView sftp = outlineButton("Файлы SFTP");
            TextView copySsh = outlineButton("Копировать SSH URI");
            sshActions.addView(sftp, new LinearLayout.LayoutParams(0, dp(44), 1f));
            sshActions.addView(space(8));
            sshActions.addView(copySsh, new LinearLayout.LayoutParams(0, dp(44), 1f));
            sftp.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ServiceEndpoint sftpEndpoint = cloneEndpoint(endpoint);
                    sftpEndpoint.protocol = "SFTP";
                    sftpEndpoint.uri = ServiceEndpoint.buildUri("SFTP", endpoint.host, endpoint.port, "");
                    ProtocolRouter.openBuiltIn(ServiceActionActivity.this, sftpEndpoint);
                }
            });
            copySsh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ProtocolRouter.copy(ServiceActionActivity.this, endpoint.uri); Toast.makeText(ServiceActionActivity.this, "SSH URI скопирован", Toast.LENGTH_SHORT).show(); } });
            actionCard.addView(sshActions, margin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        }
        body.addView(actionCard);
    }

    private void renderProtocolDetails() {
        protocolDetails.removeAllViews();
        if ("UPNP".equals(endpoint.protocol)) {
            protocolDetails.addView(sectionTitle("UPnP Device Center", "Загрузка read-only описания устройства по LOCATION"));
            final LinearLayout card = card(15);
            final ProgressBar progress = new ProgressBar(this);
            card.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            final TextView status = text("Получаем XML-описание устройства…", 13, MUTED, false);
            status.setGravity(Gravity.CENTER);
            card.addView(status);
            protocolDetails.addView(card);
            if (endpoint.location.startsWith("http://") || endpoint.location.startsWith("https://")) loadUpnpDescription(card, progress, status);
            else { progress.setVisibility(View.GONE); status.setText("LOCATION отсутствует — доступно только сохранение и просмотр обнаруженных данных."); }
        } else if ("MDNS".equals(endpoint.protocol)) {
            protocolDetails.addView(sectionTitle("mDNS / DNS-SD", "Объявленные сервисы преобразованы в действия соответствующих клиентов"));
            LinearLayout card = card(15);
            List<ServiceEndpoint> actions = mdnsActions(endpoint);
            if (actions.isEmpty()) card.addView(text("В ответе не удалось выделить SRV-сервис с поддерживаемым клиентом. Сырые записи доступны ниже.", 13, MUTED, false));
            for (final ServiceEndpoint action : actions) {
                TextView button = outlineButton(action.protocol + " · " + action.endpointText());
                button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { ProtocolRouter.dispatch(ServiceActionActivity.this, action); } });
                card.addView(button, margin(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0, 5, 0, 5));
            }
            protocolDetails.addView(card);
        } else if ("ONVIF".equals(endpoint.protocol)) {
            protocolDetails.addView(sectionTitle("ONVIF Camera Center", "Безопасный переход к web UI или RTSP-профилю камеры"));
            LinearLayout card = card(15);
            TextView web = outlineButton("Открыть web-интерфейс камеры");
            web.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    ServiceEndpoint webEndpoint = cloneEndpoint(endpoint);
                    webEndpoint.protocol = "HTTP";
                    webEndpoint.port = endpoint.port > 0 && endpoint.port != 3702 ? endpoint.port : 80;
                    webEndpoint.uri = ServiceEndpoint.buildUri("HTTP", endpoint.host, webEndpoint.port, endpoint.location);
                    ProtocolRouter.openBuiltIn(ServiceActionActivity.this, webEndpoint);
                }
            });
            card.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            TextView rtsp = outlineButton("Открыть RTSP вручную");
            rtsp.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    final android.widget.EditText input = new android.widget.EditText(ServiceActionActivity.this);
                    input.setText("rtsp://" + endpoint.host + ":554/");
                    new AlertDialog.Builder(ServiceActionActivity.this).setTitle("RTSP URL камеры").setView(input)
                        .setPositiveButton("Открыть", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                ServiceEndpoint rtspEndpoint = cloneEndpoint(endpoint);
                                rtspEndpoint.protocol = "RTSP";
                                rtspEndpoint.port = 554;
                                rtspEndpoint.uri = input.getText().toString().trim();
                                ProtocolRouter.openBuiltIn(ServiceActionActivity.this, rtspEndpoint);
                            }
                        }).setNegativeButton("Отмена", null).show();
                }
            });
            card.addView(rtsp, margin(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0, 8, 0, 0));
            protocolDetails.addView(card);
        }
    }

    private void loadUpnpDescription(final LinearLayout card, final ProgressBar progress, final TextView status) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final UpnpDescriptionParser.Description description = fetchUpnp(endpoint.location);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.setVisibility(View.GONE);
                            status.setVisibility(View.GONE);
                            if (description.friendlyName.length() > 0) card.addView(infoRow("Имя", description.friendlyName));
                            if (description.manufacturer.length() > 0) card.addView(infoRow("Производитель", description.manufacturer));
                            if (description.modelName.length() > 0) card.addView(infoRow("Модель", description.modelName));
                            if (description.serialNumber.length() > 0) card.addView(infoRow("Серийный номер", description.serialNumber));
                            if (description.deviceType.length() > 0) card.addView(infoRow("Тип", description.deviceType));
                            if (!description.services.isEmpty()) card.addView(infoRow("UPnP-сервисы", join(description.services, "\n")));
                            boolean hasContentDirectory = false;
                            for (UpnpDescriptionParser.ServiceInfo serviceInfo : description.serviceInfos) {
                                if (serviceInfo.serviceType.contains("ContentDirectory")) { hasContentDirectory = true; break; }
                            }
                            if (hasContentDirectory) {
                                TextView dlna = primaryButton("Открыть DLNA медиакаталог");
                                dlna.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View view) {
                                        Intent intent = new Intent(ServiceActionActivity.this, DlnaBrowserActivity.class);
                                        endpoint.put(intent);
                                        startActivity(intent);
                                    }
                                });
                                card.addView(dlna, margin(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0, 12, 0, 0));
                            }
                            final String webUrl = resolvePresentationUrl(endpoint.location, description.presentationUrl);
                            TextView web = primaryButton("Открыть web-интерфейс устройства");
                            web.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View view) {
                                    ServiceEndpoint webEndpoint = cloneEndpoint(endpoint);
                                    webEndpoint.protocol = webUrl.startsWith("https://") ? "HTTPS" : "HTTP";
                                    webEndpoint.uri = webUrl;
                                    ProtocolRouter.openBuiltIn(ServiceActionActivity.this, webEndpoint);
                                }
                            });
                            card.addView(web, margin(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0, 12, 0, 0));
                            TextView xml = outlineButton("Открыть XML-описание");
                            xml.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View view) {
                                    ServiceEndpoint xmlEndpoint = cloneEndpoint(endpoint);
                                    xmlEndpoint.protocol = endpoint.location.startsWith("https://") ? "HTTPS" : "HTTP";
                                    xmlEndpoint.uri = endpoint.location;
                                    ProtocolRouter.openBuiltIn(ServiceActionActivity.this, xmlEndpoint);
                                }
                            });
                            card.addView(xml, margin(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0, 8, 0, 0));
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { progress.setVisibility(View.GONE); status.setText("Описание устройства не получено: " + error.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private UpnpDescriptionParser.Description fetchUpnp(String location) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(location).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/xml,text/xml,*/*");
        connection.setRequestProperty("User-Agent", "SiteScopePro/0.23 UPnP-Inspector");
        try {
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 400) throw new Exception("HTTP " + connection.getResponseCode());
            return UpnpDescriptionParser.parse(new BufferedInputStream(connection.getInputStream()));
        } finally { connection.disconnect(); }
    }

    private void performPrimaryAction() {
        if (ProtocolRouter.supportsBuiltIn(endpoint.protocol)) {
            ProtocolRouter.openBuiltIn(this, endpoint);
            return;
        }
        if ("UPNP".equals(endpoint.protocol) && endpoint.location.length() > 0) {
            ServiceEndpoint xmlEndpoint = cloneEndpoint(endpoint);
            xmlEndpoint.protocol = endpoint.location.startsWith("https://") ? "HTTPS" : "HTTP";
            xmlEndpoint.uri = endpoint.location;
            ProtocolRouter.openBuiltIn(this, xmlEndpoint);
            return;
        }
        if ("ONVIF".equals(endpoint.protocol)) {
            ServiceEndpoint webEndpoint = cloneEndpoint(endpoint);
            webEndpoint.protocol = "HTTP";
            webEndpoint.port = endpoint.port > 0 && endpoint.port != 3702 ? endpoint.port : 80;
            webEndpoint.uri = ServiceEndpoint.buildUri("HTTP", endpoint.host, webEndpoint.port, endpoint.location);
            ProtocolRouter.openBuiltIn(this, webEndpoint);
            return;
        }
        if ("MDNS".equals(endpoint.protocol)) {
            Toast.makeText(this, "Объявленные mDNS-сервисы показаны ниже", Toast.LENGTH_SHORT).show();
            return;
        }
        ProtocolRouter.openExternal(this, endpoint);
    }

    private String primaryActionTitle() {
        if ("HTTP".equals(endpoint.protocol) || "HTTPS".equals(endpoint.protocol)) return "Открыть Web Client";
        if ("RTSP".equals(endpoint.protocol)) return "Открыть видеопоток";
        if ("SSH".equals(endpoint.protocol)) return "Открыть встроенный SSH";
        if ("SFTP".equals(endpoint.protocol)) return "Открыть встроенный SFTP";
        if ("RDP".equals(endpoint.protocol)) return "Открыть удалённый рабочий стол";
        if ("VNC".equals(endpoint.protocol)) return "Открыть встроенный VNC";
        if ("FTP".equals(endpoint.protocol) || "FTPS".equals(endpoint.protocol) || "WEBDAV".equals(endpoint.protocol)) return "Открыть встроенный файловый клиент";
        if ("SMB".equals(endpoint.protocol)) return "Открыть SMB-клиент";
        if ("SNMP".equals(endpoint.protocol)) return "Открыть SNMP Browser";
        if ("MQTT".equals(endpoint.protocol)) return "Открыть MQTT Client";
        if ("UPNP".equals(endpoint.protocol)) return "Открыть описание UPnP";
        if ("ONVIF".equals(endpoint.protocol)) return "Открыть центр камеры";
        if ("MDNS".equals(endpoint.protocol)) return "Показать объявленные сервисы";
        return "Открыть совместимым приложением";
    }

    private String primaryActionSubtitle() {
        if ("HTTP".equals(endpoint.protocol) || "HTTPS".equals(endpoint.protocol)) return "Встроенный браузер, TLS и внешний браузер";
        if ("SSH".equals(endpoint.protocol)) return "Терминал или SFTP без сохранения пароля";
        if ("VNC".equals(endpoint.protocol)) return "Нативный RFB-клиент с touch-указателем";
        if ("RDP".equals(endpoint.protocol)) return "Передача endpoint установленному RDP-клиенту";
        if ("SSH".equals(endpoint.protocol) || "SFTP".equals(endpoint.protocol)) return "Fingerprint, терминал и SFTP без сохранения пароля";
        if ("SNMP".equals(endpoint.protocol)) return "Read-only GET/WALK для разрешённого оборудования";
        if ("MQTT".equals(endpoint.protocol)) return "Ручная подписка и публикация без фоновых действий";
        if ("UPNP".equals(endpoint.protocol)) return "Описание устройства, web UI и UPnP-сервисы";
        return "Встроенный обработчик или совместимое Android-приложение";
    }

    private void showHandlerChoice() {
        final List<String> values = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        if (ProtocolRouter.supportsBuiltIn(endpoint.protocol)) { values.add(ProtocolPreferences.BUILTIN); labels.add("Встроенный клиент"); }
        values.add(ProtocolPreferences.EXTERNAL); labels.add("Внешнее приложение");
        values.add(ProtocolPreferences.PANEL); labels.add("Панель действий");
        final String current = ProtocolPreferences.handler(this, endpoint.protocol);
        int checked = 0;
        for (int i = 0; i < values.size(); i++) if (values.get(i).equals(current)) checked = i;
        new AlertDialog.Builder(this)
            .setTitle(endpoint.protocol + " открывать через")
            .setSingleChoiceItems(labels.toArray(new String[0]), checked, null)
            .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    AlertDialog alert = (AlertDialog) dialog;
                    int selected = alert.getListView().getCheckedItemPosition();
                    if (selected >= 0) {
                        ProtocolPreferences.setHandler(ServiceActionActivity.this, endpoint.protocol, values.get(selected));
                        Toast.makeText(ServiceActionActivity.this, "Обработчик сохранён", Toast.LENGTH_SHORT).show();
                    }
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private List<ServiceEndpoint> mdnsActions(ServiceEndpoint source) {
        List<String> protocols = MdnsServiceMapper.protocols(source.services + " " + source.evidence);
        List<ServiceEndpoint> result = new ArrayList<ServiceEndpoint>();
        for (String protocol : protocols) {
            ServiceEndpoint endpoint = cloneEndpoint(source);
            endpoint.protocol = protocol;
            endpoint.port = defaultPort(protocol, source.port);
            endpoint.uri = ServiceEndpoint.buildUri(protocol, source.host, endpoint.port, "");
            result.add(endpoint);
        }
        return result;
    }

    private int defaultPort(String protocol, int discovered) {
        if (discovered > 0 && discovered != 5353) return discovered;
        if ("HTTPS".equals(protocol)) return 443;
        if ("HTTP".equals(protocol)) return 80;
        if ("SSH".equals(protocol) || "SFTP".equals(protocol)) return 22;
        if ("SMB".equals(protocol)) return 445;
        if ("RTSP".equals(protocol)) return 554;
        if ("FTP".equals(protocol)) return 21;
        if ("VNC".equals(protocol)) return 5900;
        if ("MQTT".equals(protocol)) return 1883;
        return discovered;
    }

    private ServiceEndpoint cloneEndpoint(ServiceEndpoint value) {
        ServiceEndpoint result = new ServiceEndpoint();
        result.host = value.host; result.port = value.port; result.protocol = value.protocol; result.service = value.service;
        result.displayName = value.displayName; result.resourceType = value.resourceType; result.uri = value.uri;
        result.location = value.location; result.services = value.services; result.evidence = value.evidence;
        result.banner = value.banner; result.tlsSubject = value.tlsSubject; result.confidence = value.confidence;
        result.description = value.description; result.latencyMs = value.latencyMs;
        return result;
    }

    private String resolvePresentationUrl(String location, String presentation) {
        try {
            URL base = new URL(location);
            if (presentation == null || presentation.trim().length() == 0) return base.getProtocol() + "://" + base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "") + "/";
            return new URL(base, presentation.trim()).toString();
        } catch (Exception ignored) { return location; }
    }

    private String displayName() {
        if (endpoint.displayName.length() > 0) return endpoint.displayName;
        return endpoint.host + (endpoint.port > 0 ? ":" + endpoint.port : "");
    }

    private String protocolIcon(String protocol) {
        if ("HTTP".equals(protocol) || "HTTPS".equals(protocol)) return "🌐";
        if ("SSH".equals(protocol) || "SFTP".equals(protocol) || "TELNET".equals(protocol)) return ">_";
        if ("RDP".equals(protocol) || "VNC".equals(protocol)) return "▣";
        if ("RTSP".equals(protocol) || "ONVIF".equals(protocol)) return "▶";
        if ("SMB".equals(protocol) || "FTP".equals(protocol) || "FTPS".equals(protocol)) return "▤";
        if ("UPNP".equals(protocol) || "MDNS".equals(protocol)) return "⌁";
        return "◎";
    }

    private int iconSize(String protocol) { return protocolIcon(protocol).length() <= 2 ? 26 : 19; }

    private String confidenceTitle(String confidence) {
        if ("high".equals(confidence)) return "подтверждён";
        if ("medium".equals(confidence)) return "вероятен";
        return "требует проверки";
    }

    private LinearLayout infoRow(String key, String value) {
        LinearLayout row = row();
        row.setPadding(0, dp(7), 0, dp(7));
        TextView keyView = text(key, 12, MUTED, true);
        row.addView(keyView, new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView valueView = text(value == null || value.length() == 0 ? "—" : value, 13, NAVY, false);
        valueView.setTextIsSelectable(true);
        row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout sectionTitle(String title, String subtitle) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(20), 0, dp(9));
        block.addView(text(title, 18, NAVY, true));
        block.addView(text(subtitle, 12, MUTED, false));
        return block;
    }

    private LinearLayout card(int padding) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        card.setBackground(round(SURFACE, 18, BORDER, 1));
        card.setElevation(dp(2));
        card.setLayoutParams(margin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 5, 0, 5));
        return card;
    }

    private TextView primaryButton(String title) { return actionButton(title, Color.WHITE, PRIMARY, Color.TRANSPARENT); }
    private TextView outlineButton(String title) { return actionButton(title, PRIMARY, Color.WHITE, BORDER); }
    private TextView actionButton(String title, int textColor, int background, int stroke) {
        TextView button = text(title, 14, textColor, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(round(background, 13, stroke, stroke == Color.TRANSPARENT ? 0 : 1));
        button.setClickable(true);
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private View space(int width) {
        android.widget.Space space = new android.widget.Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1));
        return space;
    }

    private GradientDrawable round(int color, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (width > 0 && stroke != Color.TRANSPARENT) drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) { if (builder.length() > 0) builder.append(delimiter); builder.append(value); }
        return builder.toString();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

}
