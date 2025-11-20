package com.example.csci_asp.places

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.maps.model.LatLng
import java.io.IOException

class PhotoLocation {

    companion object {
        private const val TAG = "PhotoLocation"
        fun readLocationMetadata(resolver: ContentResolver, imageUri: Uri): LatLng? {
            try {
                // IMPORTANT: Request the original URI to get full metadata.
                // This is the key to preserving location data. [4, 5]
                val originalUri = MediaStore.setRequireOriginal(imageUri)

                resolver.openInputStream(originalUri)?.use { stream -> // Use the new originalUri
                    val exifInterface = ExifInterface(stream)

                    val latLong = exifInterface.latLong
                    if (latLong != null && latLong.size == 2) {
                        // Check for invalid (0.0, 0.0) coordinates
                        if (latLong[0] == 0.0 && latLong[1] == 0.0) {
                            Log.d(TAG, "Found invalid GPS coordinates (0.0, 0.0).")
                            return null
                        }
                        return LatLng(latLong[0], latLong[1])
                    }
                }
            } catch (e: SecurityException) {
                // This will be thrown if ACCESS_MEDIA_LOCATION is not granted.
                Log.e(TAG, "SecurityException: Missing ACCESS_MEDIA_LOCATION permission?", e)
            } catch (e: IOException) {
                Log.e(TAG, "Could not read image stream.", e)
            }
            return null
        }

        fun readTimeStampMetadata(resolver: ContentResolver, imageUri: Uri): String? {
             try {
                val originalUri = MediaStore.setRequireOriginal(imageUri)

                resolver.openInputStream(originalUri)?.use { stream -> // Use the new originalUri
                    val exifInterface = ExifInterface(stream)

                    val timestamp = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)

                    println("Timestamp: {$timestamp}")
                    return timestamp
                }
            } catch (e: IOException) {
                Log.e(TAG, "Could not read image stream.", e)
            }
            return null
        }
//        fun readLocationMetadata(context: Context, imageUri: Uri): LatLng? {
//            try {
//                // Open an InputStream from the image Uri
//                val inputStream = context.contentResolver.openInputStream(imageUri)
//
//                inputStream?.use { stream ->
//                    // Create an ExifInterface instance from the InputStream
//                    val exifInterface = ExifInterface(stream)
//
//                    // Get the GPS coordinates as a float array
//                    val latLong = exifInterface.latLong
//
//                    // Check if latLong is not null (meaning GPS data exists)
//                    if (latLong != null && latLong.size == 2) {
//                        val latitude = latLong[0]
//                        val longitude = latLong[1]
//                        return LatLng(latitude, longitude)
//                    }
//                }
//            } catch (e: IOException) {
//                e.printStackTrace()
//            } catch (e: SecurityException) {
//                // Handle cases where ACCESS_MEDIA_LOCATION permission might be denied
//                e.printStackTrace()
//            }
//            return null
//        }

        fun identifyPlace(context: Context, location: LatLng) {
            //Call Google Places API
        }
    }


}