package com.example.mylibrary;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.content.Context;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class CameraManager {
    private Context context;
    private WebViewCallback callback;
    private static final int REQUEST_QR_SCAN = 49374;

    public interface WebViewCallback {
        void onQRCodeScanned(String result);
        void onError(String error);
    }

    public CameraManager(Context context, WebViewCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    @JavascriptInterface
    public void startQRCodeScan() {
        if (!(context instanceof Activity)) {
            callback.onError("Context不是Activity");
            return;
        }

        Activity activity = (Activity) context;
        IntentIntegrator integrator = new IntentIntegrator(activity);
        integrator.setPrompt("将二维码放入框内扫描");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(false);
        integrator.setCaptureActivity(QRScanActivity.class);
        integrator.setRequestCode(REQUEST_QR_SCAN);
        integrator.initiateScan();
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_QR_SCAN) {
            IntentResult result = IntentIntegrator.parseActivityResult(resultCode, data);
            if (result != null) {
                if (result.getContents() != null) {
                    callback.onQRCodeScanned(result.getContents());
                } else {
                    callback.onError("扫码取消");
                }
            } else {
                callback.onError("扫码失败");
            }
        }
    }
}