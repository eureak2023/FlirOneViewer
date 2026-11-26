package com.example.flironeviewer.usb;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 아이폰(Lightning)용 FLIR ONE 을 위한 최소 iAP2 "Apple 기기(호스트) 측" 구현.
 *
 * <p>실기기 관찰: 아이폰용 카메라는 EP 0x81 로 iAP2 Detect(FF 55 02 00 EE 10)를 1초마다 보내고,
 * 응답이 없으면 약 5초 뒤 스스로 재부팅합니다. Detect 에 같은 바이트로 응답하면 링크 SYN
 * (FF 5A …, 세션: 1=Control, 2=External Accessory)을 보냅니다. 이 클래스는 그 이후 과정을 처리합니다.
 * <ol>
 *   <li>링크 동기화: SYN → SYN+ACK → ACK</li>
 *   <li>인증: RequestAuthenticationCertificate(0xAA00) → 인증서 수신(0xAA01) → AuthenticationSucceeded(0xAA05)
 *       — 우리가 검증하는 쪽(호스트)이므로 인증서를 받기만 하고 수락합니다.</li>
 *   <li>식별: StartIdentification(0x1D00) → IdentificationInformation(0x1D01) → IdentificationAccepted(0x1D02)</li>
 *   <li>EA 세션 시작: StartExternalAccessoryProtocolSession(0xEA00) — 식별 정보에 선언된 모든 프로토콜</li>
 * </ol>
 */
final class Iap2Host {

    private static final String TAG = "Iap2Host";

