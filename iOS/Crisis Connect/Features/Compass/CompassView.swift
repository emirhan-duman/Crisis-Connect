//
//  CompassView.swift
//  Crisis Connect
//
//  Created by Assistant on 12.12.2025
//

import SwiftUI

struct CompassView: View {
    @StateObject private var viewModel = CompassViewModel()

    var body: some View {
        ZStack {
            AppScreenBackground()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 20) {
                    if !viewModel.isAvailable {
                        unavailableState
                    } else {
                        compassInstrument
                    }
                }
                .frame(maxWidth: 620)
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 18)
                .padding(.bottom, 32)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Compass")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }

    private var compassInstrument: some View {
        VStack(spacing: 16) {
            compassHeroCard
            metricsGrid
            calibrationCard
        }
    }

    private var compassHeroCard: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Heading")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)

                    Text(verbatim: "\(viewModel.headingDegrees)°")
                        .font(.system(size: 52, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .minimumScaleFactor(0.7)

                    Text(LocalizedStringKey(viewModel.directionKey))
                        .font(.headline.weight(.semibold))
                        .foregroundStyle(.primary.opacity(0.78))
                }

                Spacer(minLength: 0)

                VStack(alignment: .trailing, spacing: 8) {
                    CompassStatusPill(
                        title: "Accuracy",
                        value: accuracyText,
                        tint: accuracyTint
                    )

                    CompassStatusPill(
                        title: "Direction",
                        value: Text(LocalizedStringKey(viewModel.directionKey)),
                        tint: .appPrimary
                    )
                }
            }

