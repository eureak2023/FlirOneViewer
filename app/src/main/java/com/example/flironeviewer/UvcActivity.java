package com.example.flironeviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.flironeviewer.protocol.UvcDescriptors;
import com.example.flironeviewer.ui.ThermalView;
import com.example.flironeviewer.ui.UvcView;
import com.example.flironeviewer.usb.UvcCamera;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UV 카메라(표준 UVC 웹캠) 화면: 실시간 영상, 사진 저장, 회전·반전, 표시 방식, 해상도, 화질 조정. */
public final class UvcActivity extends Activity implements UvcCamera.Callback {

    private static final String ACTION_PERM = "com.example.flironeviewer.UVC_PERMISSION";

    private UsbManager usbManager;
    private volatile UvcCamera camera;
    /** USB 제어 전송은 막힐 수 있으므로 한 스레드에서 차례로 처리. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "UVC-io"));
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private UvcView view;
    private TextView status, btnScale;
    private View dot, empty, topBar, bottomBar, flash;
    private boolean permissionRequested, started, uiHidden;
    private UsbDevice requested;

    private long fpsStart;
    private int fpsCount;
    private volatile float fps;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            UsbDevice d = deviceExtra(i);
            String a = i.getAction();
            if (ACTION_PERM.equals(a)) {
                permissionRequested = false;
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) open(d);
                else setStatus(getString(R.string.uvc_permission), R.color.dot_warn);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UvcCamera cam = camera;
                if (cam != null && d != null && d.getDeviceName().equals(cam.getDevice().getDeviceName())) {
                    disconnect();
                    view.setFrame(null);
                    showEmpty(true);
                    setStatus(getString(R.string.uvc_disconnected), R.color.dot_off);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (started && UvcCamera.isUvc(d)) connect(d);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_uvc);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences("viewer", MODE_PRIVATE);
        view = findViewById(R.id.uvcView);
        status = findViewById(R.id.uvcStatus);
        dot = findViewById(R.id.uvcDot);
        empty = findViewById(R.id.uvcEmpty);
        topBar = findViewById(R.id.uvcTopBar);
        bottomBar = findViewById(R.id.uvcBottomBar);
        flash = findViewById(R.id.uvcFlash);
        btnScale = findViewById(R.id.btnUvcScale);
        setupEdgeToEdge();

        view.setImageRotation(prefs.getInt("uvcRotation", 0));
        view.setMirror(prefs.getBoolean("uvcMirror", false));
        try {
            view.setScaleMode(ThermalView.ScaleMode.valueOf(prefs.getString("uvcScale", "ORIGINAL")));
        } catch (IllegalArgumentException ignored) {
        }
        updateScaleButton();
        setStatus(getString(R.string.uvc_title), R.color.dot_off);

        view.setOnClickListener(v -> setUiHidden(!uiHidden));
        btnScale.setOnClickListener(v -> {
            ThermalView.ScaleMode[] all = ThermalView.ScaleMode.values();
            ThermalView.ScaleMode next = all[(view.getScaleMode().ordinal() + 1) % all.length];
            view.setScaleMode(next);
            prefs.edit().putString("uvcScale", next.name()).apply();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            updateScaleButton();
        });
        findViewById(R.id.btnUvcRotate).setOnClickListener(v -> {
            view.setImageRotation(view.getImageRotation() + 90);
            prefs.edit().putInt("uvcRotation", view.getImageRotation()).apply();
        });
        findViewById(R.id.btnUvcMirror).setOnClickListener(v -> {
            view.setMirror(!view.isMirror());
            prefs.edit().putBoolean("uvcMirror", view.isMirror()).apply();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        });
        findViewById(R.id.btnUvcSnapshot).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            saveSnapshot();
        });
        findViewById(R.id.btnUvcAdjust).setOnClickListener(v -> showAdjustDialog());
        findViewById(R.id.btnUvcResolution).setOnClickListener(v -> showResolutionDialog());

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacy(f);
        requested = deviceExtra(getIntent());
    }

    /** Android 10~12: 내보내기 플래그 개념이 없음. 위조 권한 브로드캐스트가 와도 실제 권한이 없으면 열리지 않습니다. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacy(IntentFilter f) {
        registerReceiver(receiver, f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        UsbDevice d = deviceExtra(intent);
        if (d != null) requested = d;
        if (started && camera == null) connect(requested);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        connect(requested);
    }

    @Override
    protected void onStop() {
        started = false;
        disconnect(); // 백그라운드에서는 카메라를 놓아 다른 앱·전력 소모를 막음
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        disconnect();
        io.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------- 연결

    private void connect(UsbDevice preferred) {
        if (camera != null) return;
        UsbDevice d = null;
        if (preferred != null && usbManager.getDeviceList().containsKey(preferred.getDeviceName())) d = preferred;
        if (d == null) {
            for (UsbDevice x : usbManager.getDeviceList().values()) {
                if (UvcCamera.isUvc(x)) { d = x; break; }
            }
        }
        if (d == null) {
            showEmpty(true);
            setStatus(getString(R.string.uvc_title), R.color.dot_off);
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // UVC 장치는 카메라 권한이 없으면 USB 권한 요청이 창 없이 거부됨 (UsbUserPermissionManager 로그로 확인)
            if (!cameraPermissionAsked) {
                cameraPermissionAsked = true;
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            setStatus(getString(R.string.uvc_camera_permission), R.color.dot_warn);
            return;
        }
        if (usbManager.hasPermission(d)) {
            open(d);
        } else if (!permissionRequested) {
            permissionRequested = true;
            setStatus(getString(R.string.uvc_permission), R.color.dot_warn);
            Intent i = new Intent(ACTION_PERM).setPackage(getPackageName());
            usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        }
    }

    private static final int REQ_CAMERA = 7;
    private boolean cameraPermissionAsked;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (started) connect(requested);
        } else {
            setStatus(getString(R.string.uvc_camera_permission), R.color.hot);
        }
    }

    private void open(UsbDevice d) {
        if (camera != null || !started) return;
        setStatus(getString(R.string.uvc_connecting), R.color.dot_warn);
        final UvcCamera cam = new UvcCamera(usbManager, d);
        camera = cam;
        io.execute(() -> {
            try {
                cam.open();
                UvcDescriptors.FrameSize s = chooseSize(cam);
                fpsStart = SystemClock.elapsedRealtime();
                fpsCount = 0;
                cam.start(s, this);
            } catch (Exception e) {
                cam.close();
                ui.post(() -> {
                    if (camera == cam) camera = null;
                    setStatus(getString(R.string.uvc_error, e.getMessage()), R.color.hot);
                });
            }
        });
    }

    private UvcDescriptors.FrameSize chooseSize(UvcCamera cam) {
        int w = prefs.getInt("uvcWidth", 1280), h = prefs.getInt("uvcHeight", 720); // UVLOOK 기본값과 같음
        UvcDescriptors.FrameSize best = null;
        for (UvcDescriptors.FrameSize s : cam.getSizes()) {
            if (s.width == w && s.height == h && (best == null || (s.mjpeg && !best.mjpeg))) best = s;
        }
        if (best != null) return best;
        // 없으면 1280×720 이하 중 가장 큰 MJPEG, 그것도 없으면 첫 항목
        for (UvcDescriptors.FrameSize s : cam.getSizes()) {
            if (!s.mjpeg || (long) s.width * s.height > 1280L * 720) continue;
            if (best == null || (long) s.width * s.height > (long) best.width * best.height) best = s;
        }
        return best != null ? best : cam.getSizes().get(0);
    }

    private void disconnect() {
        final UvcCamera cam = camera;
        camera = null;
        if (cam != null) io.execute(cam::close);
    }

    // ------------------------------------------------------------- UvcCamera.Callback

    @Override
    public void onFrame(Bitmap frame) {
        if (camera == null) return;
        view.setFrame(frame);
        fpsCount++;
        long now = SystemClock.elapsedRealtime();
        boolean first = fpsCount == 1;
        if (now - fpsStart >= 1000 || first) {
            if (!first) {
                fps = fpsCount * 1000f / (now - fpsStart);
                fpsStart = now;
                fpsCount = 0;
            }
            ui.post(this::updateLiveStatus);
        }
    }

    @Override
    public void onStopped(String reason) {
        ui.post(() -> {
            disconnect();
            setStatus(getString(R.string.uvc_error, reason), R.color.hot);
        });
    }

    private void updateLiveStatus() {
        UvcCamera cam = camera;
        if (cam == null || cam.getCurrentSize() == null) return;
        showEmpty(false);
        UvcDescriptors.FrameSize s = cam.getCurrentSize();
        setStatus(getString(R.string.uvc_status, s.width, s.height, fps), R.color.dot_ok);
    }

    // ------------------------------------------------------------- UI

    private void setStatus(String text, int dotColor) {
        status.setText(text);
        dot.getBackground().mutate().setTint(getColor(dotColor));
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateScaleButton() {
        int text, icon;
        switch (view.getScaleMode()) {
            case STRETCH: text = R.string.scale_stretch; icon = R.drawable.ic_scale_stretch; break;
            case FILL: text = R.string.scale_fill; icon = R.drawable.ic_scale_fill; break;
            default: text = R.string.scale_original; icon = R.drawable.ic_scale_original; break;
        }
        btnScale.setText(text);
        btnScale.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** 영상을 탭하면 조작 UI·시스템 바를 숨겨 영상만 크게 봅니다. */
    private void setUiHidden(boolean hide) {
        uiHidden = hide;
        for (View v : new View[]{topBar, bottomBar}) {
            v.animate().cancel();
            if (hide) {
                v.animate().alpha(0f).setDuration(180).withEndAction(() -> v.setVisibility(View.INVISIBLE)).start();
            } else {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(180).withEndAction(null).start();
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (hide) {
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void setupEdgeToEdge() {
        final View root = findViewById(R.id.uvcRoot);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);
        final int padTop = (int) (12 * getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = in.left; t = in.top; r = in.right; b = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            topBar.setPadding(pad + l, padTop + t, pad + r, 0);
            bottomBar.setPadding(pad + l, 0, pad + r, pad + b);
            return insets;
        });
    }

    private void showResolutionDialog() {
        final UvcCamera cam = camera;
        if (cam == null || cam.getCurrentSize() == null) return;
        final List<UvcDescriptors.FrameSize> sizes = cam.getSizes();
        String[] labels = new String[sizes.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            UvcDescriptors.FrameSize s = sizes.get(i);
            labels[i] = String.format(Locale.US, "%d × %d  ·  %s  ·  %.0f fps", s.width, s.height,
                    s.mjpeg ? "MJPEG" : "YUYV", s.fps());
            if (s == cam.getCurrentSize()) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.uvc_resolution)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    UvcDescriptors.FrameSize s = sizes.get(which);
                    prefs.edit().putInt("uvcWidth", s.width).putInt("uvcHeight", s.height).apply();
                    setStatus(getString(R.string.uvc_connecting), R.color.dot_warn);
                    io.execute(() -> {
                        try {
                            fpsStart = SystemClock.elapsedRealtime();
                            fpsCount = 0;
                            cam.start(s, this);
                        } catch (Exception e) {
                            onStopped(e.getMessage());
                        }
                    });
                })
                .show();
    }

