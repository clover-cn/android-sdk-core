package com.webbridgesdk.webbridgekit;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.content.Context;
import android.util.Log;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class CameraManager {
    private static final String TAG = "CameraManager";
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
        try {
            if (!(context instanceof Activity)) {
                Log.e(TAG, "Context is not an Activity");
                callback.onError("Context不是Activity");
                return;
            }

            Activity activity = (Activity) context;

            // 检查相机权限
            if (!PermissionHelper.hasAllCameraPermissions(context)) {
                Log.w(TAG, "Camera permissions not granted");
                callback.onError("缺少相机权限");
                return;
            }

            IntentIntegrator integrator = new IntentIntegrator(activity);
            integrator.setPrompt("将二维码放入框内扫描");
            integrator.setBeepEnabled(true);
            integrator.setOrientationLocked(false);
            integrator.setCaptureActivity(QRScanActivity.class);
            integrator.setRequestCode(REQUEST_QR_SCAN);
            integrator.initiateScan();

            Log.d(TAG, "QR code scan initiated");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start QR code scan: " + e.getMessage(), e);
            callback.onError("启动扫码失败: " + e.getMessage());
        }
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