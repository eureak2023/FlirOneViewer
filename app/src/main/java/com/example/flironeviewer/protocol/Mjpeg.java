package com.example.flironeviewer.protocol;

/**
 * UVC 카메라 프레임 도우미.
 *
 * <p>UVC MJPEG 카메라는 대부분 프레임마다 허프만 테이블(DHT)을 빼고 보냅니다(AVI1 MJPEG). 이 경우 JPEG 표준
 * 부록 K 의 기본 테이블을 쓴다고 약속되어 있으므로, SOS 앞에 기본 DHT 를 끼워 넣어야 일반 JPEG 디코더가 읽을 수
 * 있습니다.
 */
public final class Mjpeg {
    private Mjpeg() { }

    /** JPEG 부록 K 기본 허프만 테이블 (DC 휘도·색차, AC 휘도·색차) — FFC4 세그먼트 전체. */
    static final byte[] DEFAULT_DHT = buildDefaultDht();

    private static byte[] buildDefaultDht() {
        int[][] tables = {
                // 클래스/ID, 길이별 개수 16개, 값들
                concat(new int[]{0x00}, new int[]{0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                        new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}),
                concat(new int[]{0x01}, new int[]{0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0},
                        new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}),
                concat(new int[]{0x10}, new int[]{0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d}, hex(
                        "01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa")),
                concat(new int[]{0x11}, new int[]{0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77}, hex(
                        "000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa")),
        };
        int len = 2;
        for (int[] t : tables) len += t.length;
        byte[] out = new byte[2 + len];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xC4;
        out[2] = (byte) (len >> 8);
        out[3] = (byte) len;
        int p = 4;
        for (int[] t : tables) for (int v : t) out[p++] = (byte) v;
        return out;
    }

    private static int[] concat(int[] a, int[] b, int[] c) {
        int[] r = new int[a.length + b.length + c.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        System.arraycopy(c, 0, r, a.length + b.length, c.length);
        return r;
    }

    private static int[] hex(String s) {
        int[] r = new int[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    /** JPEG 시작(FFD8)인지. */
    public static boolean isJpeg(byte[] d, int len) {
        return len > 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
    }

    /**
     * 허프만 테이블이 없으면 SOS(FFDA) 앞에 기본 DHT 를 넣은 새 배열을, 있으면 원본 길이만큼의 배열을 돌려줍니다.
     * JPEG 가 아니거나 SOS 를 못 찾으면 null.
     */
    public static byte[] withHuffmanTables(byte[] d, int len) {
        if (!isJpeg(d, len)) return null;
        int p = 2;
        while (p + 4 <= len) {
            if ((d[p] & 0xFF) != 0xFF) return null;
            int marker = d[p + 1] & 0xFF;
            if (marker == 0xFF) { p++; continue; } // 채움 바이트
            if (marker == 0xC4) return java.util.Arrays.copyOf(d, len); // DHT 있음
            if (marker == 0xDA) {
                byte[] out = new byte[len + DEFAULT_DHT.length];
                System.arraycopy(d, 0, out, 0, p);
                System.arraycopy(DEFAULT_DHT, 0, out, p, DEFAULT_DHT.length);
                System.arraycopy(d, p, out, p + DEFAULT_DHT.length, len - p);
                return out;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { p += 2; continue; } // 길이 없는 마커
            int segLen = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (segLen < 2) return null;
            p += 2 + segLen;
        }
        return null;
    }

    /** YUYV(YUY2, 4:2:2) → ARGB_8888 (BT.601 제한 범위). */
    public static void yuyvToArgb(byte[] src, int w, int h, int[] dst) {
        int n = w * h;
        for (int i = 0, s = 0; i + 1 < n; i += 2, s += 4) {
            int y0 = (src[s] & 0xFF) - 16, u = (src[s + 1] & 0xFF) - 128;
            int y1 = (src[s + 2] & 0xFF) - 16, v = (src[s + 3] & 0xFF) - 128;
            int rv = 409 * v, guv = -100 * u - 208 * v, bu = 516 * u;
            dst[i] = argb(298 * y0, rv, guv, bu);
            dst[i + 1] = argb(298 * y1, rv, guv, bu);
        }
    }

    private static int argb(int c, int rv, int guv, int bu) {
        int r = clamp((c + rv + 128) >> 8), g = clamp((c + guv + 128) >> 8), b = clamp((c + bu + 128) >> 8);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
