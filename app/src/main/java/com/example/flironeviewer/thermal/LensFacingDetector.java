package com.example.flironeviewer.thermal;

import java.util.Locale;

/**
 * 렌즈가 뒷면을 보는지 화면 쪽(셀카)을 보는지 자이로와 영상 움직임으로 판별합니다.
 *
 * <p>카메라를 반대로 꽂으면 카메라가 커넥터 축(영상 u 축)을 중심으로 180° 돌아갑니다. 이때 폰을 앞뒤로
 * 기울이면(B 축 회전) 영상이 u 방향으로 움직이는 부호가 반대가 되고, 좌우로 돌리면(A 축 회전) 영상이
 * v 방향으로 움직이는 부호는 같습니다. 핀홀 카메라에서 회전 ω 에 대한 화면 중앙의 이동은
 * <pre>
 *   du = −f·(ω·y_c)·dt,  dv = +f·(ω·x_c)·dt
 *   뒷면:   x_c = A, y_c =  B  →  du = −f·gB,  dv = +f·gA
 *   화면 쪽: x_c = A, y_c = −B  →  du = +f·gB,  dv = +f·gA
 * </pre>
 * 이고, A·B 는 영상 u·v 축이 폰 좌표계(자연 방향 기준, x 오른쪽·y 위)에서 가리키는 방향입니다
 * (화면 표시 회전각 θ 로부터 계산). du 와 gB 의 상관 부호로 방향을 정하고, 방향과 무관한 dv–gA 상관은
 * 자이로·영상 시간차(지연) 추정과 측정 신뢰도 확인에 씁니다.
 *
 * <p>스레드 안전: 자이로(센서 스레드)와 프레임(렌더 스레드)이 동시에 호출해도 됩니다.
 */
public final class LensFacingDetector {

    public static final int UNKNOWN = 0, BACK = 1, SCREEN = 2;

    /** 자이로·영상 지연 후보(ns). 영상은 노출 후 USB 전송을 거쳐 늦게 도착합니다. */
    private static final long[] LAGS_NS = {0, 40_000_000L, 80_000_000L, 120_000_000L, 160_000_000L, 220_000_000L};
    /** 프레임 간 이동 탐색 범위(px). */
    private static final int SEARCH = 10;
    /** 판단에 쓰는 최소 회전량(rad, 프레임 간). 약 0.25°. */
    private static final double MIN_ROT = 0.0045;
    private static final int MIN_SAMPLES = 6;
    private static final double MIN_CORR = 0.6, MIN_CORR_V = 0.4;

    // 자이로 링버퍼 (자연 방향 기준 x, y 축 각속도 rad/s)
    private static final int GN = 2048;
    private final long[] gt = new long[GN];
    private final float[] gx = new float[GN], gy = new float[GN];
    private int gHead, gCount;

    private float[] prev;
    private int prevW, prevH;
    private long prevT;

    // 지연 후보별 누적합
    private final double[] sUU = new double[LAGS_NS.length], sBB = new double[LAGS_NS.length],
            sUB = new double[LAGS_NS.length], sVV = new double[LAGS_NS.length],
            sAA = new double[LAGS_NS.length], sVA = new double[LAGS_NS.length];
    private final int[] nB = new int[LAGS_NS.length];
    private int decision = UNKNOWN;
    private String last = "";

    public synchronized void reset() {
        gHead = gCount = 0;
        prev = null;
        decision = UNKNOWN;
        java.util.Arrays.fill(sUU, 0);
        java.util.Arrays.fill(sBB, 0);
        java.util.Arrays.fill(sUB, 0);
        java.util.Arrays.fill(sVV, 0);
        java.util.Arrays.fill(sAA, 0);
        java.util.Arrays.fill(sVA, 0);
        java.util.Arrays.fill(nB, 0);
        last = "";
    }

    public synchronized int getDecision() { return decision; }

    /** 마지막 판단 근거 (로그용). */
    public synchronized String debugString() { return last; }

    /** 자이로 샘플. t 는 SensorEvent.timestamp 와 같은 elapsedRealtimeNanos 기준. */
    public synchronized void addGyro(long tNs, float wx, float wy) {
        gt[gHead] = tNs;
        gx[gHead] = wx;
        gy[gHead] = wy;
        gHead = (gHead + 1) % GN;
        if (gCount < GN) gCount++;
    }

    /**
     * 프레임 하나를 넣고 현재 판단을 돌려줍니다.
     *
     * @param img         밝기 영상 (행 우선, w*h). 열화상 raw 또는 정합된 실화상 휘도.
     * @param rotationDeg 자연 방향 화면(ROTATION_0)에서 영상을 바로 세우는 회전각 (시계 방향, 90° 단위)
     */
    public synchronized int addFrame(long tNs, float[] img, int w, int h, int rotationDeg) {
        if (decision != UNKNOWN) return decision;
        float[] cur = normalizedHighPass(img, w, h);
        float[] p = prev;
        long dtNs = tNs - prevT;
        prev = cur;
        prevW = w;
        prevH = h;
        long t0 = prevT;
        prevT = tNs;
        if (cur == null || p == null || p.length != cur.length || dtNs < 30_000_000L || dtNs > 400_000_000L) {
            return decision;
        }
        double[] shift = estimateShift(p, cur, w, h, SEARCH);
        if (shift == null) return decision; // 무늬가 없거나 일치가 불분명

        double th = Math.toRadians(rotationDeg);
        double c = Math.cos(th), s = Math.sin(th);
        // 영상 u 축 → 화면 (cos, sin) → 폰 (cos, −sin);  v 축 → 화면 (−sin, cos) → 폰 (−sin, −cos)
        double ax = c, ay = -s, bx = -s, by = -c;

        for (int k = 0; k < LAGS_NS.length; k++) {
            double[] rot = integrate(t0 - LAGS_NS[k], tNs - LAGS_NS[k]);
            if (rot == null) continue;
            double gA = rot[0] * ax + rot[1] * ay;
            double gB = rot[0] * bx + rot[1] * by;
            if (Math.abs(gA) + Math.abs(gB) < MIN_ROT) continue;
            sUU[k] += shift[0] * shift[0];
            sBB[k] += gB * gB;
            sUB[k] += shift[0] * gB;
            sVV[k] += shift[1] * shift[1];
            sAA[k] += gA * gA;
            sVA[k] += shift[1] * gA;
            if (Math.abs(gB) >= MIN_ROT) nB[k]++;
        }
        evaluate();
        return decision;
    }

