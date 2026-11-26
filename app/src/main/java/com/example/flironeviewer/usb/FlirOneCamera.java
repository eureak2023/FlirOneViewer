package com.example.flironeviewer.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.SystemClock;
import android.util.Log;

import com.example.flironeviewer.protocol.FrameAssembler;
import com.example.flironeviewer.protocol.RawFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * FLIR ONE (USB) 저수준 드라이버.
 *
 * <p>원본 앱 {@code com.flir.thermalsdk.androidsdk.live.connectivity.UsbDriver} 디컴파일 결과에서
 * 관찰한 동작을 FLIR SDK 없이 그대로 재현합니다.
 * <ul>
 *   <li>엔드포인트 번호: CONFIG IN=1 / CONFIG OUT=2 / FILEIO IN=3 / FILEIO OUT=4 / FRAME IN=5</li>
 *   <li>채널 on/off: {@code controlTransfer(0x01, 11(SET_INTERFACE), on?1:0, ifNum, null, 0, 200)}
 *       — FILE_IO 는 인터페이스 1, FRAME 은 인터페이스 2</li>
 *   <li>연결 순서: FILE_IO 끄기 → FRAME 끄기 → FILE_IO 켜기 → 폴링 시작 → (startFrames) FRAME 켜기</li>
 *   <li>버퍼 크기: config 4096 / frame 131072 / file 1048576, UsbRequest + requestWait 로 3개 IN 엔드포인트 폴링</li>
 * </ul>
 */
public final class FlirOneCamera {

    public static final int VENDOR_ID = 0x09CB;   // 2507
    public static final int PRODUCT_ID = 0x1996;  // 6550

    private static final String TAG = "FlirOneCamera";

    private static final int EP_CONFIG_IN = 1;
    private static final int EP_CONFIG_OUT = 2;
    private static final int EP_FILEIO_IN = 3;
    private static final int EP_FILEIO_OUT = 4;
    private static final int EP_FRAME_IN = 5;

    private static final int IF_FILEIO = 1;
    private static final int IF_FRAME = 2;

    private static final int REQ_TYPE_STANDARD_INTERFACE_OUT = 0x01;
    private static final int REQ_SET_INTERFACE = 11;
    private static final int CTRL_TIMEOUT_MS = 200;

    private static final int CONFIG_BUFFER_SIZE = 4096;
    private static final int FRAME_BUFFER_SIZE = 131072;
    private static final int FILE_BUFFER_SIZE = 1048576;

    /** 프레임이 이 시간 동안 들어오지 않으면 FRAME 채널을 다시 켭니다. */
    private static final long FRAME_WATCHDOG_MS = 3000;
    private static final int MAX_FRAME_RESTARTS = 5;

    public interface Callback {
        /** USB 수신 스레드에서 호출됩니다. 오래 걸리는 작업은 다른 스레드로 넘기세요. */
        void onFrame(RawFrame frame);

        /** CONFIG 채널로 들어온 데이터(배터리/상태 패킷 등). 수신 스레드에서 호출. */
        void onConfigData(byte[] data, int length);

        /** 카메라의 CameraFiles.zip 을 받아 풀었을 때 (파일 경로 → 내용). 수신 스레드에서 호출. */
        void onCameraFiles(java.util.Map<String, byte[]> files);

        /** 오류 또는 장치 분리로 스트리밍이 끝났을 때 한 번 호출됩니다. */
        void onStopped(String reason);
    }

    private final UsbManager usbManager;
    private final UsbDevice device;
    private final Callback callback;

    private UsbDeviceConnection connection;
    private final List<UsbInterface> claimed = new ArrayList<>();
    /** 인터페이스 번호 → [alt0, alt1] */
    private final android.util.SparseArray<UsbInterface[]> altSettings = new android.util.SparseArray<>();
    private UsbEndpoint configOut, fileioOut;
    private UsbRequest configReq, fileioReq, frameReq;
    private final ByteBuffer configBuf = ByteBuffer.allocate(CONFIG_BUFFER_SIZE);
    private final ByteBuffer frameBuf = ByteBuffer.allocate(FRAME_BUFFER_SIZE);
    private final ByteBuffer fileioBuf = ByteBuffer.allocate(FILE_BUFFER_SIZE);

