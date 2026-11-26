package com.example.flironeviewer.protocol;

/** 디코딩된 16비트 raw 열화상 (행 우선, width*height). */
public final class ThermalImage {
    public final int width;
    public final int height;
    public final int[] raw;
    public final int min;
    public final int max;
    public final int minIndex;
    public final int maxIndex;

    public ThermalImage(int width, int height, int[] raw) {
        this.width = width;
        this.height = height;
        this.raw = raw;
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE, mnI = 0, mxI = 0;
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i];
            if (v < mn) { mn = v; mnI = i; }
            if (v > mx) { mx = v; mxI = i; }
        }
        this.min = mn;
        this.max = mx;
        this.minIndex = mnI;
        this.maxIndex = mxI;
    }

    // --- Lepton 텔레메트리 (영상 뒤 3행, 없으면 -1). 실기기 캡처로 위치 확인.
    /** 라디오메트릭 스팟미터 평균(K×100). */
    public int spotMeanK100 = -1;
    /** 스팟미터 ROI (행/열, 양 끝 포함). */
    public int spotRow0, spotCol0, spotRow1, spotCol1;
    /** FPA(센서) 온도, 하우징 온도 (K×100). */
    public int fpaK100 = -1, housingK100 = -1;

    /** 스팟미터 ROI 의 raw 평균. */
    public double spotRawMean() {
        long sum = 0;
        int n = 0;
        for (int y = Math.max(0, spotRow0); y <= Math.min(height - 1, spotRow1); y++) {
            for (int x = Math.max(0, spotCol0); x <= Math.min(width - 1, spotCol1); x++) {
                sum += at(x, y);
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / (double) n;
    }

    public int at(int x, int y) {
        return raw[y * width + x];
    }

    /** (x,y) 주변 2x2 평균 — 단일 픽셀 노이즈 완화용. */
    public double spot(int x, int y) {
        int x0 = Math.max(0, Math.min(width - 2, x));
        int y0 = Math.max(0, Math.min(height - 2, y));
        return (at(x0, y0) + at(x0 + 1, y0) + at(x0, y0 + 1) + at(x0 + 1, y0 + 1)) / 4.0;
    }
}