            CompassDial(
                angle: viewModel.angle,
                headingDegrees: viewModel.headingDegrees,
                directionKey: viewModel.directionKey,
                headingAccuracy: viewModel.headingAccuracy
            )
            .frame(maxWidth: 380)
            .frame(maxWidth: .infinity)
        }
        .padding(22)
        .background(heroBackground)
        .overlay(heroOutline)
        .shadow(color: .black.opacity(0.08), radius: 18, y: 10)
    }

    private var metricsGrid: some View {
        LazyVGrid(
            columns: [GridItem(.adaptive(minimum: 140), spacing: 12, alignment: .top)],
            spacing: 12
        ) {
            CompassMetricTile(
                title: "Heading",
                value: Text(verbatim: "\(viewModel.headingDegrees)°"),
                systemImage: "location.north.line",
                tint: .appPrimary
            )

            CompassMetricTile(
                title: "Direction",
                value: Text(LocalizedStringKey(viewModel.directionKey)),
                systemImage: "safari",
                tint: .appSuccess
            )

            CompassMetricTile(
                title: "Accuracy",
                value: accuracyText,
                systemImage: "scope",
                tint: accuracyTint
            )
        }
    }

    private var calibrationCard: some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                Circle()
                    .fill(guidanceTint.opacity(0.14))

                Image(systemName: viewModel.shouldShowHoldFlatWarning ? "exclamationmark.triangle.fill" : "arrow.triangle.2.circlepath")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(guidanceTint)
            }
            .frame(width: 42, height: 42)

            VStack(alignment: .leading, spacing: 4) {
                Text(guidanceTitle)
                    .font(.subheadline.weight(.semibold))

                Text(guidanceMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var unavailableState: some View {
        VStack(spacing: 14) {
            ContentUnavailableView("Compass not available on this device.", systemImage: "xmark.octagon")

            Text("Make sure your device supports heading and try moving it in a figure‑8 to calibrate.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
        .appSurface(style: .elevated, padding: 20)
        .padding(.top, 28)
    }

    private var heroBackground: some View {
        RoundedRectangle(cornerRadius: 30, style: .continuous)
            .fill(
                LinearGradient(
                    colors: [
                        Color.appSurfaceElevated.opacity(0.98),
                        Color.appBackgroundAccent.opacity(0.94),
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .overlay(alignment: .topLeading) {
                Circle()
                    .fill(Color.appPrimary.opacity(0.16))
                    .frame(width: 200, height: 200)
                    .blur(radius: 34)
                    .offset(x: -28, y: -70)
            }
            .overlay(alignment: .bottomTrailing) {
                Circle()
                    .fill(Color.appSuccess.opacity(0.14))
                    .frame(width: 180, height: 180)
                    .blur(radius: 38)
                    .offset(x: 34, y: 72)
            }
            .overlay {
                LinearGradient(
                    colors: [Color.white.opacity(0.16), Color.clear],
                    startPoint: .top,
                    endPoint: .center
                )
                .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
            }
            .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
    }

    private var heroOutline: some View {
        RoundedRectangle(cornerRadius: 30, style: .continuous)
            .stroke(Color.white.opacity(0.34), lineWidth: 1)
            .overlay(
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
    }

    private var accuracyTint: Color {
        guard let accuracy = viewModel.headingAccuracy else { return .appWarning }
        if accuracy <= 5 { return .appSuccess }
        if accuracy <= 15 { return .appPrimary }
        return .appWarning
    }

    private var accuracyText: Text {
        guard let accuracy = viewModel.headingAccuracy else {
            return Text("--")
        }

        return Text(verbatim: "±\(Int(accuracy.rounded()))°")
    }

    private var guidanceTint: Color {
        viewModel.shouldShowHoldFlatWarning ? .appWarning : accuracyTint
    }

    private var guidanceTitle: LocalizedStringKey {
        viewModel.shouldShowHoldFlatWarning ? "Hold Flat" : "Accuracy"
    }

    private var guidanceMessage: LocalizedStringKey {
        viewModel.shouldShowHoldFlatWarning
            ? "Hold your phone flat for better compass accuracy."
            : "If the compass seems inaccurate, move your phone in a figure‑8 pattern to calibrate."
    }
}

private struct CompassDial: View {
    let angle: Double
    let headingDegrees: Int
    let directionKey: String
    let headingAccuracy: Double?

    var body: some View {
        GeometryReader { proxy in
            let size = min(proxy.size.width, proxy.size.height)

            ZStack {
                CompassDialBackground(size: size)
                CompassScale(size: size, angle: angle)
                CompassTopIndex(size: size)
                CompassNeedle(size: size)
                CompassCenterHub(
                    size: size,
                    headingDegrees: headingDegrees,
                    directionKey: directionKey,
                    headingAccuracy: headingAccuracy
                )
            }
            .frame(width: size, height: size)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
        .animation(.interactiveSpring(response: 0.42, dampingFraction: 0.82), value: angle)
    }
}

private struct CompassDialBackground: View {
    let size: CGFloat
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            Color.white.opacity(colorScheme == .light ? 0.96 : 0.20),
                            Color.appSurfaceElevated.opacity(colorScheme == .light ? 0.86 : 0.34),
                            Color.black.opacity(colorScheme == .light ? 0.12 : 0.46),
                        ],
                        center: .center,
                        startRadius: size * 0.04,
                        endRadius: size * 0.48
                    )
                )
                .padding(size * 0.03)

            Circle()
                .strokeBorder(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(colorScheme == .light ? 0.92 : 0.42),
                            Color.white.opacity(0.14),
                            Color.black.opacity(0.24),
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: size * 0.05
                )
                .padding(size * 0.03)

            Circle()
                .strokeBorder(Color.white.opacity(0.28), lineWidth: 1)
                .padding(size * 0.11)

            Circle()
                .strokeBorder(Color.black.opacity(0.10), lineWidth: 1)
                .padding(size * 0.20)

            Circle()
                .fill(
                    AngularGradient(
                        colors: [
                            Color.appDanger.opacity(0.10),
                            Color.clear,
                            Color.appPrimary.opacity(0.08),
                            Color.clear,
                            Color.appDanger.opacity(0.10),
                        ],
                        center: .center
                    )
                )
                .padding(size * 0.18)
                .blur(radius: size * 0.03)

            Circle()
                .fill(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(colorScheme == .light ? 0.16 : 0.10),
                            Color.clear,
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .padding(size * 0.08)
        }
        .shadow(
            color: .black.opacity(colorScheme == .light ? 0.15 : 0.34),
            radius: size * 0.09,
            y: size * 0.05
        )
    }
}

private struct CompassScale: View {
    let size: CGFloat
    let angle: Double

    private let cardinals: [(degrees: Double, key: String)] = [
        (0, "CARDINAL_N"),
        (45, "CARDINAL_NE"),
        (90, "CARDINAL_E"),
        (135, "CARDINAL_SE"),
        (180, "CARDINAL_S"),
        (225, "CARDINAL_SW"),
        (270, "CARDINAL_W"),
        (315, "CARDINAL_NW"),
    ]

    var body: some View {
        ZStack {
            ForEach(0..<72, id: \.self) { step in
                let degrees = Double(step) * 5
                let absoluteDegrees = Int(degrees.rounded())
                let isCardinal = absoluteDegrees.isMultiple(of: 90)
                let isMajor = absoluteDegrees.isMultiple(of: 30)
                let isMedium = absoluteDegrees.isMultiple(of: 10)

                Capsule()
                    .fill(tickColor(for: absoluteDegrees))
                    .frame(
                        width: isCardinal ? size * 0.010 : size * 0.0055,
                        height: isCardinal ? size * 0.085 : (isMajor ? size * 0.055 : (isMedium ? size * 0.036 : size * 0.024))
                    )
                    .offset(y: -size * 0.39)
                    .rotationEffect(.degrees(degrees - angle))
            }

            ForEach(Array(stride(from: 0, through: 330, by: 30)), id: \.self) { degree in
                let rotation = Double(degree) - angle

                Text(verbatim: degreeLabel(for: degree))
                    .font(.system(size: size * 0.038, weight: .semibold, design: .rounded))
                    .foregroundStyle(Color.primary.opacity(0.48))
                    .rotationEffect(.degrees(rotation))
                    .offset(y: -size * 0.315)
                    .rotationEffect(.degrees(-rotation))
                    .monospacedDigit()
            }

            ForEach(cardinals, id: \.degrees) { item in
                let rotation = item.degrees - angle
                let isNorth = item.key == "CARDINAL_N"
                let isSouth = item.key == "CARDINAL_S"

                Text(LocalizedStringKey(item.key))
                    .font(.system(size: item.degrees.truncatingRemainder(dividingBy: 90) == 0 ? size * 0.072 : size * 0.045, weight: .bold, design: .rounded))
                    .foregroundStyle(isNorth ? Color.appDanger : (isSouth ? Color.appPrimary.opacity(0.92) : Color.primary.opacity(0.78)))
                    .rotationEffect(.degrees(rotation))
                    .offset(y: -size * 0.225)
                    .rotationEffect(.degrees(-rotation))
            }
        }
    }

    private func tickColor(for degree: Int) -> Color {
        if degree == 0 {
            return .appDanger.opacity(0.9)
        }

        if degree == 180 {
            return .appPrimary.opacity(0.82)
        }

        if degree.isMultiple(of: 90) {
            return .primary.opacity(0.82)
        }

        if degree.isMultiple(of: 30) {
            return .primary.opacity(0.56)
        }

        if degree.isMultiple(of: 10) {
            return .secondary.opacity(0.48)
        }

        return .secondary.opacity(0.26)
    }

    private func degreeLabel(for degree: Int) -> String {
        degree == 0 ? "360" : String(degree)
    }
}

private struct CompassTopIndex: View {
    let size: CGFloat

    var body: some View {
        VStack(spacing: size * 0.014) {
            Capsule()
                .fill(Color.white.opacity(0.9))
                .frame(width: size * 0.044, height: size * 0.012)

            Triangle()
                .fill(
                    LinearGradient(
                        colors: [Color.white, Color.appDanger.opacity(0.86)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: size * 0.05, height: size * 0.04)
                .rotationEffect(.degrees(180))
                .shadow(color: .black.opacity(0.18), radius: size * 0.012, y: size * 0.008)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .padding(.top, size * 0.088)
    }
}

private struct CompassNeedle: View {
    let size: CGFloat

    var body: some View {
        ZStack {
            Capsule()
                .fill(Color.black.opacity(0.10))
                .frame(width: size * 0.034, height: size * 0.43)
                .blur(radius: size * 0.008)
                .offset(y: size * 0.012)

            CompassNeedleBlade(pointsUp: true)
                .fill(
                    LinearGradient(
                        colors: [Color.appDanger, Color.appDanger.opacity(0.70)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: size * 0.10, height: size * 0.22)
                .offset(y: -size * 0.125)

            CompassNeedleBlade(pointsUp: false)
                .fill(
                    LinearGradient(
                        colors: [Color.appPrimary.opacity(0.72), Color.appPrimary],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: size * 0.10, height: size * 0.22)
                .offset(y: size * 0.125)

            Circle()
                .fill(
                    RadialGradient(
                        colors: [Color.white, Color.appSurfaceElevated],
                        center: .center,
                        startRadius: size * 0.004,
                        endRadius: size * 0.038
                    )
                )
                .frame(width: size * 0.11, height: size * 0.11)
                .overlay(
                    Circle()
                        .stroke(Color.black.opacity(0.08), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.18), radius: size * 0.02, y: size * 0.012)

            Circle()
                .fill(Color.black.opacity(0.74))
                .frame(width: size * 0.028, height: size * 0.028)
        }
    }
}

private struct CompassCenterHub: View {
    let size: CGFloat
    let headingDegrees: Int
    let directionKey: String
    let headingAccuracy: Double?

    var body: some View {
        VStack(spacing: size * 0.012) {
            Text(verbatim: "\(headingDegrees)°")
                .font(.system(size: size * 0.11, weight: .bold, design: .rounded))
                .monospacedDigit()
                .minimumScaleFactor(0.7)

            Text(LocalizedStringKey(directionKey))
                .font(.system(size: size * 0.047, weight: .semibold, design: .rounded))
                .foregroundStyle(.primary.opacity(0.82))

            Text(verbatim: headingAccuracyText)
                .font(.system(size: size * 0.031, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
        .frame(width: size * 0.34, height: size * 0.34)
        .background(.ultraThinMaterial, in: Circle())
        .overlay(
            Circle()
                .stroke(Color.white.opacity(0.32), lineWidth: 1)
        )
        .overlay(
            Circle()
                .stroke(Color.black.opacity(0.05), lineWidth: 1)
                .padding(size * 0.012)
        )
        .shadow(color: .black.opacity(0.16), radius: size * 0.032, y: size * 0.02)
    }

    private var headingAccuracyText: String {
        guard let headingAccuracy else { return "--" }
        return "±\(Int(headingAccuracy.rounded()))°"
    }
}

private struct CompassStatusPill: View {
    let title: LocalizedStringKey
    let value: Text
    let tint: Color

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            value
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(tint)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            Capsule()
                .fill(tint.opacity(0.10))
        )
        .overlay(
            Capsule()
                .stroke(tint.opacity(0.14), lineWidth: 1)
        )
    }
}

private struct CompassMetricTile: View {
    let title: LocalizedStringKey
    let value: Text
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(tint.opacity(0.12))

                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(tint)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                value
                    .font(.headline.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            Spacer(minLength: 0)
        }
        .appSurface(style: .elevated, padding: 14)
    }
}

private struct CompassNeedleBlade: Shape {
    let pointsUp: Bool

    func path(in rect: CGRect) -> Path {
        var path = Path()

        if pointsUp {
            path.move(to: CGPoint(x: rect.midX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.height * 0.56))
            path.addQuadCurve(
                to: CGPoint(x: rect.midX, y: rect.maxY),
                control: CGPoint(x: rect.maxX * 0.80, y: rect.maxY)
            )
            path.addQuadCurve(
                to: CGPoint(x: rect.minX, y: rect.height * 0.56),
                control: CGPoint(x: rect.minX + rect.width * 0.20, y: rect.maxY)
            )
        } else {
            path.move(to: CGPoint(x: rect.midX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.height * 0.44))
            path.addQuadCurve(
                to: CGPoint(x: rect.midX, y: rect.minY),
                control: CGPoint(x: rect.maxX * 0.80, y: rect.minY)
            )
            path.addQuadCurve(
                to: CGPoint(x: rect.minX, y: rect.height * 0.44),
                control: CGPoint(x: rect.minX + rect.width * 0.20, y: rect.minY)
            )
        }

        path.closeSubpath()
        return path
    }
}

private struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

#Preview {
    NavigationStack { CompassView() }
}
