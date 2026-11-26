package com.example.flironeviewer.thermal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TemperatureConverterTest {

    @Test
    public void signalAndTemperatureRoundTrip() {
        TemperatureConverter c = new TemperatureConverter();
        c.setParameters(1.0, 22.0);
        for (double tc = -10; tc <= 120; tc += 10) {
            double raw = c.signalForKelvin(tc + 273.15) / TemperatureConverter.RAW_SCALE;
            assertEquals(tc, c.rawToCelsius(raw), 1e-6);
        }
    }

    @Test
    public void spotmeterOffsetMakesSpotPixelMatchLepton() {
        // 실기기 텔레메트리 값: 스팟 299.09K(25.94°C), 해당 위치 raw ≈ 3419
        TemperatureConverter c = new TemperatureConverter();
        c.setParameters(1.0, 22.0);
        double before = c.rawToCelsius(3419);
        assertTrue("보정 전에는 높게 나옴: " + before, before > 28);
        c.updateFromSpotmeter(3419, 299.09);
        assertEquals(25.94, c.rawToCelsius(3419), 0.01);
    }

    @Test
    public void emissivityBelowOneRaisesObjectTemperatureAboveAmbient() {
        TemperatureConverter c = new TemperatureConverter();
        c.setParameters(1.0, 22.0);
        double raw = c.signalForKelvin(273.15 + 40) / TemperatureConverter.RAW_SCALE;
        c.setParameters(0.95, 22.0);
        assertTrue(c.rawToCelsius(raw) > 40);
    }
}
