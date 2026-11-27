package com.example.flironeviewer.thermal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Random;

import org.junit.Test;

/** 핀홀 카메라 모의 실험: 손 떨림처럼 폰을 흔들 때 렌즈 방향을 맞게 판별하는지. */
public class LensFacingDetectorTest {

    private static final int W = 80, H = 60, WORLD = 400;
    private static final double F = 90; // px/rad (FLIR ONE 열화상 약 50° 시야)

    private static float[] world(long seed) {
        Random r = new Random(seed);
        float[] n = new float[WORLD * WORLD];
        for (int i = 0; i < n.length; i++) n[i] = r.nextFloat() * 1000;
        return Enhance.box(Enhance.box(n, WORLD, WORLD, 2), WORLD, WORLD, 1);
    }

    private static float sample(float[] wd, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        x0 = Math.max(0, Math.min(WORLD - 2, x0));
        y0 = Math.max(0, Math.min(WORLD - 2, y0));
        double a = wd[y0 * WORLD + x0], b = wd[y0 * WORLD + x0 + 1];
        double c = wd[(y0 + 1) * WORLD + x0], d = wd[(y0 + 1) * WORLD + x0 + 1];
        return (float) ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy);
    }

    /**
     * @param screen 렌즈가 화면 쪽인지 (카메라가 커넥터 축 = 영상 u 축 기준으로 180° 돌아감)
     * @param rot    자연 방향에서 영상을 바로 세우는 회전각
     */
    private static int simulate(boolean screen, int rot, long seed) {
        float[] wd = world(seed);
        Random r = new Random(seed * 31 + 7);
        LensFacingDetector det = new LensFacingDetector();
        double th = Math.toRadians(rot);
        double ax = Math.cos(th), ay = -Math.sin(th), bx = -Math.sin(th), by = -Math.cos(th);
        double sgn = screen ? -1 : 1; // y_c = sgn·B
        double U = 0, V = 0; // 누적 영상 이동(px)
        long t = 1_000_000_000L, lag = 80_000_000L, gyroDt = 5_000_000L, frameDt = 110_000_000L;
        long nextFrame = t + frameDt;
        double phase1 = r.nextDouble() * 6, phase2 = r.nextDouble() * 6;
        for (int step = 0; step < 2000; step++) {
            double s = step * gyroDt * 1e-9;
            // 손 떨림 + 천천히 기울이기 (rad/s)
            double wx = 0.35 * Math.sin(2 * Math.PI * 0.9 * s + phase1) + 0.05 * r.nextGaussian();
            double wy = 0.30 * Math.sin(2 * Math.PI * 0.7 * s + phase2) + 0.05 * r.nextGaussian();
            det.addGyro(t, (float) wx, (float) wy);
            double gA = (wx * ax + wy * ay) * gyroDt * 1e-9;
            double gB = (wx * bx + wy * by) * gyroDt * 1e-9;
            U += -F * sgn * gB;
            V += F * gA;
            t += gyroDt;
            if (t >= nextFrame) {
                float[] img = new float[W * H];
                double ox = WORLD / 2.0 - W / 2.0 - U, oy = WORLD / 2.0 - H / 2.0 - V;
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img[y * W + x] = sample(wd, ox + x, oy + y);
                // 영상은 지연되어 도착: 촬영 시각 t, 도착 시각 t + lag
                int d = det.addFrame(t + lag, img, W, H, rot);
                if (d != LensFacingDetector.UNKNOWN) return d;
                nextFrame += frameDt;
            }
        }
        return det.getDecision();
    }

    @Test
    public void detectsBackFacing() {
        assertEquals(LensFacingDetector.BACK, simulate(false, 270, 1));
        assertEquals(LensFacingDetector.BACK, simulate(false, 0, 2));
    }

    @Test
    public void detectsScreenFacing() {
        assertEquals(LensFacingDetector.SCREEN, simulate(true, 270, 3));
        assertEquals(LensFacingDetector.SCREEN, simulate(true, 90, 4));
    }

    @Test
    public void estimatesKnownShift() {
        float[] wd = world(9);
        float[] a = new float[W * H], b = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            a[y * W + x] = sample(wd, 100 + x, 100 + y);
            b[y * W + x] = sample(wd, 100 + x - 3, 100 + y + 2); // 장면이 (+3, −2) 이동
        }
        double[] s = LensFacingDetector.estimateShift(LensFacingDetector.normalizedHighPass(a, W, H),
                LensFacingDetector.normalizedHighPass(b, W, H), W, H, 10);
        assertNotNull(s);
        assertEquals(3, s[0], 0.3);
        assertEquals(-2, s[1], 0.3);
    }

    @Test
    public void staysUnknownWithoutMotion() {
        float[] wd = world(5);
        LensFacingDetector det = new LensFacingDetector();
        float[] img = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img[y * W + x] = sample(wd, 150 + x, 150 + y);
        long t = 1_000_000_000L;
        for (int i = 0; i < 400; i++) {
            det.addGyro(t + i * 5_000_000L, 0f, 0f);
        }
        for (int i = 0; i < 15; i++) {
            assertEquals(LensFacingDetector.UNKNOWN, det.addFrame(t + 300_000_000L + i * 110_000_000L, img, W, H, 270));
        }
    }
}
