//
//  RecentDisastersView.swift
//  Crisis Connect
//
//  SwiftUI port of the Android RecentDisastersScreen. Shows a merged, multi-source
//  disaster feed with a "your country" / global tab split, search + filters, a
//  nearby section based on device location, and a per-event detail sheet.
//

import SwiftUI
import Combine
import CoreLocation
import MapKit

// ─── Shared helpers ──────────────────────────────────────────────────────────

private func alertColor(_ level: String) -> Color {
    switch level.lowercased() {
    case "red": return Color(red: 0xD3 / 255, green: 0x2F / 255, blue: 0x2F / 255)
    case "orange": return Color(red: 0xF5 / 255, green: 0x7C / 255, blue: 0x00 / 255)
    case "green": return Color(red: 0x38 / 255, green: 0x8E / 255, blue: 0x3C / 255)
    default: return .gray
    }
}

private let severityRed = Color(red: 0xD3 / 255, green: 0x2F / 255, blue: 0x2F / 255)
private let severityGreen = Color(red: 0x38 / 255, green: 0x8E / 255, blue: 0x3C / 255)
private let offlineOrange = Color(red: 0xE6 / 255, green: 0x51 / 255, blue: 0x00 / 255)

private func eventTypeSymbol(_ type: String) -> String {
    switch type.uppercased() {
    case "EQ": return "waveform.path"
    case "FL": return "drop.fill"
    case "TC": return "hurricane"
    case "VO": return "mountain.2.fill"
    case "WF": return "flame.fill"
    case "DR": return "sun.max.fill"
    default: return "exclamationmark.triangle.fill"
    }
}

private func friendlyEventType(_ type: String) -> String {
    switch type.uppercased() {
    case "EQ": return RDLocalized.string("RECENT_DISASTERS_TYPE_EQ")
    case "FL": return RDLocalized.string("RECENT_DISASTERS_TYPE_FL")
    case "TC": return RDLocalized.string("RECENT_DISASTERS_TYPE_TC")
    case "VO": return RDLocalized.string("RECENT_DISASTERS_TYPE_VO")
    case "WF": return RDLocalized.string("RECENT_DISASTERS_TYPE_WF")
    case "DR": return RDLocalized.string("RECENT_DISASTERS_TYPE_DR")
    default: return type
    }
}

private func formatDistanceKm(_ km: Double) -> String {
    if km < 1.0 {
        return "\(Int((km * 1000).rounded())) m"
    } else if km < 100.0 {
        return String(format: "%.1f km", km)
    } else {
        return "\(Int(km.rounded())) km"
    }
}

private func relativeTime(_ millis: Int64) -> String {
    guard millis > 0 else { return "" }
    let diff = max(0, Int64(Date().timeIntervalSince1970 * 1000) - millis)
    switch diff {
    case ..<60_000:
        return RDLocalized.string("RECENT_DISASTERS_TIME_JUST_NOW")
    case ..<3_600_000:
        return RDLocalized.format("RECENT_DISASTERS_TIME_MINUTES", Int(diff / 60_000))
    case ..<86_400_000:
        return RDLocalized.format("RECENT_DISASTERS_TIME_HOURS", Int(diff / 3_600_000))
    case ..<604_800_000:
        return RDLocalized.format("RECENT_DISASTERS_TIME_DAYS", Int(diff / 86_400_000))
    default:
        return formatAbsoluteMillis(millis)
    }
}

private func formatAbsoluteMillis(_ millis: Int64) -> String {
    let formatter = DateFormatter()
    formatter.locale = RDLocalized.locale
    formatter.dateFormat = "MMM d, yyyy HH:mm"
    return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
}

private func formatIsoDate(_ dateStr: String) -> String {
    guard !dateStr.isEmpty else { return "" }
    let input = DateFormatter()
    input.locale = Locale(identifier: "en_US_POSIX")
    input.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
    guard let date = input.date(from: dateStr) else {
        return dateStr.replacingOccurrences(of: "T", with: " ")
    }
    let output = DateFormatter()
    output.locale = RDLocalized.locale
    output.dateFormat = "MMM d, yyyy HH:mm"
    return output.string(from: date)
}