    /** 처리 장치 제어(밝기·대비·채도·선명도). 범위는 카메라에서 읽어 옵니다. */
    private void showAdjustDialog() {
        final UvcCamera cam = camera;
        if (cam == null) return;
        final int[] sels = {UvcCamera.PU_BRIGHTNESS, UvcCamera.PU_CONTRAST, UvcCamera.PU_SATURATION, UvcCamera.PU_SHARPNESS};
        final int[] names = {R.string.uvc_brightness, R.string.uvc_contrast, R.string.uvc_saturation, R.string.uvc_sharpness};
        io.execute(() -> {
            final int[][] ranges = new int[sels.length][];
            for (int i = 0; i < sels.length; i++) ranges[i] = cam.getRange(sels[i]);
            ui.post(() -> buildAdjustDialog(cam, sels, names, ranges));
        });
    }

    private void buildAdjustDialog(UvcCamera cam, int[] sels, int[] names, int[][] ranges) {
        if (isFinishing() || camera != cam) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final SeekBar[] bars = new SeekBar[sels.length];
        boolean any = false;
        for (int i = 0; i < sels.length; i++) {
            final int[] r = ranges[i];
            if (r == null) continue;
            any = true;
            final int sel = sels[i];
            final TextView label = new TextView(this);
            final String name = getString(names[i]);
            label.setText(String.format(Locale.US, "%s  %d", name, r[3]));
            SeekBar bar = new SeekBar(this);
            bar.setMax(r[1] - r[0]);
            bar.setProgress(r[3] - r[0]);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    int v = r[0] + p;
                    label.setText(String.format(Locale.US, "%s  %d", name, v));
                    if (user) io.execute(() -> cam.set(sel, v));
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            bars[i] = bar;
            box.addView(label);
            box.addView(bar);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.uvc_adjust).setPositiveButton(R.string.ok, null);
        if (!any) {
            b.setMessage(R.string.uvc_no_controls).show();
            return;
        }
        b.setView(box)
                .setNeutralButton(R.string.uvc_reset, (d, w) -> {
                    for (int i = 0; i < sels.length; i++) {
                        if (ranges[i] == null) continue;
                        final int sel = sels[i], def = ranges[i][2];
                        io.execute(() -> cam.set(sel, def));
                    }
                })
                .show();
    }

    private void saveSnapshot() {
        final Bitmap bmp = view.capture();
        if (bmp == null) return;
        flash.animate().cancel();
        flash.setAlpha(0.85f);
        flash.animate().alpha(0f).setDuration(320);
        final String name = "UV_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FlirOneViewer");
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("MediaStore insert failed");
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null || !bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                        throw new IllegalStateException("JPEG encoding failed");
                    }
                }
                ui.post(() -> Toast.makeText(this, getString(R.string.snapshot_saved, name), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, getString(R.string.snapshot_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }, "UVC-save").start();
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
