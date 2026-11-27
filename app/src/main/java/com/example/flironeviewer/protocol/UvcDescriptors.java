package com.example.flironeviewer.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * UVC(USB Video Class) 디스크립터 파서. {@code UsbDeviceConnection.getRawDescriptors()} 결과를 읽어
 * 비디오 제어(VC)·스트리밍(VS) 인터페이스 번호, 처리 장치(밝기 등) ID, 지원 포맷·해상도를 찾습니다.
 */
public final class UvcDescriptors {

    /** 한 가지 해상도 (포맷 + 프레임 디스크립터). */
    public static final class FrameSize {
        public final boolean mjpeg;
        public final int formatIndex, frameIndex, width, height;
        /** 100ns 단위 프레임 간격 (333333 = 30fps). */
        public final int defaultInterval;

        FrameSize(boolean mjpeg, int formatIndex, int frameIndex, int width, int height, int defaultInterval) {
            this.mjpeg = mjpeg;
            this.formatIndex = formatIndex;
            this.frameIndex = frameIndex;
            this.width = width;
            this.height = height;
            this.defaultInterval = defaultInterval;
        }

        public double fps() { return defaultInterval > 0 ? 1e7 / defaultInterval : 0; }

        @Override
        public String toString() {
            return width + "×" + height + (mjpeg ? " MJPEG" : " YUYV");
        }
    }

    /** UVC 버전 (예: 0x0100, 0x0110). */
    public int bcdUVC = 0x0100;
    public int vcInterface = -1, vsInterface = -1;
    /** 처리 장치(Processing Unit) ID 와 지원 제어 비트맵. */
    public int processingUnitId = -1;
    public long processingControls;
    public final List<FrameSize> sizes = new ArrayList<>();

    private static final int CS_INTERFACE = 0x24;
    private static final int CC_VIDEO = 0x0E, SC_VIDEOCONTROL = 0x01, SC_VIDEOSTREAMING = 0x02;

    public static UvcDescriptors parse(byte[] raw) {
        UvcDescriptors r = new UvcDescriptors();
        int ifClass = -1, ifSub = -1, ifNum = -1, ifAlt = -1;
        int fmtIndex = -1;
        boolean fmtMjpeg = false, fmtYuyv = false;
        int p = 0;
        while (p + 2 <= raw.length) {
            int len = raw[p] & 0xFF, type = raw[p + 1] & 0xFF;
            if (len < 2 || p + len > raw.length) break;
            if (type == 0x04 && len >= 9) { // INTERFACE
                ifNum = raw[p + 2] & 0xFF;
                ifAlt = raw[p + 3] & 0xFF;
                ifClass = raw[p + 5] & 0xFF;
                ifSub = raw[p + 6] & 0xFF;
                if (ifClass == CC_VIDEO && ifSub == SC_VIDEOCONTROL && r.vcInterface < 0) r.vcInterface = ifNum;
                if (ifClass == CC_VIDEO && ifSub == SC_VIDEOSTREAMING && r.vsInterface < 0) r.vsInterface = ifNum;
            } else if (type == CS_INTERFACE && ifClass == CC_VIDEO && len >= 3) {
                int sub = raw[p + 2] & 0xFF;
                if (ifSub == SC_VIDEOCONTROL) {
                    if (sub == 0x01 && len >= 5) { // VC_HEADER
                        r.bcdUVC = u16(raw, p + 3);
                    } else if (sub == 0x05 && len >= 8 && r.processingUnitId < 0) { // PROCESSING_UNIT
                        r.processingUnitId = raw[p + 3] & 0xFF;
                        int n = raw[p + 7] & 0xFF;
                        long bits = 0;
                        for (int i = 0; i < n && i < 8 && p + 8 + i < p + len; i++) bits |= (long) (raw[p + 8 + i] & 0xFF) << (8 * i);
                        r.processingControls = bits;
                    }
                } else if (ifSub == SC_VIDEOSTREAMING && ifNum == r.vsInterface && ifAlt == 0) {
                    if (sub == 0x06 && len >= 5) { // FORMAT_MJPEG
                        fmtIndex = raw[p + 3] & 0xFF;
                        fmtMjpeg = true;
                        fmtYuyv = false;
                    } else if (sub == 0x04 && len >= 21) { // FORMAT_UNCOMPRESSED
                        fmtIndex = raw[p + 3] & 0xFF;
                        fmtMjpeg = false;
                        // GUID 앞 4바이트가 'YUY2' 인 것만 지원
                        fmtYuyv = raw[p + 5] == 'Y' && raw[p + 6] == 'U' && raw[p + 7] == 'Y' && raw[p + 8] == '2';
                    } else if ((sub == 0x07 || sub == 0x05) && len >= 26 && fmtIndex > 0) { // FRAME_MJPEG / FRAME_UNCOMPRESSED
                        boolean isMjpegFrame = sub == 0x07;
                        if (isMjpegFrame == fmtMjpeg && (fmtMjpeg || fmtYuyv)) {
                            r.sizes.add(new FrameSize(fmtMjpeg, fmtIndex, raw[p + 3] & 0xFF,
                                    u16(raw, p + 5), u16(raw, p + 7), (int) u32(raw, p + 21)));
                        }
                    } else if (sub != 0x07 && sub != 0x05 && sub != 0x03 && sub != 0x0D && sub != 0x01) {
                        fmtIndex = -1; // 지원하지 않는 포맷(H.264 등)의 프레임은 무시
                    }
                }
            }
            p += len;
        }
        return r;
    }

    /** 원하는 해상도에 가장 가까운 MJPEG(없으면 YUYV) 해상도. */
    public FrameSize choose(int wantW, int wantH) {
        FrameSize best = null;
        long bestScore = Long.MAX_VALUE;
        for (FrameSize s : sizes) {
            long score = Math.abs((long) s.width * s.height - (long) wantW * wantH) + (s.mjpeg ? 0 : 1_000_000_000L);
            if (s.width == wantW && s.height == wantH) score = s.mjpeg ? -2 : -1;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }

    static long u32(byte[] b, int o) { return (u16(b, o) | (long) u16(b, o + 2) << 16) & 0xFFFFFFFFL; }
}
