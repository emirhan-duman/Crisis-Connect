//
//  CrisisSentinelCardView.swift
//  Crisis Connect
//
//  SwiftUI renderers for the 12 Crisis Sentinel tool cards — iOS port of Android's
//  CrisisSentinelCards Compose views. Dispatches on the parsed card; unknown/nil renders nothing.
//

import SwiftUI

struct CrisisSentinelCardView: View {
    let cardJson: String
    var onShowOnMap: ([CrisisSentinelMapPoint]) -> Void = { _ in }
    @State private var routeExpanded = false

    var body: some View {
        if let card = parseCrisisSentinelCard(cardJson) {
            switch card {
            case .weather(let c): weatherCard(c)
            case .quake(let c): quakeCard(c)
            case .airQuality(let c): airQualityCard(c)
            case .hazardEvents(let c): hazardEventsCard(c)
            case .flood(let c): floodCard(c)
            case .alerts(let c): alertsCard(c)
            case .quakeImpact(let c): quakeImpactCard(c)
            case .route(let c): routeCard(c)
            case .marine(let c): marineCard(c)
            case .satellite(let c): satelliteCard(c)
            case .facility(let c): facilityCard(c)
            case .damage(let c): damageCard(c)
            }
        }
    }

    // MARK: - Shell + shared pieces

