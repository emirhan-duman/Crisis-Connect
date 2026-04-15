//
//  AppTheme.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

enum AppTheme {
    static let cornerSmall: CGFloat = 12
    static let cornerMedium: CGFloat = 16
    static let cornerLarge: CGFloat = 20
    static let screenPadding: CGFloat = 18
}

enum AppReleaseInfo {
    static var marketingVersion: String {
        bundleString(for: "CFBundleShortVersionString") ?? "1.0.0"
    }

    static var buildNumber: String {
        bundleString(for: "CFBundleVersion") ?? "1"
    }

    static var versionBadge: String {
        "v\(marketingVersion)"
    }

    static var versionTitle: String {
        String(
            format: NSLocalizedString("APP_VERSION_FORMAT", comment: "App version title"),
            marketingVersion
        )
    }

    static var versionDetail: String {
        String(
            format: NSLocalizedString("APP_VERSION_BUILD_FORMAT", comment: "App version and build"),
            marketingVersion,
            buildNumber
        )
    }

    private static func bundleString(for key: String) -> String? {
        let rawValue = Bundle.main.object(forInfoDictionaryKey: key) as? String
        let trimmedValue = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedValue?.isEmpty == false ? trimmedValue : nil
    }
}

extension Color {
    static let appBackground = palette(light: 0xF3F6F8, dark: 0x000000)
    static let appBackgroundAccent = palette(light: 0xE7EDF2, dark: 0x050505)
    static let appSurface = palette(light: 0xF9FBFC, dark: 0x0A0A0A)
    static let appSurfaceElevated = palette(light: 0xFFFFFF, dark: 0x121212)
    static let appSurfaceMuted = palette(light: 0xEEF3F7, dark: 0x1A1A1A)
    static let appRowBackground: Color = .appSurface
    static let appBorder = palette(light: 0xD2DAE2, dark: 0x2A2A2A)
    static let appPrimary = palette(light: 0x0E4E78, dark: 0x4E8FC4)
    static let appPrimarySoft = palette(light: 0xDCE8F1, dark: 0x173446)
    static let appSuccess = palette(light: 0x2E7A60, dark: 0x54B08B)
    static let appWarning = palette(light: 0xA26818, dark: 0xD39A4C)
    static let appDanger = palette(light: 0xB44C3B, dark: 0xE07B69)
    static let appSOS = palette(light: 0xD71920, dark: 0xFF4D57)
    static let appTextSecondary = palette(light: 0x62707C, dark: 0xA0A0A0)

    private static func palette(light: UInt32, dark: UInt32, alpha: CGFloat = 1) -> Color {
        Color(
            uiColor: UIColor { traits in
                traits.userInterfaceStyle == .dark
                    ? uiColor(hex: dark, alpha: alpha)
                    : uiColor(hex: light, alpha: alpha)
            }
        )
    }

    private static func uiColor(hex: UInt32, alpha: CGFloat = 1) -> UIColor {
        UIColor(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: alpha
        )
    }
}

struct AppScreenBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    private var gradientColors: [Color] {
        if colorScheme == .dark {
            return [
                Color.black,
                Color.appBackground,
                Color.appSurface,
            ]
        }

        return [
            Color.appSurfaceElevated,
            Color.appBackground,
            Color.appBackgroundAccent,
        ]
    }

    private var topAmbientColor: Color {
        colorScheme == .dark
            ? Color.white.opacity(0.035)
            : Color.appPrimary.opacity(0.08)
    }

    private var bottomAmbientColor: Color {
        colorScheme == .dark
            ? Color.white.opacity(0.02)
            : Color.appPrimarySoft.opacity(0.88)
    }

    private var topOverlayOpacity: Double {
        colorScheme == .dark ? 0.025 : 0.16
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: gradientColors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            Circle()
                .fill(topAmbientColor)
                .frame(width: 320, height: 320)
                .blur(radius: 90)
                .offset(x: -140, y: -280)

            Circle()
                .fill(bottomAmbientColor)
                .frame(width: 260, height: 260)
                .blur(radius: 110)
                .offset(x: 180, y: 330)

            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(topOverlayOpacity),
                            Color.clear,
                        ],
                        startPoint: .top,
                        endPoint: .center
                    )
                )
                .ignoresSafeArea()
        }
        .allowsHitTesting(false)
    }
}

enum AppSurfaceStyle {
    case regular
    case elevated
    case muted
}

struct AppSurfaceModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    var style: AppSurfaceStyle
    var padding: CGFloat

    private var fillColor: Color {
        switch style {
        case .regular:
            return .appSurface
        case .elevated:
            return .appSurfaceElevated
        case .muted:
            return .appSurfaceMuted
        }
    }

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .fill(fillColor)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
            .shadow(
                color: .black.opacity(style == .elevated && colorScheme == .light ? 0.05 : 0),
                radius: 8,
                y: 3
            )
    }
}

struct AppPrimaryButtonStyle: ButtonStyle {
    var fill: Color = .appPrimary

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline.weight(.semibold))
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(fill)
            )
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(configuration.isPressed ? 0.94 : 1)
            .animation(.easeOut(duration: 0.16), value: configuration.isPressed)
    }
}

struct AppReleaseChip: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "sparkles.rectangle.stack.fill")
                .font(.caption.weight(.bold))

            Text(AppReleaseInfo.versionBadge)
                .font(.caption.weight(.semibold))
        }
        .foregroundStyle(Color.appPrimary)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            Capsule()
                .fill(Color.appPrimary.opacity(0.11))
        )
        .overlay(
            Capsule()
                .stroke(Color.appPrimary.opacity(0.2), lineWidth: 1)
        )
    }
}

struct AppSecondaryButtonStyle: ButtonStyle {
    var tint: Color = .appPrimary

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.vertical, 13)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(Color.appSurfaceMuted)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
            .scaleEffect(configuration.isPressed ? 0.99 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(.easeOut(duration: 0.16), value: configuration.isPressed)
    }
}

struct AppIconBadge: View {
    let systemName: String
    var tint: Color = .appPrimary
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.appSurfaceMuted)

            Image(systemName: systemName)
                .font(.system(size: size * 0.42, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(tint)
        }
        .frame(width: size, height: size)
    }
}

struct AppStatusPill: View {
    let title: LocalizedStringKey
    var tint: Color = .appPrimary

    var body: some View {
        Text(title)
            .font(.caption.weight(.medium))
            .foregroundStyle(tint)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(
                Capsule()
                    .fill(tint.opacity(0.12))
            )
            .overlay(
                Capsule()
                    .stroke(tint.opacity(0.18), lineWidth: 1)
            )
    }
}

extension View {
    func appSurface(style: AppSurfaceStyle = .regular, padding: CGFloat = 16) -> some View {
        modifier(AppSurfaceModifier(style: style, padding: padding))
    }
}
