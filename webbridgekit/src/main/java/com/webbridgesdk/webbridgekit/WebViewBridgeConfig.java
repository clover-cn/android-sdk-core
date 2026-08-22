package com.webbridgesdk.webbridgekit;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * WebBridgeKit v2 配置。默认不限制页面来源，宿主可选配置 origin 规则收窄范围。
 */
public class WebViewBridgeConfig {
    public static final String DEFAULT_ASSET_DOMAIN = "appassets.androidplatform.net";
    public static final String ALLOW_ALL_ORIGINS = "*";

    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    public interface PermissionDelegate {
        void ensurePermission(String feature, String action, PermissionCallback callback);
    }

    private final Set<String> allowedOriginRules;
    private final boolean debugEnabled;
    private final boolean allowLocalAssets;
    private final String assetLoaderDomain;
    private final PermissionDelegate permissionDelegate;

    private WebViewBridgeConfig(Builder builder) {
        Set<String> originRules = new HashSet<>(builder.allowedOriginRules);
        if (originRules.isEmpty()) {
            originRules.add(ALLOW_ALL_ORIGINS);
        }
        this.allowedOriginRules = Collections.unmodifiableSet(originRules);
        this.debugEnabled = builder.debugEnabled;
        this.allowLocalAssets = builder.allowLocalAssets;
        this.assetLoaderDomain = builder.assetLoaderDomain;
        this.permissionDelegate = builder.permissionDelegate;

        if (permissionDelegate == null) {
            throw new IllegalArgumentException("permissionDelegate is required");
        }
    }

    public Set<String> getAllowedOriginRules() {
        return allowedOriginRules;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public boolean isAllowLocalAssets() {
        return allowLocalAssets;
    }

    public String getAssetLoaderDomain() {
        return assetLoaderDomain;
    }

    public PermissionDelegate getPermissionDelegate() {
        return permissionDelegate;
    }

    boolean isUrlAllowed(String url) {
        return true;
    }

    public static class Builder {
        private final Set<String> allowedOriginRules = new HashSet<>();
        private boolean debugEnabled = false;
        private boolean allowLocalAssets = false;
        private String assetLoaderDomain = DEFAULT_ASSET_DOMAIN;
        private PermissionDelegate permissionDelegate;

        public Builder addAllowedOriginRule(String originRule) {
            if (originRule != null && !originRule.trim().isEmpty()) {
                allowedOriginRules.add(originRule.trim());
            }
            return this;
        }

        public Builder setDebugEnabled(boolean debugEnabled) {
            this.debugEnabled = debugEnabled;
            return this;
        }

        public Builder setAllowLocalAssets(boolean allowLocalAssets) {
            this.allowLocalAssets = allowLocalAssets;
            return this;
        }

        public Builder setAssetLoaderDomain(String assetLoaderDomain) {
            if (assetLoaderDomain != null && !assetLoaderDomain.trim().isEmpty()) {
                this.assetLoaderDomain = assetLoaderDomain.trim();
            }
            return this;
        }

        public Builder setPermissionDelegate(PermissionDelegate permissionDelegate) {
            this.permissionDelegate = permissionDelegate;
            return this;
        }

        public WebViewBridgeConfig build() {
            return new WebViewBridgeConfig(this);
        }
    }
}
