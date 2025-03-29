/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.hayzer.grooveslider.twa;

import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import com.android.billingclient.api.*;

import java.util.List;

public class LauncherActivity
        extends com.google.androidbrowserhelper.trusted.LauncherActivity {

    private BillingClient billingClient;
    private static final String TAG = "LauncherActivity";
    private WebView twaWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Setting an orientation crashes the app due to the transparent background on Android 8.0
        // Oreo and below. We only set the orientation on Oreo and above. This only affects the
        // splash screen and Chrome will still respect the orientation.
        // See https://github.com/GoogleChromeLabs/bubblewrap/issues/496 for details.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }

        // Set up billing client
        setupBillingClient();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Add a delay to ensure WebView is initialized
        new android.os.Handler().postDelayed(() -> {
            // Find the WebView using reflection
            try {
                java.lang.reflect.Field twaLauncherField =
                        com.google.androidbrowserhelper.trusted.LauncherActivity.class
                                .getDeclaredField("mTwaLauncher");
                twaLauncherField.setAccessible(true);
                Object twaLauncher = twaLauncherField.get(this);

                if (twaLauncher != null) {
                    java.lang.reflect.Field webViewField =
                            twaLauncher.getClass().getDeclaredField("mWebView");
                    webViewField.setAccessible(true);
                    twaWebView = (WebView) webViewField.get(twaLauncher);

                    if (twaWebView != null) {
                        twaWebView.addJavascriptInterface(
                                new WebAppInterface(this), "Android");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error setting up JavaScript interface", e);
            }
        }, 2000); // 2 second delay
    }

    @Override
    protected Uri getLaunchingUrl() {
        // Get the original launch Url.
        Uri uri = super.getLaunchingUrl();
        return uri;
    }

    private void setupBillingClient() {
        billingClient = BillingClient.newBuilder(this)
                .enablePendingPurchases()
                .setListener(this::handlePurchaseUpdated)
                .build();

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected successfully");
                } else {
                    Log.e(TAG, "Billing client connection failed: " + billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.e(TAG, "Billing service disconnected");
                // Retry to connect in a few seconds
                new android.os.Handler().postDelayed(() -> {
                    setupBillingClient();
                }, 5000);
            }
        });
    }

    private void handlePurchaseUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            // Acknowledge the purchase if it hasn't been acknowledged yet
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build();

                billingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Purchase acknowledged");
                    }
                });
            }

            // Notify JavaScript
            if (purchase.getProducts().contains("premium_upgrade")) {
                notifyJavaScript(true);
            }
        }
    }

    // This will be called from JavaScript
    public void restorePurchases() {
        Log.d(TAG, "Restoring purchases...");

        if (billingClient.isReady()) {
            queryPurchases();
        } else {
            // Try to reconnect
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult billingResult) {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        queryPurchases();
                    } else {
                        notifyJavaScript(false);
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    notifyJavaScript(false);
                }
            });
        }
    }

    private void queryPurchases() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        boolean hasPremium = false;

                        for (Purchase purchase : purchases) {
                            if (purchase.getProducts().contains("premium_upgrade") &&
                                    purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                hasPremium = true;
                                break;
                            }
                        }

                        notifyJavaScript(hasPremium);
                        Log.d(TAG, "Purchases restored, premium found: " + hasPremium);
                    } else {
                        notifyJavaScript(false);
                        Log.e(TAG, "Failed to query purchases: " + billingResult.getDebugMessage());
                    }
                }
        );
    }

    private void notifyJavaScript(boolean success) {
        if (twaWebView != null) {
            runOnUiThread(() -> {
                twaWebView.evaluateJavascript(
                        "window.handlePremiumRestored(" + success + ");", null);
            });
        }
    }

    // Inner class for JavaScript interface
    public class WebAppInterface {
        private LauncherActivity activity;

        public WebAppInterface(LauncherActivity activity) {
            this.activity = activity;
        }

        @android.webkit.JavascriptInterface
        public void restorePurchases() {
            activity.restorePurchases();
        }
    }
}