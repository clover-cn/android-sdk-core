package com.webbridgesdk.webbridgekit.retry;

import android.os.Handler;
import android.os.Looper;
import com.webbridgesdk.webbridgekit.util.LogUtils;

/**
 * 重试策略类
 * 提供统一的重试机制
 */
public class RetryPolicy {
    private static final String TAG = "RetryPolicy";
    
    private final int maxRetries;
    private final long baseDelayMs;
    private final double backoffMultiplier;
    private final long maxDelayMs;
    private final Handler handler;
    
    private int currentRetryCount = 0;
    
    public interface RetryCallback {
        void onRetry(int retryCount);
        void onMaxRetriesReached();
    }
    
    public static class Builder {
        private int maxRetries = 3;
        private long baseDelayMs = 1000;
        private double backoffMultiplier = 2.0;
        private long maxDelayMs = 30000;
        
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public Builder baseDelay(long delayMs) {
            this.baseDelayMs = delayMs;
            return this;
        }
        
        public Builder backoffMultiplier(double multiplier) {
            this.backoffMultiplier = multiplier;
            return this;
        }
        
        public Builder maxDelay(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }
        
        public RetryPolicy build() {
            return new RetryPolicy(maxRetries, baseDelayMs, backoffMultiplier, maxDelayMs);
        }
    }
    
    private RetryPolicy(int maxRetries, long baseDelayMs, double backoffMultiplier, long maxDelayMs) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMs = maxDelayMs;
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 执行重试
     */
    public boolean retry(RetryCallback callback) {
        if (currentRetryCount >= maxRetries) {
            LogUtils.w(TAG, "Max retries reached: " + maxRetries);
            callback.onMaxRetriesReached();
            return false;
        }
        
        currentRetryCount++;
        long delay = calculateDelay();
        
        LogUtils.d(TAG, "Scheduling retry " + currentRetryCount + "/" + maxRetries + " after " + delay + "ms");
        
        handler.postDelayed(() -> callback.onRetry(currentRetryCount), delay);
        return true;
    }
    
    /**
     * 重置重试计数
     */
    public void reset() {
        currentRetryCount = 0;
        LogUtils.d(TAG, "Retry count reset");
    }
    
    /**
     * 获取当前重试次数
     */
    public int getCurrentRetryCount() {
        return currentRetryCount;
    }
    
    /**
     * 是否还可以重试
     */
    public boolean canRetry() {
        return currentRetryCount < maxRetries;
    }
    
    /**
     * 计算延迟时间（指数退避）
     */
    private long calculateDelay() {
        long delay = (long) (baseDelayMs * Math.pow(backoffMultiplier, currentRetryCount - 1));
        return Math.min(delay, maxDelayMs);
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        handler.removeCallbacksAndMessages(null);
    }
}
