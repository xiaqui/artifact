package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val apk = ApkInfo("org.test.app", "Sample", "v9", 9, "AA")
    @Test fun repositoryIdentityAndScopedCredentials() {
        assertEquals("a/b", repoName("https://github.com/A/B/releases/tag/preview"))
        for (s in listOf("a/..", "https://github.com.evil/a/b", "https://user@github.com/a/b", "a/b/c"))
            assertThrows(ImportFailure::class.java) { repoName(s) }
        val auth = SessionAuth()
        auth.use("a/b", "first")
        auth.use("a/c", "second")
        auth.forget("A/B")
        assertNull(auth.tokenFor("a/b"))
        assertEquals("second", auth.tokenFor("A/C"))
    }
    @Test fun cachedHistoryAndIndependentSameNameAssets() {
        val x = ReleaseApk(1,"app.apk",100,null)
        val y = x.copy(id = 2)
        val row = StoreRelease(3,"v10","2026-01-01T00:00:00Z",false,listOf(x,y))
        val r = Registration("a/b",false,"revision",42,"offline",
            listOf(row.copy(id=4,preview=true),row),mapOf(x.identity() to apk, y.identity() to apk.copy(pkg="org.test.other")))
        val restored = CatalogCodec.decode(CatalogCodec.encode(r))
        assertEquals(r, restored)
        assertEquals(listOf(row), restored.visible)
        assertNotEquals(x.identity(), x.copy(updated="changed").identity())
    }
    @Test fun compactVersionsSeparateLastSuccessFromFailureAndAmbiguity() {
        val a = ReleaseApk(1, "demo.apk", 2, "abc")
        val h = StoreRelease(3, "hidden-tag", "2026", true, listOf(a))
        val r = Registration("o/r",true,"1",10,"",listOf(h),mapOf(a.identity() to apk),20)
        assertEquals("同一版",rowFor(r,true) { apk }.status)
        val failed = rowFor(r.copy(error="HTTP 404 private detail"),true) { apk }
        assertTrue(failed.versions.contains("前回 v9"))
        assertFalse(failed.versions.contains("(9)"))
        assertEquals("確認失敗 HTTP 404",failed.status)
        val multiple = rowFor(r.copy(history=listOf(h.copy(assets=listOf(a,a.copy(id=2))))),true) { apk }
        assertTrue(multiple.versions.contains("最新 未確認"))
        val uninspected = rowFor(r.copy(inspected=emptyMap()),true) { apk }
        assertEquals("APK要確認",uninspected.status)
        assertEquals("複数APK",multiple.status)
        val legacy = org.json.JSONObject(CatalogCodec.encode(r)).apply { remove("checked") }
        assertEquals(0L,CatalogCodec.decode(legacy.toString()).checked)
    }
    @Test fun versionCodeAndSignerDetermineStatus() {
        assertEquals("更新候補", compareInstalled(apk, apk.copy(version="v100",code=1),true))
        assertEquals("同一版", compareInstalled(apk,apk,true))
        assertTrue(compareInstalled(apk,apk.copy(code=20),true).startsWith("旧版"))
        assertTrue(compareInstalled(apk,apk.copy(signer="BB"),true).contains("署名不一致"))
        assertEquals("未導入", compareInstalled(apk,null,true))
        assertEquals("判定不能", compareInstalled(apk,null,false))
        assertEquals("判定不能", compareInstalled(apk,apk.copy(pkg="wrong"),true))
    }
    @Test fun bulkSummaryCountsPartialFailureWithoutMarkingItUpToDate() {
        val outcomes = listOf("更新候補", "同一版", "失敗", "未確認")
        assertEquals("成功 3（更新 1・同一 1）／失敗 1",
            batchSummary(outcomes) { StoreRow("demo", "", "", it) })
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedException::class.java) { batchSummary(outcomes) { null } } }
        finally { Thread.interrupted() }
    }

    @Test fun publicationTimeUsesDisplayZoneWithoutChangingUtc() {
        val raw = "2026-11-13T18:26:30Z"
        assertEquals("2026/11/14 03:26",StoreTime.published(raw,java.util.TimeZone.getTimeZone("Asia/Tokyo")))
        assertEquals("2026/11/13 18:26",StoreTime.published(raw,java.util.TimeZone.getTimeZone("UTC")))
        assertEquals("—",StoreTime.published("invalid"))
    }
    @Test fun quotaKeepsOldFilesUntilExplicitDeletion() {
        val dir = temp.newFolder()
        val store = ApkStore(dir)
        val saved = (1..3).map { store.save(temp.newFile(), "same.apk", "pkg") }
        assertThrows(ImportFailure::class.java) { store.save(temp.newFile(), "same.apk", "pkg") }
        assertTrue(saved.all { it.exists() })
        saved.first().delete()
        assertTrue(store.save(temp.newFile(),"same.apk","pkg").exists())
    }
}