// ─── Main Screen ─────────────────────────────────────────────────────────────

struct RecentDisastersView: View {
    @StateObject private var viewModel = RecentDisastersViewModel()
    @StateObject private var locationProvider = RecentDisastersLocationProvider()

    var body: some View {
        ZStack {
            AppScreenBackground()

            VStack(spacing: 0) {
                if viewModel.hasRegionalTab {
                    SegmentedTabSelector(
                        selectedTab: viewModel.selectedTab,
                        regionalLabel: viewModel.regionalTabLabel,
                        globalLabel: RDLocalized.string("RECENT_DISASTERS_TAB_GLOBAL"),
                        onTabSelected: { viewModel.changeTab($0) }
                    )
                }

                searchBar

                filtersRow

                if viewModel.isStale {
                    OfflineBanner()
                }

                content
            }
        }
        .navigationTitle("RECENT_DISASTERS_TITLE")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    locationProvider.refresh()
                    Task { await viewModel.loadData(forceRefresh: true) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel(Text("RECENT_DISASTERS_REFRESH_CD"))
            }
        }
        .task {
            await viewModel.start(initialCoordinate: locationProvider.bestLastKnownCoordinate)
        }
        .onReceive(locationProvider.$currentLocation.compactMap { $0 }) { location in
            viewModel.updateLocation(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude
            )
        }
        .sheet(item: $viewModel.detailEvent) { event in
            DisasterDetailSheet(event: event, userLocation: locationProvider.currentLocation)
                .presentationDragIndicator(.visible)
        }
    }

    // ─── Search bar ────────────────────────────────────────────────────────────

    private var searchBar: some View {
        HStack {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Color.appTextSecondary)
            TextField(
                "",
                text: Binding(
                    get: { viewModel.searchQuery },
                    set: { viewModel.updateSearchQuery($0) }
                ),
                prompt: Text("RECENT_DISASTERS_SEARCH_HINT").foregroundColor(Color.appTextSecondary)
            )
            .autocorrectionDisabled()
            if !viewModel.searchQuery.isEmpty {
                Button {
                    viewModel.updateSearchQuery("")
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Color.appTextSecondary)
                }
                .accessibilityLabel(Text("RECENT_DISASTERS_CLEAR_CD"))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            Capsule().fill(Color.appSurfaceMuted)
        )
        .overlay(Capsule().stroke(Color.appBorder, lineWidth: 1))
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 6)
    }

    // ─── Filter chips ────────────────────────────────────────────────────────

    private var filtersRow: some View {
        Group {
            if viewModel.selectedTab == "Global" {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        highSeverityChip
                        Divider().frame(height: 22)
                        ForEach(Self.categories, id: \.0) { code, key in
                            DisasterChip(
                                titleKey: key,
                                isSelected: viewModel.selectedCategory == code
                            ) {
                                viewModel.selectCategory(code)
                            }
                        }
                    }
                    .padding(.horizontal, AppTheme.screenPadding)
                    .padding(.vertical, 4)
                }
            } else {
                HStack {
                    highSeverityChip
                    Spacer()
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.vertical, 4)
            }
        }
    }

    private var highSeverityChip: some View {
        let isRedOnly = viewModel.selectedAlertLevel == "Red"
        return Button {
            viewModel.selectAlertLevel(isRedOnly ? "All" : "Red")
        } label: {
            HStack(spacing: 6) {
                Circle().fill(severityRed).frame(width: 8, height: 8)
                Text("RECENT_DISASTERS_FILTER_HIGH_SEVERITY")
                    .font(.caption)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                Capsule().fill(isRedOnly ? severityRed.opacity(0.14) : Color.appSurfaceMuted)
            )
            .overlay(Capsule().stroke(isRedOnly ? severityRed.opacity(0.4) : Color.appBorder, lineWidth: 1))
            .foregroundStyle(isRedOnly ? severityRed : Color.primary)
        }
        .buttonStyle(.plain)
    }

    private static let categories: [(String, String)] = [
        ("All", "RECENT_DISASTERS_FILTER_ALL"),
        ("EQ", "RECENT_DISASTERS_FILTER_EQ"),
        ("FL", "RECENT_DISASTERS_FILTER_FL"),
        ("TC", "RECENT_DISASTERS_FILTER_TC"),
        ("VO", "RECENT_DISASTERS_FILTER_VO"),
        ("WF", "RECENT_DISASTERS_FILTER_WF"),
        ("DR", "RECENT_DISASTERS_FILTER_DR")
    ]

    // ─── Content ───────────────────────────────────────────────────────────────

    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading {
            DisasterSkeletonList()
        } else if !viewModel.hasAnyEvents, let error = viewModel.error {
            DisasterErrorState(error: error) {
                Task { await viewModel.loadData(forceRefresh: true) }
            }
        } else if !viewModel.hasEvents {
            DisasterEmptyState(isFiltered: viewModel.hasAnyEvents)
        } else {
            eventsList
        }
    }

    private var eventsList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                // ── Nearby section ──
                NearbyHeader()

                if !viewModel.hasLocation {
                    NearbyNoLocationCard {
                        locationProvider.requestLocation()
                    }
                } else if viewModel.nearbyEvents.isEmpty {
                    NearbySafeCard()
                } else {
                    ForEach(viewModel.nearbyEvents) { event in
                        NearbyDisasterCard(event: event, userLocation: locationProvider.currentLocation) {
                            viewModel.showDetail(event)
                        }
                    }
                }

                // ── All events section ──
                HStack {
                    Text("RECENT_DISASTERS_ALL_EVENTS_TITLE")
                        .font(.subheadline.weight(.bold))
                    Spacer()
                    if viewModel.lastUpdatedMillis > 0 {
                        Text(RDLocalized.format("RECENT_DISASTERS_LAST_UPDATED", relativeTime(viewModel.lastUpdatedMillis)))
                            .font(.caption2)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 16)
                .padding(.bottom, 8)

                Divider().padding(.horizontal, AppTheme.screenPadding)

                if viewModel.otherEvents.isEmpty {
                    Text(LocalizedStringKey(viewModel.hasAnyEvents ? "RECENT_DISASTERS_NO_MATCH" : "RECENT_DISASTERS_NO_EVENTS"))
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .padding(.horizontal, AppTheme.screenPadding)
                        .padding(.vertical, 16)
                } else {
                    ForEach(viewModel.otherEvents) { event in
                        DisasterEventRow(event: event, userLocation: locationProvider.currentLocation) {
                            viewModel.showDetail(event)
                        }
                        Divider().padding(.horizontal, AppTheme.screenPadding)
                    }
                }

                SourceFooter(source: viewModel.primarySource)
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .refreshable {
            locationProvider.refresh()
            await viewModel.loadData(forceRefresh: true)
        }
    }
}

