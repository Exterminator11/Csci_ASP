package com.example.csci_asp.photos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.csci_asp.R
import com.example.csci_asp.databinding.FragmentPhotoDetailBinding
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Locale

class PhotoDetailFragment : Fragment() {

    private var _binding: FragmentPhotoDetailBinding? = null
    private val binding get() = _binding!!

    private val dateFormat = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val devicePhoto = arguments?.getSerializable(ARG_DEVICE_PHOTO) as? DevicePhoto
        if (devicePhoto == null) {
            Snackbar.make(
                binding.root,
                getString(R.string.photo_detail_load_error),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        renderPhoto(devicePhoto)

        binding.buttonSendCaption.setOnClickListener {
            Snackbar.make(
                binding.root,
                getString(R.string.photo_detail_caption_placeholder_action),
                Snackbar.LENGTH_SHORT
            ).show()
        }

        binding.buttonSendSummary.setOnClickListener {
            Snackbar.make(
                binding.root,
                getString(R.string.photo_detail_summary_placeholder_action),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderPhoto(photo: DevicePhoto) {
        binding.imageFullPhoto.setImageURI(photo.uri)

        val unknownValue = getString(R.string.common_unknown)

        binding.textMetadataFileName.text = getString(
            R.string.photo_detail_metadata_filename,
            photo.displayName ?: unknownValue
        )

        binding.textMetadataSize.text = getString(
            R.string.photo_detail_metadata_size,
            readableFileSize(photo.sizeBytes) ?: unknownValue
        )

        val dimensions = if (photo.width != null && photo.height != null) {
            "${photo.width} x ${photo.height}"
        } else {
            unknownValue
        }
        binding.textMetadataDimensions.text = getString(
            R.string.photo_detail_metadata_dimensions,
            dimensions
        )

        binding.textMetadataMime.text = getString(
            R.string.photo_detail_metadata_mime,
            photo.mimeType ?: unknownValue
        )

        binding.textMetadataDate.text = getString(
            R.string.photo_detail_metadata_date,
            photo.dateTakenMillis?.let { dateFormat.format(it) } ?: unknownValue
        )
    }

    private fun readableFileSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
            Locale.getDefault(),
            "%.1f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_DEVICE_PHOTO = "arg_device_photo"
    }
}

