package com.neura.ai;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends ComponentActivity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int MEDIA_PERMISSION_REQUEST = 2002;
    private PermissionRequest pendingWebPermission;

    private BillingClient billingClient;
    private boolean billingReady = false;
    private String pendingProductId = null;

    private static final String[] SUBS = {"neura_pro_monthly", "neura_pro_yearly"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        webView = new WebView(this);
        setContentView(webView);
        WebView.setWebContentsDebuggingEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new BillingBridge(), "NEURA_BILLING");

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://appassets.androidplatform.net/")) return false;
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (Exception ignored) { Toast.makeText(MainActivity.this, "Unable to open link", Toast.LENGTH_SHORT).show(); }
                    return true;
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent;
                try { intent = params.createIntent(); }
                catch (Exception e) { filePathCallback = null; return false; }
                try { startActivityForResult(intent, FILE_CHOOSER_REQUEST); return true; }
                catch (Exception e) { filePathCallback = null; return false; }
            }
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    List<String> needed = new ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO);
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA);
                    }
                    if (needed.isEmpty()) request.grant(request.getResources());
                    else { pendingWebPermission = request; ActivityCompat.requestPermissions(MainActivity.this, needed.toArray(new String[0]), MEDIA_PERMISSION_REQUEST); }
                });
            }
        });

        setupBilling();
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack(); else finish();
            }
        });
    }

    /* ---------- Google Play Billing ---------- */

    private void setupBilling() {
        billingClient = BillingClient.newBuilder(this)
                .setListener((BillingResult result, List<Purchase> purchases) -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                        for (Purchase p : purchases) handlePurchase(p);
                    } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
                        sendToJs("{\"state\":\"error\",\"message\":\"Purchase cancel ho gaya.\"}");
                    } else if (result.getResponseCode() != BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                        sendToJs("{\"state\":\"error\",\"message\":\"" + escape(result.getDebugMessage()) + "\"}");
                    }
                })
                .enablePendingPurchases()
                .build();

        billingClient.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult result) {
                billingReady = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
            }
            @Override public void onBillingServiceDisconnected() { billingReady = false; }
        });
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken()).build();
                billingClient.acknowledgePurchase(params, r -> {});
            }
            String pid = purchase.getProducts().isEmpty() ? "" : purchase.getProducts().get(0);
            sendToJs("{\"state\":\"purchased\",\"productId\":\"" + pid + "\"}");
        } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
            sendToJs("{\"state\":\"pending\"}");
        }
    }

    private void launchPurchase(final String productId) {
        if (!billingReady) {
            sendToJs("{\"state\":\"error\",\"message\":\"Google Play se connection nahi bana. Thodi der baad try karo.\"}");
            return;
        }
        List<QueryProductDetailsParams.Product> list = new ArrayList<>();
        list.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder().setProductList(list).build();

        billingClient.queryProductDetailsAsync(params, (BillingResult r, List<ProductDetails> details) -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK || details == null || details.isEmpty()) {
                sendToJs("{\"state\":\"error\",\"message\":\"Plan Play Console mein nahi mila. Product ID check karo.\"}");
                return;
            }
            ProductDetails pd = details.get(0);
            BillingFlowParams.ProductDetailsParams.Builder pdp =
                    BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd);
            try {
                if (pd.getSubscriptionOfferDetails() != null && !pd.getSubscriptionOfferDetails().isEmpty()) {
                    pdp.setOfferToken(pd.getSubscriptionOfferDetails().get(0).getOfferToken());
                }
            } catch (Exception ignored) {}
            BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(pdp.build()))
                    .build();
            runOnUiThread(() -> billingClient.launchBillingFlow(MainActivity.this, flow));
        });
    }

    private void restorePurchases() {
        if (!billingReady) {
            sendToJs("{\"state\":\"error\",\"message\":\"Google Play se connection nahi bana.\"}");
            return;
        }
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                new PurchasesResponseListener() {
                    @Override public void onQueryPurchasesResponse(BillingResult r, List<Purchase> purchases) {
                        if (purchases != null) {
                            for (Purchase p : purchases) {
                                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                    String pid = p.getProducts().isEmpty() ? "" : p.getProducts().get(0);
                                    sendToJs("{\"state\":\"restored\",\"productId\":\"" + pid + "\"}");
                                    return;
                                }
                            }
                        }
                        sendToJs("{\"state\":\"none\"}");
                    }
                });
    }

    private void sendToJs(final String json) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.onBillingResult&&window.onBillingResult(" + JSONObject.quote(json) + ")", null);
            }
        });
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", " ").replace("\"", "'");
    }

    public class BillingBridge {
        @JavascriptInterface
        public void buy(String productId) {
            pendingProductId = productId;
            launchPurchase(productId);
        }
        @JavascriptInterface
        public void restore() { restorePurchases(); }
        @JavascriptInterface
        public boolean available() { return true; }
    }

    /* ---------- lifecycle ---------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MEDIA_PERMISSION_REQUEST && pendingWebPermission != null) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (billingClient != null) { try { billingClient.endConnection(); } catch (Exception ignored) {} }
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
