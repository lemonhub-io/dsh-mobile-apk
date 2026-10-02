package com.dsharnessmobile.shell

import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewFeature

/**
 * WebView 统一 shim：壳侧所有 WebView 的「版本敏感面」唯一入口。
 *
 * 为什么存在：此前同一份版本适配被三个调用点各抄一份且已漂移——
 *  - 内核包回读（getCurrentWebViewPackage + 主版本号正则）在 MainActivity /
 *    BrowserHost / EngineManager 各有一份，且「读不到」的哨兵不一致（0 vs -1）；
 *  - `SDK_INT >= 29 → forceDark` 的深色跟随在 MainActivity / BrowserHost /
 *    ConsoleActivity 里重复三遍；
 *  - androidx.webkit 能力门只在 BrowserHost 有一份私有实现。
 * 任何一处改版本策略就要三处同改；漏一处 = 同一台设备上不同壳侧 WebView 行为不一致。
 * 本对象把这些点收成单面真源：provider 回读 / 特性门 / 两档 settings 配置。
 *
 * 设计边界（有意收窄）：
 *  - 不做 WebView 子类或包装类——构造器在某些 ROM 上自身就是故障源
 *    （provider 缺失/升级中），包装层解决不了，只会多一个被怀疑对象；
 *  - [LocalDocs] 的静态文档 WebView **不走** baseline：它刻意关 JS，
 *    是最小能力面，统一进基线反而放大攻击面。
 */
internal object WebViewShim {

  // ── 内核 provider 回读（原三处副本的唯一实现）────────────────────────

  /** 内核包名（如 com.google.android.webview）；读不到回空串（显式未知，不抛）。 */
  fun providerPackageName(): String = try {
    WebView.getCurrentWebViewPackage()?.packageName ?: ""
  } catch (_: Throwable) {
    // provider 正在切换/更新时该调用可抛——回读通路不得成为故障源。
    ""
  }

  /** 内核版本名（形如 "110.0.5481.154.1"）；读不到回空串。 */
  fun providerVersionName(): String = try {
    WebView.getCurrentWebViewPackage()?.versionName ?: ""
  } catch (_: Throwable) {
    ""
  }

  /** 内核主版本号；读不到记 0（显式未知——判据侧 0 不冒充通过）。 */
  fun providerMajor(): Int = majorOf(providerVersionName())

  /**
   * 「首个点分段数字」主版本号解析。**纯函数**（不碰 android.*）：JVM 单测直接跑，
   * UA-CH 等需要「任意版本串 → major」的调用点也复用它；版本串异形或空串一律回 0。
   */
  internal fun majorOf(versionName: String): Int =
    Regex("(\\d+)\\.").find(versionName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

  // ── androidx.webkit 能力门 ──────────────────────────────────────────

  /**
   * androidx.webkit 特性门：内核包不支持该特性时回 false（不抛）。
   * 与 SDK_INT 门的区别：它按**当前 WebView provider** 的实际能力判，
   * 而不是按系统版本猜——同一台 API 34 机器装旧内核时后者会误判。
   */
  fun supports(feature: String): Boolean = try {
    WebViewFeature.isFeatureSupported(feature)
  } catch (_: Throwable) {
    false
  }

  // ── settings 配置档 ─────────────────────────────────────────────────

  /**
   * 共享基线档：所有承载动态页面的壳侧 WebView（引擎主页 / 控制台 / 隔离浏览器）
   * 必须一致的最小安全与行为集合，版本差异在本函数内一次消化。
   *
   *  - LOAD_NO_CACHE：杜绝 WebView 命中旧 index/旧 bundle 造成「卡 loading 且
   *    无诊断层」（缓存页里没有页面看门狗；荣耀/MagicUI 实测类问题）。
   *  - mediaPlaybackRequiresUserGesture 显式钉 true：值与平台默认一致，但钉死它
   *    才能免疫厂商 ROM 改默认值的漂移——这正是 shim 存在的理由。
   *  - textZoom 故意不设：0.13.3 起持久化退役（D6 收益省略），上游 ui-theme 的
   *    fontSize 原生管内容字号；新增调用点**不要**在本函数外自行加回。
   */
  fun applyBaseline(s: WebSettings) {
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    // file 方案内容访问全关；file:///android_asset 与 /android_res 不受此限，
    // 控制台与本地文档的 assets 加载不受影响。
    s.allowFileAccess = false
    s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    s.cacheMode = WebSettings.LOAD_NO_CACHE
    s.mediaPlaybackRequiresUserGesture = true
    applyFollowSystemDark(s)
  }

  /**
   * 隔离档（在 baseline 之上叠加）：专给 [BrowserHost] 承载**不可信外部页面**的
   * WebView——在基线上再砍掉所有「页面可借力」的能力面。
   */
  fun applyIsolation(s: WebSettings) {
    s.allowContentAccess = false
    @Suppress("DEPRECATION")
    s.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    s.allowUniversalAccessFromFileURLs = false
    s.javaScriptCanOpenWindowsAutomatically = false
    s.setSupportMultipleWindows(false)
    s.setGeolocationEnabled(false)
    // 分辨率预设 = CSS 视口：document-start 注入 width=<cssW>
    // （见 BrowserHost.applyDocumentStartScript），overview/wideViewport 让
    // 页面按注入视口排版，而不是按物理窗口。
    s.loadWithOverviewMode = true
    s.useWideViewPort = true
    applySafeBrowsing(s)
  }

  /**
   * prefers-color-scheme 跟随系统深色（某些厂商 WebView 默认不跟随；
   * FORCE_DARK_AUTO 让 media query 反映系统深浅，「跟随系统」主题依赖它）。
   * setter API 29 才有；更低版本无平台深色语义可跟随，留默认即可。
   */
  private fun applyFollowSystemDark(s: WebSettings) {
    if (Build.VERSION.SDK_INT >= 29) {
      @Suppress("DEPRECATION")
      s.forceDark = WebSettings.FORCE_DARK_AUTO
    }
  }

  /**
   * Safe Browsing：框架 setter API 26 起提供（= minSdk），但实际能力由 provider
   * 决定，旧内核上是静默 no-op；provider 切换窗口期 setter 可能抛——包一层，
   * 失败如实放弃，不让安全开关的缺失变成崩溃。
   */
  private fun applySafeBrowsing(s: WebSettings) {
    try {
      s.safeBrowsingEnabled = true
    } catch (_: Throwable) {
    }
  }
}
