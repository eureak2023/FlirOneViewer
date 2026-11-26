package com.example.flironeviewer.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import org.junit.Test;

public class FrameAssemblerTest {

    /** 디스어셈블한 writeFrameToOutputBuffer 와 같은 방식으로 프레임을 만듭니다. */
    static byte[] buildFrame(int seq, byte[] thermal, byte[] jpeg, String json) {
        byte[] status = (json + "\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xBEEF).putInt(seq).putInt(thermal.length + jpeg.length + status.length)
                .putInt(thermal.length).putInt(jpeg.length).putInt(status.length);
        CRC32 crc = new CRC32();
        crc.update(h.array(), 0, 24);
        h.putInt((int) crc.getValue());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(h.array(), 0, 28);
        out.write(thermal, 0, thermal.length);
        out.write(jpeg, 0, jpeg.length);
        out.write(status, 0, status.length);
        return out.toByteArray();
    }

    @Test
    public void assemblesFramesSplitAcrossChunksWithGarbage() {
        Random rnd = new Random(1);
        byte[] thermal = new byte[164 * 120 * 2];
        rnd.nextBytes(thermal);
        byte[] jpeg = new byte[30000];
        rnd.nextBytes(jpeg);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(new byte[]{1, 2, 3, (byte) 0xEF, (byte) 0xBE, 0, 0, 9}, 0, 8); // 쓰레기 + 가짜 매직
        for (int i = 0; i < 3; i++) {
            byte[] f = buildFrame(i, thermal, jpeg, "{\"shutterState\":\"ON\",\"n\":" + i + "}");
            stream.write(f, 0, f.length);
        }
        byte[] all = stream.toByteArray();

        List<RawFrame> got = new ArrayList<>();
        FrameAssembler a = new FrameAssembler(got::add);
        int off = 0;
        while (off < all.length) {
            int n = Math.min(all.length - off, 1 + rnd.nextInt(131072));
            a.feed(all, off, n);
            off += n;
        }

        assertEquals(3, got.size());
        for (int i = 0; i < 3; i++) {
            RawFrame f = got.get(i);
            assertEquals(i, f.sequence);
            assertTrue(f.headerCrcValid);
            assertArrayEquals(thermal, f.thermal);
            assertArrayEquals(jpeg, f.jpeg);
            assertEquals("{\"shutterState\":\"ON\",\"n\":" + i + "}", f.statusJson);
        }
        assertEquals(0, a.getCrcMismatches());
    }
}
