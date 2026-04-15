//
//  FirebaseAuthPresenter.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import UIKit
import FirebaseAuth

final class FirebaseAuthPresenter: NSObject, AuthUIDelegate {
    static let shared = FirebaseAuthPresenter()

    private override init() {}

    func present(_ viewControllerToPresent: UIViewController,
                 animated flag: Bool,
                 completion: (() -> Void)?) {
        guard let presenter = topViewController() else {
            completion?()
            return
        }
        if Thread.isMainThread {
            presenter.present(viewControllerToPresent, animated: flag, completion: completion)
        } else {
            DispatchQueue.main.async {
                presenter.present(viewControllerToPresent, animated: flag, completion: completion)
            }
        }
    }

    func dismiss(animated flag: Bool, completion: (() -> Void)?) {
        guard let presenter = topViewController() else {
            completion?()
            return
        }
        if Thread.isMainThread {
            presenter.dismiss(animated: flag, completion: completion)
        } else {
            DispatchQueue.main.async {
                presenter.dismiss(animated: flag, completion: completion)
            }
        }
    }

    func presentingViewController() -> UIViewController? {
        topViewController()
    }

    func canPresentAuthUI() -> Bool {
        topViewController() != nil
    }

    private func topViewController() -> UIViewController? {
        let windowScenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
        let prioritizedWindows = windowScenes.first { $0.activationState == .foregroundActive }?.windows ?? []
        let windows = prioritizedWindows.isEmpty ? windowScenes.flatMap(\.windows) : prioritizedWindows
        let keyWindow = windows.first { $0.isKeyWindow } ?? windows.first
        return keyWindow?.rootViewController?.topmostPresented()
    }
}

private extension UIViewController {
    func topmostPresented() -> UIViewController {
        if let presented = presentedViewController {
            return presented.topmostPresented()
        }
        if let nav = self as? UINavigationController, let top = nav.visibleViewController {
            return top.topmostPresented()
        }
        if let tab = self as? UITabBarController, let selected = tab.selectedViewController {
            return selected.topmostPresented()
        }
        return self
    }
}
