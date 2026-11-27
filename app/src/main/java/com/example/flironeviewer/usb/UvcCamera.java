package com.example.flironeviewer.usb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.SystemClock;
import android.util.Log;

import com.example.flironeviewer.protocol.Mjpeg;
import com.example.flironeviewer.protocol.UvcDescriptors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 표준 UVC 웹캠(UV 카메라 등) 드라이버 — 네이티브 라이브러리 없이 Android USB 호스트 API 만 사용합니다.
 *
 * <p>UVLOOK 앱(com.iuv.android 1.2.2) 분석 결과 이 카메라들은 전용 프로토콜이 없는 표준 UVC 장치이며,
 * 원본 앱은 saki4510t UVCCamera(libuvc)로 1280×720 MJPEG 를 받고 처리 장치(PU)의 밝기·대비·선명도·채도만
 * 조절합니다. 여기서는 같은 일을 직접 합니다: 디스크립터 해석 → PROBE/COMMIT 협상 → 벌크 엔드포인트에서
 * 페이로드(헤더 + 데이터) 수신 → FID/EOF 로 프레임 조립 → JPEG 디코딩.
 *
 * <p>Android Java API 는 등시성(isochronous) 전송을 지원하지 않으므로 벌크 전송 카메라만 지원합니다.
 */
public final class UvcCamera {

    private static final String TAG = "UvcCamera";

    public interface Callback {
        /** 디코딩된 프레임 (디코딩 스레드). 비트맵은 다음 프레임에서 재사용하지 않으므로 보관해도 됩니다. */
        void onFrame(Bitmap frame);

        /** 스트림이 끊김 (스트림 스레드). */
        void onStopped(String reason);
    }

    /** UVLOOK 앱의 장치 필터와 같은 UV 카메라 (VID:PID). */
    private static final int[][] KNOWN_UV = {{0x13D3, 0x784B}, {0x0C45, 0x64AB}};

