package jp.xiaqui.artifact

import android.content.Context
import java.io.File

internal fun storeError(e: Exception) = when {
    e is UpdateDeferred -> e.message ?: "取得を保留しました。"
    e is ImportFailure -> e.message ?: "APK取得失敗"
    e is java.util.zip.ZipException -> "破損APKです。"
    e is java.net.SocketTimeoutException || e is java.net.UnknownHostException -> "通信失敗。接続を確認し再試行してください。"
    e is java.io.IOException && e.message.orEmpty().contains("ENOSPC") -> "端末の空き容量が不足しています。"
    e is java.io.IOException -> "通信・ファイル読取に失敗しました。再試行してください。"
    else -> "APKまたはRelease情報を読み取れません。"
}
internal class StoreService(private val context: Context, val catalog: Catalog,
    private val api: GitHubReleases = GitHubReleases(StoredAuth(context)),
    private val installed: (String) -> ApkInfo? = { Installation.installed(context,it) }) {
    val shelf = SavedShelf(context)
    private val temp = File(context.cacheDir, "store-imports")
    init {
        synchronized(tempLock) {
            if (!cleaned) { temp.deleteRecursively(); cleaned = true }
            temp.mkdirs()
        }
    }
    private fun <T> fetch(r: Registration, asset: ReleaseApk, allowed: () -> Unit = {}, apply: (File, ApkInfo) -> T): T {
        if (temp.usableSpace < asset.size + 4L * 1024 * 1024) throw ImportFailure("端末の空き容量が不足しています。")
        val staged = File.createTempFile("release-", ".apk", temp)
        try {
            api.download(r.link, asset, staged, allowed)
            checkReleaseApk(staged)
            val info = Installation.describe(context, staged)
            if (info.signer.isEmpty()) throw ImportFailure("APKの署名を取得できません。")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return apply(staged, info)
        } finally { staged.delete() }
    }

    fun refresh(r: Registration, inspectApks: Boolean = true) {
        try {
            val history = api.history(r.link)
            val known = r.inspected.toMutableMap()
            val latest = history.firstOrNull { r.previews || !it.preview }
            val errors = mutableListOf<String>()
            if (inspectApks) for (a in latest?.assets.orEmpty().filter { it.identity() !in known }.take(4)) {
                try { known[a.identity()] = fetch(r, a) { _, meta -> meta } }
                catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    errors += "${a.name}: ${storeError(e)}"
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val now = System.currentTimeMillis()
            catalog.update(r) {
                val snapshot = if (errors.isEmpty() || it.fetched == 0L) history else it.history
                val retained = snapshot.flatMap { release -> release.assets }.map { asset -> asset.identity() }.toSet()
                it.copy(history = snapshot, checked = now, fetched = if (errors.isEmpty()) now else it.fetched,
                    error = errors.joinToString("\n"), inspected = (it.inspected + known).filterKeys(retained::contains))
            }
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            catalog.update(r) { it.copy(error = storeError(e), checked = System.currentTimeMillis()) }
        }
    }

    fun inspect(r: Registration, a: ReleaseApk): ApkInfo = synchronized(transferLock) { fetch(r, a) { _, info ->
        catalog.update(r) { it.copy(inspected = it.inspected + (a.identity() to info)) }
        info
    } }

    fun saved(r: Registration, a: ReleaseApk) = shelf.candidate(r.repo,a)
    fun save(r: Registration, release: StoreRelease, a: ReleaseApk): File = synchronized(transferLock) {
        shelf.verified(r.repo,a)?.let { shelf.pin(it); return@synchronized it }
        fetch(r, a) { staged, info ->
            val expected = r.inspected[a.identity()]
            if (expected != null && (expected.pkg != info.pkg || expected.code != info.code || expected.signer != info.signer))
                throw ImportFailure("解析済みAPKと取得したpackageName・versionCode・署名が異なります。Release Assetを確認してください。")
            catalog.update(r) { it.copy(inspected = it.inspected + (a.identity() to info)) }
            synchronized(repoDataLock) {
                if (catalog.get(r.repo)?.revision != r.revision)
                    throw ImportFailure("登録が変更されたため保存を中止しました。現在の登録から再試行してください。")
                shelf.save(staged, a.name, info, "GitHub Release", r.repo, release.tag, a)
            }
        }
    }
    fun prepareUpdate(r: Registration, allowed: () -> Unit): String = synchronized(transferLock) {
        if (r.error.isNotEmpty()) return@synchronized "保留（更新確認失敗）"
        val release=r.visible.firstOrNull() ?: return@synchronized "対象なし"
        val asset=release.assets.singleOrNull() ?: return@synchronized "保留（複数APK・手動選択が必要）"
        val known=r.inspected[asset.identity()]
        fun local(info: ApkInfo) = runCatching { installed(info.pkg) }.getOrNull()
        if (known?.sdkChecked == true && known.sdk.min == 0) return@synchronized "保留（対応OS不明・APK詳細で再解析可能）"
        if (known != null && known.sdk.min > 0 && !updateEligible(known,local(known),android.os.Build.VERSION.SDK_INT))
            return@synchronized "対象なし（導入状態・署名・対応OS）"
        fun validate(info: ApkInfo) {
            if (known != null && (known.pkg!=info.pkg || known.code!=info.code || known.signer!=info.signer))
                throw ImportFailure("解析済みAPKと取得したパッケージ・版・署名が異なります。")
            if (catalog.get(r.repo)?.revision != r.revision) throw ImportFailure("登録設定が変更されたため取得を保留しました。")
            catalog.update(r) { it.copy(inspected=it.inspected+(asset.identity() to info)) }
        }
        shelf.verified(r.repo,asset)?.let { file ->
            val info=Installation.describe(context,file); validate(info)
            if (updateEligible(info,local(info),android.os.Build.VERSION.SDK_INT)) {
                shelf.markPending(file)
                return@synchronized "保存済み"
            }
            return@synchronized "対象なし（保存済みAPKを照合）"
        }
        allowed()
        fetch(r,asset,allowed) { staged, info ->
            validate(info)
            if (!updateEligible(info,local(info),android.os.Build.VERSION.SDK_INT)) "対象なし（導入状態・署名・対応OS）"
            else {
                allowed()
                shelf.save(staged,asset.name,info,"GitHub Release",r.repo,release.tag,asset,automatic=true)
                "取得成功"
            }
        }
    }
    fun delete(file: File) = shelf.delete(file)
    companion object { private val transferLock = Any(); private val tempLock = Any(); private var cleaned = false }
}