// ─── Segmented Tab Selector ───────────────────────────────────────────────────

private struct SegmentedTabSelector: View {
    let selectedTab: String
    let regionalLabel: String
    let globalLabel: String
    let onTabSelected: (String) -> Void

    var body: some View {
        HStack(spacing: 4) {
            tab(name: "Local", label: regionalLabel)
            tab(name: "Global", label: globalLabel)
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 8)
    }

    private func tab(name: String, label: String) -> some View {
        let isSelected = selectedTab == name
        return Button {
            onTabSelected(name)
        } label: {
            Text(label)
                .font(.callout.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(isSelected ? Color.appPrimary : Color.clear)
                )
                .foregroundStyle(isSelected ? Color.white : Color.appTextSecondary)
        }
        .buttonStyle(.plain)
    }
}

private struct DisasterChip: View {
    let titleKey: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(LocalizedStringKey(titleKey))
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(
                    Capsule().fill(isSelected ? Color.appPrimary.opacity(0.16) : Color.appSurfaceMuted)
                )
                .overlay(
                    Capsule().stroke(isSelected ? Color.appPrimary.opacity(0.5) : Color.appBorder, lineWidth: 1)
                )
                .foregroundStyle(isSelected ? Color.appPrimary : Color.primary)
        }
        .buttonStyle(.plain)
    }
}

