package com.bharatgps.drishti

import android.graphics.RectF

data class Det(val label: String, val rect: RectF, val score: Float)          // normalised 0..1
data class FaceHit(val name: String?, val rect: RectF, val stableMs: Long)    // name null = unknown

/**
 * Turns detections into short spoken guidance:
 *  - direction (left / ahead / right)
 *  - distance from box size (far / close / very close)
 *  - "coming closer" when an object's box grows quickly
 *  - names of known people
 */
class Guide(private val speaker: Speaker) {

    private val history = HashMap<String, ArrayDeque<Pair<Long, Float>>>()
    private val lastSaid = HashMap<String, Long>()
    private var lastObjKey = ""
    private var lastObjTime = 0L
    private var lastBuzz = 0L

    @Volatile var latestObjects: List<Det> = emptyList(); private set
    @Volatile var latestFaces: List<FaceHit> = emptyList(); private set

    fun update(objectsIn: List<Det>, faces: List<FaceHit>, now: Long): String {
        // If we can see a face, the face logic speaks for that person.
        val objects = if (faces.isNotEmpty()) objectsIn.filter { it.label != "person" } else objectsIn
        latestObjects = objects
        latestFaces = faces

        // ---- 1. People we know (highest value to the user) ----
        for (f in faces) {
            if (f.name != null) {
                if (throttle("face:${f.name}", now, 10_000)) {
                    speaker.say("${f.name} ${facePos(f.rect)}", urgent = true)
                    speaker.buzz(60, 120)
                    return "${f.name} ${facePos(f.rect)}"
                }
            } else if (f.stableMs > 1200 && f.rect.width() > 0.18f) {
                if (throttle("face:unknown", now, 12_000)) {
                    speaker.say("Unknown person ${facePos(f.rect)}")
                    return "Unknown person ${facePos(f.rect)}"
                }
            }
        }

        // ---- 2. Objects ----
        // track size history per label (largest box of that label)
        val bestPerLabel = objects.groupBy { it.label }.mapValues { e -> e.value.maxBy { size(it.rect) } }
        for ((label, d) in bestPerLabel) {
            val q = history.getOrPut(label) { ArrayDeque() }
            q.addLast(now to size(d.rect))
            while (q.isNotEmpty() && now - q.first().first > 2000) q.removeFirst()
        }
        history.keys.retainAll(bestPerLabel.keys)

        val primary = objects.maxByOrNull { size(it.rect) } ?: return statusFaces(faces)
        val s = size(primary.rect)
        val pos = position(primary.rect)
        val name = pretty(primary.label)
        val approaching = isApproaching(primary.label, now)

        // Danger: big object straight ahead
        if (s > 0.72f && pos == "ahead") {
            if (now - lastBuzz > 400) { speaker.buzz(150, 255); lastBuzz = now }
            if (throttle("danger", now, 2500)) speaker.say("Stop. $name very close ahead", urgent = true)
            return "STOP – $name very close ahead"
        }
        if (approaching && s > 0.3f) {
            if (now - lastBuzz > 700) { speaker.buzz(80, 180); lastBuzz = now }
            if (throttle("approach:${primary.label}", now, 3000)) speaker.say("$name coming closer, $pos", urgent = true)
            return "$name coming closer, $pos"
        }

        val dist = when {
            s > 0.72f -> "very close"
            s > 0.4f -> "close"
            else -> ""
        }
        val msg = listOf(name, pos, dist).filter { it.isNotEmpty() }.joinToString(", ")
        val key = "${primary.label}|$pos|$dist"
        val repeatAfter = if (dist.isEmpty()) 12_000 else 6_000
        if ((key != lastObjKey && now - lastObjTime > 1800) || now - lastObjTime > repeatAfter) {
            if (speaker.say(msg)) { lastObjKey = key; lastObjTime = now }
        }
        return msg
    }

    /** Full description of everything currently visible (Describe button / double tap). */
    fun describe(): String {
        val parts = mutableListOf<String>()
        for (f in latestFaces) parts += "${f.name ?: "unknown person"} ${facePos(f.rect)}"
        for (d in latestObjects.sortedByDescending { size(it.rect) }.take(5)) {
            val s = size(d.rect)
            val dist = when { s > 0.72f -> ", very close"; s > 0.4f -> ", close"; else -> ", far" }
            parts += "${pretty(d.label)} ${position(d.rect)}$dist"
        }
        return if (parts.isEmpty()) "Nothing recognised in front of you" else "I see: " + parts.joinToString(". ")
    }

    private fun statusFaces(faces: List<FaceHit>) =
        if (faces.isEmpty()) "Path looks clear" else faces.joinToString { it.name ?: "Unknown person" }

    private fun isApproaching(label: String, now: Long): Boolean {
        val q = history[label] ?: return false
        if (q.size < 4) return false
        val old = q.firstOrNull { now - it.first in 700..2000 } ?: return false
        val cur = q.last().second
        return cur > 0.2f && cur / old.second > 1.25f
    }

    private fun throttle(key: String, now: Long, ms: Long): Boolean {
        val last = lastSaid[key] ?: 0L
        if (now - last < ms) return false
        lastSaid[key] = now
        return true
    }

    private fun size(r: RectF) = maxOf(r.width(), r.height())

    private fun position(r: RectF): String {
        val cx = r.centerX()
        return when {
            cx < 0.35f -> "on your left"
            cx > 0.65f -> "on your right"
            else -> "ahead"
        }
    }

    private fun facePos(r: RectF): String = when (position(r)) {
        "ahead" -> "is in front of you"
        else -> "is ${position(r)}"
    }

    private fun pretty(label: String): String = when (label) {
        "dining table" -> "table"
        "tv" -> "TV"
        "cell phone" -> "phone"
        "potted plant" -> "plant"
        "couch" -> "sofa"
        else -> label
    }.replaceFirstChar { it.uppercase() }
}
