package ro.plesiarazvan.profitride;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    private static final int NOTIF_ID = 918;
    private static final String TAG = "ProfitRideOCR";
    private static final long OFFER_GONE_MS = 2200L;

    private static final Pattern PRICE = Pattern.compile("(\\d{1,4}(?:[\\.,]\\d{1,2})?)\\s*(?:ron|lei)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3}(?:[\\.,]\\d+)?)\\s*(?:min|minute|mins)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KM = Pattern.compile("(\\d{1,4}(?:[\\.,]\\d+)?)\\s*km\\b", Pattern.CASE_INSENSITIVE);

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private TextRecognizer recognizer;
    private long lastScan = 0;
    private long lastOfferSeenAt = 0;
    private volatile boolean processing = false;
    private String currentForegroundPackage = "";
    private String rideMode = OverlayService.MODE_WAITING;
    private SharedPreferences prefs;
    private BroadcastReceiver driverStateReceiver;

    private static class LineData {
        final String text;
        final int top;
        final int left;
        LineData(String text, Rect box) {
            this.text = text == null ? "" : text;
            this.top = box == null ? Integer.MAX_VALUE : box.top;
            this.left = box == null ? 0 : box.left;
        }
    }

    private static class Leg {
        final double minutes;
        final double km;
        final int top;
        Leg(double minutes, double km, int top) {
            this.minutes = minutes;
            this.km = km;
            this.top = top;
        }
    }

    private static class FareCandidate {
        final double fare;
        final int top;
        FareCandidate(double fare, int top) {
            this.fare = fare;
            this.top = top;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("profitride", MODE_PRIVATE);
        rideMode = prefs.getString("rideMode", OverlayService.MODE_WAITING);
        createChannel();
        Notification n = new Notification.Builder(this, "profitride_capture")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ProfitRide activ")
                .setContentText("Analiză automată Bolt • pauză la cursă • ascuns în Waze")
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        registerDriverStateReceiver();
    }

    private void registerDriverStateReceiver() {
        driverStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String event = intent.getStringExtra(DriverStateAccessibilityService.EXTRA_EVENT);
                String pkg = intent.getStringExtra(DriverStateAccessibilityService.EXTRA_PACKAGE);
                if (event == null) return;

                if (DriverStateAccessibilityService.EVENT_FOREGROUND.equals(event)) {
                    currentForegroundPackage = pkg == null ? "" : pkg;
                    prefs.edit().putString("foregroundPackage", currentForegroundPackage).apply();
                    if (DriverStateAccessibilityService.WAZE_PACKAGE.equals(currentForegroundPackage)) {
                        sendSimpleOverlayAction(OverlayService.ACTION_HIDE);
                    } else if (DriverStateAccessibilityService.BOLT_PACKAGE.equals(currentForegroundPackage)) {
                        sendSimpleOverlayAction(OverlayService.ACTION_SHOW_CURRENT);
                    } else {
                        sendSimpleOverlayAction(OverlayService.ACTION_HIDE);
                    }
                } else if (DriverStateAccessibilityService.EVENT_REJECTED.equals(event)) {
                    resetToWaiting();
                } else if (DriverStateAccessibilityService.EVENT_ACCEPTED.equals(event)) {
                    pauseForTrip();
                } else if (DriverStateAccessibilityService.EVENT_FINISHED.equals(event)) {
                    resetToWaiting();
                }
            }
        };
        IntentFilter f = new IntentFilter(DriverStateAccessibilityService.ACTION_DRIVER_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(driverStateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(driverStateReceiver, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) return START_STICKY;
        int code = intent != null ? intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) : Activity.RESULT_CANCELED;
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent != null ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class) : null;
        else data = intent != null ? intent.getParcelableExtra(EXTRA_RESULT_DATA) : null;
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                projection = null;
                cleanupCapture();
                stopSelf();
            }
        }, new Handler(getMainLooper()));
        startCapture();
        return START_STICKY;
    }

    private void startCapture() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
            dm.densityDpi = getResources().getDisplayMetrics().densityDpi;
            dm.widthPixels = metrics.getBounds().width();
            dm.heightPixels = metrics.getBounds().height();
        } else {
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        int width = Math.max(720, dm.widthPixels);
        int height = Math.max(1280, dm.heightPixels);
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        thread = new HandlerThread("ProfitRideCapture");
        thread.start();
        handler = new Handler(thread.getLooper());
        virtualDisplay = projection.createVirtualDisplay("ProfitRide", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        reader.setOnImageAvailableListener(this::onFrame, handler);
    }

    private void onFrame(ImageReader r) {
        long now = SystemClock.elapsedRealtime();
        if (processing || now - lastScan < 900) {
            Image skip = r.acquireLatestImage();
            if (skip != null) skip.close();
            return;
        }
        Image image = r.acquireLatestImage();
        if (image == null) return;
        lastScan = now;
        processing = true;
        Bitmap bitmap = null;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            bitmap = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            padded.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Nu pot converti cadrul", e);
            processing = false;
        } finally {
            image.close();
        }
        if (bitmap == null) { processing = false; return; }

        Bitmap finalBitmap = bitmap;
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(this::parseAndBroadcast)
                .addOnFailureListener(e -> Log.e(TAG, "OCR error", e))
                .addOnCompleteListener(t -> {
                    finalBitmap.recycle();
                    processing = false;
                });
    }

    private void parseAndBroadcast(Text result) {
        String raw = result.getText();
        String normalizedRaw = normalize(raw == null ? "" : raw);

        if (DriverStateAccessibilityService.WAZE_PACKAGE.equals(currentForegroundPackage)) {
            sendSimpleOverlayAction(OverlayService.ACTION_HIDE);
            return;
        }

        List<LineData> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                lines.add(new LineData(line.getText(), line.getBoundingBox()));
            }
        }
        lines.sort(Comparator.comparingInt((LineData l) -> l.top).thenComparingInt(l -> l.left));

        List<Leg> legs = new ArrayList<>();
        List<FareCandidate> fares = new ArrayList<>();

        for (LineData line : lines) {
            String s = normalize(line.text);
            Matcher fm = PRICE.matcher(s);
            while (fm.find()) {
                double fare = parseNum(fm.group(1));
                if (fare >= 3 && fare <= 5000) fares.add(new FareCandidate(fare, line.top));
            }

            Double mins = firstMatch(MINUTES, s);
            Double km = firstMatch(KM, s);
            if (mins != null && km != null && mins > 0 && mins <= 240 && km > 0 && km <= 500) {
                legs.add(new Leg(mins, km, line.top));
            }
        }

        if (legs.size() < 2) {
            for (int i = 0; i < lines.size(); i++) {
                String a = normalize(lines.get(i).text);
                Double mins = firstMatch(MINUTES, a);
                Double km = firstMatch(KM, a);
                if (mins != null && km != null) continue;
                for (int j = i + 1; j < Math.min(lines.size(), i + 4); j++) {
                    if (Math.abs(lines.get(j).top - lines.get(i).top) > 90) break;
                    String joined = a + " " + normalize(lines.get(j).text);
                    mins = firstMatch(MINUTES, joined);
                    km = firstMatch(KM, joined);
                    if (mins != null && km != null && mins > 0 && mins <= 240 && km > 0 && km <= 500) {
                        boolean duplicate = false;
                        for (Leg leg : legs) {
                            if (Math.abs(leg.top - Math.min(lines.get(i).top, lines.get(j).top)) < 35
                                    && Math.abs(leg.km - km) < 0.05 && Math.abs(leg.minutes - mins) < 0.05) {
                                duplicate = true;
                                break;
                            }
                        }
                        if (!duplicate) legs.add(new Leg(mins, km, Math.min(lines.get(i).top, lines.get(j).top)));
                        break;
                    }
                }
            }
        }

        legs.sort(Comparator.comparingInt(l -> l.top));
        fares.sort(Comparator.comparingInt(f -> f.top));

        Leg pickup = null;
        Leg trip = null;
        if (legs.size() >= 2) {
            pickup = legs.get(0);
            trip = legs.get(1);
        } else if (legs.size() == 1) {
            trip = legs.get(0);
        }

        double fare = chooseFare(fares, pickup != null ? pickup.top : (trip != null ? trip.top : Integer.MAX_VALUE));
        if (fare <= 0 || (pickup == null && trip == null)) {
            List<Double> fallbackFares = extract(normalizedRaw, PRICE);
            List<Double> fallbackKm = extract(normalizedRaw, KM);
            List<Double> fallbackMins = extract(normalizedRaw, MINUTES);
            if (fare <= 0 && !fallbackFares.isEmpty()) fare = Collections.max(fallbackFares);
            if (trip == null && !fallbackKm.isEmpty()) {
                double km = fallbackKm.get(fallbackKm.size() - 1);
                double min = fallbackMins.isEmpty() ? 0 : fallbackMins.get(fallbackMins.size() - 1);
                trip = new Leg(min, km, Integer.MAX_VALUE);
            }
        }

        boolean looksLikeDriverOffer = containsAny(normalizedRaw,
                "accepta", "refuza", "rata de acceptare", "taxe incluse", "in afara razei")
                || (normalizedRaw.contains("bolt") && fares.size() > 0 && legs.size() > 0);

        if (fare >= 3 && fare <= 5000 && (pickup != null || trip != null) && looksLikeDriverOffer) {
            double pickupKm = pickup == null ? 0 : pickup.km;
            double pickupMin = pickup == null ? 0 : pickup.minutes;
            double tripKm = trip == null ? 0 : trip.km;
            double tripMin = trip == null ? 0 : trip.minutes;
            double totalKm = pickupKm + tripKm;
            double totalMin = pickupMin + tripMin;

            if (totalKm > 0 && totalKm <= 1000) {
                lastOfferSeenAt = SystemClock.elapsedRealtime();
                rideMode = OverlayService.MODE_OFFER;
                prefs.edit().putString("rideMode", rideMode).apply();
                Log.d(TAG, "Oferta: fare=" + fare + " pickup=" + pickupKm + "/" + pickupMin
                        + " trip=" + tripKm + "/" + tripMin + " total=" + totalKm + "/" + totalMin);
                sendOffer(fare, totalKm, totalMin, pickupKm, pickupMin, tripKm, tripMin);
                return;
            }
        }

        handleNoOffer(normalizedRaw);
    }

    private void handleNoOffer(String raw) {
        long now = SystemClock.elapsedRealtime();
        boolean boltForeground = DriverStateAccessibilityService.BOLT_PACKAGE.equals(currentForegroundPackage);
        boolean foregroundUnknown = currentForegroundPackage == null || currentForegroundPackage.isEmpty();
        boolean looksLikeBolt = boltForeground || containsAny(raw, "bolt", "numerar", "rata de acceptare");
        boolean activeTrip = containsAny(raw,
                "navigheaza", "navigare", "spre client", "ajuns", "am ajuns", "incepe cursa",
                "cursa in desfasurare", "finalizeaza cursa", "termina cursa", "contacteaza clientul",
                "pickup", "dropoff", "start ride", "end ride");
        boolean idle = containsAny(raw,
                "esti online", "sunt online", "cauta curse", "cautam curse", "asteptam curse",
                "asteapta o cursa", "disponibil", "go online");

        if (OverlayService.MODE_OFFER.equals(rideMode) && now - lastOfferSeenAt > OFFER_GONE_MS) {
            if (activeTrip && (looksLikeBolt || foregroundUnknown)) pauseForTrip();
            else resetToWaiting();
            return;
        }

        if (OverlayService.MODE_PAUSED.equals(rideMode) && idle && (looksLikeBolt || foregroundUnknown)) {
            resetToWaiting();
        }
    }

    private void sendOffer(double fare, double totalKm, double totalMin, double pickupKm, double pickupMin, double tripKm, double tripMin) {
        Intent update = new Intent(OverlayService.ACTION_UPDATE);
        update.setPackage(getPackageName());
        update.putExtra("fare", fare);
        update.putExtra("km", totalKm);
        update.putExtra("minutes", totalMin);
        update.putExtra("pickupKm", pickupKm);
        update.putExtra("pickupMinutes", pickupMin);
        update.putExtra("tripKm", tripKm);
        update.putExtra("tripMinutes", tripMin);
        sendBroadcast(update);
    }

    private void resetToWaiting() {
        rideMode = OverlayService.MODE_WAITING;
        lastOfferSeenAt = 0;
        prefs.edit().putString("rideMode", rideMode).apply();
        sendSimpleOverlayAction(OverlayService.ACTION_RESET);
    }

    private void pauseForTrip() {
        rideMode = OverlayService.MODE_PAUSED;
        lastOfferSeenAt = 0;
        prefs.edit().putString("rideMode", rideMode).apply();
        sendSimpleOverlayAction(OverlayService.ACTION_PAUSE);
    }

    private void sendSimpleOverlayAction(String action) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private double chooseFare(List<FareCandidate> fares, int firstLegTop) {
        if (fares.isEmpty()) return 0;
        FareCandidate best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (FareCandidate f : fares) {
            int d;
            if (firstLegTop != Integer.MAX_VALUE && f.top <= firstLegTop) d = firstLegTop - f.top;
            else d = Math.abs(firstLegTop - f.top);
            if (best == null || d < bestDistance) {
                best = f;
                bestDistance = d;
            }
        }
        return best == null ? 0 : best.fare;
    }

    private String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT)
                .replace(',', '.')
                .replace('•', ' ')
                .replace('·', ' ')
                .replace('–', '-')
                .replace('—', '-');
    }

    private boolean containsAny(String s, String... needles) {
        for (String needle : needles) if (s.contains(needle)) return true;
        return false;
    }

    private Double firstMatch(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        return parseNum(m.group(1));
    }

    private double parseNum(String value) {
        if (value == null) return 0;
        try { return Double.parseDouble(value.replace(',', '.')); }
        catch (Exception ignored) { return 0; }
    }

    private List<Double> extract(String s, Pattern pattern) {
        List<Double> out = new ArrayList<>();
        Matcher m = pattern.matcher(s);
        while (m.find()) {
            double value = parseNum(m.group(1));
            if (value > 0) out.add(value);
        }
        return out;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel("profitride_capture", "ProfitRide analiză", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void cleanupCapture() {
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} virtualDisplay = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        if (recognizer != null) { try { recognizer.close(); } catch (Exception ignored) {} recognizer = null; }
        if (thread != null) { try { thread.quitSafely(); } catch (Exception ignored) {} thread = null; }
        handler = null;
        processing = false;
    }

    @Override public void onDestroy() {
        if (driverStateReceiver != null) {
            try { unregisterReceiver(driverStateReceiver); } catch (Exception ignored) {}
        }
        cleanupCapture();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
