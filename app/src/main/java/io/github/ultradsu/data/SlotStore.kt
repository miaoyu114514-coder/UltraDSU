package io.github.ultradsu.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import io.github.ultradsu.dsu.PreparedImageCleaner

class SlotStore(context: Context) {
    companion object { private val lock = Any() }
    private val file = AtomicFile(File(context.filesDir, "slots.json"))

    fun read(): List<DsuSlot> = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            DsuSlot(o.getString("id"), o.getString("slotName"), o.getString("displayName"),
                o.getInt("userdataGiB"), o.optString("sourceUri"), o.optString("sourceName"),
                SlotState.valueOf(o.getString("state")), o.optLong("systemBytes"), o.getLong("createdAt"),
                images(o.optJSONArray("images")), images(o.optJSONArray("pendingImages")), o.optString("pendingSourceName"),
                o.optBoolean("preparedFilesCleaned", false))
        }
    }

    fun write(slots: List<DsuSlot>) = synchronized(lock) {
        val array = JSONArray()
        slots.forEach { s -> array.put(JSONObject().apply {
            put("id", s.id); put("slotName", s.slotName); put("displayName", s.displayName)
            put("userdataGiB", s.userdataGiB); put("sourceUri", s.sourceUri); put("sourceName", s.sourceName)
            put("state", s.state.name); put("systemBytes", s.systemBytes); put("createdAt", s.createdAt)
            put("images", imagesJson(s.images)); put("pendingImages", imagesJson(s.pendingImages)); put("pendingSourceName", s.pendingSourceName)
            put("preparedFilesCleaned", s.preparedFilesCleaned)
        }) }
        val output = file.startWrite()
        try { output.write(array.toString(2).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    fun update(id: String, transform: (DsuSlot) -> DsuSlot) = synchronized(lock) {
        write(read().map { if (it.id == id) transform(it) else it })
    }
    fun cleanupPrepared(id: String, imagesRoot: File, installationConfirmed: Boolean): Long = synchronized(lock) {
        val slots = read()
        val slot = slots.firstOrNull { it.id == id } ?: return@synchronized 0L
        if (!installationConfirmed || slot.state != SlotState.INSTALLED || slot.preparedFilesCleaned || slot.pendingImages.isNotEmpty()) return@synchronized 0L
        val released = PreparedImageCleaner(imagesRoot).clean(slot, true)
        // Persist only after unlink succeeds. An interruption can safely retry cleanup.
        write(slots.map { if (it.id != id) it else it.copy(preparedFilesCleaned = true,
            images = it.images.map { image -> image.copy(fileName = "") }) })
        released
    }
    private fun images(array: JSONArray?): List<PartitionImage> = if (array == null) emptyList() else
        (0 until array.length()).map { val o = array.getJSONObject(it)
            PartitionImage(o.getString("partition"), o.getString("fileName"), o.getLong("bytes"), o.getString("sourceName")) }
    private fun imagesJson(images: List<PartitionImage>) = JSONArray().apply { images.forEach { image -> put(JSONObject().apply {
        put("partition", image.partition); put("fileName", image.fileName); put("bytes", image.bytes); put("sourceName", image.sourceName)
    }) } }
}
