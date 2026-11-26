package com.example.flironeviewer.thermal;

/**
 * 표시용 화질 향상: 쌍선형 업스케일 + 실화상 기반 가이드 필터(He et al., "Guided Image Filtering").
 *
 * <pre>
 *   p = bilinearUpsample(thermal)      (출력 해상도)
 *   I = 정합된 실화상 휘도               (같은 해상도, 가이드)
 *   a = cov(I,p) / (var(I) + eps),  b = mean(p) − a·mean(I)    (반경 r 박스 창)
 *   q = mean(a)·I + mean(b)
 * </pre>
 * 열 경계가 실화상 경계에 맞춰 선명해지고, 열 차이가 없는 실화상 무늬는 cov≈0 이라 거의 옮겨지지 않습니다.
 * 측정값(스팟·최고·최저)에는 쓰지 않습니다 — 표시 전용.
 */
public final class Enhance {

    private Enhance() {}

    public static float[] upsampleBilinear(float[] src, int w, int h, int ow, int oh) {
        float[] out = new float[ow * oh];
        float fx = (float) w / ow, fy = (float) h / oh;
        for (int oy = 0; oy < oh; oy++) {
            float sy = (oy + 0.5f) * fy - 0.5f;
            int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(sy)));
            int y1 = Math.min(h - 1, y0 + 1);
            float wy = Math.max(0f, Math.min(1f, sy - y0));
            for (int ox = 0; ox < ow; ox++) {
                float sx = (ox + 0.5f) * fx - 0.5f;
                int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(sx)));
                int x1 = Math.min(w - 1, x0 + 1);
                float wx = Math.max(0f, Math.min(1f, sx - x0));
                out[oy * ow + ox] = (src[y0 * w + x0] * (1 - wx) + src[y0 * w + x1] * wx) * (1 - wy)
                        + (src[y1 * w + x0] * (1 - wx) + src[y1 * w + x1] * wx) * wy;
            }
        }
        return out;
    }

    /** ARGB → 휘도(0~255). */
    public static float[] luma(int[] argb) {
        float[] y = new float[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            y[i] = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) / 256f;
        }
        return y;
    }

    /** 가이드 필터. guide·p 는 같은 크기(w x h). */
    public static float[] guidedFilter(float[] guide, float[] p, int w, int h, int r, float eps) {
        int n = w * h;
        float[] ip = new float[n], ii = new float[n];
        for (int i = 0; i < n; i++) {
            ip[i] = guide[i] * p[i];
            ii[i] = guide[i] * guide[i];
        }
        float[] mI = box(guide, w, h, r), mP = box(p, w, h, r), mIP = box(ip, w, h, r), mII = box(ii, w, h, r);
        float[] a = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            float cov = mIP[i] - mI[i] * mP[i];
            float var = mII[i] - mI[i] * mI[i];
            a[i] = cov / (var + eps);
            b[i] = mP[i] - a[i] * mI[i];
        }
        float[] mA = box(a, w, h, r), mB = box(b, w, h, r);
        float[] q = new float[n];
        for (int i = 0; i < n; i++) q[i] = mA[i] * guide[i] + mB[i];
        return q;
    }

    /** 적분 영상 박스 필터(가장자리는 유효 영역 평균). */
    static float[] box(float[] src, int w, int h, int r) {
        double[] integ = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                row += src[y * w + x];
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row;
            }
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int ya = Math.max(0, y - r), yb = Math.min(h - 1, y + r);
            for (int x = 0; x < w; x++) {
                int xa = Math.max(0, x - r), xb = Math.min(w - 1, x + r);
                double s = integ[(yb + 1) * (w + 1) + xb + 1] - integ[ya * (w + 1) + xb + 1]
                        - integ[(yb + 1) * (w + 1) + xa] + integ[ya * (w + 1) + xa];
                out[y * w + x] = (float) (s / ((yb - ya + 1) * (xb - xa + 1)));
            }
        }
        return out;
    }

    /** raw 값 → 팔레트 ARGB. */
    public static int[] colorize(float[] v, int lo, int hi, int[] colors) {
        int[] out = new int[v.length];
        float scale = 255f / Math.max(1, hi - lo);
        for (int i = 0; i < v.length; i++) {
            int idx = (int) ((v[i] - lo) * scale);
            out[i] = colors[idx < 0 ? 0 : (idx > 255 ? 255 : idx)];
        }
        return out;
    }

    /** 윤곽 이득(강도 100% 기준)과 소프트 클리핑 한계(휘도 단계). */
    static final float EDGE_GAIN = 2.5f, EDGE_MAX = 120f;

    /**
     * MSX 윤곽 더하기.
     * <pre>
     *   d = (Y − boxBlur(Y, r)) · strength · EDGE_GAIN    (Y 는 이미 필터링 축소된 실화상이라 별도 노이즈 제거 불필요)
     *   e = EDGE_MAX · tanh(d / EDGE_MAX)                                (약한 윤곽은 키우고 강한 윤곽은 포화 방지)
     *   out = clamp(color + e)
     * </pre>
     */
    public static void addEdges(int[] argb, float[] luma, int w, int h, float strength, int r) {
        if (strength <= 0) return;
        float[] coarse = box(luma, w, h, r);
        float g = strength * EDGE_GAIN;
        for (int i = 0; i < argb.length; i++) {
            float d = (luma[i] - coarse[i]) * g;
            int e = Math.round(EDGE_MAX * (float) Math.tanh(d / EDGE_MAX));
            int c = argb[i];
            argb[i] = 0xFF000000 | clamp(((c >> 16) & 0xFF) + e) << 16 | clamp(((c >> 8) & 0xFF) + e) << 8 | clamp((c & 0xFF) + e);
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
