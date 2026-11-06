package com.videokeeper.mobile

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Small durable queue store shared by the Compose UI and the foreground service. */
object QueueStore {
    const val ACTION_QUEUE_UPDATED = "com.videokeeper.mobile.QUEUE_UPDATED"
    const val ACTION_START = "com.videokeeper.mobile.START_QUEUE"
    const val ACTION_CANCEL = "com.videokeeper.mobile.CANCEL_QUEUE"
    private const val PREFS = "export_queue_v2"
    private const val KEY = "items"
    private val lock = Any()

    fun load(context: Context): List<QueueItem> = synchronized(lock) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    add(fromJson(array.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<QueueItem>) = synchronized(lock) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun update(context: Context, updated: QueueItem) {
        val items = load(context).toMutableList()
        val index = items.indexOfFirst { it.id == updated.id }
        if (index >= 0) items[index] = updated else items += updated
        save(context, items)
        context.sendBroadcast(Intent(ACTION_QUEUE_UPDATED).setPackage(context.packageName))
    }

    fun start(context: Context) {
        val intent = Intent(context, MediaProcessingService::class.java).setAction(ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancel(context: Context) {
        val intent = Intent(context, MediaProcessingService::class.java).setAction(ACTION_CANCEL)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun toJson(item: QueueItem) = JSONObject().apply {
        put("id", item.id)
        put("uri", item.uriString)
        put("name", item.name)
        put("destination", item.destinationUriString)
        put("left", item.left.toDouble())
        put("right", item.right.toDouble())
        put("duration", item.sourceDurationMs)
        put("width", item.sourceWidth)
        put("height", item.sourceHeight)
        put("state", item.state.name)
        put("progress", item.progress)
        put("elapsed", item.elapsedMs)
        put("speed", item.speed.toDouble())
        put("output", item.outputUriString)
        put("error", item.error)
        put("updated", item.updatedAt)
    }

    private fun fromJson(json: JSONObject) = QueueItem(
        id = json.optString("id"),
        uriString = json.optString("uri"),
        name = json.optString("name"),
        destinationUriString = json.optString("destination"),
        left = json.optDouble("left", 0.0).toFloat(),
        right = json.optDouble("right", 0.0).toFloat(),
        sourceDurationMs = json.optLong("duration"),
        sourceWidth = json.optInt("width"),
        sourceHeight = json.optInt("height"),
        state = runCatching { QueueState.valueOf(json.optString("state")) }.getOrDefault(QueueState.QUEUED),
        progress = json.optInt("progress"),
        elapsedMs = json.optLong("elapsed"),
        speed = json.optDouble("speed", 0.0).toFloat(),
        outputUriString = json.optString("output").takeIf { it.isNotBlank() && it != "null" },
        error = json.optString("error").takeIf { it.isNotBlank() && it != "null" },
        updatedAt = json.optLong("updated", System.currentTimeMillis())
    )
}
