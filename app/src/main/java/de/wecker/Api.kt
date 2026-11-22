package de.wecker
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter

/** Die DB Navigator App hat keine öffentliche API. Genutzt wird der freie HAFAS-Wrapper db.transport.rest.
 *  Falls der Dienst wechselt, nur BASE ändern. */
object Api {
    const val BASE = "https://v6.db.transport.rest"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun get(path: String): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 7000
        return c.inputStream.bufferedReader().readText()
    }

    fun stations(q: String): List<Pair<String, String>> {
        val a = JSONArray(get("/locations?results=5&stops=true&addresses=false&poi=false&query=" + enc(q)))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.has("id") && it.optString("type") in listOf("stop", "station") }
            .map { it.getString("id") to it.getString("name") }
    }

    /** true = Ausfall/Verspätung >= Grenze, false = ok, null = unbekannt (kein Ergebnis) */
    fun isBad(a: Conn, date: LocalDate, thrMin: Int): Boolean? {
        val z = ZoneId.systemDefault(); val t = LocalTime.parse(a.time)
        val from = date.atTime(t).atZone(z).minusMinutes(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val js = JSONObject(get("/journeys?from=${a.fromId}&to=${a.toId}&results=5&departure=" + enc(from))).optJSONArray("journeys") ?: return null
        if (js.length() == 0) return null
        for (i in 0 until js.length()) {
            val legs = js.getJSONObject(i).getJSONArray("legs")
            val f = legs.getJSONObject(0); val l = legs.getJSONObject(legs.length() - 1)
            val planned = ZonedDateTime.parse(f.optString("plannedDeparture", f.optString("departure")))
                .withZoneSameInstant(z).toLocalTime().withSecond(0).withNano(0)
            if (planned != t) continue
            val cancelled = (0 until legs.length()).any { legs.getJSONObject(it).optBoolean("cancelled") }
            return cancelled || maxOf(f.optInt("departureDelay"), l.optInt("arrivalDelay")) >= thrMin * 60
        }
        return true // Netz ok, aber geplanter Zug taucht nicht mehr auf -> vermutlich ausgefallen
    }
}
