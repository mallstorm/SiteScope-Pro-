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
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public final class SettingsActivity extends Activity {
    private static final int REQUEST_EXPORT = 801;
    private static final int REQUEST_IMPORT = 802;
    private static final int NAVY = Color.rgb(22, 32, 51);
    private static final int MUTED = Color.rgb(104, 116, 137);
    private static final int PRIMARY = Color.rgb(13, 99, 229);
    private static final int PRIMARY_SOFT = Color.rgb(231, 240, 255);
    private static final int BORDER = Color.rgb(222, 229, 239);
    private static final int SURFACE = Color.rgb(248, 243, 235);

    private EditText timeout;
    private EditText concurrency;
    private CheckBox detect;
    private CheckBox confirmLarge;
    private CheckBox compact;
    private TextView dataSummary;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(34));
        page.setBackgroundColor(Color.WHITE);

        LinearLayout header = row();
        TextView back = label("‹", 30, NAVY, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(label("Настройки", 24, NAVY, true));
        title.addView(label("Резервное копирование, сканер и интерфейс", 13, MUTED, false));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(header);

        page.addView(section("Резервное копирование", "Перенос проектов и истории между установками"));
        LinearLayout backup = card(18);
        dataSummary = label(BackupManager.currentSummary(this), 13, NAVY, true);
        backup.addView(dataSummary);
        backup.addView(info("В архив входят проекты, SEO-аудиты, IP-сканирования, IPTV-каталог, избранное, история IP Intelligence, сохранённые подключения и правила Protocol Router. OAuth/API-секреты, пароли, ключи и скачанные GeoIP-базы не экспортируются."));
        TextView export = button("Экспортировать резервную копию", PRIMARY, Color.WHITE);
        export.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startExport(); } });
        backup.addView(export, margin(dp(48), 12));
        TextView restore = button("Восстановить из файла", NAVY, Color.WHITE);
        restore.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmImport(); } });
        backup.addView(restore, margin(dp(48), 8));
        page.addView(backup);

        page.addView(section("Сканер IP и портов", "Значения по умолчанию для новых заданий"));
        LinearLayout scanner = card(18);
        LinearLayout values = row();
        LinearLayout timeoutBox = fieldBox("Timeout, мс");
        timeout = input(String.valueOf(SettingsStore.scannerTimeoutMs(this)));
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeoutBox.addView(timeout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout concurrencyBox = fieldBox("Параллельные потоки");
        concurrency = input(String.valueOf(SettingsStore.scannerConcurrency(this)));
        concurrency.setInputType(InputType.TYPE_CLASS_NUMBER);
        concurrencyBox.addView(concurrency, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        values.addView(timeoutBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        values.addView(space(10));
        values.addView(concurrencyBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanner.addView(values);
        detect = check("Определять сервисы по безопасным сигнатурам", SettingsStore.scannerDetectServices(this));
        confirmLarge = check("Подтверждать крупные задания перед запуском", SettingsStore.confirmLargeTasks(this));
        scanner.addView(detect, marginWrap(10));
        scanner.addView(confirmLarge);
        TextView save = button("Сохранить настройки сканера", Color.WHITE, PRIMARY);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveScanner(); } });
        scanner.addView(save, margin(dp(48), 12));
        page.addView(scanner);

        page.addView(section("Сетевые клиенты", "Protocol Router и сохранённые подключения"));
        LinearLayout clients = card(18);
        clients.addView(info("HTTP/HTTPS и RTSP могут открываться встроенными клиентами. SSH, SFTP, RDP, VNC, SMB и другие протоколы передаются совместимому Android-приложению. Учётные данные в профилях не сохраняются."));
        TextView connections = button("Открыть сохранённые подключения", Color.WHITE, PRIMARY);
        connections.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ConnectionsActivity.class)); }
        });
        clients.addView(connections, margin(dp(48), 12));
        TextView resetHandlers = button("Сбросить обработчики протоколов", NAVY, Color.WHITE);
        resetHandlers.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("protocol_router_preferences", MODE_PRIVATE).edit().clear().apply();
                Toast.makeText(SettingsActivity.this, "Назначения клиентов сброшены", Toast.LENGTH_SHORT).show();
            }
        });
        clients.addView(resetHandlers, margin(dp(48), 8));
        page.addView(clients);

        page.addView(section("Интерфейс", "Плотность карточек и системные параметры"));
        LinearLayout ui = card(18);
        compact = check("Компактные карточки проектов и истории", SettingsStore.compactCards(this));
        compact.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SettingsStore.setCompactCards(SettingsActivity.this, compact.isChecked());
                Toast.makeText(SettingsActivity.this, "Применится после возврата на экран", Toast.LENGTH_SHORT).show();
            }
        });
        ui.addView(compact);
        TextView appSettings = button("Открыть системные настройки приложения", NAVY, Color.WHITE);
        appSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });
        ui.addView(appSettings, margin(dp(48), 12));
        page.addView(ui);

        page.addView(section("Версия", "Техническая информация"));
        LinearLayout about = card(18);
        about.addView(keyValue("Версия", "0.28.0-alpha26"));
        about.addView(keyValue("Схема backup", String.valueOf(BackupManager.SCHEMA_VERSION)));
        about.addView(keyValue("Package", getPackageName()));
        page.addView(about);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void saveScanner() {
        int timeoutValue = parse(timeout.getText().toString(), 650);
        int concurrencyValue = parse(concurrency.getText().toString(), 32);
        SettingsStore.saveScanner(this, timeoutValue, concurrencyValue, detect.isChecked(), confirmLarge.isChecked());
        timeout.setText(String.valueOf(SettingsStore.scannerTimeoutMs(this)));
        concurrency.setText(String.valueOf(SettingsStore.scannerConcurrency(this)));
        Toast.makeText(this, "Настройки сканера сохранены", Toast.LENGTH_SHORT).show();
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, BackupManager.suggestedFileName());
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void confirmImport() {
        new AlertDialog.Builder(this)
            .setTitle("Восстановление данных")
            .setMessage("Текущие проекты, история, IPTV-каталог и настройки будут заменены данными из выбранного файла. Сначала рекомендуется сделать экспорт текущего состояния.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выбрать файл", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    startActivityForResult(intent, REQUEST_IMPORT);
                }
            }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            try {
                BackupManager.exportTo(this, uri);
                Toast.makeText(this, "Резервная копия сохранена", Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                showError("Не удалось создать резервную копию", error);
            }
        } else if (requestCode == REQUEST_IMPORT) {
            try {
                final JSONObject backup = BackupManager.readBackup(this, uri);
                new AlertDialog.Builder(this)
                    .setTitle("Подтвердить восстановление")
                    .setMessage(BackupManager.describe(backup) + "\n\nТекущие данные будут заменены.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Восстановить", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            try {
                                BackupManager.restore(SettingsActivity.this, backup);
                                dataSummary.setText(BackupManager.currentSummary(SettingsActivity.this));
                                Toast.makeText(SettingsActivity.this, "Данные восстановлены", Toast.LENGTH_LONG).show();
                                Intent restart = new Intent(SettingsActivity.this, MainActivity.class);
                                restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(restart);
                                finish();
                            } catch (Exception error) {
                                showError("Не удалось восстановить данные", error);
                            }
                        }
                    }).show();
            } catch (Exception error) {
                showError("Файл резервной копии повреждён или несовместим", error);
            }
        }
    }

    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()).setPositiveButton("Закрыть", null).show();
    }

    private LinearLayout fieldBox(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(title, 11, MUTED, true));
        return box;
    }

    private CheckBox check(String title, boolean checked) {
        CheckBox view = new CheckBox(this);
        view.setText(title);
        view.setTextColor(NAVY);
        view.setChecked(checked);
        return view;
    }

    private EditText input(String value) {
        EditText view = new EditText(this);
        view.setText(value);
        view.setTextColor(NAVY);
        view.setTextSize(15);
        view.setSingleLine(true);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(round(Color.WHITE, 13, BORDER, 1));
        return view;
    }

    private LinearLayout card(int padding) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        card.setBackground(round(SURFACE, 18, BORDER, 1));
        card.setElevation(dp(2));
        card.setLayoutParams(marginWrap(8));
        return card;
    }

    private LinearLayout section(String title, String subtitle) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(20), 0, dp(9));
        block.addView(label(title, 18, NAVY, true));
        block.addView(label(subtitle, 12, MUTED, false));
        return block;
    }

    private TextView info(String value) {
        TextView view = label(value, 12, Color.rgb(49, 78, 123), false);
        view.setPadding(dp(13), dp(11), dp(13), dp(11));
        view.setBackground(round(PRIMARY_SOFT, 12, Color.TRANSPARENT, 0));
        view.setLayoutParams(marginWrap(12));
        return view;
    }

    private TextView button(String title, int textColor, int background) {
        TextView view = label(title, 14, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(background, 13, background == Color.WHITE ? BORDER : Color.TRANSPARENT, background == Color.WHITE ? 1 : 0));
        view.setClickable(true);
        return view;
    }

    private LinearLayout keyValue(String key, String value) {
        LinearLayout row = row();
        row.setPadding(0, dp(7), 0, dp(7));
        row.addView(label(key, 13, MUTED, true), new LinearLayout.LayoutParams(dp(115), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(label(value, 13, NAVY, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
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
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1));
        return view;
    }

    private LinearLayout.LayoutParams margin(int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams marginWrap(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    private int parse(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
