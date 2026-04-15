//
//  SurvivalGuideView.swift
//  Crisis Connect
//
//  Updated by Assistant on 26.02.2026
//

import SwiftUI

struct SurvivalGuideView: View {
    @StateObject private var viewModel = SurvivalGuideViewModel()
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                filtersPanel
                introPanel

                if visibleEntries.isEmpty {
                    emptyStateCard
                } else {
                    ForEach(visibleEntries) { entry in
                        guideArticleCard(entry)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(ScrollViewTouchFixer())
        }
        .background(Color.clear)
        .navigationTitle("Survival Guide")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: visibleEntryIDs) { _, ids in
            if let expandedID = viewModel.expandedGuideID, ids.contains(expandedID) == false {
                withAnimation(.easeInOut(duration: 0.18)) {
                    viewModel.expandedGuideID = nil
                }
            }
        }
    }

    private var visibleEntries: [SurvivalGuideEntry] {
        viewModel.visibleEntries(locale: locale)
    }

    private var visibleEntryIDs: [String] {
        visibleEntries.map(\.article.id)
    }

    private var emergencyNumber: String {
        viewModel.emergencyNumber(locale: locale)
    }

    private var isTurkish: Bool {
        let languageCode = locale.language.languageCode?.identifier.lowercased() ?? locale.identifier.lowercased()
        return languageCode.hasPrefix("tr")
    }

    private var assemblyQuery: String {
        localized("Afet ve Acil Durum Toplanma Alanı", "Disaster assembly area")
    }

    private var cardBackground: Color {
        .appSurface
    }

    private var cardBorder: Color {
        .appBorder
    }

    private var filtersPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)

                TextField(
                    localized("Kod, kategori veya konuya göre ara", "Search by code, category, or topic"),
                    text: $viewModel.searchQuery
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

                if viewModel.searchQuery.isEmpty == false {
                    Button {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            viewModel.searchQuery = ""
                        }
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(localized("Aramayı temizle", "Clear search"))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.appSurfaceMuted)
            )

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    GuideCategoryChip(
                        title: localized("Tüm kategoriler", "All categories"),
                        systemImage: "square.grid.2x2",
                        isSelected: viewModel.selectedCategoryID == SurvivalGuideData.allCategoryID
                    ) {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            viewModel.selectedCategoryID = SurvivalGuideData.allCategoryID
                        }
                    }

                    ForEach(viewModel.categories) { category in
                        GuideCategoryChip(
                            title: category.title.resolve(locale: locale),
                            systemImage: category.symbolName,
                            isSelected: viewModel.selectedCategoryID == category.id
                        ) {
                            withAnimation(.easeInOut(duration: 0.18)) {
                                viewModel.selectedCategoryID = category.id
                            }
                        }
                    }
                }
                .padding(.horizontal, 1)
            }

            HStack(alignment: .center) {
                Text(format(localized("%d rehber listeleniyor", "%d guides listed"), visibleEntries.count))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()

                if viewModel.hasActiveFilters {
                    Button(localized("Filtreleri temizle", "Clear filters")) {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            viewModel.clearFilters()
                        }
                    }
                    .font(.caption.weight(.semibold))
                }
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(cardBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(cardBorder, lineWidth: 1)
        )
    }

    private var introPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(localized("Acil Durum Rehberi", "Emergency Guide"))
                    .font(.title3.weight(.semibold))

                Text(localized("Kritik bilgiye hızla ulaş, panik anında kısa adımları uygula.", "Reach critical information quickly and follow short actions under pressure."))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Text(
                format(
                    localized("Bölge: %@ • Acil numara: %@", "Region: %@ • Emergency number: %@"),
                    viewModel.countryLabel(locale: locale),
                    emergencyNumber
                )
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack(spacing: 8) {
                Button {
                    callEmergency(number: emergencyNumber)
                } label: {
                    Label(
                        format(localized("%@'yi ara", "Call %@"), emergencyNumber),
                        systemImage: "phone.fill"
                    )
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)

                Button {
                    openAssemblyArea()
                } label: {
                    Label(localized("Toplanma alanını aç", "Open assembly area"), systemImage: "mappin.and.ellipse")
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(cardBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(cardBorder, lineWidth: 1)
        )
    }

    private var emptyStateCard: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.appSurfaceMuted)
                    .frame(width: 36, height: 36)
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(localized("Rehber bulunamadı", "No guide found"))
                    .font(.subheadline.weight(.semibold))

                Text(localized("Filtreleri azaltıp daha genel bir anahtar kelime ile tekrar dene.", "Try reducing filters or searching with a broader keyword."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(cardBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(cardBorder, lineWidth: 1)
        )
    }

    @ViewBuilder
    private func guideArticleCard(_ entry: SurvivalGuideEntry) -> some View {
        let article = entry.article
        let isExpanded = viewModel.expandedGuideID == article.id
        let quickActions = isExpanded ? article.in30Seconds : Array(article.in30Seconds.prefix(2))
        let hiddenQuickActionCount = max(0, article.in30Seconds.count - quickActions.count)
        let metadata = "\(article.id) • \(entry.category.title.resolve(locale: locale)) • \(viewModel.readDurationLabel(locale: locale, minutes: article.readMinutes)) • \(article.priority.resolve(locale: locale))"

        VStack(alignment: .leading, spacing: 12) {
            Button {
                withAnimation(.spring(response: 0.28, dampingFraction: 0.92)) {
                    viewModel.toggleExpansion(articleID: article.id)
                }
            } label: {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(guideText(article.title))
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)

                        Text(metadata)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.leading)
                    }

                    Spacer(minLength: 8)

                    Image(systemName: "chevron.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                        .animation(.easeInOut(duration: 0.2), value: isExpanded)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            guideSection(
                title: localized("30 saniyede", "In 30 seconds"),
                items: quickActions.map(guideText),
                warning: false
            )

            if isExpanded == false && hiddenQuickActionCount > 0 {
                Text(format(localized("+%d hızlı adım daha", "+%d more quick actions"), hiddenQuickActionCount))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if isExpanded {
                guideSection(
                    title: localized("Adım adım", "Step by step"),
                    items: article.stepByStep.map(guideText),
                    warning: false
                )

                guideSection(
                    title: localized("Yapma", "Don't do"),
                    items: article.dontDo.map(guideText),
                    warning: true
                )

                guideChecklistSection(article: article)

                if let sourceNote = article.sourceNote.map(guideText) {
                    Text("\(localized("Kaynak notu:", "Source note:")) \(sourceNote)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if article.checklist.isEmpty == false {
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        viewModel.startChecklist(for: article)
                    }
                } label: {
                    Label(localized("Checklist'i başlat", "Start checklist"), systemImage: "checkmark.circle")
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(cardBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(cardBorder, lineWidth: 1)
        )
    }

    @ViewBuilder
    private func guideSection(title: String, items: [String], warning: Bool) -> some View {
        if items.isEmpty == false {
            let titleColor: Color = warning ? .red : .primary
            let bulletColor: Color = warning ? .red : .secondary

            VStack(alignment: .leading, spacing: 8) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(titleColor)

                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(bulletColor)
                            .frame(width: 6, height: 6)
                            .padding(.top, 7)

                        Text(item)
                            .font(.footnote)
                            .foregroundStyle(warning ? Color.red : Color.primary)
                            .multilineTextAlignment(.leading)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(warning ? 10 : 0)
            .background {
                if warning {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Color.red.opacity(0.08))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(Color.red.opacity(0.18), lineWidth: 1)
                        )
                }
            }
        }
    }

    private func guideChecklistSection(article: SurvivalGuideArticle) -> some View {
        let progress = viewModel.checklistProgress(for: article)

        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(localized("Checklist", "Checklist"))
                    .font(.subheadline.weight(.semibold))

                Spacer()

                Text(
                    format(
                        localized("%d/%d tamamlandı", "%d/%d completed"),
                        progress.completed,
                        progress.total
                    )
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            ProgressView(value: progress.ratio)
                .tint(.appPrimary)

            ForEach(Array(article.checklist.enumerated()), id: \.offset) { index, item in
                let isChecked = viewModel.isChecklistItemChecked(articleID: article.id, index: index)
                Button {
                    withAnimation(.easeInOut(duration: 0.15)) {
                        viewModel.toggleChecklist(articleID: article.id, index: index)
                    }
                } label: {
                    HStack(alignment: .center, spacing: 10) {
                        Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(isChecked ? Color.appPrimary : Color.secondary)

                        Text(guideText(item))
                            .font(.footnote)
                            .foregroundStyle(isChecked ? Color.secondary : Color.primary)
                            .strikethrough(isChecked)
                            .multilineTextAlignment(.leading)

                        Spacer(minLength: 0)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(cardBorder, lineWidth: 1)
        )
    }

    private func callEmergency(number: String) {
        guard let url = URL(string: "tel://\(number)") else { return }
        openURL(url)
    }

    private func openAssemblyArea() {
        guard
            let encodedQuery = assemblyQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
            let url = URL(string: "http://maps.apple.com/?q=\(encodedQuery)")
        else {
            return
        }
        openURL(url)
    }

    private func localized(_ tr: String, _ en: String) -> String {
        isTurkish ? tr : en
    }

    private func guideText(_ text: SurvivalGuideLocalizedText) -> String {
        replacingEmergencyNumber(in: text.resolve(locale: locale))
    }

    private func replacingEmergencyNumber(in text: String) -> String {
        let currentNumber = emergencyNumber
        return text.replacingOccurrences(
            of: #"\b(?:112|911|999|000|111|119|120|190)\b"#,
            with: currentNumber,
            options: .regularExpression
        )
    }

    private func format(_ template: String, _ arguments: CVarArg...) -> String {
        String(format: template, arguments: arguments)
    }
}

private struct GuideCategoryChip: View {
    let title: String
    let systemImage: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.caption.weight(.semibold))

                Text(title)
                    .font(.caption.weight(isSelected ? .semibold : .medium))
                    .lineLimit(1)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .foregroundStyle(isSelected ? Color.primary : Color.secondary)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(isSelected ? Color.appPrimary.opacity(0.12) : Color.appSurfaceMuted)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(isSelected ? Color.appPrimary.opacity(0.22) : Color.appBorder, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    NavigationStack {
        SurvivalGuideView()
    }
}
