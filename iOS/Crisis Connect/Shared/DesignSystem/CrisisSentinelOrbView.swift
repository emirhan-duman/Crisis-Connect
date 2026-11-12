//
//  CrisisSentinelOrbView.swift
//  Crisis Connect
//
//  Modern animated Orb and vector icon for Crisis Sentinel (Cloud AI)
//  and Crisis Edge (On-Device / Offline AI).
//

import SwiftUI
import WebKit

public enum CrisisSentinelOrbVariant: Equatable {
    case sentinel // Ember preset (Red / Orange / Amber / Cream)
    case edge     // Aurora preset (Emerald / Cyan / Blue / Cream)
}

public enum CrisisSentinelColors {
    // Official React Bits "ember" preset for Crisis Sentinel (matching Web)
    public static let sentinelPalette: [Color] = [
        Color(red: 1.000, green: 0.165, blue: 0.122), // Crimson #FF2A1F
        Color(red: 1.000, green: 0.353, blue: 0.180), // Neon Orange #FF5A2E
        Color(red: 1.000, green: 0.478, blue: 0.102), // Amber #FF7A1A
        Color(red: 1.000, green: 0.886, blue: 0.659), // Warm Core #FFE2A8
        Color(red: 1.000, green: 0.702, blue: 0.478), // Peach #FFB37A
        Color(red: 1.000, green: 0.239, blue: 0.180), // Bright Red #FF3D2E
        Color(red: 0.769, green: 0.165, blue: 0.416)  // Deep Rose #C42A6A
    ]

    // Official React Bits "aurora" preset for Crisis Edge
    public static let edgePalette: [Color] = [
        Color(red: 0.000, green: 0.878, blue: 0.541), // Emerald #00E08A
        Color(red: 0.239, green: 1.000, blue: 0.690), // Bright Mint #3DFFB0
        Color(red: 0.000, green: 0.761, blue: 0.659), // Teal #00C2A8
        Color(red: 0.784, green: 1.000, blue: 0.941), // Light Core #C8FFF0
        Color(red: 0.498, green: 0.890, blue: 1.000), // Electric Cyan #7FE3FF
        Color(red: 0.184, green: 0.420, blue: 1.000), // Vibrant Blue #2F6BFF
        Color(red: 0.416, green: 0.302, blue: 1.000)  // Deep Violet #6A4DFF
    ]
}

/// Full interactive animated Orb running the authentic React component (Strands.jsx by David Haz)
/// bundled directly from developer/crisis-connect-web.
public struct CrisisSentinelOrbView: View {
    @Environment(\.colorScheme) private var colorScheme
    public var variant: CrisisSentinelOrbVariant
    public var size: CGFloat
    public var isListening: Bool
    public var isGenerating: Bool

    public init(
        variant: CrisisSentinelOrbVariant = .sentinel,
        size: CGFloat = 58,
        isListening: Bool = false,
        isGenerating: Bool = false
    ) {
        self.variant = variant
        self.size = size
        self.isListening = isListening
        self.isGenerating = isGenerating
    }

    private var fallbackImageName: String {
        variant == .edge ? "ic_crisis_edge_orb" : "ic_crisis_sentinel_orb"
    }

    public var body: some View {
        ZStack {
            // Instant crisp raster placeholder while WebGL initializes
            Image(fallbackImageName)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)

            // Live interactive React WebGL component
            StrandsNativeWebView(
                variant: variant,
                isListening: isListening,
                isGenerating: isGenerating,
                isDark: colorScheme == .dark
            )
            .frame(width: size, height: size)
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}

private struct StrandsNativeWebView: UIViewRepresentable {
    var variant: CrisisSentinelOrbVariant
    var isListening: Bool
    var isGenerating: Bool
    var isDark: Bool

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false

        if let url = Bundle.main.url(forResource: "strands_app", withExtension: "html") {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let variantStr = variant == .edge ? "edge" : "sentinel"
        let themeStr = isDark ? "dark" : "light"
        let js = "if(window.setOrbVariant){window.setOrbVariant('\(variantStr)');window.setOrbListening(\(isListening));window.setOrbGenerating(\(isGenerating));window.setOrbTheme('\(themeStr)');}"
        webView.evaluateJavaScript(js, completionHandler: nil)
    }
}

/// High-fidelity static icon matching the authentic React Strands Orb 100% pixel-perfect.
/// Used for 16pt–32pt sizes (navigation bars, list rows, badges) with 0 battery consumption.
public struct CrisisSentinelOrbIcon: View {
    public var variant: CrisisSentinelOrbVariant
    public var size: CGFloat

    public init(variant: CrisisSentinelOrbVariant = .sentinel, size: CGFloat = 24) {
        self.variant = variant
        self.size = size
    }

    private var imageName: String {
        variant == .edge ? "ic_crisis_edge_orb" : "ic_crisis_sentinel_orb"
    }

    public var body: some View {
        Image(imageName)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
    }
}
