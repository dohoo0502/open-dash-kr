package com.example.opendash.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.opendash.BuildConfig
import com.example.opendash.R
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.ui.view.KNNaviView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class KakaoNaviActivity : AppCompatActivity() {
    private lateinit var naviView: KNNaviView
    private lateinit var routeOverlayView: RouteOverlayView

    private lateinit var navStatusText: TextView
    private lateinit var firstInstructionText: TextView
    private lateinit var destinationText: TextView
    private lateinit var remainingDistanceText: TextView
    private lateinit var estimatedTimeText: TextView
    private lateinit var routeStatusText: TextView
    private lateinit var debugStatusText: TextView

    private var destName: String = "목적지"
    private var destLat: Double = 0.0
    private var destLng: Double = 0.0

    private val curRoutePriority = KNRoutePriority.KNRoutePriority_Recommand
    private val curAvoidOptions = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private var locationListener: LocationListener? = null
    private var bestFreshLocation: Location? = null
    private var locationResolved = false

    companion object {
        private const val TAG = "KakaoNaviActivity"

        private const val START_NAVIGATION_DELAY_MS = 1200L
        private const val FRESH_LOCATION_TIMEOUT_MS = 10000L
        private const val INIT_GUIDANCE_DELAY_MS = 1200L
        private const val START_GUIDANCE_DELAY_MS = 1200L

        private const val GOOD_ACCURACY_METERS = 80f

        private val FALLBACK_WGS84 = Pair(126.904777, 37.556367)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kakao_navi)

        naviView = findViewById(R.id.navi_view)
        routeOverlayView = findViewById(R.id.route_overlay_view)

        navStatusText = findViewById(R.id.nav_status_text)
        firstInstructionText = findViewById(R.id.first_instruction_text)
        destinationText = findViewById(R.id.destination_text)
        remainingDistanceText = findViewById(R.id.remaining_distance_text)
        estimatedTimeText = findViewById(R.id.estimated_time_text)
        routeStatusText = findViewById(R.id.route_status_text)
        debugStatusText = findViewById(R.id.debug_status_text)

        destName = intent.getStringExtra("dest_name") ?: "목적지"
        destLat = intent.getDoubleExtra("dest_lat", 0.0)
        destLng = intent.getDoubleExtra("dest_lng", 0.0)

        Log.i(TAG, "Destination WGS84: $destName / lat=$destLat, lng=$destLng")

        setOverlayStatus(
            top = "길안내 준비 중",
            instruction = "목적지 정보를 확인하고 있어",
            destination = destName,
            distance = "거리 --",
            time = "시간 --",
            bottom = "KakaoNavi + OpenDash 경로 오버레이",
            debug = "dest lat=$destLat, lng=$destLng",
        )

        if (destLat == 0.0 || destLng == 0.0) {
            Log.e(TAG, "Invalid destination coordinates")
            setOverlayStatus(
                top = "목적지 오류",
                instruction = "공유 링크에서 좌표를 읽지 못했어",
                destination = destName,
                distance = "거리 --",
                time = "시간 --",
                bottom = "dest_lat / dest_lng 확인 필요",
                debug = "Invalid destination coordinates",
            )
            return
        }

        mainHandler.postDelayed({
            Log.i(TAG, "Delayed requestFreshStartLocation()")
            requestFreshStartLocation()
        }, START_NAVIGATION_DELAY_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeLocationListener()
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshStartLocation() {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            Log.w(TAG, "Location permission not granted. Using fallback start location.")
            startNavigationSetup(FALLBACK_WGS84, "fallback_permission")
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        bestFreshLocation = null
        locationResolved = false

        setOverlayStatus(
            top = "현재 위치 잡는 중",
            instruction = "실시간 GPS 위치를 기다리는 중",
            destination = destName,
            distance = "거리 --",
            time = "최대 10초",
            bottom = "현재 위치 확정 후 경로선을 직접 그릴게",
            debug = "requestLocationUpdates GPS/NETWORK",
        )

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.i(
                    TAG,
                    "Fresh location update: provider=${location.provider}, lng=${location.longitude}, lat=${location.latitude}, accuracy=${location.accuracy}",
                )

                if (bestFreshLocation == null || isBetterLocation(location, bestFreshLocation)) {
                    bestFreshLocation = location

                    setOverlayStatus(
                        top = "현재 위치 수신",
                        instruction = "정확도 ${location.accuracy.roundToInt()}m / ${location.provider}",
                        destination = destName,
                        distance = "위치 확인",
                        time = "대기 중",
                        bottom = "더 정확한 위치를 잠깐 더 기다리는 중",
                        debug = "lng=${location.longitude}, lat=${location.latitude}",
                    )
                }

                if (location.hasAccuracy() && location.accuracy <= GOOD_ACCURACY_METERS) {
                    resolveFreshLocation(location, "fresh_${location.provider}")
                }
            }

            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "Location provider disabled: $provider")
            }
        }

        locationListener = listener

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                Log.i(TAG, "GPS_PROVIDER location updates requested")
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    500L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                Log.i(TAG, "NETWORK_PROVIDER location updates requested")
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed", e)
            removeLocationListener()

            val lastKnown = getBestLastKnownLocation(locationManager)
            if (lastKnown != null) {
                startNavigationSetup(
                    Pair(lastKnown.longitude, lastKnown.latitude),
                    "last_known_after_request_failed",
                )
            } else {
                startNavigationSetup(FALLBACK_WGS84, "fallback_request_failed")
            }
            return
        }

        mainHandler.postDelayed({
            if (locationResolved) {
                return@postDelayed
            }

            val best = bestFreshLocation
            if (best != null) {
                resolveFreshLocation(best, "fresh_timeout_best")
                return@postDelayed
            }

            val lastKnown = getBestLastKnownLocation(locationManager)
            if (lastKnown != null) {
                removeLocationListener()
                locationResolved = true

                startNavigationSetup(
                    Pair(lastKnown.longitude, lastKnown.latitude),
                    "last_known_timeout",
                )
                return@postDelayed
            }

            removeLocationListener()
            locationResolved = true
            startNavigationSetup(FALLBACK_WGS84, "fallback_no_location")
        }, FRESH_LOCATION_TIMEOUT_MS)
    }

    private fun resolveFreshLocation(location: Location, source: String) {
        if (locationResolved) {
            return
        }

        locationResolved = true
        removeLocationListener()

        Log.i(
            TAG,
            "Resolved start location: source=$source, provider=${location.provider}, lng=${location.longitude}, lat=${location.latitude}, accuracy=${location.accuracy}",
        )

        setOverlayStatus(
            top = "현재 위치 확정",
            instruction = "정확도 ${location.accuracy.roundToInt()}m / ${location.provider}",
            destination = destName,
            distance = "위치 확인",
            time = "경로 준비",
            bottom = "REST Directions 경로선 요청으로 이동",
            debug = "source=$source, lng=${location.longitude}, lat=${location.latitude}",
        )

        startNavigationSetup(
            Pair(location.longitude, location.latitude),
            source,
        )
    }

    private fun removeLocationListener() {
        val listener = locationListener ?: return

        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(listener)
        } catch (e: Exception) {
            Log.w(TAG, "removeLocationListener failed", e)
        } finally {
            locationListener = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(locationManager: LocationManager): Location? {
        val candidates = mutableListOf<Location>()

        try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { candidates.add(it) }
        } catch (_: Exception) {
        }

        try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { candidates.add(it) }
        } catch (_: Exception) {
        }

        try {
            locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { candidates.add(it) }
        } catch (_: Exception) {
        }

        return candidates.minByOrNull { location ->
            if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        }
    }

    private fun isBetterLocation(newLocation: Location, currentBest: Location?): Boolean {
        if (currentBest == null) {
            return true
        }

        val newAccuracy = if (newLocation.hasAccuracy()) newLocation.accuracy else Float.MAX_VALUE
        val currentAccuracy = if (currentBest.hasAccuracy()) currentBest.accuracy else Float.MAX_VALUE

        if (newAccuracy < currentAccuracy) {
            return true
        }

        val newTime = newLocation.time
        val currentTime = currentBest.time

        return newTime > currentTime && newAccuracy <= currentAccuracy + 50f
    }

    private fun startNavigationSetup(startWgs84: Pair<Double, Double>, source: String) {
        thread {
            try {
                Log.i(
                    TAG,
                    "Start WGS84 resolved: source=$source, lng=${startWgs84.first}, lat=${startWgs84.second}",
                )

                fetchRestDirectionsAndDrawRoute(startWgs84)

                val startKtm = transCoordWgs84ToKtm(
                    lng = startWgs84.first,
                    lat = startWgs84.second,
                )

                val goalKtm = transCoordWgs84ToKtm(
                    lng = destLng,
                    lat = destLat,
                )

                if (startKtm == null) {
                    Log.e(TAG, "Start KTM conversion failed")
                    setOverlayStatus(
                        top = "출발지 변환 실패",
                        instruction = "현재 위치 좌표 변환 실패",
                        destination = destName,
                        distance = "거리 --",
                        time = "시간 --",
                        bottom = "Kakao Local transcoord 확인 필요",
                        debug = "Start KTM conversion failed",
                    )
                    return@thread
                }

                if (goalKtm == null) {
                    Log.e(TAG, "Goal KTM conversion failed")
                    setOverlayStatus(
                        top = "목적지 변환 실패",
                        instruction = "목적지 좌표 변환 실패",
                        destination = destName,
                        distance = "거리 --",
                        time = "시간 --",
                        bottom = "Kakao Local transcoord 확인 필요",
                        debug = "Goal KTM conversion failed",
                    )
                    return@thread
                }

                val start = KNPOI(
                    "현재 위치",
                    startKtm.first,
                    startKtm.second,
                    "현재 위치",
                )

                val goal = KNPOI(
                    destName,
                    goalKtm.first,
                    goalKtm.second,
                    destName,
                )

                Log.i(
                    TAG,
                    "Start POI created: source=$source, ktmX=${startKtm.first}, ktmY=${startKtm.second}, poi=$start",
                )

                Log.i(
                    TAG,
                    "Goal POI created: name=$destName, ktmX=${goalKtm.first}, ktmY=${goalKtm.second}, poi=$goal",
                )

                runOnUiThread {
                    makeTripAndRequestRoute(start, goal)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to make trip", e)
                setOverlayStatus(
                    top = "길안내 오류",
                    instruction = "경로 생성 준비 중 오류 발생",
                    destination = destName,
                    distance = "거리 --",
                    time = "시간 --",
                    bottom = "예외 로그 확인 필요",
                    debug = e.message ?: e.toString(),
                )
            }
        }
    }

    private fun fetchRestDirectionsAndDrawRoute(startWgs84: Pair<Double, Double>) {
        val apiKey = BuildConfig.KAKAO_REST_API_KEY

        if (apiKey.isBlank()) {
            Log.e(TAG, "KAKAO_REST_API_KEY is empty")
            return
        }

        setOverlayStatus(
            top = "경로선 요청 중",
            instruction = "REST Directions에서 vertex를 받아오는 중",
            destination = destName,
            distance = "거리 --",
            time = "시간 --",
            bottom = "OpenDash 커스텀 경로선 준비",
            debug = "REST directions",
        )

        val urlText =
            "https://apis-navi.kakaomobility.com/v1/directions" +
                    "?origin=${startWgs84.first},${startWgs84.second}" +
                    "&destination=$destLng,$destLat" +
                    "&priority=RECOMMEND" +
                    "&car_fuel=GASOLINE" +
                    "&car_hipass=false" +
                    "&alternatives=false" +
                    "&road_details=false"

        val connection = URL(urlText).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "KakaoAK $apiKey")
            connection.connectTimeout = 7000
            connection.readTimeout = 7000

            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            Log.i(TAG, "REST directions responseCode=$responseCode body=$body")

            if (responseCode !in 200..299) {
                setOverlayStatus(
                    top = "경로선 요청 실패",
                    instruction = "REST Directions 응답 실패",
                    destination = destName,
                    distance = "거리 --",
                    time = "시간 --",
                    bottom = "Kakao Mobility REST 응답 확인 필요",
                    debug = "responseCode=$responseCode",
                )
                return
            }

            val result = parseRestDirections(body)

            if (result.points.size < 2) {
                Log.e(TAG, "REST directions has not enough route points")
                setOverlayStatus(
                    top = "경로선 없음",
                    instruction = "vertex 좌표가 충분하지 않아",
                    destination = destName,
                    distance = "거리 --",
                    time = "시간 --",
                    bottom = "REST Directions vertex 확인 필요",
                    debug = "points=${result.points.size}",
                )
                return
            }

            runOnUiThread {
                routeOverlayView.setRoute(result.points)

                setOverlayStatus(
                    top = "경로선 표시 완료",
                    instruction = "OpenDash 커스텀 경로선 표시 중",
                    destination = destName,
                    distance = formatDistance(result.distanceMeters),
                    time = formatDuration(result.durationSeconds),
                    bottom = "Kakao 지도 위에 직접 그린 경로선",
                    debug = "REST route points=${result.points.size}",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchRestDirectionsAndDrawRoute failed", e)
            setOverlayStatus(
                top = "경로선 요청 오류",
                instruction = "REST Directions 호출 중 오류 발생",
                destination = destName,
                distance = "거리 --",
                time = "시간 --",
                bottom = "네트워크/API 키 확인 필요",
                debug = e.message ?: e.toString(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private data class RestRouteResult(
        val points: List<RoutePoint>,
        val distanceMeters: Int,
        val durationSeconds: Int,
    )

    private fun parseRestDirections(body: String): RestRouteResult {
        val json = JSONObject(body)
        val routes = json.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            return RestRouteResult(emptyList(), 0, 0)
        }

        val firstRoute = routes.getJSONObject(0)
        val summary = firstRoute.optJSONObject("summary")
        val distance = summary?.optInt("distance", 0) ?: 0
        val duration = summary?.optInt("duration", 0) ?: 0

        val points = mutableListOf<RoutePoint>()
        val sections = firstRoute.optJSONArray("sections") ?: return RestRouteResult(emptyList(), distance, duration)

        for (sectionIndex in 0 until sections.length()) {
            val section = sections.getJSONObject(sectionIndex)
            val roads = section.optJSONArray("roads") ?: continue

            for (roadIndex in 0 until roads.length()) {
                val road = roads.getJSONObject(roadIndex)
                val vertexes = road.optJSONArray("vertexes") ?: continue

                var i = 0
                while (i < vertexes.length() - 1) {
                    val lng = vertexes.optDouble(i)
                    val lat = vertexes.optDouble(i + 1)

                    if (lng != 0.0 && lat != 0.0) {
                        points.add(RoutePoint(lng = lng, lat = lat))
                    }

                    i += 2
                }
            }
        }

        return RestRouteResult(
            points = points,
            distanceMeters = distance,
            durationSeconds = duration,
        )
    }

    private fun makeTripAndRequestRoute(start: KNPOI, goal: KNPOI) {
        Log.i(TAG, "Calling makeTripWithStart...")

        KNSDK.makeTripWithStart(
            start,
            goal,
            null,
            null,
        ) { error, trip ->
            if (error != null) {
                Log.e(TAG, "makeTripWithStart failed: ${error.code} / $error")
                return@makeTripWithStart
            }

            if (trip == null) {
                Log.e(TAG, "makeTripWithStart failed: trip is null")
                return@makeTripWithStart
            }

            Log.i(TAG, "makeTripWithStart success: trip=$trip")
            requestRouteAndStartGuidance(trip)
        }
    }

    private fun requestRouteAndStartGuidance(trip: KNTrip) {
        try {
            Log.i(
                TAG,
                "Calling trip.routeWithPriority priority=$curRoutePriority avoid=$curAvoidOptions",
            )

            trip.routeWithPriority(
                curRoutePriority,
                curAvoidOptions,
            ) { routeError, routes ->
                if (routeError != null) {
                    Log.e(TAG, "routeWithPriority failed: ${routeError.code} / $routeError")
                    return@routeWithPriority
                }

                val routeCount = routes?.size ?: 0
                Log.i(TAG, "routeWithPriority success: routeCount=$routeCount routes=$routes")

                val guidance = KNSDK.sharedGuidance()
                if (guidance == null) {
                    Log.e(TAG, "Cannot start guidance: sharedGuidance is null")
                    return@routeWithPriority
                }

                attachGuidanceToNaviView(
                    guidance = guidance,
                    trip = trip,
                    routeCount = routeCount,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call routeWithPriority", e)
        }
    }

    private fun attachGuidanceToNaviView(
        guidance: KNGuidance,
        trip: KNTrip,
        routeCount: Int,
    ) {
        Log.i(TAG, "Scheduling naviView.initWithGuidance...")

        naviView.postDelayed({
            try {
                Log.i(
                    TAG,
                    "Calling naviView.initWithGuidance priority=$curRoutePriority avoid=$curAvoidOptions",
                )

                naviView.initWithGuidance(
                    guidance,
                    trip,
                    curRoutePriority,
                    curAvoidOptions,
                )

                Log.i(TAG, "naviView.initWithGuidance called")

                naviView.postDelayed({
                    startGuidance(
                        guidance = guidance,
                        trip = trip,
                        routeCount = routeCount,
                    )
                }, START_GUIDANCE_DELAY_MS)
            } catch (e: Exception) {
                Log.e(TAG, "initWithGuidance failed", e)
            }
        }, INIT_GUIDANCE_DELAY_MS)
    }

    private fun startGuidance(
        guidance: KNGuidance,
        trip: KNTrip,
        routeCount: Int,
    ) {
        try {
            Log.i(
                TAG,
                "Calling guidance.startWithTrip priority=$curRoutePriority avoid=$curAvoidOptions",
            )

            guidance.startWithTrip(
                trip,
                curRoutePriority,
                curAvoidOptions,
            )

            Log.i(TAG, "guidance.startWithTrip called routeCount=$routeCount")
        } catch (e: Exception) {
            Log.e(TAG, "startWithTrip failed", e)
        }
    }

    private fun transCoordWgs84ToKtm(lng: Double, lat: Double): Pair<Int, Int>? {
        val apiKey = BuildConfig.KAKAO_REST_API_KEY

        if (apiKey.isBlank()) {
            Log.e(TAG, "KAKAO_REST_API_KEY is empty")
            return null
        }

        val urlText =
            "https://dapi.kakao.com/v2/local/geo/transcoord.json" +
                    "?x=$lng" +
                    "&y=$lat" +
                    "&input_coord=WGS84" +
                    "&output_coord=KTM"

        val connection = URL(urlText).openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "KakaoAK $apiKey")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            Log.i(TAG, "transcoord responseCode=$responseCode body=$body")

            if (responseCode !in 200..299) {
                return null
            }

            val json = JSONObject(body)
            val documents = json.optJSONArray("documents")

            if (documents == null || documents.length() == 0) {
                Log.e(TAG, "No transcoord documents")
                return null
            }

            val first = documents.getJSONObject(0)
            val x = first.getDouble("x").roundToInt()
            val y = first.getDouble("y").roundToInt()

            Pair(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "transCoordWgs84ToKtm failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            val km = meters / 1000.0
            "%.1f km".format(km)
        } else {
            "${meters} m"
        }
    }

    private fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes >= 60) {
            val hours = minutes / 60
            val remainMinutes = minutes % 60
            "${hours}시간 ${remainMinutes}분"
        } else {
            "${minutes}분"
        }
    }

    private fun setOverlayStatus(
        top: String,
        instruction: String,
        destination: String,
        distance: String,
        time: String,
        bottom: String,
        debug: String,
    ) {
        runOnUiThread {
            navStatusText.text = top
            firstInstructionText.text = instruction
            destinationText.text = destination
            remainingDistanceText.text = distance
            estimatedTimeText.text = time
            routeStatusText.text = bottom
            debugStatusText.text = debug
        }
    }
}