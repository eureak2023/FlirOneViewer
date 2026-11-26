package com.example.flironeviewer.protocol;

/**
 * 프레임의 thermal 페이로드를 raw 픽셀 배열로 변환합니다.
 *
 * <p>FLIR ONE Gen3/Pro 는 Lepton 3 계열 센서(160x120)를 사용하며, 각 영상 행은 VoSPI 패킷 2개
 * (패킷마다 2워드 헤더 + 80픽셀)로 전달됩니다. 따라서 한 행은 164워드입니다.
 * <pre>
 *  [hdr hdr | px0 … px79 | hdr hdr | px80 … px159]   = 164 x uint16
 * </pre>
 * 이 행 레이아웃은 네이티브 코드 문자열(LEP_*, "Image size: irWidth={}, irHeight={}")과
 * Lepton VoSPI 규격, 공개된 flirone-v4l2 분석 결과를 종합한 <b>추론</b>입니다.
 * 상태 JSON 에 {@code "bigEndianThermal"} 키가 있으면(네이티브 문자열에서 관찰) 바이트 순서를 바꿉니다.
 */
public final class ThermalDecoder {

    private ThermalDecoder() {}

    public static final int LEPTON3_W = 160;
    public static final int LEPTON3_H = 120;
    public static final int LEPTON3_STRIDE = 164;

    public static ThermalImage decode(byte[] t, boolean bigEndian) {
        if (t == null) return null;
        int words = t.length / 2;

        if (words >= LEPTON3_STRIDE * LEPTON3_H) {
            return decodeSegmented(t, bigEndian, LEPTON3_W, LEPTON3_H, LEPTON3_STRIDE);
        }
        if (words == LEPTON3_W * LEPTON3_H) {
            return decodePacked(t, bigEndian, LEPTON3_W, LEPTON3_H);
        }
        // Lepton 2.x (80x60, 행당 2워드 헤더 + 80픽셀)
        if (words >= 82 * 60) {
            int[] raw = new int[80 * 60];
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 80; x++) {
                    raw[y * 80 + x] = u16(t, y * 82 + 2 + x, bigEndian);
                }
            }
            ThermalImage img = new ThermalImage(80, 60, raw);
            if (words >= 82 * 63) parseTelemetry80(t, bigEndian, img);
            return img;
        }
        if (words == 80 * 60) {
            return decodePacked(t, bigEndian, 80, 60);
        }
        return null;
    }

    /**
     * 80x60 프레임 뒤의 텔레메트리 3행(각 82워드, 앞 2워드는 VoSPI 헤더) 해석.
     * 실기기(FLIR ONE 435-0004-01) 캡처로 확인한 워드 위치(행 내 인덱스, 헤더 포함):
     *  행60: [26]=FPA 온도 K×100, [28]=하우징 온도 K×100
     *  행62: [52]=스팟미터 평균 K×100, [53]=최대, [54]=최소, [55]=픽셀 수, [56..59]=ROI(시작행,시작열,끝행,끝열)
     */
    static void parseTelemetry80(byte[] t, boolean be, ThermalImage img) {
        int rowA = 60 * 82, rowC = 62 * 82;
        if (u16(t, rowA, be) != 60 && u16(t, rowA, false) != 60) return; // 패킷 ID 확인
        img.fpaK100 = u16(t, rowA + 26, be);
        img.housingK100 = u16(t, rowA + 28, be);
        int spot = u16(t, rowC + 52, be);
        int r0 = u16(t, rowC + 56, be), c0 = u16(t, rowC + 57, be);
        int r1 = u16(t, rowC + 58, be), c1 = u16(t, rowC + 59, be);
        if (spot > 20000 && spot < 70000 && r0 <= r1 && c0 <= c1 && r1 < 60 && c1 < 80) {
            img.spotMeanK100 = spot;
            img.spotRow0 = r0;
            img.spotCol0 = c0;
            img.spotRow1 = r1;
            img.spotCol1 = c1;
        }
    }

    /**
     * 손상된 행 수(80x60 형식: 행 = 82워드 = ID + CRC + 80픽셀). Lepton VoSPI CRC16-CCITT(다항식 0x1021, 초기값 0)를
     * 빅엔디언 바이트 순서로, ID 상위 4비트와 CRC 필드를 0으로 두고 계산합니다.
     * 실기기 약 1,750프레임에서 모든 행이 일치함을 확인했습니다. 형식이 다르면 0을 돌려줍니다.
     */
    public static int countCorruptRows(byte[] t) {
        if (t == null || t.length % 164 != 0) return 0;
        int rows = t.length / 164, bad = 0;
        for (int r = 0; r < rows; r++) {
            int o = r * 164;
            int id = (t[o] & 0xFF) | (t[o + 1] & 0xFF) << 8;
            int crc = (t[o + 2] & 0xFF) | (t[o + 3] & 0xFF) << 8;
            if ((id & 0x0FFF) != r || rowCrc(t, o) != crc) bad++;
        }
        return bad;
    }

    static int rowCrc(byte[] t, int o) {
        int crc = 0;
        for (int w = 0; w < 82; w++) {
            int v = (t[o + 2 * w] & 0xFF) | (t[o + 2 * w + 1] & 0xFF) << 8;
            if (w == 0) v &= 0x0FFF;
            if (w == 1) v = 0;
            crc = crcByte(crc, (v >> 8) & 0xFF);
            crc = crcByte(crc, v & 0xFF);
        }
        return crc;
    }

    private static int crcByte(int crc, int b) {
        crc ^= b << 8;
        for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
        return crc & 0xFFFF;
    }

    private static ThermalImage decodeSegmented(byte[] t, boolean be, int w, int h, int stride) {
        int half = w / 2;
        int[] raw = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * stride;
            for (int x = 0; x < w; x++) {
                int word = row + (x < half ? 2 + x : 4 + x);
                raw[y * w + x] = u16(t, word, be);
            }
        }
        return new ThermalImage(w, h, raw);
    }

    private static ThermalImage decodePacked(byte[] t, boolean be, int w, int h) {
        int[] raw = new int[w * h];
        for (int i = 0; i < raw.length; i++) raw[i] = u16(t, i, be);
        return new ThermalImage(w, h, raw);
    }

    private static int u16(byte[] b, int wordIndex, boolean bigEndian) {
        int i = wordIndex * 2;
        int lo = b[i] & 0xFF, hi = b[i + 1] & 0xFF;
        return bigEndian ? (lo << 8 | hi) : (hi << 8 | lo);
    }
}