    /** 벌크 전송 UVC 스트리밍 인터페이스가 있는 장치인지 (FLIR ONE 제외). */
    public static boolean isUvc(UsbDevice d) {
        if (d == null || FlirOneCamera.isFlirOne(d)) return false;
        for (int[] k : KNOWN_UV) if (d.getVendorId() == k[0] && d.getProductId() == k[1]) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO && it.getInterfaceSubclass() == 2) return true;
        }
        return false;
    }

    // 처리 장치(PU) 제어 선택자 (UVC 1.1 표 A-13)
    public static final int PU_BRIGHTNESS = 0x02, PU_CONTRAST = 0x03, PU_SATURATION = 0x07, PU_SHARPNESS = 0x08;
    private static final int[] PU_BIT = new int[0x14];

    static {
        // bmControls 비트 위치: 0 밝기, 1 대비, 3 채도, 4 선명도
        PU_BIT[PU_BRIGHTNESS] = 0;
        PU_BIT[PU_CONTRAST] = 1;
        PU_BIT[PU_SATURATION] = 3;
        PU_BIT[PU_SHARPNESS] = 4;
    }

    private static final int SET_CUR = 0x01, GET_CUR = 0x81, GET_MIN = 0x82, GET_MAX = 0x83, GET_DEF = 0x87;
    private static final int VS_PROBE = 0x01, VS_COMMIT = 0x02;
    private static final int REQ_OUT = 0x21, REQ_IN = 0xA1, TIMEOUT = 1000;

    private final UsbManager manager;
    private final UsbDevice device;
    private UsbDeviceConnection conn;
    private UvcDescriptors desc;
    private UsbInterface vcIf, vsIf;
    private UsbEndpoint inEp;
    private Thread streamThread, decodeThread;
    private volatile boolean running;
    private UvcDescriptors.FrameSize current;
    private int maxPayload;

    public UvcCamera(UsbManager manager, UsbDevice device) {
        this.manager = manager;
        this.device = device;
    }

    public UsbDevice getDevice() { return device; }
    public UvcDescriptors.FrameSize getCurrentSize() { return current; }
    public java.util.List<UvcDescriptors.FrameSize> getSizes() { return desc.sizes; }

    /** 장치를 열고 디스크립터를 해석합니다. 작업 스레드에서 호출하세요. */
    public void open() throws IOException {
        conn = manager.openDevice(device);
        if (conn == null) throw new IOException("openDevice failed");
        byte[] raw = conn.getRawDescriptors();
        if (raw == null) throw new IOException("no descriptors");
        desc = UvcDescriptors.parse(raw);
        if (desc.vsInterface < 0) throw new IOException("no UVC streaming interface");
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            if (it.getId() == desc.vcInterface && it.getAlternateSetting() == 0) vcIf = it;
            if (it.getId() == desc.vsInterface && it.getAlternateSetting() == 0) vsIf = it;
        }
        if (vsIf == null) throw new IOException("streaming interface missing");
        for (int e = 0; e < vsIf.getEndpointCount(); e++) {
            UsbEndpoint ep = vsIf.getEndpoint(e);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) inEp = ep;
        }
        if (inEp == null) throw new IOException("isochronous-only camera (not supported)");
        if (vcIf != null) conn.claimInterface(vcIf, true);
        if (!conn.claimInterface(vsIf, true)) throw new IOException("claimInterface failed");
        if (desc.sizes.isEmpty()) throw new IOException("no MJPEG/YUYV format");
        Log.i(TAG, String.format(Locale.US, "UVC %04x:%04x bcdUVC=%04x VC=%d VS=%d PU=%d ep=0x%02x sizes=%s",
                device.getVendorId(), device.getProductId(), desc.bcdUVC, desc.vcInterface, desc.vsInterface,
                desc.processingUnitId, inEp.getAddress(), desc.sizes));
    }

    /** 해상도를 협상하고 스트리밍을 시작합니다. 작업 스레드에서 호출하세요. */
    public void start(UvcDescriptors.FrameSize size, Callback cb) throws IOException {
        stop();
        int plen = desc.bcdUVC >= 0x0150 ? 48 : (desc.bcdUVC >= 0x0110 ? 34 : 26);
        byte[] probe = new byte[plen];
        probe[0] = 1; // bmHint: dwFrameInterval 고정
        probe[2] = (byte) size.formatIndex;
        probe[3] = (byte) size.frameIndex;
        putU32(probe, 4, size.defaultInterval);
        int r = conn.controlTransfer(REQ_OUT, SET_CUR, VS_PROBE << 8, desc.vsInterface, probe, plen, TIMEOUT);
        if (r < 0 && plen != 26) { // 일부 장치는 버전과 다른 길이를 씀
            plen = 26;
            probe = java.util.Arrays.copyOf(probe, plen);
            r = conn.controlTransfer(REQ_OUT, SET_CUR, VS_PROBE << 8, desc.vsInterface, probe, plen, TIMEOUT);
        }
        if (r < 0) throw new IOException("PROBE SET_CUR failed");
        r = conn.controlTransfer(REQ_IN, GET_CUR, VS_PROBE << 8, desc.vsInterface, probe, plen, TIMEOUT);
        if (r < 26) throw new IOException("PROBE GET_CUR failed");
        r = conn.controlTransfer(REQ_OUT, SET_CUR, VS_COMMIT << 8, desc.vsInterface, probe, plen, TIMEOUT);
        if (r < 0) throw new IOException("COMMIT failed");
        long maxFrame = u32(probe, 18);
        maxPayload = (int) Math.min(Math.max(u32(probe, 22), 512), 4 * 1024 * 1024);
        current = size;
        Log.i(TAG, String.format(Locale.US, "commit %s fmt=%d frame=%d interval=%d maxFrame=%d maxPayload=%d",
                size, probe[2], probe[3], u32(probe, 4), maxFrame, maxPayload));
        running = true;
        final FrameQueue queue = new FrameQueue();
        streamThread = new Thread(() -> streamLoop(queue, cb, (int) Math.max(maxFrame, (long) size.width * size.height * 2)),
                "UVC-stream");
        decodeThread = new Thread(() -> decodeLoop(queue, cb, size), "UVC-decode");
        streamThread.start();
        decodeThread.start();
    }

    /** 최신 프레임 하나만 유지 (디코딩이 밀리면 오래된 프레임은 버림). */
    private static final class FrameQueue {
        private byte[] data;
        private int len;
        private boolean closed;

        synchronized void put(byte[] d, int l) {
            data = d;
            len = l;
            notifyAll();
        }

        synchronized byte[] take(int[] outLen) throws InterruptedException {
            while (data == null && !closed) wait(500);
            if (data == null) return null;
            byte[] d = data;
            outLen[0] = len;
            data = null;
            return d;
        }

        synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private void streamLoop(FrameQueue queue, Callback cb, int maxFrame) {
        final int nReq = 4;
        ArrayDeque<UsbRequest> idle = new ArrayDeque<>();
        UsbRequest[] reqs = new UsbRequest[nReq];
        ByteBuffer[] bufs = new ByteBuffer[nReq];
        String reason = null;
        byte[] frame = new byte[Math.max(maxFrame, 64 * 1024)];
        int frameLen = 0, lastFid = -1;
        boolean frameErr = false;
        long frames = 0, statAt = SystemClock.elapsedRealtime();
        try {
            for (int i = 0; i < nReq; i++) {
                reqs[i] = new UsbRequest();
                if (!reqs[i].initialize(conn, inEp)) throw new IOException("UsbRequest init failed");
                bufs[i] = ByteBuffer.allocate(maxPayload);
                reqs[i].setClientData(i);
                if (!reqs[i].queue(bufs[i])) throw new IOException("queue failed");
            }
            while (running) {
                UsbRequest done = conn.requestWait(1000);
                if (done == null) continue;
                int i = (Integer) done.getClientData();
                ByteBuffer b = bufs[i];
                int n = b.position();
                byte[] a = b.array();
                if (n >= 2) {
                    int hlen = a[0] & 0xFF, info = a[1] & 0xFF;
                    // 페이로드 헤더: 길이 2~12 (기본 2 + PTS 4 + SCR 6). EOH 비트는 장치마다 달라 보지 않음(libuvc 와 같음)
                    if (hlen >= 2 && hlen <= 12 && hlen <= n) {
                        int fid = info & 0x01;
                        boolean eof = (info & 0x02) != 0, err = (info & 0x40) != 0;
                        if (lastFid >= 0 && fid != lastFid && frameLen > 0) { // EOF 없이 다음 프레임 시작
                            if (!frameErr) { queue.put(java.util.Arrays.copyOf(frame, frameLen), frameLen); frames++; }
                            frameLen = 0;
                            frameErr = false;
                        }
                        lastFid = fid;
                        frameErr |= err;
                        int dl = n - hlen;
                        if (frameLen + dl > frame.length) frame = java.util.Arrays.copyOf(frame, (frameLen + dl) * 2);
                        System.arraycopy(a, hlen, frame, frameLen, dl);
                        frameLen += dl;
                        if (eof) {
                            if (!frameErr && frameLen > 0) { queue.put(java.util.Arrays.copyOf(frame, frameLen), frameLen); frames++; }
                            frameLen = 0;
                            frameErr = false;
                        }
                    }
                }
                b.clear();
                if (running && !done.queue(b)) throw new IOException("requeue failed");
                long now = SystemClock.elapsedRealtime();
                if (now - statAt >= 5000) {
                    Log.i(TAG, String.format(Locale.US, "%.1f fps", frames * 1000.0 / (now - statAt)));
                    frames = 0;
                    statAt = now;
                }
            }
        } catch (Exception e) {
            reason = e.getMessage();
            Log.w(TAG, "stream stopped", e);
        } finally {
            for (UsbRequest r : reqs) {
                if (r != null) {
                    r.cancel();
                    r.close();
                }
            }
            queue.close();
        }
        if (reason != null && running) {
            running = false;
            cb.onStopped(reason);
        }
    }

    private void decodeLoop(FrameQueue queue, Callback cb, UvcDescriptors.FrameSize size) {
        int[] len = new int[1];
        int[] argb = size.mjpeg ? null : new int[size.width * size.height];
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            while (running) {
                byte[] d = queue.take(len);
                if (d == null) continue;
                Bitmap bmp = null;
                if (size.mjpeg) {
                    byte[] jpg = Mjpeg.withHuffmanTables(d, len[0]);
                    if (jpg != null) bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, opt);
                } else if (len[0] >= size.width * size.height * 2) {
                    Mjpeg.yuyvToArgb(d, size.width, size.height, argb);
                    bmp = Bitmap.createBitmap(argb, size.width, size.height, Bitmap.Config.ARGB_8888);
                }
                if (bmp != null) cb.onFrame(bmp);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        running = false;
        joinQuietly(streamThread);
        joinQuietly(decodeThread);
        streamThread = decodeThread = null;
    }

    public void close() {
        stop();
        if (conn != null) {
            try {
                if (vsIf != null) conn.releaseInterface(vsIf);
                if (vcIf != null) conn.releaseInterface(vcIf);
            } catch (Exception ignored) {
            }
            conn.close();
            conn = null;
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------- 화질 조정 (처리 장치)

    public boolean supports(int selector) {
        return desc != null && desc.processingUnitId >= 0 && selector < PU_BIT.length
                && (desc.processingControls & (1L << PU_BIT[selector])) != 0;
    }

    /** {최소, 최대, 기본, 현재}. 지원하지 않으면 null. */
    public int[] getRange(int selector) {
        if (!supports(selector)) return null;
        int[] r = new int[4];
        int[] reqs = {GET_MIN, GET_MAX, GET_DEF, GET_CUR};
        for (int i = 0; i < 4; i++) {
            Integer v = getPu(reqs[i], selector);
            if (v == null) return null;
            r[i] = v;
        }
        if (selector == PU_BRIGHTNESS) for (int i = 0; i < 4; i++) r[i] = (short) r[i]; // 밝기만 부호 있음
        return r[0] < r[1] ? r : null;
    }

    public boolean set(int selector, int value) {
        if (!supports(selector) || conn == null) return false;
        byte[] b = {(byte) value, (byte) (value >> 8)};
        return conn.controlTransfer(REQ_OUT, SET_CUR, selector << 8, puIndex(), b, 2, TIMEOUT) >= 0;
    }

    private Integer getPu(int req, int selector) {
        if (conn == null) return null;
        byte[] b = new byte[2];
        int r = conn.controlTransfer(REQ_IN, req, selector << 8, puIndex(), b, 2, TIMEOUT);
        return r == 2 ? (b[0] & 0xFF) | (b[1] & 0xFF) << 8 : null;
    }

    private int puIndex() { return desc.processingUnitId << 8 | desc.vcInterface; }

    private static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static long u32(byte[] b, int o) {
        return ((b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24);
    }
}
