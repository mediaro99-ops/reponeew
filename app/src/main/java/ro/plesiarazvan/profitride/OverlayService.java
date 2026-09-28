package ro.plesiarazvan.profitride;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OverlayService extends Service {
    public static final String ACTION_UPDATE = "ro.plesiarazvan.profitride.UPDATE_OFFER";
    public static final String ACTION_RESET = "ro.plesiarazvan.profitride.RESET_OFFER";
    public static final String ACTION_PAUSE = "ro.plesiarazvan.profitride.PAUSE_ANALYSIS";
    public static final String ACTION_HIDE = "ro.plesiarazvan.profitride.HIDE_OVERLAY";
    public static final String ACTION_SHOW_CURRENT = "ro.plesiarazvan.profitride.SHOW_CURRENT";

    public static final String MODE_WAITING = "waiting";
    public static final String MODE_OFFER = "offer";
    public static final String MODE_PAUSED = "paused";

    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams params;
    private TextView title, fareView, distanceView, timeView, kmView, hourView, profitView, verdictView, reasonView, legsView;
    private BroadcastReceiver receiver;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("profitride", MODE_PRIVATE);
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = buildOverlay();
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                dp(340),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(85);
        wm.addView(overlay, params);
        enableDrag(overlay);
        registerUpdateReceiver();
        applySavedMode();
    }

    private View buildOverlay() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackground(Shape.rounded(Color.rgb(16, 16, 20), 22));

        TextView brand = tv("ProfitRide", 14, Color.WHITE, true);
        root.addView(brand);
        title = tv("Se așteaptă oferta…", 22, Color.rgb(255, 210, 28), true);
        root.addView(title);
        TextView live = tv("analiză automată • Creat de Plesia Razvan", 10, Color.LTGRAY, false);
        root.addView(live);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);
        fareView = metric("PREȚ", "0,00");
        distanceView = metric("DISTANȚĂ", "0,0 km");
        timeView = metric("TIMP", "0 min");
        addMetric(row1, fareView);
        addMetric(row1, distanceView);
        addMetric(row1, timeView);
        root.addView(row1);

        legsView = tv("", 11, Color.LTGRAY, true);
        legsView.setGravity(Gravity.CENTER);
        legsView.setPadding(0, dp(6), 0, 0);
        root.addView(legsView);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        kmView = metric("RON/KM", "0,00");
        hourView = metric("RON/ORĂ", "0,00");
        profitView = metric("PROFIT", "0,00");
        addMetric(row2, kmView);
        addMetric(row2, hourView);
        addMetric(row2, profitView);
        root.addView(row2);

        verdictView = tv("Aștept următoarea cursă", 16, Color.WHITE, true);
        verdictView.setGravity(Gravity.CENTER);
        verdictView.setPadding(0, dp(9), 0, 0);
        root.addView(verdictView);

        reasonView = tv("Resetarea și reluarea se fac automat", 11, Color.LTGRAY, false);
        reasonView.setGravity(Gravity.CENTER);
        reasonView.setPadding(0, dp(3), 0, 0);
        root.addView(reasonView);
        return root;
    }

    private void registerUpdateReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_UPDATE.equals(action)) {
                    double fare = intent.getDoubleExtra("fare", 0);
                    double km = intent.getDoubleExtra("km", 0);
                    double minutes = intent.getDoubleExtra("minutes", 0);
                    double pickupKm = intent.getDoubleExtra("pickupKm", 0);
                    double pickupMinutes = intent.getDoubleExtra("pickupMinutes", 0);
                    double tripKm = intent.getDoubleExtra("tripKm", 0);
                    double tripMinutes = intent.getDoubleExtra("tripMinutes", 0);
                    if (fare > 0 && km > 0) {
                        saveOffer(fare, km, minutes, pickupKm, pickupMinutes, tripKm, tripMinutes);
                        showOverlay();
                        showOffer(fare, km, minutes, pickupKm, pickupMinutes, tripKm, tripMinutes);
                    }
                } else if (ACTION_RESET.equals(action)) {
                    prefs.edit().putString("rideMode", MODE_WAITING).apply();
                    clearSavedOffer();
                    showOverlay();
                    showWaiting();
                } else if (ACTION_PAUSE.equals(action)) {
                    prefs.edit().putString("rideMode", MODE_PAUSED).apply();
                    showOverlay();
                    showPaused();
                } else if (ACTION_HIDE.equals(action)) {
                    hideOverlay();
                } else if (ACTION_SHOW_CURRENT.equals(action)) {
                    showOverlay();
                    applySavedMode();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_UPDATE);
        filter.addAction(ACTION_RESET);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_HIDE);
        filter.addAction(ACTION_SHOW_CURRENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void applySavedMode() {
        String mode = prefs.getString("rideMode", MODE_WAITING);
        if (MODE_PAUSED.equals(mode)) {
            showPaused();
        } else if (MODE_OFFER.equals(mode) && prefs.getFloat("lastFare", 0f) > 0 && prefs.getFloat("lastKm", 0f) > 0) {
            showOffer(
                    prefs.getFloat("lastFare", 0f),
                    prefs.getFloat("lastKm", 0f),
                    prefs.getFloat("lastMinutes", 0f),
                    prefs.getFloat("lastPickupKm", 0f),
                    prefs.getFloat("lastPickupMinutes", 0f),
                    prefs.getFloat("lastTripKm", 0f),
                    prefs.getFloat("lastTripMinutes", 0f)
            );
        } else {
            showWaiting();
        }
    }

    private void saveOffer(double fare, double km, double minutes, double pickupKm, double pickupMinutes, double tripKm, double tripMinutes) {
        prefs.edit()
                .putString("rideMode", MODE_OFFER)
                .putFloat("lastFare", (float) fare)
                .putFloat("lastKm", (float) km)
                .putFloat("lastMinutes", (float) minutes)
                .putFloat("lastPickupKm", (float) pickupKm)
                .putFloat("lastPickupMinutes", (float) pickupMinutes)
                .putFloat("lastTripKm", (float) tripKm)
                .putFloat("lastTripMinutes", (float) tripMinutes)
                .apply();
    }

    private void clearSavedOffer() {
        prefs.edit()
                .remove("lastFare")
                .remove("lastKm")
                .remove("lastMinutes")
                .remove("lastPickupKm")
                .remove("lastPickupMinutes")
                .remove("lastTripKm")
                .remove("lastTripMinutes")
                .apply();
    }

    private void showWaiting() {
        title.setText("Se așteaptă oferta…");
        title.setTextColor(Color.rgb(255, 210, 28));
        fareView.setText(metricText("PREȚ", "0,00 RON"));
        distanceView.setText(metricText("DISTANȚĂ", "0,0 km"));
        timeView.setText(metricText("TIMP", "0 min"));
        legsView.setText("");
        kmView.setText(metricText("RON/KM", "0,00"));
        hourView.setText(metricText("RON/ORĂ", "0,00"));
        profitView.setText(metricText("PROFIT", "0,00 RON"));
        verdictView.setText("Aștept următoarea cursă");
        verdictView.setTextColor(Color.WHITE);
        reasonView.setText("La o ofertă nouă, calculele pornesc automat");
    }

    private void showPaused() {
        title.setText("Pauză • cursă în desfășurare");
        title.setTextColor(Color.rgb(120, 190, 255));
        fareView.setText(metricText("PREȚ", "0,00 RON"));
        distanceView.setText(metricText("DISTANȚĂ", "0,0 km"));
        timeView.setText(metricText("TIMP", "0 min"));
        legsView.setText("");
        kmView.setText(metricText("RON/KM", "0,00"));
        hourView.setText(metricText("RON/ORĂ", "0,00"));
        profitView.setText(metricText("PROFIT", "0,00 RON"));
        verdictView.setText("Analiza este pe pauză");
        verdictView.setTextColor(Color.rgb(120, 190, 255));
        reasonView.setText("Se reactivează automat când apare următoarea ofertă");
    }

    private void showOffer(double fare, double km, double minutes, double pickupKm, double pickupMinutes, double tripKm, double tripMinutes) {
        double costKm = prefs.getFloat("manualCost", 0f);
        if (costKm <= 0) {
            costKm = prefs.getFloat("liters100", 7f) / 100.0 * prefs.getFloat("ronLiter", 7.35f);
        }

        double ronKm = fare / km;
        double ronHour = minutes > 0 ? fare / (minutes / 60.0) : 0;
        double profit = fare - (km * costKm);

        double thresholdKm = prefs.getFloat("minRonKm", 3f);
        double thresholdHour = prefs.getFloat("minRonHour", 80f);
        double thresholdProfit = prefs.getFloat("minProfit", 15f);

        title.setText("Ofertă detectată");
        title.setTextColor(Color.rgb(255, 210, 28));
        fareView.setText(metricText("PREȚ", f(fare) + " RON"));
        distanceView.setText(metricText("DISTANȚĂ", f(km) + " km"));
        timeView.setText(metricText("TIMP TOTAL", minutes > 0 ? f0(minutes) + " min" : "—"));
        if (pickupKm > 0 && tripKm > 0) {
            legsView.setText("Până la client: " + f1(pickupKm) + " km • " + f0(pickupMinutes) + " min   |   Cursă: " + f1(tripKm) + " km • " + f0(tripMinutes) + " min");
        } else {
            legsView.setText("");
        }
        kmView.setText(metricText("RON/KM", f(ronKm)));
        hourView.setText(metricText("RON/ORĂ", ronHour > 0 ? f(ronHour) : "—"));
        profitView.setText(metricText("PROFIT", f(profit) + " RON"));

        boolean kmOk = ronKm >= thresholdKm;
        boolean hourOk = ronHour <= 0 || ronHour >= thresholdHour;
        boolean profitOk = profit >= thresholdProfit;
        boolean ok = kmOk && hourOk && profitOk;

        verdictView.setText(ok ? "✓ MERITĂ" : "✕ NU MERITĂ");
        verdictView.setTextColor(ok ? Color.rgb(96, 220, 108) : Color.rgb(255, 100, 100));

        if (ok) {
            reasonView.setText("Toate condițiile tale sunt îndeplinite");
        } else {
            List<String> reasons = new ArrayList<>();
            if (!kmOk) reasons.add("RON/km sub limită");
            if (!hourOk) reasons.add("RON/oră sub limită");
            if (!profitOk) reasons.add("profit sub limită");
            reasonView.setText(joinReasons(reasons));
        }
    }

    private void hideOverlay() {
        if (overlay != null) overlay.setVisibility(View.GONE);
    }

    private void showOverlay() {
        if (overlay != null) overlay.setVisibility(View.VISIBLE);
    }

    private String joinReasons(List<String> reasons) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) out.append(" • ");
            out.append(reasons.get(i));
        }
        return out.toString();
    }

    private TextView metric(String label, String value) {
        return tv(metricText(label, value), 12, Color.WHITE, true);
    }

    private String metricText(String label, String value) {
        return label + "\n" + value;
    }

    private void addMetric(LinearLayout row, TextView v) {
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(4), dp(8), dp(4), dp(8));
        v.setBackground(Shape.rounded(Color.rgb(40, 40, 47), 13));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        row.addView(v, p);
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private String f0(double v) {
        if (!Double.isFinite(v)) v = 0;
        return String.format(Locale.US, "%.0f", v).replace('.', ',');
    }

    private String f1(double v) {
        if (!Double.isFinite(v)) v = 0;
        return String.format(Locale.US, "%.1f", v).replace('.', ',');
    }

    private String f(double v) {
        if (!Double.isFinite(v)) v = 0;
        return String.format(Locale.US, "%.2f", v).replace('.', ',');
    }

    private void enableDrag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = params.x;
                    startY = params.y;
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = startX + (int) (e.getRawX() - downX);
                    params.y = startY + (int) (e.getRawY() - downY);
                    wm.updateViewLayout(overlay, params);
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
        if (wm != null && overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
