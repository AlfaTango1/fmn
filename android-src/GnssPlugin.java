package __PKG__;

import android.Manifest;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Position straight from the phone's satellite receiver (LocationManager.GPS_PROVIDER: GPS, GLONASS, BeiDou,
 * Galileo, QZSS, NavIC - whatever the chip supports), without Wi-Fi / cell positioning, plus satellite status
 * (constellation, signal strength, elevation, azimuth, used in fix, carrier frequency).
 */
@CapacitorPlugin(
    name = "Gnss",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION })
    }
)
public class GnssPlugin extends Plugin {
    private LocationManager lm;
    private LocationListener listener;
    private Object statusCallback;
    private Object altitudeConverter;
    private long lastStatus = 0;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "permissionResult");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void permissionResult(PluginCall call) {
        if (getPermissionState("location") == PermissionState.GRANTED) doStart(call);
        else call.reject("location permission refused");
    }

    private void doStart(PluginCall call) {
        lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { call.reject("no location service"); return; }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { call.reject("satellite positioning is switched off in the phone settings"); return; }
        stopAll();
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) { sendFix(l); }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {
                JSObject o = new JSObject(); o.put("disabled", true); notifyListeners("state", o);
            }
        };
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper());
            if (Build.VERSION.SDK_INT >= 24) {
                GnssStatus.Callback cb = new GnssStatus.Callback() {
                    @Override public void onSatelliteStatusChanged(GnssStatus s) { sendStatus(s); }
                };
                lm.registerGnssStatusCallback(cb, new Handler(Looper.getMainLooper()));
                statusCallback = cb;
            }
        } catch (SecurityException e) {
            call.reject("location permission refused");
            return;
        }
        JSObject r = new JSObject();
        r.put("ok", true);
        r.put("sdk", Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String hw = lm.getGnssHardwareModelName();
                if (hw != null) r.put("hw", hw);
                r.put("year", lm.getGnssYearOfHardware());
            } catch (Exception ignored) {}
        }
        call.resolve(r);
    }

    private void sendFix(final Location l) {
        worker.execute(() -> {
            JSObject o = new JSObject();
            o.put("lat", l.getLatitude());
            o.put("lon", l.getLongitude());
            if (l.hasAltitude()) o.put("alt", l.getAltitude());
            if (l.hasAccuracy()) o.put("acc", (double) l.getAccuracy());
            if (l.hasSpeed()) o.put("spd", (double) l.getSpeed());
            if (l.hasBearing()) o.put("brg", (double) l.getBearing());
            o.put("t", l.getTime());
            if (Build.VERSION.SDK_INT >= 26) {
                if (l.hasVerticalAccuracy()) o.put("vacc", (double) l.getVerticalAccuracyMeters());
                if (l.hasSpeedAccuracy()) o.put("sacc", (double) l.getSpeedAccuracyMetersPerSecond());
            }
            /* Android 14+: height above mean sea level from the system geoid model */
            if (Build.VERSION.SDK_INT >= 34 && l.hasAltitude()) {
                try {
                    if (altitudeConverter == null) altitudeConverter = new android.location.altitude.AltitudeConverter();
                    ((android.location.altitude.AltitudeConverter) altitudeConverter).addMslAltitudeToLocation(getContext(), l);
                    if (l.hasMslAltitude()) o.put("msl", l.getMslAltitudeMeters());
                } catch (Exception ignored) {}
            }
            Bundle b = l.getExtras();
            if (b != null && b.containsKey("satellites")) o.put("sats", b.getInt("satellites"));
            notifyListeners("fix", o);
        });
    }

    private void sendStatus(GnssStatus s) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatus < 900) return;
        lastStatus = now;
        JSArray a = new JSArray();
        int n = s.getSatelliteCount();
        for (int i = 0; i < n; i++) {
            JSObject o = new JSObject();
            o.put("c", s.getConstellationType(i));
            o.put("id", s.getSvid(i));
            o.put("cn0", (double) s.getCn0DbHz(i));
            o.put("el", (double) s.getElevationDegrees(i));
            o.put("az", (double) s.getAzimuthDegrees(i));
            o.put("u", s.usedInFix(i));
            if (Build.VERSION.SDK_INT >= 26 && s.hasCarrierFrequencyHz(i)) o.put("f", (double) s.getCarrierFrequencyHz(i));
            a.put(o);
        }
        JSObject r = new JSObject();
        r.put("sats", a);
        notifyListeners("status", r);
    }

    @PluginMethod
    public void stop(PluginCall call) { stopAll(); call.resolve(); }

    private void stopAll() {
        try {
            if (lm != null && listener != null) lm.removeUpdates(listener);
            if (Build.VERSION.SDK_INT >= 24 && lm != null && statusCallback != null) lm.unregisterGnssStatusCallback((GnssStatus.Callback) statusCallback);
        } catch (Exception ignored) {}
        listener = null;
        statusCallback = null;
    }

    @Override
    protected void handleOnDestroy() { stopAll(); }
}
