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

    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams params;
    private TextView title, fareView, distanceView, timeView, kmView, hourView, profitView, verdictView, reasonView;
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(85);
        wm.addView(overlay, params);
        enableDrag(overlay);
        registerUpdateReceiver();
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
        TextView live = tv("analiză live • Creat de Plesia Razvan", 10, Color.LTGRAY, false);
        root.addView(live);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);
        fareView = metric("PREȚ", "—");
        distanceView = metric("DISTANȚĂ", "—");
        timeView = metric("TIMP", "—");
        addMetric(row1, fareView);
        addMetric(row1, distanceView);
        addMetric(row1, timeView);
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        kmView = metric("RON/KM", "—");
        hourView = metric("RON/ORĂ", "—");
        profitView = metric("PROFIT", "—");
        addMetric(row2, kmView);
        addMetric(row2, hourView);
        addMetric(row2, profitView);
        root.addView(row2);

        verdictView = tv("", 16, Color.WHITE, true);
        verdictView.setGravity(Gravity.CENTER);
        verdictView.setPadding(0, dp(9), 0, 0);
        root.addView(verdictView);

        reasonView = tv("", 11, Color.LTGRAY, false);
        reasonView.setGravity(Gravity.CENTER);
        reasonView.setPadding(0, dp(3), 0, 0);
        root.addView(reasonView);
        return root;
    }

    private void registerUpdateReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                double fare = intent.getDoubleExtra("fare", 0);
                double km = intent.getDoubleExtra("km", 0);
                double minutes = intent.getDoubleExtra("minutes", 0);
                if (fare > 0 && km > 0) showOffer(fare, km, minutes);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void showOffer(double fare, double km, double minutes) {
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
        fareView.setText(metricText("PREȚ", f(fare) + " RON"));
        distanceView.setText(metricText("DISTANȚĂ", f(km) + " km"));
        timeView.setText(metricText("TIMP", minutes > 0 ? f(minutes) + " min" : "—"));
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
