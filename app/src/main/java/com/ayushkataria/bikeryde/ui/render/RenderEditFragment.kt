package com.ayushkataria.bikeryde.ui.render

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.media.MergedStop
import com.ayushkataria.bikeryde.media.RenderImageStorage
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.media.VideoDurationRecommender
import com.ayushkataria.bikeryde.media.VideoRenderWorker
import com.ayushkataria.bikeryde.ride.RideDay
import com.ayushkataria.bikeryde.ride.RideDayType
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ui.render.RenderEditViewModel.Event
import com.ayushkataria.bikeryde.ui.render.RenderEditViewModel.PickTarget
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The design doc's "tapping [Create Image/Animation] opens a customize screen" step: lets the
 * rider rename stops (e.g. a recorded "Konappana Agrahara" becomes "Home") and attach background
 * photos before the actual render kicks off. A static image gets one cover photo; a single-day
 * video gets one photo per stop; a multi-day video gets one photo (and an optional caption) per
 * day instead — see [daySection].
 *
 * All loaded data and every edit lives in [RenderEditViewModel] so it survives a configuration
 * change; this Fragment only owns the views and rebuilds them from that state. The photo copy and
 * static-image render run in the ViewModel too, so a rotation can't cancel them — this Fragment
 * just reacts to their outcomes ([RenderEditViewModel.events], [RenderEditViewModel.isRendering]).
 */
class RenderEditFragment : Fragment(R.layout.fragment_render_edit) {

    private val viewModel: RenderEditViewModel by viewModels()

    private val renderType: RenderType get() = viewModel.renderType
    private val rideId: Long get() = viewModel.rideId

    private lateinit var coverImageCard: View
    private lateinit var coverImagePreview: ImageView
    private lateinit var coverImageButton: MaterialButton
    private lateinit var coverImageRemoveButton: View
    private lateinit var videoBackgroundHint: View
    private lateinit var videoLengthSection: View
    private lateinit var videoLengthValueText: TextView
    private lateinit var videoLengthSlider: Slider
    private lateinit var daySection: View
    private lateinit var dayLabelsCheckbox: MaterialCheckBox
    private lateinit var daysContainer: ViewGroup
    private lateinit var stopsContainer: ViewGroup
    private lateinit var generateButton: MaterialButton

    // Views of the currently built rows, keyed by stop/day index so a picked photo can find its thumbnail.
    private val stopThumbnails = mutableMapOf<Int, ImageView>()
    private val dayThumbnails = mutableMapOf<Int, ImageView>()
    private val dayCaptionLayouts = mutableMapOf<Int, TextInputLayout>()

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = viewModel.pendingPickTarget
        viewModel.pendingPickTarget = null
        if (uri != null && target != null) viewModel.onImagePicked(target, uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.topBarTitle).setText(
            if (renderType == RenderType.VIDEO) R.string.render_edit_title_video else R.string.render_edit_title_image
        )
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        coverImageCard = view.findViewById(R.id.coverImageCard)
        coverImagePreview = view.findViewById(R.id.coverImagePreview)
        coverImageButton = view.findViewById(R.id.coverImageButton)
        coverImageRemoveButton = view.findViewById(R.id.coverImageRemoveButton)
        videoBackgroundHint = view.findViewById(R.id.videoBackgroundHint)
        videoLengthSection = view.findViewById(R.id.videoLengthSection)
        videoLengthValueText = view.findViewById(R.id.videoLengthValueText)
        videoLengthSlider = view.findViewById(R.id.videoLengthSlider)
        daySection = view.findViewById(R.id.daySection)
        dayLabelsCheckbox = view.findViewById(R.id.dayLabelsCheckbox)
        daysContainer = view.findViewById(R.id.daysContainer)
        stopsContainer = view.findViewById(R.id.stopsContainer)
        generateButton = view.findViewById(R.id.generateButton)

        val isVideo = renderType == RenderType.VIDEO
        coverImageCard.visibility = if (isVideo) View.GONE else View.VISIBLE
        videoLengthSection.visibility = if (isVideo) View.VISIBLE else View.GONE
        generateButton.setText(R.string.render_edit_generate)

        coverImageButton.setOnClickListener { launchPicker(PickTarget.Cover) }
        coverImageRemoveButton.setOnClickListener { clearCoverImage() }
        generateButton.setOnClickListener { onGenerateClicked() }

