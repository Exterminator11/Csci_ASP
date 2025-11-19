package com.example.csci_asp.places

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.csci_asp.databinding.FragmentPlacesBinding

//import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
//import com.example.csci_asp.R
import com.google.android.material.snackbar.Snackbar



class PlacesFragment : Fragment() {

    private var _binding: FragmentPlacesBinding? = null
    private val binding get() = _binding!!
    private lateinit var photoPickerLauncher: ActivityResultLauncher<String>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the permission launcher
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // If permission is granted, launch the new picker
                photoPickerLauncher.launch("image/*") // Pass the MIME type
            } else {
                // Explain to the user that the feature is unavailable
                Snackbar.make(binding.root, "Permission denied. Cannot access photo location data.", Snackbar.LENGTH_LONG).show()
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
                // This call will now work because GetContent provides a compatible URI
                val photoLocation = PhotoLocation.readLocationMetadata(contentResolver, uri)

                if (photoLocation != null) {
                    println("Got (Lat,Lng)=(${photoLocation.latitude},${photoLocation.longitude}).")
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

        binding.selectPhotosButton.setOnClickListener {
            // Check if permission is already granted
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // 4. Launch the GetContent picker with the "image/*" MIME type
                    photoPickerLauncher.launch("image/*")
                }
                else -> {
                    // If permission is not granted, launch the permission request
                    permissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
//    private fun applySelection(uris: List<Uri>) {
//        val trimmed = if (uris.size > MAX_SELECTION) {
//            _binding?.let {
//                Snackbar.make(
//                    it.root,
//                    getString(R.string.scrapbook_max_selection, MAX_SELECTION),
//                    Snackbar.LENGTH_SHORT
//                ).show()
//            }
//            uris.take(MAX_SELECTION)
//        } else {
//            uris
//        }
//        viewModel.setPhotos(trimmed.toList())
//    }
}
