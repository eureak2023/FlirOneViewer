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
        float[] rf = new float[raw.length];
        for (int i = 0; i < raw.length; i++) rf[i] = raw[i];
        int[] out = Enhance.colorize(Enhance.upsampleBilinear(rf, tw, th, ow, oh), lo, hi, colors);
        Enhance.addEdges(out, Enhance.luma(visual), ow, oh, strength, blurRadius);
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
