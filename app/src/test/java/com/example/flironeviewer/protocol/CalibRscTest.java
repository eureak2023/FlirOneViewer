package com.example.flironeviewer.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class CalibRscTest {

    /** 실기기 calib.rsc 와 같은 형식(일부 키만, 값은 실기기에서 관찰한 값). */
    static final String RSC = "#\r\n.calib entry\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_le.B double 1435\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_le.J0 double 2439.776\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_le.J1 double 80\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_le.R double 17860.9\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_le.type int32 0\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_leExFOL2.B double 1423.9\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_leExFOL2.F double 1\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_leExFOL2.J0 double 1301\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_leExFOL2.J1 double 82.397461\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_leExFOL2.R double 16331.195\r\n"
            + ".calib.extRBF.ds120C_weRB_apNOA_fiNOF_leExFOL2.type int32 0\r\n"
            + ".calib.extRBF.ds400C_weRB_apNOA_fiNOF_le.R double 6029.65\r\n"
            + ".calib.extRBF.ds400C_weRB_apNOA_fiNOF_le.B double 1530\r\n"
            + ".calib.general.fixedLensID text \"leExFOL2\"\r\n"
            + ".calib.visual.fusion.leExFOL2.C0X double -5.0744071\r\n"
            + ".calib.visual.fusion.leExFOL2.C0Y double -0\r\n"
            + ".calib.visual.fusion.leExFOL2.C1X double -3.3491952\r\n"
            + ".calib.visual.fusion.leExFOL2.C1Y double -16.948754\r\n"
            + ".calib.visual.fusion.leExFOL2.fixFusionDistance double 3\r\n"
            + ".calib.visual.fusion.leExFOL2.magnQuote double 1.2515709\r\n";

    @Test
    public void picksSetMatchingFixedLens() {
        CalibRsc c = CalibRsc.parse(RSC);
        assertNotNull(c);
        assertEquals("ds120C_weRB_apNOA_fiNOF_leExFOL2", c.id);
        assertEquals(16331.195, c.r, 1e-9);
        assertEquals(1423.9, c.b, 1e-9);
        assertEquals(1301, c.j0, 1e-9);
        assertEquals(82.397461, c.j1, 1e-9);
        assertNotNull(c.fusion);
        assertEquals(1.2515709, c.fusion[0], 1e-9);
        assertEquals(-16.948754, c.fusion[4], 1e-9);
    }

    @Test
    public void receiverReassemblesZipFromChunks() throws Exception {
        ByteArrayOutputStream zbytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zbytes)) {
            z.putNextEntry(new ZipEntry("CameraFiles/system/calib.rsc"));
            z.write(RSC.getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
            // 실기기 ZIP 처럼 절대 경로 항목도 포함
            z.putNextEntry(new ZipEntry("/CameraFiles/system/maps/x.fff"));
            z.write(new byte[]{1, 2, 3});
            z.closeEntry();
        }
        byte[] zip = zbytes.toByteArray();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x5510).putInt(1).putInt(zip.length).putInt(14).putInt(0).putInt(0);
        byte[] all = new byte[24 + zip.length];
        System.arraycopy(h.array(), 0, all, 0, 24);
        System.arraycopy(zip, 0, all, 24, zip.length);

        CameraFilesReceiver r = new CameraFilesReceiver();
        Map<String, byte[]> files = null;
        for (int off = 0; off < all.length; off += 100) {
            Map<String, byte[]> m = r.feed(all, off, Math.min(100, all.length - off));
            if (m != null) files = m;
        }
        assertNotNull(files);
        assertEquals(RSC, new String(files.get("CameraFiles/system/calib.rsc"), StandardCharsets.ISO_8859_1));
        assertEquals(3, files.get("CameraFiles/system/maps/x.fff").length);
    }
}
