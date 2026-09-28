package ro.plesiarazvan.profitride;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 2001;
    private static final int REQ_NOTIFY = 2002;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_OVERLAY_ONLY = 1;
    private static final int PENDING_START_ANALYSIS = 2;

    private final int BG = Color.rgb(9, 9, 12);
    private final int CARD = Color.rgb(29, 29, 35);
    private final int MUTED = Color.rgb(178, 178, 186);
    private final int YELLOW = Color.rgb(255, 210, 28);
    private final int GREEN = Color.rgb(83, 205, 96);
    private final int RED = Color.rgb(255, 82, 82);
    private final int FIELD = Color.rgb(48, 48, 56);

    private EditText minRonKm, minRonHour, minProfit;
    private EditText liters100, ronLiter, manualCost;
    private EditText demoFare, demoKm, demoMinutes;
    private TextView costLabel;
    private SharedPreferences prefs;
    private MediaProjectionManager projectionManager;
    private int pendingOverlayAction = PENDING_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("profitride", MODE_PRIVATE);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        setContentView(buildUi());
        maybeAskNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOverlayAction != PENDING_NONE && Settings.canDrawOverlays(this)) {
            int action = pendingOverlayAction;
            pendingOverlayAction = PENDING_NONE;
            if (action == PENDING_START_ANALYSIS) requestScreenCapture();
            else if (action == PENDING_OVERLAY_ONLY) startOverlayService();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView logo = text("ProfitRide", 32, Color.WHITE, true);
        root.addView(logo);
        TextView sub = text("Calculator live pentru ofertele de cursă", 16, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(18));
        root.addView(sub);

        root.addView(sectionTitle("Când merită cursa"));
        LinearLayout conditions = card();
        conditions.addView(text("Condiții minime", 17, Color.WHITE, true));
        TextView conditionHint = text("Tu alegi limitele. Cardul va afișa MERITĂ doar dacă oferta le îndeplinește.", 12, MUTED, false);
        conditionHint.setPadding(0, dp(4), 0, dp(4));
        conditions.addView(conditionHint);

        minRonKm = field("Min. RON/km", pref("minRonKm", 3.0f));
        minRonHour = field("Min. RON/oră", pref("minRonHour", 80.0f));
        minProfit = field("Profit min. RON", pref("minProfit", 15.0f));
        conditions.addView(row(minRonKm, minRonHour, minProfit));

        Button saveConditions = button("Salvează condițiile", YELLOW, Color.BLACK);
        saveConditions.setOnClickListener(v -> {
            saveSettings();
            Toast.makeText(this, "Condițiile au fost salvate.", Toast.LENGTH_SHORT).show();
        });
        conditions.addView(saveConditions, buttonParams());
        root.addView(conditions);

        root.addView(sectionTitle("Cost mașină"));
        LinearLayout costs = card();
        costs.addView(text("Consum personalizat", 17, Color.WHITE, true));

        TextView consumptionLabel = text("Consum mașină (L/100 km)", 14, Color.WHITE, true);
        consumptionLabel.setPadding(0, dp(10), 0, dp(6));
        costs.addView(consumptionLabel);

        liters100 = field("ex. 7,2", pref("liters100", 7.0f));
        Button minusConsumption = button("−", FIELD, Color.WHITE);
        Button plusConsumption = button("+", FIELD, Color.WHITE);
        LinearLayout consumptionRow = new LinearLayout(this);
        consumptionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams step = new LinearLayout.LayoutParams(dp(56), dp(56));
        LinearLayout.LayoutParams consumptionField = new LinearLayout.LayoutParams(0, dp(56), 1f);
        consumptionField.setMargins(dp(8), 0, dp(8), 0);
        consumptionRow.addView(minusConsumption, step);
        consumptionRow.addView(liters100, consumptionField);
        consumptionRow.addView(plusConsumption, new LinearLayout.LayoutParams(dp(56), dp(56)));
        costs.addView(consumptionRow);

        TextView consumptionHint = text("Exemple: 5,8 / 7,3 / 11,5 L/100 km. Poți scrie valoarea sau folosi + și −.", 12, MUTED, false);
        consumptionHint.setPadding(0, dp(7), 0, dp(8));
        costs.addView(consumptionHint);

        ronLiter = field("RON/litru", pref("ronLiter", 7.35f));
        manualCost = field("Cost/km manual", pref("manualCost", 0.0f));
        costs.addView(row(ronLiter, manualCost));

        TextView manualHint = text("Cost/km manual este opțional. Dacă pui o valoare mai mare ca 0, ea înlocuiește calculul din combustibil — util și pentru EV.", 12, MUTED, false);
        manualHint.setPadding(0, 0, 0, dp(8));
        costs.addView(manualHint);

        costLabel = text("Cost estimat: " + format(costPerKm()) + " RON/km", 15, MUTED, true);
        costLabel.setPadding(0, dp(4), 0, dp(8));
        costs.addView(costLabel);

        minusConsumption.setOnClickListener(v -> adjustConsumption(-0.1));
        plusConsumption.setOnClickListener(v -> adjustConsumption(0.1));

        TextWatcher costWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateCostPreview(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        liters100.addTextChangedListener(costWatcher);
        ronLiter.addTextChangedListener(costWatcher);
        manualCost.addTextChangedListener(costWatcher);

        Button saveCar = button("Salvează consumul și costurile", YELLOW, Color.BLACK);
        saveCar.setOnClickListener(v -> {
            saveSettings();
            updateCostPreview();
            Toast.makeText(this, "Setările mașinii au fost salvate.", Toast.LENGTH_SHORT).show();
        });
        costs.addView(saveCar, buttonParams());
        root.addView(costs);

        root.addView(sectionTitle("Afișare peste Uber/Bolt"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button overlay = button("AFIȘAJ", YELLOW, Color.BLACK);
        Button stop = button("STOP", RED, Color.WHITE);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(54), 1f);
        half.setMargins(0, 0, dp(7), 0);
        actions.addView(overlay, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(54), 1f);
        half2.setMargins(dp(7), 0, 0, 0);
        actions.addView(stop, half2);
        root.addView(actions);

        Button start = button("▶ START ANALIZĂ", GREEN, Color.BLACK);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, dp(58));
        full.setMargins(0, dp(14), 0, 0);
        root.addView(start, full);

        TextView note = text("START cere permisiunea Android pentru captură ecran. Analiza OCR se face local, iar cardul flotant afișează calculele peste aplicația de șofer.", 13, MUTED, false);
        note.setPadding(0, dp(10), 0, dp(18));
        root.addView(note);

        root.addView(sectionTitle("Test rapid fără Uber/Bolt"));
        LinearLayout demo = card();
        demoFare = field("Preț RON", "45");
        demoKm = field("Distanță km", "12");
        demoMinutes = field("Minute", "28");
        demo.addView(row(demoFare, demoKm, demoMinutes));
        Button simulate = button("Simulează ofertă", YELLOW, Color.BLACK);
        simulate.setOnClickListener(v -> simulateOffer());
        demo.addView(simulate, buttonParams());
        root.addView(demo);

        LinearLayout about = card();
        about.addView(text("Despre aplicație", 17, Color.WHITE, true));
        TextView version = text("ProfitRide 1.0.0\nCreat de Plesia Razvan", 13, MUTED, true);
        version.setPadding(0, dp(8), 0, 0);
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        about.addView(version);
        root.addView(about);

        overlay.setOnClickListener(v -> ensureOverlayThen(PENDING_OVERLAY_ONLY));
        stop.setOnClickListener(v -> stopEverything());
        start.setOnClickListener(v -> ensureOverlayThen(PENDING_START_ANALYSIS));

        return scroll;
    }

    private void ensureOverlayThen(int action) {
        saveSettings();
        if (Settings.canDrawOverlays(this)) {
            if (action == PENDING_START_ANALYSIS) requestScreenCapture();
            else startOverlayService();
            return;
        }

        pendingOverlayAction = action;
        new AlertDialog.Builder(this)
                .setTitle("Permisiune afișaj")
                .setMessage("Pentru cardul flotant, permite ProfitRide să se afișeze peste alte aplicații. După ce activezi permisiunea, revino în ProfitRide.")
                .setPositiveButton("Deschide setările", (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                })
                .setNegativeButton("Anulează", (d, w) -> pendingOverlayAction = PENDING_NONE)
                .show();
    }

    private void requestScreenCapture() {
        saveSettings();
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, "Nu pot porni captura: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, ScreenCaptureService.class);
            svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            startOverlayService();
            Toast.makeText(this, "Analiza a pornit. Deschide aplicația de șofer.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Captura ecran a fost anulată.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startOverlayService() {
        if (!Settings.canDrawOverlays(this)) return;
        startService(new Intent(this, OverlayService.class));
    }

    private void simulateOffer() {
        saveSettings();
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlayAction = PENDING_OVERLAY_ONLY;
            ensureOverlayThen(PENDING_OVERLAY_ONLY);
            Toast.makeText(this, "După permisiune, apasă din nou «Simulează ofertă».", Toast.LENGTH_LONG).show();
            return;
        }

        startOverlayService();
        Intent update = new Intent(OverlayService.ACTION_UPDATE);
        update.setPackage(getPackageName());
        update.putExtra("fare", positiveNum(demoFare));
        update.putExtra("km", positiveNum(demoKm));
        update.putExtra("minutes", positiveNum(demoMinutes));
        sendBroadcast(update);
    }

    private void stopEverything() {
        stopService(new Intent(this, ScreenCaptureService.class));
        stopService(new Intent(this, OverlayService.class));
        Toast.makeText(this, "ProfitRide oprit.", Toast.LENGTH_SHORT).show();
    }

    private void saveSettings() {
        prefs.edit()
                .putFloat("minRonKm", (float) clamp(positiveNum(minRonKm), 0, 1000))
                .putFloat("minRonHour", (float) clamp(positiveNum(minRonHour), 0, 10000))
                .putFloat("minProfit", (float) clamp(positiveNum(minProfit), 0, 100000))
                .putFloat("liters100", (float) clamp(positiveNum(liters100), 0, 99.9))
                .putFloat("ronLiter", (float) clamp(positiveNum(ronLiter), 0, 100))
                .putFloat("manualCost", (float) clamp(positiveNum(manualCost), 0, 1000))
                .apply();
    }

    private double costPerKm() {
        double manual = manualCost == null ? prefs.getFloat("manualCost", 0f) : positiveNum(manualCost);
        if (manual > 0) return manual;
        double l100 = liters100 == null ? prefs.getFloat("liters100", 7f) : positiveNum(liters100);
        double price = ronLiter == null ? prefs.getFloat("ronLiter", 7.35f) : positiveNum(ronLiter);
        return (l100 / 100.0) * price;
    }

    private void adjustConsumption(double delta) {
        double current = positiveNum(liters100);
        if (current <= 0) current = prefs.getFloat("liters100", 7f);
        current = Math.round(clamp(current + delta, 0.1, 99.9) * 10.0) / 10.0;
        liters100.setText(String.format(Locale.US, "%.1f", current).replace('.', ','));
        liters100.setSelection(liters100.getText().length());
        saveSettings();
        updateCostPreview();
    }

    private void updateCostPreview() {
        if (costLabel != null) costLabel.setText("Cost estimat: " + format(costPerKm()) + " RON/km");
    }

    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(Shape.rounded(CARD, 20));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(14));
        l.setLayoutParams(p);
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 18, Color.WHITE, true);
        t.setPadding(0, dp(6), 0, dp(10));
        return t;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(Color.WHITE);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setGravity(Gravity.CENTER);
        e.setPadding(dp(8), 0, dp(8), 0);
        e.setBackground(Shape.rounded(FIELD, 15));
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(12), 0, dp(12));
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f);
            p.setMargins(i == 0 ? 0 : dp(5), 0, i == views.length - 1 ? 0 : dp(5), 0);
            r.addView(views[i], p);
        }
        return r;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, dp(4), 0, 0);
        return p;
    }

    private Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(Shape.rounded(bg, 18));
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private String pref(String key, float fallback) {
        return trimNumber(prefs.getFloat(key, fallback));
    }

    private String trimNumber(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.0001) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "").replace('.', ',');
    }

    private double positiveNum(EditText e) {
        if (e == null) return 0;
        try {
            double value = Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
            return Double.isFinite(value) && value > 0 ? value : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String format(double v) {
        if (!Double.isFinite(v)) v = 0;
        return String.format(Locale.US, "%.2f", v).replace('.', ',');
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
