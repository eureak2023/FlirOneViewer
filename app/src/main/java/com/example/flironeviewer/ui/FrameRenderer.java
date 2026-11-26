package com.example.flironeviewer.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.example.flironeviewer.protocol.RawFrame;
import com.example.flironeviewer.protocol.ThermalDecoder;
import com.example.flironeviewer.protocol.ThermalImage;
import com.example.flironeviewer.thermal.Fusion;
import com.example.flironeviewer.thermal.Enhance;
import com.example.flironeviewer.thermal.TemporalDenoiser;
import com.example.flironeviewer.thermal.Palette;
import com.example.flironeviewer.thermal.TemperatureConverter;

import org.json.JSONArray;
import org.json.JSONObject;

/** RawFrame → 화면 표시용 RenderedFrame 변환 (작업 스레드에서 호출). */
public final class FrameRenderer {

    public static final class RenderedFrame {
        public final ThermalImage image;
        public final Bitmap thermal;
        public final Bitmap visual;          // 열화상 시야에 맞춰 잘라낸 실화상 (필요 없으면 null)
        public final int rangeLowRaw, rangeHighRaw;
        public final JSONObject status;      // 파싱 실패 시 null
        public final String statusRaw;
        public final int sequence;

        RenderedFrame(ThermalImage image, Bitmap thermal, Bitmap visual, int lo, int hi,
                      JSONObject status, String statusRaw, int sequence) {
            this.image = image;
            this.thermal = thermal;
            this.visual = visual;
            this.rangeLowRaw = lo;
            this.rangeHighRaw = hi;
            this.status = status;
            this.statusRaw = statusRaw;
            this.sequence = sequence;
        }
    }

    private final TemperatureConverter converter;
    private volatile Palette palette = Palette.IRON;
    private volatile boolean rangeLocked;
    private double lo = Double.NaN, hi = Double.NaN;

    public FrameRenderer(TemperatureConverter converter) {
        this.converter = converter;
    }

    public TemperatureConverter getConverter() { return converter; }
    public Palette getPalette() { return palette; }
    public void setPalette(Palette p) { palette = p; }
    public boolean isRangeLocked() { return rangeLocked; }
    public void setRangeLocked(boolean locked) { rangeLocked = locked; }

    private volatile Fusion fusion = Fusion.deviceDefault();
    private volatile float msxStrength = 1.0f;

    public Fusion getFusion() { return fusion; }
    public void setFusion(Fusion f) { fusion = f; }
    public float getMsxStrength() { return msxStrength; }
    public void setMsxStrength(float s) { msxStrength = s; }

    /** MSX 출력 배율 (열화상 80x60 → 320x240). */
    private static final int MSX_SCALE = 4;

    // ---- 화질 향상
    private final TemporalDenoiser denoiser = new TemporalDenoiser();
    private volatile boolean denoise = true, enhance = true;
    /** 가이드 필터 출력 배율(80x60 → 480x360), 창 반경(출력 픽셀), 정규화 eps(휘도²). */
    private static final int ENHANCE_SCALE = 6, GUIDE_RADIUS = 8;
    private static final float GUIDE_EPS = 100f;

    public boolean isDenoise() { return denoise; }
    public void setDenoise(boolean on) { denoise = on; if (!on) denoiser.reset(); }
    public boolean isEnhance() { return enhance; }
    public void setEnhance(boolean on) { enhance = on; }

