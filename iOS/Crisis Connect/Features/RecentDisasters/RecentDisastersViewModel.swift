//
//  RecentDisastersViewModel.swift
//  Crisis Connect
//
//  Ported from the Android RecentDisastersViewModel. Detects the user's country
//  for the default "local" tab, loads merged disaster feeds, and partitions
//  results into a nearby/other split based on device location.
//

import Foundation
import Combine
import CoreLocation
import WidgetKit

private let nearbyRadiusKm = 500.0

@MainActor
final class RecentDisastersViewModel: ObservableObject {

    // Loading / error state
    @Published var isLoading = false        // initial load / tab switch (full-screen skeleton)
    @Published var isRefreshing = false     // pull-to-refresh (keeps current list visible)
    @Published var error: DisasterErrorType? // only set when the list is empty due to a failure
    @Published var isStale = false          // showing cached data after a failed refresh
    @Published var lastUpdatedMillis: Int64 = 0

    // Filters / selection
    @Published var selectedTab = "Global"   // "Global" | "Local"
    @Published var selectedCategory = "All"
    @Published var selectedAlertLevel = "All"
    @Published var searchQuery = ""
    @Published var detailEvent: DisasterEvent?

    // Derived content
    @Published var hasLocation = false
    @Published var nearbyEvents: [DisasterEvent] = []
    @Published var otherEvents: [DisasterEvent] = []
    @Published var hasAnyEvents = false     // any events loaded (pre-filter)
    @Published var primarySource = DisasterSource.gdacs

    // Regional ("your country") tab
    @Published var hasRegionalTab = false
    @Published var regionalTabLabel = ""

    private let repository = DisasterRepository()
    private let countryLocator = CountryLocator()

    private var allEvents: [DisasterEvent] = []
    private var userLat: Double?
    private var userLon: Double?
    private var region: DisasterRegion?
    private var didStart = false

    var hasEvents: Bool { !nearbyEvents.isEmpty || !otherEvents.isEmpty }

    /// Called once when the screen appears. Detects the user's country (so the
    /// default tab can be theirs) and performs the first load.
    func start(initialCoordinate: CLLocationCoordinate2D?) async {
        guard !didStart else { return }
        didStart = true

        let detected = await countryLocator.detect(coordinate: initialCoordinate)
        if let detected, !detected.countryName.isEmpty {
            region = detected
            hasRegionalTab = true
            regionalTabLabel = detected.countryName
            selectedTab = "Local" // default to the user's own country
            // Hand the detected region to the home-screen widget (App Group).
            WidgetSharedState.saveRegion(detected)
        }
        await loadData(forceRefresh: false)
    }

    func loadData(forceRefresh: Bool) async {
        let isGlobal = selectedTab == "Global"
        let feed: DisasterFeed = isGlobal ? .global : .local
        let activeRegion = isGlobal ? nil : region

        if forceRefresh {
            isRefreshing = true
        } else {
            isLoading = true
            error = nil
        }

        let result = await repository.load(feed: feed, region: activeRegion, forceRefresh: forceRefresh)
        allEvents = result.events

        isLoading = false
        isRefreshing = false
        isStale = result.isStale
        error = result.events.isEmpty ? (result.error ?? .noData) : nil
        lastUpdatedMillis = result.lastUpdatedMillis
        hasAnyEvents = !result.events.isEmpty
        primarySource = result.primarySource
        recompute()

        if !result.events.isEmpty {
            WidgetCenter.shared.reloadTimelines(ofKind: WidgetSharedState.disastersWidgetKind)
        }
    }

    func changeTab(_ tabName: String) {
        guard selectedTab != tabName else { return }
        allEvents = []
        selectedTab = tabName
        searchQuery = ""
        selectedCategory = "All"
        selectedAlertLevel = "All"
        nearbyEvents = []
        otherEvents = []
        hasAnyEvents = false
        Task { await loadData(forceRefresh: false) }
    }

    func selectCategory(_ category: String) {
        selectedCategory = category
        recompute()
    }

    func selectAlertLevel(_ level: String) {
        selectedAlertLevel = level
        recompute()
    }

    func updateSearchQuery(_ query: String) {
        searchQuery = query
        recompute()
    }

    func showDetail(_ event: DisasterEvent?) {
        detailEvent = event
    }

    /// Called whenever a fresh device location is obtained.
    func updateLocation(latitude: Double, longitude: Double) {
        userLat = latitude
        userLon = longitude
        recompute()
    }

    /// Rebuilds the filtered + nearby/other partition from `allEvents`.
    private func recompute() {
        let filtered = allEvents.filter { event in
            let categoryMatches = selectedCategory == "All"
                || event.eventType.uppercased() == selectedCategory.uppercased()
            let levelMatches = selectedAlertLevel == "All"
                || event.alertLevel.uppercased() == selectedAlertLevel.uppercased()
            let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            let searchMatches = query.isEmpty
                || event.country.localizedCaseInsensitiveContains(query)
                || event.title.localizedCaseInsensitiveContains(query)
            return categoryMatches && levelMatches && searchMatches
        }

        guard let lat = userLat, let lon = userLon else {
            hasLocation = false
            nearbyEvents = []
            otherEvents = filtered
            return
        }

        let withDistance = filtered
            .filter { $0.hasCoordinates }
            .map { ($0, DisasterGeo.haversineKm(lat, lon, $0.latitude, $0.longitude)) }

        let nearby = withDistance
            .filter { $0.1 <= nearbyRadiusKm }
            .sorted { $0.1 < $1.1 }
            .map { $0.0 }

        let nearbyIds = Set(nearby.map { $0.id })
        let others = filtered.filter { !nearbyIds.contains($0.id) }

        hasLocation = true
        nearbyEvents = nearby
        otherEvents = others
    }
}

// ─── Location provider ─────────────────────────────────────────────────────────

/// Lightweight CoreLocation wrapper for the Recent Disasters screen. Provides a
/// best last-known location (for the nearby split) and requests "when in use"
/// permission on demand. Mirrors the screen-level location handling on Android.
@MainActor
final class RecentDisastersLocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var currentLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus

    private let manager = CLLocationManager()

    override init() {
        authorizationStatus = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    var isAuthorized: Bool {
        authorizationStatus == .authorizedAlways || authorizationStatus == .authorizedWhenInUse
    }

    /// Returns the best immediately-available location, if any.
    var bestLastKnownCoordinate: CLLocationCoordinate2D? {
        (currentLocation ?? (isAuthorized ? manager.location : nil))?.coordinate
    }

    /// Requests permission if needed and refreshes the location.
    func requestLocation() {
        switch authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            refresh()
        default:
            break
        }
    }

    func refresh() {
        guard isAuthorized else { return }
        if let location = manager.location {
            currentLocation = location
        }
        manager.requestLocation()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            if self.isAuthorized { self.refresh() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in
            self.currentLocation = location
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Best-effort: keep whatever location we already have.
    }
}
