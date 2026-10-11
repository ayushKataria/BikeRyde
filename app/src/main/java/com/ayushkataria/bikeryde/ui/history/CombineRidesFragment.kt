package com.ayushkataria.bikeryde.ui.history

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.CombineDay
import com.ayushkataria.bikeryde.ui.history.CombineRidesViewModel.Event
import com.ayushkataria.bikeryde.ui.history.CombineRidesViewModel.UiState
import com.ayushkataria.bikeryde.ui.ride.MultiDayRowBinder
import com.ayushkataria.bikeryde.ui.ride.RideDetailFragment
import com.ayushkataria.bikeryde.ui.ride.RideStatsFormat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Builds a multi-day ride out of completed single-day rides, opened from [RideHistoryFragment] —
 * for a trip recorded one day at a time with the single-day tracker. The rider ticks the rides to
 * include (ordered by date), optionally inserts rest/sightseeing days after any of them, and the
 * result is saved as an ordinary completed multi-day ride (see [com.ayushkataria.bikeryde.ride.RideRepository.combineRides]).
 */
class CombineRidesFragment : Fragment(R.layout.fragment_combine_rides) {

    private val viewModel: CombineRidesViewModel by viewModels()

    private lateinit var ridesContainer: LinearLayout
    private lateinit var emptyStateText: View
    private lateinit var daysSection: View
    private lateinit var daysContainer: LinearLayout
    private lateinit var titleInput: TextInputEditText
    private lateinit var deleteOriginalsCheckbox: MaterialCheckBox
    private lateinit var combineButton: MaterialButton

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.topBarTitle).setText(R.string.combine_title)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        ridesContainer = view.findViewById(R.id.ridesContainer)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        daysSection = view.findViewById(R.id.daysSection)
        daysContainer = view.findViewById(R.id.daysContainer)
        titleInput = view.findViewById(R.id.titleInput)
        deleteOriginalsCheckbox = view.findViewById(R.id.deleteOriginalsCheckbox)
        combineButton = view.findViewById(R.id.combineButton)

        combineButton.setOnClickListener {
            viewModel.combine(titleInput.text?.toString(), deleteOriginalsCheckbox.isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun render(state: UiState) {
        if (state.isLoading) return
        emptyStateText.visibility =
            if (state.candidates.size < CombineRidesViewModel.MIN_RIDES) View.VISIBLE else View.GONE

        ridesContainer.removeAllViews()
        state.candidates.forEach { candidate ->
            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_combine_ride_row, ridesContainer, false)
            val ride = candidate.ride
            row.findViewById<TextView>(R.id.cardTitle).text =
                ride.title?.takeIf { it.isNotBlank() } ?: dateFormat.format(ride.startTime)
            row.findViewById<TextView>(R.id.cardSubtitle).text = getString(
                R.string.ride_history_row_subtitle_format,
                RideStatsFormat.distance(ride.totalDistanceM),
                RideStatsFormat.duration(ride.totalDurationS)
            )
            row.findViewById<CheckBox>(R.id.rowCheckbox).isChecked = ride.id in state.selectedRideIds
            row.isEnabled = !state.isCombining
            row.setOnClickListener { viewModel.toggleSelected(ride.id) }
            ridesContainer.addView(row)
        }

        val plannedDays = state.plannedDays
        daysSection.visibility = if (state.selectedRideIds.size >= CombineRidesViewModel.MIN_RIDES) View.VISIBLE else View.GONE
        daysContainer.removeAllViews()
        plannedDays.forEachIndexed { index, planned ->
            val dayRow = MultiDayRowBinder.buildRow(requireContext(), daysContainer, planned.preview)
            if (planned.plan is CombineDay.Rest) {
                daysContainer.addView(withRemoveButton(dayRow, planned.afterRideId, state.isCombining))
            } else {
                daysContainer.addView(dayRow)
            }
            // The "add" slot goes after a ride's last rest day, so new rest days append in order.
            val isLastOfGroup = plannedDays.getOrNull(index + 1)?.plan !is CombineDay.Rest
            if (isLastOfGroup) {
                daysContainer.addView(buildAddRestDayButton(planned.afterRideId, state.isCombining))
            }
        }

        combineButton.isEnabled = state.canCombine
    }

    /** Overlays a remove (×) button on a rest day's [MultiDayRowBinder] row. */
    private fun withRemoveButton(dayRow: View, afterRideId: Long, isCombining: Boolean): View {
        val size = (40 * resources.displayMetrics.density).toInt()
        val borderless = TypedValue().also {
            requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        val removeButton = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(borderless.resourceId)
            contentDescription = getString(R.string.combine_remove_rest_day)
            isEnabled = !isCombining
            setOnClickListener { viewModel.removeRestDay(afterRideId) }
        }
        return FrameLayout(requireContext()).apply {
            addView(dayRow)
            addView(removeButton, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END))
        }
    }

    private fun buildAddRestDayButton(afterRideId: Long, isCombining: Boolean): View =
        LayoutInflater.from(requireContext()).inflate(R.layout.item_combine_add_rest_day, daysContainer, false).apply {
            isEnabled = !isCombining
            setOnClickListener { viewModel.addRestDay(afterRideId) }
        }

    private fun onEvent(event: Event) {
        when (event) {
            is Event.Combined -> findNavController().navigate(
                R.id.action_combineRides_to_rideDetail,
                RideDetailFragment.args(event.rideId)
            )
            Event.CombineFailed -> Toast.makeText(requireContext(), R.string.combine_failed, Toast.LENGTH_LONG).show()
        }
    }
}
