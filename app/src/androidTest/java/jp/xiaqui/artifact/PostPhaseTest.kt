package jp.xiaqui.artifact

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class PostPhaseTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    @Test fun encryptedRepoIsolationReplacementRevocationAndKeyLoss() {
        val vault = StoredAuth(context)
        val a = "fixture/one"
        val b = "fixture/two"
        vault.use(a, "fakeFirst")
        vault.use(b, "fakeOther")
        try {
            vault.use(a,"fakeReplacement")
            val reopened = StoredAuth(context)
            assertEquals("fakeReplacement", reopened.tokenFor(a))
            val ciphertext = File(context.noBackupFilesDir,"release-auth/" + vault.alias(a)).readBytes()
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains("fakeReplacement"))
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry(vault.alias(a))
            try { reopened.tokenFor(a); fail("Expected re-entry") } catch (_: ImportFailure) {}
            assertNull(reopened.tokenFor(a))
            reopened.use(a,"newValue")
            reopened.forget(a)
            assertNull(vault.tokenFor(a))
            assertEquals("fakeOther", vault.tokenFor(b))
        } finally { vault.forget(a); vault.forget(b) }
    }

    // CI invokes these separately with a force-stop between the two instrumentations.
    @Test fun processSeed() {
        val vault = StoredAuth(context)
        vault.useCommon("commonFixture")
        vault.use("fixture/restart", "offlineFixture")
    }
    @Test fun processRead() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartCheck") == "true")
        val vault = StoredAuth(context)
        try {
            assertEquals("commonFixture",vault.commonToken())
            vault.forgetCommon()
            assertNull(vault.commonToken())
            assertEquals("offlineFixture", vault.tokenFor("fixture/restart"))
            val service = GitHubReleases(vault) { url -> object : HttpURLConnection(url) {
                private var authorized = false
                override fun setRequestProperty(key: String, value: String) {
                    if (key == "Authorization") authorized = value == "Bearer offlineFixture"
                }
                override fun getResponseCode() = if (authorized) 200 else 401
                override fun getInputStream() = "[]".byteInputStream()
                override fun disconnect() {}
                override fun connect() {}
                override fun usingProxy() = false
            } }
            assertTrue(service.releases(ReleaseLink("fixture/restart", null, null, true)).isEmpty())
        } finally { vault.forget("fixture/restart") }
    }

    @Test fun legacyAndMissingRecordsSurviveShelfOpen() {
        val shelf = SavedShelf(context)
        shelf.dir.mkdirs()
        val old = File.createTempFile("pre-ledger-", ".apk",shelf.dir)
        val missing = "no-longer-present-test.apk"
        val prefs = context.getSharedPreferences("saved-release-v1",android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(missing,"""{"repo":"fixture/demo","tag":"v1","name":"old.apk","savedAt":1}""").commit()
        try {
            val list = SavedShelf(context).entries()
            assertTrue(old in list)
            assertTrue(list.any { it.name == missing })
            assertTrue(shelf.describe(old).contains("旧保存分"))
            assertTrue(shelf.describe(File(shelf.dir,missing)).contains("実体が見つかりません"))
        } finally { old.delete(); prefs.edit().remove(missing).commit() }
    }

    @Test fun storeScreensStayCapturableWhileSensitiveDialogsAreSecure() {
        val activity = inst.startActivitySync(Intent(context, StoreActivity::class.java)
            .putExtra("settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                val dialog = AlertDialog.Builder(activity).setView(EditText(activity)).create()
                protectSensitiveDialog(dialog)
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                dialog.show()
                protectSensitiveDialog(dialog)
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                dialog.dismiss()
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun sevenRowsAndDetails() {
        AutoSettings(context).apply { startupEnabled=false; periodicEnabled=false }
        val cat = Catalog(context)
        val regs = (1..7).map { n ->
            val repo = "ui/app$n"
            cat.remove(repo)
            val r = cat.register(null, repo, true)
            val a = ReleaseApk(n.toLong(),"private-name.apk", 10,"1234")
            val h = StoreRelease(42,"internal-tag","2026-11-13",true,listOf(a))
            cat.update(r) { it.copy(history=listOf(h), inspected=mapOf(a.identity() to ApkInfo("fixture.app$n",
                if (n == 1) "長い日本語アプリ名称・試験端末での表示確認" else "アプリ $n","0.4",4,"AA")),fetched=20,checked=30,
                error=if(n==2) "HTTP 404（Release一覧・匿名）。fixture details" else "") }
            r
        }
        val activity = inst.startActivitySync(Intent(context,StoreActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val views = all(activity.window.decorView)
                val rows = views.filter { it.isClickable && it.contentDescription?.contains(" → ") == true }
                assertEquals(7,rows.size)
                for (row in rows) {
                    assertTrue(row.height >= 48 * context.resources.displayMetrics.density - 2)
                    for (text in all(row).filterIsInstance<TextView>()) {
                        val layout = requireNotNull(text.layout)
                        assertTrue(layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
                    }
                }
                val flat = views.filterIsInstance<TextView>().joinToString { it.text.toString() }
                assertFalse(flat.contains("internal-tag")); assertFalse(flat.contains("private-name"))
                assertTrue(flat.contains("前回 0.4")); assertFalse(flat.contains("(4)")); assertTrue(flat.contains("HTTP 404"))
                val decor = activity.window.decorView
                val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir,"seven-rows.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
                rows[1].performClick()
                assertTrue(all(decor).filterIsInstance<TextView>().any { it.text.contains("fixture details") })
            }
        } finally {
            inst.runOnMainSync { activity.finish() }
            regs.forEach { cat.remove(it.repo) }
        }
    }

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
}