    static final byte[] DETECT = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};

    private static final int CTRL_SYN = 0x80, CTRL_ACK = 0x40, CTRL_EAK = 0x20, CTRL_RST = 0x10;

    // 컨트롤 세션 메시지 ID
    static final int MSG_REQ_AUTH_CERT = 0xAA00;
    static final int MSG_AUTH_CERT = 0xAA01;
    static final int MSG_REQ_AUTH_CHALLENGE = 0xAA02;
    static final int MSG_AUTH_RESPONSE = 0xAA03;
    static final int MSG_AUTH_FAILED = 0xAA04;
    static final int MSG_AUTH_SUCCEEDED = 0xAA05;
    static final int MSG_START_IDENT = 0x1D00;
    static final int MSG_IDENT_INFO = 0x1D01;
    static final int MSG_IDENT_ACCEPTED = 0x1D02;
    static final int MSG_IDENT_REJECTED = 0x1D03;
    static final int MSG_START_EA_SESSION = 0xEA00;
    static final int MSG_STOP_EA_SESSION = 0xEA01;

    interface Transport {
        boolean send(byte[] packet);
    }

    interface Listener {
        /** EA 프로토콜 세션들을 시작했을 때. names 는 식별 정보에서 얻은 프로토콜 이름. */
        void onEaSessionsStarted(List<EaProtocol> protocols);

        /** 링크 세션(EA 타입)으로 들어온 데이터. eaSessionId 는 앞 2바이트. */
        void onEaData(int eaSessionId, byte[] data, int off, int len);
    }

    static final class EaProtocol {
        int id = -1;
        String name = "";
        int nativeTransportComponent = -1;
        int sessionId;

        @Override
        public String toString() {
            return String.format(Locale.US, "EA[id=%d name=%s native=%d session=%d]", id, name, nativeTransportComponent, sessionId);
        }
    }

    private final Transport transport;
    private final Listener listener;
    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();

    private boolean linkUp;
    private boolean synAckSent;
    private long lastRstAt;
    private int ourSeq = 0x2B;
    private int lastRxSeq = -1;
    private int controlSessionId = 1;
    private int eaLinkSessionId = 2;
    private final List<EaProtocol> protocols = new ArrayList<>();
    /** 시작할 EA 프로토콜 이름 목록(null 이면 시작하지 않음 — 진단용). */
    List<String> sessionPlan = null;

    Iap2Host(Transport transport, Listener listener) {
        this.transport = transport;
        this.listener = listener;
    }

    /** EP 0x81 로 받은 바이트를 넣습니다. 처리했으면 true. */
    boolean onData(byte[] data, int len) {
        if (len == DETECT.length && startsWith(data, DETECT)) {
            Log.i(TAG, "Detect 수신 → 응답");
            linkUp = false;
            synAckSent = false;
            lastRxSeq = -1;
            rx.reset();
            transport.send(DETECT);
            return true;
        }
        rx.write(data, 0, len);
        byte[] b = rx.toByteArray();
        int pos = 0;
        while (true) {
            // 0xFF 0x5A 시작 바이트 찾기
            while (pos + 1 < b.length && !((b[pos] & 0xFF) == 0xFF && (b[pos + 1] & 0xFF) == 0x5A)) pos++;
            if (b.length - pos < 9) break;
            int plen = u16(b, pos + 2);
            if (plen < 9 || cs(b, pos, 8) != (b[pos + 8] & 0xFF)) {
                pos++;
                continue;
            }
            if (b.length - pos < plen) break;
            handlePacket(b, pos, plen);
            pos += plen;
        }
        rx.reset();
        if (pos < b.length) rx.write(b, pos, b.length - pos);
        return true;
    }

    private void handlePacket(byte[] b, int p, int plen) {
        int ctrl = b[p + 4] & 0xFF;
        int seq = b[p + 5] & 0xFF;
        int ack = b[p + 6] & 0xFF;
        int session = b[p + 7] & 0xFF;
        int payloadLen = plen > 9 ? plen - 10 : 0;
        int payloadOff = p + 9;
        if (payloadLen > 0 && cs(b, payloadOff, payloadLen) != (b[payloadOff + payloadLen] & 0xFF)) {
            Log.w(TAG, "payload checksum 불일치, 무시");
            return;
        }

        if ((ctrl & CTRL_SYN) != 0) {
            Log.i(TAG, String.format(Locale.US, "링크 SYN 수신 seq=%02X → SYN+ACK", seq));
            parseSyncSessions(b, payloadOff, payloadLen);
            lastRxSeq = seq;
            linkUp = false;
            // 받은 동기화 파라미터를 그대로 돌려주며 수락
            byte[] payload = new byte[payloadLen];
            System.arraycopy(b, payloadOff, payload, 0, payloadLen);
            transport.send(buildPacket(CTRL_SYN | CTRL_ACK, ourSeq, lastRxSeq, 0, payload));
            synAckSent = true;
            return;
        }
        if ((ctrl & CTRL_RST) != 0) {
            Log.w(TAG, "링크 RST 수신");
            linkUp = false;
            synAckSent = false;
            return;
        }
        if (!synAckSent) {
            // 앱 재시작 등으로 이전 인스턴스가 맺은 링크가 남아 있음 → RST 로 카메라가 SYN 부터 다시 하게 함
            long now = System.currentTimeMillis();
            if (now - lastRstAt > 1000) {
                lastRstAt = now;
                Log.i(TAG, "동기화되지 않은 링크 패킷 수신 → RST 전송");
                transport.send(buildPacket(CTRL_RST, ourSeq, Math.max(seq, 0), 0, null));
            }
            return;
        }

        if ((ctrl & CTRL_ACK) != 0 && !linkUp && ack == ourSeq) {
            linkUp = true;
            Log.i(TAG, "링크 동기화 완료 → 인증서 요청");
            sendControl(MSG_REQ_AUTH_CERT, null);
        }

        if (payloadLen == 0) return; // 순수 ACK

        lastRxSeq = seq;
        sendAck();
        if (session == controlSessionId) {
            handleControl(b, payloadOff, payloadLen);
        } else if (session == eaLinkSessionId && payloadLen >= 2) {
            int eaId = u16(b, payloadOff);
            listener.onEaData(eaId, b, payloadOff + 2, payloadLen - 2);
        } else {
            Log.i(TAG, "알 수 없는 세션 " + session + " 데이터 " + payloadLen + "B");
        }
    }

    private void parseSyncSessions(byte[] b, int off, int len) {
        // 버전1, 최대 미확인, 최대 길이(2), 재전송 타임아웃(2), 누적 ACK 타임아웃(2), 최대 재전송, 최대 누적 ACK, [세션 id,type,ver]*
        for (int i = off + 10; i + 2 < off + len; i += 3) {
            int id = b[i] & 0xFF, type = b[i + 1] & 0xFF;
            if (type == 0) controlSessionId = id;
            else if (type == 2) eaLinkSessionId = id;
            Log.i(TAG, "  링크 세션 id=" + id + " type=" + type + " ver=" + (b[i + 2] & 0xFF));
        }
    }

    private void handleControl(byte[] b, int off, int len) {
        if (len < 6 || (b[off] & 0xFF) != 0x40 || (b[off + 1] & 0xFF) != 0x40) {
            Log.w(TAG, "컨트롤 메시지 형식 아님: " + hex(b, off, Math.min(len, 32)));
            return;
        }
        int mlen = u16(b, off + 2);
        int msgId = u16(b, off + 4);
        Log.i(TAG, String.format(Locale.US, "컨트롤 메시지 수신 0x%04X (%dB)", msgId, mlen));
        switch (msgId) {
            case MSG_AUTH_CERT:
                Log.i(TAG, "인증서 수신 → AuthenticationSucceeded (호스트 측 검증 생략)");
                sendControl(MSG_AUTH_SUCCEEDED, null);
                sendControl(MSG_START_IDENT, null);
                break;
            case MSG_AUTH_RESPONSE:
                sendControl(MSG_AUTH_SUCCEEDED, null);
                sendControl(MSG_START_IDENT, null);
                break;
            case MSG_IDENT_INFO: {
                int n = Math.min(len, mlen);
                for (int i = 0; i < n; i += 64) {
                    Log.i(TAG, String.format(Locale.US, "  IDENT[%03d] %s", i, hex(b, off + i, Math.min(64, n - i))));
                }
                parseIdentification(b, off + 6, n - 6);
                sendControl(MSG_IDENT_ACCEPTED, null);
                if (sessionPlan != null) startEaSessions();
                else Log.i(TAG, "진단 모드: EA 세션 시작 안 함");
                break;
            }
            default:
                Log.i(TAG, "  처리하지 않는 메시지: " + hex(b, off, Math.min(len, 64)));
        }
    }

    private void parseIdentification(byte[] b, int off, int len) {
        protocols.clear();
        int end = off + len;
        int p = off;
        while (p + 4 <= end) {
            int plen = u16(b, p), pid = u16(b, p + 2);
            if (plen < 4 || p + plen > end) break;
            if (pid == 10) { // SupportedExternalAccessoryProtocol (그룹)
                EaProtocol ea = new EaProtocol();
                int q = p + 4;
                while (q + 4 <= p + plen) {
                    int sl = u16(b, q), sid = u16(b, q + 2);
                    if (sl < 4) break;
                    int d = q + 4, dl = sl - 4;
                    if (sid == 0 && dl >= 1) ea.id = b[d] & 0xFF;
                    else if (sid == 1) ea.name = cstr(b, d, dl);
                    else if (sid == 3 && dl >= 2) ea.nativeTransportComponent = u16(b, d);
                    q += sl;
                }
                protocols.add(ea);
                Log.i(TAG, "  식별: " + ea);
            } else if (pid <= 9 && pid != 2 && pid != 3 && pid != 4) {
                Log.i(TAG, "  식별 파라미터 " + pid + " = " + cstr(b, p + 4, plen - 4));
            } else {
                Log.i(TAG, "  식별 파라미터 " + pid + " (" + (plen - 4) + "B) " + hex(b, p + 4, Math.min(plen - 4, 24)));
            }
            p += plen;
        }
    }

    private void startEaSessions() {
        int sid = 1;
        for (EaProtocol ea : protocols) {
            if (ea.id < 0 || !sessionPlan.contains(ea.name)) continue;
            ea.sessionId = sid++;
            ByteArrayOutputStream params = new ByteArrayOutputStream();
            writeParam(params, 0, new byte[]{(byte) ea.id});
            writeParam(params, 1, new byte[]{(byte) (ea.sessionId >> 8), (byte) ea.sessionId});
            Log.i(TAG, "EA 세션 시작 요청: " + ea);
            sendControl(MSG_START_EA_SESSION, params.toByteArray());
        }
        listener.onEaSessionsStarted(new ArrayList<>(protocols));
    }

    // ------------------------------------------------------------------ 송신

    private void sendControl(int msgId, byte[] params) {
        int plen = params == null ? 0 : params.length;
        byte[] m = new byte[6 + plen];
        m[0] = 0x40;
        m[1] = 0x40;
        m[2] = (byte) (m.length >> 8);
        m[3] = (byte) m.length;
        m[4] = (byte) (msgId >> 8);
        m[5] = (byte) msgId;
        if (plen > 0) System.arraycopy(params, 0, m, 6, plen);
        ourSeq = (ourSeq + 1) & 0xFF;
        Log.i(TAG, String.format(Locale.US, "컨트롤 메시지 송신 0x%04X seq=%02X", msgId, ourSeq));
        transport.send(buildPacket(CTRL_ACK, ourSeq, Math.max(lastRxSeq, 0), controlSessionId, m));
    }

    /** EA 링크 세션으로 데이터 송신(앞에 EA 세션 ID 2바이트). */
    /** 시작된 EA 세션 중 이름이 일치하는 세션 ID (없으면 -1). */
    int sessionIdFor(String protocolName) {
        for (EaProtocol p : protocols) if (p.name.equals(protocolName) && p.sessionId > 0) return p.sessionId;
        return -1;
    }

    void sendEaData(int eaSessionId, byte[] data) {
        byte[] payload = new byte[2 + data.length];
        payload[0] = (byte) (eaSessionId >> 8);
        payload[1] = (byte) eaSessionId;
        System.arraycopy(data, 0, payload, 2, data.length);
        ourSeq = (ourSeq + 1) & 0xFF;
        transport.send(buildPacket(CTRL_ACK, ourSeq, Math.max(lastRxSeq, 0), eaLinkSessionId, payload));
    }

    private void sendAck() {
        transport.send(buildPacket(CTRL_ACK, ourSeq, lastRxSeq, 0, null));
    }

    static byte[] buildPacket(int ctrl, int seq, int ack, int session, byte[] payload) {
        int pl = payload == null ? 0 : payload.length;
        int total = 9 + (pl > 0 ? pl + 1 : 0);
        byte[] p = new byte[total];
        p[0] = (byte) 0xFF;
        p[1] = 0x5A;
        p[2] = (byte) (total >> 8);
        p[3] = (byte) total;
        p[4] = (byte) ctrl;
        p[5] = (byte) seq;
        p[6] = (byte) ack;
        p[7] = (byte) session;
        p[8] = (byte) cs(p, 0, 8);
        if (pl > 0) {
            System.arraycopy(payload, 0, p, 9, pl);
            p[9 + pl] = (byte) cs(p, 9, pl);
        }
        return p;
    }

    private static void writeParam(ByteArrayOutputStream o, int id, byte[] data) {
        int l = 4 + data.length;
        o.write(l >> 8);
        o.write(l);
        o.write(id >> 8);
        o.write(id);
        o.write(data, 0, data.length);
    }

    // ------------------------------------------------------------------ 유틸

    /** iAP2 체크섬: 합계가 0(mod 256)이 되게 하는 값. */
    static int cs(byte[] b, int off, int len) {
        int s = 0;
        for (int i = 0; i < len; i++) s += b[off + i] & 0xFF;
        return (0x100 - (s & 0xFF)) & 0xFF;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    private static String cstr(byte[] b, int off, int len) {
        int e = off;
        while (e < off + len && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.UTF_8);
    }

    private static boolean startsWith(byte[] a, byte[] p) {
        if (a.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (a[i] != p[i]) return false;
        return true;
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format("%02X", b[off + i] & 0xFF));
        return sb.toString();
    }
}
