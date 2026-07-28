//
//  OfflineMapView.swift
//  Crisis Connect
//
//  Created by Codex on 27.12.2025
//

import SwiftUI
import MapKit

/// MKMapView annotation for an externally-dropped Sentinel pin (kept distinct so we can add/remove
/// only these without touching the user-location annotation).
final class SentinelPinAnnotation: MKPointAnnotation {}

struct OfflineMapView: View {
    @StateObject private var viewModel = OfflineMapViewModel()
    @State private var tileProvider = OfflineTileProvider(tileStore: OfflineTileStore())
    /// Optional markers to drop and fit on appear (Crisis Sentinel "show on map").
    var initialPins: [OfflineMapPin] = []

    var body: some View {
        ZStack {
            OfflineMapRepresentable(viewModel: viewModel, tileProvider: tileProvider)
                .ignoresSafeArea()

            VStack {
                HStack {
                    Spacer()
                    MapControlStack(
                        onDownload: { viewModel.openEditor() },
                        onList: { viewModel.openList() },
                        onLocation: { viewModel.requestUserLocation() }
                    )
                }

                Spacer()

                if let region = viewModel.activeRegion {
                    ActiveDownloadCard(
                        region: region,
                        onPause: { viewModel.pauseRegion(region) },
                        onResume: { viewModel.resumeRegion(region) }
                    )
                }
            }
            .padding()
        }
        .navigationTitle("OFFLINE_MAP_TITLE")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            viewModel.activate()
            if !initialPins.isEmpty {
                viewModel.showExternalPins(initialPins)
            }
        }
        .overlay(alignment: .top) {
            if let toast = viewModel.toast {
                OfflineMapToastView(toast: toast)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .padding(.top, 12)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.toast?.id)
        .sheet(item: $viewModel.activeSheet) { sheet in
            switch sheet {
            case .editor:
                OfflineRegionEditorSheet(viewModel: viewModel)
            case .list:
                OfflineRegionListSheet(viewModel: viewModel)
            }
        }
        .alert(item: $viewModel.alertState) { state in
            if state.isWarning {
                return Alert(
                    title: Text(state.title),
                    message: Text(state.message),
                    primaryButton: .default(Text("OFFLINE_MAP_CONTINUE")) {
                        viewModel.confirmWarningDownload()
                    },
                    secondaryButton: .cancel {
                        viewModel.cancelWarningDownload()
                    }
                )
            }
            return Alert(
                title: Text(state.title),
                message: Text(state.message),
                dismissButton: .default(Text("OFFLINE_MAP_OK"))
            )
        }
    }
}

private struct OfflineMapToastView: View {
    var toast: OfflineMapToast

    private var tint: Color {
        switch toast.style {
        case .info:
            return .blue
        case .success:
            return .green
        }
    }

    private var systemImage: String {
        switch toast.style {
        case .info:
            return "info.circle.fill"
        case .success:
            return "checkmark.circle.fill"
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .foregroundStyle(tint)
            Text(toast.message)
                .font(.subheadline)
                .foregroundStyle(.primary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.appRowBackground.opacity(0.95))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        )
        .padding(.horizontal, 16)
        .accessibilityLabel(toast.message)
    }
}

private struct MapControlStack: View {
    var onDownload: () -> Void
    var onList: () -> Void
    var onLocation: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            MapControlButton(systemName: "arrow.down.circle", label: NSLocalizedString("Download", comment: ""), action: onDownload)
            MapControlButton(systemName: "list.bullet", label: NSLocalizedString("Regions", comment: ""), action: onList)
            MapControlButton(systemName: "location.fill", label: NSLocalizedString("My Location", comment: ""), action: onLocation)
        }
    }
}

private struct MapControlButton: View {
    var systemName: String
    var label: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 40, height: 40)
                .foregroundStyle(Color.primary)
                .background(Circle().fill(Color.appRowBackground.opacity(0.9)))
                .overlay(Circle().stroke(Color.primary.opacity(0.12), lineWidth: 1))
        }
        .accessibilityLabel(label)
    }
}

private struct ActiveDownloadCard: View {
    var region: OfflineMapRegion
    var onPause: () -> Void
    var onResume: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(region.name)
                    .font(.headline)
                Spacer()
                Text(region.status.label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack {
                Text(String(format: NSLocalizedString("%d / %d tiles", comment: ""), region.downloadedTiles, region.tileCount))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(region.percentComplete)%")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            ProgressView(value: region.progress)

            HStack {
                if region.status == .downloading {
                    Button("COMMON_PAUSE", action: onPause)
                        .buttonStyle(.bordered)
                } else if region.status == .paused {
                    Button("COMMON_RESUME", action: onResume)
                        .buttonStyle(.borderedProminent)
                }
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.appRowBackground.opacity(0.95))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Color.primary.opacity(0.1), lineWidth: 1)
        )
    }
}