// ─── Leading badge (magnitude or type icon) ──────────────────────────────────

private struct EventLeadingBadge: View {
    let event: DisasterEvent
    let tint: Color
    var boxSize: CGFloat = 32
    var iconSize: CGFloat = 18
    var magFont: Font = .caption2.weight(.heavy)

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: boxSize >= 40 ? 12 : 8, style: .continuous)
                .fill(tint.opacity(0.12))
            if event.eventType.uppercased() == "EQ", let mag = event.magnitude, mag > 0 {
                Text("M\(String(format: "%.1f", mag))")
                    .font(magFont)
                    .foregroundStyle(tint)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            } else {
                Image(systemName: eventTypeSymbol(event.eventType))
                    .font(.system(size: iconSize, weight: .semibold))
                    .foregroundStyle(tint)
            }
        }
        .frame(width: boxSize, height: boxSize)
    }
}

// ─── Event list row (other events) ───────────────────────────────────────────

private struct DisasterEventRow: View {
    let event: DisasterEvent
    let userLocation: CLLocation?
    let onTap: () -> Void

    private var distanceText: String? {
        guard let userLocation, event.hasCoordinates else { return nil }
        return formatDistanceKm(DisasterGeo.haversineKm(
            userLocation.coordinate.latitude, userLocation.coordinate.longitude,
            event.latitude, event.longitude
        ))
    }

    var body: some View {
        let tint = alertColor(event.alertLevel)
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("\(event.country.uppercased()) • \(friendlyEventType(event.eventType))")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(Color.appTextSecondary)
                        .lineLimit(1)
                    Spacer()
                    Text(event.eventTimeMillis > 0 ? relativeTime(event.eventTimeMillis) : formatIsoDate(event.fromDate))
                        .font(.caption2)
                        .foregroundStyle(Color.appTextSecondary)
                }

                HStack(spacing: 10) {
                    EventLeadingBadge(event: event, tint: tint)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(event.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        if !event.severityText.isEmpty {
                            Text(event.severityText)
                                .font(.caption)
                                .foregroundStyle(Color.appTextSecondary)
                        }
                    }
                    Spacer()
                    if let distanceText {
                        Text(RDLocalized.format("RECENT_DISASTERS_DISTANCE_AWAY", distanceText))
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(Color.appTextSecondary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(RoundedRectangle(cornerRadius: 8).fill(Color.appSurfaceMuted))
                    }
                }
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// ─── Nearby section ──────────────────────────────────────────────────────────

private struct NearbyHeader: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "location.fill")
                .font(.footnote)
                .foregroundStyle(Color.appPrimary)
            Text("RECENT_DISASTERS_NEARBY_TITLE")
                .font(.subheadline.weight(.bold))
        }
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }
}

private struct NearbySafeCard: View {
    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(severityGreen.opacity(0.12))
                Image(systemName: "checkmark")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(severityGreen)
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text("RECENT_DISASTERS_NEARBY_SAFE_TITLE")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(severityGreen)
                Text("RECENT_DISASTERS_NEARBY_SAFE_DESC")
                    .font(.caption)
                    .foregroundStyle(severityGreen.opacity(0.85))
            }
            Spacer()
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(severityGreen.opacity(0.08)))
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 6)
    }
}

private struct NearbyNoLocationCard: View {
    let onEnableLocation: () -> Void