    private final FrameAssembler assembler;
    private final com.example.flironeviewer.protocol.CameraFilesReceiver cameraFiles =
            new com.example.flironeviewer.protocol.CameraFilesReceiver();
    private Thread pollThread;
    private volatile boolean running;
    private volatile long lastFrameAt;
    private volatile long frameBytes;
    private long statsAt, statsFrames, statsBytes;
    /** USB 진단 로그 (logcat 태그 FlirOneCamera). */
    private static final boolean DEBUG_LOG = false;
    private int frameRestarts;

    public FlirOneCamera(UsbManager usbManager, UsbDevice device, Callback callback) {
        this.usbManager = usbManager;
        this.device = device;
        this.callback = callback;
        this.assembler = new FrameAssembler(frame -> {
            lastFrameAt = SystemClock.elapsedRealtime();
            callback.onFrame(frame);
        });
    }

    public static boolean isFlirOne(UsbDevice d) {
        return d != null && d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID;
    }

    public UsbDevice getDevice() {
        return device;
    }

    public FrameAssembler getAssembler() {
        return assembler;
    }

    /** 장치를 열고 스트리밍을 시작합니다. 호출 전에 USB 권한이 있어야 합니다. */
    public synchronized void start() throws IOException {
        if (running) return;
        connection = usbManager.openDevice(device);
        if (connection == null) throw new IOException("openDevice() failed (check permission / device state)");

        try {
            claimAndMapEndpoints();

            // 원본 UsbDriver.connect() 순서. 단, 인터페이스 1/2 의 엔드포인트는 alt 1 에만 존재하므로
            // (실기기 디스크립터로 확인) alt 를 먼저 활성화한 뒤에 읽기 요청을 큐잉해야 합니다.
            setInterface(IF_FILEIO, false);
            setInterface(IF_FRAME, false);
            setInterface(IF_FILEIO, true);
            queue(fileioReq, fileioBuf);

            running = true;
            queue(configReq, configBuf);

            // 원본에서는 네이티브 계층이 이어서 startFrames() 호출 → 인터페이스 2 활성화
            setInterface(IF_FRAME, true);
            queue(frameReq, frameBuf);
            lastFrameAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "start OK: 인터페이스 " + claimed.size() + "개 claim, FILEIO/FRAME 활성화");
            startedAt = lastFrameAt;
        } catch (IOException e) {
            Log.w(TAG, "start 실패: " + e.getMessage());
            running = false;
            releaseAll();
            throw e;
        }

