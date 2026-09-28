package ro.plesiarazvan.profitride;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
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
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    private static final int NOTIF_ID = 918;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private TextRecognizer recognizer;
    private long lastScan = 0;
    private volatile boolean processing = false;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new Notification.Builder(this, "profitride_capture")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ProfitRide activ")
                .setContentText("Analizează ofertele vizibile pe ecran")
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
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
                cleanup();
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
        thread = new HandlerThread("ProfitRideCapture"); thread.start(); handler = new Handler(thread.getLooper());
        virtualDisplay = projection.createVirtualDisplay("ProfitRide", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        reader.setOnImageAvailableListener(r -> onFrame(r), handler);
    }

    private void onFrame(ImageReader r) {
        long now = SystemClock.elapsedRealtime();
        if (processing || now - lastScan < 1200) { Image skip = r.acquireLatestImage(); if (skip != null) skip.close(); return; }
        Image image = r.acquireLatestImage(); if (image == null) return;
        lastScan = now; processing = true;
        Bitmap bitmap = null;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride(); int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            bitmap = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            padded.recycle();
        } catch (Exception e) { processing = false; }
        finally { image.close(); }
        if (bitmap == null) { processing = false; return; }
        Bitmap finalBitmap = bitmap;
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(this::parseAndBroadcast)
                .addOnCompleteListener(t -> { finalBitmap.recycle(); processing = false; });
    }

    private void parseAndBroadcast(Text result) {
        String raw = result.getText();
        if (raw == null || raw.length() < 4) return;
        String s = raw.replace(',', '.').toLowerCase(Locale.ROOT);
        List<Double> fares = extract(s, "(\\d+(?:\\.\\d+)?)\\s*(?:ron|lei)");
        List<Double> kms = extract(s, "(\\d+(?:\\.\\d+)?)\\s*km\\b");
        List<Double> mins = extract(s, "(\\d+(?:\\.\\d+)?)\\s*(?:min|minute|mins)\\b");
        if (fares.isEmpty() || kms.isEmpty()) return;

        double fare = Collections.max(fares);
        double km = Collections.max(kms);
        double minutes = mins.isEmpty() ? 0 : Collections.max(mins);
        if (fare < 5 || fare > 2000 || km <= 0 || km > 1000) return;

        Intent update = new Intent(OverlayService.ACTION_UPDATE);
        update.setPackage(getPackageName());
        update.putExtra("fare", fare);
        update.putExtra("km", km);
        update.putExtra("minutes", minutes);
        sendBroadcast(update);
    }

    private List<Double> extract(String s, String regex) {
        List<Double> out = new ArrayList<>(); Matcher m = Pattern.compile(regex).matcher(s);
        while (m.find()) { try { out.add(Double.parseDouble(m.group(1))); } catch (Exception ignored) {} }
        return out;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel("profitride_capture", "ProfitRide analiză", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void cleanup() {
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Exception ignored) {} virtualDisplay = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        if (recognizer != null) { try { recognizer.close(); } catch (Exception ignored) {} recognizer = null; }
        if (thread != null) { try { thread.quitSafely(); } catch (Exception ignored) {} thread = null; }
        handler = null;
        processing = false;
    }

    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