    private void evaluate() {
        // 방향과 무관한 dv–gA 상관이 가장 큰 지연을 고름
        int best = -1;
        double bestV = 0;
        for (int k = 0; k < LAGS_NS.length; k++) {
            double rv = corr(sVA[k], sVV[k], sAA[k]);
            if (Math.abs(rv) > Math.abs(bestV)) { bestV = rv; best = k; }
        }
        if (best < 0) return;
        double ru = corr(sUB[best], sUU[best], sBB[best]);
        last = String.format(Locale.US, "lag=%dms nB=%d r_u=%.2f r_v=%.2f f_u=%.0f f_v=%.0f",
                LAGS_NS[best] / 1_000_000, nB[best], ru, bestV,
                sBB[best] > 0 ? sUB[best] / sBB[best] : 0, sAA[best] > 0 ? sVA[best] / sAA[best] : 0);
        if (nB[best] >= MIN_SAMPLES && Math.abs(ru) >= MIN_CORR && Math.abs(bestV) >= MIN_CORR_V) {
            decision = ru < 0 ? BACK : SCREEN;
        }
    }

    private static double corr(double sxy, double sxx, double syy) {
        return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : 0;
    }

    /** [t0, t1] 구간의 자이로 회전량 (rad, x·y). 구간을 덮는 샘플이 없으면 null. */
    private double[] integrate(long t0, long t1) {
        if (gCount < 2 || t1 <= t0) return null;
        int newest = (gHead - 1 + GN) % GN, oldest = (gHead - gCount + GN) % GN;
        if (gt[oldest] > t0 || gt[newest] < t1) return null;
        double rx = 0, ry = 0;
        for (int n = 1; n < gCount; n++) {
            int i = (oldest + n) % GN, j = (oldest + n - 1) % GN;
            long a = Math.max(gt[j], t0), b = Math.min(gt[i], t1);
            if (b <= a) continue;
            double dt = (b - a) * 1e-9;
            rx += gx[i] * dt;
            ry += gy[i] * dt;
        }
        return new double[]{rx, ry};
    }

    /** 고역 통과(밝기 변화·자동 노출 영향 제거) 후 표준편차로 정규화. 무늬가 거의 없으면 null. */
    static float[] normalizedHighPass(float[] img, int w, int h) {
        int r = 3;
        float[] blur = Enhance.box(img, w, h, r);
        float[] out = new float[img.length];
        double ss = 0, sm = 0;
        for (int i = 0; i < img.length; i++) {
            float d = img[i] - blur[i];
            out[i] = d;
            sm += d;
            ss += (double) d * d;
        }
        double mean = sm / img.length;
        double std = Math.sqrt(Math.max(0, ss / img.length - mean * mean));
        if (std < 1e-3) return null;
        float inv = (float) (1 / std);
        for (int i = 0; i < out.length; i++) out[i] = (float) ((out[i] - mean) * inv);
        return out;
    }

    /**
     * prev → cur 이동량 (du, dv) px. cur(x+du, y+dv) ≈ prev(x, y).
     * 최소 비용이 뚜렷하지 않거나 탐색 경계에 닿으면 null.
     */
    static double[] estimateShift(float[] prev, float[] cur, int w, int h, int range) {
        int side = 2 * range + 1;
        double[] cost = new double[side * side];
        double sum = 0;
        int bi = -1;
        double bc = Double.MAX_VALUE;
        for (int dy = -range; dy <= range; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                int x0 = Math.max(0, -dx), x1 = Math.min(w, w - dx);
                int y0 = Math.max(0, -dy), y1 = Math.min(h, h - dy);
                double c = 0;
                for (int y = y0; y < y1; y++) {
                    int pi = y * w, ci = (y + dy) * w + dx;
                    for (int x = x0; x < x1; x++) c += Math.abs(prev[pi + x] - cur[ci + x]);
                }
                c /= (double) (x1 - x0) * (y1 - y0);
                int idx = (dy + range) * side + (dx + range);
                cost[idx] = c;
                sum += c;
                if (c < bc) { bc = c; bi = idx; }
            }
        }
        int bx = bi % side - range, by = bi / side - range;
        if (Math.abs(bx) >= range || Math.abs(by) >= range) return null;
        if (bc > 0.75 * (sum / cost.length)) return null; // 뚜렷한 최소가 없음
        int ix = bi % side, iy = bi / side;
        double fx = bx + parabola(cost[bi - 1], bc, cost[bi + 1]);
        double fy = by + parabola(cost[(iy - 1) * side + ix], bc, cost[(iy + 1) * side + ix]);
        return new double[]{fx, fy};
    }

    private static double parabola(double l, double c, double r) {
        double d = l - 2 * c + r;
        return d > 1e-9 ? 0.5 * (l - r) / d : 0;
    }
}
