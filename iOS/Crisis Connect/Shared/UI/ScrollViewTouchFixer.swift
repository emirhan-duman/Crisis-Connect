//
//  ScrollViewTouchFixer.swift
//  Crisis Connect
//

import SwiftUI

/// Finds the parent `UIScrollView` and disables `delaysContentTouches` so that
/// `TextField` and other interactive controls respond on the first tap.
///
/// ## Usage
///
///     ScrollView {
///         content
///             .background(ScrollViewTouchFixer())
///     }
///
/// ## Why this is needed
///
/// `UIScrollView` sets `delaysContentTouches = true` by default. That means
/// every touch-down starts a ~150 ms timer to decide scroll-vs-tap. A quick
/// tap on a `TextField` gets swallowed because the user lifts their finger
/// before the timer fires, so the touch is never forwarded.
///
/// ## Why `DispatchQueue.main.async` in `makeUIView` is unreliable
///
/// SwiftUI may not have inserted the representable view into the scroll view
/// hierarchy by the next run-loop tick, so the superview traversal can
/// silently return `nil`.
///
/// This version uses a lightweight `UIView` subclass that overrides
/// `didMoveToWindow()`. UIKit guarantees that method is called *after* the
/// entire ancestor chain (including the `UIScrollView`) is connected to the
/// window, so the traversal always succeeds.
struct ScrollViewTouchFixer: UIViewRepresentable {

    func makeUIView(context: Context) -> UIView {
        let view = Backing()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    // MARK: - Private

    private final class Backing: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil else { return }

            var current: UIView? = superview
            while let parent = current {
                if let scrollView = parent as? UIScrollView {
                    scrollView.delaysContentTouches = false
                    // Keep canCancelContentTouches true so that a finger
                    // drag starting on a control still initiates a scroll.
                    scrollView.canCancelContentTouches = true
                    return
                }
                current = parent.superview
            }
        }
    }
}
