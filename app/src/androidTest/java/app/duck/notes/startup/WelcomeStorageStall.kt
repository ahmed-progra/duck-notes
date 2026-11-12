package app.duck.notes.startup

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class WelcomeStorageStall {
    @Volatile var block = true
    val entries = AtomicInteger()
    val release = CountDownLatch(1)
    val exited = CountDownLatch(1)
    @Volatile var startMs: Long? = null
    @Volatile var launchMs: Long? = null
    @Volatile var predrawMs: Long? = null
    @Volatile var readyMs: Long? = null
    @Volatile var enterMs: Long? = null
    @Volatile var enterThread: String? = null
    @Volatile var enterBlock: Boolean? = null
    @Volatile var exitMs: Long? = null

    fun fields(now: Long): Map<String, Any?> = mapOf(
        "abs_ms" to now, "T_launch" to launchMs, "T_start" to startMs, "T_predraw1" to predrawMs,
        "delta_T_launch_ms" to launchMs?.let { now - it },
        "delta_T_start_ms" to startMs?.let { now - it },
        "delta_T_predraw1_ms" to predrawMs?.let { now - it },
    )

    fun event(name: String, now: Long, values: Map<String, Any?> = emptyMap()) {
        val json = JSONObject()
        (fields(now) + values + ("event" to name)).forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        Log.i("WelcomeGate", json.toString())
    }
}
