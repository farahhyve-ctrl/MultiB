package com.multib.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // UI References
    private LinearLayout browserGrid;
    private EditText urlInput;
    private EditText refreshIntervalInput;
    private SeekBar volumeSeekBar;
    private TextView ipText;
    private TextView statusText;
    private Spinner browserCountSpinner;

    // Browser tracking
    private final List<WebView> webViews = new ArrayList<>();
    private final List<TextView> timerTexts = new ArrayList<>();
    private final List<TextView> refreshTexts = new ArrayList<>();
    private final List<Long> startTimes = new ArrayList<>();
    private final List<Integer> refreshCounts = new ArrayList<>();

    // Handlers
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Handler ipHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // State
    private AudioManager audioManager;
    private int browserCount = 2;
    private int refreshIntervalMinutes = 30;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep screen on while app is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initViews();
        setupListeners();
        fetchIP();
    }

    private void initViews() {
        browserGrid = findViewById(R.id.browser_grid);
        urlInput = findViewById(R.id.url_input);
        refreshIntervalInput = findViewById(R.id.refresh_interval_input);
        volumeSeekBar = findViewById(R.id.volume_seekbar);
        ipText = findViewById(R.id.ip_text);
        statusText = findViewById(R.id.status_text);
        browserCountSpinner = findViewById(R.id.browser_count_spinner);

        // Volume setup
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeSeekBar.setMax(maxVol);
        volumeSeekBar.setProgress(curVol);

        // Browser count spinner (1-8)
        String[] counts = {"1", "2", "3", "4", "5", "6", "7", "8"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, counts);
        browserCountSpinner.setAdapter(adapter);
        browserCountSpinner.setSelection(1); // Default: 2 browsers
    }

    private void setupListeners() {
        // Volume control
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Browser count selector
        browserCountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                browserCount = position + 1;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Buttons
        Button btnStart = findViewById(R.id.btn_start);
        Button btnStop = findViewById(R.id.btn_stop);
        Button btnRefreshAll = findViewById(R.id.btn_refresh_all);

        btnStart.setOnClickListener(v -> startBrowsers());
        btnStop.setOnClickListener(v -> stopBrowsers());
        btnRefreshAll.setOnClickListener(v -> refreshAll());
    }

    private void startBrowsers() {
        String rawUrl = urlInput.getText().toString().trim();
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, "YouTube URL paste කරන්න!", Toast.LENGTH_SHORT).show();
            return;
        }
        String videoId = extractVideoId(rawUrl);
        if (videoId == null) {
            Toast.makeText(this, "Invalid YouTube URL!", Toast.LENGTH_SHORT).show();
            return;
        }

        stopBrowsers(); // Clear old views first

        // Build YouTube embed URL — vq=tiny forces 144p quality
        String embedUrl = "https://www.youtube.com/embed/" + videoId
                + "?autoplay=1&vq=tiny&controls=1&rel=0&playsinline=1";

        isRunning = true;
        statusText.setText("▶ Running — " + browserCount + " browsers");
        statusText.setTextColor(Color.parseColor("#00ff88"));

        // Parse refresh interval
        try {
            String iv = refreshIntervalInput.getText().toString().trim();
            refreshIntervalMinutes = Integer.parseInt(iv);
            if (refreshIntervalMinutes < 1) refreshIntervalMinutes = 1;
        } catch (NumberFormatException e) {
            refreshIntervalMinutes = 30;
        }

        // Create browser views
        for (int i = 0; i < browserCount; i++) {
            addBrowserView(i, embedUrl);
        }

        startTimerUpdates();
        scheduleAutoRefresh();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void addBrowserView(int index, String url) {
        int margin = dpToPx(4);

        // Outer container card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(260));
        cardParams.setMargins(margin, margin, margin, margin);
        card.setLayoutParams(cardParams);
        card.setBackgroundColor(Color.parseColor("#1a1a2e"));

        // ── Header bar ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5));
        header.setBackgroundColor(Color.parseColor("#16213e"));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        header.setLayoutParams(headerParams);

        // Browser label
        TextView label = new TextView(this);
        label.setText("🌐 Browser " + (index + 1));
        label.setTextColor(Color.parseColor("#e94560"));
        label.setTextSize(10f);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Timer
        TextView timerText = new TextView(this);
        timerText.setText("⏱ 00:00");
        timerText.setTextColor(Color.parseColor("#00ff88"));
        timerText.setTextSize(9f);
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timerParams.setMargins(dpToPx(6), 0, dpToPx(6), 0);
        timerText.setLayoutParams(timerParams);

        // Refresh count
        TextView refreshText = new TextView(this);
        refreshText.setText("🔄 0");
        refreshText.setTextColor(Color.parseColor("#ffd700"));
        refreshText.setTextSize(9f);

        header.addView(label);
        header.addView(timerText);
        header.addView(refreshText);

        // ── WebView ──
        WebView webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);  // Auto-play audio
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Mobile Chrome user agent — better YouTube mobile experience
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36");

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        LinearLayout.LayoutParams wvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(wvParams);
        webView.loadUrl(url);

        card.addView(header);
        card.addView(webView);
        browserGrid.addView(card);

        // Track state
        webViews.add(webView);
        timerTexts.add(timerText);
        refreshTexts.add(refreshText);
        startTimes.add(System.currentTimeMillis());
        refreshCounts.add(0);
    }

    private void startTimerUpdates() {
        timerHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                long now = System.currentTimeMillis();
                for (int i = 0; i < timerTexts.size(); i++) {
                    long elapsed = now - startTimes.get(i);
                    long min = (elapsed / 1000) / 60;
                    long sec = (elapsed / 1000) % 60;
                    timerTexts.get(i).setText(String.format(Locale.US, "⏱ %02d:%02d", min, sec));
                }
                timerHandler.postDelayed(this, 1000);
            }
        });
    }

    private void scheduleAutoRefresh() {
        long intervalMs = (long) refreshIntervalMinutes * 60 * 1000L;
        refreshHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                refreshAll();
                refreshHandler.postDelayed(this, intervalMs);
            }
        }, intervalMs);
    }

    private void refreshAll() {
        for (int i = 0; i < webViews.size(); i++) {
            webViews.get(i).reload();
            int count = refreshCounts.get(i) + 1;
            refreshCounts.set(i, count);
            refreshTexts.get(i).setText("🔄 " + count);
            // Reset timer per browser on refresh
            startTimes.set(i, System.currentTimeMillis());
        }
        Toast.makeText(this, "All browsers refreshed!", Toast.LENGTH_SHORT).show();
    }

    private void stopBrowsers() {
        isRunning = false;
        timerHandler.removeCallbacksAndMessages(null);
        refreshHandler.removeCallbacksAndMessages(null);

        for (WebView wv : webViews) {
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.destroy();
        }

        webViews.clear();
        timerTexts.clear();
        refreshTexts.clear();
        startTimes.clear();
        refreshCounts.clear();

        browserGrid.removeAllViews();
        statusText.setText("⏹ Stopped");
        statusText.setTextColor(Color.parseColor("#e94560"));
    }

    /** Detect current IP (VPN IP if NordVPN is active) */
    private void fetchIP() {
        executor.execute(() -> {
            try {
                URL url = new URL("https://api.ipify.org");
                BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
                String ip = reader.readLine();
                reader.close();
                runOnUiThread(() -> ipText.setText("🌐 IP: " + ip));
            } catch (Exception e) {
                runOnUiThread(() -> ipText.setText("🌐 IP: ..."));
                // Retry after 10 seconds
                ipHandler.postDelayed(this::fetchIP, 10000);
            }
        });
    }

    /** Extracts YouTube video ID from various URL formats */
    private String extractVideoId(String url) {
        // youtu.be/VIDEO_ID
        if (url.contains("youtu.be/")) {
            int s = url.indexOf("youtu.be/") + 9;
            String id = url.substring(s);
            if (id.contains("?")) id = id.substring(0, id.indexOf("?"));
            if (id.contains("/")) id = id.substring(0, id.indexOf("/"));
            return id.length() >= 11 ? id.substring(0, 11) : null;
        }
        // youtube.com/watch?v=VIDEO_ID
        if (url.contains("v=")) {
            int s = url.indexOf("v=") + 2;
            String id = url.substring(s);
            if (id.contains("&")) id = id.substring(0, id.indexOf("&"));
            return id.length() >= 11 ? id.substring(0, 11) : null;
        }
        // youtube.com/embed/VIDEO_ID
        if (url.contains("embed/")) {
            int s = url.indexOf("embed/") + 6;
            String id = url.substring(s);
            if (id.contains("?")) id = id.substring(0, id.indexOf("?"));
            return id.length() >= 11 ? id.substring(0, 11) : null;
        }
        // youtube.com/shorts/VIDEO_ID
        if (url.contains("shorts/")) {
            int s = url.indexOf("shorts/") + 7;
            String id = url.substring(s);
            if (id.contains("?")) id = id.substring(0, id.indexOf("?"));
            return id.length() >= 11 ? id.substring(0, 11) : null;
        }
        return null;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBrowsers();
        executor.shutdown();
    }

    @Override
    public void onBackPressed() {
        // Prevent accidental exit
        Toast.makeText(this, "Stop ප්‍රථමයෙන් click කරන්න!", Toast.LENGTH_SHORT).show();
    }
}
