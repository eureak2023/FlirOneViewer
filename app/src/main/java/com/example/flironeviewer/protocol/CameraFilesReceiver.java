package com.example.flironeviewer.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FILEIO 채널(EP 0x83)로 들어오는 readFile 응답을 모아 ZIP(CameraFiles.zip)을 풉니다.
 *
 * <p>실기기 캡처로 확인한 응답 헤더(24바이트, 리틀엔디언):
 * <pre>
 *  u32 0x5510 | u32 1 | u32 데이터 길이 | u32 streamIdentifier | u32 ? | u32 ?   → 이어서 데이터
 * </pre>
 */
public final class CameraFilesReceiver {

    public static final int HEADER_SIZE = 24;
    private static final int MAGIC = 0x5510;

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private boolean done;

    /** 데이터를 넣고, ZIP 이 완성되면 파일 이름→내용 맵을 돌려줍니다(그 전에는 null). */
    public Map<String, byte[]> feed(byte[] data, int off, int len) throws IOException {
        if (done) return null;
        buf.write(data, off, len);
        byte[] b = buf.toByteArray();
        if (b.length < HEADER_SIZE) return null;
        if (le32(b, 0) != MAGIC) {
            buf.reset(); // 알 수 없는 데이터 → 버림
            return null;
        }
        int size = le32(b, 8);
        if (size <= 0 || size > 16 * 1024 * 1024) {
            buf.reset();
            return null;
        }
        if (b.length < HEADER_SIZE + size) return null;
        done = true;
        return unzip(b, HEADER_SIZE, size);
    }

    /**
     * ZIP 을 메모리에서 직접 풉니다(중앙 디렉터리 파싱, stored/deflate).
     * 카메라의 ZIP 에는 "/CameraFiles/..." 같은 절대 경로 항목이 있어, Android 14+ 의
     * ZipInputStream 은 경로 검증으로 예외를 던집니다. 디스크에 쓰지 않으므로 직접 파싱합니다.
     */
    public static Map<String, byte[]> unzip(byte[] b, int off, int len) throws IOException {
        int end = off + len;
        int eocd = -1;
        for (int i = end - 22; i >= Math.max(off, end - 22 - 65535); i--) {
            if (le32(b, i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("ZIP EOCD 없음");
        int count = le16(b, eocd + 10);
        int cd = off + le32(b, eocd + 16);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int k = 0; k < count; k++) {
            if (cd + 46 > end || le32(b, cd) != 0x02014b50) throw new IOException("ZIP 중앙 디렉터리 손상");
            int method = le16(b, cd + 10);
            int csize = le32(b, cd + 20), usize = le32(b, cd + 24);
            int nlen = le16(b, cd + 28), elen = le16(b, cd + 30), clen = le16(b, cd + 32);
            int lho = off + le32(b, cd + 42);
            String name = new String(b, cd + 46, nlen, java.nio.charset.StandardCharsets.UTF_8);
            cd += 46 + nlen + elen + clen;
            if (le32(b, lho) != 0x04034b50) throw new IOException("ZIP 로컬 헤더 손상: " + name);
            int data = lho + 30 + le16(b, lho + 26) + le16(b, lho + 28);
            if (data + csize > end || csize < 0 || usize < 0) throw new IOException("ZIP 범위 오류: " + name);
            byte[] content;
            if (method == 0) {
                content = java.util.Arrays.copyOfRange(b, data, data + csize);
            } else if (method == 8) {
                java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
                try {
                    inf.setInput(b, data, csize);
                    content = new byte[usize];
                    int got = 0;
                    while (got < usize && !inf.finished()) {
                        int n = inf.inflate(content, got, usize - got);
                        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                        got += n;
                    }
                } catch (java.util.zip.DataFormatException e) {
                    throw new IOException("ZIP 압축 해제 실패: " + name, e);
                } finally {
                    inf.end();
                }
            } else {
                continue; // 지원하지 않는 압축 방식은 건너뜀
            }
            while (name.startsWith("/")) name = name.substring(1);
            if (!out.containsKey(name)) out.put(name, content);
        }
        return out;
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24;
    }
}