    var body: some View {
        Button(action: onEnableLocation) {
            HStack(spacing: 12) {
                Image(systemName: "location.fill")
                    .foregroundStyle(Color.appPrimary)
                VStack(alignment: .leading, spacing: 4) {
                    Text("RECENT_DISASTERS_NEARBY_NO_LOCATION")
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                    Text("RECENT_DISASTERS_ENABLE_LOCATION")
                        .font(.callout.weight(.bold))
                        .foregroundStyle(Color.appPrimary)
                }
                Spacer()
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.appSurfaceMuted))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 6)
    }
}

private struct NearbyDisasterCard: View {
    let event: DisasterEvent
    let userLocation: CLLocation?
    let onTap: () -> Void

    private var distanceText: String? {
        guard let userLocation, event.hasCoordinates else { return nil }
        return formatDistanceKm(DisasterGeo.haversineKm(
            userLocation.coordinate.latitude, userLocation.coordinate.longitude,
            event.latitude, event.longitude
        ))
    }

    var body: some View {
        let tint = alertColor(event.alertLevel)
        Button(action: onTap) {
            HStack(spacing: 12) {
                EventLeadingBadge(event: event, tint: tint, boxSize: 40, iconSize: 22, magFont: .subheadline.weight(.heavy))
                VStack(alignment: .leading, spacing: 2) {
                    Text(event.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if !event.severityText.isEmpty {
                        Text(event.severityText)
                            .font(.caption)
                            .foregroundStyle(Color.appTextSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                VStack(spacing: 2) {
                    if let distanceText {
                        Text(distanceText)
                            .font(.callout.weight(.heavy))
                            .foregroundStyle(tint)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(RoundedRectangle(cornerRadius: 8).fill(tint.opacity(0.12)))
                    }
                    Text(event.alertLevel.uppercased())
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(tint.opacity(0.7))
                }
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(tint.opacity(0.06)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 4)
    }
}

// ─── Banners / states ────────────────────────────────────────────────────────

private struct OfflineBanner: View {
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 2) {
                Text("RECENT_DISASTERS_OFFLINE_MODE")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.white)
                Text("RECENT_DISASTERS_OFFLINE_MESSAGE")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.9))
            }
            Spacer()
        }
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 8)
        .background(offlineOrange)
    }
}

private struct DisasterErrorState: View {
    let error: DisasterErrorType
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "icloud.slash")
                .font(.system(size: 56))
                .foregroundStyle(Color.appTextSecondary.opacity(0.6))
            Text(LocalizedStringKey(error == .timeout ? "RECENT_DISASTERS_ERROR_TIMEOUT" : "RECENT_DISASTERS_ERROR_OFFLINE"))
                .font(.body)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
            Button("RECENT_DISASTERS_RETRY", action: onRetry)
                .buttonStyle(AppPrimaryButtonStyle())
                .fixedSize()
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }
}

private struct DisasterEmptyState: View {
    let isFiltered: Bool

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "cloud")
                .font(.system(size: 56))
                .foregroundStyle(Color.appTextSecondary.opacity(0.6))
            Text(LocalizedStringKey(isFiltered ? "RECENT_DISASTERS_NO_MATCH" : "RECENT_DISASTERS_NO_EVENTS"))
                .font(.body)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }
}

private struct SourceFooter: View {
    let source: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(RDLocalized.format("RECENT_DISASTERS_SOURCE", source))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
            Text("RECENT_DISASTERS_DISCLAIMER")
                .font(.caption2)
                .foregroundStyle(Color.appTextSecondary.opacity(0.7))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 12)
    }
}

// ─── Skeleton loading list ────────────────────────────────────────────────────

