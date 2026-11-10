package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;

public class PlayerActivity extends Activity {
    private static final int PANEL = Color.rgb(18, 25, 38);
    private static final int PANEL_SOFT = Color.rgb(31, 41, 57);
    private static final int MUTED = Color.rgb(172, 183, 199);
    private static final int PRIMARY = Color.rgb(50, 128, 255);

    private FrameLayout playerRoot;
    private FrameLayout videoStage;
    private VideoView video;
    private float videoAspectRatio = 16f / 9f;
    private ProgressBar loading;
    private TextView state;
    private TextView title;
    private TextView detailsView;
    private TextView playPause;
    private LinearLayout controls;
    private Handler handler;
    private Runnable hideControls;

    private String url;
    private String sourceUrl;
    private String name;
    private String details;
    private String previousUrl;
    private String previousSourceUrl;
    private String previousName;
    private String previousDetails;
    private String nextUrl;
    private String nextSourceUrl;
    private String nextName;
    private String nextDetails;
    private boolean immersive;
    private MulticastRelay multicastRelay;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler = new Handler(Looper.getMainLooper());
        readIntent();
        setContentView(buildUi());
        startPlayback();
    }

    @Override protected void onPause() {
        if (!isInPictureInPictureMode() && video != null && video.isPlaying()) {
            video.pause();
            updatePlayButton();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopCurrentPlayback();
        if (handler != null && hideControls != null) handler.removeCallbacks(hideControls);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }


    @Override public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        if (controls == null) return;
        FrameLayout root = playerRoot;
        controls.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (root.getChildCount() > 3) root.getChildAt(3).setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (!inPictureInPictureMode) scheduleHide();
    }

    private void readIntent() {
        url = safe(getIntent().getStringExtra("url"));
        sourceUrl = safe(getIntent().getStringExtra("source_url"));
        if (sourceUrl.length() == 0) sourceUrl = url;
        name = safe(getIntent().getStringExtra("name"));
        details = safe(getIntent().getStringExtra("details"));
        previousUrl = safe(getIntent().getStringExtra("previous_url"));
        previousSourceUrl = safe(getIntent().getStringExtra("previous_source_url"));
        previousName = safe(getIntent().getStringExtra("previous_name"));
        previousDetails = safe(getIntent().getStringExtra("previous_details"));
        nextUrl = safe(getIntent().getStringExtra("next_url"));
        nextSourceUrl = safe(getIntent().getStringExtra("next_source_url"));
        nextName = safe(getIntent().getStringExtra("next_name"));
        nextDetails = safe(getIntent().getStringExtra("next_details"));
        if (name.length() == 0) name = "IPTV поток";
    }

    private View buildUi() {
        playerRoot = new FrameLayout(this);
        playerRoot.setBackgroundColor(Color.BLACK);

        videoStage = new FrameLayout(this);
        videoStage.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams stageParams = new FrameLayout.LayoutParams(-1, dp(220));
        stageParams.gravity = Gravity.CENTER;
        playerRoot.addView(videoStage, stageParams);

        video = new VideoView(this);
        video.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(-1, -1);
        videoParams.gravity = Gravity.CENTER;
        videoStage.addView(video, videoParams);

        loading = new ProgressBar(this);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(52), dp(52));
        loadingParams.gravity = Gravity.CENTER;
        playerRoot.addView(loading, loadingParams);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackground(gradientPanel());
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(-1, -2);
        controlsParams.gravity = Gravity.TOP;
        playerRoot.addView(controls, controlsParams);

        LinearLayout top = row();
        top.setPadding(dp(8), dp(7), dp(8), dp(7));
        TextView back = icon("‹", 28);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(8), 0, 0, 0);
        title = text(name, 16, Color.WHITE, true);
        title.setSingleLine(true);
        detailsView = text(details, 11, MUTED, false);
        detailsView.setSingleLine(true);
        textBox.addView(title);
        textBox.addView(detailsView);
        top.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView info = icon("i", 18);
        TextView pip = icon("▣", 17);
        TextView full = icon("⛶", 18);
        top.addView(info, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(pip, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(full, new LinearLayout.LayoutParams(dp(40), dp(40)));
        controls.addView(top);

        LinearLayout bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(12), dp(8), dp(12), dp(12));
        bottomPanel.setBackground(round(PANEL, 0, Color.TRANSPARENT, 0));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2);
        bottomParams.gravity = Gravity.BOTTOM;
        playerRoot.addView(bottomPanel, bottomParams);

        state = text("Подключение к потоку…", 11, MUTED, false);
        state.setSingleLine(true);
        bottomPanel.addView(state);

        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        TextView previous = control("‹‹", previousUrl.length() > 0);
        playPause = control("▶", true);
        TextView next = control("››", nextUrl.length() > 0);
        TextView retry = control("↻", true);
        TextView external = control("↗", true);
        actions.addView(previous, new LinearLayout.LayoutParams(0, dp(46), 1f));
        actions.addView(gap());
        actions.addView(playPause, new LinearLayout.LayoutParams(0, dp(46), 1.25f));
        actions.addView(gap());
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(46), 1f));
        actions.addView(gap());
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(46), 1f));
        actions.addView(gap());
        actions.addView(external, new LinearLayout.LayoutParams(0, dp(46), 1f));
        bottomPanel.addView(actions, margin(-1, -2, 0, 8, 0, 0));

        video.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleControls(); } });
        top.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { scheduleHide(); } });
        bottomPanel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { scheduleHide(); } });

        playPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (video.isPlaying()) video.pause();
                else video.start();
                updatePlayButton();
                scheduleHide();
            }
        });
        retry.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startPlayback(); } });
        external.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openExternal(); } });
        info.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showInfo(); } });
        pip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { enterPip(); } });
        full.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleImmersive(); } });
        previous.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { switchChannel(false); } });
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { switchChannel(true); } });

        hideControls = new Runnable() { @Override public void run() { controls.setVisibility(View.GONE); bottomPanel.setVisibility(View.GONE); } };
        scheduleHide();
        playerRoot.post(new Runnable() { @Override public void run() { updateVideoStageLayout(); } });
        return playerRoot;
    }

    private void startPlayback() {
        stopCurrentPlayback();
        if (url.length() == 0) {
            state.setText("URL потока отсутствует");
            loading.setVisibility(View.GONE);
            return;
        }
        loading.setVisibility(View.VISIBLE);
        playPause.setText("▶");
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("udp://") || lower.startsWith("rtp://")) startDirectMulticast();
        else startVideo(url, "Сетевой поток");
    }

    private void startDirectMulticast() {
        try {
            state.setText("Подготовка прямого multicast…");
            multicastRelay = new MulticastRelay(this, url, new MulticastRelay.Listener() {
                @Override public void onStatus(final String value) {
                    runOnUiThread(new Runnable() { @Override public void run() { state.setText(value); } });
                }
                @Override public void onStats(final long packets, final long bytes, final long lost, final String iface) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            state.setText("Multicast · " + iface + " · " + packets + " пакетов · " + (bytes / 1024L) + " КиБ" + (lost > 0 ? " · потери " + lost : ""));
                        }
                    });
                }
                @Override public void onError(final String message) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loading.setVisibility(View.GONE);
                            state.setText("Ошибка multicast: " + message);
                        }
                    });
                }
            });
            String localUrl = multicastRelay.start();
            startVideo(localUrl, "Прямой UDP/RTP multicast");
        } catch (Exception error) {
            loading.setVisibility(View.GONE);
            state.setText("Ошибка multicast: " + error.getMessage());
        }
    }

    private void startVideo(String playback, final String mode) {
        try {
            loading.setVisibility(View.VISIBLE);
            state.setText("Подключение · " + mode);
            video.setVideoURI(Uri.parse(playback));
            video.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(android.media.MediaPlayer player) {
                    loading.setVisibility(View.GONE);
                    int width = player.getVideoWidth();
                    int height = player.getVideoHeight();
                    if (width > 0 && height > 0) {
                        videoAspectRatio = (float) width / (float) height;
                        updateVideoStageLayout();
                    }
                    state.setText(mode + " · " + width + "×" + height);
                    player.setScreenOnWhilePlaying(true);
                    player.setOnVideoSizeChangedListener(new android.media.MediaPlayer.OnVideoSizeChangedListener() {
                        @Override public void onVideoSizeChanged(android.media.MediaPlayer mp, int videoWidth, int videoHeight) {
                            if (videoWidth > 0 && videoHeight > 0) {
                                videoAspectRatio = (float) videoWidth / (float) videoHeight;
                                updateVideoStageLayout();
                            }
                        }
                    });
                    video.start();
                    updatePlayButton();
                    scheduleHide();
                }
            });
            video.setOnInfoListener(new android.media.MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(android.media.MediaPlayer mp, int what, int extra) {
                    if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        loading.setVisibility(View.VISIBLE);
                        state.setText(mode + " · буферизация…");
                    } else if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        loading.setVisibility(View.GONE);
                        state.setText(mode + " · воспроизведение");
                    }
                    return false;
                }
            });
            video.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mp) { updatePlayButton(); showControls(); }
            });
            video.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                    loading.setVisibility(View.GONE);
                    state.setText("Ошибка потока или кодека: " + what + "/" + extra);
                    playPause.setText("▶");
                    showControls();
                    return true;
                }
            });
            video.requestFocus();
            video.start();
        } catch (Exception error) {
            loading.setVisibility(View.GONE);
            state.setText("Ошибка: " + error.getMessage());
        }
    }

    private void switchChannel(boolean forward) {
        String targetUrl = forward ? nextUrl : previousUrl;
        if (targetUrl.length() == 0) return;
        String targetSource = forward ? nextSourceUrl : previousSourceUrl;
        String targetName = forward ? nextName : previousName;
        String targetDetails = forward ? nextDetails : previousDetails;

        String oldUrl = url;
        String oldSource = sourceUrl;
        String oldName = name;
        String oldDetails = details;
        url = targetUrl;
        sourceUrl = targetSource.length() > 0 ? targetSource : targetUrl;
        name = targetName.length() > 0 ? targetName : "IPTV поток";
        details = targetDetails;
        if (forward) {
            previousUrl = oldUrl;
            previousSourceUrl = oldSource;
            previousName = oldName;
            previousDetails = oldDetails;
        } else {
            nextUrl = oldUrl;
            nextSourceUrl = oldSource;
            nextName = oldName;
            nextDetails = oldDetails;
        }
        title.setText(name);
        detailsView.setText(details);
        startPlayback();
    }

    private void updatePlayButton() {
        playPause.setText(video != null && video.isPlaying() ? "❚❚" : "▶");
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        View bottom = playerRoot.getChildAt(3);
        bottom.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) {
            if (handler != null && hideControls != null) handler.removeCallbacks(hideControls);
            controls.setVisibility(View.GONE);
            playerRoot.getChildAt(3).setVisibility(View.GONE);
        } else showControls();
    }

    private void scheduleHide() {
        if (handler == null || hideControls == null) return;
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 4000L);
    }

    private void enterPip() {
        try {
            int width = Math.max(1, Math.round(videoAspectRatio * 1000f));
            int height = 1000;
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(width, height)).build());
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("Картинка в картинке").setMessage("Режим недоступен на этом устройстве.").setPositiveButton("Понятно", null).show();
        }
    }

    private void updateVideoStageLayout() {
        if (playerRoot == null || videoStage == null) return;
        int availableWidth = playerRoot.getWidth();
        int availableHeight = playerRoot.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) return;

        float aspect = videoAspectRatio > 0.2f ? videoAspectRatio : 16f / 9f;
        int width = availableWidth;
        int height = Math.round(width / aspect);
        if (height > availableHeight) {
            height = availableHeight;
            width = Math.round(height * aspect);
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoStage.getLayoutParams();
        params.width = Math.max(1, width);
        params.height = Math.max(1, height);
        params.gravity = Gravity.CENTER;
        videoStage.setLayoutParams(params);
        videoStage.requestLayout();
    }

    private void toggleImmersive() {
        immersive = !immersive;
        if (immersive) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        if (playerRoot != null) playerRoot.postDelayed(new Runnable() { @Override public void run() { updateVideoStageLayout(); } }, 180L);
    }

    private void showInfo() {
        new AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Поток: " + sourceUrl + "\n\n" + (details.length() > 0 ? details : "Технические данные отсутствуют"))
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Внешний плеер", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface dialog, int which) { openExternal(); }
            }).show();
    }

    private void openExternal() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl));
            intent.setDataAndType(Uri.parse(sourceUrl), "video/*");
            startActivity(Intent.createChooser(intent, "Открыть исходный поток"));
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("Внешний плеер").setMessage("На устройстве нет приложения, способного открыть этот поток.").setPositiveButton("Понятно", null).show();
        }
    }

    private void stopCurrentPlayback() {
        try { if (video != null) video.stopPlayback(); } catch (Exception ignored) { }
        if (multicastRelay != null) {
            multicastRelay.stop();
            multicastRelay = null;
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView control(String value, boolean enabled) {
        TextView view = text(value, 17, enabled ? Color.WHITE : Color.rgb(92, 102, 119), true);
        view.setGravity(Gravity.CENTER);
        view.setEnabled(enabled);
        view.setBackground(round(PANEL_SOFT, 11, Color.rgb(66, 78, 98), 1));
        return view;
    }

    private TextView icon(String value, int size) {
        TextView view = text(value, size, Color.WHITE, false);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private View gap() {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(7), 1));
        return gap;
    }

    private LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w, h);
        params.setMargins(dp(l), dp(t), dp(r), dp(b));
        return params;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (strokeWidth > 0 && stroke != Color.TRANSPARENT) background.setStroke(dp(strokeWidth), stroke);
        return background;
    }

    private GradientDrawable gradientPanel() {
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.argb(230, 0, 0, 0), Color.argb(0, 0, 0, 0)}
        );
        return background;
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
