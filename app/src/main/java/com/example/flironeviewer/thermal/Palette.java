package com.example.flironeviewer.thermal;

/** 256단계 컬러 팔레트(ARGB). 키 컬러를 선형 보간해 생성합니다. */
public enum Palette {
    IRON("Iron", new int[][]{
            {0, 0, 0}, {30, 0, 90}, {120, 0, 150}, {190, 30, 110},
            {235, 90, 20}, {255, 170, 0}, {255, 230, 90}, {255, 255, 255}}),
    RAINBOW("Rainbow", new int[][]{
            {0, 0, 80}, {0, 0, 255}, {0, 200, 255}, {0, 220, 60},
            {255, 255, 0}, {255, 120, 0}, {255, 0, 0}, {255, 255, 255}}),
    WHITE_HOT("White hot", new int[][]{{0, 0, 0}, {255, 255, 255}}),
    BLACK_HOT("Black hot", new int[][]{{255, 255, 255}, {0, 0, 0}}),
    ARCTIC("Arctic", new int[][]{
            {0, 0, 40}, {0, 40, 140}, {40, 120, 220}, {160, 210, 255},
            {255, 220, 120}, {255, 160, 0}, {255, 255, 220}}),
    LAVA("Lava", new int[][]{
            {0, 0, 0}, {20, 40, 90}, {60, 30, 120}, {170, 20, 40},
            {240, 90, 0}, {255, 200, 40}, {255, 255, 255}});

    public final String label;
    public final int[] colors = new int[256];

    Palette(String label, int[][] keys) {
        this.label = label;
        int segments = keys.length - 1;
        for (int i = 0; i < 256; i++) {
            double pos = i / 255.0 * segments;
            int s = Math.min(segments - 1, (int) Math.floor(pos));
            double t = pos - s;
            int[] a = keys[s], c = keys[s + 1];
            int r = (int) Math.round(a[0] + (c[0] - a[0]) * t);
            int g = (int) Math.round(a[1] + (c[1] - a[1]) * t);
            int b = (int) Math.round(a[2] + (c[2] - a[2]) * t);
            colors[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
    }

    public Palette next() {
        Palette[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