private struct SkeletonBlock: View {
    var cornerRadius: CGFloat = 8
    @State private var animate = false

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(Color.appSurfaceMuted)
            .overlay(
                GeometryReader { geo in
                    LinearGradient(
                        colors: [.clear, Color.primary.opacity(0.08), .clear],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: geo.size.width * 0.6)
                    .offset(x: animate ? geo.size.width : -geo.size.width * 0.6)
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .onAppear {
                withAnimation(.linear(duration: 1.2).repeatForever(autoreverses: false)) {
                    animate = true
                }
            }
    }
}

private struct DisasterSkeletonList: View {
    var body: some View {
        VStack(spacing: 20) {
            ForEach(0..<7, id: \.self) { _ in
                HStack(spacing: 12) {
                    SkeletonBlock(cornerRadius: 12).frame(width: 40, height: 40)
                    VStack(alignment: .leading, spacing: 8) {
                        SkeletonBlock(cornerRadius: 6).frame(height: 14).frame(maxWidth: .infinity)
                        SkeletonBlock(cornerRadius: 6).frame(width: 140, height: 11)
                    }
                    Spacer()
                    SkeletonBlock(cornerRadius: 8).frame(width: 48, height: 22)
                }
            }
            Spacer()
        }
        .padding(AppTheme.screenPadding)
    }
}

// ─── Detail sheet ────────────────────────────────────────────────────────────

private struct DisasterDetailSheet: View {
    let event: DisasterEvent
    let userLocation: CLLocation?
    @Environment(\.dismiss) private var dismiss
    @State private var showOfflineMap = false

    private var tint: Color { alertColor(event.alertLevel) }

    private var hasCoords: Bool { event.hasCoordinates }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if event.alertLevel.lowercased() == "red" {
                    criticalAlertCard
                }

                HStack {
                    HStack(spacing: 8) {
                        Image(systemName: eventTypeSymbol(event.eventType))
                            .foregroundStyle(tint)
                        Text("\(friendlyEventType(event.eventType)) • \(event.country)")
                            .font(.title3.weight(.bold))
                            .foregroundStyle(tint)
                    }
                    Spacer()
                    Text(event.alertLevel.uppercased())
                        .font(.caption.weight(.heavy))
                        .foregroundStyle(tint)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 8).fill(tint.opacity(0.15)))
                }

                Text(event.title)
                    .font(.title2.weight(.heavy))
                    .foregroundStyle(.primary)

                metadataCard

                Text(event.eventDescription.isEmpty ? event.title : event.eventDescription)
                    .font(.body)
                    .foregroundStyle(.primary.opacity(0.85))

                locationRow

                Button {
                    // The in-app OFFLINE map, not Apple Maps: this screen matters most exactly when
                    // the internet is down, and Apple Maps is useless without it. Android has always
                    // opened its own map here.
                    showOfflineMap = true
                } label: {
                    Label("RECENT_DISASTERS_ACTION_MAP", systemImage: "mappin.and.ellipse")
                }
                .buttonStyle(AppPrimaryButtonStyle())

                Button("COMMON_CLOSE") { dismiss() }
                    .buttonStyle(AppSecondaryButtonStyle())
            }
            .padding(20)
        }
        .analyticsScreen("disaster_detail")
        .background(AppScreenBackground())
        .sheet(isPresented: $showOfflineMap) {
            NavigationStack {
                OfflineMapView(initialPins: [
                    OfflineMapPin(
                        lat: event.latitude,
                        lng: event.longitude,
                        label: event.title,
                        details: event.eventDescription.isEmpty ? nil : event.eventDescription
                    )
                ])
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("COMMON_CLOSE") { showOfflineMap = false }
                    }
                }
            }
        }
    }

    private var criticalAlertCard: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(severityRed)
            VStack(alignment: .leading, spacing: 4) {
                Text("RECENT_DISASTERS_FEMA_RED_ALERT_TITLE")
                    .font(.subheadline.weight(.heavy))
                    .foregroundStyle(severityRed)
                Text(LocalizedStringKey(event.eventType.uppercased() == "EQ"
                     ? "RECENT_DISASTERS_FEMA_RED_ALERT_DESC_LOCAL"
                     : "RECENT_DISASTERS_FEMA_RED_ALERT_DESC_GLOBAL"))
                    .font(.caption)
                    .foregroundStyle(severityRed.opacity(0.85))
            }
            Spacer()
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(severityRed.opacity(0.12)))
    }

    private var metadataCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(RDLocalized.format("RECENT_DISASTERS_IMPACT_DATE", formatIsoDate(event.fromDate), formatIsoDate(event.toDate)))
                .font(.caption)
                .foregroundStyle(Color.appTextSecondary)
            if !event.severityText.isEmpty {
                Text(RDLocalized.format("RECENT_DISASTERS_DETAIL_SEVERITY", event.severityText))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.appSurfaceMuted))
    }

    private var locationRow: some View {
        HStack(alignment: .center, spacing: 16) {
            if let userLocation, hasCoords {
                EventDirectionDial(userLocation: userLocation, event: event, tint: tint)
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(systemName: "mappin.circle.fill")
                        .foregroundStyle(tint)
                    Text(RDLocalized.format("RECENT_DISASTERS_IMPACT_COORDINATES", event.latitude, event.longitude))
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                }
                if let userLocation, hasCoords {
                    let km = DisasterGeo.haversineKm(
                        userLocation.coordinate.latitude, userLocation.coordinate.longitude,
                        event.latitude, event.longitude
                    )
                    Text(RDLocalized.format("RECENT_DISASTERS_DISTANCE_AWAY", formatDistanceKm(km)))
                        .font(.caption.weight(.bold))
                        .foregroundStyle(tint)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 8).fill(tint.opacity(0.1)))
                }
                Text(RDLocalized.format("RECENT_DISASTERS_SOURCE", event.source))
                    .font(.caption2)
                    .foregroundStyle(Color.appTextSecondary)
            }
            Spacer()
        }
    }

    private func openInMaps() {
        let coordinate = CLLocationCoordinate2D(latitude: event.latitude, longitude: event.longitude)
        let placemark = MKPlacemark(coordinate: coordinate)
        let item = MKMapItem(placemark: placemark)
        item.name = event.title
        item.openInMaps(launchOptions: [
            MKLaunchOptionsMapCenterKey: NSValue(mkCoordinate: coordinate)
        ])
    }
}

