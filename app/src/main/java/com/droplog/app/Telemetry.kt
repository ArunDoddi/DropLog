package com.droplog.app

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Only operation names, counts and fixed error categories. Never pass log bodies or credentials. */
object Telemetry {
    fun initialize() {
        safely {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
            FirebasePerformance.getInstance().setPerformanceCollectionEnabled(!BuildConfig.DEBUG)
        }
    }
    private fun safely(block: () -> Unit) { try { block() } catch(_: Exception) { } }
    suspend fun <T> measure(operation: String, block: suspend () -> T): T {
        val trace=if(BuildConfig.DEBUG) null else try {FirebasePerformance.getInstance().newTrace("droplog_$operation").also {it.start()}} catch(_: Exception){null}
        try {
            val result=block()
            val queued=result is JSONObject && result.optBoolean("queued")
            safely {trace?.putAttribute("outcome",if(queued) "saved_locally" else "success");trace?.incrementMetric("success_count",1)}
            return result
        } catch(e: CancellationException) {
            safely {trace?.putAttribute("outcome","cancelled")};throw e
        } catch(e: Exception) {
            val code=when(e){is CloudFailure->e.code;is FirebaseFunctionsException->e.code;else->null}
            val expected=code in listOf(FirebaseFunctionsException.Code.UNAUTHENTICATED,FirebaseFunctionsException.Code.PERMISSION_DENIED,FirebaseFunctionsException.Code.INVALID_ARGUMENT,FirebaseFunctionsException.Code.ALREADY_EXISTS,FirebaseFunctionsException.Code.FAILED_PRECONDITION,FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)
            safely {
                trace?.putAttribute("outcome",if(expected) "rejected" else "error")
                trace?.putAttribute("error_category",code?.name?.lowercase() ?: "client_error")
                trace?.incrementMetric(if(expected) "rejected_count" else "error_count",1)
                // Sanitized exception: original messages can contain employee information.
                if(!BuildConfig.DEBUG && !expected)FirebaseCrashlytics.getInstance().recordException(IllegalStateException("DropLog $operation: ${code?.name ?: "client_error"}"))
            }
            throw e
        } finally {safely {trace?.stop()}}
    }
    suspend fun diagnostic() {
        if(BuildConfig.DEBUG)return
        measure("diagnostic") {
            safely {FirebaseCrashlytics.getInstance().recordException(IllegalStateException("DropLog monitoring diagnostic: intentional non-fatal test"))}
        }
    }
    fun queue(pending: Int, flagged: Int, oldestMillis: Long?) {
        if(BuildConfig.DEBUG)return
        safely {
            val trace=FirebasePerformance.getInstance().newTrace("droplog_queue_health")
            trace.start();trace.putMetric("pending_count",pending.toLong());trace.putMetric("review_count",flagged.toLong())
            trace.putMetric("oldest_pending_seconds",oldestMillis?.let {((System.currentTimeMillis()-it)/1000).coerceAtLeast(0)} ?: 0L)
            trace.stop()
        }
    }
}
