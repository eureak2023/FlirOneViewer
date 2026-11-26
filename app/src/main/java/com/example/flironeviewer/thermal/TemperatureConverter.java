package com.example.flironeviewer.thermal;

/**
 * raw → °C 변환 (FLIR extRBF 모델 + Lepton 스팟미터 기반 드리프트 보정).
 *
 * <p>FLIR 모델 (카메라의 CameraFiles.zip/system/calib.rsc, 키 {@code .calib.extRBF.<id>.R/B/F/J0/J1}):
 * <pre>
 *   S(T) = J0 + J1 · R / (exp(B / T) − F)          (S = 16비트 신호 = Lepton 14비트 raw × 4)
 *   S_obj = (S − (1 − ε) · S(T_refl)) / ε
 *   T     = B / ln(R / ((S_obj − J0) / J1) + F)
 * </pre>
 * 센서는 자체 온도(FPA)에 따라 raw 가 흐르므로, 프레임 텔레메트리의 Lepton 라디오메트릭
 * 스팟미터(중앙 2x2 영역의 온도, K×100, 방사율 1.0)를 기준으로 신호 오프셋을 매 프레임 추정해 보정합니다.
 */
public final class TemperatureConverter {

    /** 실기기(FLIR ONE 435-0004-01) calib.rsc 의 ds120C_weRB_apNOA_fiNOF_leExFOL2 값. 파일을 못 받으면 사용. */
    public static final double DEFAULT_R = 16331.195;
    public static final double DEFAULT_B = 1423.9;
    public static final double DEFAULT_F = 1.0;
    public static final double DEFAULT_J0 = 1301;
    public static final double DEFAULT_J1 = 82.397461;
    /** Lepton 14비트 raw → FLIR 16비트 신호 배율. */
    public static final double RAW_SCALE = 4.0;

    private volatile double r = DEFAULT_R, b = DEFAULT_B, f = DEFAULT_F, j0 = DEFAULT_J0, j1 = DEFAULT_J1;
    private volatile String source = "기본값(ExFOL2)";
    private volatile double emissivity = 0.95;
    private volatile double reflectedC = 22.0;
    /** 스팟미터 기준 신호 오프셋(16비트 신호 단위). NaN 이면 아직 추정 안 됨. */
    private volatile double signalOffset = Double.NaN;

    public synchronized void setCalibration(double r, double b, double f, double j0, double j1, String source) {
        this.r = r;
        this.b = b;
        this.f = f;
        this.j0 = j0;
        this.j1 = j1;
        this.source = source;
        this.signalOffset = Double.NaN; // 상수가 바뀌면 오프셋 재추정
    }

    public String getSource() { return source; }
    public double getEmissivity() { return emissivity; }
    public double getReflectedC() { return reflectedC; }
    public double getSignalOffset() { return signalOffset; }

    public synchronized void setParameters(double emissivity, double reflectedC) {
        this.emissivity = Math.max(0.01, Math.min(1.0, emissivity));
        this.reflectedC = reflectedC;
    }

    /** 온도(K) → 16비트 신호. */
    public double signalForKelvin(double kelvin) {
        return j0 + j1 * r / (Math.exp(b / kelvin) - f);
    }

    /**
     * Lepton 스팟미터로 오프셋을 갱신합니다.
     * @param spotRawMean 스팟 ROI 의 raw 평균(14비트)
     * @param spotKelvin  텔레메트리 스팟미터 값(K)
     */
    public void updateFromSpotmeter(double spotRawMean, double spotKelvin) {
        if (!(spotKelvin > 200 && spotKelvin < 700) || !(spotRawMean > 0)) return;
        double target = signalForKelvin(spotKelvin) - spotRawMean * RAW_SCALE;
        double cur = signalOffset;
        signalOffset = Double.isNaN(cur) ? target : cur + (target - cur) * 0.2; // 지터 완화
    }

    /** raw(14비트, 평균값 허용) → °C. */
    public double rawToCelsius(double raw) {
        double off = signalOffset;
        double s = raw * RAW_SCALE + (Double.isNaN(off) ? 0 : off);
        double e = emissivity;
        double sRefl = signalForKelvin(reflectedC + 273.15);
        double sObj = (s - (1.0 - e) * sRefl) / e;
        double l = (sObj - j0) / j1;
        if (l <= 0) return Double.NaN;
        double arg = r / l + f;
        if (!(arg > 1.0)) return Double.NaN;
        return b / Math.log(arg) - 273.15;
    }

    public float celsius(int raw) {
        return (float) rawToCelsius(raw);
    }
}
