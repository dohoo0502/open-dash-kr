package com.example.opendash.dash.nav

import com.example.opendash.BuildConfig
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Korean routing provider using Kakao Mobility Directions API.
 *
 * OpenDash original:
 * OSRM public demo server
 *
 * OpenDash KR:
 * Kakao Mobility Directions API
 *
 * Important:
 * - KAKAO_REST_API_KEY must be provided through local.properties.
 * - We avoid motorway by default because Korean motorcycles cannot use many car-only roads.
 */
object Router {
    private const val TAG = "Router"
    private const val BASE = "https://apis-navi.kakaomobility.com/v1/directions"
    private const val UA = "OpenDash-KR/0.1 (personal motorcycle nav; single user)"

    suspend fun route(from: GeoPoint, to: GeoPoint): Route? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.KAKAO_REST_API_KEY

        if (apiKey.isBlank()) {
            DebugLog.w(TAG) { "KAKAO_REST_API_KEY is empty" }
            return@withContext null
        }

        val url = "$BASE" +
                "?origin=${from.lng},${from.lat}" +
                "&destination=${to.lng},${to.lat}" +
                "&priority=RECOMMEND" +
                "&avoid=motorway" +
                "&summary=false"

        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "KakaoAK $apiKey")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.use { it.readBytes().toString(Charsets.UTF_8) }

            conn.disconnect()

            if (responseCode !in 200..299) {
                DebugLog.w(TAG) { "Kakao route() HTTP $responseCode: $body" }
                return@withContext null
            }

            parse(body)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Kakao route() failed: ${e.message}" }
            null
        }
    }

    private fun parse(json: String): Route? {
        val root = JSONObject(json)

        val routes = root.optJSONArray("routes") ?: run {
            DebugLog.w(TAG) { "Kakao response has no routes" }
            return null
        }

        if (routes.length() == 0) {
            DebugLog.w(TAG) { "Kakao routes is empty" }
            return null
        }

        val r0 = routes.getJSONObject(0)
        val resultCode = r0.optInt("result_code", -1)
        val resultMsg = r0.optString("result_msg")

        if (resultCode != 0) {
            DebugLog.w(TAG) { "Kakao result_code=$resultCode, result_msg=$resultMsg" }
            return null
        }

        val summary = r0.optJSONObject("summary")
        val totalMeters = summary?.optDouble("distance", 0.0) ?: 0.0
        val totalSeconds = summary?.optDouble("duration", 0.0) ?: 0.0

        val geometry = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()

        val sections = r0.optJSONArray("sections") ?: return null

        for (si in 0 until sections.length()) {
            val section = sections.getJSONObject(si)

            val roads = section.optJSONArray("roads")
            if (roads != null) {
                for (ri in 0 until roads.length()) {
                    val road = roads.getJSONObject(ri)
                    val vertexes = road.optJSONArray("vertexes") ?: continue

                    var i = 0
                    while (i + 1 < vertexes.length()) {
                        val lng = vertexes.getDouble(i)
                        val lat = vertexes.getDouble(i + 1)
                        val point = GeoPoint(lat, lng)

                        if (geometry.isEmpty() || GeoPoint.distMeters(geometry.last(), point) > 0.1) {
                            geometry.add(point)
                        }

                        i += 2
                    }
                }
            }
        }

        if (geometry.size < 2) {
            DebugLog.w(TAG) { "Kakao geometry has too few points: ${geometry.size}" }
            return null
        }

        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        for (si in 0 until sections.length()) {
            val section = sections.getJSONObject(si)
            val guides = section.optJSONArray("guides") ?: continue

            for (gi in 0 until guides.length()) {
                val guide = guides.getJSONObject(gi)

                val lng = guide.optDouble("x", Double.NaN)
                val lat = guide.optDouble("y", Double.NaN)

                if (lng.isNaN() || lat.isNaN()) continue

                val point = GeoPoint(lat, lng)
                val guidance = guide.optString("guidance").ifBlank { "Continue" }
                val type = maneuverTypeFromKakaoGuidance(guidance)

                maneuvers.add(
                    Maneuver(
                        type = type,
                        instruction = buildInstruction(type, guidance),
                        location = point,
                        cumulativeMeters = nearestCumulative(point, geometry, cum),
                    )
                )
            }
        }

        if (maneuvers.isEmpty()) {
            maneuvers.add(
                Maneuver(
                    type = ManeuverType.DEPART,
                    instruction = "출발",
                    location = geometry.first(),
                    cumulativeMeters = 0.0,
                )
            )
            maneuvers.add(
                Maneuver(
                    type = ManeuverType.ARRIVE,
                    instruction = "목적지 도착",
                    location = geometry.last(),
                    cumulativeMeters = cum.last(),
                )
            )
        }

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = if (totalMeters > 0.0) totalMeters else cum.last(),
            totalSeconds = totalSeconds,
            cumulative = cum,
        )
    }

    private fun maneuverTypeFromKakaoGuidance(guidance: String): ManeuverType {
        return when {
            guidance.contains("좌회전") -> ManeuverType.TURN_LEFT
            guidance.contains("우회전") -> ManeuverType.TURN_RIGHT
            guidance.contains("유턴") -> ManeuverType.UTURN
            guidance.contains("U턴", ignoreCase = true) -> ManeuverType.UTURN
            guidance.contains("로터리") -> ManeuverType.ROUNDABOUT
            guidance.contains("회전교차로") -> ManeuverType.ROUNDABOUT
            guidance.contains("목적지") -> ManeuverType.ARRIVE
            guidance.contains("도착") -> ManeuverType.ARRIVE
            guidance.contains("출발") -> ManeuverType.DEPART
            else -> ManeuverType.CONTINUE
        }
    }

    private fun buildInstruction(type: ManeuverType, guidance: String): String = when (type) {
        ManeuverType.DEPART -> "출발"
        ManeuverType.ARRIVE -> "목적지 도착"
        else -> guidance
    }

    /** Cumulative distance of the geometry vertex nearest to a maneuver location. */
    private fun nearestCumulative(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE

        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) {
                bestD = d
                best = cum[i]
            }
        }

        return best
    }
}