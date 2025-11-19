package com.example.csci_asp.places

import android.Manifest
//import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.csci_asp.databinding.FragmentPlacesBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

//import androidx.activity.result.PickVisualMediaRequest
//import androidx.core.content.ContextCompat
import com.example.csci_asp.R
import com.google.android.material.snackbar.Snackbar



class PlacesFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentPlacesBinding? = null
    private val binding get() = _binding!!
    private lateinit var photoPickerLauncher: ActivityResultLauncher<String>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var googleMap: GoogleMap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the permission launcher
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Check if either full or partial media access was granted, along with location.
            val hasFullMediaAccess = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
            val hasPartialMediaAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] ?: false
            } else false
            val hasLocationAccess = permissions[Manifest.permission.ACCESS_MEDIA_LOCATION] ?: false

            if ((hasFullMediaAccess || hasPartialMediaAccess) && hasLocationAccess) {
                // User has granted the necessary permissions, launch the picker.
                photoPickerLauncher.launch("image/*")
            } else {
                Snackbar.make(binding.root, "Full or partial media permission and location permissions are required.", Snackbar.LENGTH_LONG).show()
            }
        }

        // Initialize the launcher here.
        photoPickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
                val safeContext = context
                if (uris.isNullOrEmpty() || safeContext == null) {
                    println("No photos selected or context is null.")
                    return@registerForActivityResult
                }

                googleMap?.clear() // Clear existing markers before adding new ones
                val contentResolver = safeContext.contentResolver

                val boundsBuilder = LatLngBounds.builder()
                val locationsFound = mutableListOf<LatLng>()

                for (uri in uris) {
                    val photoLocation = PhotoLocation.readLocationMetadata(contentResolver, uri)
                    if (photoLocation != null) {
                        println("Found photo at (Lat,Lng)=(${photoLocation.latitude},${photoLocation.longitude}).")
                        // Add a marker for each photo that has location data
                        googleMap?.addMarker(MarkerOptions().position(photoLocation).title("Photo Location"))
                        // Include this location in our bounds
                        boundsBuilder.include(photoLocation)
                        locationsFound.add(photoLocation)
                    } else {
                        println("Photo at $uri does not have location metadata.")
                    }
                }

                if (locationsFound.size > 1) {
                    val polylineOptions = PolylineOptions()
                        .addAll(locationsFound)
                        .color(Color.BLUE)      // Set the line color
                        .width(10f)     // Set the line width in pixels
                    googleMap?.addPolyline(polylineOptions)
                }

                // Zoom the map to fit the bounds
                if (locationsFound.isNotEmpty()) {
                    if (locationsFound.size == 1) {
                        // If there's only one marker, just zoom to it.
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(locationsFound.first(), 15f))
                    } else {
                        // If there are multiple markers, build the bounds and animate.
                        val bounds = boundsBuilder.build()
                        // The '100' is padding in pixels, so markers aren't on the very edge.
                        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100)
                        googleMap?.animateCamera(cameraUpdate)
                    }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlacesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        binding.selectPhotosButton.setOnClickListener {
            // Build the list of permissions to request based on the Android version.
            val permissionsToRequest = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else { // Below Android 13
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionsToRequest.add(Manifest.permission.ACCESS_MEDIA_LOCATION)

            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        // You can set up initial map settings here if you want
        // For example, move to a default location
        val defaultLocation = LatLng(40.7128, -74.0060) // New York City
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
