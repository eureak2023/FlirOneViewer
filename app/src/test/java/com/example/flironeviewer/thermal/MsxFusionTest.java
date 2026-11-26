package com.example.flironeviewer.thermal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MsxFusionTest {

    @Test
    public void cropCoversCentralRegionScaledByMagnQuote() {
        Fusion f = Fusion.deviceDefault();
        int[] c = f.visualCrop(640, 480);
        // 시야 배율 1.2516 → 약 511x384
        assertEquals(511, c[2]);
        assertEquals(384, c[3]);
        // 시차 오프셋(3m): X = -5.07 - 3.35/3 ≈ -6.2, Y = -16.95/3 ≈ -5.6 → 잘라낼 영역은 중앙보다 오른쪽·아래
        assertEquals(71, c[0]);   // 320 + 6.19 - 255.68 = 70.5
        assertEquals(54, c[1]);   // 240 + 5.65 - 191.76 = 53.9
    }

    @Test
    public void msxAddsVisualEdgesOntoUniformThermal() {
        int tw = 4, th = 4, ow = 16, oh = 16;
        int[] raw = new int[tw * th];
        java.util.Arrays.fill(raw, 100);
        int[] gray = new int[256];
        for (int i = 0; i < 256; i++) gray[i] = 0xFF000000 | i << 16 | i << 8 | i;
        // 실화상: 왼쪽 절반 검정, 오른쪽 절반 흰색 → 경계에 윤곽
        int[] vis = new int[ow * oh];
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) vis[y * ow + x] = x < ow / 2 ? 0xFF000000 : 0xFFFFFFFF;
        int[] out = Msx.compose(raw, tw, th, 0, 200, gray, vis, ow, oh, 1.0f, 2);
        int flat = out[8 * ow + 1] & 0xFF;            // 경계에서 먼 곳 = 열화상 그대로(127 근처)
        int edgeDark = out[8 * ow + 7] & 0xFF;        // 경계 왼쪽(어두운 쪽)
        int edgeBright = out[8 * ow + 8] & 0xFF;      // 경계 오른쪽(밝은 쪽)
        assertEquals(127, flat, 2);
        assertTrue(edgeDark < flat - 30);
        assertTrue(edgeBright > flat + 30);
    }

    @Test
    public void weakVisualEdgeIsStillClearlyVisible() {
        // 어두운 실화상의 약한 경계(휘도 20 → 40)도 윤곽이 뚜렷해야 함
        int tw = 4, th = 4, ow = 16, oh = 16;
        int[] raw = new int[tw * th];
        java.util.Arrays.fill(raw, 100);
        int[] gray = new int[256];
        for (int i = 0; i < 256; i++) gray[i] = 0xFF000000 | i << 16 | i << 8 | i;
        int[] vis = new int[ow * oh];
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) {
            int v = x < ow / 2 ? 20 : 40;
            vis[y * ow + x] = 0xFF000000 | v << 16 | v << 8 | v;
        }
        int[] out = Msx.compose(raw, tw, th, 0, 200, gray, vis, ow, oh, 1.0f, 4);
        int flat = out[8 * ow + 1] & 0xFF;
        int dark = out[8 * ow + 7] & 0xFF, bright = out[8 * ow + 8] & 0xFF;
        assertTrue("dark=" + dark + " flat=" + flat, flat - dark >= 12);
        assertTrue("bright=" + bright + " flat=" + flat, bright - flat >= 12);
    }
}
