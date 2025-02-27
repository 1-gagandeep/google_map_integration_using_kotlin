package com.example.googlemaps

import android.Manifest
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
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
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.PolylineOptions
import java.io.IOException

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var myMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var searchView: SearchView
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private var suggestionAddresses: List<Address> = emptyList() // Store addresses for suggestion selection

    // Initializes the activity, sets up UI components, and prepares the map and search functionality
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Enables edge-to-edge display for a fullscreen experience
        setContentView(R.layout.activity_main) // Loads the layout defined in activity_main.xml
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom) // Adjusts padding for system bars
            insets
        }

        // Initializes the FusedLocationProviderClient to fetch the device's location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Sets up the SearchView for place searching and suggestions
        searchView = findViewById(R.id.mapSearch)
        setupSearchView()

        // Initializes and adds the map fragment to the layout
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager
            .beginTransaction()
            .add(R.id.map, mapFragment) // Places the map below the SearchView
            .commit()
        mapFragment.getMapAsync(this) // Calls onMapReady when the map is ready

        // Requests location permissions to enable location features
        requestLocationPermissions()
    }

    // Configures the GoogleMap when it’s ready, setting up UI controls and initial map state
    override fun onMapReady(googleMap: GoogleMap) {
        myMap = googleMap // Assigns the GoogleMap instance to the class variable

        // Enables map UI controls for user interaction
        myMap.uiSettings.isZoomControlsEnabled = true // Adds zoom in/out buttons
        myMap.uiSettings.isCompassEnabled = true // Displays a compass in the map (appears below SearchView)
        myMap.uiSettings.isMapToolbarEnabled = true // Enables toolbar actions in the map
        myMap.uiSettings.isMyLocationButtonEnabled = true // Adds "My Location" button (appears below SearchView)
        myMap.uiSettings.isTiltGesturesEnabled = true // Allows tilting with two-finger gesture
        myMap.uiSettings.isRotateGesturesEnabled = true // Allows rotating with two-finger gesture
        myMap.uiSettings.isScrollGesturesEnabled = true // Enables panning the map
        myMap.uiSettings.isZoomGesturesEnabled = true // Enables pinch-to-zoom

        // Enables the user's location dot if permissions are granted
        if (checkLocationPermission()) {
            myMap.isMyLocationEnabled = true // Shows a blue dot at the user's location
            getCurrentLocation() // Fetches and displays the current location
        }

        // Sets the map type to satellite view for a detailed visual
        myMap.mapType = GoogleMap.MAP_TYPE_NORMAL

        // Adds a listener to place a marker when the user taps the map
        myMap.setOnMapClickListener { latLng ->
            Log.d("MapClick", "Clicked at: $latLng")
            myMap.addMarker(MarkerOptions().position(latLng).title("Clicked Here")) // Adds marker at tap location
        }

        // Shows the info window when a marker is clicked
        myMap.setOnMarkerClickListener { marker ->
            marker.showInfoWindow() // Displays the marker’s title and snippet
            true // Indicates the event is consumed
        }
    }

    // Configures the SearchView to handle search submissions, suggestions, and selection
    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            // Triggered when the user submits a search query (e.g., presses Enter)
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { searchQuery ->
                    searchPlace(searchQuery) // Searches for the entered place and updates the map
                }
                return true // Event is handled
            }

            // Triggered as the user types, providing real-time suggestions
            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { text ->
                    if (text.length > 2) { // Only fetch suggestions after 3 characters to reduce requests
                        provideSearchSuggestions(text)
                    }
                }
                return true // Event is handled
            }
        })

        // Handles suggestion selection from the dropdown
        searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean {
                return true // Not typically used with SearchView, but required to implement
            }

            override fun onSuggestionClick(position: Int): Boolean {
                if (position < suggestionAddresses.size) {
                    val selectedAddress = suggestionAddresses[position]
                    val selectedText = "${selectedAddress.featureName}, ${selectedAddress.locality ?: ""}, ${selectedAddress.countryName ?: ""}".trim(',', ' ')
                    searchView.setQuery(selectedText, false) // Sets the selected suggestion text in the SearchView
                    val latLng = LatLng(selectedAddress.latitude, selectedAddress.longitude)
                    updateMapWithLocation(latLng, selectedText) // Updates the map with the selected location
                }
                return true // Event is handled
            }
        })
    }

    // Fetches and displays location suggestions in a dropdown as the user types
    private fun provideSearchSuggestions(query: String) {
        val geocoder = Geocoder(this) // Creates a Geocoder instance to convert text to locations
        try {
            val addresses: List<Address>? = geocoder.getFromLocationName(query, 5) // Gets up to 5 matching locations
            if (!addresses.isNullOrEmpty()) {
                suggestionAddresses = addresses // Stores addresses for selection
                val suggestions = addresses.map { address ->
                    "${address.featureName}, ${address.locality ?: ""}, ${address.countryName ?: ""}"
                        .trim(',', ' ') // Formats each suggestion as "Place, City, Country"
                }

                // Creates a MatrixCursor to store suggestions for the adapter
                val cursor = MatrixCursor(arrayOf("_id", "suggestion"))
                suggestions.forEachIndexed { index, suggestion ->
                    cursor.addRow(arrayOf(index, suggestion)) // Adds each suggestion with an ID
                }

                // Configures a SimpleCursorAdapter to display suggestions in the SearchView dropdown
                val adapter = SimpleCursorAdapter(
                    this,
                    android.R.layout.simple_dropdown_item_1line, // Uses a simple dropdown layout
                    cursor,
                    arrayOf("suggestion"), // Column to display
                    intArrayOf(android.R.id.text1), // Maps to the TextView in the layout
                    0
                )
                searchView.suggestionsAdapter = adapter // Sets the adapter for suggestions
            }
        } catch (e: IOException) {
            Log.e("SearchSuggestions", "Failed to get suggestions", e) // Logs errors if geocoding fails
        }
    }

    // Searches for a place based on the user’s query and updates the map with a marker and overlays
    private fun searchPlace(query: String) {
        val geocoder = Geocoder(this) // Creates a Geocoder instance for location lookup
        try {
            val addresses: List<Address>? = geocoder.getFromLocationName(query, 1) // Gets the top result
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val latLng = LatLng(address.latitude, address.longitude) // Converts address to LatLng
                updateMapWithLocation(latLng, query) // Updates the map with the searched location
            } else {
                Log.e("Search", "No location found for query: $query") // Logs if no result is found
            }
        } catch (e: IOException) {
            Log.e("Search", "Geocoder failed", e) // Logs network or geocoding errors
        }
    }

    // Updates the map with a marker, camera movement, and overlays for a given location
    private fun updateMapWithLocation(latLng: LatLng, title: String) {
        myMap.clear() // Clears existing markers and overlays (optional)

        // Adds a marker at the specified location
        val markerOptions = MarkerOptions()
            .position(latLng)
            .title(title)
            .snippet("Searched location")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
        myMap.addMarker(markerOptions)

        // Moves and animates the camera to focus on the location
        myMap.moveCamera(CameraUpdateFactory.newLatLng(latLng))
        myMap.animateCamera(CameraUpdateFactory.zoomTo(12f), 2000, null)

        // Adds circle, polyline, and polygon overlays around the location
        addOverlays(latLng)
    }

    // Retrieves the device’s current location and updates the map with a marker and overlays
    private fun getCurrentLocation() {
        if (checkLocationPermission()) { // Ensures location permissions are granted
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    location?.let {
                        val currentLatLng = LatLng(it.latitude, it.longitude) // Gets current coordinates
                        val markerOptions = MarkerOptions()
                            .position(currentLatLng)
                            .title("My Location")
                            .snippet("You are here!")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA))
                        myMap.addMarker(markerOptions) // Adds marker at current location
                        myMap.moveCamera(CameraUpdateFactory.newLatLng(currentLatLng)) // Centers the map
                        myMap.animateCamera(CameraUpdateFactory.zoomTo(12f), 2000, null) // Zooms in with animation
                        addOverlays(currentLatLng) // Adds overlays around current location
                    } ?: run {
                        Log.e("Location", "Last known location is null") // Logs if location is unavailable
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Location", "Failed to get location", e) // Logs location retrieval errors
                }
        }
    }

    // Adds a circle, polyline, and polygon overlays around a specified LatLng on the map
    private fun addOverlays(latLng: LatLng) {
        myMap.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(1000.0) // Creates a 1km radius circle
                .strokeColor(android.graphics.Color.RED)
                .fillColor(android.graphics.Color.argb(50, 255, 0, 0)) // Red outline with semi-transparent fill
        )
        myMap.addPolyline(
            PolylineOptions()
                .add(latLng, LatLng(latLng.latitude + 0.01, latLng.longitude + 0.01)) // Draws a line to a nearby point
                .width(10f)
                .color(android.graphics.Color.BLUE) // Blue line with 10px width
        )
        myMap.addPolygon(
            PolygonOptions()
                .add(
                    LatLng(latLng.latitude - 0.01, latLng.longitude - 0.01),
                    LatLng(latLng.latitude + 0.01, latLng.longitude - 0.01),
                    LatLng(latLng.latitude + 0.01, latLng.longitude + 0.01),
                    LatLng(latLng.latitude - 0.01, latLng.longitude + 0.01)
                ) // Creates a square polygon
                .strokeColor(android.graphics.Color.GREEN)
                .fillColor(android.graphics.Color.argb(50, 0, 255, 0)) // Green outline with semi-transparent fill
        )
    }

    // Requests location permissions from the user if not already granted
    private fun requestLocationPermissions() {
        if (!checkLocationPermission()) { // Checks if permissions are missing
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE // Requests fine and coarse location permissions
            )
        }
    }

    // Checks if either fine or coarse location permission is granted
    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED // Returns true if either permission is granted
    }

    // Handles the result of the permission request and updates the map if granted
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) { // Verifies permission was granted
            if (checkLocationPermission()) {
                myMap.isMyLocationEnabled = true // Enables the location layer
                getCurrentLocation() // Updates map with current location
            }
        }
    }
}