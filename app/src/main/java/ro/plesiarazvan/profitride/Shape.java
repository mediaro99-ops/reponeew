package ro.plesiarazvan.profitride;

import android.graphics.drawable.GradientDrawable;

public final class Shape {
    private Shape() {}
    public static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        return d;
    }
}
