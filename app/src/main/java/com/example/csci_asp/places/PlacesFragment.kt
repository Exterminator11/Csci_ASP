package com.example.csci_asp.places

import android.Manifest
import android.content.pm.PackageManager
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
import com.google.android.gms.maps.model.MarkerOptions

//import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
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
            val readImagesGranted = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
            val mediaLocationGranted = permissions[Manifest.permission.ACCESS_MEDIA_LOCATION] ?: false
            val userSelectedMediaGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] ?: false
            } else false

            if ((readImagesGranted || userSelectedMediaGranted) && mediaLocationGranted) {
                // Full access granted, launch the photo picker
                photoPickerLauncher.launch("image/*")
            } else {
                Snackbar.make(binding.root, "Full or partial media permission and location permissions are required.", Snackbar.LENGTH_LONG).show()
            }
        }

        // Initialize the launcher here.
        photoPickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                val safeContext = context
                if (uri == null || safeContext == null) {
                    return@registerForActivityResult
                }

                val contentResolver = safeContext.contentResolver
                val photoLocation = PhotoLocation.readLocationMetadata(contentResolver, uri)

                if (photoLocation != null) {
                    println("Got (Lat,Lng)=(${photoLocation.latitude},${photoLocation.longitude}).")
                    // If we have a location, update the map
                    updateMapLocation(photoLocation)
                } else {
                    println("No location metadata found in the selected image.")
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
            val permissionsToRequest = mutableListOf(
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
            // READ_MEDIA_IMAGES is only needed for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                // For older versions, use READ_EXTERNAL_STORAGE
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasReadPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            val hasLocationPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            when {
                hasReadPermission && hasLocationPermission -> {
                    // Both permissions are already granted
                    photoPickerLauncher.launch("image/*")
                }
                else -> {
                    // Request the permissions array
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        // You can set up initial map settings here if you want
        // For example, move to a default location
        val defaultLocation = LatLng(40.7128, -74.0060) // New York City
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
    }

    private fun updateMapLocation(location: LatLng) {
        googleMap?.let { map ->
            map.clear() // Remove any existing markers
            map.addMarker(MarkerOptions().position(location).title("Photo Location"))
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f)) // Zoom in on the location
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
