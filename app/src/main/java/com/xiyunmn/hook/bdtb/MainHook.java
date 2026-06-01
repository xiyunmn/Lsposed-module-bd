package com.xiyunmn.hook.bdtb;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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

    // WeakSet：自动随 WebView GC 而释放，无内存泄漏风险
    private static final Set<WebView> sSidebarWebViews =
            Collections.newSetFromMap(new WeakHashMap<>());

    // ──────────────────────────── JS 净化脚本 ────────────────────────────
    private static final String JS_PURIFY = "(function() {\n" +
            "  if (window.__sidebar_purify_injected) return;\n" +
            "  window.__sidebar_purify_injected = true;\n" +
            "\n" +
            "  var style = document.createElement('style');\n" +
            "  style.type = 'text/css';\n" +
            "  style.innerHTML = [\n" +
            "    /* 1. 成为贴吧会员横幅 */\n" +
            "    '.vip-box { display: none !important; }',\n" +
            "    /* 2. 每日签到面板（埋点精准匹配） */\n" +
            "    '.main-container[data-track*=\"checkinTask\"] { display: none !important; }',\n" +
            "    /* 3. 限时活动任务（如去美团刷视频） */\n" +
            "    '.exchange-task-wrapper { display: none !important; }',\n" +
            "    /* 4. 游戏中心完整卡片 */\n" +
            "    '.normal-box.no-foot-btn-mb { display: none !important; }',\n" +
            "    /* 5. 度小满钱包等推广小项（埋点精准匹配） */\n" +
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
            "    inject(); // 兜底\n" +
            "  } else {\n" +
            "    inject();\n" +
            "  }\n" +
            "})();";
    // ─────────────────────────────────────────────────────────────────────

    public MainHook(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;

        try {
            ClassLoader cl = param.getClassLoader();

            // ── 钩子 1：BaseWebView.loadUrl(String) ──
            // 目的：识别侧边栏 WebView 实例并登记
            Class<?> baseWebViewCls = cl.loadClass(
                    "com.baidu.tieba.browser.core.webview.base.BaseWebView");
            Method loadUrl = baseWebViewCls.getMethod("loadUrl", String.class);
            hook(loadUrl, LoadUrlHooker.class);

            // ── 钩子 2：WebViewClient.onPageFinished(WebView, String) ──
            // 目的：页面加载完成后对已登记实例注入净化脚本
            // 注意：若 Tieba 自定义 WebViewClient 子类覆写了此方法且不调 super，
            //       需额外 hook 该子类。观察日志中如无注入，再追加。
            Method onPageFinished = WebViewClient.class.getMethod(
                    "onPageFinished", WebView.class, String.class);
            hook(onPageFinished, OnPageFinishedHooker.class);

            log("[TiebaPurify] Hooks installed successfully.");

        } catch (Exception e) {
            log("[TiebaPurify] Hook setup failed: " + e);
        }
    }

    // ═══════════════════════════ Hooker 1 ═══════════════════════════════
    @XposedHooker
    static class LoadUrlHooker implements XposedInterface.Hooker {

        /**
         * loadUrl 调用前读取 URL 参数即可，无需等待返回值。
         * 用 @BeforeInvocation 避免 loadUrl 内部修改 URL 后判断偏差。
         */
        @BeforeInvocation
        static void before(XposedInterface.BeforeHookCallback cb) {
            Object rawUrl = cb.getArgs()[0];
            if (!(rawUrl instanceof String)) return;

            String url = (String) rawUrl;
            if (!url.contains(SIDEBAR_URL_KEYWORD)) return;

            // thisObject 是 BaseWebView，它继承自 android.webkit.WebView
            Object thisObj = cb.getThisObject();
            if (!(thisObj instanceof WebView)) return;

            WebView wv = (WebView) thisObj;
            synchronized (sSidebarWebViews) {
                sSidebarWebViews.add(wv);
            }
            // 此处故意不打日志以减少性能开销；
            // 调试期可取消注释：
            // android.util.Log.d("TiebaPurify", "Sidebar WebView registered: " + wv);
        }
    }

    // ═══════════════════════════ Hooker 2 ═══════════════════════════════
    @XposedHooker
    static class OnPageFinishedHooker implements XposedInterface.Hooker {

        @AfterInvocation
        static void after(XposedInterface.AfterHookCallback cb) {
            Object[] args = cb.getArgs();
            if (!(args[0] instanceof WebView)) return;

            WebView wv   = (WebView) args[0];
            String  url  = (args[1] instanceof String) ? (String) args[1] : "";

            // 双重校验：① 实例是否为已登记侧边栏 WebView
            //           ② URL 是否仍为侧边栏页（防止该 WebView 复用加载其他页面）
            boolean isSidebar;
            synchronized (sSidebarWebViews) {
                isSidebar = sSidebarWebViews.contains(wv);
            }
            if (!isSidebar || !url.contains(SIDEBAR_URL_KEYWORD)) return;

            // 切换到主线程安全注入（onPageFinished 本身在主线程，post 保证时序正确）
            wv.post(() -> wv.evaluateJavascript(JS_PURIFY, null));
        }
    }
}    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);
        // 在这里添加针对特定应用的 Hook 逻辑
        // if (param.getPackageName().equals("com.target.package")) {
        //     // ...
        // }
    }

    /**
     * 这是一个简单的 Hooker 示例。
     */
    @XposedHooker
    private static class ExampleHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            // 在方法执行前执行的逻辑
        }

        // @AfterInvocation
        // public static void after(@NonNull AfterHookCallback callback) {
        //     // 在方法执行后执行的逻辑
        // }
    }
}            "  } else {\n" +
            "    inject();\n" +
            "  }\n" +
            "})();";
    // ─────────────────────────────────────────────────────────────────────

    public MainHook(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;

        try {
            ClassLoader cl = param.getClassLoader();

            // ── 钩子 1：BaseWebView.loadUrl(String) ──
            // 目的：识别侧边栏 WebView 实例并登记
            Class<?> baseWebViewCls = cl.loadClass(
                    "com.baidu.tieba.browser.core.webview.base.BaseWebView");
            Method loadUrl = baseWebViewCls.getMethod("loadUrl", String.class);
            hook(loadUrl, LoadUrlHooker.class);

            // ── 钩子 2：WebViewClient.onPageFinished(WebView, String) ──
            // 目的：页面加载完成后对已登记实例注入净化脚本
            // 注意：若 Tieba 自定义 WebViewClient 子类覆写了此方法且不调 super，
            //       需额外 hook 该子类。观察日志中如无注入，再追加。
            Method onPageFinished = WebViewClient.class.getMethod(
                    "onPageFinished", WebView.class, String.class);
            hook(onPageFinished, OnPageFinishedHooker.class);

            log("[TiebaPurify] Hooks installed successfully.");

        } catch (Exception e) {
            log("[TiebaPurify] Hook setup failed: " + e);
        }
    }

    // ═══════════════════════════ Hooker 1 ═══════════════════════════════
    @XposedHooker
    static class LoadUrlHooker implements XposedInterface.Hooker {

        /**
         * loadUrl 调用前读取 URL 参数即可，无需等待返回值。
         * 用 @BeforeInvocation 避免 loadUrl 内部修改 URL 后判断偏差。
         */
        @BeforeInvocation
        static void before(XposedInterface.BeforeHookCallback cb) {
            Object rawUrl = cb.getArgs()[0];
            if (!(rawUrl instanceof String)) return;

            String url = (String) rawUrl;
            if (!url.contains(SIDEBAR_URL_KEYWORD)) return;

            // thisObject 是 BaseWebView，它继承自 android.webkit.WebView
            Object thisObj = cb.getThisObject();
            if (!(thisObj instanceof WebView)) return;

            WebView wv = (WebView) thisObj;
            synchronized (sSidebarWebViews) {
                sSidebarWebViews.add(wv);
            }
            // 此处故意不打日志以减少性能开销；
            // 调试期可取消注释：
            // android.util.Log.d("TiebaPurify", "Sidebar WebView registered: " + wv);
        }
    }

    // ═══════════════════════════ Hooker 2 ═══════════════════════════════
    @XposedHooker
    static class OnPageFinishedHooker implements XposedInterface.Hooker {

        @AfterInvocation
        static void after(XposedInterface.AfterHookCallback cb) {
            Object[] args = cb.getArgs();
            if (!(args[0] instanceof WebView)) return;

            WebView wv   = (WebView) args[0];
            String  url  = (args[1] instanceof String) ? (String) args[1] : "";

            // 双重校验：① 实例是否为已登记侧边栏 WebView
            //           ② URL 是否仍为侧边栏页（防止该 WebView 复用加载其他页面）
            boolean isSidebar;
            synchronized (sSidebarWebViews) {
                isSidebar = sSidebarWebViews.contains(wv);
            }
            if (!isSidebar || !url.contains(SIDEBAR_URL_KEYWORD)) return;

            // 切换到主线程安全注入（onPageFinished 本身在主线程，post 保证时序正确）
            wv.post(() -> wv.evaluateJavascript(JS_PURIFY, null));
        }
    }
}    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);
        // 在这里添加针对特定应用的 Hook 逻辑
        // if (param.getPackageName().equals("com.target.package")) {
        //     // ...
        // }
    }

    /**
     * 这是一个简单的 Hooker 示例。
     */
    @XposedHooker
    private static class ExampleHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            // 在方法执行前执行的逻辑
        }

        // @AfterInvocation
        // public static void after(@NonNull AfterHookCallback callback) {
        //     // 在方法执行后执行的逻辑
        // }
    }
}
