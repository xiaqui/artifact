package jp.xiaqui.artifact

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Keep original filenames and v1 ledger keys; legacy files need no destructive migration. */
internal class SavedShelf(private val context: Context) {
    private val catalog = Catalog(context)
    val dir = File(context.filesDir, "apks")
    private val ledger = context.getSharedPreferences("saved-release-v1", Context.MODE_PRIVATE)
    fun entries(): List<File> = synchronized(lock) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val missing = ledger.all.keys.filter { it == File(it).name && !File(dir,it).exists() }.map { File(dir,it) }
        (files.sortedByDescending { it.lastModified() } + missing)
    }
    private fun record(file: File) = runCatching { ledger.getString(file.name, null)?.let(::JSONObject) }.getOrNull()

    fun hasOrigin(repo: String): Boolean = synchronized(lock) {
        ledger.all.values.any { value ->
            runCatching { catalog.canonical(JSONObject(value as String).optString("repo")) == repo }.getOrDefault(false)
        }
    }

    private fun candidates(repo: String, asset: ReleaseApk) = dir.listFiles().orEmpty().filter { file ->
        val r = record(file)
        val key = r?.optString("asset")
        val oldKey = "${asset.id}:${asset.size}:${asset.digest.orEmpty()}"
        file.isFile && r != null && catalog.canonical(r.optString("repo")) == repo && (key == asset.identity() || key == oldKey)
    }

    fun candidate(repo: String, asset: ReleaseApk) = candidates(repo, asset).firstOrNull()

    private fun hash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.security.DigestInputStream(file.inputStream(), digest).use { transfer(it, null, MAX_INPUT) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verified(repo: String, asset: ReleaseApk, expected: String? = asset.digest): File? =
        candidates(repo, asset).firstOrNull { file ->
            val sha = expected ?: record(file)?.optString("sha256")?.takeIf { it.length == 64 }
            sha != null && file.length() == asset.size && try { hash(file).equals(sha,true) }
                catch (_: java.io.IOException) { false }
        }

    fun save(staged: File, name: String, info: ApkInfo, source: String, repo: String? = null,
        tag: String? = null, asset: ReleaseApk? = null, automatic: Boolean = false): File = synchronized(lock) {
        val sha = hash(staged)
        if (repo != null && asset != null) verified(repo,asset,sha)?.let { if (!automatic) pin(it); return@synchronized it }
        if (automatic) makeRoom(info.pkg, staged.length())
        val file = ApkStore(dir).save(staged, name, info.pkg)
        val r = JSONObject().put("source",source).put("repo",repo).put("tag",tag).put("asset",asset?.identity())
            .put("name",name).put("package",info.pkg).put("versionCode",info.code).put("versionName",info.version)
            .put("label",info.label).put("automatic",automatic).put("pending",automatic)
            .put("minSdk",info.sdk.min).put("targetSdk",info.sdk.target).put("maxSdk",info.sdk.max)
            .put("sha256",sha).put("signer",info.signer).put("savedAt",System.currentTimeMillis())
        if (!ledger.edit().putString(file.name,r.toString()).commit())
            throw ImportFailure("APKを保存しました。履歴の書き込みに失敗したため、保存したAPK一覧から再度開いてください。")
        file
    }

    fun protect(file: File) = synchronized(lock) { active[file.name]=(active[file.name] ?: 0)+1 }
    fun release(file: File) = synchronized(lock) {
        val count=(active[file.name] ?: 1)-1
        if(count<=0) active.remove(file.name) else active[file.name]=count
        Unit
    }
    fun markPending(file: File) = synchronized(lock) {
        val r=record(file) ?: throw ImportFailure("保存履歴を照合できません。")
        if (!ledger.edit().putString(file.name,r.put("pending",true).toString()).commit())
            throw ImportFailure("インストール待ち状態を保存できません。")
    }
    fun pin(file: File) = synchronized(lock) {
        val r=record(file) ?: return@synchronized
        if (r.optBoolean("automatic")) {
            r.put("automatic",false)
            if (!ledger.edit().putString(file.name,r.toString()).commit()) throw ImportFailure("APKの保護設定を保存できません。")
        }
    }
    private fun makeRoom(pkg: String, bytes: Long) {
        val rows=dir.listFiles().orEmpty().filter { it.isFile }.map { f ->
            val r=record(f)
            val signer=r?.optString("signer").orEmpty()
            val valid=r != null && r.optString("sha256").length==64 && runCatching { hash(f)==r.optString("sha256") }.getOrDefault(false)
            RetainedApk(f.name,f.name.substringBefore('~',""),r?.optLong("versionCode") ?: 0,signer,
                f.length(),r?.optLong("savedAt") ?: 0,r?.optBoolean("automatic") == true,r?.optBoolean("pending") == true || f.name in active,valid)
        }
        val plan=retentionPlan(rows,pkg,bytes) { Installation.installed(context,it) }
            ?: throw UpdateDeferred("保存上限のため保留。手動保存・インストール待ち・唯一の復旧APKは自動削除しません。「保存したAPK」で整理してください。")
        for (name in plan) {
            val file=File(dir,name); val r=record(file) ?: continue
            r.put("removedAt",System.currentTimeMillis())
            if (!ledger.edit().putString(name,r.toString()).commit()) throw ImportFailure("整理履歴を保存できません。")
            if (!file.delete()) throw ImportFailure("旧自動取得APKを整理できません。取得を保留します。")
        }
    }
    fun ready(): List<Pair<File,ApkInfo>> = synchronized(lock) {
        entries().mapNotNull { file ->
            val r=record(file) ?: return@mapNotNull null
            if (!r.optBoolean("pending")) return@mapNotNull null
            val info=ApkInfo(r.optString("package"),r.optString("label",r.optString("package")),r.optString("versionName"),
                r.optLong("versionCode"),r.optString("signer"),ApkSdk(r.optInt("minSdk"),r.optInt("targetSdk"),r.optInt("maxSdk")))
            val local=runCatching { Installation.installed(context,info.pkg) }.getOrNull()
            if (!file.isFile || local != null && local.signer==info.signer && local.code>=info.code) {
                r.put("pending",false); ledger.edit().putString(file.name,r.toString()).commit()
                return@mapNotNull null
            }
            if (!updateEligible(info,local,android.os.Build.VERSION.SDK_INT) ||
                !runCatching { hash(file)==r.optString("sha256") }.getOrDefault(false)) return@mapNotNull null
            file to info
        }.groupBy { it.second.pkg }.values.map { it.maxBy { row -> row.second.code } }
    }

    fun describe(file: File): String {
        val r = record(file)
        val origin = r?.optString("source")?.takeIf { it.isNotBlank() }
            ?: if (r?.has("repo") == true) "GitHub Release" else "旧保存分・出所未記録"
        return buildString {
            appendLine(r?.optString("name")?.ifEmpty { null } ?: file.name)
            append(origin)
            if (r?.has("repo") == true) append(" · " + catalog.canonical(r.optString("repo")) + " " + r.optString("tag"))
            append("\n保存 " + StoreTime.local(r?.optLong("savedAt",file.lastModified()) ?: file.lastModified()))
            if (r?.has("package") == true) append("\n" + r.optString("package") + " " + r.optString("versionName") + " (" + r.optLong("versionCode") + ")")
            if (r?.optBoolean("automatic") == true) append("\n自動取得" + if (r.optBoolean("pending")) "・インストール待ち（保護）" else "・安全な旧世代のみ整理対象")
            if (r?.has("removedAt") == true) append("\n自動整理済み（履歴を保持）")
            if (!file.isFile) append("\n実体が見つかりません。履歴を保持しています。")
        }
    }
    fun delete(file: File) = synchronized(lock) {
        if (!file.delete()) throw ImportFailure("ファイルを削除できません。履歴を保持します。")
        ledger.edit().remove(file.name).apply()
    }
    companion object { private val lock = repoDataLock; private val active=mutableMapOf<String,Int>() }
}
