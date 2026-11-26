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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.flironeviewer.protocol.RawFrame;
import com.example.flironeviewer.thermal.TemperatureConverter;
import com.example.flironeviewer.ui.FrameRenderer;
import com.example.flironeviewer.ui.ThermalView;
import com.example.flironeviewer.usb.FlirOneCamera;

import org.json.JSONObject;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements FlirOneCamera.Callback {

    private static final String ACTION_USB_PERMISSION = "com.example.flironeviewer.USB_PERMISSION";

    private UsbManager usbManager;
    private FlirOneCamera camera;
    private final TemperatureConverter converter = new TemperatureConverter();
    private final FrameRenderer renderer = new FrameRenderer(converter);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "FlirOne-render"));
    private final AtomicBoolean rendering = new AtomicBoolean(false);
    private volatile boolean ffcActive;
    private volatile long corruptFrames;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ThermalView thermalView;
    private TextView statusText;
    private Button btnMode, btnLock, btnPalette;

    private long fpsWindowStart;
    private int fpsCount;
    private float fps;
    private volatile String lastStatusJson = "";
    private boolean permissionRequested;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = usbDeviceExtra(intent);
            if (ACTION_USB_PERMISSION.equals(action)) {
                permissionRequested = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && FlirOneCamera.isFlirOne(device)) {
                    openCamera(device);
                } else {
                    setStatus(getString(R.string.status_permission_denied));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (camera != null && device != null && device.getDeviceName().equals(camera.getDevice().getDeviceName())) {
                    closeCamera();
                    setStatus(getString(R.string.status_disconnected));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (FlirOneCamera.isFlirOne(device)) connectTo(device);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        FlirOneCamera.debugPlanFile = new java.io.File(getExternalFilesDir(null), "plan.txt");
        FlirOneCamera.dumpDir = getExternalFilesDir(null);
        thermalView = findViewById(R.id.thermalView);
        statusText = findViewById(R.id.statusText);
        btnMode = findViewById(R.id.btnMode);
        btnLock = findViewById(R.id.btnLock);
        btnPalette = findViewById(R.id.btnPalette);

        ((android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE))
                .registerDisplayListener(displayListener, ui);
        loadSavedCalibration();
        {
            android.content.SharedPreferences p = getSharedPreferences("viewer", MODE_PRIVATE);
            renderer.setMsxStrength(p.getFloat("msxStrength", 1.0f));
            renderer.setDenoise(p.getBoolean("denoise", true));
            renderer.setEnhance(p.getBoolean("enhance", true));
            thermalView.setLensFacingScreen(p.getBoolean("lensFacingScreen", false));
            renderer.getFusion().distanceM = p.getFloat("fusionDistance", 3f);
        }
        thermalView.setConverter(converter);
        thermalView.setPalette(renderer.getPalette());
        updateButtons();

        btnPalette.setOnClickListener(v -> {
            renderer.setPalette(renderer.getPalette().next());
            thermalView.setPalette(renderer.getPalette());
            updateButtons();
        });
        btnMode.setOnClickListener(v -> {
            ThermalView.Mode[] modes = ThermalView.Mode.values();
            thermalView.setMode(modes[(thermalView.getMode().ordinal() + 1) % modes.length]);
            updateButtons();
        });
        final android.content.SharedPreferences prefs = getSharedPreferences("viewer", MODE_PRIVATE);
        thermalView.setImageRotation(prefs.getInt("rotation", 0));
        findViewById(R.id.btnRotate).setOnClickListener(v -> {
            thermalView.setImageRotation(thermalView.getImageRotation() + 90);
            prefs.edit().putInt("rotation", thermalView.getImageRotation()).apply();
        });
        btnLock.setOnClickListener(v -> {
            renderer.setRangeLocked(!renderer.isRangeLocked());
            updateButtons();
        });
        findViewById(R.id.btnEmissivity).setOnClickListener(v -> showEmissivityDialog());
        btnMode.setOnLongClickListener(v -> {
            showMsxDialog();
            return true;
        });
        findViewById(R.id.btnSnapshot).setOnClickListener(v -> saveSnapshot());
        statusText.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.status_json_title)
                    .setMessage(prettyJson(lastStatusJson, getString(R.string.none)))
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return true;
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiverLegacy(filter);
        }

        handleIntent(getIntent());
    }

    /**
     * Android 10~12: 동적 리시버에 내보내기 플래그 개념이 없습니다. 위조된 USB_PERMISSION 브로드캐스트가 와도
     * 실제 권한이 없으면 openDevice() 가 실패하므로 안전합니다.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceiverLegacy(IntentFilter filter) {
        registerReceiver(usbReceiver, filter);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (camera == null) findAndConnect();
    }

    /** 세로 ↔ 거꾸로 세로(180°)는 화면 크기가 같아 구성 변경 이벤트가 오지 않으므로 디스플레이 회전을 직접 감지합니다. */
    private final android.hardware.display.DisplayManager.DisplayListener displayListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) { }
                @Override public void onDisplayChanged(int id) { thermalView.invalidate(); }
            };

    @Override
    protected void onDestroy() {
        ((android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE))
                .unregisterDisplayListener(displayListener);
        unregisterReceiver(usbReceiver);
        closeCamera();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------- 연결 관리

    private void handleIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice d = usbDeviceExtra(intent);
            if (FlirOneCamera.isFlirOne(d)) connectTo(d);
        }
    }

    private void findAndConnect() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (FlirOneCamera.isFlirOne(d)) {
                connectTo(d);
                return;
            }
        }
        setStatus(getString(R.string.status_no_device));
    }

    private void connectTo(UsbDevice device) {
        if (camera != null) return;
        if (usbManager.hasPermission(device)) {
            openCamera(device);
        } else if (!permissionRequested) {
            permissionRequested = true;
            setStatus(getString(R.string.status_permission));
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(device, pi);
        }
    }

    private void openCamera(UsbDevice device) {
        if (camera != null) return;
        setStatus(getString(R.string.status_connecting));
        FlirOneCamera cam = new FlirOneCamera(usbManager, device, this);
        try {
            cam.start();
            camera = cam;
            fpsWindowStart = SystemClock.elapsedRealtime();
            fpsCount = 0;
            setStatus(getString(R.string.status_waiting_frames));
        } catch (Exception e) {
            setStatus(getString(R.string.status_connect_failed, e.getMessage()));
        }
    }

    private void closeCamera() {
        FlirOneCamera cam = camera;
        camera = null;
        if (cam != null) new Thread(cam::stop, "FlirOne-close").start();
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice usbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    // ------------------------------------------------------------- FlirOneCamera.Callback (USB 스레드)

    @Override
    public void onFrame(RawFrame frame) {
        lastStatusJson = frame.statusJson;
        // 셔터 보정(FFC) 중 프레임은 셔터와 장면이 섞여 화면이 번쩍이므로 표시·보정에서 제외(실기기 로그로 확인)
        String st = frame.statusJson;
        boolean ffc = st != null && (st.contains("\"shutterState\":\"FFC\"") || st.contains("FFC_PROGRESS"));
        if (ffc != ffcActive) {
            ffcActive = ffc;
            android.util.Log.i("FlirOneCamera", ffc ? "셔터 보정(FFC) 시작 → 화면 유지" : "셔터 보정(FFC) 종료");
            ui.post(() -> { if (ffcActive) statusText.setText(R.string.status_ffc); });
        }
        if (ffc) return;
        if (com.example.flironeviewer.protocol.ThermalDecoder.countCorruptRows(frame.thermal) > 0) {
            corruptFrames++;
            return; // 손상된 행이 있는 프레임은 버림
        }
        if (!rendering.compareAndSet(false, true)) return; // 렌더링이 밀리면 프레임 드롭
        final ThermalView.Mode mode = thermalView.getMode();
        worker.execute(() -> {
            try {
                FrameRenderer.RenderedFrame rf = renderer.render(frame, mode);
                if (rf != null) {
                    thermalView.setFrame(rf);
                    ui.post(() -> onRendered(rf));
                }
            } catch (Exception ignored) {
                // 손상된 프레임은 건너뜀
            } finally {
                rendering.set(false);
            }
        });
    }

    private volatile int batteryPercent = -1;

    @Override
    public void onConfigData(byte[] data, int length) {
        // 카메라 config 프로토콜 JSON. 예: {"type":"batteryVoltageUpdate","data":{"voltage":3.82,"percentage":60}}
        if (length <= 0 || data[0] != '{') return;
        try {
            int end = length;
            while (end > 0 && data[end - 1] == 0) end--;
            JSONObject o = new JSONObject(new String(data, 0, end, java.nio.charset.StandardCharsets.UTF_8));
            JSONObject d = o.optJSONObject("data");
            if ("batteryVoltageUpdate".equals(o.optString("type")) && d != null && d.has("percentage")) {
                batteryPercent = d.optInt("percentage", -1);
            }
        } catch (Exception ignored) {
        }
    }

    private void loadSavedCalibration() {
        String s = getSharedPreferences("viewer", MODE_PRIVATE).getString("calib", null);
        if (s == null) return;
        try {
            String[] p = s.split(",");
            converter.setCalibration(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Double.parseDouble(p[4]), "저장된 calib.rsc (" + p[5] + ")");
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCameraFiles(java.util.Map<String, byte[]> files) {
        byte[] rsc = files.get("CameraFiles/system/calib.rsc");
        if (rsc == null) return;
        com.example.flironeviewer.protocol.CalibRsc c = com.example.flironeviewer.protocol.CalibRsc.parse(
                new String(rsc, java.nio.charset.StandardCharsets.ISO_8859_1));
        if (c == null) return;
        converter.setCalibration(c.r, c.b, c.f, c.j0, c.j1, "카메라 calib.rsc");
        if (c.fusion != null) {
            double[] fu = c.fusion;
            renderer.setFusion(new com.example.flironeviewer.thermal.Fusion(fu[0], fu[1], fu[2], fu[3], fu[4],
                    renderer.getFusion().distanceM));
            android.util.Log.i("FlirOneCamera", String.format(Locale.US,
                    "정합 파라미터 적용 magn=%.4f C0X=%.3f C1X=%.3f C0Y=%.3f C1Y=%.3f", fu[0], fu[1], fu[2], fu[3], fu[4]));
        }
        // 다음 연결에서 파일을 못 받을 때를 대비해 저장
        getSharedPreferences("viewer", MODE_PRIVATE).edit()
                .putString("calib", c.r + "," + c.b + "," + c.f + "," + c.j0 + "," + c.j1 + "," + c.id)
                .apply();
        android.util.Log.i("FlirOneCamera", String.format(Locale.US,
                "카메라 보정값 적용 [%s] R=%.3f B=%.1f F=%.2f J0=%.3f J1=%.6f", c.id, c.r, c.b, c.f, c.j0, c.j1));
    }

    @Override
    public void onStopped(String reason) {
        ui.post(() -> {
            camera = null;
            setStatus(getString(R.string.status_disconnected) + (reason != null ? " (" + reason + ")" : ""));
        });
    }

    // ------------------------------------------------------------- UI

    private void onRendered(FrameRenderer.RenderedFrame rf) {
        fpsCount++;
        long now = SystemClock.elapsedRealtime();
        if (now - fpsWindowStart >= 1000) {
            fps = fpsCount * 1000f / (now - fpsWindowStart);
            fpsCount = 0;
            fpsWindowStart = now;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%dx%d  %.1f fps  ", rf.image.width, rf.image.height, fps));
        sb.append(String.format(Locale.US, "ε=%.2f  ", converter.getEmissivity()));
        sb.append(getString(Double.isNaN(converter.getSignalOffset()) ? R.string.st_calibrating : R.string.st_calibrated)).append("  ");
        if (batteryPercent >= 0) sb.append(getString(R.string.st_battery, batteryPercent)).append("  ");
        if (rf.status != null) {
            appendStatus(sb, rf.status, "shutterState", getString(R.string.st_shutter));
            appendStatus(sb, rf.status, "ffcState", "FFC");
            Object st = FrameRenderer.find(rf.status, "shutterTemperature");
            if (st instanceof Number) {
                double k = ((Number) st).doubleValue();
                double c = k > 200 ? k - 273.15 : k; // 켈빈이면 섭씨로
                sb.append(getString(R.string.st_shutter_temp, c)).append("  ");
            }
        }
        if (corruptFrames > 0) sb.append(getString(R.string.st_corrupt, (int) corruptFrames)).append("  ");
        FlirOneCamera cam = camera;
        if (cam != null && cam.getAssembler().getCrcMismatches() > 0) {
            sb.append(getString(R.string.st_crc, (int) cam.getAssembler().getCrcMismatches()));
        }
        statusText.setText(sb.toString().trim());
    }

    private static void appendStatus(StringBuilder sb, JSONObject s, String key, String label) {
        Object v = FrameRenderer.find(s, key);
        if (v != null) sb.append(label).append(' ').append(v).append("  ");
    }

    private void updateButtons() {
        btnPalette.setText(renderer.getPalette().label);
        int modeRes;
        switch (thermalView.getMode()) {
            case MSX: modeRes = R.string.mode_msx; break;
            case VISUAL: modeRes = R.string.mode_visual; break;
            case BLEND: modeRes = R.string.mode_blend; break;
            default: modeRes = R.string.mode_thermal;
        }
        btnMode.setText(modeRes);
        btnLock.setText(renderer.isRangeLocked() ? R.string.btn_unlock : R.string.btn_lock);
    }

    private void setStatus(String s) {
        statusText.setText(s);
    }

    private void showEmissivityDialog() {
        final TextView label = new TextView(this);
        final SeekBar bar = new SeekBar(this);
        bar.setMax(99); // 0.01 ~ 1.00
        bar.setProgress((int) Math.round(converter.getEmissivity() * 100) - 1);
        label.setText(String.format(Locale.US, "ε = %.2f", converter.getEmissivity()));
        label.setTextSize(18);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                label.setText(String.format(Locale.US, "ε = %.2f", (p + 1) / 100.0));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(label);
        box.addView(bar);
        new AlertDialog.Builder(this)
                .setTitle(R.string.emissivity_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) ->
                        worker.execute(() -> converter.setParameters((bar.getProgress() + 1) / 100.0, converter.getReflectedC())))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 모드 버튼 길게 누르기: MSX 윤곽 강도와 정합 거리(시차 보정) 조정. */
    private void showMsxDialog() {
        final android.content.SharedPreferences prefs = getSharedPreferences("viewer", MODE_PRIVATE);
        final TextView strengthLabel = new TextView(this);
        final SeekBar strength = new SeekBar(this);
        strength.setMax(300); // 0~3.0
        strength.setProgress(Math.round(renderer.getMsxStrength() * 100));
        final TextView distLabel = new TextView(this);
        final SeekBar dist = new SeekBar(this);
        dist.setMax(49); // 0.1~5.0 m
        dist.setProgress((int) Math.round(renderer.getFusion().distanceM * 10) - 1);
        Runnable refresh = () -> {
            strengthLabel.setText(getString(R.string.msx_strength, strength.getProgress()));
            distLabel.setText(getString(R.string.msx_distance, (dist.getProgress() + 1) / 10.0));
        };
        refresh.run();
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                refresh.run();
                renderer.setMsxStrength(strength.getProgress() / 100f);
                renderer.getFusion().distanceM = (dist.getProgress() + 1) / 10.0;
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        strength.setOnSeekBarChangeListener(l);
        dist.setOnSeekBarChangeListener(l);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.CheckBox denoiseBox = new android.widget.CheckBox(this);
        denoiseBox.setText(R.string.opt_denoise);
        denoiseBox.setChecked(renderer.isDenoise());
        denoiseBox.setOnCheckedChangeListener((b, c) -> renderer.setDenoise(c));
        final android.widget.CheckBox enhanceBox = new android.widget.CheckBox(this);
        enhanceBox.setText(R.string.opt_enhance);
        enhanceBox.setChecked(renderer.isEnhance());
        enhanceBox.setOnCheckedChangeListener((b, c) -> renderer.setEnhance(c));
        final android.widget.CheckBox lensBox = new android.widget.CheckBox(this);
        lensBox.setText(R.string.opt_lens_front);
        lensBox.setChecked(thermalView.isLensFacingScreen());
        lensBox.setOnCheckedChangeListener((b, c) -> thermalView.setLensFacingScreen(c));
        box.addView(denoiseBox);
        box.addView(enhanceBox);
        box.addView(lensBox);
        box.addView(strengthLabel);
        box.addView(strength);
        box.addView(distLabel);
        box.addView(dist);
        new AlertDialog.Builder(this)
                .setTitle(R.string.msx_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> prefs.edit()
                        .putBoolean("denoise", renderer.isDenoise())
                        .putBoolean("enhance", renderer.isEnhance())
                        .putBoolean("lensFacingScreen", thermalView.isLensFacingScreen())
                        .putFloat("msxStrength", renderer.getMsxStrength())
                        .putFloat("fusionDistance", (float) renderer.getFusion().distanceM)
                        .apply())
                .show();
    }

    private void saveSnapshot() {
        if (thermalView.getFrame() == null) return;
        Bitmap bmp = thermalView.capture();
        if (bmp == null) return;
        String name = "FLIRONE_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        worker.execute(() -> {
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
        });
    }

    private static String prettyJson(String s, String none) {
        if (s == null || s.isEmpty()) return none;
        try {
            return new JSONObject(s).toString(2);
        } catch (Exception e) {
            return s;
        }
    }
}