private struct OfflineRegionEditorSheet: View {
    @ObservedObject var viewModel: OfflineMapViewModel

    var body: some View {
        NavigationStack {
            Form {
                Section("OFFLINE_MAP_SECTION_REGION") {
                    TextField("OFFLINE_MAP_NAME", text: $viewModel.editorName)
                }

                Section("OFFLINE_MAP_SECTION_ZOOM") {
                    Stepper(value: Binding(
                        get: { viewModel.minZoom },
                        set: { viewModel.updateMinZoom($0) }
                    ), in: OfflineMapConfiguration.downloadMinZoom...OfflineMapConfiguration.downloadMaxZoom) {
                        Text(String(format: NSLocalizedString("OFFLINE_MAP_MIN_ZOOM", comment: ""), viewModel.minZoom))
                    }
                    Stepper(value: Binding(
                        get: { viewModel.maxZoom },
                        set: { viewModel.updateMaxZoom($0) }
                    ), in: OfflineMapConfiguration.downloadMinZoom...OfflineMapConfiguration.downloadMaxZoom) {
                        Text(String(format: NSLocalizedString("OFFLINE_MAP_MAX_ZOOM", comment: ""), viewModel.maxZoom))
                    }
                }

                Section("OFFLINE_MAP_SECTION_ESTIMATE") {
                    HStack {
                        Text("OFFLINE_MAP_TILES")
                        Spacer()
                        Text("\(viewModel.estimatedTileCount)")
                    }
                    HStack {
                        Text("OFFLINE_MAP_SIZE")
                        Spacer()
                        Text(ByteCountFormatter.string(fromByteCount: viewModel.estimatedBytes, countStyle: .file))
                    }
                }

                Section {
                    Button("OFFLINE_MAP_DOWNLOAD_ACTION") {
                        viewModel.startDownload()
                    }
                    .disabled(viewModel.selectionBounds == nil)
                }
            }
            .navigationTitle("OFFLINE_MAP_DOWNLOAD_TITLE")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("COMMON_CLOSE") { viewModel.dismissSheet() }
                }
            }
        }
    }
}

private struct OfflineRegionListSheet: View {
    @ObservedObject var viewModel: OfflineMapViewModel
    @State private var renameRegion: OfflineMapRegion?
    @State private var renameText: String = ""

    var body: some View {
        NavigationStack {
            List {
                if viewModel.regions.isEmpty {
                    Text("OFFLINE_MAP_NO_REGIONS")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(viewModel.regions) { region in
                        OfflineRegionRow(
                            region: region,
                            onPause: { viewModel.pauseRegion(region) },
                            onResume: { viewModel.resumeRegion(region) },
                            onDelete: { viewModel.deleteRegion(region) },
                            onRename: {
                                renameRegion = region
                                renameText = region.name
                            },
                            onFocus: { viewModel.focusOnRegion(region) }
                        )
                    }
                }
            }
            .navigationTitle("OFFLINE_MAP_REGIONS_TITLE")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("COMMON_CLOSE") { viewModel.dismissSheet() }
                }
            }
            .alert("OFFLINE_MAP_RENAME_TITLE", isPresented: Binding(
                get: { renameRegion != nil },
                set: { if !$0 { renameRegion = nil } }
            ), actions: {
                TextField("OFFLINE_MAP_NAME", text: $renameText)
                Button("COMMON_SAVE") {
                    if let region = renameRegion {
                        viewModel.renameRegion(region, name: renameText)
                    }
                    renameRegion = nil
                }
                Button("COMMON_CANCEL", role: .cancel) {
                    renameRegion = nil
                }
            }, message: {
                Text("OFFLINE_MAP_RENAME_MESSAGE")
            })
        }
    }
}

