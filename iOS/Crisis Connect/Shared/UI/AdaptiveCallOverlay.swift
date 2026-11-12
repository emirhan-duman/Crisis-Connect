import SwiftUI

/// Keeps call controls reachable in shallow windows while retaining a compact card on large displays.
struct AdaptiveCallOverlay: ViewModifier {
    func body(content: Content) -> some View {
        GeometryReader { geometry in
            let region = AdaptivePresentationRegion.frame(in: geometry)
            ViewThatFits(in: .vertical) {
                card(content)
                ScrollView {
                    card(content)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
            .frame(width: region.width, height: region.height, alignment: .top)
            .position(x: region.midX, y: region.midY)
        }
    }

    private func card(_ content: Content) -> some View {
        content
            .frame(maxWidth: 640)
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
    }
}

/// A single control group stays clear of an active hinge or camera; scrolling feeds remain continuous.
enum AdaptivePresentationRegion {
    static func frame(in geometry: GeometryProxy) -> CGRect {
        let bounds = CGRect(origin: .zero, size: geometry.size)
        if #available(iOS 27.1, *) {
            let regions = geometry.reservedRegions(kind: .division)
                + geometry.reservedRegions(kind: .occlusion)
            return frame(in: bounds, avoiding: regions.map { $0.frame.insetBy(dx: -8, dy: -8) })
        }
        return bounds
    }

    static func frame(in bounds: CGRect, avoiding obstacles: [CGRect]) -> CGRect {
        var candidates = [bounds]
        for obstacle in obstacles {
            candidates = candidates.flatMap { candidate -> [CGRect] in
                let intersection = candidate.intersection(obstacle)
                guard !intersection.isNull, intersection.width > 0, intersection.height > 0 else { return [candidate] }
                return [
                    CGRect(x: candidate.minX, y: candidate.minY, width: intersection.minX - candidate.minX, height: candidate.height),
                    CGRect(x: intersection.maxX, y: candidate.minY, width: candidate.maxX - intersection.maxX, height: candidate.height),
                    CGRect(x: candidate.minX, y: candidate.minY, width: candidate.width, height: intersection.minY - candidate.minY),
                    CGRect(x: candidate.minX, y: intersection.maxY, width: candidate.width, height: candidate.maxY - intersection.maxY)
                ].filter { $0.width > 0 && $0.height > 0 }
            }
        }
        return candidates.max {
            let leftArea = $0.width * $0.height
            let rightArea = $1.width * $1.height
            if leftArea != rightArea { return leftArea < rightArea }
            if $0.minY != $1.minY { return $0.minY < $1.minY }
            return $0.minX < $1.minX
        } ?? bounds
    }
}
