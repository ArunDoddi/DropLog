package com.droplog.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Device credentials and queued signatures are encrypted with a non-exportable Android key.
object DeviceVault {
    @Synchronized private fun key(alias: String, algorithm: String): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        val generator=KeyGenerator.getInstance(algorithm,"AndroidKeyStore")
        val builder=KeyGenParameterSpec.Builder(alias,if(algorithm=="AES") KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT else KeyProperties.PURPOSE_SIGN)
        if(algorithm=="AES")builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        else builder.setDigests(KeyProperties.DIGEST_SHA256)
        generator.init(builder.build());return generator.generateKey()
    }
    fun pinHash(pin: String): String=Mac.getInstance("HmacSHA256").run {init(key("droplog-pin-v1","HmacSHA256"));Base64.encodeToString(doFinal(pin.toByteArray()),Base64.NO_WRAP)}
    fun encrypt(value: String): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key("droplog-vault-v1","AES"))
        return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        val bytes=Base64.decode(value,Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key("droplog-vault-v1","AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)))
    }
}

class LogApplication: android.app.Application() {
    override fun onCreate(){super.onCreate();Telemetry.initialize();LocalDatabase.initialize(this);OfflineStore.initialize(this)}
}
class SyncWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {if(OfflineStore.sync())Result.retry() else Result.success()} catch(e: Exception){if(e is CancellationException)throw e;Result.retry()}
}

