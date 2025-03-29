package app.hayzer.grooveslider.twa;

import android.util.Log;
import com.google.androidbrowserhelper.playbilling.digitalgoods.DigitalGoodsRequestHandler;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;

public class DelegationService extends
        com.google.androidbrowserhelper.trusted.DelegationService {
    private static final String TAG = "DelegationService";
    private BillingClient billingClient;
    private DigitalGoodsRequestHandler handler;
    private boolean handlerRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DelegationService onCreate started");

        // Initialize BillingClient first
        billingClient = BillingClient.newBuilder(getApplicationContext())
                .enablePendingPurchases()
                .setListener((billingResult, purchases) -> {
                    Log.d(TAG, "Purchase update received: " + purchases);
                })
                .build();

        // Connect to the billing service
        connectToBillingService();
    }

    private void connectToBillingService() {
        Log.d(TAG, "Connecting to billing service...");
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected successfully");
                    // Now register the DigitalGoodsRequestHandler
                    registerHandler();
                } else {
                    Log.e(TAG, "Billing client connection failed: " + billingResult.getDebugMessage() +
                            " Code: " + billingResult.getResponseCode());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.e(TAG, "Billing service disconnected");
                // Try to reconnect if disconnected
                handlerRegistered = false;
                new android.os.Handler().postDelayed(() -> {
                    if (!handlerRegistered) {
                        Log.d(TAG, "Attempting to reconnect billing service");
                        connectToBillingService();
                    }
                }, 5000); // Wait 5 seconds before reconnecting
            }
        });
    }

    private void registerHandler() {
        if (handlerRegistered) {
            Log.d(TAG, "Handler already registered, skipping");
            return;
        }

        try {
            handler = new DigitalGoodsRequestHandler(getApplicationContext());
            registerExtraCommandHandler(handler);
            handlerRegistered = true;
            Log.d(TAG, "DigitalGoodsRequestHandler registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error registering DigitalGoodsRequestHandler: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (billingClient != null) {
            billingClient.endConnection();
            Log.d(TAG, "Billing client connection ended");
        }
    }
}