    private func cardShell<Content: View>(
        icon: String,
        title: String?,
        subtitle: String? = nil,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Color.appPrimary)
                VStack(alignment: .leading, spacing: 1) {
                    if let title, !title.isEmpty {
                        Text(title).font(.subheadline.weight(.semibold))
                    }
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle).font(.caption2).foregroundStyle(Color.appTextSecondary)
                    }
                }
                Spacer(minLength: 0)
            }
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.appSurfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.appBorder, lineWidth: 1)
        )
    }

    private func metricChips(_ chips: [(String, String)]) -> some View {
        SentinelChipFlowLayout(spacing: 8, lineSpacing: 6) {
            ForEach(Array(chips.enumerated()), id: \.offset) { _, chip in
                HStack(spacing: 5) {
                    Image(systemName: chip.0)
                        .font(.system(size: 11))
                        .foregroundStyle(Color.appTextSecondary)
                    Text(chip.1).font(.caption).foregroundStyle(.primary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(Capsule(style: .continuous).fill(Color.appSurfaceMuted))
            }
        }
    }

    private func listRow(
        primary: String,
        secondary: String? = nil,
        severity: String? = nil,
        mapPoint: CrisisSentinelMapPoint? = nil
    ) -> some View {
        HStack(spacing: 8) {
            if let severity { severityBadge(severity) }
            VStack(alignment: .leading, spacing: 1) {
                Text(primary).font(.callout.weight(.medium))
                if let secondary, !secondary.isEmpty {
                    Text(secondary).font(.caption2).foregroundStyle(Color.appTextSecondary)
                }
            }
            Spacer(minLength: 0)
            if let mapPoint {
                Button {
                    onShowOnMap([mapPoint])
                } label: {
                    Image(systemName: "mappin.circle.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(Color.appPrimary)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func severityBadge(_ severity: String) -> some View {
        let color: Color
        switch severity.lowercased() {
        case "extreme", "severe", "critical", "red", "high": color = .appDanger
        case "moderate", "orange", "medium", "warning": color = .appWarning
        default: color = .appPrimary
        }
        return Text(severity)
            .font(.caption2)
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(color.opacity(0.16)))
    }

    private func showAllOnMapButton(_ points: [CrisisSentinelMapPoint]) -> some View {
        Button {
            onShowOnMap(points)
        } label: {
            Label("CRISIS_SENTINEL_CARD_SHOW_ALL_ON_MAP", systemImage: "map")
                .font(.caption.weight(.medium))
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.appPrimary)
    }

    // MARK: - The 12 cards

    private func weatherCard(_ c: CrisisSentinelCard.Weather) -> some View {
        cardShell(icon: "thermometer.medium", title: c.label) {
            if let temp = fmt(c.tempC) {
                Text("\(temp)°C").font(.title.weight(.bold))
            }
            metricChips([
                fmt(c.feelsLikeC).map { ("thermometer.medium", "≈ \($0)°C") },
                fmt(c.humidity).map { ("drop.fill", "\($0)%") },
                fmt(c.windKmh).map { ("wind", "\($0) km/h") },
                fmt(c.gustKmh).map { ("wind", "max \($0) km/h") },
                fmt(c.rainMm ?? c.precipMm).map { ("drop.fill", "\($0) mm") },
            ].compactMap { $0 })
        }
    }

    private func quakeCard(_ c: CrisisSentinelCard.Quake) -> some View {
        cardShell(icon: "globe", title: c.label ?? c.region) {
            ForEach(Array(c.events.enumerated()), id: \.offset) { _, e in
                let mag = fmt(e.magnitude).map { "M\($0)" }
                let depth = fmt(e.depth).map { "\($0) km" }
                listRow(
                    primary: [mag, e.place].compactMap { $0 }.joined(separator: " · ").nilIfEmpty ?? (e.occurredAt ?? ""),
                    secondary: [depth, e.occurredAt].compactMap { $0 }.joined(separator: " · ").nilIfEmpty,
                    mapPoint: mapPoint(e.lat, e.lon, [mag, e.place].compactMap { $0 }.joined(separator: " "))
                )
            }
        }
    }

    private func airQualityCard(_ c: CrisisSentinelCard.AirQuality) -> some View {
        cardShell(icon: "wind", title: c.label, subtitle: c.level) {
            if let aqi = fmt(c.usAqi) {
                Text("AQI \(aqi)").font(.title.weight(.bold))
            }
            metricChips([
                fmt(c.pm25).map { ("wind", "PM2.5 \($0)") },
                fmt(c.pm10).map { ("wind", "PM10 \($0)") },
                fmt(c.ozone).map { ("wind", "O₃ \($0)") },
                fmt(c.no2).map { ("wind", "NO₂ \($0)") },
                fmt(c.so2).map { ("wind", "SO₂ \($0)") },
                fmt(c.co).map { ("wind", "CO \($0)") },
            ].compactMap { $0 })
        }
    }

    private func hazardEventsCard(_ c: CrisisSentinelCard.HazardEvents) -> some View {
        cardShell(icon: "exclamationmark.triangle.fill", title: c.label ?? c.category, subtitle: c.place) {
            ForEach(Array(c.events.enumerated()), id: \.offset) { _, e in
                listRow(
                    primary: e.title ?? (e.category ?? ""),
                    secondary: e.date,
                    mapPoint: mapPoint(e.lat, e.lon, e.title ?? "")
                )
            }
        }
    }

    private func floodCard(_ c: CrisisSentinelCard.Flood) -> some View {
        let unit = c.unit ?? ""
        return cardShell(icon: "water.waves", title: c.label) {
            metricChips([
                fmt(c.todayDischarge).map { ("water.waves", "\($0) \(unit)".trimmed) },
                fmt(c.peakDischarge).map { peak in
                    ("water.waves", ["max \(peak) \(unit)".trimmed, c.peakDate].compactMap { $0 }.joined(separator: " · "))
                },
                c.rising.map { $0 ? ("arrow.up.right", "↑") : ("arrow.down.right", "↓") },
            ].compactMap { $0 })
        }
    }

    private func alertsCard(_ c: CrisisSentinelCard.Alerts) -> some View {
        cardShell(icon: "bell.badge.fill", title: c.title, subtitle: c.source) {
            ForEach(Array(c.items.enumerated()), id: \.offset) { _, i in
                listRow(
                    primary: i.label ?? (i.detail ?? ""),
                    secondary: [i.label != nil ? i.detail : nil, i.whenText].compactMap { $0 }.joined(separator: " · ").nilIfEmpty,
                    severity: i.severity
                )
            }
        }
    }

    private func quakeImpactCard(_ c: CrisisSentinelCard.QuakeImpact) -> some View {
        cardShell(icon: "globe", title: nil) {
            ForEach(Array(c.events.enumerated()), id: \.offset) { _, e in
                listRow(
                    primary: [fmt(e.mag).map { "M\($0)" }, e.place].compactMap { $0 }.joined(separator: " · "),
                    secondary: [
                        e.alert.map { "alert: \($0)" },
                        fmt(e.mmi).map { "MMI \($0)" },
                        e.felt.map { "felt: \($0)" },
                        e.tsunami == true ? "tsunami" : nil,
                        e.whenText,
                    ].compactMap { $0 }.joined(separator: " · ").nilIfEmpty
                )
            }
        }
    }

    private func routeCard(_ c: CrisisSentinelCard.Route) -> some View {
        let title = [c.from, c.to].compactMap { $0 }.joined(separator: " → ").nilIfEmpty ?? (c.mode ?? "")
        let subtitle = [
            fmt(c.distanceKm).map { "\($0) km" },
            fmt(c.durationMin).map { "\($0) dk" },
            c.mode,
        ].compactMap { $0 }.joined(separator: " · ").nilIfEmpty
        let visible = routeExpanded ? c.steps : Array(c.steps.prefix(4))
        let hidden = c.steps.count - visible.count
        return cardShell(icon: "arrow.triangle.turn.up.right.diamond.fill", title: title, subtitle: subtitle) {
            ForEach(Array(visible.enumerated()), id: \.offset) { index, step in
                listRow(primary: "\(index + 1). \(step.text ?? "")", secondary: fmt(step.distanceKm).map { "\($0) km" })
            }
            if hidden > 0 {
                Button {
                    routeExpanded = true
                } label: {
                    Text(String(format: NSLocalizedString("CRISIS_SENTINEL_CARD_ROUTE_STEPS_MORE", comment: ""), hidden))
                        .font(.caption.weight(.medium))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.appPrimary)
            }
        }
    }

    private func marineCard(_ c: CrisisSentinelCard.Marine) -> some View {
        cardShell(icon: "water.waves", title: c.label, subtitle: c.severity) {
            metricChips([
                fmt(c.waveHeight).map { ("water.waves", "\($0) m") },
                fmt(c.wavePeriod).map { ("water.waves", "\($0) s") },
                fmt(c.waveDirection).map { ("wind", "\($0)°") },
                fmt(c.windWaveHeight).map { ("wind", "\($0) m") },
                fmt(c.swellWaveHeight).map { ("water.waves", "swell \($0) m") },
                fmt(c.seaSurfaceTemp).map { ("thermometer.medium", "\($0)°C") },
            ].compactMap { $0 })
        }
    }

    private func satelliteCard(_ c: CrisisSentinelCard.Satellite) -> some View {
        cardShell(
            icon: "antenna.radiowaves.left.and.right",
            title: c.label,
            subtitle: [c.date, c.imageKind, fmt(c.widthKm).map { "\($0) km" }].compactMap { $0 }.joined(separator: " · ").nilIfEmpty
        ) {
            if let urlString = c.url, let url = URL(string: urlString) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFit()
                } placeholder: {
                    ProgressView().frame(maxWidth: .infinity).frame(height: 120)
                }
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                Link(destination: url) {
                    Label("CRISIS_SENTINEL_CARD_OPEN_IMAGE", systemImage: "arrow.up.right.square")
                        .font(.caption.weight(.medium))
                }
                .foregroundStyle(Color.appPrimary)
            }
        }
    }

    private func facilityCard(_ c: CrisisSentinelCard.Facility) -> some View {
        let allPoints = c.facilities.compactMap { mapPoint($0.lat, $0.lon, $0.name ?? "") }
        return cardShell(icon: "cross.case.fill", title: c.label ?? c.facilityType) {
            ForEach(Array(c.facilities.enumerated()), id: \.offset) { _, f in
                listRow(
                    primary: f.name ?? "",
                    secondary: fmt(f.distanceKm).map { "\($0) km" },
                    mapPoint: mapPoint(f.lat, f.lon, f.name ?? "")
                )
            }
            if allPoints.count > 1 {
                showAllOnMapButton(allPoints)
            }
        }
    }

    private func damageCard(_ c: CrisisSentinelCard.Damage) -> some View {
        cardShell(icon: "exclamationmark.octagon.fill", title: c.rating) {
            if let summary = c.summary, !summary.isEmpty {
                Text(summary).font(.callout)
            }
            ForEach(Array(c.hazards.enumerated()), id: \.offset) { _, hazard in
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 11))
                        .foregroundStyle(Color.appWarning)
                    Text(hazard).font(.caption)
                }
            }
        }
    }

    // MARK: - helpers

    private func mapPoint(_ lat: Double?, _ lon: Double?, _ label: String) -> CrisisSentinelMapPoint? {
        guard let lat, let lon else { return nil }
        return CrisisSentinelMapPoint(lat: lat, lng: lon, label: label)
    }
}

/// value % 1 == 0 → no decimals, else one — matches Android's `fmt`.
func fmt(_ value: Double?) -> String? {
    guard let value else { return nil }
    return value.truncatingRemainder(dividingBy: 1) == 0
        ? String(format: "%.0f", value)
        : String(format: "%.1f", value)
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
    var trimmed: String { trimmingCharacters(in: .whitespaces) }
}

/// Minimal wrapping HStack for the metric chips (SwiftUI has no built-in FlowLayout pre-iOS 16
/// Layout; this uses the Layout protocol available on the iOS 17 min target).
struct SentinelChipFlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                x = 0
                y += lineHeight + lineSpacing
                lineHeight = 0
            }
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: maxWidth == .infinity ? x : maxWidth, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += lineHeight + lineSpacing
                lineHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}
