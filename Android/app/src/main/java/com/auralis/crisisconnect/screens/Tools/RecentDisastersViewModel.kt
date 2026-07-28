package com.auralis.crisisconnect.screens.Tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.screens.Tools.data.CountryLocator
import com.auralis.crisisconnect.screens.Tools.data.DisasterErrorType
import com.auralis.crisisconnect.screens.Tools.data.DisasterEvent
import com.auralis.crisisconnect.screens.Tools.data.DisasterFeed
import com.auralis.crisisconnect.screens.Tools.data.DisasterRegion
import com.auralis.crisisconnect.screens.Tools.data.DisasterRepository
import com.auralis.crisisconnect.screens.Tools.data.haversineDistanceKm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val NEARBY_RADIUS_KM = 500.0

data class RecentDisastersUiState(
    val isLoading: Boolean = false,      // initial load / tab switch (full-screen skeleton)
    val isRefreshing: Boolean = false,   // pull-to-refresh (keeps current list visible)
    val error: DisasterErrorType? = null,// only set when the list is empty due to a failure
    val isStale: Boolean = false,        // showing cached data after a failed refresh
    val lastUpdatedMillis: Long = 0L,
    val selectedTab: String = "Global",  // "Global" (GDACS) | "Local" (AFAD)
    val selectedCategory: String = "All",
    val selectedAlertLevel: String = "All",
    val searchQuery: String = "",
    val detailEvent: DisasterEvent? = null,
    val hasLocation: Boolean = false,
    val nearbyEvents: List<DisasterEvent> = emptyList(),
    val otherEvents: List<DisasterEvent> = emptyList(),
    val hasAnyEvents: Boolean = false,   // any events loaded (pre-filter) — distinguishes "no data" from "no match"
    val primarySource: String = DisasterRepository.SOURCE_GDACS,
    val hasRegionalTab: Boolean = false, // the "your country" tab exists once a country is detected
    val regionalTabLabel: String = ""    // detected country name shown on that tab
)

class RecentDisastersViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DisasterRepository(application)
    private val countryLocator = CountryLocator(application)

    private val _uiState = MutableStateFlow(RecentDisastersUiState())
    val uiState: StateFlow<RecentDisastersUiState> = _uiState.asStateFlow()

    /** Full result set for the active feed, before filtering. */
    private var allEvents: List<DisasterEvent> = emptyList()
    private var userLat: Double? = null
    private var userLon: Double? = null

    /** The user's detected country; null until/unless detection succeeds. */
    private var region: DisasterRegion? = null

    init {
        viewModelScope.launch {
            // Detect the user's country first so the default tab can be theirs.
            val detected = countryLocator.detect()
            if (detected != null && detected.countryName.isNotBlank()) {
                region = detected
                _uiState.update {
                    it.copy(
                        hasRegionalTab = true,
                        regionalTabLabel = detected.countryName,
                        selectedTab = "Local" // default to the user's own country
                    )
                }
            }
            loadData(forceRefresh = false)
        }
    }

    fun loadData(forceRefresh: Boolean = false) {
        val isGlobal = _uiState.value.selectedTab == "Global"
        val feed = if (isGlobal) DisasterFeed.GLOBAL else DisasterFeed.LOCAL
        val activeRegion = if (isGlobal) null else region

        viewModelScope.launch {
            _uiState.update {
                if (forceRefresh) it.copy(isRefreshing = true)
                else it.copy(isLoading = true, error = null)
            }

            val result = repository.load(feed, activeRegion, forceRefresh)
            allEvents = result.events

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isStale = result.isStale,
                    error = if (result.events.isEmpty()) (result.error ?: DisasterErrorType.NO_DATA) else null,
                    lastUpdatedMillis = result.lastUpdatedMillis,
                    hasAnyEvents = result.events.isNotEmpty(),
                    primarySource = result.primarySource
                )
            }
            recompute()
        }
    }

    fun changeTab(tabName: String) {
        if (_uiState.value.selectedTab == tabName) return
        allEvents = emptyList()
        _uiState.update {
            it.copy(
                selectedTab = tabName,
                searchQuery = "",
                selectedCategory = "All",
                selectedAlertLevel = "All",
                nearbyEvents = emptyList(),
                otherEvents = emptyList(),
                hasAnyEvents = false
            )
        }
        loadData(forceRefresh = false)
    }

    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
        recompute()
    }

    fun selectAlertLevel(level: String) {
        _uiState.update { it.copy(selectedAlertLevel = level) }
        recompute()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        recompute()
    }

    fun showDetail(event: DisasterEvent?) {
        _uiState.update { it.copy(detailEvent = event) }
    }

    /** Called by the screen whenever a fresh device location is obtained. */
    fun updateLocation(lat: Double, lon: Double) {
        userLat = lat
        userLon = lon
        recompute()
    }

    /** Rebuilds the filtered + nearby/other partition from [allEvents]. */
    private fun recompute() {
        val state = _uiState.value
        val filtered = allEvents.filter { event ->
            val categoryMatches = state.selectedCategory == "All" ||
                event.eventType.equals(state.selectedCategory, ignoreCase = true)
            val levelMatches = state.selectedAlertLevel == "All" ||
                event.alertLevel.equals(state.selectedAlertLevel, ignoreCase = true)
            val searchMatches = state.searchQuery.isBlank() ||
                event.country.contains(state.searchQuery, ignoreCase = true) ||
                event.title.contains(state.searchQuery, ignoreCase = true)
            categoryMatches && levelMatches && searchMatches
        }

        val lat = userLat
        val lon = userLon
        if (lat == null || lon == null) {
            _uiState.update {
                it.copy(hasLocation = false, nearbyEvents = emptyList(), otherEvents = filtered)
            }
            return
        }

        // Compute each distance once, then partition.
        val withDistance = filtered
            .filter { it.latitude != 0.0 && it.longitude != 0.0 }
            .map { it to haversineDistanceKm(lat, lon, it.latitude, it.longitude) }

        val nearby = withDistance
            .filter { it.second <= NEARBY_RADIUS_KM }
            .sortedBy { it.second }
            .map { it.first }

        val nearbySet = HashSet(nearby)
        val others = filtered.filter { it !in nearbySet }

        _uiState.update {
            it.copy(hasLocation = true, nearbyEvents = nearby, otherEvents = others)
        }
    }
}
