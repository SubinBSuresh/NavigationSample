package com.dutch.navigationsample

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
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
import androidx.appcompat.app.AlertDialog
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
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(kakkanad)

        fabZoomIn.setOnClickListener { map.controller.zoomIn() }
        fabZoomOut.setOnClickListener { map.controller.zoomOut() }
        fabMyLocation.setOnClickListener { map.controller.animateTo(kakkanad) }
    }

    private fun initCarServices() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            isCarServiceConnected = false
            return
        }

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
                                }
                            }
                            override fun onErrorEvent(propId: Int, zone: Int) {}
                        }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
                        isCarServiceConnected = true
                    } catch (e: Exception) {
                        isCarServiceConnected = false
                    }
                }
            }
        } catch (e: Exception) {
            isCarServiceConnected = false
        }
    }

    private fun initMockLocation() {
        try {
            if (locationManager.getProvider(MOCK_PROVIDER) != null) {
                locationManager.removeTestProvider(MOCK_PROVIDER)
            }
            locationManager.addTestProvider(MOCK_PROVIDER, false, false, false, false, true, true, true, 1, 2)
            locationManager.setTestProviderEnabled(MOCK_PROVIDER, true)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Enable 'Mock Locations' in Developer Options", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {}
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
        } catch (e: Exception) {}
    }

    private fun simulateNextStep() {
        val road = currentRoad ?: return
        val points = road.mRouteHigh

        if (currentStepIndex < points.size) {
            val currentPoint = points.get(currentStepIndex)
            injectMockLocationIntoOS(currentPoint)

            val currentSpeed: Double = if (isCarServiceConnected) realCarSpeedKmh else 60.0

            if (currentStepIndex < points.size - 1) {
                val bearing = calculateBearing(currentPoint, points.get(currentStepIndex + 1))
                carMarker?.rotation = -bearing 
            }

            carMarker?.position = currentPoint
            map.controller.animateTo(currentPoint)
            processNavigationProgress(currentPoint, road, currentSpeed)

            currentStepIndex++
            simulationHandler.postDelayed({ simulateNextStep() }, simulationSpeedMs)
        } else {
            stopSimulation()
            Toast.makeText(this, "Destination reached", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchPOIs(keyword: String) {
        thread {
            try {
                val poiProvider = NominatimPOIProvider(packageName)
                val pois: ArrayList<POI>? = poiProvider.getPOICloseTo(kakkanad, keyword, 20, 0.05)
                runOnUiThread {
                    clearPOIMarkers()
                    if (pois.isNullOrEmpty()) {
                        Toast.makeText(this, "No '$keyword' found near Kakkanad", Toast.LENGTH_SHORT).show()
                    } else {
                        for (poi: POI in pois) {
                            val marker = Marker(map)
                            marker.position = poi.mLocation
                            marker.title = poi.mType ?: keyword
                            marker.snippet = poi.mDescription
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            marker.setOnMarkerClickListener { m, _ ->
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
                runOnUiThread { Toast.makeText(this, "Search Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun clearPOIMarkers() {
        map.overlays.removeAll(poiMarkers)
        poiMarkers.clear()
        map.invalidate()
    }

    private fun startNavigationToPOI(destPoint: GeoPoint, destName: String) {
        val startPlace = etStart.text.toString()
        thread {
            try {
                val geocoder = GeocoderNominatim(Locale.getDefault(), packageName)
                val startPoint = if (startPlace.isBlank()) kakkanad else {
                    val res = geocoder.getFromLocationName(startPlace, 1)
                    if (res.isNullOrEmpty()) kakkanad else {
                        val addr = res.get(0)
                        GeoPoint(addr.latitude, addr.longitude)
                    }
                }
                calculateAndStartRoute(startPoint, destPoint, destName)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startNavigation(startPlace: String, destPlace: String) {
        Log.i(TAG, "Dutch__ startNavigation: Global search for '$destPlace' starting from '$startPlace'")
        thread {
            try {
                val geocoder = GeocoderNominatim(Locale.getDefault(), packageName)
                
                // 1. Resolve Start Point
                val startPoint = if (startPlace.isBlank()) {
                    Log.d(TAG, "Dutch__ startNavigation: Using default Kakkanad as start")
                    kakkanad
                } else {
                    val startResults = geocoder.getFromLocationName(startPlace, 1)
                    if (startResults.isNullOrEmpty()) {
                        Log.w(TAG, "Dutch__ startNavigation: Start location not found")
                        runOnUiThread { Toast.makeText(this@MainActivity, "Start location not found", Toast.LENGTH_SHORT).show() }
                        return@thread
                    }
                    val addr = startResults.get(0)
                    GeoPoint(addr.latitude, addr.longitude)
                }

                // 2. Global Search for Destination
                val destResults = geocoder.getFromLocationName(destPlace, 1)
                
                if (destResults.isNullOrEmpty()) {
                    Log.w(TAG, "Dutch__ startNavigation: Destination not found globally")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Destination not found", Toast.LENGTH_SHORT).show() }
                    return@thread
                }

                val address = destResults.get(0)
                val endPoint = GeoPoint(address.latitude, address.longitude)
                
                // 3. Extract Full Readable Address
                val addressStringBuilder = StringBuilder()
                for (i in 0..address.maxAddressLineIndex) {
                    addressStringBuilder.append(address.getAddressLine(i)).append("\n")
                }
                val fullAddress = addressStringBuilder.toString().trim()

                // 4. Verification Dialog on UI Thread
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Confirm Destination")
                        .setMessage("Navigate to:\n\n$fullAddress")
                        .setPositiveButton("Yes") { _, _ ->
                            Log.i(TAG, "Dutch__ startNavigation: User confirmed destination")
                            calculateAndStartRoute(startPoint, endPoint, address.featureName ?: destPlace)
                        }
                        .setNegativeButton("Cancel") { dialog, _ ->
                            Log.d(TAG, "Dutch__ startNavigation: User cancelled")
                            dialog.dismiss()
                        }
                        .show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Dutch__ startNavigation: Error during geocoding", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun calculateAndStartRoute(startPoint: GeoPoint, endPoint: GeoPoint, destTitle: String) {
        Log.i(TAG, "Dutch__ calculateAndStartRoute: Fetching road data in background")
        
        thread {
            try {
                val roadManager = OSRMRoadManager(this@MainActivity, packageName)
                val waypoints = ArrayList<GeoPoint>()
                waypoints.add(startPoint)
                waypoints.add(endPoint)
                
                // This network call is now correctly inside the thread block
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
            } catch (e: Exception) {
                Log.e(TAG, "Dutch__ calculateAndStartRoute: Error during road fetch", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayRoad(road: Road, destinationName: String) {
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
            val nextNode = road.mNodes.get(nextNodeIndex)
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
        car?.disconnect()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
