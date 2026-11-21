package jp.xiaqui.artifact

import android.app.*
import android.app.job.*
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

internal class AutoSettings(context: Context) {
    val prefs=context.getSharedPreferences("automatic-updates-v1",Context.MODE_PRIVATE)
    init {
        if (!prefs.getBoolean("independent-switches-v2", false)) {
            val migrated = migrateAutoPreferences(
                prefs.getBoolean("paused", false),
                prefs.getBoolean("download", true)
            )
            prefs.edit()
                .putBoolean("startup", migrated.startup)
                .putBoolean("periodic", migrated.periodic)
                .putBoolean("download-v2", migrated.download)
                .putBoolean("wifiOnly", true)
                .putBoolean("independent-switches-v2", true)
                .commit()
        }
    }
    var startupEnabled: Boolean
        get()=prefs.getBoolean("startup",true)
        set(value) { prefs.edit().putBoolean("startup",value).commit() }
    var periodicEnabled: Boolean
        get()=prefs.getBoolean("periodic",false)
        set(value) { prefs.edit().putBoolean("periodic",value).commit() }
    var downloadEnabled: Boolean
        get()=prefs.getBoolean("download-v2",true)
        set(value) { prefs.edit().putBoolean("download-v2",value).commit() }
    fun state(): String {
        val summary=prefs.getString("summary","自動確認は未実行").orEmpty()
        val status=if (AutoUpdates.running.get()) "確認中・キャッシュを表示" else "最終処理"
        return "$status · $summary\n最終確認 ${StoreTime.local(prefs.getLong("attempt",0))} / 最終成功 ${StoreTime.local(prefs.getLong("success",0))}"
    }
}

internal data class AutoPreferences(val startup: Boolean, val periodic: Boolean, val download: Boolean)
internal fun migrateAutoPreferences(paused: Boolean, download: Boolean) =
    if (paused) AutoPreferences(false, false, false) else AutoPreferences(true, false, download)

internal enum class AutoTrigger { STARTUP, PERIODIC, MANUAL }

