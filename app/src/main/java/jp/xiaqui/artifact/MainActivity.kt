package jp.xiaqui.artifact

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

open class MainActivity : Activity() {
    private val worker = Executors.newCachedThreadPool()
    private lateinit var panel: LinearLayout
    private lateinit var session: File
    private var task: Future<*>? = null
    private var generation = 0
    private var source: Uri? = null
    private val auth by lazy { StoredAuth(this) }
    private val releases by lazy { GitHubReleases(auth) }
    private var diagnostic = ""
    private var saved: File? = null
    private var protectedApk: File? = null
    private var details = ""
    private var reading: android.os.CancellationSignal? = null
    private val shelf by lazy { SavedShelf(this) }
    private val storeDir get() = shelf.dir

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = File(cacheDir, "imports")
        if (!started) {
            root.deleteRecursively()
            started = true
        }
        session = File(root, UUID.randomUUID().toString())
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (16 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
        }
        setContentView(ScrollView(this).apply { addView(panel) })
        val previous = state?.getString("apk")?.let { File(storeDir, it) }?.takeIf { it.isFile }
        if (previous != null) {
            diagnostic = state?.getString("diagnostic").orEmpty()
            showSaved(previous)
        } else if (state != null) {
            source = runCatching { (state.getParcelable<Uri>("source") ?: Incoming.uri(intent))?.also { rejectCredentialUrl(it.toString()) } }.getOrNull()
            diagnostic = state?.getString("diagnostic").orEmpty()
            home("読み込みを再開できます。")
        } else if (this is SavedActivity) {
            val name = intent.getStringExtra("saved").orEmpty()
            if (name.isNotBlank() && name == File(name).name && File(storeDir, name).isFile) showSaved(File(storeDir, name))
            else home("保存済みAPKがありません。")
        } else receive(intent)
    }

    private fun receive(incoming: Intent) {
        source = try { Incoming.uri(incoming) } catch (_: RuntimeException) { null }
        diagnostic = Incoming.diagnostics(incoming, source)
        if (source != null) load() else home(if (incoming.action == Intent.ACTION_MAIN) "" else "受信内容からファイルまたはURLを取得できませんでした。")
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("apk", saved?.name)
        state.putString("diagnostic", diagnostic)
        state.putParcelable("source", source?.takeIf { it.scheme == "content" })
    }

    private fun page(title: String) {
        panel.removeAllViews()
        panel.addView(TextView(this).apply { text = title; textSize = 22f })
    }

    private fun text(value: String) {
        if (value.isNotEmpty()) panel.addView(TextView(this).apply { text = value; setTextIsSelectable(true) })
    }

    private fun button(label: String, action: () -> Unit) {
        panel.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun home(message: String = "") {
        saved = null
        page("Release APK Installer")
        text(message)
        if (source != null) button("再試行") { load() }
        button("認証設定") { startActivity(Intent(this, StoreActivity::class.java).putExtra("settings", true)) }
        button("ZIP / APK を開く") {
            launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
        }
        button("保存したAPK") { startActivity(Intent(this, StoreActivity::class.java).putExtra("shelf", true)) }
        text(diagnostic)
    }

    private fun cancel() {
        generation++
        task?.cancel(true)
        val old = session
        val signal = reading
        worker.execute { old.deleteRecursively(); signal?.cancel() }
        session = File(old.parentFile, UUID.randomUUID().toString())
        home("処理を取り消しました。")
    }

    private fun <T> work(title: String, operation: () -> T, done: (T) -> Unit) {
        val current = ++generation
        val directory = session
        page(title)
        button("キャンセル") { cancel() }
        task = worker.submit {
            try {
                val result = operation()
                runOnUiThread { if (generation == current && !isDestroyed) done(result) }
            } catch (e: Exception) {
                directory.deleteRecursively()
                val reason = when (e) {
                    is ImportFailure -> e.message.orEmpty()
                    is java.util.zip.ZipException -> "ZIP / APKが破損しているか、形式が異なります。"
                    is SecurityException -> "ファイルの読み取り許可がありません。ファイルを選び直してください。"
                    else -> "読み込みに失敗しました。通信、空き容量、ファイル形式を確認してください。"
                }
                runOnUiThread { if (generation == current && !isDestroyed) home(reason) }
            }
        }
    }

    private fun load() {
        val uri = source ?: return
        saved = null
        val link = try { ReleaseLink.parse(uri.toString()) } catch (e: ImportFailure) {
            source = null
            home(e.message.orEmpty())
            return
        }
        if (link != null) {
            openRelease(link)
            return
        }
        val directory = session
        val staged = File(directory, "input.apk")
        val signal = android.os.CancellationSignal()
        reading = signal
        work("読み込み中…", {
            directory.deleteRecursively()
            if (!directory.mkdirs()) throw ImportFailure("一時領域を作成できません。")
            SourceReader(contentResolver).read(uri, staged, signal)
            val index = Archive(staged).inspect()
            if (index.standalone && index.choices.isNotEmpty() &&
                packageManager.getPackageArchiveInfo(staged.path, 0) == null) index.copy(standalone = false)
            else index
        }) { index ->
            when {
                index.standalone -> persist(null)
                index.choices.size == 1 -> persist(index.choices.single())
                else -> {
                    page("保存するAPKを選択")
                    index.choices.forEach { entry -> button(entry) { persist(entry) } }
                    button("キャンセル") { cancel() }
                    text(diagnostic)
                }
            }
        }
    }

    private fun openRelease(link: ReleaseLink) {
        if (!link.list) { selectAsset(link, null); return }
        work("Release一覧を取得中…", { releases.releases(link) }) { items ->
            page(link.repository)
            if (items.isEmpty()) text("公開済みReleaseがありません。")
            items.forEach { item -> button(item.tag) { selectAsset(link, item.id) } }
            button("戻る") { home() }
        }
    }

    private fun selectAsset(link: ReleaseLink, releaseId: Long?) {
        work("APK Assetを取得中…", { releases.assets(link, releaseId) }) { assets ->
            when (assets.size) {
                0 -> home("指定したReleaseに対象のAPK Assetがありません。")
                1 -> downloadRelease(link, assets.single())
                else -> {
                    page("保存するRelease APK")
                    assets.forEach { apk -> button(apk.name) { downloadRelease(link, apk) } }
                    button("戻る") { home() }
                }
            }
        }
    }

    private fun downloadRelease(link: ReleaseLink, apk: ReleaseApk) {
        val directory = session
        work("APKをダウンロード中…", {
            directory.deleteRecursively()
            if (!directory.mkdirs()) throw ImportFailure("一時領域を作成できません。")
            val staged = File(directory, "release.apk")
            releases.download(link, apk, staged)
            checkReleaseApk(staged)
            saveApk(staged, apk.name, directory, link, apk)
        }) { (file, metadata) -> confirm(file, metadata) }
    }

    private fun saveApk(candidate: File, name: String, directory: File, link: ReleaseLink? = null, asset: ReleaseApk? = null): Pair<File, String> {
        val metadata = Installation.inspect(this, candidate)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val origin = if (link != null) "GitHub Release" else if (source?.scheme == "content") "端末ファイル" else "共有URL"
        val dest = shelf.save(candidate, name, Installation.describe(this, candidate), origin,
            link?.repository, link?.tag, asset)
        directory.deleteRecursively()
        return dest to metadata
    }

    private fun persist(entry: String?) {
        val directory = session
        val staged = File(directory, "input.apk")
        work("APKを確認中…", {
            val candidate = if (entry == null) staged else File(directory, "selected.apk").also {
                Archive(staged).extract(entry, it)
                if (!Archive(it).inspect().standalone) throw ImportFailure("選択したファイルはAPKではありません。")
            }
            saveApk(candidate, entry ?: "artifact.apk", directory)
        }) { (file, metadata) -> confirm(file, metadata) }
    }

    override fun onResume() { super.onResume(); AutoUpdates.reconcileAsync(this) }

    private fun showSaved(file: File) {
        if (protectedApk != file) {
            protectedApk?.let(shelf::release)
            shelf.protect(file); protectedApk=file
        }
        work("APKを確認中…", { Installation.inspect(this, file) }) { confirm(file, it) }
    }

    private fun confirm(file: File, metadata: String, message: String = "") {
        saved = file
        details = metadata
        page("保存済みAPKの確認")
        text(metadata)
        text(file.name)
        text(message)
        button("標準インストーラで開く") {
            val settings = Installation.permissionSettings(this)
            when {
                android.os.Build.VERSION.SDK_INT == 23 -> work("標準インストーラに転送中…", {
                    requestMarshmallowInstall(this, file)
                }) { confirm(file, metadata) }
                settings != null -> launch(settings, SETTINGS)
                else -> try { launch(Installation.installer(this, file), INSTALL) }
                    catch (e: ImportFailure) { confirm(file, metadata, e.message.orEmpty()) }
            }
        }
        button("他のファイルを開く") { source = null; home() }
        text(diagnostic)
    }

    private fun launch(intent: Intent, code: Int) {
        try { startActivityForResult(intent, code) }
        catch (_: RuntimeException) {
            val message = "対応するシステム画面を開けません。端末の設定を確認してください。"
            saved?.let { confirm(it, details, message) } ?: home(message)
        }
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK && data?.data != null)
            receive(Intent(Intent.ACTION_VIEW).setDataAndType(data.data, data.type))
        else if (requestCode == SETTINGS || requestCode == INSTALL)
            saved?.let {
                val result = if (requestCode == SETTINGS) "提供元の許可設定から戻りました。" else if (data?.hasExtra(android.content.pm.PackageInstaller.EXTRA_STATUS) == true)
                    installStatus(data.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, 1))
                else when (data?.getIntExtra("android.intent.extra.INSTALL_RESULT", 0)) {
                    -4 -> "インストール先の容量が不足しています。"
                    -7 -> "既存パッケージと署名が一致しません。"
                    -25 -> "OSが旧版への更新を拒否しました。"
                    else -> when (resultCode) {
                    RESULT_OK -> "インストール完了。"
                    RESULT_CANCELED -> "OS画面を閉じました（キャンセルまたは結果通知なし）。"
                    else -> "OSがインストールを拒否しました。署名・容量・端末互換性を確認してください。"
                    }
                }
                android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show()
                confirm(it, details, "保存済みAPKは再度開けます。")
            }
    }

    override fun onDestroy() {
        protectedApk?.let(shelf::release); protectedApk=null
        generation++
        task?.cancel(true)
        val old = session
        val signal = reading
        worker.execute { old.deleteRecursively(); signal?.cancel() }
        session = File(old.parentFile, UUID.randomUUID().toString())
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private var started = false
        private const val PICK = 1
        private const val SETTINGS = 2
        private const val INSTALL = 3
    }
}

class SavedActivity : MainActivity()
