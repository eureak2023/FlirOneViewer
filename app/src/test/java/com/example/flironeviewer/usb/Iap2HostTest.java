package com.example.flironeviewer.usb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 실기기(아이폰용 FLIR ONE)에서 캡처한 iAP2 링크 패킷으로 구현을 검증합니다. */
public class Iap2HostTest {

    @Test
    public void ackPacketMatchesCapturedBytes() {
        // 카메라가 보낸 ACK: FF5A000940742B00BF
        byte[] p = Iap2Host.buildPacket(0x40, 0x74, 0x2B, 0, null);
        assertEquals("FF5A000940742B00BF", Iap2Host.hex(p, 0, p.length));
    }

    @Test
    public void synPacketChecksumsMatchCapturedBytes() {
        // 카메라가 보낸 SYN: FF5A001A8074000099 | 017FFFFF07D001F41E0101000102020190
        byte[] payload = {0x01, 0x7F, (byte) 0xFF, (byte) 0xFF, 0x07, (byte) 0xD0, 0x01, (byte) 0xF4,
                0x1E, 0x01, 0x01, 0x00, 0x01, 0x02, 0x02, 0x01};
        byte[] p = Iap2Host.buildPacket(0x80, 0x74, 0x00, 0, payload);
        assertEquals("FF5A001A8074000099017FFFFF07D001F41E0101000102020190", Iap2Host.hex(p, 0, p.length));
    }
}
