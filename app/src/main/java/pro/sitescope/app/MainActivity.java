package pro.sitescope.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Прежняя главная страница. С версии 1.0 главный экран — pro.sitescope.app.ui.ShellActivity;
 * этот класс оставлен, чтобы старые ссылки (уведомления сканера, перезапуск после восстановления
 * резервной копии, нижняя панель прежних экранов) вели на новый экран.
 */
public class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent().setClassName(this, "pro.sitescope.app.ui.ShellActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (getIntent() != null && getIntent().getExtras() != null) intent.putExtras(getIntent().getExtras());
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }
}
