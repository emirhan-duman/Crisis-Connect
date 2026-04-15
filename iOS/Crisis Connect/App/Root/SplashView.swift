//
//  SplashView.swift
//  Crisis Connect
//
//  Created by Assistant on 09.12.2025
//

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct SplashView: View {
    @StateObject private var viewModel = SplashViewModel()

    var body: some View {
        ZStack {
            Color.appBackground
                .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer(minLength: 0)

                VStack(spacing: 24) {
                    logoMark

                    VStack(spacing: 8) {
                        Text("Crisis Connect")
                            .font(.system(size: 28, weight: .semibold))
                            .tracking(-0.6)
                            .foregroundStyle(.primary)

                        Text("SPLASH_SUBTITLE")
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .opacity(viewModel.detailsOpacity)
                    .offset(y: viewModel.detailsOffset)
                }

                Spacer(minLength: 0)

                VStack(spacing: 12) {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.appPrimary)

                    Text("SPLASH_LOADING_STATUS")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }
                .opacity(viewModel.detailsOpacity)
                .offset(y: viewModel.detailsOffset)
                .padding(.bottom, 36)
            }
            .padding(.horizontal, 32)
            .padding(.top, 32)
        }
        .onAppear {
            viewModel.animate()
        }
    }

    private var logoMark: some View {
        logoImage
            .resizable()
            .scaledToFit()
            .frame(width: 104, height: 104)
    }

    private var logoImage: Image {
        let candidates = ["AppLogo", "DCS Logo", "DCS_Logo", "DCSLogo", "dcslogo", "logo", "Logo"]
        #if canImport(UIKit)
        for name in candidates {
            if UIImage(named: name) != nil {
                return Image(name)
            }
        }
        #else
        for name in candidates {
            if NSImage(named: name) != nil {
                return Image(name)
            }
        }
        #endif
        #if DEBUG
        print("⚠️ SplashView: Logo asset not found. Tried: \(candidates)")
        #endif
        return Image(systemName: "shield.lefthalf.filled")
    }
}

#Preview {
    SplashView()
}
