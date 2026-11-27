package com.example.flironeviewer.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.example.flironeviewer.protocol.ThermalImage;
import com.example.flironeviewer.thermal.Palette;
import com.example.flironeviewer.thermal.TemperatureConverter;

import java.util.Locale;

/** 열화상/실화상/혼합 영상과 스팟·최고·최저 온도, 컬러바를 그리는 뷰. */
public final class ThermalView extends View {

    /** MSX = 열화상 + 실화상 윤곽선 (FLIR 앱의 기본 모드와 같은 방식). */
    public enum Mode { MSX, THERMAL, VISUAL, BLEND }

    private FrameRenderer.RenderedFrame frame;
    private TemperatureConverter converter;
    private Palette palette = Palette.IRON;
    private Mode mode = Mode.MSX;
    private int rotation; // 0, 90, 180, 270

    /** 스팟 위치(열화상 좌표 비율 0..1). */
    private float spotU = 0.5f, spotV = 0.5f;

    private final Matrix imageMatrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final Matrix tmp = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint blendPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;
    private final float[] pt = new float[2];

    public ThermalView(Context c) { this(c, null); }

    public ThermalView(Context c, AttributeSet a) {
        super(c, a);
        dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        blendPaint.setAlpha(150);
        text.setColor(Color.WHITE);
        text.setTextSize(15 * dp);
        text.setShadowLayer(3 * dp, 0, 0, Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * dp);
        stroke.setColor(Color.WHITE);
        stroke.setShadowLayer(2 * dp, 0, 0, Color.BLACK);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Runnable r = onDoubleTap;
                if (r == null) return false;
                r.run();
                return true;
            }
        });
    }

    private final GestureDetector gestures;
    private Runnable onDoubleTap;

    /** 영상을 두 번 탭했을 때 실행할 동작 (전체 화면 전환). */
    public void setOnDoubleTapListener(Runnable r) { onDoubleTap = r; }

    /**
     * 전체 화면 표시 방식.
     * ORIGINAL = 원본 비율 유지(남는 곳은 검은 여백), STRETCH = 화면에 맞춰 늘림(비율 무시),
     * FILL = 비율 유지한 채 화면을 가득 채움(넘치는 가장자리는 잘림).
     */
    public enum ScaleMode { ORIGINAL, STRETCH, FILL }

    private ScaleMode scaleMode = ScaleMode.ORIGINAL;

    public ScaleMode getScaleMode() { return scaleMode; }

    public void setScaleMode(ScaleMode m) {
        scaleMode = m;
        invalidate();
    }

    /** 전체 화면: 위아래 오버레이를 피하지 않고 뷰 전체에 영상을 표시 방식대로 맞춥니다. */
    private boolean fullscreen;

    public void setFullscreen(boolean v) {
        if (fullscreen == v) return;
        fullscreen = v;
        invalidate();
    }

    public void setConverter(TemperatureConverter c) { converter = c; invalidate(); }
    public void setPalette(Palette p) { palette = p; invalidate(); }
    public Mode getMode() { return mode; }
    public void setMode(Mode m) { mode = m; invalidate(); }
    public int getImageRotation() { return rotation; }
    public void setImageRotation(int deg) { rotation = ((deg % 360) + 360) % 360; invalidate(); }

    public void setFrame(FrameRenderer.RenderedFrame f) {
        frame = f;
        postInvalidateOnAnimation();
    }

    public FrameRenderer.RenderedFrame getFrame() { return frame; }

    /** 현재 화면을 비트맵으로 (스냅샷용). */
    public Bitmap capture() {
        FrameRenderer.RenderedFrame f = frame;
        if (f == null || getWidth() <= 0 || getHeight() <= 0) return null;
        // 영상이 그려지는 영역만 잘라 저장 (위아래/좌우 검은 여백 제외)
        buildMatrix(f.image.width, f.image.height);
        RectF r = new RectF(0, 0, f.image.width, f.image.height);
        imageMatrix.mapRect(r);
        int l = Math.max(0, Math.round(r.left)), t = Math.max(0, Math.round(r.top));
        int rr = Math.min(getWidth(), Math.round(r.right)), bb = Math.min(getHeight(), Math.round(r.bottom));
        if (rr - l < 2 || bb - t < 2) return null;
        Bitmap b = Bitmap.createBitmap(rr - l, bb - t, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        c.translate(-l, -t);
        captureBounds = new RectF(l, t, rr, bb);
        try {
            draw(c);
        } finally {
            captureBounds = null;
        }
        return b;
    }

    /** 저장용 그리기 중이면 영상 영역(뷰 좌표). 컬러바·라벨을 이 안에 배치합니다. */
    private RectF captureBounds;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled = gestures.onTouchEvent(e);
        FrameRenderer.RenderedFrame f = frame;
        if (f == null) return handled || onDoubleTap != null;
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            buildMatrix(f.image.width, f.image.height);
            if (imageMatrix.invert(inverse)) {
                pt[0] = e.getX();
                pt[1] = e.getY();
                inverse.mapPoints(pt);
                spotU = clamp01(pt[0] / f.image.width);
                spotV = clamp01(pt[1] / f.image.height);
                invalidate();
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        FrameRenderer.RenderedFrame f = frame;
        if (f == null) return;
        ThermalImage img = f.image;
        int w = img.width, h = img.height;
        buildMatrix(w, h);

        // 모든 비트맵은 열화상 좌표(w x h)로 늘려 그립니다. 실화상은 렌더러에서 이미 열화상 시야로 잘라 정합됨.
        boolean drawVisual = (mode == Mode.VISUAL || mode == Mode.BLEND) && f.visual != null;
        if (drawVisual) {
            tmp.set(imageMatrix);
            tmp.preScale((float) w / f.visual.getWidth(), (float) h / f.visual.getHeight());
            canvas.drawBitmap(f.visual, tmp, bitmapPaint);
        }
        if (!drawVisual || mode == Mode.BLEND) {
            tmp.set(imageMatrix);
            tmp.preScale((float) w / f.thermal.getWidth(), (float) h / f.thermal.getHeight());
            canvas.drawBitmap(f.thermal, tmp, drawVisual ? blendPaint : bitmapPaint);
        }

        if (converter == null) return;

        drawColorBar(canvas, f);

        // 최고/최저 온도 마커
        int maxX = img.maxIndex % w, maxY = img.maxIndex / w;
        int minX = img.minIndex % w, minY = img.minIndex / w;
        drawMarker(canvas, maxX + 0.5f, maxY + 0.5f, HOT, converter.celsius(img.max), true);
        drawMarker(canvas, minX + 0.5f, minY + 0.5f, COLD, converter.celsius(img.min), false);

        // 스팟 레티클 + 온도 라벨
        double spotC = getSpotCelsius();
        pt[0] = spotU * w;
        pt[1] = spotV * h;
        imageMatrix.mapPoints(pt);
        float r = 11 * dp, gap = 4 * dp, tick = 7 * dp;
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(1.5f * dp);
        canvas.drawCircle(pt[0], pt[1], r, stroke);
        canvas.drawLine(pt[0] - r - gap - tick, pt[1], pt[0] - r - gap, pt[1], stroke);
        canvas.drawLine(pt[0] + r + gap, pt[1], pt[0] + r + gap + tick, pt[1], stroke);
        canvas.drawLine(pt[0], pt[1] - r - gap - tick, pt[0], pt[1] - r - gap, stroke);
        canvas.drawLine(pt[0], pt[1] + r + gap, pt[0], pt[1] + r + gap + tick, stroke);
        fill.setColor(Color.WHITE);
        canvas.drawCircle(pt[0], pt[1], 1.5f * dp, fill);
        drawLabelBeside(canvas, fmt(spotC), pt[0], pt[1], r + gap + tick + 6 * dp, 16 * dp, Color.WHITE);
    }

    private static final int HOT = 0xFFFF6B5E, COLD = 0xFF5EB2FF;
    private final RectF pill = new RectF();

    /** 반투명 둥근 배경 위에 글자를 그립니다. (x,y) 는 정렬 기준점과 세로 중앙. */
    private void drawPill(Canvas c, String s, float x, float y, Paint.Align align, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(Paint.Align.LEFT);
        float tw = text.measureText(s);
        float padH = 7 * dp, padV = 4 * dp;
        float left = align == Paint.Align.LEFT ? x : (align == Paint.Align.RIGHT ? x - tw - 2 * padH : x - tw / 2 - padH);
        Paint.FontMetrics fm = text.getFontMetrics();
        float th = fm.descent - fm.ascent;
        pill.set(left, y - th / 2 - padV, left + tw + 2 * padH, y + th / 2 + padV);
        fill.setColor(0x99000000);
        float rr = pill.height() / 2;
        c.drawRoundRect(pill, rr, rr, fill);
        c.drawText(s, left + padH, y - (fm.ascent + fm.descent) / 2, text);
        text.setColor(Color.WHITE);
    }

    /** 컬러바 왼쪽 끝(px). 라벨이 컬러바와 겹치지 않게 하는 데 씁니다. */
    private float colorBarLeft = Float.MAX_VALUE;

    /** 점 (x,y) 오른쪽에 라벨을 그리되, 컬러바·화면 끝에 닿으면 왼쪽에 그립니다. */
    private void drawLabelBeside(Canvas c, String s, float x, float y, float offset, float size, int color) {
        text.setTextSize(size);
        float width = text.measureText(s) + 14 * dp;
        RectF cb = captureBounds;
        float limit = Math.min(colorBarLeft - 6 * dp, (cb != null ? cb.right : getWidth()) - 4 * dp);
        if (x + offset + width > limit) {
            drawPill(c, s, x - offset, y, Paint.Align.RIGHT, size, color);
        } else {
            drawPill(c, s, x + offset, y, Paint.Align.LEFT, size, color);
        }
    }

    /** 현재 스팟 위치의 온도(°C). 원본 센서 값 기준. 프레임이 없으면 NaN. */
    public double getSpotCelsius() {
        FrameRenderer.RenderedFrame f = frame;
        if (f == null || converter == null) return Double.NaN;
        ThermalImage img = f.image;
        int sx = Math.min(img.width - 1, (int) (spotU * img.width));
        int sy = Math.min(img.height - 1, (int) (spotV * img.height));
        return converter.rawToCelsius(img.spot(sx, sy));
    }

    private void drawMarker(Canvas c, float ix, float iy, int color, double tempC, boolean up) {
        pt[0] = ix;
        pt[1] = iy;
        imageMatrix.mapPoints(pt);
        float s = 6 * dp;
        path.reset();
        if (up) {
            path.moveTo(pt[0], pt[1] - s);
            path.lineTo(pt[0] - s, pt[1] + s * 0.75f);
            path.lineTo(pt[0] + s, pt[1] + s * 0.75f);
        } else {
            path.moveTo(pt[0], pt[1] + s);
            path.lineTo(pt[0] - s, pt[1] - s * 0.75f);
            path.lineTo(pt[0] + s, pt[1] - s * 0.75f);
        }
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(1.2f * dp);
        c.drawPath(path, stroke);
        drawLabelBeside(c, fmt(tempC), pt[0], pt[1], s + 4 * dp, 12 * dp, color);
    }

    private void drawColorBar(Canvas c, FrameRenderer.RenderedFrame f) {
        float barW = 8 * dp, margin = 14 * dp;
        RectF cb = captureBounds;
        // 화면: 뷰 세로 중앙(위아래 오버레이를 피함). 저장: 잘라낸 영상 영역 안.
        float areaTop = cb != null ? cb.top : (useSafeArea ? safeTop : 0);
        float areaBottom = cb != null ? cb.bottom : (useSafeArea ? getHeight() - safeBottom : getHeight());
        float areaRight = cb != null ? cb.right : getWidth();
        float areaH = areaBottom - areaTop;
        float barH = Math.min(areaH * (cb != null ? 0.62f : 0.40f), 320 * dp);
        if (barH < 60 * dp) return;
        float top = areaTop + (areaH - barH) / 2, bottom = top + barH;
        float left = areaRight - margin - barW;
        colorBarLeft = left;
        int[] colors = palette.colors;
        int[] stops = new int[17];
        for (int i = 0; i < stops.length; i++) stops[i] = colors[255 - i * 255 / (stops.length - 1)];
        fill.setColor(Color.WHITE); // 이전 라벨 배경의 반투명 알파가 남지 않도록
        fill.setShader(new android.graphics.LinearGradient(0, top, 0, bottom, stops, null, android.graphics.Shader.TileMode.CLAMP));
        pill.set(left, top, left + barW, bottom);
        c.drawRoundRect(pill, barW / 2, barW / 2, fill);
        fill.setShader(null);
        stroke.setColor(0x66FFFFFF);
        stroke.setStrokeWidth(1 * dp);
        c.drawRoundRect(pill, barW / 2, barW / 2, stroke);
        float cx = left + barW / 2;
        drawPill(c, fmt(converter.celsius(f.rangeHighRaw)), cx + barW / 2 + 2 * dp, top - 16 * dp, Paint.Align.RIGHT, 12 * dp, Color.WHITE);
        drawPill(c, fmt(converter.celsius(f.rangeLowRaw)), cx + barW / 2 + 2 * dp, bottom + 16 * dp, Paint.Align.RIGHT, 12 * dp, Color.WHITE);
    }

    /** 기본 세로 화면(ROTATION_0)에서 영상이 바로 서는 회전각 (실기기 확인). */
    private static final int PORTRAIT_AUTO_ROTATION = 270;

    /** 렌즈가 화면 쪽(셀카 방향)을 보도록 꽂혀 있는지. 화면 회전 보정 방향이 반대가 됩니다. */
    private boolean lensFacingScreen;

    public boolean isLensFacingScreen() { return lensFacingScreen; }

    /** 자연 방향 화면(ROTATION_0)에서 영상을 바로 세우는 회전각. 렌즈 방향 자동 감지에 씁니다. */
    public int getNaturalRotation() { return (rotation + PORTRAIT_AUTO_ROTATION) % 360; }
    public void setLensFacingScreen(boolean v) { lensFacingScreen = v; invalidate(); }

    /**
     * 실제 적용 회전각. 카메라는 폰에 고정되어 있으므로 화면이 돌아간 만큼 반대로 보정합니다.
     * <pre>
     *   effective = 사용자 회전 + 270° − s · 화면회전각     (s = +1 뒷면 렌즈, −1 화면 쪽 렌즈)
     * </pre>
     * 180°(거꾸로 세로)는 렌즈 방향과 관계없이 90° 가 됩니다.
     */
    public int getEffectiveRotation() {
        int displayDeg = 0;
        android.view.Display d = getDisplay();
        if (d != null) displayDeg = d.getRotation() * 90;
        int s = lensFacingScreen ? -1 : 1;
        return (((rotation + PORTRAIT_AUTO_ROTATION - s * displayDeg) % 360) + 360) % 360;
    }

    /** 상단·하단 오버레이가 차지하는 높이(px). 영상은 가능하면 이 영역을 피해 배치합니다. */
    private int safeTop, safeBottom;

    public void setSafeInsets(int top, int bottom) {
        if (top == safeTop && bottom == safeBottom) return;
        safeTop = top;
        safeBottom = bottom;
        invalidate();
    }

    /** 현재 영상이 안전 영역에 맞춰졌는지 (가로 화면처럼 너무 작아지면 전체 화면을 씀). */
    private boolean useSafeArea;

    private void buildMatrix(int w, int h) {
        int vw = getWidth(), vh = getHeight();
        int rotation = getEffectiveRotation();
        boolean swap = rotation == 90 || rotation == 270;
        float rw = swap ? h : w, rh = swap ? w : h;
        float full = Math.min(vw / rw, vh / rh);
        float availH = vh - safeTop - safeBottom;
        float safe = availH > 0 ? Math.min(vw / rw, availH / rh) : 0;
        useSafeArea = !fullscreen && captureBounds == null && safe >= full * 0.8f;
        float sx = useSafeArea ? safe : full, sy = sx;
        if (fullscreen) {
            if (scaleMode == ScaleMode.STRETCH) {
                sx = vw / rw;
                sy = vh / rh;
            } else if (scaleMode == ScaleMode.FILL) {
                sx = sy = Math.max(vw / rw, vh / rh);
            }
        }
        float cy = useSafeArea ? safeTop + availH / 2f : vh / 2f;
        imageMatrix.reset();
        imageMatrix.postTranslate(-w / 2f, -h / 2f);
        imageMatrix.postRotate(rotation);
        imageMatrix.postScale(sx, sy); // 회전 후 화면 축 기준이라 늘이기(비율 무시)도 그대로 적용됨
        imageMatrix.postTranslate(vw / 2f, cy);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static String fmt(double c) {
        return Double.isNaN(c) ? "--.-°C" : String.format(Locale.US, "%.1f°C", c);
    }
}
