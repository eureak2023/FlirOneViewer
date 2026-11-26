package com.example.flironeviewer.thermal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class EnhanceTest {

    private static final int TW = 8, TH = 4, S = 6, OW = TW * S, OH = TH * S;

    /** 열화상: 왼쪽 100, 오른쪽 200 (x=4 경계). 실화상 가이드: 같은 위치(출력 x=24)에 휘도 경계. */
    private static float[] thermalStep() {
        float[] t = new float[TW * TH];
        for (int y = 0; y < TH; y++) for (int x = 0; x < TW; x++) t[y * TW + x] = x < TW / 2 ? 100 : 200;
        return t;
    }

    @Test
    public void guidedFilterSharpensEdgeAlignedWithVisual() {
        float[] up = Enhance.upsampleBilinear(thermalStep(), TW, TH, OW, OH);
        float[] guide = new float[OW * OH];
        for (int y = 0; y < OH; y++) for (int x = 0; x < OW; x++) guide[y * OW + x] = x < OW / 2 ? 30 : 220;
        float[] q = Enhance.guidedFilter(guide, up, OW, OH, 8, 100f);
        int row = OH / 2 * OW;
        // 경계 바로 옆 픽셀: 쌍선형은 중간값 근처로 번지지만, 가이드 필터는 양쪽 값에 더 가까워야 함
        float bilLeft = up[row + 22], bilRight = up[row + 25];
        float gLeft = q[row + 22], gRight = q[row + 25];
        assertTrue("left " + bilLeft + " -> " + gLeft, Math.abs(gLeft - 100) < Math.abs(bilLeft - 100));
        assertTrue("right " + bilRight + " -> " + gRight, Math.abs(gRight - 200) < Math.abs(bilRight - 200));
    }

    @Test
    public void visualTextureWithoutThermalDifferenceIsNotTransferred() {
        float[] flat = new float[TW * TH];
        java.util.Arrays.fill(flat, 150);
        float[] up = Enhance.upsampleBilinear(flat, TW, TH, OW, OH);
        float[] guide = new float[OW * OH];
        for (int y = 0; y < OH; y++) for (int x = 0; x < OW; x++) guide[y * OW + x] = ((x / 3 + y / 3) % 2) * 200; // 체크무늬
        float[] q = Enhance.guidedFilter(guide, up, OW, OH, 8, 100f);
        for (float v : q) assertEquals(150, v, 0.01);
    }

    @Test
    public void temporalDenoiserReducesNoiseAndTracksMotion() {
        TemporalDenoiser d = new TemporalDenoiser();
        Random rnd = new Random(7);
        int n = 4800;
        int[] raw = new int[n];
        double inVar = 0, outVar = 0;
        float[] out = null;
        for (int f = 0; f < 60; f++) {
            for (int i = 0; i < n; i++) raw[i] = 3500 + (int) Math.round(rnd.nextGaussian() * 2.5);
            out = d.apply(raw, 80, 60);
        }
        for (int i = 0; i < n; i++) {
            inVar += Math.pow(raw[i] - 3500, 2);
            outVar += Math.pow(out[i] - 3500, 2);
        }
        assertTrue("노이즈 감소 " + Math.sqrt(inVar / n) + " -> " + Math.sqrt(outVar / n), outVar < inVar * 0.5);
        // 큰 변화(움직임)는 한 프레임 안에 따라가야 함
        java.util.Arrays.fill(raw, 3700);
        out = d.apply(raw, 80, 60);
        assertEquals(3700, out[100], 0.5);
    }
}
