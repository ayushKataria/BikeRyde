package com.ayushkataria.bikeryde.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.Ride
import com.ayushkataria.bikeryde.ride.RideRepository
import com.ayushkataria.bikeryde.ride.RideType
import com.ayushkataria.bikeryde.ui.ride.RideDetailFragment
import com.ayushkataria.bikeryde.ui.ride.RideStatsFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Lists completed rides, most recent first; tapping one opens [com.ayushkataria.bikeryde.ui.ride.RideDetailFragment]. */
class RideHistoryFragment : Fragment(R.layout.fragment_ride_history) {

    private lateinit var repository: RideRepository
    private lateinit var listContainer: ViewGroup
    private lateinit var emptyStateText: View
    private lateinit var combineRidesButton: View

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.topBarTitle).setText(R.string.home_card_ride_history_title)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        repository = RideRepository(requireContext())
        listContainer = view.findViewById(R.id.rideListContainer)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        combineRidesButton = view.findViewById(R.id.combineRidesButton)
        combineRidesButton.setOnClickListener { findNavController().navigate(R.id.action_rideHistory_to_combineRides) }

        loadRides()
    }

    private fun loadRides() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rides = repository.getCompletedRides()
            listContainer.removeAllViews()
            emptyStateText.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
            combineRidesButton.visibility =
                if (rides.count { it.type == RideType.SINGLE_DAY } >= CombineRidesViewModel.MIN_RIDES) View.VISIBLE else View.GONE
            rides.forEach { ride -> listContainer.addView(buildRow(ride)) }
        }
    }

    private fun displayTitle(ride: Ride): String = ride.title?.takeIf { it.isNotBlank() } ?: dateFormat.format(ride.startTime)

    private fun buildRow(ride: Ride): View {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_ride_history_row, listContainer, false)
        row.findViewById<TextView>(R.id.cardTitle).text = displayTitle(ride)
        row.findViewById<TextView>(R.id.cardSubtitle).text = getString(
            R.string.ride_history_row_subtitle_format,
            RideStatsFormat.distance(ride.totalDistanceM),
            RideStatsFormat.duration(ride.totalDurationS)
        )
        row.setOnClickListener {
            findNavController().navigate(
                R.id.action_rideHistory_to_rideDetail,
                RideDetailFragment.args(ride.id)
            )
        }
        row.findViewById<View>(R.id.rowMenuButton).setOnClickListener { anchor -> showRowMenu(anchor, ride) }
        return row
    }

    private fun showRowMenu(anchor: View, ride: Ride) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.ride_history_action_rename)
            menu.add(0, MENU_DELETE, 1, R.string.ride_history_action_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> {
                        showRenameDialog(ride)
                        true
                    }
                    MENU_DELETE -> {
                        showDeleteConfirm(ride)
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private fun showRenameDialog(ride: Ride) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(requireContext()).apply {
            setText(ride.title)
            hint = dateFormat.format(ride.startTime)
            setSelection(text.length)
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ride_rename_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.renameRide(ride.id, input.text.toString())
                    loadRides()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirm(ride: Ride) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ride_delete_confirm_title)
            .setMessage(R.string.ride_delete_confirm_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deleteRide(ride.id)
                    loadRides()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
    }
}
