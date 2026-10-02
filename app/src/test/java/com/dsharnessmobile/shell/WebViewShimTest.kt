package com.dsharnessmobile.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebViewShim 契约 + 纯函数测试。
 *
 * 两类判据：
 *  1. **纯函数**：`majorOf` 不碰 android.*，JVM 上真实执行（与 CoordBasisPolicy 同手法）。
 *  2. **源码契约**：WebView 的「版本敏感面」（provider 回读 / SDK_INT 设置门 / androidx
 *     特性门）必须只住在 WebViewShim.kt——防副本再次漂移（本 shim 就是为此而生）。
 */
class WebViewShimTest {

  private fun sourceRoot(): File {
    val candidates = listOf(
      File("src/main/java/com/dsharnessmobile/shell"),
      File("app/src/main/java/com/dsharnessmobile/shell"),
    )
    return candidates.firstOrNull { it.isDirectory }
      ?: throw AssertionError("找不到壳侧源码目录（工作目录 = " + File(".").absolutePath + "）")
  }

  private fun source(name: String): String =
    File(sourceRoot(), name).takeIf { it.isFile }?.readText()
      ?: throw AssertionError("找不到壳侧源码 $name")

  /** 去掉注释行（与 CallSiteContractTest 同口径：注释里出现形态名不算命中）。 */
  private fun codeOnly(src: String): String = src.lineSequence()
    .filterNot {
      val t = it.trimStart()
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
    .joinToString("\n")

  /** 全部壳侧 main 源码（含子目录）。 */
  private fun allMainSources(): List<File> =
    sourceRoot().walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

  /** 某 token 出现在哪些 main 源文件的**代码**里（注释剥离后）。 */
  private fun filesContaining(token: String): List<String> =
    allMainSources().filter { codeOnly(it.readText()).contains(token) }.map { it.name }

  // ── majorOf 纯函数行为 ─────────────────────────────────────────────

  @Test
  fun majorOfParsesTheFirstDottedSegment() {
    assertEquals(110, WebViewShim.majorOf("110.0.5481.154.1"))
    assertEquals(9, WebViewShim.majorOf("9.9"))
    assertEquals(94, WebViewShim.majorOf("94.0.4606.85"))
  }

  @Test
  fun majorOfReturnsZeroOnUnreadableInput() {
    // 0 = 显式未知：判据侧（syntax_floor_ok）必须拿不到版本就失败，不许冒充通过。
    assertEquals(0, WebViewShim.majorOf(""))
    assertEquals(0, WebViewShim.majorOf("unknown"))
    assertEquals(0, WebViewShim.majorOf(".5"))
  }

  // ── 单面真源契约 ────────────────────────────────────────────────────

  @Test
  fun providerReadbackLivesOnlyInTheShim() {
    val files = filesContaining("getCurrentWebViewPackage")
    assertEquals(
      "provider 回读必须只有 WebViewShim 一份实现（历史：三处副本、哨兵不一致）",
      listOf("WebViewShim.kt"),
      files,
    )
  }

  @Test
  fun versionGatedSettingsLiveOnlyInTheShim() {
    for (token in listOf("forceDark", "safeBrowsingEnabled")) {
      val files = filesContaining(token)
      assertEquals(
        "版本敏感设置 $token 必须只出现在 WebViewShim.kt（重复即漂移源）",
        listOf("WebViewShim.kt"),
        files,
      )
    }
  }

  @Test
  fun allDynamicPageWebViewsGoThroughTheShim() {
    assertTrue("引擎主页必须吃 baseline", codeOnly(source("MainActivity.kt")).contains("WebViewShim.applyBaseline"))
    assertTrue("控制台必须吃 baseline", codeOnly(source("ConsoleActivity.kt")).contains("WebViewShim.applyBaseline"))
    val browser = codeOnly(source("BrowserHost.kt"))
    assertTrue("隔离浏览器必须叠加 isolation 档", browser.contains("WebViewShim.applyIsolation"))
    assertTrue("隔离浏览器必须先吃 baseline", browser.contains("WebViewShim.applyBaseline"))
    assertTrue("特性门必须走 shim", browser.contains("WebViewShim.supports("))
  }

  @Test
  fun diagnosticsReadTheProviderThroughTheShim() {
    val engine = codeOnly(source("EngineManager.kt"))
    assertTrue("info.txt 的版本字段必须经 shim 取值", engine.contains("WebViewShim.providerVersionName()"))
    assertTrue("MainActivity 版本回读必须经 shim", codeOnly(source("MainActivity.kt")).contains("WebViewShim.provider"))
  }

  @Test
  fun shimKeepsBothDarkModeAndSafeBrowsing() {
    val shim = codeOnly(source("WebViewShim.kt"))
    assertTrue("深色跟随必须保留在 shim 内", shim.contains("FORCE_DARK_AUTO"))
    assertTrue("深色跟随必须有 API 29 门", shim.contains("VERSION.SDK_INT >= 29"))
    assertTrue("safe browsing 必须在隔离档里", shim.contains("safeBrowsingEnabled = true"))
    assertTrue("baseline 必须保留 NO_CACHE 语义", shim.contains("LOAD_NO_CACHE"))
    assertTrue("baseline 必须保留混合内容拒绝", shim.contains("MIXED_CONTENT_NEVER_ALLOW"))
  }

  @Test
  fun localDocsStaysOffTheBaseline() {
    // 静态文档面刻意关 JS、最小能力：统一进 baseline 反而放大攻击面（shim KDoc 已声明此边界）。
    val docs = codeOnly(source("LocalDocs.kt"))
    assertTrue("LocalDocs 必须保持 JS 关闭", docs.contains("javaScriptEnabled = false"))
    assertFalse("LocalDocs 不得吃 baseline（baseline 会开 JS）", docs.contains("applyBaseline"))
  }
}
