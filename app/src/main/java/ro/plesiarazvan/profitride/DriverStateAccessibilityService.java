package ro.plesiarazvan.profitride;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.Locale;

public class DriverStateAccessibilityService extends AccessibilityService {
    public static final String ACTION_DRIVER_STATE = "ro.plesiarazvan.profitride.DRIVER_STATE";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_PACKAGE = "package";

    public static final String EVENT_FOREGROUND = "foreground";
    public static final String EVENT_ACCEPTED = "accepted";
    public static final String EVENT_REJECTED = "rejected";
    public static final String EVENT_FINISHED = "finished";

    public static final String BOLT_PACKAGE = "ee.mtakso.driver";
    public static final String WAZE_PACKAGE = "com.waze";

    private String lastForeground = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();

        if (!pkg.equals(lastForeground)) {
            lastForeground = pkg;
            sendState(EVENT_FOREGROUND, pkg);
        }

        if (!BOLT_PACKAGE.equals(pkg) || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;

        String text = eventText(event);
        if (containsAny(text, "refuza", "refuz", "decline", "respinge")) {
            sendState(EVENT_REJECTED, pkg);
        } else if (containsAny(text, "accepta", "accept")) {
            sendState(EVENT_ACCEPTED, pkg);
        } else if (containsAny(text, "termina cursa", "finalizeaza cursa", "incheie cursa", "finalizeaza", "end trip", "complete ride", "finish ride")) {
            sendState(EVENT_FINISHED, pkg);
        }
    }

    private String eventText(AccessibilityEvent event) {
        StringBuilder b = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) b.append(' ').append(cs);
            }
        }
        if (event.getContentDescription() != null) b.append(' ').append(event.getContentDescription());
        AccessibilityNodeInfo src = event.getSource();
        if (src != null) {
            appendNode(src, b, 0);
            src.recycle();
        }
        return normalize(b.toString());
    }

    private void appendNode(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 2 || out.length() > 1200) return;
        if (node.getText() != null) out.append(' ').append(node.getText());
        if (node.getContentDescription() != null) out.append(' ').append(node.getContentDescription());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                appendNode(child, out, depth + 1);
                child.recycle();
            }
        }
    }

    private boolean containsAny(String s, String... needles) {
        for (String needle : needles) if (s.contains(needle)) return true;
        return false;
    }

    private String normalize(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private void sendState(String event, String pkg) {
        Intent i = new Intent(ACTION_DRIVER_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_EVENT, event);
        i.putExtra(EXTRA_PACKAGE, pkg);
        sendBroadcast(i);
    }

    @Override
    public void onInterrupt() {
    }
}
