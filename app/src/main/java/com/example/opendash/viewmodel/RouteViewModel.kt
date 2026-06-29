package com.example.opendash.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.BuildConfig
import com.example.opendash.data.SharedLocation
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.Route
import com.example.opendash.dash.nav.Router
import com.example.opendash.util.DebugLog
import com.example.opendash.util.LocationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RouteState(
    val destination: SharedLocation? = null,
    val isResolving: Boolean = false,
    val pendingNavigate: Boolean = false,
    // Real routing results
    val route: Route? = null,
    val routing: Boolean = false,
    val distanceText: String? = null,   // "218 km"
    val durationText: String? = null,   // "4h 50m"
    val etaText: String? = null,        // "13:32"
)

class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "RouteViewModel"
    private val _state = MutableStateFlow(RouteState())
    val state = _state.asStateFlow()

    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val repo = com.example.opendash.data.SyncRepository.get(app)

    private val _saved = MutableStateFlow<List<com.example.opendash.data.SavedLocation>>(emptyList())

    /** Saved destinations the rider can tap to load + navigate again. */
    val saved = _saved.asStateFlow()

    init {
        reloadSaved()
        // Reflect saves made locally OR synced in from another device.
        viewModelScope.launch { repo.revision.collect { reloadSaved() } }
    }

    private fun reloadSaved() = viewModelScope.launch {
        _saved.value = withContext(Dispatchers.IO) { repo.savedLocations() }
    }

    /** Save the current resolved destination so it can be reused. No-op without coords. */
    fun saveCurrentDestination(name: String, note: String = "") {
        val d = _state.value.destination ?: return
        val lat = d.lat ?: return
        val lng = d.lng ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.addSaved(name.ifBlank { d.name }, lat, lng, note)
            }
        }
    }

    fun renameSaved(loc: com.example.opendash.data.SavedLocation, name: String, note: String) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.renameSaved(loc, name, note)
            }
        }

    fun deleteSaved(loc: com.example.opendash.data.SavedLocation) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.deleteSaved(loc)
            }
        }

    /** Load a saved destination into the route preview (compute route; stay on the page). */
    fun selectSaved(loc: com.example.opendash.data.SavedLocation) {
        _state.value = RouteState(
            destination = SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng),
            isResolving = false,
            pendingNavigate = false,
        )
        computeRoute(loc.lat, loc.lng)
    }

    fun handleSharedText(text: String) {
        val loc = LocationParser.parse(text)

        _state.value = RouteState(
            destination = loc,
            isResolving = loc.needsExpansion,
            pendingNavigate = true,
        )

        if (loc.lat != null && loc.lng != null) {
            computeRoute(loc.lat, loc.lng)
        } else if (loc.url != null) {
            viewModelScope.launch {
                val (urlCoords, resolvedName) = LocationParser.resolve(loc.url)

                val searchQuery = buildKakaoSearchQuery(
                    rawText = text,
                    locName = loc.name,
                    resolvedName = resolvedName,
                )

                val kakaoCoords = if (urlCoords == null && searchQuery.isNotBlank()) {
                    searchKakaoLocal(searchQuery)
                } else {
                    null
                }

                val androidGeocoderCoords = if (
                    urlCoords == null &&
                    kakaoCoords == null &&
                    searchQuery.isNotBlank()
                ) {
                    geocode(searchQuery)
                } else {
                    null
                }

                val coords = urlCoords ?: kakaoCoords ?: androidGeocoderCoords

                val name = when {
                    loc.name.isNotBlank() && loc.name != "Loading…" -> loc.name
                    !resolvedName.isNullOrBlank() -> resolvedName
                    searchQuery.isNotBlank() -> searchQuery
                    coords != null -> "Dropped pin"
                    else -> "Shared location"
                }

                val resolved = loc.copy(
                    name = name,
                    lat = coords?.first,
                    lng = coords?.second,
                    needsExpansion = false,
                )

                _state.value = _state.value.copy(
                    destination = resolved,
                    isResolving = false,
                )

                if (coords != null) {
                    computeRoute(coords.first, coords.second)
                } else {
                    DebugLog.w(TAG) { "No coords for '$name' (url+kakao+geocode all empty)" }
                }
            }
        }
    }

    private fun buildKakaoSearchQuery(
        rawText: String,
        locName: String,
        resolvedName: String?,
    ): String {
        val cleanedLines = rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("http://") || it.startsWith("https://") }
            .filterNot { it.startsWith("[카카오맵]") }
            .filterNot { it.startsWith("[네이버 지도]") }
            .filterNot { it.equals("Check out", ignoreCase = true) }

        val addressLike = cleanedLines.firstOrNull {
            it.contains("서울") ||
                    it.contains("경기") ||
                    it.contains("인천") ||
                    it.contains("부산") ||
                    it.contains("대구") ||
                    it.contains("광주") ||
                    it.contains("대전") ||
                    it.contains("울산") ||
                    it.contains("세종") ||
                    it.contains("강원") ||
                    it.contains("충북") ||
                    it.contains("충남") ||
                    it.contains("전북") ||
                    it.contains("전남") ||
                    it.contains("경북") ||
                    it.contains("경남") ||
                    it.contains("제주") ||
                    it.contains("로") ||
                    it.contains("길")
        }

        return when {
            !addressLike.isNullOrBlank() -> addressLike
            locName.isNotBlank() && locName != "Loading…" -> locName
            !resolvedName.isNullOrBlank() -> resolvedName
            cleanedLines.isNotEmpty() -> cleanedLines.last()
            else -> ""
        }
    }

    private suspend fun searchKakaoLocal(query: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.KAKAO_REST_API_KEY

            if (apiKey.isBlank()) {
                DebugLog.w(TAG) { "KAKAO_REST_API_KEY is empty; cannot search Kakao Local" }
                return@withContext null
            }

            val encodedQuery = URLEncoder.encode(query, "UTF-8")

            val urls = listOf(
                "https://dapi.kakao.com/v2/local/search/keyword.json?query=$encodedQuery&size=1",
                "https://dapi.kakao.com/v2/local/search/address.json?query=$encodedQuery&size=1",
            )

            for (urlString in urls) {
                try {
                    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "KakaoAK $apiKey")
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }

                    val responseCode = conn.responseCode
                    val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                    val body = stream.use { it.readBytes().toString(Charsets.UTF_8) }

                    conn.disconnect()

                    if (responseCode !in 200..299) {
                        DebugLog.w(TAG) { "Kakao Local HTTP $responseCode: $body" }
                        continue
                    }

                    val root = JSONObject(body)
                    val documents = root.optJSONArray("documents") ?: continue
                    if (documents.length() == 0) continue

                    val first = documents.getJSONObject(0)
                    val lng = first.optString("x").toDoubleOrNull()
                    val lat = first.optString("y").toDoubleOrNull()

                    if (lat != null && lng != null) {
                        DebugLog.i(TAG) { "Kakao Local '$query' → $lat,$lng" }
                        return@withContext lat to lng
                    }
                } catch (e: Exception) {
                    DebugLog.w(TAG) { "Kakao Local failed: ${e.message}" }
                }
            }

            null
        }

    /** Geocode an address/place name to coordinates via the device's geocoder backend. */
    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) {
                    DebugLog.w(TAG) { "No geocoder backend present" }
                    return@withContext null
                }

                val results = Geocoder(getApplication(), Locale.getDefault())
                    .getFromLocationName(query, 1)

                val a = results?.firstOrNull() ?: run {
                    DebugLog.w(TAG) { "Geocoder: no result for '$query'" }
                    return@withContext null
                }

                DebugLog.i(TAG) { "Geocoded '$query' → ${a.latitude},${a.longitude}" }
                a.latitude to a.longitude
            } catch (e: Exception) {
                DebugLog.w(TAG) { "Geocoder failed: ${e.message}" }
                null
            }
        }

    @SuppressLint("MissingPermission")
    private fun computeRoute(destLat: Double, destLng: Double) {
        val origin = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()

        if (origin == null) {
            DebugLog.w(TAG) { "No last known location; cannot compute route yet" }
            _state.value = _state.value.copy(routing = false)
            return
        }

        DebugLog.i(TAG) { "Origin location = ${origin.latitude}, ${origin.longitude}" }

        _state.value = _state.value.copy(routing = true)

        viewModelScope.launch {
            val r = Router.route(
                GeoPoint(origin.latitude, origin.longitude),
                GeoPoint(destLat, destLng),
            )

            _state.value = if (r != null) {
                _state.value.copy(
                    route = r,
                    routing = false,
                    distanceText = fmtKm(r.totalMeters),
                    durationText = fmtDuration(r.totalSeconds),
                    etaText = fmtEta(r.totalSeconds),
                )
            } else {
                _state.value.copy(routing = false)
            }
        }
    }

    fun onNavigated() {
        _state.value = _state.value.copy(pendingNavigate = false)
    }

    fun clear() {
        _state.value = RouteState()
    }

    private fun fmtKm(m: Double) = "%.0f km".format(m / 1000.0)

    private fun fmtDuration(sec: Double): String {
        val total = (sec / 60.0).toInt()
        return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
    }

    private fun fmtEta(sec: Double): String =
        SimpleDateFormat(
            "HH:mm",
            Locale.getDefault(),
        ).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))
}