object OfflineStore {
    lateinit var context: Context;private set
    val status=MutableStateFlow("")
    private val syncMutex=Mutex()
    private val queueMutex=Mutex()
    private val dao get()=LocalDatabase.instance.records()
    private val preferences get()=context.getSharedPreferences("offline-access",Context.MODE_PRIVATE)
    @Synchronized fun initialize(c: Context){
        if(::context.isInitialized)return
        context=c.applicationContext
        val connectivity=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(object: ConnectivityManager.NetworkCallback(){override fun onAvailable(network: Network){schedule()}})
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("droplog-sync-safety",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(networkConstraints()).build())
        schedule()
    }
    fun online(): Boolean {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    }
    private fun networkConstraints()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(){WorkManager.getInstance(context).enqueueUniqueWork("droplog-log-sync",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(networkConstraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())}
    fun deviceId(): String {
        val old=preferences.getString("device-id",null);if(old!=null)return old
        val id=UUID.randomUUID().toString();preferences.edit().putString("device-id",id).commit();return id
    }
    suspend fun credential(uid: String): JSONObject?=dao.records("offline_profiles").firstOrNull {it.key=="offline/$uid"}?.let {JSONObject(DeviceVault.decrypt(it.json))}
    suspend fun cachedForPin(pin: String): JSONObject? {
        val hash=DeviceVault.pinHash(pin)
        return dao.records("offline_profiles").map {JSONObject(DeviceVault.decrypt(it.json))}.firstOrNull {it.optString("pin_hash")==hash}
    }
    suspend fun rememberLogin(pin: String,user: JSONObject,grant: JSONObject?){
        if(grant==null)return
        val value=JSONObject().put("user",user).put("offline",grant).put("pin_hash",DeviceVault.pinHash(pin))
        dao.put(listOf(CloudRecord("offline/${user.getString("id")}","offline_profiles",DeviceVault.encrypt(value.toString()),System.currentTimeMillis())))
    }
    suspend fun forgetAccess(uid: String) {dao.delete("offline/$uid");dao.delete("dashboard/$uid");dao.delete("users/$uid")}
    suspend fun offlineLogin(pin: String): JSONObject {
        val now=System.currentTimeMillis()
        require(now>=preferences.getLong("locked-until",0)){"Too many PIN attempts. Wait one minute."}
        val cached=cachedForPin(pin)
        if(cached==null){val attempts=preferences.getInt("attempts",0)+1;preferences.edit().putInt("attempts",if(attempts>=5)0 else attempts).putLong("locked-until",if(attempts>=5)now+60000 else 0).commit();error("PIN not available offline. This employee must sign in online on this tablet first.")}
        require(cached.getJSONObject("offline").getLong("expires")>now){"Offline access expired. Sign in while connected to the internet."}
        preferences.edit().putInt("attempts",0).putLong("locked-until",0).apply()
        val user=cached.getJSONObject("user")
        return JSONObject().put("user",user).put("token",user.getString("id")).put("offline",true)
    }
    suspend fun rememberDashboard(uid: String,data: JSONObject){dao.put(listOf(CloudRecord("dashboard/$uid","dashboards",data.toString(),System.currentTimeMillis())))}
    suspend fun pending(): List<Pair<CloudRecord,JSONObject>> = dao.records("pending").sortedWith(compareBy<CloudRecord>{it.mirroredAt}.thenBy {it.key}).map {it to JSONObject(DeviceVault.decrypt(it.json))}
    fun mealAt(time: Instant=Instant.now()): String {val hour=time.atZone(ZoneId.of("America/Chicago")).hour;return if(hour<12)"Breakfast" else if(hour<15)"Lunch" else "Dinner"}
    suspend fun dashboard(uid: String,throughKey: String? = null): JSONObject {
        val data=dao.records("dashboards").firstOrNull {it.key=="dashboard/$uid"}?.let {JSONObject(it.json)} ?: JSONObject()
        credential(uid)?.let {data.put("user",it.getJSONObject("user"))}
        val queue=pending();val eligible=if(throughKey==null)queue else queue.take(queue.indexOfFirst {it.first.key==throughKey}+1)
        return LocalLogProjection.project(uid,data,eligible.map {it.second})
    }
    suspend fun enqueue(uid: String,operation: String,body: JSONObject): JSONObject=queueMutex.withLock {
        val credential=credential(uid)?:error("Sign in online first to enable offline saving.")
        val grant=credential.getJSONObject("offline");require(grant.getLong("expires")>System.currentTimeMillis()){ "Offline access expired. Sign in online again." }
        if(operation=="meal") {
            body.put("meal",mealAt())
            val meals=dashboard(uid).getJSONArray("meals")
            require(meals.length()<2){"Two free meals are already logged today."}
            require((0 until meals.length()).none {meals.getJSONObject(it).optString("meal")==body.getString("meal")}){"This meal is already logged today."}
        }
        if(operation=="equipment" && body.getString("action")!="start") {
            val shift=dashboard(uid).optJSONObject("shift")?:error("No equipment shift is available on this tablet. Sign in online to refresh it.")
            body.put("expected_shift_id",shift.optString("shift_id",shift.optString("id")))
        }
        require(body.getJSONArray("signature").length()>0 && body.getJSONArray("signature").toString().toByteArray().size<=200000){"Draw a shorter signature before saving."}
        val id=body.getString("request_id");val stamp=Instant.now().toString()
        // Derive meal from the actual signature submission time, including at noon / 3 PM boundaries.
        if(operation=="meal")body.put("meal",mealAt(Instant.parse(stamp)))
        val record=JSONObject().put("uid",uid).put("name",credential.getJSONObject("user").getString("name")).put("operation",operation).put("body",body).put("created",stamp).put("grant_id",grant.getString("id")).put("grant_token",grant.getString("token"))
        val order=maxOf(System.currentTimeMillis(),(dao.records("pending").maxOfOrNull {it.mirroredAt}?:0L)+1)
        dao.put(listOf(CloudRecord("pending/$id","pending",DeviceVault.encrypt(record.toString()),order)))
        schedule();status.value="Logs saved on tablet · waiting to sync"
        JSONObject().put("ok",true).put("queued",true).put("id",id)
    }
    private fun native(value: Any?): Any?=when(value){null,JSONObject.NULL->null;is JSONObject->value.keys().asSequence().associateWith {native(value.opt(it))};is JSONArray->(0 until value.length()).map {native(value.opt(it))};else->value}
    // Sequential replay preserves equipment transitions and uses idempotent request IDs.
    suspend fun sync(): Boolean=Telemetry.measure("offline_sync") { syncInternal() }
    private suspend fun syncInternal(): Boolean=syncMutex.withLock {
        if(!online())return@withLock true
        var retry=false;val blocked=mutableSetOf<String>()
        for((row,record) in pending()) {
            val uid=record.getString("uid");val chain="$uid/${record.getString("operation")}";if(chain in blocked)continue
            try {
                Telemetry.measure("offline_entry_sync") {FirebaseFunctions.getInstance("us-central1").getHttpsCallable("offlineSync").call(native(record)).await()}
                // Update the cached baseline before deleting the pending event so offline re-entry sees confirmed state.
                val baseline=dashboard(uid,row.key)
                for(collection in listOf("cash","events","meals")) {
                    val array=baseline.optJSONArray(collection)?:continue
                    for(i in 0 until array.length())if(array.getJSONObject(i).optString("id")==record.getJSONObject("body").getString("request_id"))array.getJSONObject(i).put("pending",false)
                }
                rememberDashboard(uid,baseline)
                dao.delete(row.key)
            } catch(e: Exception){
                if(e is CancellationException)throw e
                val transient=e !is FirebaseFunctionsException || e.code in listOf(FirebaseFunctionsException.Code.UNAVAILABLE,FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,FirebaseFunctionsException.Code.INTERNAL,FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)
                record.put("error",e.message?:"Sync failed");dao.put(listOf(row.copy(json=DeviceVault.encrypt(record.toString()))));blocked.add(chain)
                if(transient)retry=true
            }
        }
        val remaining=pending()
        Telemetry.queue(remaining.size,remaining.count {it.second.has("error")},remaining.mapNotNull {try {Instant.parse(it.second.optString("created")).toEpochMilli()} catch(_: Exception){null}}.minOrNull())
        status.value=if(remaining.isEmpty())"All logs synced" else "${remaining.size} logs on tablet · ${remaining.count {it.second.has("error")}} need sync review"
        retry
    }
}
