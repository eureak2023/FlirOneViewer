package com.example.flironeviewer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** UV 카메라(UVC) 영상 표시: 회전·좌우 반전·표시 방식(원본 비율/화면에 맞추기/채우기). */
public final class UvcView extends View {

    private volatile Bitmap frame;
    private int rotation; // 0, 90, 180, 270 (시계 방향)
    private boolean mirror;
    private ThermalView.ScaleMode scaleMode = ThermalView.ScaleMode.ORIGINAL;
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public UvcView(Context c) { this(c, null); }

    public UvcView(Context c, AttributeSet a) { super(c, a); }

    public void setFrame(Bitmap b) {
        frame = b;
        postInvalidateOnAnimation();
    }

    public Bitmap getFrame() { return frame; }

    public int getImageRotation() { return rotation; }

    public void setImageRotation(int deg) {
        rotation = ((deg % 360) + 360) % 360;
        invalidate();
    }

    public boolean isMirror() { return mirror; }

    public void setMirror(boolean m) {
        mirror = m;
        invalidate();
    }

    public ThermalView.ScaleMode getScaleMode() { return scaleMode; }

    public void setScaleMode(ThermalView.ScaleMode m) {
        scaleMode = m;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        Bitmap b = frame;
        if (b == null) return;
        build(matrix, b.getWidth(), b.getHeight(), getWidth(), getHeight(), scaleMode);
        c.drawBitmap(b, matrix, paint);
    }

    private void build(Matrix m, int w, int h, float vw, float vh, ThermalView.ScaleMode mode) {
        boolean swap = rotation == 90 || rotation == 270;
        float rw = swap ? h : w, rh = swap ? w : h;
        float sx, sy;
        if (mode == ThermalView.ScaleMode.STRETCH) {
            sx = vw / rw;
            sy = vh / rh;
        } else {
            sx = sy = mode == ThermalView.ScaleMode.FILL ? Math.max(vw / rw, vh / rh) : Math.min(vw / rw, vh / rh);
        }
        m.reset();
        m.postTranslate(-w / 2f, -h / 2f);
        if (mirror) m.postScale(-1, 1);
        m.postRotate(rotation);
        m.postScale(sx, sy);
        m.postTranslate(vw / 2f, vh / 2f);
    }

    /** 저장용: 회전·반전을 적용한 원본 해상도 프레임. */
    public Bitmap capture() {
        Bitmap b = frame;
        if (b == null) return null;
        boolean swap = rotation == 90 || rotation == 270;
        int ow = swap ? b.getHeight() : b.getWidth(), oh = swap ? b.getWidth() : b.getHeight();
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        build(m, b.getWidth(), b.getHeight(), ow, oh, ThermalView.ScaleMode.ORIGINAL);
        new Canvas(out).drawBitmap(b, m, paint);
        return out;
    }
}
