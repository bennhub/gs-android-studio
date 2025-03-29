package app.hayzer.grooveslider.twa;

import android.webkit.JavascriptInterface;
import android.content.Context;

public class WebAppInterface {
    private Context context;
    private LauncherActivity activity;

    public WebAppInterface(Context context, LauncherActivity activity) {
        this.context = context;
        this.activity = activity;
    }

    @JavascriptInterface
    public void restorePurchases() {
        activity.restorePurchases();
    }
}