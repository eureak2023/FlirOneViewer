package com.example.flironeviewer.thermal;

/**
 * 실화상 ↔ 열화상 정합 파라미터 (calib.rsc 의 {@code .calib.visual.fusion.<lens>.*}).
 *
 * <p>실기기(FLIR ONE 435-0004-01, 렌즈 leExFOL2) 값:
 * magnQuote=1.2515709, C0X=-5.0744071, C1X=-3.3491952, C0Y=0, C1Y=-16.948754, fixFusionDistance=3.
 * <ul>
 *   <li>magnQuote: 실화상 시야가 열화상보다 넓은 배율. 열화상 프레임은 실화상 중앙 1/magnQuote 영역에 해당.</li>
 *   <li>시차 오프셋(실화상 기준 해상도 640x480 픽셀): off = C0 + C1 / 거리(m).</li>
 * </ul>
 * 오프셋 부호는 실기기 화면 비교로 정했고(잘라낼 중심 = 중앙 − 오프셋), 단위는 추론입니다.
 * 실화상 기준 해상도는 calib.rsc 의 {@code .calib.visual.data} (480x640) 를 가로로 본 640x480 입니다.
 */
public final class Fusion {

    public static final double BASE_W = 640, BASE_H = 480;

    public final double magn, c0x, c1x, c0y, c1y;
    public volatile double distanceM;

    public Fusion(double magn, double c0x, double c1x, double c0y, double c1y, double distanceM) {
        this.magn = magn > 0.5 && magn < 4 ? magn : 1.0;
        this.c0x = c0x;
        this.c1x = c1x;
        this.c0y = c0y;
        this.c1y = c1y;
        this.distanceM = distanceM > 0 ? distanceM : 3;
    }

    public static Fusion deviceDefault() {
        return new Fusion(1.2515709, -5.0744071, -3.3491952, 0, -16.948754, 3);
    }

    /**
     * 실화상(vw x vh) 안에서 열화상 시야에 해당하는 영역 [left, top, width, height] (정수, 이미지 안으로 보정).
     */
    public int[] visualCrop(int vw, int vh) {
        double sx = vw / BASE_W, sy = vh / BASE_H;
        double d = Math.max(0.1, distanceM);
        double offX = (c0x + c1x / d) * sx;
        double offY = (c0y + c1y / d) * sy;
        double cw = vw / magn, ch = vh / magn;
        // 오프셋은 "열화상 기준 실화상의 위치"로 해석 → 잘라낼 중심은 반대 방향.
        // (처음엔 +로 적용했으나 실기기에서 윤곽선이 열화상보다 +X 쪽으로 어긋나 부호를 뒤집음)
        double left = vw / 2.0 - offX - cw / 2.0;
        double top = vh / 2.0 - offY - ch / 2.0;
        int w = (int) Math.round(cw), h = (int) Math.round(ch);
        int l = (int) Math.round(Math.max(0, Math.min(vw - w, left)));
        int t = (int) Math.round(Math.max(0, Math.min(vh - h, top)));
        return new int[]{l, t, Math.min(w, vw), Math.min(h, vh)};
    }
}