private struct OfflineRegionRow: View {
    var region: OfflineMapRegion
    var onPause: () -> Void
    var onResume: () -> Void
    var onDelete: () -> Void
    var onRename: () -> Void
    var onFocus: () -> Void
    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(region.name)
                    .font(.headline)
                Spacer()
                Text(region.status.label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if region.status == .complete {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text(String(format: NSLocalizedString("OFFLINE_MAP_DOWNLOADED_TILES", comment: ""), region.tileCount))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                ProgressView(value: region.progress)

                HStack {
                    Text("\(region.downloadedTiles) / \(region.tileCount)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(region.percentComplete)%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let error = region.lastError, region.status == .failed {
                Text(error)
                    .font(.caption2)
                    .foregroundStyle(.red)
            }

            Text(String(format: NSLocalizedString("OFFLINE_MAP_CREATED", comment: ""), Self.dateFormatter.string(from: region.createdAt)))
                .font(.caption2)
                .foregroundStyle(.secondary)

            HStack(spacing: 12) {
                if region.status == .complete {
                    Button("OFFLINE_MAP_VIEW") { onFocus() }
                        .buttonStyle(.bordered)
                }
                if region.status == .downloading {
                    Button("COMMON_PAUSE") { onPause() }
                        .buttonStyle(.bordered)
                }
                if region.status == .paused || region.status == .failed || region.status == .idle {
                    Button("COMMON_RESUME") { onResume() }
                        .buttonStyle(.borderedProminent)
                }
                Button("OFFLINE_MAP_RENAME") { onRename() }
                    .buttonStyle(.bordered)
                Button("OFFLINE_MAP_DELETE", role: .destructive) { onDelete() }
            }
            .font(.caption)
        }
        .padding(.vertical, 8)
    }
}

private struct OfflineMapRepresentable: UIViewRepresentable {
    @ObservedObject var viewModel: OfflineMapViewModel
    let tileProvider: OfflineTileProvider

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView(frame: .zero)
        mapView.delegate = context.coordinator
        mapView.showsCompass = false
        mapView.isRotateEnabled = false
        mapView.isPitchEnabled = false
        mapView.pointOfInterestFilter = .excludingAll
        mapView.showsScale = true
        mapView.showsUserLocation = viewModel.isLocationAuthorized

        let overlay = OfflineTileOverlay(provider: tileProvider, urlTemplate: OfflineMapConfiguration.tileTemplate)
        mapView.addOverlay(overlay, level: .aboveLabels)
        context.coordinator.tileOverlay = overlay
        return mapView
    }

