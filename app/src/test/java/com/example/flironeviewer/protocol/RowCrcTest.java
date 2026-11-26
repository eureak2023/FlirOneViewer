package com.example.flironeviewer.protocol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RowCrcTest {

    /** 실기기에서 캡처한 텔레메트리 행 60 (82워드, [1]=CRC 75). */
    static final int[] ROW60 = {60, 75, 14, 58495, 2, 2104, 0, 24848, 35336, 27684, 27274, 4358, 1424, 9216, 0, 48, 1, 48,
            1, 1101, 0, 0, 4917, 0, 3913, 5508, 30842, 8207, 30793, 0, 30000, 30841, 58225, 2, 30793, 0, 0, 0, 79, 59,
            4800, 512, 1, 128, 0, 0, 0, 0, 0, 1, 128, 1, 0, 0, 0, 0, 0, 0, 30, 0, 1, 1, 78, 58, 7, 75, 25, 210, 75, 37,
            10, 15, 0, 0, 7, 0, 3, 0, 0, 0, 0, 0};

    private static byte[] rowsBytes(int rows) {
        // 행 0..rows-1 을 만들고, 마지막 행은 실기기 행60 내용(행 번호만 바꾸면 CRC 가 달라지므로 그대로 60번 위치에 둠)
        byte[] t = new byte[rows * 164];
        for (int r = 0; r < rows; r++) {
            int[] w = new int[82];
            if (r == 60) w = ROW60.clone();
            else {
                w[0] = r;
                for (int i = 2; i < 82; i++) w[i] = (r * 31 + i * 7) & 0x3FFF;
            }
            for (int i = 0; i < 82; i++) {
                t[r * 164 + 2 * i] = (byte) w[i];
                t[r * 164 + 2 * i + 1] = (byte) (w[i] >> 8);
            }
            if (r != 60) {
                int crc = ThermalDecoder.rowCrc(t, r * 164);
                t[r * 164 + 2] = (byte) crc;
                t[r * 164 + 3] = (byte) (crc >> 8);
            }
        }
        return t;
    }

    @Test
    public void crcMatchesCapturedDeviceRow() {
        byte[] t = rowsBytes(63);
        assertEquals(75, ThermalDecoder.rowCrc(t, 60 * 164));
        assertEquals(0, ThermalDecoder.countCorruptRows(t));
    }

    @Test
    public void detectsCorruptedPixel() {
        byte[] t = rowsBytes(63);
        t[10 * 164 + 50] ^= 0x10; // 10행 픽셀 1비트 손상
        assertEquals(1, ThermalDecoder.countCorruptRows(t));
    }
}
