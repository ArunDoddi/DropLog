package com.droplog.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Keeps the existing UI contract while replacing the Python HTTP server.
class CloudFailure(val code: FirebaseFunctionsException.Code,message: String): Exception(message)

class Api(@Suppress("UNUSED_PARAMETER") base: String = "", private val token: String = "") {
    private val auth get() = FirebaseAuth.getInstance()
    private val functions get() = FirebaseFunctions.getInstance("us-central1")

    private fun native(value: Any?): Any? = when(value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { native(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { native(value.opt(it)) }
        else -> value
    }
    private suspend fun invoke(name: String, body: JSONObject): JSONObject {
        try {
            val result=functions.getHttpsCallable(name).call(native(body)).await().data
            @Suppress("UNCHECKED_CAST")
            return JSONObject((result as? Map<String, Any?>) ?: emptyMap<String, Any?>())
        } catch(e: FirebaseFunctionsException) {
            val message=when(e.code) {
                FirebaseFunctionsException.Code.INTERNAL -> "Cloud operation failed ($name). Ask your admin to check the Firebase function logs."
                FirebaseFunctionsException.Code.NOT_FOUND -> "Cloud function $name is unavailable. Check the Firebase project and deployment."
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "Could not reach Firebase. Check your internet connection and try again."
                else -> e.message ?: "Cloud operation failed. Check Firebase setup."
            }
            throw CloudFailure(e.code,message)
        }
    }
    private suspend fun mirrorDashboard(data: JSONObject) {
        // A local cache error must never turn a confirmed cloud write into a false failure.
        try { LocalMirror.dashboard(data) } catch(_: Exception) { }
    }
    suspend fun call(path: String, body: JSONObject? = null): JSONObject = Telemetry.measure(operationName(path)) { callInternal(path,body) }
    private fun operationName(path: String): String = when(path) {
        "/pin-login" -> "pin_login"; "/login" -> "admin_login"; "/cash" -> "cash_save"; "/equipment" -> "equipment_save"
        "/meal" -> "meal_save"; "/dashboard" -> "dashboard_load"; "/reports/range","/reports/previous" -> "report_send"
        "/test-email" -> "test_email"; "/users" -> "users"; "/admins" -> "create_admin"; "/settings" -> "settings"; else -> "session"
    }
    private suspend fun callInternal(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        if(path=="/logout") { auth.signOut(); return@withContext JSONObject().put("ok",true) }
        if(path=="/login" || path=="/pin-login") {
            auth.signOut()
            if(path=="/pin-login" && !OfflineStore.online())return@withContext OfflineStore.offlineLogin(body!!.getString("pin"))
            try {
                val user=if(path=="/login") {
                    auth.signInWithEmailAndPassword(body!!.getString("username").trim(),body.getString("password")).await()
                    val profile=invoke("profile",JSONObject()).getJSONObject("user")
                    require(profile.getString("role")=="manager") { "Admin access required" };profile
                } else {
                    val pin=body!!.getString("pin")
                    val cached=OfflineStore.cachedForPin(pin)
                    val login=invoke("pinLogin",body.put("device_id",OfflineStore.deviceId()).put("offline",cached?.optJSONObject("offline")))
                    auth.signInWithCustomToken(login.getString("customToken")).await()
                    val profile=login.getJSONObject("user")
                    OfflineStore.rememberLogin(pin,profile,login.optJSONObject("offline"))
                    profile
                }
                return@withContext JSONObject().put("token",auth.currentUser!!.uid).put("user",user)
            } catch(e: Exception) {
                auth.signOut()
                if(path=="/pin-login" && (e is CloudFailure && e.code in listOf(FirebaseFunctionsException.Code.UNAVAILABLE,FirebaseFunctionsException.Code.DEADLINE_EXCEEDED) || e is com.google.firebase.FirebaseNetworkException))return@withContext OfflineStore.offlineLogin(body!!.getString("pin"))
                throw e
            }
        }
        require(token.isNotEmpty()){ "Please sign in again" }
        if(path in listOf("/cash","/equipment","/meal")) {
            if(OfflineStore.credential(token)==null && OfflineStore.online() && auth.currentUser?.uid==token)return@withContext invoke(path.removePrefix("/"),body!!).put("queued",false)
            val result=OfflineStore.enqueue(token,path.removePrefix("/"),body!!)
            if(OfflineStore.online())OfflineStore.sync()
            result.put("queued",OfflineStore.pending().any { (record, _) -> record.key=="pending/${body.getString("request_id")}" })
            return@withContext result
        }
        if(path=="/dashboard") {
            if(OfflineStore.online() && auth.currentUser?.uid==token)try {
                val result=invoke("dashboard",JSONObject());OfflineStore.rememberDashboard(token,result);mirrorDashboard(result)
            } catch(e: Exception){if(e !is CloudFailure || e.code !in listOf(FirebaseFunctionsException.Code.UNAVAILABLE,FirebaseFunctionsException.Code.DEADLINE_EXCEEDED))throw e}
            return@withContext OfflineStore.dashboard(token)
        }
        require(OfflineStore.online() && auth.currentUser?.uid==token){"Admin actions require an online sign-in."}
        if(path=="/admin-pin") {
            val current=auth.currentUser ?: error("Please sign in with your admin email first.")
            val email=current.email ?: error("Sign in with your admin email to set your PIN.")
            current.reauthenticate(EmailAuthProvider.getCredential(email,body!!.getString("password"))).await()
            current.getIdToken(true).await();body.remove("password")
        }
        val names=mapOf("/admin-pin" to "setAdminPin","/activity" to "activity", "/meal-server" to "logServer", "/dashboard" to "dashboard", "/cash" to "cash", "/equipment" to "equipment", "/users" to "users", "/admins" to "createAdmin", "/settings" to "settings", "/test-email" to "testEmail", "/reports/previous" to "previousReport", "/reports/range" to "sendReport")
        val name=names[path] ?: error("Unknown cloud operation")
        val data=body ?: JSONObject().put("method","GET")
        val result=invoke(name,data)
        when(path) {
            "/settings" -> try {LocalMirror.settings(result)} catch(_: Exception) { }
            "/users" -> if(body==null) try {LocalMirror.users(result)} catch(_: Exception) { } else if(body.optString("id").isNotBlank())try {OfflineStore.forgetAccess(body.getString("id"))}catch(_: Exception) { }
            "/admin-pin" -> try {OfflineStore.forgetAccess(token)}catch(_: Exception) { }
        }
        result
    }
}
