import SwiftUI
import CoreLocation

struct BreadcrumbTrailView: View {
    @StateObject private var manager = BreadcrumbTrailManager.shared
    @State private var showReturnOptions = false
    @State private var showClearConfirmation = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if manager.session == nil {
                    emptyState
                } else if manager.session?.mode == .returning || manager.session?.mode == .arrived {
                    returnGuidanceCard
                } else {
                    recordingStatusCard
                }

                if let errorKey = manager.errorKey {
                    errorCard(errorKey)
                }

                if let session = manager.session, !session.points.isEmpty {
                    metricsRow
                    trailPreview(session)

                    if session.mode != .returning && session.mode != .arrived {
                        actionRow
                    }

                    mapCard
                }

                safetyCard
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 16)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
        .navigationTitle(Text("BREADCRUMB_TITLE", tableName: "Breadcrumb"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if manager.session != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) { showClearConfirmation = true } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel(Text("BREADCRUMB_CLEAR_CONFIRM", tableName: "Breadcrumb"))
                }
            }
        }
        .confirmationDialog(
            Text("BREADCRUMB_RETURN_CHOOSE_TITLE", tableName: "Breadcrumb"),
            isPresented: $showReturnOptions,
            titleVisibility: .visible
        ) {
            Button { manager.requestReturn(to: .start) } label: {
                Text("BREADCRUMB_RETURN_TO_START", tableName: "Breadcrumb")
            }
            Button { manager.requestReturn(to: .lastSafe) } label: {
                Text("BREADCRUMB_RETURN_TO_SAFE", tableName: "Breadcrumb")
            }
            Button(role: .cancel) {} label: {
                Text("BREADCRUMB_CANCEL", tableName: "Breadcrumb")
            }
        } message: {
            Text("BREADCRUMB_RETURN_CHOOSE_BODY", tableName: "Breadcrumb")
        }
        .alert(
            Text("BREADCRUMB_CLEAR_TITLE", tableName: "Breadcrumb"),
            isPresented: $showClearConfirmation
        ) {
            Button(role: .cancel) {} label: {
                Text("BREADCRUMB_CANCEL", tableName: "Breadcrumb")
            }
            Button(role: .destructive) { manager.clear() } label: {
                Text("BREADCRUMB_CLEAR_CONFIRM", tableName: "Breadcrumb")
            }
        } message: {
            Text("BREADCRUMB_CLEAR_BODY", tableName: "Breadcrumb")
        }
        .onAppear {
            manager.refreshOfflineMapAvailability()
        }
        .accessibilityIdentifier("breadcrumb-trail-screen")
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(Color.appPrimary)
                    .frame(width: 76, height: 76)
                Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                    .font(.system(size: 31, weight: .semibold))
                    .foregroundStyle(.white)
            }

            BText("BREADCRUMB_EMPTY_TITLE")
                .font(.title3.weight(.bold))
                .multilineTextAlignment(.center)

            BText("BREADCRUMB_EMPTY_BODY")
                .font(.body)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)

            Button(action: manager.requestStartNew) {
                Label {
                    BText("BREADCRUMB_START")
                } icon: {
                    Image(systemName: "record.circle")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppPrimaryButtonStyle())
        }
        .frame(maxWidth: .infinity)
        .appSurface(style: .elevated, padding: 22)
    }

    private var recordingStatusCard: some View {
        let recording = manager.session?.mode == .recording
        return HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(recording ? Color.appPrimary : Color.appSurfaceMuted)
                    .frame(width: 52, height: 52)
                Image(systemName: recording ? "location.fill" : "pause.fill")
                    .foregroundStyle(recording ? .white : Color.appTextSecondary)
            }

            VStack(alignment: .leading, spacing: 3) {
                BText(recording ? "BREADCRUMB_STATUS_RECORDING" : "BREADCRUMB_STATUS_PAUSED")
                    .font(.headline)
                Text(
                    String(
                        format: bLocalized("BREADCRUMB_POINT_COUNT"),
                        manager.session?.points.count ?? 0
                    )
                )
                .font(.caption)
                .foregroundStyle(Color.appTextSecondary)
            }

            Spacer(minLength: 4)

            Button(recording ? bLocalized("BREADCRUMB_PAUSE") : bLocalized("BREADCRUMB_RESUME")) {
                if recording { manager.pause() } else { manager.requestResume() }
            }
            .buttonStyle(.bordered)
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var returnGuidanceCard: some View {
        let arrived = manager.session?.mode == .arrived
        let relativeRotation: Double = {
            guard let bearing = manager.targetBearing else { return 0 }
            let heading = manager.headingDegrees ?? 0
            return (bearing - heading + 540).truncatingRemainder(dividingBy: 360) - 180
        }()

        return VStack(spacing: 12) {
            Image(systemName: arrived ? "flag.checkered" : "location.north.fill")
                .font(.system(size: 68, weight: .semibold))
                .foregroundStyle(arrived ? Color.appSuccess : Color.appPrimary)
                .rotationEffect(.degrees(arrived ? 0 : relativeRotation))
                .animation(.easeOut(duration: 0.2), value: relativeRotation)

            BText(arrived ? "BREADCRUMB_ARRIVED" : "BREADCRUMB_FOLLOW_ARROW")
                .font(.title3.weight(.bold))
                .multilineTextAlignment(.center)

            if !arrived {
                Text(formatDistance(manager.nextBreadcrumbDistance))
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                Text(
                    String(
                        format: bLocalized("BREADCRUMB_REMAINING_FORMAT"),
                        formatDistance(manager.remainingRouteDistance)
                    )
                )
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)

                ProgressView(value: manager.returnProgress)
                    .tint(.appPrimary)

                Button(action: manager.pause) {
                    Label {
                        BText("BREADCRUMB_PAUSE")
                    } icon: {
                        Image(systemName: "pause.fill")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity)
        .appSurface(style: .elevated, padding: 22)
    }

    private func errorCard(_ key: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.appDanger)
            BText(key)
                .font(.footnote)
            Spacer(minLength: 0)
            Button { manager.clearError() } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
            }
        }
        .appSurface(style: .muted, padding: 14)
    }

    private var metricsRow: some View {
        HStack(spacing: 10) {
            metricCard(
                titleKey: "BREADCRUMB_DISTANCE_START",
                value: formatDistance(manager.distanceToStart),
                systemImage: "flag.fill"
            )
            metricCard(
                titleKey: "BREADCRUMB_DISTANCE_SAFE",
                value: formatDistance(manager.distanceToSafe),
                systemImage: "checkmark.shield.fill"
            )
        }
    }

    private func metricCard(titleKey: String, value: String, systemImage: String) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Image(systemName: systemImage)
                .foregroundStyle(Color.appPrimary)
            BText(titleKey)
                .font(.caption.weight(.medium))
                .foregroundStyle(Color.appTextSecondary)
            Text(value)
                .font(.title3.weight(.bold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .regular, padding: 14)
    }

    private func trailPreview(_ session: BreadcrumbTrailSession) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            BText("BREADCRUMB_TRAIL_PREVIEW")
                .font(.headline)

            BreadcrumbTrailCanvas(
                points: session.points,
                current: manager.currentPoint,
                safeIndex: session.safePointIndex
            )
            .frame(height: 150)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(Color.appSurfaceMuted)
            )
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var actionRow: some View {
        HStack(spacing: 10) {
            Button(action: manager.markCurrentAsSafe) {
                Label {
                    BText("BREADCRUMB_MARK_SAFE")
                } icon: {
                    Image(systemName: "checkmark.shield.fill")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button { showReturnOptions = true } label: {
                Label {
                    BText("BREADCRUMB_RETURN_ACTION")
                } icon: {
                    Image(systemName: "arrow.uturn.backward")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled((manager.session?.points.count ?? 0) < 2)
        }
    }

    private var mapCard: some View {
        let hasOfflineMap = manager.offlineRegionName != nil
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 11) {
                Image(systemName: hasOfflineMap ? "map.fill" : "map")
                    .foregroundStyle(Color.appPrimary)
                VStack(alignment: .leading, spacing: 3) {
                    BText(hasOfflineMap ? "BREADCRUMB_OFFLINE_MAP_READY" : "BREADCRUMB_OFFLINE_MAP_MISSING")
                        .font(.headline)
                    Text(manager.offlineRegionName ?? bLocalized("BREADCRUMB_OFFLINE_MAP_FALLBACK"))
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }

            NavigationLink {
                OfflineMapView(breadcrumbTrail: manager.routeCoordinates)
            } label: {
                BText(hasOfflineMap ? "BREADCRUMB_OPEN_OFFLINE_MAP" : "BREADCRUMB_OPEN_MAP")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppPrimaryButtonStyle())
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var safetyCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.appWarning)
            BText("BREADCRUMB_SAFETY_NOTE")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .appSurface(style: .muted, padding: 14)
    }

    private func formatDistance(_ meters: CLLocationDistance?) -> String {
        guard let meters, meters.isFinite else { return bLocalized("BREADCRUMB_DISTANCE_WAITING") }
        if meters >= 1_000 {
            return String(format: bLocalized("BREADCRUMB_DISTANCE_KM"), meters / 1_000)
        }
        return String(format: bLocalized("BREADCRUMB_DISTANCE_M"), max(Int(meters.rounded()), 0))
    }
}

private struct BreadcrumbTrailCanvas: View {
    let points: [BreadcrumbPoint]
    let current: BreadcrumbPoint?
    let safeIndex: Int

    var body: some View {
        Canvas { context, size in
            guard let first = points.first else { return }
            let all = current.map { points + [$0] } ?? points
            let minLatitude = all.map(\.latitude).min() ?? first.latitude
            let maxLatitude = all.map(\.latitude).max() ?? first.latitude
            let meanLatitude = all.map(\.latitude).reduce(0, +) / Double(all.count)
            let longitudeScale = max(cos(meanLatitude * .pi / 180), 0.15)
            let scaledLongitudes = all.map { $0.longitude * longitudeScale }
            let minLongitude = scaledLongitudes.min() ?? first.longitude
            let maxLongitude = scaledLongitudes.max() ?? first.longitude
            let latitudeSpan = max(maxLatitude - minLatitude, 0.00001)
            let longitudeSpan = max(maxLongitude - minLongitude, 0.00001)

            func position(_ point: BreadcrumbPoint) -> CGPoint {
                CGPoint(
                    x: (point.longitude * longitudeScale - minLongitude) / longitudeSpan * size.width,
                    y: size.height - (point.latitude - minLatitude) / latitudeSpan * size.height
                )
            }

            var path = Path()
            path.move(to: position(first))
            points.dropFirst().forEach { path.addLine(to: position($0)) }
            context.stroke(
                path,
                with: .color(.appPrimary),
                style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round)
            )

            func dot(_ point: BreadcrumbPoint, color: Color, radius: CGFloat) {
                let center = position(point)
                let rect = CGRect(
                    x: center.x - radius,
                    y: center.y - radius,
                    width: radius * 2,
                    height: radius * 2
                )
                context.fill(Path(ellipseIn: rect), with: .color(color))
            }

            dot(first, color: .appWarning, radius: 6)
            if points.indices.contains(safeIndex) {
                dot(points[safeIndex], color: .appSuccess, radius: 6)
            }
            if let current {
                dot(current, color: .white, radius: 7)
            }
        }
        .padding(12)
    }
}

private struct BText: View {
    let key: String

    init(_ key: String) {
        self.key = key
    }

    var body: some View {
        Text(LocalizedStringKey(key), tableName: "Breadcrumb")
    }
}

private func bLocalized(_ key: String) -> String {
    NSLocalizedString(key, tableName: "Breadcrumb", bundle: .main, value: key, comment: "")
}

#if DEBUG
    #Preview {
        NavigationStack { BreadcrumbTrailView() }
    }
#endif
