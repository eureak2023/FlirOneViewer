package com.example.flironeviewer.thermal;

/**
 * MSX 방식 윤곽선 합성: 정합된 실화상 휘도의 고주파 성분(윤곽)을 확대한 열화상 컬러에 더합니다.
 * <pre>
 *   edge = Y − boxBlur(Y)            (Y: 실화상 휘도, 반경 r 박스 블러)
 *   out  = clamp(thermalColor + strength · edge)   (R,G,B 각각)
 * </pre>
 * Android 의존성이 없는 순수 연산이라 단위 테스트가 가능합니다.
 */
public final class Msx {

    private Msx() {}

    /**
     * @param raw       열화상 raw (tw x th)
     * @param lo,hi     팔레트 매핑 범위(raw)
     * @param colors    256 단계 팔레트 (ARGB)
     * @param visual    정합된 실화상 ARGB 픽셀 (ow x oh, 출력 크기와 같아야 함)
     * @param strength  윤곽 강도 (0 = 열화상만, 보통 0.6~1.5)
     * @return 출력 ARGB (ow x oh)
     */
    public static int[] compose(int[] raw, int tw, int th, int lo, int hi, int[] colors,
                                int[] visual, int ow, int oh, float strength, int blurRadius) {
        int n = ow * oh;
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            int c = visual[i];
            y[i] = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
        }
        int[] blur = boxBlur(y, ow, oh, blurRadius);

        int[] out = new int[n];
        float scale = 255f / Math.max(1, hi - lo);
        float fx = (float) tw / ow, fy = (float) th / oh;
        for (int oy = 0; oy < oh; oy++) {
            // 열화상 쌍선형 보간 좌표
            float sy = (oy + 0.5f) * fy - 0.5f;
            int y0 = Math.max(0, Math.min(th - 1, (int) Math.floor(sy)));
            int y1 = Math.min(th - 1, y0 + 1);
            float wy = Math.max(0f, Math.min(1f, sy - y0));
            for (int ox = 0; ox < ow; ox++) {
                float sx = (ox + 0.5f) * fx - 0.5f;
                int x0 = Math.max(0, Math.min(tw - 1, (int) Math.floor(sx)));
                int x1 = Math.min(tw - 1, x0 + 1);
                float wx = Math.max(0f, Math.min(1f, sx - x0));
                float v = (raw[y0 * tw + x0] * (1 - wx) + raw[y0 * tw + x1] * wx) * (1 - wy)
                        + (raw[y1 * tw + x0] * (1 - wx) + raw[y1 * tw + x1] * wx) * wy;
                int idx = (int) ((v - lo) * scale);
                int c = colors[idx < 0 ? 0 : (idx > 255 ? 255 : idx)];

                int i = oy * ow + ox;
                int e = (int) ((y[i] - blur[i]) * strength);
                int r = clamp(((c >> 16) & 0xFF) + e);
                int g = clamp(((c >> 8) & 0xFF) + e);
                int b = clamp((c & 0xFF) + e);
                out[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        return out;
    }

    /** 적분 영상 기반 박스 블러(가장자리는 유효 영역 평균). */
    static int[] boxBlur(int[] src, int w, int h, int r) {
        long[] integ = new long[(w + 1) * (h + 1)];
        for (int yy = 0; yy < h; yy++) {
            long rowSum = 0;
            for (int xx = 0; xx < w; xx++) {
                rowSum += src[yy * w + xx];
                integ[(yy + 1) * (w + 1) + xx + 1] = integ[yy * (w + 1) + xx + 1] + rowSum;
            }
        }
        int[] out = new int[w * h];
        for (int yy = 0; yy < h; yy++) {
            int ya = Math.max(0, yy - r), yb = Math.min(h - 1, yy + r);
            for (int xx = 0; xx < w; xx++) {
                int xa = Math.max(0, xx - r), xb = Math.min(w - 1, xx + r);
                long s = integ[(yb + 1) * (w + 1) + xb + 1] - integ[ya * (w + 1) + xb + 1]
                        - integ[(yb + 1) * (w + 1) + xa] + integ[ya * (w + 1) + xa];
                out[yy * w + xx] = (int) (s / ((long) (yb - ya + 1) * (xb - xa + 1)));
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
