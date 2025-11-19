package com.example.csci_asp.places

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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


        // This launcher only needs to handle the result of a permission request.
        // The decision to launch the picker will be made after the result is processed.
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Check if either full or partial media access was granted, along with location.
            val hasFullMediaAccess = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
            val hasPartialMediaAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] ?: false
            } else false
            val hasLocationAccess = permissions[Manifest.permission.ACCESS_MEDIA_LOCATION] ?: false

            if ((hasFullMediaAccess || hasPartialMediaAccess) && hasLocationAccess) {
                // User has granted the necessary permissions, launch the picker directly.
                photoPickerLauncher.launch("image/*")
            } else {
                Snackbar.make(binding.root, "Full or partial media permission and location permissions are required.", Snackbar.LENGTH_LONG).show()
            }
        }

//        // Initialize the permission launcher
//        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
//            // Check if either full or partial media access was granted, along with location.
//            val hasFullMediaAccess = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
//            val hasPartialMediaAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//                permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] ?: false
//            } else false
//            val hasLocationAccess = permissions[Manifest.permission.ACCESS_MEDIA_LOCATION] ?: false
//
//            if ((hasFullMediaAccess || hasPartialMediaAccess) && hasLocationAccess) {
//                // User has granted the necessary permissions, launch the picker.
//                photoPickerLauncher.launch("image/*")
//            } else {
//                Snackbar.make(binding.root, "Full or partial media permission and location permissions are required.", Snackbar.LENGTH_LONG).show()
//            }
//        }

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

                // Sort the selected photos by timestamp
                val sortedUris = uris.sortedBy { uri -> PhotoLocation.readTimeStampMetadata(contentResolver, uri) }

                val boundsBuilder = LatLngBounds.builder()
                val locationsFound = mutableListOf<LatLng>()

                for (uri in sortedUris) {
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
                        val bounds = boundsBuilder.build()
                        // Pad the edges by 100 pixels so markers aren't on the very edge.
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
            val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            val locationPermission = Manifest.permission.ACCESS_MEDIA_LOCATION

            // Check the status of each required permission.
            val hasReadPermission = ContextCompat.checkSelfPermission(requireContext(), readPermission) == PackageManager.PERMISSION_GRANTED
            val hasLocationPermission = ContextCompat.checkSelfPermission(requireContext(), locationPermission) == PackageManager.PERMISSION_GRANTED

            // On Android 14+, we also need to consider the partial access permission.
            val hasPartialAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            } else false

            // If we have location access and either full or partial media access, we can launch the picker.
            if (hasLocationPermission && (hasReadPermission || hasPartialAccess)) {
                photoPickerLauncher.launch("image/*")
            } else {
                // We are missing one or more permissions, so we request them.
                val permissionsToRequest = mutableListOf<String>()
                if (!hasReadPermission && !hasPartialAccess) {
                    permissionsToRequest.add(readPermission)
                    // On Android 14+, add the user selection permission as well for the picker prompt.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    }
                }
                if (!hasLocationPermission) {
                    permissionsToRequest.add(locationPermission)
                }

                if (permissionsToRequest.isNotEmpty()) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                } else {
                    // This case should ideally not be hit if the logic above is sound, but as a fallback:
                    photoPickerLauncher.launch("image/*")
                }
            }
        }
    }


//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//
//        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
//        mapFragment?.getMapAsync(this)
//
//        binding.selectPhotosButton.setOnClickListener {
//
//            val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                Manifest.permission.READ_MEDIA_IMAGES
//            } else {
//                Manifest.permission.READ_EXTERNAL_STORAGE
//            }
//            val locationPermission = Manifest.permission.ACCESS_MEDIA_LOCATION
//
//            val hasReadPermission = ContextCompat.checkSelfPermission(requireContext(), readPermission) == PackageManager.PERMISSION_GRANTED
//            val hasLocationPermission = ContextCompat.checkSelfPermission(requireContext(), locationPermission) == PackageManager.PERMISSION_GRANTED
//
//            val permissionsToRequest = mutableListOf<String>()
//            if (!hasReadPermission) {
//                // On Android 14+, add the user selection permission as well.
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
//                }
//                permissionsToRequest.add(readPermission)
//            }
//            if (!hasLocationPermission) {
//                permissionsToRequest.add(locationPermission)
//            }
//
//            if (permissionsToRequest.isEmpty()) {
//                // All permissions are already granted, launch the picker directly.
//                photoPickerLauncher.launch("image/*")
//            } else {
//                // Request only the permissions that are missing.
//                permissionLauncher.launch(permissionsToRequest.toTypedArray())
//            }
//        }
//    }

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
