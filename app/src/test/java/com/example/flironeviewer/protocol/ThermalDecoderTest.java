package com.example.flironeviewer.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class ThermalDecoderTest {

    @Test
    public void decodesLepton3SegmentedRows() {
        byte[] t = new byte[164 * 120 * 2];
        // 각 행: [hdr,hdr, 80px, hdr,hdr, 80px]. 헤더 워드는 0xFFFF 로 채워 섞이지 않는지 확인.
        for (int y = 0; y < 120; y++) {
            for (int w = 0; w < 164; w++) {
                int v;
                if (w < 2 || w == 82 || w == 83) v = 0xFFFF;
                else {
                    int x = w < 82 ? w - 2 : w - 4;
                    v = y * 200 + x;
                }
                int i = (y * 164 + w) * 2;
                t[i] = (byte) v;
                t[i + 1] = (byte) (v >> 8);
            }
        }
        ThermalImage img = ThermalDecoder.decode(t, false);
        assertNotNull(img);
        assertEquals(160, img.width);
        assertEquals(120, img.height);
        assertEquals(0, img.at(0, 0));
        assertEquals(79, img.at(79, 0));
        assertEquals(80, img.at(80, 0));
        assertEquals(119 * 200 + 159, img.at(159, 119));
        assertEquals(0, img.min);
        assertEquals(119 * 200 + 159, img.max);
    }

    @Test
    public void honoursBigEndianFlag() {
        byte[] t = new byte[160 * 120 * 2];
        t[0] = 0x12;
        t[1] = 0x34;
        assertEquals(0x3412, ThermalDecoder.decode(t, false).at(0, 0));
        assertEquals(0x1234, ThermalDecoder.decode(t, true).at(0, 0));
    }
}
