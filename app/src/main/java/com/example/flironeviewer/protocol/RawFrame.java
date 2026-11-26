package com.example.flironeviewer.protocol;

/**
 * FRAME 엔드포인트(EP 0x85)에서 조립된 프레임 1개.
 *
 * <p>헤더 레이아웃(28바이트, 리틀엔디언)은 libatlas_native.so 의
 * {@code RBProtocol_framePacketParser_writeFrameToOutputBuffer} 역어셈블 결과에서 확인했습니다.
 * <pre>
 *  off  size  내용
 *   0    4    매직 0x0000BEEF  (바이트: EF BE 00 00)
 *   4    4    시퀀스/버전 필드 (파서 상태 구조체 +0xB4 에서 복사)
 *   8    4    페이로드 전체 크기 = thermal + jpeg + status
 *  12    4    열화상(raw) 데이터 크기
 *  16    4    실화상 JPEG 크기
 *  20    4    상태 JSON 크기 (NUL 포함)
 *  24    4    헤더 앞 24바이트의 CRC32 (zlib crc32)
 *  28    ...  thermal | jpeg | status JSON
 * </pre>
 */
public final class RawFrame {
    public final int sequence;
    public final byte[] thermal;
    public final byte[] jpeg;
    public final String statusJson;
    public final boolean headerCrcValid;

    public RawFrame(int sequence, byte[] thermal, byte[] jpeg, String statusJson, boolean headerCrcValid) {
        this.sequence = sequence;
        this.thermal = thermal;
        this.jpeg = jpeg;
        this.statusJson = statusJson;
        this.headerCrcValid = headerCrcValid;
    }
}
