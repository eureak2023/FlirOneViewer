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
        FrameRenderer.RenderedFrame f = frame;
        if (f == null) return false;
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

        // 최고/최저 온도 마커
        int maxX = img.maxIndex % w, maxY = img.maxIndex / w;
        int minX = img.minIndex % w, minY = img.minIndex / w;
        drawMarker(canvas, maxX + 0.5f, maxY + 0.5f, Color.rgb(255, 60, 40), converter.celsius(img.max), true);
        drawMarker(canvas, minX + 0.5f, minY + 0.5f, Color.rgb(60, 160, 255), converter.celsius(img.min), false);

        // 스팟 측정
        int sx = Math.min(w - 1, (int) (spotU * w)), sy = Math.min(h - 1, (int) (spotV * h));
        double spotRaw = img.spot(sx, sy);
        double spotC = converter.rawToCelsius(spotRaw);
        pt[0] = spotU * w;
        pt[1] = spotV * h;
        imageMatrix.mapPoints(pt);
        float r = 14 * dp;
        stroke.setColor(Color.WHITE);
        canvas.drawCircle(pt[0], pt[1], r * 0.45f, stroke);
        canvas.drawLine(pt[0] - r, pt[1], pt[0] - r * 0.45f, pt[1], stroke);
        canvas.drawLine(pt[0] + r * 0.45f, pt[1], pt[0] + r, pt[1], stroke);
        canvas.drawLine(pt[0], pt[1] - r, pt[0], pt[1] - r * 0.45f, stroke);
        canvas.drawLine(pt[0], pt[1] + r * 0.45f, pt[0], pt[1] + r, stroke);
        text.setTextSize(18 * dp);
        canvas.drawText(fmt(spotC), pt[0] + r + 4 * dp, pt[1] - 4 * dp, text);

        drawColorBar(canvas, f);
    }

    private void drawMarker(Canvas c, float ix, float iy, int color, double tempC, boolean up) {
        pt[0] = ix;
        pt[1] = iy;
        imageMatrix.mapPoints(pt);
        float s = 7 * dp;
        path.reset();
        if (up) {
            path.moveTo(pt[0], pt[1] - s);
            path.lineTo(pt[0] - s, pt[1] + s * 0.7f);
            path.lineTo(pt[0] + s, pt[1] + s * 0.7f);
        } else {
            path.moveTo(pt[0], pt[1] + s);
            path.lineTo(pt[0] - s, pt[1] - s * 0.7f);
            path.lineTo(pt[0] + s, pt[1] - s * 0.7f);
        }
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(1.2f * dp);
        c.drawPath(path, stroke);
        stroke.setStrokeWidth(2 * dp);
        text.setTextSize(13 * dp);
        c.drawText(fmt(tempC), pt[0] + s + 2 * dp, pt[1] + 4 * dp, text);
    }

    private void drawColorBar(Canvas c, FrameRenderer.RenderedFrame f) {
        float barW = 12 * dp, margin = 10 * dp;
        RectF cb = captureBounds;
        // 화면: 뷰 전체 높이 기준(라벨은 위아래 여백에). 저장: 잘라낸 영상 영역 안에 라벨까지 들어가게.
        float areaTop = cb != null ? cb.top : 0, areaBottom = cb != null ? cb.bottom : getHeight();
        float areaRight = cb != null ? cb.right : getWidth();
        float top = areaTop + 36 * dp, bottom = areaBottom - 36 * dp;
        if (bottom - top < 60 * dp) return;
        float left = areaRight - margin - barW;
        int[] colors = palette.colors;
        float step = (bottom - top) / 256f;
        for (int i = 0; i < 256; i++) {
            fill.setColor(colors[255 - i]);
            c.drawRect(left, top + i * step, left + barW, top + (i + 1) * step + 1, fill);
        }
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(1 * dp);
        c.drawRect(new RectF(left, top, left + barW, bottom), stroke);
        stroke.setStrokeWidth(2 * dp);
        text.setTextSize(13 * dp);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmt(converter.celsius(f.rangeHighRaw)), left + barW, top - 8 * dp, text);
        c.drawText(fmt(converter.celsius(f.rangeLowRaw)), left + barW, bottom + 20 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** 세로 화면에서 자동으로 더할 회전각(센서 영상은 가로 방향). */
    /** 기본 세로 화면(ROTATION_0)에서 영상이 바로 서는 회전각 (실기기 확인). */
    private static final int PORTRAIT_AUTO_ROTATION = 270;

    /** 렌즈가 화면 쪽(셀카 방향)을 보도록 꽂혀 있는지. 화면 회전 보정 방향이 반대가 됩니다. */
    private boolean lensFacingScreen;

    public boolean isLensFacingScreen() { return lensFacingScreen; }
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

    private void buildMatrix(int w, int h) {
        int vw = getWidth(), vh = getHeight();
        int rotation = getEffectiveRotation();
        boolean swap = rotation == 90 || rotation == 270;
        float rw = swap ? h : w, rh = swap ? w : h;
        float s = Math.min(vw / rw, vh / rh);
        imageMatrix.reset();
        imageMatrix.postTranslate(-w / 2f, -h / 2f);
        imageMatrix.postRotate(rotation);
        imageMatrix.postScale(s, s);
        imageMatrix.postTranslate(vw / 2f, vh / 2f);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static String fmt(double c) {
        return Double.isNaN(c) ? "--.-°C" : String.format(Locale.US, "%.1f°C", c);
    }
}
