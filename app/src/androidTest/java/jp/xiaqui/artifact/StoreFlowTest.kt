package jp.xiaqui.artifact

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class StoreFlowTest {
    @Test fun registryRefreshOfflineSaveAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = Catalog(context)
        val repo = "fixtures/store"
        catalog.remove(repo)
        val r = catalog.register(null, "https://github.com/fixtures/store", true)
        val apk = File(context.applicationInfo.sourceDir)
        val json = """[{"id":1,"tag_name":"v1","published_at":"2026-01-01T00:00:00Z","assets":[{"name":"demo.apk","size":${apk.length()},"id":2}]}]"""
        var offline = false
        val api = GitHubReleases(SessionAuth()) { url ->
            object : HttpURLConnection(url) {
                override fun getResponseCode(): Int { if (offline) throw IOException(); return 200 }
                override fun getInputStream() = if (url.path.endsWith("/assets/2")) apk.inputStream() else json.byteInputStream()
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun connect() {}
            }
        }
        val service = StoreService(context,catalog,api)
        var saved: File? = null
        try {
            service.refresh(r, inspectApks = false)
            val metadata = Catalog(context).get(repo)!!
            assertTrue(metadata.inspected.isEmpty()) // Metadata checks must not download APK bodies.
            service.inspect(metadata,metadata.visible.single().assets.single())
            val cached = Catalog(context).get(repo)!!
            val release = cached.visible.single()
            val asset = release.assets.single()
            assertTrue("APK inspection failed: ${cached.error}", cached.inspected.containsKey(asset.identity()))
            val info = cached.inspected.getValue(asset.identity())
            assertTrue(info.signer.isNotBlank())
            assertEquals(context.packageName, info.pkg)
            assertEquals("同一版", compareInstalled(info,Installation.installed(context,info.pkg),true))
            saved = service.save(cached,release,asset)
            assertTrue(saved.isFile)
            offline = true
            service.refresh(cached)
            val failed = catalog.get(repo)!!
            assertTrue(failed.error.isNotEmpty())
            assertEquals(cached.history,failed.history)
            assertEquals(cached.fetched,failed.fetched)
            assertEquals(saved, StoreService(context,Catalog(context),api).save(failed,release,asset))
            val original = requireNotNull(saved)
            original.outputStream().use { out -> out.write(byteArrayOf(1,2,3)) }
            offline = false
            val repaired = service.save(failed,release,asset)
            assertNotEquals(original, repaired)
            assertEquals(3L,original.length())
            assertEquals(apk.length(),repaired.length())
            service.delete(repaired)
            val edited = catalog.register(repo,repo,false)
            catalog.update(r) { it.copy(error="stale") }
            assertEquals(edited,catalog.get(repo))
            assertTrue(catalog.remove(repo))
            assertTrue(saved.isFile)
        } finally { saved?.let(service::delete); catalog.remove(repo) }
    }
}
