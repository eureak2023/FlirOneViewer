package com.example.flironeviewer.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * FLIR 리소스 파일(calib.rsc) 파서. 각 줄은 {@code <키> <타입> <값>} 형식입니다. 예:
 * <pre>
 *  .calib.general.fixedLensID text "leExFOL2"
 *  .calib.extRBF.ds120C_weRB_apNOA_fiNOF_leExFOL2.R double 16331.195
 * </pre>
 */
public final class CalibRsc {

    public final double r, b, f, j0, j1;
    public final String id;
    /** 실화상 정합 [magnQuote, C0X, C1X, C0Y, C1Y, fixFusionDistance] (없으면 null). */
    public double[] fusion;

    private CalibRsc(double r, double b, double f, double j0, double j1, String id) {
        this.r = r;
        this.b = b;
        this.f = f;
        this.j0 = j0;
        this.j1 = j1;
        this.id = id;
    }

    /** 고정 렌즈 ID 에 맞는 ds120C(고감도 범위) extRBF 세트를 찾습니다. 없으면 null. */
    public static CalibRsc parse(String text) {
        Map<String, String> kv = new HashMap<>();
        for (String line : text.split("\r?\n")) {
            line = line.trim();
            if (!line.startsWith(".")) continue;
            String[] p = line.split("\\s+", 3);
            if (p.length < 3) continue;
            String v = p[2].trim();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
            kv.put(p[0], v);
        }
        String lens = kv.getOrDefault(".calib.general.fixedLensID", "").trim();

        String best = null;
        int bestScore = -1;
        for (String k : kv.keySet()) {
            if (!k.startsWith(".calib.extRBF.") || !k.endsWith(".R")) continue;
            String id = k.substring(".calib.extRBF.".length(), k.length() - 2);
            int score = 0;
            if (id.startsWith("ds120C")) score += 4;
            if (!lens.isEmpty() && id.endsWith("_" + lens)) score += 8;
            if ("0".equals(kv.get(".calib.extRBF." + id + ".type"))) score += 2; // type 100 은 미보정 기본값
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        if (best == null) return null;
        String pre = ".calib.extRBF." + best + ".";
        try {
            CalibRsc c = new CalibRsc(
                    Double.parseDouble(kv.get(pre + "R")),
                    Double.parseDouble(kv.get(pre + "B")),
                    Double.parseDouble(kv.getOrDefault(pre + "F", "1")),
                    Double.parseDouble(kv.getOrDefault(pre + "J0", "0")),
                    Double.parseDouble(kv.getOrDefault(pre + "J1", "1")),
                    best);
            String fp = ".calib.visual.fusion." + lens + ".";
            if (kv.containsKey(fp + "magnQuote")) {
                c.fusion = new double[]{
                        Double.parseDouble(kv.get(fp + "magnQuote")),
                        Double.parseDouble(kv.getOrDefault(fp + "C0X", "0")),
                        Double.parseDouble(kv.getOrDefault(fp + "C1X", "0")),
                        Double.parseDouble(kv.getOrDefault(fp + "C0Y", "0")),
                        Double.parseDouble(kv.getOrDefault(fp + "C1Y", "0")),
                        Double.parseDouble(kv.getOrDefault(fp + "fixFusionDistance", "3"))};
            }
            return c;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
