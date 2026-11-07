package com.ppt.k90monitor;

import android.content.Context;
import android.net.TrafficStats;
import android.os.SystemClock;

/** Device-wide realtime network throughput. */
public final class NetworkDisplayReader {
    public static final class State {
        public double downBytesPerSec = Double.NaN;
        public double upBytesPerSec = Double.NaN;
    }

    private long lastRx = -1L;
    private long lastTx = -1L;
    private long lastAtMs = -1L;

    public NetworkDisplayReader(Context context) {
        // Keep Context constructor for API compatibility with the overlay service.
    }

    public State read() {
        State s = new State();
        long now = SystemClock.elapsedRealtime();
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();

        if (rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED &&
                lastRx >= 0L && lastTx >= 0L && lastAtMs > 0L && now > lastAtMs) {
            long dt = now - lastAtMs;
            long drx = Math.max(0L, rx - lastRx);
            long dtx = Math.max(0L, tx - lastTx);
            s.downBytesPerSec = drx * 1000.0 / dt;
            s.upBytesPerSec = dtx * 1000.0 / dt;
        }

        if (rx != TrafficStats.UNSUPPORTED) lastRx = rx;
        if (tx != TrafficStats.UNSUPPORTED) lastTx = tx;
        lastAtMs = now;
        return s;
    }
}
