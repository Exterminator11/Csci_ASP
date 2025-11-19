package com.example.csci_asp.photos

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Utility functions for photo processing
 */
object PhotoUtils {

    /**
     * Converts an image URI to base64 encoded string
     * @param uri The URI of the image
     * @param context The context to access content resolver
     * @return Base64 encoded string or null if conversion fails
     */
    fun convertImageToBase64(uri: Uri, context: Context): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        } catch (e: SecurityException) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts image type (extension) from file name
     * @param fileName The file name (e.g., "orange.jpg")
     * @return The image type without dot (e.g., "jpg") or null if not found
     */
    fun extractImageTypeFromFileName(fileName: String): String? {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex >= 0 && lastDotIndex < fileName.length - 1) {
            fileName.substring(lastDotIndex + 1).lowercase(Locale.getDefault())
        } else {
            null
        }
    }

    /**
     * Formats timestamp in milliseconds to ISO 8601 format
     * @param timestampMillis The timestamp in milliseconds
     * @return ISO 8601 formatted string (e.g., "2025-11-18T20:30:00Z") or null if timestamp is null
     */
    fun formatTimestampToISO8601(timestampMillis: Long?): String? {
        if (timestampMillis == null) return null
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(timestampMillis)
    }

    /**
     * Reads location from EXIF metadata
     * @param uri The URI of the image
     * @param context The context to access content resolver
     * @return Location string (e.g., "Malibu, California") or null if not available
     * Note: This currently returns null as reverse geocoding would require additional API calls
     */
    fun getLocationFromExif(uri: Uri, context: Context): String? {
        // For now, return null as we would need to reverse geocode GPS coordinates
        // to get a location string like "Malibu, California"
        // This can be implemented later if needed using Google Places API or similar
        return null
    }
}

