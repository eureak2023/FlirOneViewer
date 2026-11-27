package com.example.flironeviewer.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import org.junit.Test;

public class UvcTest {

    /** 기본 DHT 의 길이별 개수 합이 값 개수와 맞는지 (테이블 4개, 세그먼트 길이 0x01A2). */
    @Test
    public void defaultHuffmanTablesAreConsistent() {
        byte[] d = Mjpeg.DEFAULT_DHT;
        assertEquals(0xFF, d[0] & 0xFF);
        assertEquals(0xC4, d[1] & 0xFF);
        int segLen = (d[2] & 0xFF) << 8 | (d[3] & 0xFF);
        assertEquals(0x01A2, segLen);
        assertEquals(segLen + 2, d.length);
        int p = 4, tables = 0;
        while (p < d.length) {
            int n = 0;
            for (int i = 0; i < 16; i++) n += d[p + 1 + i] & 0xFF;
            p += 1 + 16 + n;
            tables++;
        }
        assertEquals(d.length, p);
        assertEquals(4, tables);
    }

    /**
     * 실제 JPEG 에서 DHT 를 지운 뒤(UVC MJPEG 처럼) 다시 끼워 넣으면 원본과 같이 디코딩되는지.
     * 단위 테스트는 android.jar 로 컴파일되므로 JDK 의 ImageIO 는 리플렉션으로 씁니다.
     */
    @Test
    public void reinsertedTablesDecodeLikeOriginal() throws Exception {
        Class<?> biC = Class.forName("java.awt.image.BufferedImage");
        Class<?> renderedC = Class.forName("java.awt.image.RenderedImage");
        Class<?> ioC = Class.forName("javax.imageio.ImageIO");
        Object src = biC.getConstructor(int.class, int.class, int.class).newInstance(64, 48, 1); // TYPE_INT_RGB
        Method setRgb = biC.getMethod("setRGB", int.class, int.class, int.class);
        Method getRgb = biC.getMethod("getRGB", int.class, int.class);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) setRgb.invoke(src, x, y, (x * 4) << 16 | (y * 5) << 8 | ((x + y) * 2));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        assertTrue((Boolean) ioC.getMethod("write", renderedC, String.class, OutputStream.class).invoke(null, src, "jpg", bo));
        byte[] jpg = bo.toByteArray();

        byte[] stripped = stripDht(jpg);
        assertTrue(stripped.length < jpg.length);
        byte[] fixed = Mjpeg.withHuffmanTables(stripped, stripped.length);
        assertNotNull(fixed);
        Method read = ioC.getMethod("read", InputStream.class);
        Object a = read.invoke(null, new ByteArrayInputStream(jpg));
        Object b = read.invoke(null, new ByteArrayInputStream(fixed));
        assertNotNull(b);
        for (int y = 0; y < 48; y += 7) for (int x = 0; x < 64; x += 9) assertEquals(getRgb.invoke(a, x, y), getRgb.invoke(b, x, y));

        // 이미 DHT 가 있으면 그대로
        assertArrayEquals(jpg, Mjpeg.withHuffmanTables(jpg, jpg.length));
        assertFalse(Mjpeg.isJpeg(new byte[]{1, 2, 3, 4, 5}, 5));
    }

    private static byte[] stripDht(byte[] j) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(j, 0, 2);
        int p = 2;
        while (p < j.length) {
            int m = j[p + 1] & 0xFF;
            if (m == 0xDA) { o.write(j, p, j.length - p); break; }
            int len = (j[p + 2] & 0xFF) << 8 | (j[p + 3] & 0xFF);
            if (m != 0xC4) o.write(j, p, 2 + len);
            p += 2 + len;
        }
        return o.toByteArray();
    }

    @Test
    public void yuyvGrayAndWhite() {
        byte[] src = {(byte) 235, (byte) 128, 16, (byte) 128};
        int[] out = new int[2];
        Mjpeg.yuyvToArgb(src, 2, 1, out);
        assertEquals(0xFFFFFFFF, out[0]);
        assertEquals(0xFF000000, out[1]);
    }

    /** uvlook mini 와 같은 구성(IAD + VC + 벌크 VS, MJPEG 2개·YUYV 1개 해상도)의 디스크립터. */
    @Test
    public void parsesStreamingFormats() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        w(b, 9, 4, 0, 0, 1, 0x0E, 1, 0, 0); // VC 인터페이스 0
        w(b, 13, 0x24, 1, 0x10, 0x01, 0x33, 0, 0, 0, 0, 0, 1, 1); // VC_HEADER bcdUVC 1.10
        w(b, 11, 0x24, 5, 2, 1, 0, 0, 2, 0x1B, 0x00, 0); // PU id 2, bmControls 0x001B
        w(b, 9, 4, 1, 0, 1, 0x0E, 2, 0, 0); // VS 인터페이스 1
        w(b, 7, 5, 0x81, 2, 0, 2, 0); // 벌크 IN 0x81
        w(b, 14, 0x24, 1, 2, 0, 0, 0x81, 0, 0, 0, 0, 0, 1, 0); // VS_INPUT_HEADER
        w(b, 11, 0x24, 6, 1, 2, 0, 1, 0, 0, 0, 0); // FORMAT_MJPEG #1
        frame(b, 7, 1, 1280, 720, 333333);
        frame(b, 7, 2, 640, 480, 333333);
        byte[] fu = new byte[27];
        fu[0] = 27; fu[1] = 0x24; fu[2] = 4; fu[3] = 2; fu[4] = 1;
        fu[5] = 'Y'; fu[6] = 'U'; fu[7] = 'Y'; fu[8] = '2';
        b.write(fu, 0, fu.length); // FORMAT_UNCOMPRESSED #2 (YUY2)
        frame(b, 5, 1, 640, 480, 666666);
        UvcDescriptors d = UvcDescriptors.parse(b.toByteArray());
        assertEquals(0x0110, d.bcdUVC);
        assertEquals(0, d.vcInterface);
        assertEquals(1, d.vsInterface);
        assertEquals(2, d.processingUnitId);
        assertEquals(0x1B, d.processingControls);
        assertEquals(3, d.sizes.size());
        UvcDescriptors.FrameSize s = d.choose(1280, 720);
        assertTrue(s.mjpeg);
        assertEquals(1, s.formatIndex);
        assertEquals(1, s.frameIndex);
        assertEquals(30.0, s.fps(), 0.01);
        UvcDescriptors.FrameSize y = d.sizes.get(2);
        assertFalse(y.mjpeg);
        assertEquals(2, y.formatIndex);
        assertEquals(15.0, y.fps(), 0.01);
        assertTrue(d.choose(640, 480).mjpeg); // 같은 해상도면 MJPEG 우선
    }

    private static void frame(ByteArrayOutputStream b, int sub, int idx, int w, int h, int interval) {
        byte[] f = new byte[30];
        f[0] = 30; f[1] = 0x24; f[2] = (byte) sub; f[3] = (byte) idx;
        f[5] = (byte) w; f[6] = (byte) (w >> 8); f[7] = (byte) h; f[8] = (byte) (h >> 8);
        for (int i = 0; i < 4; i++) f[21 + i] = (byte) (interval >> (8 * i));
        f[25] = 1;
        for (int i = 0; i < 4; i++) f[26 + i] = (byte) (interval >> (8 * i));
        b.write(f, 0, f.length);
    }

    private static void w(ByteArrayOutputStream b, int... v) {
        for (int x : v) b.write(x);
    }
}
