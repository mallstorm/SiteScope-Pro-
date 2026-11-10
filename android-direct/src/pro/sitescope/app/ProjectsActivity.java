package pro.sitescope.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class ProjectsActivity extends BaseSiteScopeActivity {
    private DataStore store;
    private ProjectAdapter adapter;
    private String activeFilter = "ALL";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DataStore(this);
        setRootScreen(buildScreen(), "PROJECTS");
    }

    @Override protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.reload(activeFilter);
    }

    private View buildScreen() {
        LinearLayout root = page();
        root.addView(appHeader("Проекты", "Сохранённые сайты, сети и IPTV", true));

        LinearLayout actions = row();
        TextView add = outlineButton("+ SEO-проект");
        TextView settings = outlineButton("Настройки / backup");
        actions.addView(add, new LinearLayout.LayoutParams(0, dp(46), 1f));
        actions.addView(space(8));
        actions.addView(settings, new LinearLayout.LayoutParams(0, dp(46), 1f));
        root.addView(actions, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

        LinearLayout filters = row();
        final TextView all = smallOutlineButton("Все");
        final TextView seo = smallOutlineButton("SEO");
        final TextView ip = smallOutlineButton("IP");
        final TextView iptv = smallOutlineButton("IPTV");
        final TextView archived = smallOutlineButton("Архив");
        TextView[] items = {all, seo, ip, iptv, archived};
        for (int i = 0; i < items.length; i++) {
            filters.addView(items[i], new LinearLayout.LayoutParams(0, dp(40), 1f));
            if (i < items.length - 1) filters.addView(space(5));
        }
        root.addView(filters, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 8));

        ListView list = new ListView(this);
        list.setDividerHeight(dp(8));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(16));
        adapter = new ProjectAdapter();
        adapter.reload(activeFilter);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) { openProject(adapter.getItem(position)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) { showActions(adapter.getItem(position)); return true; }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View.OnClickListener choose = new View.OnClickListener() {
            @Override public void onClick(View view) {
                activeFilter = view == seo ? "SEO" : view == ip ? "IP" : view == iptv ? "IPTV" : view == archived ? "ARCHIVED" : "ALL";
                adapter.reload(activeFilter);
            }
        };
        all.setOnClickListener(choose); seo.setOnClickListener(choose); ip.setOnClickListener(choose); iptv.setOnClickListener(choose); archived.setOnClickListener(choose);
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { addProjectDialog(); } });
        settings.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { startActivity(new Intent(ProjectsActivity.this, SettingsActivity.class)); } });
        return root;
    }

    private View projectCard(final Models.Project project) {
        LinearLayout card = card(SettingsStore.compactCards(this) ? 12 : 15);
        card.setClickable(true);
        LinearLayout top = row();
        String iconText = "SEO".equals(project.type) ? "SEO" : "IPTV".equals(project.type) ? "TV" : "IP";
        int typeColor = "SEO".equals(project.type) ? PRIMARY : "IPTV".equals(project.type) ? android.graphics.Color.rgb(109, 67, 190) : GREEN;
        int typeSoft = "SEO".equals(project.type) ? PRIMARY_SOFT : "IPTV".equals(project.type) ? android.graphics.Color.rgb(241, 233, 255) : GREEN_SOFT;
        TextView icon = label(iconText, iconText.length() > 2 ? 11 : 15, typeColor, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(typeSoft, 14, android.graphics.Color.TRANSPARENT, 0));
        top.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, 0, 0);
        details.addView(label((project.pinned ? "★ " : "") + project.name, 16, NAVY, true));
        details.addView(label(project.target, 12, MUTED, false));
        if (project.summary.length() > 0) details.addView(label(project.summary, 12, MUTED, false));
        if (project.tags.length() > 0) details.addView(label("Теги: " + project.tags, 11, PRIMARY, false));
        if (project.notes.length() > 0 && !SettingsStore.compactCards(this)) details.addView(label(project.notes, 12, MUTED, false));
        details.addView(label(project.updatedAt > 0 ? "Обновлён " + formatDate(project.updatedAt) : "Ещё не запускался", 12, MUTED, false));
        top.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (project.score >= 0 && "SEO".equals(project.type)) {
            TextView score = label(String.valueOf(project.score), 18, scoreColor(project.score), true);
            score.setGravity(Gravity.CENTER);
            score.setBackground(round(scoreSoftColor(project.score), 12, android.graphics.Color.TRANSPARENT, 0));
            top.addView(score, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        card.addView(top);
        return card;
    }

    private void openProject(Models.Project project) {
        if ("IP".equals(project.type)) {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra("target", project.target);
            startActivity(intent);
        } else if ("IPTV".equals(project.type)) {
            startActivity(new Intent(this, IptvActivity.class));
        } else {
            Intent intent = new Intent(this, SeoActivity.class);
            intent.putExtra("target", project.target);
            startActivity(intent);
        }
    }

    private void showActions(final Models.Project project) {
        final String pinAction = project.pinned ? "Открепить" : "Закрепить";
        final String archiveAction = project.archived ? "Вернуть из архива" : "Архивировать";
        String[] actions = {"Открыть", "Изменить название, заметки и теги", pinAction, archiveAction, "Удалить"};
        new AlertDialog.Builder(this).setTitle(project.name + " · " + project.type).setItems(actions, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                if (which == 0) openProject(project);
                else if (which == 1) editProjectDialog(project);
                else if (which == 2) { store.setProjectPinned(project.type, project.target, !project.pinned); adapter.reload(activeFilter); }
                else if (which == 3) { store.setProjectArchived(project.type, project.target, !project.archived); adapter.reload(activeFilter); }
                else confirmDelete(project);
            }
        }).show();
    }

    private void editProjectDialog(final Models.Project project) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), 0);
        final EditText name = input("Название проекта"); name.setText(project.name);
        final EditText tags = input("Теги через запятую"); tags.setText(project.tags);
        final EditText notes = new EditText(this);
        notes.setHint("Заметки о проекте"); notes.setText(project.notes); notes.setTextColor(NAVY); notes.setHintTextColor(MUTED);
        notes.setMinLines(3); notes.setGravity(Gravity.TOP); notes.setPadding(dp(14), dp(12), dp(14), dp(12));
        notes.setBackground(round(android.graphics.Color.WHITE, 13, BORDER, 1));
        form.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        form.addView(tags, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, 10, 0, 0));
        form.addView(notes, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110), 0, 10, 0, 0));
        new AlertDialog.Builder(this).setTitle("Редактирование проекта").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                store.updateProjectMetadata(project.type, project.target, name.getText().toString(), notes.getText().toString(), tags.getText().toString(), project.pinned, project.archived);
                adapter.reload(activeFilter);
            }
        }).show();
    }

    private void confirmDelete(final Models.Project project) {
        new AlertDialog.Builder(this).setTitle("Удалить проект?").setMessage(project.name + " · " + project.type + "\nИстория анализов останется.")
            .setNegativeButton("Отмена", null).setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { store.removeProject(project.type, project.target); adapter.reload(activeFilter); }
            }).show();
    }

    private void addProjectDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), 0);
        final EditText name = input("Название проекта");
        final EditText target = input("example.com");
        form.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        form.addView(target, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, 10, 0, 0));
        new AlertDialog.Builder(this).setTitle("Новый SEO-проект").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Добавить", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                String targetValue = target.getText().toString().trim();
                if (targetValue.length() == 0) return;
                String projectName = name.getText().toString().trim();
                if (projectName.length() == 0) projectName = AuditEngine.hostFrom(targetValue);
                store.upsertProject("SEO", projectName, AuditEngine.normalizeTarget(targetValue), -1, "SEO-проект · аудит ещё не запускался");
                adapter.reload(activeFilter);
            }
        }).show();
    }

    private final class ProjectAdapter extends BaseAdapter {
        private final List<Models.Project> rows = new ArrayList<Models.Project>();
        void reload(String filter) {
            rows.clear();
            for (Models.Project p : store.projects()) {
                boolean show = "ARCHIVED".equals(filter) ? p.archived : !p.archived && ("ALL".equals(filter) || filter.equals(p.type));
                if (show) rows.add(p);
            }
            notifyDataSetChanged();
        }
        @Override public int getCount() { return rows.size(); }
        @Override public Models.Project getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) { return projectCard(getItem(position)); }
    }
}
