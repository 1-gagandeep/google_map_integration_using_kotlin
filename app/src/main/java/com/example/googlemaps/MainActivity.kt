package com.example.googlemaps

import android.Manifest
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.cursoradapter.widget.SimpleCursorAdapter
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.PolygonOptions
import android.widget.TextView
import java.io.IOException
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var myMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var fromSearchView: SearchView
    private lateinit var toSearchView: SearchView
    private lateinit var distanceText: TextView
    private lateinit var swapIcon: ImageView
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private var fromLatLng: LatLng? = null
    private var toLatLng: LatLng? = null
    private var fromSuggestionAddresses: List<Address> = emptyList()
    private var toSuggestionAddresses: List<Address> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fromSearchView = findViewById(R.id.fromSearch)
        toSearchView = findViewById(R.id.toSearch)
        distanceText = findViewById(R.id.distanceText)
        swapIcon = findViewById(R.id.swapIcon)
        setupSearchViews()

        swapIcon.setOnClickListener {
            swapSearchViewContents()
        }

        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager
            .beginTransaction()
            .add(R.id.map, mapFragment)
            .commit()
        mapFragment.getMapAsync(this)

        requestLocationPermissions()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        myMap = googleMap
        myMap.uiSettings.isZoomControlsEnabled = true
        myMap.uiSettings.isCompassEnabled = true
        myMap.uiSettings.isMapToolbarEnabled = true
        myMap.uiSettings.isMyLocationButtonEnabled = true
        myMap.uiSettings.isTiltGesturesEnabled = true
        myMap.uiSettings.isRotateGesturesEnabled = true
        myMap.uiSettings.isScrollGesturesEnabled = true
        myMap.uiSettings.isZoomGesturesEnabled = true

        if (checkLocationPermission()) {
            myMap.isMyLocationEnabled = true
            getCurrentLocation()
        }

        myMap.mapType = GoogleMap.MAP_TYPE_NORMAL

        myMap.setOnMapClickListener { latLng ->
            Log.d("MapClick", "Clicked at: $latLng")
            myMap.addMarker(MarkerOptions().position(latLng).title("Clicked Here"))
        }

        myMap.setOnMarkerClickListener { marker ->
            marker.showInfoWindow()
            true
        }
    }

    private fun setupSearchViews() {
        fromSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchPlace(it, isFrom = true) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { if (it.length > 2) provideSearchSuggestions(it, isFrom = true) }
                return true
            }
        })

        fromSearchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = true
            override fun onSuggestionClick(position: Int): Boolean {
                if (position < fromSuggestionAddresses.size) {
                    val address = fromSuggestionAddresses[position]
                    val text = formatAddress(address) // Use the same format as suggestions
                    fromSearchView.setQuery(text, false)
                    fromLatLng = LatLng(address.latitude, address.longitude)
                    updateMapWithFromTo()
                }
                return true
            }
        })

        toSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchPlace(it, isFrom = false) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { if (it.length > 2) provideSearchSuggestions(it, isFrom = false) }
                return true
            }
        })

        toSearchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean = true
            override fun onSuggestionClick(position: Int): Boolean {
                if (position < toSuggestionAddresses.size) {
                    val address = toSuggestionAddresses[position]
                    val text = formatAddress(address) // Use the same format as suggestions
                    toSearchView.setQuery(text, false)
                    toLatLng = LatLng(address.latitude, address.longitude)
                    updateMapWithFromTo()
                }
                return true
            }
        })
    }

    private fun swapSearchViewContents() {
        val fromText = fromSearchView.query.toString()
        val toText = toSearchView.query.toString()
        fromSearchView.setQuery(toText, false)
        toSearchView.setQuery(fromText, false)

        val tempLatLng = fromLatLng
        fromLatLng = toLatLng
        toLatLng = tempLatLng

        updateMapWithFromTo()
    }

    private fun provideSearchSuggestions(query: String, isFrom: Boolean) {
        val geocoder = Geocoder(this)
        try {
            val addresses = geocoder.getFromLocationName(query, 5)
            if (!addresses.isNullOrEmpty()) {
                if (isFrom) fromSuggestionAddresses = addresses else toSuggestionAddresses = addresses
                val suggestions = addresses.map { formatAddress(it) }

                val cursor = MatrixCursor(arrayOf("_id", "suggestion"))
                suggestions.forEachIndexed { index, suggestion -> cursor.addRow(arrayOf(index, suggestion)) }

                val adapter = SimpleCursorAdapter(
                    this, android.R.layout.simple_dropdown_item_1line, cursor,
                    arrayOf("suggestion"), intArrayOf(android.R.id.text1), 0
                )
                if (isFrom) fromSearchView.suggestionsAdapter = adapter else toSearchView.suggestionsAdapter = adapter
            }
        } catch (e: IOException) {
            Log.e("SearchSuggestions", "Failed to get suggestions", e)
        }
    }

    private fun formatAddress(address: Address): String {
        val city = address.locality ?: ""
        val state = address.adminArea ?: ""
        val country = address.countryName ?: ""
        return "$city, $state, $country".trim(',', ' ')
    }

    private fun searchPlace(query: String, isFrom: Boolean) {
        val geocoder = Geocoder(this)
        try {
            val addresses = geocoder.getFromLocationName(query, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val latLng = LatLng(address.latitude, address.longitude)
                if (isFrom) fromLatLng = latLng else toLatLng = latLng
                updateMapWithFromTo()
            } else {
                Log.e("Search", "No location found for query: $query")
            }
        } catch (e: IOException) {
            Log.e("Search", "Geocoder failed", e)
        }
    }

    private fun updateMapWithFromTo() {
        myMap.clear()
        fromLatLng?.let {
            myMap.addMarker(
                MarkerOptions().position(it).title("From").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
            myMap.moveCamera(CameraUpdateFactory.newLatLng(it))
            myMap.animateCamera(CameraUpdateFactory.zoomTo(12f), 2000, null)
            addOverlays(it)
        }
        toLatLng?.let {
            myMap.addMarker(
                MarkerOptions().position(it).title("To").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
            if (fromLatLng == null) {
                myMap.moveCamera(CameraUpdateFactory.newLatLng(it))
                myMap.animateCamera(CameraUpdateFactory.zoomTo(12f), 2000, null)
            }
            addOverlays(it)
        }

        if (fromLatLng != null && toLatLng != null) {
            myMap.addPolyline(
                PolylineOptions()
                    .add(fromLatLng, toLatLng)
                    .width(10f)
                    .color(android.graphics.Color.BLUE)
            )
            calculateDistance()
        } else {
            distanceText.text = "Distance: N/A"
        }
    }

    private fun calculateDistance() {
        if (fromLatLng != null && toLatLng != null) {
            val locationA = Location("From")
            locationA.latitude = fromLatLng!!.latitude
            locationA.longitude = fromLatLng!!.longitude

            val locationB = Location("To")
            locationB.latitude = toLatLng!!.latitude
            locationB.longitude = toLatLng!!.longitude

            val distanceMeters = locationA.distanceTo(locationB)
            val distanceKm = distanceMeters / 1000.0
            distanceText.text = "Distance: ${distanceKm.roundToInt()} km"
        }
    }

    private fun getCurrentLocation() {
        if (checkLocationPermission()) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    location?.let {
                        val currentLatLng = LatLng(it.latitude, it.longitude)
                        myMap.addMarker(
                            MarkerOptions()
                                .position(currentLatLng)
                                .title("My Location")
                                .snippet("You are here!")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA))
                        )
                        myMap.moveCamera(CameraUpdateFactory.newLatLng(currentLatLng))
                        myMap.animateCamera(CameraUpdateFactory.zoomTo(12f), 2000, null)
                        addOverlays(currentLatLng)
                    } ?: Log.e("Location", "Last known location is null")
                }
                .addOnFailureListener { e -> Log.e("Location", "Failed to get location", e) }
        }
    }

    private fun addOverlays(latLng: LatLng) {
        myMap.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(1000.0)
                .strokeColor(android.graphics.Color.RED)
                .fillColor(android.graphics.Color.argb(50, 255, 0, 0))
        )
        myMap.addPolyline(
            PolylineOptions()
                .add(latLng, LatLng(latLng.latitude + 0.01, latLng.longitude + 0.01))
                .width(10f)
                .color(android.graphics.Color.BLUE)
        )
        myMap.addPolygon(
            PolygonOptions()
                .add(
                    LatLng(latLng.latitude - 0.01, latLng.longitude - 0.01),
                    LatLng(latLng.latitude + 0.01, latLng.longitude - 0.01),
                    LatLng(latLng.latitude + 0.01, latLng.longitude + 0.01),
                    LatLng(latLng.latitude - 0.01, latLng.longitude + 0.01)
                )
                .strokeColor(android.graphics.Color.GREEN)
                .fillColor(android.graphics.Color.argb(50, 0, 255, 0))
        )
    }

    private fun requestLocationPermissions() {
        if (!checkLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (checkLocationPermission()) {
                myMap.isMyLocationEnabled = true
                getCurrentLocation()
            }
        }
    }
}
