package io.github.ultradsu.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
                images(o.optJSONArray("images")), images(o.optJSONArray("pendingImages")), o.optString("pendingSourceName"))
        }
    }

    fun write(slots: List<DsuSlot>) = synchronized(lock) {
        val array = JSONArray()
        slots.forEach { s -> array.put(JSONObject().apply {
            put("id", s.id); put("slotName", s.slotName); put("displayName", s.displayName)
            put("userdataGiB", s.userdataGiB); put("sourceUri", s.sourceUri); put("sourceName", s.sourceName)
            put("state", s.state.name); put("systemBytes", s.systemBytes); put("createdAt", s.createdAt)
            put("images", imagesJson(s.images)); put("pendingImages", imagesJson(s.pendingImages)); put("pendingSourceName", s.pendingSourceName)
        }) }
        val output = file.startWrite()
        try { output.write(array.toString(2).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    fun update(id: String, transform: (DsuSlot) -> DsuSlot) = synchronized(lock) {
        write(read().map { if (it.id == id) transform(it) else it })
    }
    private fun images(array: JSONArray?): List<PartitionImage> = if (array == null) emptyList() else
        (0 until array.length()).map { val o = array.getJSONObject(it)
            PartitionImage(o.getString("partition"), o.getString("fileName"), o.getLong("bytes"), o.getString("sourceName")) }
    private fun imagesJson(images: List<PartitionImage>) = JSONArray().apply { images.forEach { image -> put(JSONObject().apply {
        put("partition", image.partition); put("fileName", image.fileName); put("bytes", image.bytes); put("sourceName", image.sourceName)
    }) } }
}