    func updateUIView(_ uiView: MKMapView, context: Context) {
        context.coordinator.updateTileProviderIfNeeded(
            tileProvider,
            regions: viewModel.regions,
            allowsNetworkFallback: viewModel.allowsNetworkFallback
        )

        uiView.showsUserLocation = viewModel.isLocationAuthorized
        if viewModel.selectionBounds == nil {
            let bounds = MapBounds.from(mapRect: uiView.visibleMapRect)
            viewModel.updateVisibleBounds(bounds)
        }

        context.coordinator.updateRegionOverlays(on: uiView, regions: viewModel.regions)
        context.coordinator.updateExternalPins(on: uiView, pins: viewModel.externalPins)

        if context.coordinator.lastFocusToken != viewModel.focusToken {
            context.coordinator.lastFocusToken = viewModel.focusToken
            if let bounds = viewModel.consumeFocusBounds() {
                uiView.setVisibleMapRect(bounds.mapRect, edgePadding: UIEdgeInsets(top: 80, left: 40, bottom: 80, right: 40), animated: true)
            }
        }

        if context.coordinator.lastCenterToken != viewModel.centerOnUserToken {
            context.coordinator.lastCenterToken = viewModel.centerOnUserToken
            if let location = viewModel.lastKnownLocation?.coordinate ?? uiView.userLocation.location?.coordinate {
                let region = MKCoordinateRegion(
                    center: location,
                    latitudinalMeters: viewModel.userZoomMeters,
                    longitudinalMeters: viewModel.userZoomMeters
                )
                uiView.setRegion(region, animated: true)
            } else {
                uiView.setUserTrackingMode(.follow, animated: true)
            }
        }

        if context.coordinator.lastRefreshToken != viewModel.tileRefreshToken,
           let overlay = context.coordinator.tileOverlay {
            context.coordinator.lastRefreshToken = viewModel.tileRefreshToken
            uiView.removeOverlay(overlay)
            uiView.addOverlay(overlay, level: .aboveLabels)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var tileOverlay: OfflineTileOverlay?
        var lastFocusToken: UUID?
        var lastCenterToken: UUID?
        var lastRefreshToken: UUID?
        private var tileProviderRegionIDs: [UUID] = []
        private var lastAllowsNetworkFallback: Bool?
        private var regionOverlaySignature: [String] = []
        private var overlayStatus: [ObjectIdentifier: OfflineRegionStatus] = [:]
        private var externalPinSignature: [String] = []
        private let viewModel: OfflineMapViewModel

        init(viewModel: OfflineMapViewModel) {
            self.viewModel = viewModel
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            let bounds = MapBounds.from(mapRect: mapView.visibleMapRect)
            viewModel.updateVisibleBounds(bounds)
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tileOverlay = overlay as? OfflineTileOverlay {
                return MKTileOverlayRenderer(tileOverlay: tileOverlay)
            }
            if let polygon = overlay as? MKPolygon {
                let renderer = MKPolygonRenderer(polygon: polygon)
                let status = overlayStatus[ObjectIdentifier(polygon)] ?? .idle
                renderer.strokeColor = statusColor(status).withAlphaComponent(0.9)
                renderer.fillColor = statusColor(status).withAlphaComponent(0.18)
                renderer.lineWidth = 2
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func updateTileProviderIfNeeded(
            _ tileProvider: OfflineTileProvider,
            regions: [OfflineMapRegion],
            allowsNetworkFallback: Bool
        ) {
            if lastAllowsNetworkFallback != allowsNetworkFallback {
                tileProvider.allowsNetworkFallback = allowsNetworkFallback
                lastAllowsNetworkFallback = allowsNetworkFallback
            }

            let regionIDs = regions.map(\.id)
            guard regionIDs != tileProviderRegionIDs else { return }
            tileProviderRegionIDs = regionIDs
            tileProvider.updateRegions(regionIDs)
        }

        func updateExternalPins(on mapView: MKMapView, pins: [OfflineMapPin]) {
            let signature = pins.map { "\($0.lat),\($0.lng),\($0.label)" }
            guard signature != externalPinSignature else { return }
            externalPinSignature = signature
            let existing = mapView.annotations.compactMap { $0 as? SentinelPinAnnotation }
            mapView.removeAnnotations(existing)
            let annotations = pins.map { pin -> SentinelPinAnnotation in
                let a = SentinelPinAnnotation()
                a.coordinate = pin.coordinate
                a.title = pin.label.isEmpty ? nil : pin.label
                a.subtitle = pin.details
                return a
            }
            mapView.addAnnotations(annotations)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard annotation is SentinelPinAnnotation else { return nil }
            let id = "sentinel-pin"
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView)
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: id)
            view.annotation = annotation
            view.markerTintColor = UIColor(Color.appPrimary)
            view.glyphImage = UIImage(systemName: "mappin")
            view.canShowCallout = true
            return view
        }

        func updateRegionOverlays(on mapView: MKMapView, regions: [OfflineMapRegion]) {
            let signature = regions.map { "\($0.id.uuidString)-\($0.status.rawValue)-\($0.name)" }
            guard signature != regionOverlaySignature else { return }
            regionOverlaySignature = signature
            overlayStatus.removeAll()
            let existing = mapView.overlays.filter { $0 is MKPolygon }
            mapView.removeOverlays(existing)
            var overlays: [MKPolygon] = []
            overlays.reserveCapacity(regions.count)
            for region in regions {
                let overlay = makeRegionOverlay(region)
                overlayStatus[ObjectIdentifier(overlay)] = region.status
                overlays.append(overlay)
            }
            mapView.addOverlays(overlays)
        }

        private func makeRegionOverlay(_ region: OfflineMapRegion) -> MKPolygon {
            let b = region.bounds.normalized
            var coords = [
                CLLocationCoordinate2D(latitude: b.north, longitude: b.west),
                CLLocationCoordinate2D(latitude: b.north, longitude: b.east),
                CLLocationCoordinate2D(latitude: b.south, longitude: b.east),
                CLLocationCoordinate2D(latitude: b.south, longitude: b.west)
            ]
            let polygon = MKPolygon(coordinates: &coords, count: coords.count)
            polygon.title = region.name
            polygon.subtitle = region.id.uuidString
            return polygon
        }

        private func statusColor(_ status: OfflineRegionStatus) -> UIColor {
            switch status {
            case .downloading:
                return .systemBlue
            case .paused:
                return .systemOrange
            case .complete:
                return .systemGreen
            case .failed:
                return .systemRed
            case .idle:
                return .systemGray
            }
        }
    }
}

private extension MapBounds {
    static func from(mapRect: MKMapRect) -> MapBounds {
        let topLeft = MKMapPoint(x: mapRect.minX, y: mapRect.minY).coordinate
        let bottomRight = MKMapPoint(x: mapRect.maxX, y: mapRect.maxY).coordinate
        return MapBounds(
            north: topLeft.latitude,
            south: bottomRight.latitude,
            east: bottomRight.longitude,
            west: topLeft.longitude
        )
    }

    var mapRect: MKMapRect {
        let topLeft = MKMapPoint(CLLocationCoordinate2D(latitude: north, longitude: west))
        let bottomRight = MKMapPoint(CLLocationCoordinate2D(latitude: south, longitude: east))
        let minX = min(topLeft.x, bottomRight.x)
        let minY = min(topLeft.y, bottomRight.y)
        let maxX = max(topLeft.x, bottomRight.x)
        let maxY = max(topLeft.y, bottomRight.y)
        return MKMapRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }
}

#Preview {
    NavigationStack { OfflineMapView() }
}
