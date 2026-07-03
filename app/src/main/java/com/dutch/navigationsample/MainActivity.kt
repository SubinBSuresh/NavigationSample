package com.dutch.navigationsample

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.bonuspack.location.GeocoderNominatim
import org.osmdroid.bonuspack.location.NominatimPOIProvider
import org.osmdroid.bonuspack.location.POI
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.ArrayList
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private val TAG = "Dutch__MainActivity"

    private lateinit var map: MapView
    private lateinit var tvManeuver: TextView
    private lateinit var tvDistanceToNext: TextView
    private lateinit var tvTotalDistance: TextView
    private lateinit var btnStartNav: Button
    private lateinit var etStart: EditText
    private lateinit var etDestination: EditText
    private lateinit var etPOISearch: EditText
    private lateinit var cardSearch: CardView
    private lateinit var cardInfo: CardView
    private lateinit var fabSpeed: ExtendedFloatingActionButton
    private lateinit var fabZoomIn: FloatingActionButton
    private lateinit var fabZoomOut: FloatingActionButton
    private lateinit var fabMyLocation: FloatingActionButton

    private var carMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private val poiMarkers = ArrayList<Marker>()
    private var currentRoad: Road? = null
    private val simulationHandler = Handler(Looper.getMainLooper())
    private var isSimulating = false

    private var currentStepIndex = 0
    private var nextNodeIndex = 0
    private val simulationSpeedMs = 200L // 200ms delay between steps for smoothness
    private val kakkanad = GeoPoint(10.0159, 76.3414)

    // --- AAOS & Mock Location Variables ---
    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private var realCarSpeedKmh: Double = 0.0
    private var isCarServiceConnected = false
    private lateinit var locationManager: LocationManager
    private val MOCK_PROVIDER = LocationManager.GPS_PROVIDER

    // --- Navigation Callbacks ---
    interface NavigationListener {
        fun onTurnDone(instruction: String)
        fun onProgressUpdate(maneuver: String, distanceToNext: Double, totalRemaining: Double, speedKmh: Double)
    }

    private val navListener = object : NavigationListener {
        override fun onTurnDone(instruction: String) {
            Log.d(TAG, "Dutch__ onTurnDone: $instruction")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Turn Completed: $instruction", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onProgressUpdate(maneuver: String, distanceToNext: Double, totalRemaining: Double, speedKmh: Double) {
            Log.v(TAG, "Dutch__ onProgressUpdate: Maneuver=$maneuver, DistNext=$distanceToNext, Speed=$speedKmh")
            tvManeuver.text = maneuver
            tvDistanceToNext.text = "${String.format("%.0f", distanceToNext)} m"
            tvTotalDistance.text = "Remaining: ${String.format("%.2f", totalRemaining)} km"
            fabSpeed.text = "${String.format("%.0f", speedKmh)} km/h"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Dutch__ onCreate: Application starting")
        
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        bindViews()
        initMap()
        initCarServices()
        initMockLocation()

        btnStartNav.setOnClickListener {
            Log.d(TAG, "Dutch__ StartNav Button clicked. isSimulating=$isSimulating")
            if (!isSimulating) {
                val startPlace = etStart.text.toString()
                val destPlace = etDestination.text.toString()
                if (destPlace.isEmpty()) {
                    Log.w(TAG, "Dutch__ StartNav failed: Destination is empty")
                    Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
                } else {
                    startNavigation(startPlace, destPlace)
                }
            } else {
                stopSimulation()
            }
        }

        etPOISearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val keyword = etPOISearch.text.toString()
                Log.d(TAG, "Dutch__ POI Search triggered: $keyword")
                if (keyword.isNotBlank()) searchPOIs(keyword)
                true
            } else false
        }
    }

    private fun bindViews() {
        Log.d(TAG, "Dutch__ bindViews: Mapping UI elements")
        map = findViewById(R.id.map)
        tvManeuver = findViewById(R.id.tvManeuver)
        tvDistanceToNext = findViewById(R.id.tvDistanceToNext)
        tvTotalDistance = findViewById(R.id.tvTotalDistance)
        btnStartNav = findViewById(R.id.btnStartNav)
        etStart = findViewById(R.id.etStart)
        etDestination = findViewById(R.id.etDestination)
        etPOISearch = findViewById(R.id.etPOISearch)
        cardSearch = findViewById(R.id.cardSearch)
        cardInfo = findViewById(R.id.cardInfo)
        fabSpeed = findViewById(R.id.fabSpeed)
        fabZoomIn = findViewById(R.id.fabZoomIn)
        fabZoomOut = findViewById(R.id.fabZoomOut)
        fabMyLocation = findViewById(R.id.fabMyLocation)
    }

    private fun initMap() {
        Log.d(TAG, "Dutch__ initMap: Configuring OSM MapView")
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(kakkanad)

        fabZoomIn.setOnClickListener { 
            Log.v(TAG, "Dutch__ ZoomIn clicked")
            map.controller.zoomIn() 
        }
        fabZoomOut.setOnClickListener { 
            Log.v(TAG, "Dutch__ ZoomOut clicked")
            map.controller.zoomOut() 
        }
        fabMyLocation.setOnClickListener {
            Log.d(TAG, "Dutch__ MyLocation clicked. Centering on Kakkanad")
            map.controller.animateTo(kakkanad)
        }
    }

    private fun initCarServices() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Log.d(TAG, "Dutch__ initCarServices: Standard device detected. Car APIs disabled.")
            isCarServiceConnected = false
            return
        }

        Log.i(TAG, "Dutch__ initCarServices: AAOS device detected. Binding to Car Service.")
        try {
            car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { carInstance, ready ->
                if (ready) {
                    try {
                        carPropertyManager = carInstance.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                        carPropertyManager?.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                            override fun onChangeEvent(value: CarPropertyValue<*>) {
                                if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                                    val speedMs = value.value as Float
                                    realCarSpeedKmh = (speedMs * 3.6).toDouble()
                                    Log.v(TAG, "Dutch__ AAOS Speed Update: $realCarSpeedKmh km/h")
                                }
                            }
                            override fun onErrorEvent(propId: Int, zone: Int) {
                                Log.e(TAG, "Dutch__ CarProperty Error: propId=$propId, zone=$zone")
                            }
                        }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
                        isCarServiceConnected = true
                        Log.i(TAG, "Dutch__ initCarServices: Successfully registered for speed updates")
                    } catch (e: Exception) {
                        Log.e(TAG, "Dutch__ initCarServices: Service connection failed", e)
                        isCarServiceConnected = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dutch__ initCarServices: Car.createCar failed", e)
            isCarServiceConnected = false
        }
    }

    private fun initMockLocation() {
        Log.d(TAG, "Dutch__ initMockLocation: Preparing GPS spoofing")
        try {
            if (locationManager.getProvider(MOCK_PROVIDER) != null) {
                locationManager.removeTestProvider(MOCK_PROVIDER)
            }
            locationManager.addTestProvider(MOCK_PROVIDER, false, false, false, false, true, true, true, 1, 2)
            locationManager.setTestProviderEnabled(MOCK_PROVIDER, true)
            Log.i(TAG, "Dutch__ initMockLocation: Mock provider enabled")
        } catch (e: SecurityException) {
            Log.e(TAG, "Dutch__ initMockLocation: SecurityException. Check Mock Location Settings.")
            Toast.makeText(this, "Enable 'Mock Locations' in Developer Options", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Dutch__ initMockLocation: Failed", e)
        }
    }

    private fun injectMockLocationIntoOS(geoPoint: GeoPoint) {
        try {
            val mockLocation = Location(MOCK_PROVIDER).apply {
                latitude = geoPoint.latitude
                longitude = geoPoint.longitude
                altitude = 0.0
                time = System.currentTimeMillis()
                accuracy = 1.0f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(MOCK_PROVIDER, mockLocation)
            Log.v(TAG, "Dutch__ Mock Injected: ${geoPoint.latitude}, ${geoPoint.longitude}")
        } catch (e: Exception) {
            Log.w(TAG, "Dutch__ injectMockLocationIntoOS: Injection error")
        }
    }

    private fun simulateNextStep() {
        val road = currentRoad ?: return
        val points = road.mRouteHigh

        if (currentStepIndex < points.size) {
            val currentPoint = points[currentStepIndex]
            injectMockLocationIntoOS(currentPoint)

            val currentSpeed: Double = if (isCarServiceConnected) realCarSpeedKmh else 60.0

            if (currentStepIndex < points.size - 1) {
                val bearing = calculateBearing(currentPoint, points[currentStepIndex + 1])
                carMarker?.rotation = -bearing 
            }

            Log.v(TAG, "Dutch__ Step $currentStepIndex/${points.size} - Point: ${currentPoint.latitude}, ${currentPoint.longitude}")

            carMarker?.position = currentPoint
            map.controller.animateTo(currentPoint)
            processNavigationProgress(currentPoint, road, currentSpeed)

            currentStepIndex++
            simulationHandler.postDelayed({ simulateNextStep() }, simulationSpeedMs)
        } else {
            Log.i(TAG, "Dutch__ simulateNextStep: Simulation Finished")
            stopSimulation()
            Toast.makeText(this, "Destination reached", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchPOIs(keyword: String) {
        Log.d(TAG, "Dutch__ searchPOIs: keyword='$keyword'")
        thread {
            try {
                val poiProvider = NominatimPOIProvider(packageName)
                val pois: ArrayList<POI>? = poiProvider.getPOICloseTo(kakkanad, keyword, 20, 0.05)
                runOnUiThread {
                    clearPOIMarkers()
                    if (pois.isNullOrEmpty()) {
                        Log.d(TAG, "Dutch__ searchPOIs: No results")
                        Toast.makeText(this, "No '$keyword' found near Kakkanad", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.i(TAG, "Dutch__ searchPOIs: Found ${pois.size} results")
                        for (poi: POI in pois) {
                            val marker = Marker(map)
                            marker.position = poi.mLocation
                            marker.title = poi.mType ?: keyword
                            marker.snippet = poi.mDescription
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            marker.setOnMarkerClickListener { m, _ ->
                                Log.d(TAG, "Dutch__ POI clicked: ${m.title}")
                                m.showInfoWindow()
                                etDestination.setText(poi.mType ?: keyword)
                                startNavigationToPOI(poi.mLocation, poi.mType ?: keyword)
                                true
                            }
                            map.overlays.add(marker)
                            poiMarkers.add(marker)
                        }
                        map.invalidate()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dutch__ searchPOIs: Error", e)
                runOnUiThread { Toast.makeText(this, "Search Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun clearPOIMarkers() {
        Log.d(TAG, "Dutch__ clearPOIMarkers: Removing ${poiMarkers.size} POI markers")
        map.overlays.removeAll(poiMarkers)
        poiMarkers.clear()
        map.invalidate()
    }

    private fun startNavigationToPOI(destPoint: GeoPoint, destName: String) {
        val startPlace = etStart.text.toString()
        Log.i(TAG, "Dutch__ startNavigationToPOI: To $destName")
        thread {
            try {
                val geocoder = GeocoderNominatim(Locale.getDefault(), packageName)
                val startPoint = if (startPlace.isBlank()) kakkanad else {
                    val res = geocoder.getFromLocationName(startPlace, 1)
                    if (res.isNullOrEmpty()) kakkanad else GeoPoint(res[0].latitude, res[0].longitude)
                }
                calculateAndStartRoute(startPoint, destPoint, destName)
            } catch (e: Exception) {
                Log.e(TAG, "Dutch__ startNavigationToPOI: Error", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startNavigation(startPlace: String, destPlace: String) {
        val mapCenter = map.mapCenter as GeoPoint
        Log.i(TAG, "Dutch__ startNavigation: From '$startPlace' to '$destPlace'")
        thread {
            try {
                val geocoder = GeocoderNominatim(Locale.getDefault(), packageName)
                val startPoint = if (startPlace.isBlank()) kakkanad else {
                    val res = geocoder.getFromLocationName(startPlace, 1)
                    if (res.isNullOrEmpty()) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Start location not found", Toast.LENGTH_SHORT).show() }
                        return@thread
                    }
                    GeoPoint(res[0].latitude, res[0].longitude)
                }

                val destResults = geocoder.getFromLocationName(destPlace, 1,
                    mapCenter.latitude - 0.1, mapCenter.longitude - 0.1,
                    mapCenter.latitude + 0.1, mapCenter.longitude + 0.1)
                
                if (destResults.isNullOrEmpty()) {
                    Log.w(TAG, "Dutch__ startNavigation: Destination results empty")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Destination not found", Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                val endPoint = GeoPoint(destResults[0].latitude, destResults[0].longitude)
                calculateAndStartRoute(startPoint, endPoint, destResults[0].featureName ?: destPlace)
            } catch (e: Exception) {
                Log.e(TAG, "Dutch__ startNavigation: Error", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun calculateAndStartRoute(startPoint: GeoPoint, endPoint: GeoPoint, destTitle: String) {
        Log.d(TAG, "Dutch__ calculateAndStartRoute: Fetching road data")
        val roadManager = OSRMRoadManager(this@MainActivity, packageName)
        val waypoints = ArrayList<GeoPoint>()
        waypoints.add(startPoint)
        waypoints.add(endPoint)
        val road = roadManager.getRoad(waypoints)
        runOnUiThread {
            if (road.mStatus == Road.STATUS_OK) {
                Log.i(TAG, "Dutch__ Road Fetched. Length: ${road.mLength} km")
                currentRoad = road
                displayRoad(road, destTitle)
                startSimulationLoop(road)
            } else {
                Log.e(TAG, "Dutch__ Road Fetch failed. Status: ${road.mStatus}")
                Toast.makeText(this@MainActivity, "Routing error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayRoad(road: Road, destinationName: String) {
        Log.d(TAG, "Dutch__ displayRoad: Updating overlays")
        val toRemove = map.overlays.filter { it is Polyline || it == destinationMarker }
        map.overlays.removeAll(toRemove)
        val roadOverlay = RoadManager.buildRoadOverlay(road)
        roadOverlay.outlinePaint.color = Color.parseColor("#1A73E8")
        roadOverlay.outlinePaint.strokeWidth = 14f
        map.overlays.add(roadOverlay)
        destinationMarker = Marker(map)
        destinationMarker?.position = road.mRouteHigh.last()
        destinationMarker?.title = destinationName
        destinationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(destinationMarker)
        map.invalidate()
    }

    private fun startSimulationLoop(road: Road) {
        Log.i(TAG, "Dutch__ startSimulationLoop: Navigation simulation active")
        isSimulating = true
        btnStartNav.text = "Stop Simulation"
        cardSearch.visibility = View.GONE
        cardInfo.visibility = View.VISIBLE
        fabSpeed.visibility = View.VISIBLE
        clearPOIMarkers()
        currentStepIndex = 0
        nextNodeIndex = 0
        if (carMarker == null) {
            carMarker = Marker(map)
            carMarker?.setIcon(ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_compass, null))
            carMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            carMarker?.setFlat(true)
        }
        map.overlays.remove(carMarker)
        map.overlays.add(carMarker)
        simulateNextStep()
    }

    private fun processNavigationProgress(currentPos: GeoPoint, road: Road, speedKmh: Double) {
        if (nextNodeIndex < road.mNodes.size) {
            val nextNode = road.mNodes[nextNodeIndex]
            val distanceToNext = currentPos.distanceToAsDouble(nextNode.mLocation)
            val progress = currentStepIndex.toDouble() / road.mRouteHigh.size
            navListener.onProgressUpdate(nextNode.mInstructions ?: "Continue", distanceToNext, road.mLength * (1.0 - progress), speedKmh)
            if (distanceToNext < 20.0) {
                navListener.onTurnDone(nextNode.mInstructions ?: "Maneuver")
                nextNodeIndex++
            }
        }
    }

    private fun stopSimulation() {
        Log.i(TAG, "Dutch__ stopSimulation: Simulation terminated by user")
        isSimulating = false
        simulationHandler.removeCallbacksAndMessages(null)
        btnStartNav.text = "Start Simulation"
        cardSearch.visibility = View.VISIBLE
        cardInfo.visibility = View.GONE
        fabSpeed.visibility = View.GONE
        val toRemove = map.overlays.filter { it is Polyline || it == destinationMarker }
        map.overlays.removeAll(toRemove)
        destinationMarker = null
        clearPOIMarkers()
    }

    private fun calculateBearing(start: GeoPoint, end: GeoPoint): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)
        val bearing = Math.toDegrees(atan2(sin(lon2 - lon1) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)))
        return (bearing.toFloat() + 360) % 360
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Dutch__ onDestroy: Cleaning up Car Service")
        car?.disconnect()
    }

    override fun onResume() { 
        super.onResume()
        Log.d(TAG, "Dutch__ onResume")
        map.onResume() 
    }
    
    override fun onPause() { 
        super.onPause()
        Log.d(TAG, "Dutch__ onPause")
        map.onPause() 
    }
}