        pollThread = new Thread(this::pollLoop, "FlirOne-USB");
        pollThread.setPriority(Thread.MAX_PRIORITY - 1);
        pollThread.start();
    }

    /** 스트리밍을 멈추고 모든 USB 자원을 해제합니다. 어느 스레드에서나 호출 가능. */
    public void stop() {
        Thread t;
        synchronized (this) {
            if (!running && connection == null) return;
            running = false;
            t = pollThread;
            pollThread = null;
            cancelQuietly(frameReq);
            cancelQuietly(fileioReq);
            cancelQuietly(configReq);
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (connection != null) {
                try { setInterface(IF_FRAME, false); } catch (IOException ignored) { }
                try { setInterface(IF_FILEIO, false); } catch (IOException ignored) { }
            }
            releaseAll();
        }
    }

    /** CONFIG 채널(EP 0x02)로 원시 패킷을 보냅니다(고급 용도). */
    public synchronized boolean sendConfig(byte[] packet) {
        if (connection == null || configOut == null) return false;
        int off = 0;
        while (off < packet.length) {
            int n = connection.bulkTransfer(configOut, packet, off, packet.length - off, 500);
            if (n <= 0) return false;
            off += n;
        }
        return true;
    }

    // ------------------------------------------------------------------ 내부

    private void claimAndMapEndpoints() throws IOException {
        UsbEndpoint configIn = null, fileioIn = null, frameIn = null;
        altSettings.clear();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            int alt = intf.getAlternateSetting();
            if (alt == 0 || alt == 1) {
                UsbInterface[] a = altSettings.get(intf.getId());
                if (a == null) altSettings.put(intf.getId(), a = new UsbInterface[2]);
                a[alt] = intf;
            }
            if (intf.getEndpointCount() == 0) continue;
            if (!connection.claimInterface(intf, false)) {
                throw new IOException("claimInterface(" + intf.getId() + ") failed");
            }
            claimed.add(intf);
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                int num = ep.getEndpointNumber();
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    if (num == EP_CONFIG_IN) configIn = ep;
                    else if (num == EP_FILEIO_IN) fileioIn = ep;
                    else if (num == EP_FRAME_IN) frameIn = ep;
                } else {
                    if (num == EP_CONFIG_OUT) configOut = ep;
                    else if (num == EP_FILEIO_OUT) fileioOut = ep;
                }
            }
        }
        if (configIn == null || fileioIn == null || frameIn == null || configOut == null || fileioOut == null) {
            throw new IOException("Required 5 endpoints not found (is this a FLIR ONE USB device?)");
        }
        configReq = newRequest(configIn);
        fileioReq = newRequest(fileioIn);
        frameReq = newRequest(frameIn);
    }

    private UsbRequest newRequest(UsbEndpoint ep) throws IOException {
        UsbRequest r = new UsbRequest();
        if (!r.initialize(connection, ep)) throw new IOException("UsbRequest.initialize failed, EP " + ep.getAddress());
        return r;
    }

    /**
     * SET_INTERFACE(ifNum, alt). 가능하면 {@link UsbDeviceConnection#setInterface} 를 사용합니다:
     * 버스에는 원본과 같은 표준 요청(0x01, 11, alt, ifNum)이 나가고, 커널도 alt 1 의 엔드포인트를
     * 활성화합니다. 원본처럼 controlTransfer 로 직접 보내면 커널이 alt 변경을 모르므로
     * EP 0x83/0x85 에 대한 UsbRequest.queue 가 실패합니다(실기기에서 확인).
     */
    private void setInterface(int ifNum, boolean on) throws IOException {
        UsbInterface[] alts = altSettings.get(ifNum);
        UsbInterface target = alts == null ? null : alts[on ? 1 : 0];
        if (target != null && connection.setInterface(target)) return;
        Log.w(TAG, "setInterface(if=" + ifNum + ", alt=" + (on ? 1 : 0) + ") 실패 → controlTransfer 로 재시도");
        int r = connection.controlTransfer(REQ_TYPE_STANDARD_INTERFACE_OUT, REQ_SET_INTERFACE,
                on ? 1 : 0, ifNum, null, 0, CTRL_TIMEOUT_MS);
        if (r < 0) throw new IOException("SET_INTERFACE(if=" + ifNum + ", alt=" + (on ? 1 : 0) + ") 실패: " + r);
    }

    private static void queue(UsbRequest req, ByteBuffer buf) throws IOException {
        buf.clear();
        if (!req.queue(buf)) {
            UsbEndpoint ep = req.getEndpoint();
            throw new IOException("UsbRequest.queue failed (EP 0x" + (ep == null ? "?" : Integer.toHexString(ep.getAddress())) + ")");
        }
    }

    private void pollLoop() {
        String stopReason = null;
        int nullCount = 0;
        while (running) {
            UsbRequest done;
            try {
                done = connection.requestWait(500);
            } catch (TimeoutException e) {
                tick();
                continue;
            } catch (Exception e) {
                if (running) stopReason = "USB receive error: " + e;
                break;
            }
            if (!running) break;
            if (done == null) {
                // 원본도 null 응답 100회 연속이면 BAD_STATE 처리
                if (++nullCount > 100) {
                    stopReason = "USB device not responding (unplugged?)";
                    break;
                }
                SystemClock.sleep(5);
                continue;
            }
            nullCount = 0;
            try {
                if (done == frameReq) {
                    int n = frameBuf.position();
                    if (DEBUG_LOG && n > 0 && frameBytes == 0) dump("FRAME 첫 수신", frameBuf, n);
                    frameBytes += n;
                    assembler.feed(frameBuf.array(), 0, n);
                    queue(frameReq, frameBuf);
                } else if (done == configReq) {
                    int n = configBuf.position();
                    if (DEBUG_LOG) dump("CONFIG(EP 0x81)", configBuf, n);
                    if (n > 0) {
                        int b0 = configBuf.array()[0] & 0xFF, b1 = configBuf.array()[1] & 0xFF;
                        boolean iap = b0 == 0xFF && (b1 == 0x55 || b1 == 0x5A);
                        configSeen = true;
                        if (iap || iap2 != null) {
                            // 아이폰용 카메라: CONFIG 인터페이스가 iAP2 링크로 동작
                            if (iap2 == null) {
                                iap2 = new Iap2Host(this::sendConfig, iapListener);
                                iap2.sessionPlan = readSessionPlan();
                                Log.i(TAG, "iAP2 모드, 세션 계획: " + iap2.sessionPlan);
                            }
                            iap2.onData(configBuf.array(), n);
                        } else {
                            callback.onConfigData(configBuf.array(), n);
                        }
                    }
                    queue(configReq, configBuf);
                } else if (done == fileioReq) {
                    int n = fileioBuf.position();
                    if (n > 0) {
                        if (DEBUG_LOG) {
                            dump("FILEIO(EP 0x83)", fileioBuf, n);
                            appendDump("fileio.bin", fileioBuf.array(), 0, n);
                        }
                        try {
                            java.util.Map<String, byte[]> files = cameraFiles.feed(fileioBuf.array(), 0, n);
                            if (files != null) {
                                Log.i(TAG, "CameraFiles.zip 수신: " + files.keySet());
                                if (openStreamId >= 0) {
                                    // 다음 연결에서도 다시 열 수 있도록 스트림을 닫습니다.
                                    sendConfigJson("{\"type\":\"closeFile\",\"data\":{\"streamIdentifier\":" + openStreamId + "}}");
                                    openStreamId = -1;
                                }
                                callback.onCameraFiles(files);
                            }
                        } catch (IOException e) {
                            Log.w(TAG, "CameraFiles.zip 해제 실패", e);
                        }
                    }
                    queue(fileioReq, fileioBuf);
                }
            } catch (IOException e) {
                if (running) stopReason = e.getMessage();
                break;
            }
            tick();
        }
        boolean wasRunning = running;
        if (wasRunning || stopReason != null) {
            Log.w(TAG, "poll loop ended: " + stopReason);
            new Thread(this::stop, "FlirOne-stop").start();
            callback.onStopped(stopReason != null ? stopReason : "stopped");
        }
    }

    /** 진단용 EA 세션 계획 파일(쉼표 구분 프로토콜 이름). 없으면 전체 시작. */
    public static volatile java.io.File debugPlanFile;

    private static List<String> readSessionPlan() {
        // 실기기 검증 결과: config 세션만 열면 FRAME(EP 0x85) 스트리밍이 시작됩니다.
        // (config+fileio+frame 을 한꺼번에 열면 카메라가 즉시 재부팅)
        List<String> all = java.util.Collections.singletonList("com.flir.rosebud.config");
        java.io.File f = debugPlanFile;
        if (f == null || !f.exists()) return all;
        try {
            String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (t.equals("none")) return null;
            List<String> out = new ArrayList<>();
            for (String x : t.split(",")) if (!x.trim().isEmpty()) out.add(x.trim());
            return out;
        } catch (IOException e) {
            return all;
        }
    }

    private long startedAt;
    private volatile boolean configSeen;
    private boolean rstProbeSent, androidMode;

    /** 폴링 루프 주기 작업 (데이터 수신 / requestWait 타임아웃 양쪽에서 호출). */
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        if (!configSeen && iap2 == null && !androidMode) {
            if (!rstProbeSent && now - startedAt > 1500) {
                // 이전 앱 인스턴스가 맺은 iAP2 링크가 남아 카메라가 조용할 수 있음 → 링크 RST 로 재동기화 유도
                rstProbeSent = true;
                Log.i(TAG, "CONFIG 무응답 → iAP2 링크 RST 프로브 전송");
                sendConfig(Iap2Host.buildPacket(0x10, 0, 0, 0, null));
            } else if (rstProbeSent && now - startedAt > 4000) {
                androidMode = true; // iAP2 응답 없음 → 안드로이드용 카메라로 판단
                Log.i(TAG, "iAP2 응답 없음 → 안드로이드용 카메라로 간주, EP 0x02 로 보정 파일 요청");
                calibRequestAt = now;
            }
        }
        checkWatchdog();
        maybeRequestCalibration();
        if (now - statsAt >= 5000) {
            Log.i(TAG, String.format(java.util.Locale.US,
                    "통계 5s: 프레임 %d개, FRAME %d KB, CRC불일치 %d, 버림 %d B",
                    assembler.getFramesEmitted() - statsFrames, (frameBytes - statsBytes) / 1024,
                    assembler.getCrcMismatches(), assembler.getBytesDiscarded()));
            statsAt = now;
            statsFrames = assembler.getFramesEmitted();
            statsBytes = frameBytes;
        }
    }

    private void checkWatchdog() {
        if (!running || iap2 != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameAt < FRAME_WATCHDOG_MS || frameRestarts >= MAX_FRAME_RESTARTS) return;
        frameRestarts++;
        lastFrameAt = now;
        Log.i(TAG, "프레임 없음 → FRAME 채널 재시작 #" + frameRestarts);
        synchronized (this) {
            if (connection == null) return;
            try {
                // 대기 중인 FRAME 요청은 alt 0 전환 시 취소되어 돌아오고, pollLoop 가 다시 큐잉합니다.
                cancelQuietly(frameReq);
                setInterface(IF_FRAME, false);
                setInterface(IF_FRAME, true);
                assembler.reset();
            } catch (IOException e) {
                Log.w(TAG, "FRAME 재시작 실패", e);
            }
        }
    }

    private Iap2Host iap2;

    private final Iap2Host.Listener iapListener = new Iap2Host.Listener() {
        @Override
        public void onEaSessionsStarted(List<Iap2Host.EaProtocol> protocols) {
            Log.i(TAG, "iAP2 EA 세션 시작됨 " + protocols);
            lastFrameAt = SystemClock.elapsedRealtime();
            calibRequestAt = lastFrameAt + 1500;
        }

        @Override
        public void onEaData(int eaSessionId, byte[] data, int off, int len) {
            if (DEBUG_LOG) {
                Log.i(TAG, "EA 데이터 session=" + eaSessionId + " len=" + len + " hex="
                        + Iap2Host.hex(data, off, Math.min(len, 64)));
            }
            if (len > 0 && data[off] == '{') {
                // config 프로토콜 JSON (sledInformation, batteryVoltageUpdate 등)
                byte[] copy = new byte[len];
                System.arraycopy(data, off, copy, 0, len);
                String json = new String(copy, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!json.contains("battery")) Log.i(TAG, "CONFIG JSON: " + json);
                onConfigJson(json);
                callback.onConfigData(copy, len);
            } else if (len >= 4 && (data[off] & 0xFF) == 0xEF && (data[off + 1] & 0xFF) == 0xBE) {
                // 혹시 프레임이 iAP2 링크로 오는 경우 대비
                assembler.feed(data, off, len);
            }
        }
    };

    // ------------------------------------------------------------ config 명령 (RBProtocol)

    /** 진단/덤프 파일 저장 위치 (MainActivity 가 설정). */
    public static volatile java.io.File dumpDir;
    private long calibRequestAt;
    private int openStreamId = -1;

    /**
     * config 채널로 JSON 명령을 보냅니다. 헤더 16바이트(실기기 패킷으로 검증):
     * u32 0x1CC | u32 1 | u32 payloadLen | u32 CRC32(헤더 앞 12바이트), 이어서 JSON + NUL.
     */
    public synchronized boolean sendConfigJson(String json) {
        byte[] payload = (json + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x1CC).putInt(1).putInt(payload.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(h.array(), 0, 12);
        h.putInt((int) crc.getValue());
        Log.i(TAG, "CONFIG 송신: " + json);
        if (iap2 != null) {
            int sid = iap2.sessionIdFor("com.flir.rosebud.config");
            if (sid < 0) return false;
            iap2.sendEaData(sid, h.array());
            iap2.sendEaData(sid, payload);
            return true;
        }
        return sendConfig(h.array()) && sendConfig(payload);
    }

    private void maybeRequestCalibration() {
        if (calibRequestAt == 0 || SystemClock.elapsedRealtime() < calibRequestAt) return;
        calibRequestAt = 0;
        sendConfigJson("{\"type\":\"openFile\",\"data\":{\"mode\":\"r\",\"path\":\"CameraFiles.zip\"}}");
    }

    private void onConfigJson(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            if ("openFileStatus".equals(o.optString("type"))) {
                org.json.JSONObject d = o.optJSONObject("data");
                int id = d == null ? -1 : d.optInt("streamIdentifier", d.optInt("fd", -1));
                Log.i(TAG, "openFileStatus → streamIdentifier=" + id);
                if (id < 0) {
                    // 예: -3 = 이전 앱 인스턴스가 연 스트림이 닫히지 않음(카메라 재부팅 전까지 유지) → 저장된 보정값 사용
                    Log.w(TAG, "CameraFiles.zip 열기 실패(" + id + ") → 저장된 보정값을 사용합니다");
                }
                if (id >= 0) {
                    openStreamId = id;
                    sendConfigJson("{\"type\":\"readFile\",\"data\":{\"streamIdentifier\":" + id + "}}");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "config JSON 파싱 실패: " + e);
        }
    }

    private static void appendDump(String name, byte[] b, int off, int n) {
        java.io.File dir = dumpDir;
        if (dir == null) return;
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(dir, name), true)) {
            fo.write(b, off, n);
        } catch (IOException ignored) {
        }
    }

    /** EA 세션 시작 후 네이티브 전송 인터페이스(1,2)를 alt0→alt1 로 다시 켭니다. 폴링 스레드에서 호출. */
    private void restartNativeTransports() {
        synchronized (this) {
            if (connection == null) return;
            cancelQuietly(fileioReq);
            cancelQuietly(frameReq);
            try {
                setInterface(IF_FILEIO, false);
                setInterface(IF_FRAME, false);
                setInterface(IF_FILEIO, true);
                setInterface(IF_FRAME, true);
                assembler.reset();
                lastFrameAt = SystemClock.elapsedRealtime();
            } catch (IOException e) {
                Log.w(TAG, "네이티브 전송 재활성화 실패", e);
            }
        }
    }

    /** iAP2 Detect 시퀀스 (아이폰용 FLIR ONE 이 EP 0x81 로 1초마다 전송하는 것을 실기기에서 관찰). */
    private static final byte[] IAP2_DETECT = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};

    private static boolean startsWith(byte[] a, byte[] p) {
        if (a.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (a[i] != p[i]) return false;
        return true;
    }

    /** 진단용: 엔드포인트 수신 데이터를 로그로 남깁니다(앞 96바이트). */
    private static void dump(String what, ByteBuffer b, int n) {
        StringBuilder sb = new StringBuilder();
        byte[] a = b.array();
        for (int i = 0; i < Math.min(n, 96); i++) sb.append(String.format("%02X", a[i] & 0xFF));
        StringBuilder txt = new StringBuilder();
        for (int i = 0; i < Math.min(n, 96); i++) {
            int c = a[i] & 0xFF;
            txt.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        Log.i(TAG, what + " len=" + n + " hex=" + sb + " txt=" + txt);
    }

    public long getFrameBytes() {
        return frameBytes;
    }

    private static void cancelQuietly(UsbRequest r) {
        if (r == null) return;
        try { r.cancel(); } catch (Exception ignored) { }
    }

    private void releaseAll() {
        if (connection != null) {
            for (UsbInterface i : claimed) {
                try { connection.releaseInterface(i); } catch (Exception ignored) { }
            }
        }
        claimed.clear();
        closeQuietly(configReq);
        closeQuietly(fileioReq);
        closeQuietly(frameReq);
        configReq = fileioReq = frameReq = null;
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private static void closeQuietly(UsbRequest r) {
        if (r == null) return;
        try { r.close(); } catch (Exception ignored) { }
    }
}
