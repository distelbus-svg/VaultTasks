package app.vaulttasks.data.settings

import app.vaulttasks.domain.Space
import app.vaulttasks.domain.SpacesData
import org.json.JSONArray
import org.json.JSONObject

/** JSON codec for [SpacesData] (uses the framework's org.json). Decoding is tolerant: bad input yields empty data. */
object SpacesJson {
    fun encode(d: SpacesData): String = JSONObject().apply {
        put("activeId", d.activeId ?: JSONObject.NULL)
        put("spaces", JSONArray().also { arr ->
            d.spaces.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("files", JSONArray(s.files))
                    put("defaultFile", s.defaultFile ?: JSONObject.NULL)
                })
            }
        })
    }.toString()

    fun decode(json: String?): SpacesData {
        if (json.isNullOrBlank()) return SpacesData()
        return try {
            val o = JSONObject(json)
            val arr = o.getJSONArray("spaces")
            val spaces = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val files = s.getJSONArray("files").let { f -> (0 until f.length()).map { f.getString(it) } }
                Space(
                    id = s.getString("id"),
                    name = s.getString("name"),
                    files = files,
                    defaultFile = if (s.isNull("defaultFile")) null else s.getString("defaultFile"),
                )
            }
            SpacesData(spaces, if (o.isNull("activeId")) null else o.getString("activeId"))
        } catch (e: Exception) {
            SpacesData()
        }
    }
}
