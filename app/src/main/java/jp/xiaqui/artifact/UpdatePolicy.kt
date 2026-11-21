package jp.xiaqui.artifact

internal class UpdateDeferred(message: String) : java.io.IOException(message)

internal data class RetainedApk(val name: String, val pkg: String, val code: Long, val signer: String,
    val bytes: Long, val time: Long, val automatic: Boolean, val pending: Boolean, val valid: Boolean)

/** Null means no safe plan. Always keep one compatible rollback file per installed package. */
internal fun retentionPlan(rows: List<RetainedApk>, pkg: String, bytes: Long,
    installed: (String) -> ApkInfo?): List<String>? {
    fun fits(left: List<RetainedApk>) = left.size < 24 && left.sumOf { it.bytes } + bytes <= 512L*1024*1024 && left.count { it.pkg == pkg } < 3
    if (fits(rows)) return emptyList()
    val locals=rows.map { it.pkg }.distinct().associateWith { runCatching { installed(it) }.getOrNull() }
    val recovery=rows.filter { r ->
        val local=locals[r.pkg]
        r.valid && local != null && r.signer.isNotBlank() && r.signer==local.signer && r.code <= local.code
    }.groupBy { it.pkg }.values.mapNotNull { group -> group.maxWithOrNull(compareBy<RetainedApk> { it.code }.thenBy { it.time })?.name }.toSet()
    val candidates=rows.filter { r ->
        val local=locals[r.pkg]
        r.automatic && !r.pending && r.valid && r.name !in recovery && local != null &&
            r.signer.isNotBlank() && r.signer==local.signer && r.code <= local.code
    }.toMutableList()
    val left=rows.toMutableList(); val deleted=mutableListOf<String>()
    while (!fits(left)) {
        val packageLimitViolated = left.count { it.pkg == pkg } >= 3
        val candidate = candidates.filter { !packageLimitViolated || it.pkg == pkg }.minByOrNull { it.time }
            ?: return null
        candidates.remove(candidate)
        left.remove(candidate); deleted+=candidate.name
    }
    return deleted
}

internal fun updateEligible(info: ApkInfo, local: ApkInfo?, api: Int) =
    info.sdk.supports(api) && compareInstalled(info,local,false)=="更新候補"

internal fun startupDue(last: Long, now: Long) = last == 0L || now < last || now-last >= 15*60*1000L