    public RenderedFrame render(RawFrame f, ThermalView.Mode mode) {
        JSONObject status = parseJson(f.statusJson);
        boolean bigEndian = status != null && findBoolean(status, "bigEndianThermal");
        ThermalImage img = ThermalDecoder.decode(f.thermal, bigEndian);
        if (img == null) return null;
        if (img.spotMeanK100 > 0) {
            // Lepton 자체 라디오메트릭 스팟미터로 센서 드리프트 보정
            converter.updateFromSpotmeter(img.spotRawMean(), img.spotMeanK100 / 100.0);
        }

        // 자동 범위: 최소/최대를 지수 이동 평균으로 부드럽게 (깜빡임 방지)
        if (!rangeLocked || Double.isNaN(lo)) {
            double tLo = img.min, tHi = Math.max(img.max, img.min + 8);
            if (Double.isNaN(lo)) { lo = tLo; hi = tHi; }
            else { lo += (tLo - lo) * 0.35; hi += (tHi - hi) * 0.35; }
        }
        int loI = (int) Math.floor(lo), hiI = Math.max(loI + 1, (int) Math.ceil(hi));

        int[] colors = palette.colors;
        int tw = img.width, th = img.height;

        // ① 시간축 노이즈 제거 (표시용. 측정은 img.raw 원본 사용)
        float[] disp;
        if (denoise) {
            disp = denoiser.apply(img.raw, tw, th);
        } else {
            disp = new float[img.raw.length];
            for (int i = 0; i < disp.length; i++) disp[i] = img.raw[i];
        }

        boolean wantVisual = mode != ThermalView.Mode.THERMAL || enhance;
        Bitmap visual = null;
        if (wantVisual && f.jpeg != null && f.jpeg.length > 0) {
            visual = decodeAlignedVisual(f.jpeg);
        }

        Bitmap thermal;
        boolean msx = mode == ThermalView.Mode.MSX;
        if (visual != null && (enhance || msx) && mode != ThermalView.Mode.VISUAL) {
            int scale = enhance ? ENHANCE_SCALE : MSX_SCALE;
            int ow = tw * scale, oh = th * scale;
            Bitmap vs = Bitmap.createScaledBitmap(visual, ow, oh, true);
            int[] vp = new int[ow * oh];
            vs.getPixels(vp, 0, ow, 0, 0, ow, oh);
            if (vs != visual) vs.recycle();
            float[] guide = Enhance.luma(vp);
            float[] up = Enhance.upsampleBilinear(disp, tw, th, ow, oh);
            // ② 실화상 기반 가이드 필터: 열 경계를 실화상 경계에 맞춰 선명하게
            float[] v = enhance ? Enhance.guidedFilter(guide, up, ow, oh, GUIDE_RADIUS, GUIDE_EPS) : up;
            int[] out = Enhance.colorize(v, loI, hiI, colors);
            if (msx) Enhance.addEdges(out, guide, ow, oh, msxStrength, Math.max(3, scale * 2 / 3));
            thermal = Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888);
        } else {
            int[] px = Enhance.colorize(disp, loI, hiI, colors);
            thermal = Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888);
        }
        if (mode == ThermalView.Mode.THERMAL) visual = null; // 열화상 모드는 실화상을 그리지 않음
        return new RenderedFrame(img, thermal, visual, loI, hiI, status, f.statusJson, f.sequence);
    }

    /** JPEG 을 적당한 크기로 디코딩하고 열화상 시야에 해당하는 영역만 잘라냅니다(정합). */
    private Bitmap decodeAlignedVisual(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int ss = 1;
        while (o.outWidth / (ss * 2) >= 640 && o.outHeight / (ss * 2) >= 480) ss *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = ss;
        Bitmap full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (full == null) return null;
        int[] c = fusion.visualCrop(full.getWidth(), full.getHeight());
        if (c[2] <= 0 || c[3] <= 0) return full;
        Bitmap crop = Bitmap.createBitmap(full, c[0], c[1], c[2], c[3]);
        if (crop != full) full.recycle();
        return crop;
    }

    private static JSONObject parseJson(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 최상위 또는 중첩 객체에서 키를 찾습니다. */
    public static Object find(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has(key)) return o.opt(key);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                Object r = find(o.opt(it.next()), key);
                if (r != null) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object r = find(a.opt(i), key);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean findBoolean(JSONObject o, String key) {
        Object v = find(o, key);
        return v instanceof Boolean ? (Boolean) v : "true".equals(String.valueOf(v));
    }
}