        // Restore saved state into the controls *before* attaching listeners, so restoring
        // doesn't get recorded back as a new edit.
        dayLabelsCheckbox.isChecked = viewModel.dayLabelsEnabled
        dayLabelsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            viewModel.dayLabelsEnabled = isChecked
            setDayCaptionsVisible(isChecked)
        }

        if (isVideo) {
            videoLengthSlider.value = viewModel.durationMultiplier
            videoLengthSlider.addOnChangeListener { _, value, _ ->
                viewModel.durationMultiplier = value
                updateVideoLengthPreview()
            }
        }

        restoreCoverImage()
        loadEditRows()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isRendering.collect { generateButton.isEnabled = !it } }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun onEvent(event: Event) {
        when (event) {
            is Event.ImagePicked -> showPickedImage(event.target)
            Event.ImagePickFailed -> Toast.makeText(requireContext(), R.string.render_image_failed, Toast.LENGTH_SHORT).show()
            is Event.ImageRenderDone ->
                navigateToPreview(RenderPreviewFragment.args(rideId, RenderType.IMAGE, event.uri.toString()))
            Event.ImageRenderFailed -> Toast.makeText(requireContext(), R.string.render_image_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadEditRows() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.load()
            if (renderType == RenderType.VIDEO) updateVideoLengthPreview()

            videoBackgroundHint.visibility =
                if (renderType == RenderType.VIDEO && !viewModel.useDayRows) View.VISIBLE else View.GONE
            daySection.visibility = if (viewModel.useDayRows) View.VISIBLE else View.GONE

            daysContainer.removeAllViews()
            dayThumbnails.clear()
            dayCaptionLayouts.clear()
            if (viewModel.useDayRows) {
                viewModel.rideDays.forEach { day -> daysContainer.addView(buildDayRow(day)) }
            }

            stopsContainer.removeAllViews()
            stopThumbnails.clear()
            if (viewModel.stops.isEmpty()) {
                val empty = TextView(requireContext()).apply {
                    text = getString(R.string.render_edit_stops_empty)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_surface_variant))
                }
                stopsContainer.addView(empty)
                return@launch
            }
            viewModel.stops.forEachIndexed { index, stop -> stopsContainer.addView(buildStopRow(index, stop)) }
        }
    }

    private fun buildDayRow(day: RideDay): View {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_day_photo_row, daysContainer, false)
        val thumbnail = row.findViewById<ImageView>(R.id.rowThumbnail)
        val title = row.findViewById<TextView>(R.id.rowDayTitle)
        val captionLayout = row.findViewById<TextInputLayout>(R.id.rowCaptionLayout)
        val captionInput = row.findViewById<TextInputEditText>(R.id.rowCaptionInput)

        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        title.text = if (day.dayType == RideDayType.NOT_TRAVEL) {
            getString(R.string.render_edit_day_rest_format, day.dayIndex + 1, dateFormat.format(day.startTime))
        } else {
            getString(
                R.string.render_edit_day_travel_format,
                day.dayIndex + 1,
                day.endPlaceName ?: day.startPlaceName ?: getString(R.string.unknown_location)
            )
        }

        thumbnail.setOnClickListener { launchPicker(PickTarget.Day(day.dayIndex)) }
        dayThumbnails[day.dayIndex] = thumbnail
        viewModel.dayBackgroundPaths[day.dayIndex]?.let { path -> restoreThumbnail(thumbnail, path) }

        // Every row is inflated from the same layout, so its views share one ID and Android's own
        // ID-keyed view-state restore would copy the last row's value into all of them (and, with the
        // listeners below attached, back into the ViewModel). The ViewModel is the source of truth.
        captionInput.isSaveEnabled = false
        captionInput.setText(viewModel.dayCaption(day.dayIndex))
        captionInput.doAfterTextChanged { viewModel.setDayCaption(day.dayIndex, it?.toString().orEmpty()) }
        dayCaptionLayouts[day.dayIndex] = captionLayout
        captionLayout.visibility = if (dayLabelsCheckbox.isChecked) View.VISIBLE else View.GONE

        (row.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = if (day.dayIndex == 0) 0 else dpToPx(20)
        return row
    }

    private fun setDayCaptionsVisible(visible: Boolean) {
        dayCaptionLayouts.values.forEach { it.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    private fun buildStopRow(index: Int, stop: MergedStop): View {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_stop_edit_row, stopsContainer, false)
        val thumbnail = row.findViewById<ImageView>(R.id.rowThumbnail)
        val meta = row.findViewById<TextView>(R.id.rowMeta)
        val nameInput = row.findViewById<TextInputEditText>(R.id.rowNameInput)
        val includeCheckbox = row.findViewById<MaterialCheckBox>(R.id.rowIncludeCheckbox)

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        meta.text = getString(
            R.string.stop_legend_row_format,
            stop.actions.joinToString(", ") { stopActionLabel(it) },
            stop.placeName ?: getString(R.string.unknown_location),
            timeFormat.format(stop.timestamp)
        )
        // Same shared-ID problem as the day captions above: restore from the ViewModel, not view state.
        nameInput.isSaveEnabled = false
        includeCheckbox.isSaveEnabled = false
        nameInput.setText(viewModel.stopName(index))
        nameInput.doAfterTextChanged { viewModel.setStopName(index, it?.toString().orEmpty()) }

        // Once a ride has day rows, photos are assigned per day instead — no per-stop photo picker.
        if (renderType == RenderType.VIDEO && !viewModel.useDayRows) {
            thumbnail.visibility = View.VISIBLE
            thumbnail.setOnClickListener { launchPicker(PickTarget.Stop(index)) }
            stopThumbnails[index] = thumbnail
            viewModel.stopBackgroundPaths[index]?.let { path -> restoreThumbnail(thumbnail, path) }
        } else {
            thumbnail.visibility = View.GONE
        }

        val included = index !in viewModel.excludedStopIndices
        includeCheckbox.isChecked = included
        setStopRowIncluded(row, thumbnail, nameInput, included)
        includeCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.excludedStopIndices.remove(index) else viewModel.excludedStopIndices.add(index)
            setStopRowIncluded(row, thumbnail, nameInput, isChecked)
        }

        (row.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = if (index == 0) 0 else dpToPx(20)
        return row
    }

    /** Dims a stop row's other controls when it's unchecked — excluded stops still show their
     * recorded info for context, but can't be renamed or given a photo since they won't render. */
    private fun setStopRowIncluded(row: View, thumbnail: ImageView, nameInput: TextInputEditText, included: Boolean) {
        row.alpha = if (included) 1f else 0.5f
        thumbnail.isEnabled = included
        nameInput.isEnabled = included
    }

    private fun updateVideoLengthPreview() {
        val finalSeconds = viewModel.videoDurationSeconds
        val multiplier = viewModel.durationMultiplier
        val isDefault = kotlin.math.abs(multiplier - VideoDurationRecommender.DEFAULT_MULTIPLIER) < 0.01f
        videoLengthValueText.text = if (isDefault) {
            getString(R.string.render_edit_length_value_recommended_format, finalSeconds)
        } else {
            getString(
                R.string.render_edit_length_value_multiplier_format,
                finalSeconds,
                String.format(Locale.US, "%.1fx", multiplier)
            )
        }
    }

    private fun launchPicker(target: PickTarget) {
        viewModel.pendingPickTarget = target
        pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /**
     * Shows a photo the ViewModel just recorded for [target]. If that row's view isn't built yet (or
     * was just rebuilt by a rotation) there's nothing to update here — the row reads the path from
     * the ViewModel when it is built.
     */
    private fun showPickedImage(target: PickTarget) {
        when (target) {
            is PickTarget.Cover -> restoreCoverImage()
            is PickTarget.Stop -> {
                val thumbnail = stopThumbnails[target.index]
                val path = viewModel.stopBackgroundPaths[target.index]
                if (thumbnail != null && path != null) restoreThumbnail(thumbnail, path)
            }
            is PickTarget.Day -> {
                val thumbnail = dayThumbnails[target.dayIndex]
                val path = viewModel.dayBackgroundPaths[target.dayIndex]
                if (thumbnail != null && path != null) restoreThumbnail(thumbnail, path)
            }
        }
    }

    private fun restoreCoverImage() {
        val path = viewModel.coverImagePath ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            loadThumbnail(path)?.let { showCoverImage(it) }
        }
    }

    private suspend fun loadThumbnail(path: String): Bitmap? = runCatching {
        RenderImageStorage.decodeSampledBitmap(
            path,
            RenderEditViewModel.THUMBNAIL_SIZE_PX,
            RenderEditViewModel.THUMBNAIL_SIZE_PX
        )
    }.getOrNull()

    /** Re-shows an already-picked photo on a freshly built row. A photo that no longer decodes just leaves the slot blank. */
    private fun restoreThumbnail(view: ImageView, path: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            loadThumbnail(path)?.let { applyThumbnail(view, it) }
        }
    }

    private fun applyThumbnail(view: ImageView, thumbnail: Bitmap) {
        view.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(0, 0, 0, 0)
            // The layout's app:tint (for the placeholder "+" icon) otherwise carries over and
            // flattens the actual photo into a solid tinted block instead of showing it.
            imageTintList = null
            setImageBitmap(thumbnail)
        }
    }

    private fun showCoverImage(thumbnail: Bitmap) {
        coverImagePreview.setImageBitmap(thumbnail)
        coverImagePreview.visibility = View.VISIBLE
        coverImageButton.setText(R.string.render_edit_change_photo)
        coverImageRemoveButton.visibility = View.VISIBLE
    }

    private fun clearCoverImage() {
        viewModel.coverImagePath = null
        coverImagePreview.setImageBitmap(null)
        coverImagePreview.visibility = View.GONE
        coverImageButton.setText(R.string.render_edit_add_photo)
        coverImageRemoveButton.visibility = View.GONE
    }

    private fun onGenerateClicked() {
        when (renderType) {
            // The ViewModel disables the button (via isRendering) and reports back through events.
            RenderType.IMAGE -> viewModel.generateImage()
            RenderType.VIDEO -> generateVideo(viewModel.stopNameOverrides())
        }
    }

    private fun generateVideo(nameOverrides: Map<Int, String>) {
        generateButton.isEnabled = false
        val stopCount = viewModel.stops.size
        val namesArray: Array<String?> = Array(stopCount) { i -> nameOverrides[i].orEmpty() }
        val backgroundsArray: Array<String?> = Array(stopCount) { i -> viewModel.stopBackgroundPaths[i].orEmpty() }

        val dayCount = viewModel.rideDays.size
        val dayBackgroundsArray: Array<String?> = Array(dayCount) { i -> viewModel.dayBackgroundPaths[i].orEmpty() }
        val dayCaptionsArray: Array<String?> = Array(dayCount) { i -> viewModel.dayCaption(i) }

        val request = OneTimeWorkRequestBuilder<VideoRenderWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(VideoRenderWorker.KEY_RIDE_ID, rideId)
                    .putStringArray(VideoRenderWorker.KEY_STOP_NAMES, namesArray)
                    .putStringArray(VideoRenderWorker.KEY_STOP_BACKGROUNDS, backgroundsArray)
                    .putIntArray(VideoRenderWorker.KEY_EXCLUDED_STOPS, viewModel.excludedStopIndices.toIntArray())
                    .putStringArray(VideoRenderWorker.KEY_DAY_BACKGROUNDS, dayBackgroundsArray)
                    .putStringArray(VideoRenderWorker.KEY_DAY_CAPTIONS, dayCaptionsArray)
                    .putBoolean(VideoRenderWorker.KEY_DAY_LABELS_ENABLED, viewModel.useDayRows && viewModel.dayLabelsEnabled)
                    .putInt(VideoRenderWorker.KEY_DURATION_SECONDS, viewModel.videoDurationSeconds)
                    .build()
            )
            .build()

        // REPLACE (not KEEP): this is an explicit user-triggered (re)generate, so it should
        // always actually run — KEEP would silently no-op if a same-named request were still
        // pending, leaving the WorkRequest id below pointing at nothing WorkManager ever runs.
        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            VideoRenderWorker.uniqueWorkName(rideId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        Toast.makeText(requireContext(), R.string.render_video_queued, Toast.LENGTH_SHORT).show()
        navigateToPreview(RenderPreviewFragment.args(rideId, RenderType.VIDEO, workId = request.id.toString()))
    }

    /** Pops this customize screen off the back stack on the way to the preview — while a render is
     * in flight (video) or just finished (image), there's nothing left here to re-submit, so back
     * from the preview should return to whatever screen opened the render flow, not this form. */
    private fun navigateToPreview(args: Bundle) {
        findNavController().navigate(
            R.id.action_renderEdit_to_renderPreview,
            args,
            NavOptions.Builder().setPopUpTo(R.id.renderEditFragment, true).build()
        )
    }

    private fun stopActionLabel(action: RideEventAction): String = when (action) {
        RideEventAction.START -> getString(R.string.stop_action_start)
        RideEventAction.PAUSE -> getString(R.string.stop_action_pause)
        RideEventAction.RESUME -> getString(R.string.stop_action_resume)
        RideEventAction.END -> getString(R.string.stop_action_end)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_RIDE_ID = "rideId"
        const val ARG_RENDER_TYPE = "renderType"

        fun args(rideId: Long, renderType: RenderType): Bundle = bundleOf(
            ARG_RIDE_ID to rideId,
            ARG_RENDER_TYPE to renderType.name
        )
    }
}