internal data class NetworkState(val connected: Boolean, val wifi: Boolean)
internal fun networkState(context: Context): NetworkState {
    val manager=context.getSystemService(ConnectivityManager::class.java)
    val caps=manager.getNetworkCapabilities(manager.activeNetwork)
    return NetworkState(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true,
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
}
internal fun downloadAllowed(network: NetworkState, wifiOnly: Boolean, override: Boolean) = network.connected && (!wifiOnly || network.wifi || override)

internal object AutoUpdates {
    private const val JOB=42042
    private const val CHANNEL="prepared-updates"
    private const val NOTICE=42043
    val running=AtomicBoolean(false)
    private val checkedFiles=java.util.concurrent.ConcurrentHashMap<String,String>()
    private fun key(repo: String, a: ReleaseApk) = "$repo:${a.identity()}"
    private fun stamp(file: java.io.File) = "${file.name}:${file.length()}:${file.lastModified()}"
    private val executor=Executors.newSingleThreadExecutor()
    fun schedule(context: Context) {
        val manager=context.getSystemService(JobScheduler::class.java)
        val settings=AutoSettings(context)
        if(!settings.periodicEnabled) { manager.cancel(JOB); return }
        if(manager.allPendingJobs.any { it.id==JOB }) return
        manager.schedule(JobInfo.Builder(JOB,ComponentName(context,UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(6*60*60*1000L).setPersisted(true)
            .setBackoffCriteria(30*60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun startup(context: Context) {
        val app=context.applicationContext
        schedule(app)
        executor.submit {
            runCatching { reconcile(app) }
            val settings=AutoSettings(app)
            if (settings.startupEnabled && startupDue(settings.prefs.getLong("attempt",0),System.currentTimeMillis()))
                run(app, AutoTrigger.STARTUP)
        }
    }
    fun reconcileAsync(context: Context) { val app=context.applicationContext; executor.submit { runCatching { reconcile(app) } } }
    fun submitReconcile(context: Context, done: () -> Unit): Future<*> =
        executor.submit { try { reconcile(context.applicationContext) } finally { done() } }
    fun scheduleReconcile(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(42044,ComponentName(context,UpdateJobService::class.java)).setOverrideDeadline(0).build())
    }
    fun submit(context: Context, finished: () -> Unit): Future<*> = executor.submit {
        try { run(context.applicationContext, AutoTrigger.PERIODIC) } finally { finished() }
    }
    fun manualCandidates(context: Context): List<Registration> {
        val catalog=Catalog(context)
        return catalog.names().mapNotNull(catalog::get).filter { r ->
            val a=r.visible.firstOrNull()?.assets?.singleOrNull()
            val info=a?.let { r.inspected[it.identity()] }
            r.error.isEmpty() && a != null &&
                (info == null || updateEligible(info,runCatching { Installation.installed(context,info.pkg) }.getOrNull(),Build.VERSION.SDK_INT)) &&
                SavedShelf(context).candidate(r.repo,a)?.let { info != null && checkedFiles[key(r.repo,a)]==stamp(it) } != true
        }
    }
    fun manual(context: Context, override: Boolean, done: () -> Unit) = executor.submit {
        try { run(context.applicationContext, AutoTrigger.MANUAL, override) } finally { done() }
    }
    private fun enabled(settings: AutoSettings, trigger: AutoTrigger) = when (trigger) {
        AutoTrigger.STARTUP -> settings.startupEnabled
        AutoTrigger.PERIODIC -> settings.periodicEnabled
        AutoTrigger.MANUAL -> true
    }
    fun run(context: Context, trigger: AutoTrigger, override: Boolean=false) {
        if (!running.compareAndSet(false,true)) return
        val settings=AutoSettings(context)
        val manual=trigger==AutoTrigger.MANUAL
        val prefs=settings.prefs
        var success=0; var failures=0; var held=0; var inspected=0
        try {
            if (!enabled(settings,trigger)) return
            prefs.edit().putLong("attempt",System.currentTimeMillis()).putString("summary","前回処理は未完了・再試行可能").commit()
            val catalog=Catalog(context); val service=StoreService(context,catalog)
            val repos=if (manual) manualCandidates(context).map { it.repo } else catalog.names()
            for(repo in repos) {
                if(Thread.currentThread().isInterrupted || !enabled(settings,trigger)) throw InterruptedException()
                val initial=catalog.get(repo) ?: continue
                if(!manual) service.refresh(initial, inspectApks = false)
                val r=catalog.get(repo) ?: continue
                var outcome="メタデータ確認済み"
                if(r.error.isNotEmpty()) { failures++; outcome=r.error }
                else if (manual || settings.downloadEnabled) {
                    try {
                        outcome=service.prepareUpdate(r) {
                            if (Thread.currentThread().isInterrupted || !enabled(settings,trigger)) throw InterruptedException()
                            if (!manual && !settings.downloadEnabled) throw UpdateDeferred("自動取得を停止しました。")
                            if(!downloadAllowed(networkState(context),true,override))
                                throw UpdateDeferred("Wi-Fi待ち・通信切断のため取得を保留しました。")
                        }
                        when {
                            outcome=="取得成功" || outcome=="保存済み" -> success++
                            outcome.startsWith("保留") -> held++
                        }
                    } catch(e: Exception) {
                        if(e is InterruptedException) throw e
                        if(e is UpdateDeferred) held++ else failures++
                        outcome=storeError(e)
                    }
                }
                prefs.edit().putString("repo:$repo",outcome).commit()
                inspected++
                prefs.edit().putString("summary","確認 $inspected/${repos.size} · 取得 $success / 失敗 $failures / 保留 $held\n$repo · $outcome").commit()
            }
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            val change=prefs.edit().putString("summary","完了 · 確認 $inspected / 取得 $success / 失敗 $failures / 保留 $held" +
                (if(held>0 || failures>0) "\n"+prefs.getString("summary","") else ""))
            if(failures==0 && held==0) change.putLong("success",System.currentTimeMillis())
            change.commit()
            reconcile(context)
        } catch(e: Exception) {
            prefs.edit().putString("summary","未完了 · 取得 $success / 失敗 $failures / 保留 $held\n"+
                if(e is InterruptedException) "中断しました。保存済みAPKは保持します。" else storeError(e)).commit()
        } finally { running.set(false); prefs.edit().putLong("finished",System.currentTimeMillis()).commit() }
    }
    fun notificationsEnabled(context: Context): Boolean {
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun reconcile(context: Context) {
        val shelf=SavedShelf(context)
        val rows=shelf.ready()
        // Reconcile completed files off the UI thread; a changed/invalid file re-enters manual candidates.
        val catalog=Catalog(context)
        checkedFiles.clear()
        catalog.names().mapNotNull(catalog::get).forEach { r ->
            r.visible.firstOrNull()?.assets?.singleOrNull()?.let { a ->
                shelf.verified(r.repo,a)?.let { checkedFiles[key(r.repo,a)]=stamp(it) }
            }
        }
        publishReady(context,rows)
        AutoSettings(context).prefs.edit().putLong("reconciled",System.currentTimeMillis()).commit()
    }
    fun notificationIntent(context: Context, rows: List<Pair<java.io.File,ApkInfo>>): Intent =
        if(rows.size==1) Intent(context,SavedActivity::class.java).putExtra("saved",rows.single().first.name)
        else Intent(context,StoreActivity::class.java).putExtra("shelf",true)
    internal fun publishReady(context: Context, rows: List<Pair<java.io.File,ApkInfo>>) {
        val manager=context.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel(CHANNEL,"更新準備完了",NotificationManager.IMPORTANCE_DEFAULT))
        val prefs=AutoSettings(context).prefs
        val fingerprint=rows.sortedBy { it.first.name }.joinToString { it.first.name+":"+it.second.code }
        if(rows.isEmpty()) {
            manager.cancel(NOTICE); prefs.edit().remove("notified").commit(); return
        }
        if(!notificationsEnabled(context) || prefs.getString("notified","")==fingerprint) return
        val intent=notificationIntent(context,rows)
        val pending=PendingIntent.getActivity(context,NOTICE,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=if(rows.size==1) "${rows.single().second.label} ${rows.single().second.version} 更新準備完了" else "${rows.size}件の更新準備が完了"
        val notification=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText("タップして保存APKを確認・インストール")
            .setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try {
            manager.notify(NOTICE,notification)
            prefs.edit().putString("notified",fingerprint).commit()
        } catch (_: SecurityException) { /* Permission may change between check and notify; APK stays available. */ }
    }
}

class UpdateJobService : JobService() {
    private val tasks=mutableMapOf<Int,Future<*>>()
    private val tokens=mutableMapOf<Int,Int>()
    private var serial=0
    override fun onStartJob(params: JobParameters): Boolean {
        val token=++serial; tokens[params.jobId]=token
        val done: () -> Unit = { android.os.Handler(mainLooper).post {
            if(tokens[params.jobId]==token) { tasks.remove(params.jobId); tokens.remove(params.jobId); jobFinished(params,false) }
        }; Unit }
        tasks[params.jobId]=if(params.jobId==42044) AutoUpdates.submitReconcile(this,done) else AutoUpdates.submit(this,done)
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        tokens.remove(params.jobId); tasks.remove(params.jobId)?.cancel(true); return true
    }
    override fun onDestroy() { tasks.values.forEach { it.cancel(true) }; tasks.clear(); tokens.clear(); super.onDestroy() }
}
class UpdateEvents : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED) AutoSettings(context).prefs.edit().remove("notified").commit()
        if(intent.action==Intent.ACTION_BOOT_COMPLETED || intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) AutoUpdates.schedule(context)
        AutoUpdates.scheduleReconcile(context)
    }
}
