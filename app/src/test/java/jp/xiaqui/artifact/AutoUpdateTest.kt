package jp.xiaqui.artifact

import org.junit.Assert.*
import org.junit.Test

class AutoUpdateTest {
    private val installed=ApkInfo("pkg","Old Name","2",2,"AA",ApkSdk(23,35))
    private fun row(code: Long, auto: Boolean=true, pending: Boolean=false) = RetainedApk("pkg~$code.apk","pkg",code,"AA",100,code,auto,pending,true)
    @Test fun cleanupOnlyRemovesOldAutoAndKeepsRecoveryAndPending() {
        val rows=listOf(row(1),row(2),row(3,pending=true))
        assertEquals(listOf("pkg~1.apk"),retentionPlan(rows,"pkg",100){installed})
        assertNull(retentionPlan(rows.map { it.copy(automatic=false) },"pkg",100){installed})
        assertNull(retentionPlan(rows.map { it.copy(pending=true) },"pkg",100){installed})
        assertNull(retentionPlan(rows,"pkg",100){null})
        assertNull(retentionPlan(rows.map { it.copy(valid=false) },"pkg",100){installed})
    }
    @Test fun globalLimitAndUnknownLegacyAreProtected() {
        val unknown=(1..24).map { row(it.toLong(),auto=false).copy(pkg="other$it",name="other$it.apk") }
        assertNull(retentionPlan(unknown,"pkg",100){installed})
        val huge=listOf(row(1).copy(bytes=512L*1024*1024))
        assertNull(retentionPlan(huge,"pkg",100){installed})
        assertEquals(emptyList<String>(),retentionPlan(listOf(row(2)),"pkg",100){installed})
    }
    @Test fun perPackageLimitNeverDeletesAnUnrelatedOlderApk() {
        val other=row(1).copy(name="other-old.apk",pkg="other",time=0)
        val rows=listOf(other,row(1),row(2),row(3))
        assertEquals(listOf("pkg~1.apk"),retentionPlan(rows,"pkg",100){ requested ->
            if (requested=="other") installed.copy(pkg="other") else installed
        })
    }
    @Test fun wifiConsentAndThrottleAreExplicit() {
        assertFalse(downloadAllowed(NetworkState(true,false),true,false))
        assertTrue(downloadAllowed(NetworkState(true,false),true,true))
        assertTrue(downloadAllowed(NetworkState(true,true),true,false))
        assertFalse(downloadAllowed(NetworkState(false,false),false,true))
        assertFalse(startupDue(1000,1001)); assertTrue(startupDue(1000,1000+15*60*1000))
        assertTrue(startupDue(0,1000)); assertTrue(startupDue(1000,1))
    }
    @Test fun independentSwitchMigrationPreservesExplicitLegacyOffs() {
        assertEquals(AutoPreferences(true,false,true),migrateAutoPreferences(paused=false,download=true))
        assertEquals(AutoPreferences(true,false,false),migrateAutoPreferences(paused=false,download=false))
        assertEquals(AutoPreferences(false,false,false),migrateAutoPreferences(paused=true,download=true))
    }
    @Test fun renamedLabelsRemainSamePackageButNewPackageNeverUpdates() {
        val changed=installed.copy(label="New Label",code=3)
        assertTrue(updateEligible(changed,installed,23))
        assertFalse(updateEligible(changed.copy(pkg="new.pkg"),installed,23))
        assertFalse(updateEligible(changed.copy(signer="BB"),installed,23))
        assertFalse(updateEligible(changed,null,23))
        assertFalse(updateEligible(changed.copy(sdk=ApkSdk(24,35)),installed,23))
        assertFalse(updateEligible(changed.copy(sdk=ApkSdk()),installed,29))
    }
    @Test fun sdkDisplayUsesActualMinAndNeverTargetAsMaximum() {
        assertEquals("Android 6+",ApkSdk(23,35).label)
        assertTrue(ApkSdk(23,35).supports(29))
        assertFalse(ApkSdk(23,35,28).supports(29))
        assertEquals("Android 6〜9",ApkSdk(23,35,28).label)
        assertEquals("Android 要確認",ApkSdk().label)
        assertThrows(IllegalArgumentException::class.java) { ApkSdkReader.parse(ByteArray(8)) }
    }
    @Test fun installedAppsDoNotDependOnStoreDownloadHistory() {
        for (name in listOf("Forge","IP Messenger","Nearby Games")) {
            val asset=ReleaseApk(1,"app.apk",100,null)
            val meta=installed.copy(label=name,code=3)
            val r=Registration("fixture/$name",true,"r",1,"",listOf(StoreRelease(1,"v3","2026",false,listOf(asset))),mapOf(asset.identity() to meta))
            val display=rowFor(r,true,23) { pkg -> assertEquals("pkg",pkg); installed }
            assertTrue(display.name.contains("$name · Android 6+"))
            assertEquals("導入 2 → 最新 2",display.versions); assertEquals("更新候補",display.status)
            assertFalse(display.versions.contains("(2)"))
            assertTrue(rowFor(r,true,23) { throw SecurityException() }.versions.contains("未確認"))
            assertEquals("端末OS非対応",rowFor(r.copy(inspected=mapOf(asset.identity() to meta.copy(sdk=ApkSdk(24,35)))),true,23){installed}.status)
        }
    }
}
