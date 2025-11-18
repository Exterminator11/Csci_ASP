package com.example.csci_asp.places

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.maps.model.LatLng
import java.io.IOException

class PhotoLocation {

    fun readLocationMetadata(context: Context, imageUri: Uri): LatLng? {
        try {
            // Open an InputStream from the image Uri
            val inputStream = context.contentResolver.openInputStream(imageUri)

            inputStream?.use { stream ->
                // Create an ExifInterface instance from the InputStream
                val exifInterface = ExifInterface(stream)

                // Get the GPS coordinates as a float array
                val latLong = exifInterface.latLong

                // Check if latLong is not null (meaning GPS data exists)
                if (latLong != null && latLong.size == 2) {
                    val latitude = latLong[0]
                    val longitude = latLong[1]
                    return LatLng(latitude, longitude)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            // Handle cases where ACCESS_MEDIA_LOCATION permission might be denied
            e.printStackTrace()
        }
        return null
    }

    fun identifyPlace(context: Context, location: LatLng) {
        //Call Google Places API
    }

}