// ─── Direction dial (offline compass to the event) ───────────────────────────

private struct EventDirectionDial: View {
    let userLocation: CLLocation
    let event: DisasterEvent
    let tint: Color

    var body: some View {
        let bearing = DisasterGeo.bearing(
            userLocation.coordinate.latitude, userLocation.coordinate.longitude,
            event.latitude, event.longitude
        )
        Canvas { context, size in
            let radius = min(size.width, size.height) / 2 * 0.82
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let grid = Color.appTextSecondary.opacity(0.25)

            context.stroke(Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)), with: .color(grid), lineWidth: 2)
            let inner = radius * 0.5
            context.stroke(Path(ellipseIn: CGRect(x: center.x - inner, y: center.y - inner, width: inner * 2, height: inner * 2)), with: .color(grid), lineWidth: 1.5)

            var cross = Path()
            cross.move(to: CGPoint(x: center.x, y: center.y - radius))
            cross.addLine(to: CGPoint(x: center.x, y: center.y + radius))
            cross.move(to: CGPoint(x: center.x - radius, y: center.y))
            cross.addLine(to: CGPoint(x: center.x + radius, y: center.y))
            context.stroke(cross, with: .color(grid), lineWidth: 1.5)

            let rad = bearing * .pi / 180
            let dot = CGPoint(
                x: center.x + radius * 0.78 * sin(rad),
                y: center.y - radius * 0.78 * cos(rad)
            )
            var needle = Path()
            needle.move(to: center)
            needle.addLine(to: dot)
            context.stroke(needle, with: .color(tint.opacity(0.6)), lineWidth: 3)
            context.fill(Path(ellipseIn: CGRect(x: dot.x - 6, y: dot.y - 6, width: 12, height: 12)), with: .color(tint))
            context.fill(Path(ellipseIn: CGRect(x: center.x - 4, y: center.y - 4, width: 8, height: 8)), with: .color(.appPrimary))
        }
        .frame(width: 92, height: 92)
    }
}
