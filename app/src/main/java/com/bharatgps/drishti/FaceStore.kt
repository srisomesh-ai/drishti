package com.bharatgps.drishti

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Person(val name: String, val embeddings: MutableList<FloatArray>)

/** Saves known people (name + face embeddings) to a private file on the phone. Nothing leaves the phone. */
class FaceStore(context: Context) {
    private val file = File(context.filesDir, "people.json")
    val people = mutableListOf<Person>()

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val embs = mutableListOf<FloatArray>()
                val ea = o.getJSONArray("e")
                for (j in 0 until ea.length()) {
                    val v = ea.getJSONArray(j)
                    embs.add(FloatArray(v.length()) { v.getDouble(it).toFloat() })
                }
                people.add(Person(o.getString("name"), embs))
            }
        } catch (_: Exception) { }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        for (p in people) {
            val ea = JSONArray()
            for (e in p.embeddings) ea.put(JSONArray().apply { e.forEach { put(it.toDouble()) } })
            arr.put(JSONObject().put("name", p.name).put("e", ea))
        }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun add(name: String, embs: List<FloatArray>) {
        val existing = people.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) existing.embeddings.addAll(embs)
        else people.add(Person(name, embs.toMutableList()))
        save()
    }

    @Synchronized
    fun remove(name: String) {
        people.removeAll { it.name == name }
        save()
    }

    /** Returns best matching name and its cosine similarity. */
    @Synchronized
    fun match(emb: FloatArray): Pair<String, Float>? {
        var best: String? = null
        var bestSim = -1f
        for (p in people) for (e in p.embeddings) {
            val s = cosine(emb, e)
            if (s > bestSim) { bestSim = s; best = p.name }
        }
        return best?.let { it to bestSim }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d // both are L2-normalised
    }
}
