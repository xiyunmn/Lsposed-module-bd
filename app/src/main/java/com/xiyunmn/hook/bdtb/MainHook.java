package com.xiyunmn.hook.bdtb;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class MainHook extends XposedModule {

    private static final String TARGET_PKG = "com.baidu.tieba";
    private static final String SIDEBAR_URL_KEYWORD = "mainSidebar";

    private static final Set<WebView> sSidebarWebViews =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final String JS_PURIFY = "(function() {\n" +
            "  if (window.__sidebar_purify_injected) return;\n" +
            "  window.__sidebar_purify_injected = true;\n" +
            "\n" +
            "  var style = document.createElement('style');\n" +
            "  style.type = 'text/css';\n" +
            "  style.innerHTML = [\n" +
            "    '.vip-box { display: none !important; }',\n" +
            "    '.main-container[data-track*=\"checkinTask\"] { display: none !important; }',\n" +
            "    '.exchange-task-wrapper { display: none !important; }',\n" +
            "    '.normal-box.no-foot-btn-mb { display: none !important; }',\n" +
            "    '.nor-util-item[data-track*=\"度小满\"] { display: none !important; }'\n" +
            "  ].join('\\n');\n" +
            "\n" +
            "  var inject = function() {\n" +
            "    var head = document.head || document.getElementsByTagName('head')[0]\n" +
            "              || document.documentElement;\n" +
            "    head.appendChild(style);\n" +
            "  };\n" +
            "\n" +
            "  if (document.readyState === 'loading') {\n" +
            "    document.addEventListener('DOMContentLoaded', inject);\n" +
            "    inject();\n" +
            "  } else {\n" +
            "    inject();\n" +
            "  }\n" +
            "})();";

    public MainHook(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;

        try {
            ClassLoader cl = param.getClassLoader();

            Class<?> baseWebViewCls = cl.loadClass("com.baidu.tieba.browser.core.webview.base.BaseWebView");
            Method loadUrl = baseWebViewCls.getMethod("loadUrl", String.class);
            hook(loadUrl, LoadUrlHooker.class);

            Method onPageFinished = WebViewClient.class.getMethod("onPageFinished", WebView.class, String.class);
            hook(onPageFinished, OnPageFinishedHooker.class);

            log("[TiebaPurify] Hooks installed successfully.");

        } catch (Exception e) {
            log("[TiebaPurify] Hook setup failed: " + e);
        }
    }

    @XposedHooker
    static class LoadUrlHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        static void before(XposedInterface.BeforeHookCallback cb) {
            Object rawUrl = cb.getArgs()[0];
            if (!(rawUrl instanceof String)) return;

            String url = (String) rawUrl;
            if (!url.contains(SIDEBAR_URL_KEYWORD)) return;

            Object thisObj = cb.getThisObject();
            if (!(thisObj instanceof WebView)) return;

            WebView wv = (WebView) thisObj;
            synchronized (sSidebarWebViews) {
                sSidebarWebViews.add(wv);
            }
        }
    }

    @XposedHooker
    static class OnPageFinishedHooker implements XposedInterface.Hooker {
        @AfterInvocation
        static void after(XposedInterface.AfterHookCallback cb) {
            Object[] args = cb.getArgs();
            if (!(args[0] instanceof WebView)) return;

            WebView wv   = (WebView) args[0];
            String  url  = (args[1] instanceof String) ? (String) args[1] : "";

            boolean isSidebar;
            synchronized (sSidebarWebViews) {
                isSidebar = sSidebarWebViews.contains(wv);
            }
            if (!isSidebar || !url.contains(SIDEBAR_URL_KEYWORD)) return;

            wv.post(() -> wv.evaluateJavascript(JS_PURIFY, null));
        }
    }
}
