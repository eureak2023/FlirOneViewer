package com.example.flironeviewer.protocol;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * USB 벌크 전송 조각(최대 128KiB, 원본 앱의 FRAME_BUFFER_SIZE = 131072)을 이어 붙여
 * 완전한 {@link RawFrame} 으로 조립합니다. 스레드 안전하지 않으므로 USB 수신 스레드 하나에서만 호출하세요.
 */
public final class FrameAssembler {

    public static final int HEADER_SIZE = 28;
    /** 헤더 첫 4바이트(리틀엔디언 0x0000BEEF). */
    private static final byte M0 = (byte) 0xEF, M1 = (byte) 0xBE, M2 = 0x00, M3 = 0x00;
    /** 비정상 헤더를 걸러내기 위한 상한(실제 프레임은 수십~수백 KiB). */
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    public interface Listener {
        void onFrame(RawFrame frame);
    }

    private final Listener listener;
    private final CRC32 crc = new CRC32();
    private byte[] buf = new byte[512 * 1024];
    private int len;

    private long framesEmitted;
    private long crcMismatches;
    private long bytesDiscarded;

    public FrameAssembler(Listener listener) {
        this.listener = listener;
    }

    public void reset() {
        len = 0;
    }

    public long getFramesEmitted() { return framesEmitted; }
    public long getCrcMismatches() { return crcMismatches; }
    public long getBytesDiscarded() { return bytesDiscarded; }

    public void feed(byte[] data, int off, int count) {
        if (count <= 0) return;
        ensureCapacity(len + count);
        System.arraycopy(data, off, buf, len, count);
        len += count;
        parse();
    }

    private void parse() {
        int pos = 0;
        while (true) {
            int m = findMagic(pos);
            if (m < 0) {
                // 다음 조각과 이어질 수 있는 마지막 3바이트만 남깁니다.
                int keepFrom = Math.max(pos, len - 3);
                bytesDiscarded += keepFrom - pos;
                pos = keepFrom;
                break;
            }
            bytesDiscarded += m - pos;
            pos = m;
            if (len - pos < HEADER_SIZE) break;

            int seq = le32(pos + 4);
            int total = le32(pos + 8);
            int thermalSize = le32(pos + 12);
            int jpegSize = le32(pos + 16);
            int statusSize = le32(pos + 20);
            int headerCrc = le32(pos + 24);

            boolean sane = total >= 0 && thermalSize >= 0 && jpegSize >= 0 && statusSize >= 0
                    && total <= MAX_PAYLOAD
                    && (long) thermalSize + jpegSize + statusSize == total;
            if (!sane) {
                // 데이터 중간에서 우연히 매직이 나타난 경우 → 1바이트 밀고 재동기화
                pos += 1;
                bytesDiscarded += 1;
                continue;
            }
            if (len - pos < HEADER_SIZE + total) break; // 나머지 조각 대기

            crc.reset();
            crc.update(buf, pos, 24);
            boolean crcOk = (int) crc.getValue() == headerCrc;
            if (!crcOk) crcMismatches++; // 크기 합 검증은 통과했으므로 프레임은 사용

            int p = pos + HEADER_SIZE;
            byte[] thermal = slice(p, thermalSize);
            p += thermalSize;
            byte[] jpeg = slice(p, jpegSize);
            p += jpegSize;
            String status = cString(p, statusSize);

            pos += HEADER_SIZE + total;
            framesEmitted++;
            listener.onFrame(new RawFrame(seq, thermal, jpeg, status, crcOk));
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
    }

    private int findMagic(int from) {
        for (int i = from; i + 3 < len; i++) {
            if (buf[i] == M0 && buf[i + 1] == M1 && buf[i + 2] == M2 && buf[i + 3] == M3) return i;
        }
        return -1;
    }

    private int le32(int i) {
        return (buf[i] & 0xFF) | (buf[i + 1] & 0xFF) << 8 | (buf[i + 2] & 0xFF) << 16 | (buf[i + 3] & 0xFF) << 24;
    }

    private byte[] slice(int from, int n) {
        byte[] out = new byte[n];
        System.arraycopy(buf, from, out, 0, n);
        return out;
    }

    private String cString(int from, int n) {
        int end = from;
        int limit = from + n;
        while (end < limit && buf[end] != 0) end++;
        return new String(buf, from, end - from, StandardCharsets.UTF_8);
    }

    private void ensureCapacity(int needed) {
        if (needed <= buf.length) return;
        int cap = buf.length;
        while (cap < needed) cap *= 2;
        byte[] nb = new byte[cap];
        System.arraycopy(buf, 0, nb, 0, len);
        buf = nb;
    }
}
