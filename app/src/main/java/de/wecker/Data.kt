package de.wecker
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Conn(val id: String, val fromId: String, val fromName: String, val toId: String,
                val toName: String, val time: String, val altId: String = "")

class Store(c: Context) {
    private val p = c.getSharedPreferences("p", 0)
    private fun i(k: String, d: Int) = p.getInt(k, d)
    var lead get() = i("lead", 45); set(v) = p.edit().putInt("lead", v).apply()          // Min. vor Abfahrt
    var thr get() = i("thr", 5); set(v) = p.edit().putInt("thr", v).apply()              // Verspätungsgrenze
    var extra get() = i("extra", 10); set(v) = p.edit().putInt("extra", v).apply()       // x Min. zusätzlich bei Alternative
    var checkBefore get() = i("cb", 60); set(v) = p.edit().putInt("cb", v).apply()       // Prüfung X Min. vor Weckzeit
    var defTime get() = p.getString("def", "05:30")!!; set(v) = p.edit().putString("def", v).apply()
    var sound get() = p.getString("snd", "")!!; set(v) = p.edit().putString("snd", v).apply()
    var weekdays get() = p.getBoolean("wd", true); set(v) = p.edit().putBoolean("wd", v).apply()
    var enabled get() = p.getBoolean("en", false); set(v) = p.edit().putBoolean("en", v).apply()
    var activeId get() = p.getString("act", "")!!; set(v) = p.edit().putString("act", v).apply()
    var doneDep get() = p.getLong("done", 0L); set(v) = p.edit().putLong("done", v).apply()
    var conns: List<Conn>
        get() { val a = JSONArray(p.getString("conns", "[]")); return (0 until a.length()).map { val o = a.getJSONObject(it)
            Conn(o.getString("id"), o.getString("fi"), o.getString("fn"), o.getString("ti"), o.getString("tn"), o.getString("t"), o.optString("alt")) } }
        set(l) = p.edit().putString("conns", JSONArray(l.map { JSONObject().put("id", it.id).put("fi", it.fromId).put("fn", it.fromName)
            .put("ti", it.toId).put("tn", it.toName).put("t", it.time).put("alt", it.altId) }).toString()).apply()
    fun active() = conns.firstOrNull { it.id == activeId }
}
