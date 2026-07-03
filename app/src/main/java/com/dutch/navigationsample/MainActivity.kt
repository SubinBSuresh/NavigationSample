package com.dutch.navigationsample

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
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

    private var carMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private val poiMarkers = ArrayList<Marker>()
    private var currentRoad: Road? = null
    private val simulationHandler = Handler(Looper.getMainLooper())
    private var isSimulating = false

    private var currentStepIndex = 0
    private var nextNodeIndex = 0
    private val simulationSpeedMs = 200L 
    private val kakkanad = GeoPoint(10.0159, 76.3414)

    // --- Navigation Callbacks ---
    interface NavigationListener {
        fun onTurnDone(instruction: String)
        fun onProgressUpdate(maneuver: String, distanceToNext: Double, totalRemaining: Double, speedKmh: Double)
    }

    private val navListener = object : NavigationListener {
        override fun onTurnDone(instruction: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Turn Completed: $instruction", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onProgressUpdate(maneuver: String, distanceToNext: Double, totalRemaining: Double, speedKmh: Double) {
            tvManeuver.text = "Maneuver: $maneuver"
            tvDistanceToNext.text = "Next turn: ${String.format("%.0f", distanceToNext)} m"
            tvTotalDistance.text = "Remaining: ${String.format("%.2f", totalRemaining)} km"
            fabSpeed.text = "${String.format("%.0f", speedKmh)} km/h"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

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

        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(kakkanad)

        btnStartNav.setOnClickListener {
            if (!isSimulating) {
                val startPlace = etStart.text.toString()
                val destPlace = etDestination.text.toString()

                if (destPlace.isEmpty()) {
                    Toast.makeText(this, "Please enter a destination or search for a POI", Toast.LENGTH_SHORT).show()
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
                if (keyword.isNotBlank()) {
                    searchPOIs(keyword)
                }
                true
            } else {
                false
            }
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
                        Toast.makeText(this, "No results found for '$keyword' near Kakkanad", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "Found ${pois.size} results. Click a marker to navigate.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "POI Search Error: ${e.message}", Toast.LENGTH_LONG).show() }
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
                    if (res.isNullOrEmpty()) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Start not found", Toast.LENGTH_SHORT).show() }
                        return@thread
                    }
                    GeoPoint(res[0].latitude, res[0].longitude)
                }
                calculateAndStartRoute(startPoint, destPoint, destName)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startNavigation(startPlace: String, destPlace: String) {
        val mapCenter = map.mapCenter as GeoPoint
        thread {
            try {
                val geocoder = GeocoderNominatim(Locale.getDefault(), packageName)
                val startPoint = if (startPlace.isBlank()) kakkanad else {
                    val res = geocoder.getFromLocationName(startPlace, 1)
                    if (res.isNullOrEmpty()) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Start not found", Toast.LENGTH_SHORT).show() }
                        return@thread
                    }
                    GeoPoint(res[0].latitude, res[0].longitude)
                }

                val destResults = geocoder.getFromLocationName(destPlace, 1,
                    mapCenter.latitude - 0.1, mapCenter.longitude - 0.1,
                    mapCenter.latitude + 0.1, mapCenter.longitude + 0.1)
                
                if (destResults.isNullOrEmpty()) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Destination not found", Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                calculateAndStartRoute(startPoint, GeoPoint(destResults[0].latitude, destResults[0].longitude), destResults[0].featureName ?: destPlace)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun calculateAndStartRoute(startPoint: GeoPoint, endPoint: GeoPoint, destTitle: String) {
        val roadManager = OSRMRoadManager(this@MainActivity, packageName)
        val waypoints = ArrayList<GeoPoint>()
        waypoints.add(startPoint)
        waypoints.add(endPoint)
        val road = roadManager.getRoad(waypoints)

        runOnUiThread {
            if (road.mStatus == Road.STATUS_OK) {
                currentRoad = road
                displayRoad(road, destTitle)
                startSimulationLoop(road)
            } else {
                Toast.makeText(this@MainActivity, "Routing error. Check connection.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayRoad(road: Road, destinationName: String) {
        val toRemove = map.overlays.filter { it is Polyline || it == destinationMarker }
        map.overlays.removeAll(toRemove)

        val roadOverlay = RoadManager.buildRoadOverlay(road)
        roadOverlay.outlinePaint.color = Color.parseColor("#3F51B5")
        roadOverlay.outlinePaint.strokeWidth = 12f
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

    private fun simulateNextStep() {
        val road = currentRoad ?: return
        val points = road.mRouteHigh

        if (currentStepIndex < points.size) {
            val currentPoint = points[currentStepIndex]
            var speedKmh = 0.0
            if (currentStepIndex > 0) {
                val prevPoint = points[currentStepIndex - 1]
                speedKmh = (currentPoint.distanceToAsDouble(prevPoint) * 3600.0) / simulationSpeedMs
            }
            if (currentStepIndex < points.size - 1) {
                val bearing = calculateBearing(currentPoint, points[currentStepIndex + 1])
                carMarker?.rotation = -bearing 
            }
            carMarker?.position = currentPoint
            map.controller.animateTo(currentPoint)
            processNavigationProgress(currentPoint, road, speedKmh)
            currentStepIndex++
            simulationHandler.postDelayed({ simulateNextStep() }, simulationSpeedMs)
        } else {
            stopSimulation()
            Toast.makeText(this, "Destination reached", Toast.LENGTH_SHORT).show()
        }
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
        isSimulating = false
        simulationHandler.removeCallbacksAndMessages(null)
        btnStartNav.text = "Start Simulation"
        cardSearch.visibility = View.VISIBLE
        cardInfo.visibility = View.GONE
        fabSpeed.visibility = View.GONE
        
        // Clear route, destination marker, and POI search markers
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

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
