package com.offsec.nethunter.gps;

import androidx.annotation.NonNull;
import java.util.Locale;


public class GpsPosition {
    public float time = 0.0f;

    public boolean updateIsFixed() {
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        float latitude = 0.0f;
        float longitude = 0.0f;
        float direction = 0.0f;
        float altitude = 0.0f;
        float velocity = 0.0f;
        int quality = 0;
        return String.format(Locale.getDefault(), "GpsPosition: latitude: %f, longitude: %f, time: %f, quality: %d, " +
                        "direction: %f, altitude: %f, velocity: %f", latitude, longitude, time, quality,
                direction, altitude, velocity);
    }
}