package com.example.flironeviewer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.example.flironeviewer.thermal.Palette;

/** 팔레트 그라디언트 미리보기. circle=true 면 원형(하단 버튼), 아니면 둥근 사각형(팔레트 목록). */
public final class PaletteSwatchView extends View {

    private Palette palette = Palette.IRON;
    private boolean circle = true;
    private boolean selected;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;

    public PaletteSwatchView(Context c) { this(c, null); }

    public PaletteSwatchView(Context c, AttributeSet a) {
        super(c, a);
        dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        ring.setStyle(Paint.Style.STROKE);
        setClickable(true);
    }

    public void setPalette(Palette p) { palette = p; rebuildShader(); invalidate(); }
    public void setCircle(boolean c) { circle = c; rebuildShader(); invalidate(); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildShader();
    }

    private void rebuildShader() {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int[] stops = new int[9];
        for (int i = 0; i < stops.length; i++) stops[i] = palette.colors[i * 255 / (stops.length - 1)];
        float inset = 3 * dp;
        rect.set(inset, inset, w - inset, h - inset);
        // 원형: 아래(차가움) → 위(뜨거움), 사각형: 왼쪽 → 오른쪽
        fill.setShader(circle
                ? new LinearGradient(0, rect.bottom, 0, rect.top, stops, null, Shader.TileMode.CLAMP)
                : new LinearGradient(rect.left, 0, rect.right, 0, stops, null, Shader.TileMode.CLAMP));
    }

    @Override
    public void setSelected(boolean s) {
        super.setSelected(s);
        selected = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (fill.getShader() == null) rebuildShader();
        if (circle) {
            canvas.drawOval(rect, fill);
            ring.setStrokeWidth(1.5f * dp);
            ring.setColor(0x66FFFFFF);
            canvas.drawOval(rect, ring);
        } else {
            float r = 10 * dp;
            canvas.drawRoundRect(rect, r, r, fill);
            ring.setStrokeWidth((selected ? 2.5f : 1f) * dp);
            ring.setColor(selected ? 0xFFFF8A3D : 0x33FFFFFF);
            canvas.drawRoundRect(rect, r, r, ring);
        }
    }
}
