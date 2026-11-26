package com.example.flironeviewer.thermal;

/**
 * 움직임 적응형 시간축 노이즈 제거 (픽셀별 재귀 평균).
 *
 * <p>Lepton 노이즈는 약 2~3 raw 카운트(NETD ≈ 50mK, 실기기 calib 기준 약 46 카운트/°C).
 * 이전 결과와의 차이가 작으면(정지 장면 노이즈) 강하게 평균하고, 크면(움직임·온도 변화) 즉시 따라갑니다.
 * <pre>
 *   d = |x − s|,  α = 0.2 (d ≤ 4) … 1.0 (d ≥ 16) 선형,  s ← s + α(x − s)
 * </pre>
 */
public final class TemporalDenoiser {

    private static final float LOW = 4f, HIGH = 16f, MIN_ALPHA = 0.2f;

    private float[] state;
    private int w, h;

    public synchronized void reset() {
        state = null;
    }

    /** raw(정수) → 노이즈 제거된 값(실수). 크기가 바뀌면 자동 초기화. */
    public synchronized float[] apply(int[] raw, int width, int height) {
        int n = width * height;
        if (state == null || w != width || h != height) {
            state = new float[n];
            for (int i = 0; i < n; i++) state[i] = raw[i];
            w = width;
            h = height;
            return state.clone();
        }
        float[] s = state;
        for (int i = 0; i < n; i++) {
            float x = raw[i];
            float d = Math.abs(x - s[i]);
            float a = d <= LOW ? MIN_ALPHA : (d >= HIGH ? 1f : MIN_ALPHA + (1f - MIN_ALPHA) * (d - LOW) / (HIGH - LOW));
            s[i] += a * (x - s[i]);
        }
        return s.clone();
    }
}
