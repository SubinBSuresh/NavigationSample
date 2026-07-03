package com.dutch.navigationsample

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.osmdroid.bonuspack.location.GeocoderNominatim
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
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
    private lateinit var cardSearch: CardView
    private lateinit var cardInfo: CardView
    private lateinit var fabSpeed: ExtendedFloatingActionButton

    private var carMarker: Marker? = null
    private var currentRoad: Road? = null
    private val simulationHandler = Handler(Looper.getMainLooper())
    private var isSimulating = false

    private var currentStepIndex = 0
    private var nextNodeIndex = 0
    private val simulationSpeedMs = 200L 

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
        
        // Initialize OSMDroid
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
        cardSearch = findViewById(R.id.cardSearch)
        cardInfo = findViewById(R.id.cardInfo)
        fabSpeed = findViewById(R.id.fabSpeed)

        map.setMultiTouchControls(true)
        val defaultCenter = GeoPoint(10.0159, 76.3414) // Kakkanad
        map.controller.setZoom(15.0)
        map.controller.setCenter(defaultCenter)

        btnStartNav.setOnClickListener {
            if (!isSimulating) {
                val startPlace = etStart.text.toString()
                val destPlace = etDestination.text.toString()

                if (destPlace.isEmpty()) {
                    Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
                } else {
                    startNavigation(startPlace, destPlace)
                }
            } else {
                stopSimulation()
            }
        }
    }

    private fun startNavigation(startPlace: String, destPlace: String) {
        thread {
            try {
                val geocoder = GeocoderNominatim(Locale.getDefault(), packageName)
                
                // Geocode start place or use default Kakkanad
                val startPoint = if (startPlace.isBlank()) {
                    GeoPoint(10.0159, 76.3414) // Kakkanad, Kochi
                } else {
                    val startResults = geocoder.getFromLocationName(startPlace, 1)
                    if (startResults.isNullOrEmpty()) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Start location not found", Toast.LENGTH_SHORT).show() }
                        return@thread
                    }
                    GeoPoint(startResults[0].latitude, startResults[0].longitude)
                }

                // Geocode destination place
                val destResults = geocoder.getFromLocationName(destPlace, 1)
                if (destResults.isNullOrEmpty()) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Destination location not found", Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                val endPoint = GeoPoint(destResults[0].latitude, destResults[0].longitude)

                // Calculate Road
                val roadManager = OSRMRoadManager(this@MainActivity, packageName)
                val waypoints = ArrayList<GeoPoint>()
                waypoints.add(startPoint)
                waypoints.add(endPoint)
                val road = roadManager.getRoad(waypoints)

                runOnUiThread {
                    if (road.mStatus == Road.STATUS_OK) {
                        currentRoad = road
                        displayRoad(road)
                        startSimulationLoop(road)
                    } else {
                        Toast.makeText(this@MainActivity, "Routing error. Check connection.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error finding locations: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun displayRoad(road: Road) {
        val toRemove = map.overlays.filter { it is Polyline }
        map.overlays.removeAll(toRemove)

        val roadOverlay = RoadManager.buildRoadOverlay(road)
        roadOverlay.outlinePaint.color = Color.parseColor("#3F51B5")
        roadOverlay.outlinePaint.strokeWidth = 12f
        map.overlays.add(roadOverlay)
        map.invalidate()
    }

    private fun startSimulationLoop(road: Road) {
        isSimulating = true
        btnStartNav.text = "Stop Simulation"
        cardSearch.visibility = View.GONE
        cardInfo.visibility = View.VISIBLE
        fabSpeed.visibility = View.VISIBLE
        
        currentStepIndex = 0
        nextNodeIndex = 0
        
        if (carMarker == null) {
            carMarker = Marker(map)
            carMarker?.setIcon(ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_compass, null))
            carMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            carMarker?.setFlat(true)
            map.overlays.add(carMarker)
        }
        
        simulateNextStep()
    }

    private fun simulateNextStep() {
        val road = currentRoad ?: return
        val points = road.mRouteHigh

        if (currentStepIndex < points.size) {
            val currentPoint = points[currentStepIndex]
            var speedKmh = 0.0
            
            // Calculate speed
            if (currentStepIndex > 0) {
                val prevPoint = points[currentStepIndex - 1]
                val distanceMeters = currentPoint.distanceToAsDouble(prevPoint)
                // Speed = distance (m) / time (ms) -> convert to km/h
                speedKmh = (distanceMeters * 3600.0) / simulationSpeedMs
            }

            // 1. Arrow Rotation (Bearing)
            if (currentStepIndex < points.size - 1) {
                val nextPoint = points[currentStepIndex + 1]
                val bearing = calculateBearing(currentPoint, nextPoint)
                carMarker?.rotation = -bearing // Counter-clockwise for OSMDroid
            }

            // 2. Position Arrow and Animate Map
            carMarker?.position = currentPoint
            map.controller.animateTo(currentPoint)

            // 3. Process Navigation Callbacks
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

            // Calculate overall progress for total remaining distance
            val progress = currentStepIndex.toDouble() / road.mRouteHigh.size
            val remainingKm = road.mLength * (1.0 - progress)

            // Trigger Progress Callback
            navListener.onProgressUpdate(
                nextNode.mInstructions ?: "Continue", 
                distanceToNext, 
                remainingKm,
                speedKmh
            )

            // Turn Detection Callback (e.g., within 20 meters of the node)
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
    }

    private fun calculateBearing(start: GeoPoint, end: GeoPoint): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x)).toFloat()
        return (bearing + 360) % 360
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
