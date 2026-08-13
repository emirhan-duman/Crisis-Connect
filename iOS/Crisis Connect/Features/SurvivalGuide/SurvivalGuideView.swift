//
//  SurvivalGuideView.swift
//  Crisis Connect
//
//  A task-first, offline-ready emergency guide experience.
//

import SwiftUI

private enum SurvivalGuideMode: String, CaseIterable, Identifiable {
    case now
    case prepare
    case after

    var id: String { rawValue }

    func title(locale: Locale) -> String {
        switch self {
        case .now:
            return survivalGuideString("guide_redesign_mode_now", locale: locale)
        case .prepare:
            return survivalGuideString("guide_redesign_mode_prepare", locale: locale)
        case .after:
            return survivalGuideString("guide_redesign_mode_after", locale: locale)
        }
    }

    func heading(locale: Locale) -> String {
        switch self {
        case .now:
            return survivalGuideString("guide_redesign_heading_now", locale: locale)
        case .prepare:
            return survivalGuideString("guide_redesign_heading_prepare", locale: locale)
        case .after:
            return survivalGuideString("guide_redesign_heading_after", locale: locale)
        }
    }

    func subtitle(locale: Locale) -> String {
        switch self {
        case .now:
            return survivalGuideString("guide_redesign_subtitle_now", locale: locale)
        case .prepare:
            return survivalGuideString("guide_redesign_subtitle_prepare", locale: locale)
        case .after:
            return survivalGuideString("guide_redesign_subtitle_after", locale: locale)
        }
    }

    func contains(articleID: String) -> Bool {
        switch self {
        case .prepare:
            return ["G-002", "G-003", "G-004", "E-001"].contains(articleID)
        case .after:
            return ["E-003", "E-004", "MH-001"].contains(articleID)
        case .now:
            return [
                "G-001", "E-002", "F-001", "F-002", "F-003", "W-001", "P-001",
                "FA-001", "FA-002", "FA-003", "FA-004", "FA-005"
            ].contains(articleID)
        }
    }
}

private struct SurvivalGuideHazard: Identifiable {
    let id: String
    let articleID: String
    let systemImage: String
    let color: Color
    let localizationKey: String

    func title(locale: Locale) -> String {
        survivalGuideString(localizationKey, locale: locale)
    }
}

struct SurvivalGuideView: View {
    @StateObject private var viewModel = SurvivalGuideViewModel()
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL
    @State private var selectedMode: SurvivalGuideMode = .now

    private let hazards: [SurvivalGuideHazard] = [
        .init(id: "earthquake", articleID: "E-002", systemImage: "waveform.path.ecg", color: .orange, localizationKey: "guide_redesign_hazard_earthquake"),
        .init(id: "fire", articleID: "F-001", systemImage: "flame.fill", color: .red, localizationKey: "guide_redesign_hazard_fire_smoke"),
        .init(id: "bleeding", articleID: "FA-002", systemImage: "drop.fill", color: .red, localizationKey: "guide_redesign_hazard_severe_bleeding"),
        .init(id: "airway", articleID: "FA-005", systemImage: "lungs.fill", color: .purple, localizationKey: "guide_redesign_hazard_cannot_breathe"),
        .init(id: "flood", articleID: "W-001", systemImage: "water.waves", color: .blue, localizationKey: "guide_redesign_hazard_flood"),
        .init(id: "co", articleID: "P-001", systemImage: "cloud.fog.fill", color: .gray, localizationKey: "guide_redesign_hazard_poisoning_co")
    ]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                readinessStatus
                modePicker

                VStack(alignment: .leading, spacing: 5) {
                    Text(selectedMode.heading(locale: locale))
                        .font(.title2.weight(.bold))
                    Text(selectedMode.subtitle(locale: locale))
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)

                if selectedMode == .now {
                    emergencyPanel
                    hazardGrid
                } else if selectedMode == .prepare {
                    preparationProgress
                    officialAssemblyPanel
                }

                searchField
                guideResults
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 28)
            .background(ScrollViewTouchFixer())
        }
        .background(Color.appSurfaceMuted.opacity(0.34))
        .navigationTitle(localized("guide_redesign_guide_title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var allEntries: [SurvivalGuideEntry] {
        viewModel.categories.flatMap { category in
            category.guides.map { SurvivalGuideEntry(category: category, article: $0) }
        }
    }

    private var displayedEntries: [SurvivalGuideEntry] {
        if viewModel.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
            return viewModel.visibleEntries(locale: locale)
        }
        return allEntries.filter { selectedMode.contains(articleID: $0.article.id) }
    }

    private var emergencyRegion: SurvivalGuideEmergencyRegion {
        viewModel.emergencyRegion(locale: locale)
    }

    private var isTurkey: Bool {
        emergencyRegion.countryCode == "TR"
    }

    private var readinessStatus: some View {
        HStack(spacing: 8) {
            Image(systemName: "arrow.down.circle.fill")
                .foregroundStyle(Color.appPrimary)

            Text(localized("guide_redesign_offline_available"))
                .font(.subheadline.weight(.semibold))

            Spacer(minLength: 8)

            Text(viewModel.countryLabel(locale: locale))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
    }

    private var modePicker: some View {
        Picker(localized("guide_redesign_guide_mode"), selection: $selectedMode) {
            ForEach(SurvivalGuideMode.allCases) { mode in
                Text(mode.title(locale: locale)).tag(mode)
            }
        }
        .pickerStyle(.segmented)
        .onChange(of: selectedMode) { _, _ in
            viewModel.searchQuery = ""
        }
    }

    private var emergencyPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    Circle()
                        .fill(Color.red.opacity(0.12))
                        .frame(width: 44, height: 44)
                    Image(systemName: "sos.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.red)
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(localized("guide_redesign_immediate_danger"))
                        .font(.headline)
                    Text(localized("guide_redesign_emergency_instruction"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            ForEach(emergencyRegion.contacts) { contact in
                Button {
                    callEmergency(number: contact.number)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "phone.fill")
                        VStack(alignment: .leading, spacing: 1) {
                            Text(format(localized("guide_redesign_call_format"), contact.number))
                                .font(.headline)
                            Text(contact.service.label(locale: locale))
                                .font(.caption)
                                .opacity(0.9)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.bold))
                    }
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .padding(.horizontal, 14)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(Color.red, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityLabel("\(contact.number), \(contact.service.label(locale: locale))")
                .accessibilityHint(localized("guide_redesign_opens_phone"))
            }

            if emergencyRegion.usesFallback {
                Label(
                    localized("guide_redesign_region_unverified"),
                    systemImage: "exclamationmark.triangle.fill"
                )
                .font(.footnote)
                .foregroundStyle(.orange)
            }
        }
        .padding(16)
        .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.red.opacity(0.18), lineWidth: 1)
        }
    }

    private var hazardGrid: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(localized("guide_redesign_choose_situation"))
                .font(.headline)

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible())], spacing: 10) {
                ForEach(hazards) { hazard in
                    if let entry = allEntries.first(where: { $0.article.id == hazard.articleID }) {
                        NavigationLink {
                            SurvivalGuideFocusView(entry: entry, viewModel: viewModel)
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: hazard.systemImage)
                                    .font(.title3.weight(.semibold))
                                    .foregroundStyle(hazard.color)
                                    .frame(width: 28)
                                Text(hazard.title(locale: locale))
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.primary)
                                    .multilineTextAlignment(.leading)
                                Spacer(minLength: 0)
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                            .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .stroke(Color.appBorder, lineWidth: 1)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint(localized("guide_redesign_opens_emergency_steps"))
                    }
                }
            }
        }
    }

    private var preparationProgress: some View {
        let entries = allEntries.filter { SurvivalGuideMode.prepare.contains(articleID: $0.article.id) }
        let total = entries.reduce(0) { $0 + $1.article.checklist.count }
        let completed = entries.reduce(0) { result, entry in
            result + viewModel.checklistProgress(for: entry.article).completed
        }
        let ratio = total == 0 ? 0 : Double(completed) / Double(total)

        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(localized("guide_redesign_readiness"))
                        .font(.headline)
                    Text(format(localized("guide_redesign_progress_format"), completed, total))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(Int((ratio * 100).rounded()))%")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Color.appPrimary)
            }
            ProgressView(value: ratio)
                .tint(.appPrimary)
        }
        .padding(16)
        .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var officialAssemblyPanel: some View {
        if isTurkey {
            VStack(alignment: .leading, spacing: 12) {
                Label(localized("guide_redesign_official_assembly_title"), systemImage: "mappin.and.ellipse")
                    .font(.headline)
                Text(localized("guide_redesign_official_assembly_body"))
                    .font(.body)
                    .foregroundStyle(.secondary)
                Button {
                    openOfficialAssemblyLookup()
                } label: {
                    Label(localized("guide_redesign_open_official_lookup"), systemImage: "checkmark.shield.fill")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(16)
            .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField(
                localized("guide_redesign_search_hint"),
                text: $viewModel.searchQuery
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()

            if viewModel.searchQuery.isEmpty == false {
                Button {
                    viewModel.searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel(localized("guide_redesign_clear_search"))
            }
        }
        .padding(.leading, 14)
        .frame(minHeight: 52)
        .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        }
    }

    @ViewBuilder
    private var guideResults: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(
                    viewModel.searchQuery.isEmpty
                        ? selectedMode.title(locale: locale)
                        : localized("guide_redesign_search_results")
                )
                .font(.headline)
                Spacer()
                Text("\(displayedEntries.count)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            if displayedEntries.isEmpty {
                ContentUnavailableView(
                    localized("guide_redesign_no_guide"),
                    systemImage: "magnifyingglass",
                    description: Text(localized("guide_redesign_broader_search"))
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
            } else {
                ForEach(displayedEntries) { entry in
                    NavigationLink {
                        SurvivalGuideFocusView(entry: entry, viewModel: viewModel)
                    } label: {
                        GuideCompactRow(
                            entry: entry,
                            locale: locale,
                            progress: viewModel.checklistProgress(for: entry.article)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func callEmergency(number: String) {
        guard let url = URL(string: "tel:\(number)") else { return }
        openURL(url)
    }

    private func openOfficialAssemblyLookup() {
        guard let url = URL(string: "https://www.turkiye.gov.tr/afet-ve-acil-durum-toplanma-alani-sorgulama") else { return }
        openURL(url)
    }

    private func localized(_ key: String) -> String {
        survivalGuideString(key, locale: locale)
    }

    private func format(_ template: String, _ arguments: CVarArg...) -> String {
        String(format: template, arguments: arguments)
    }
}

private struct GuideCompactRow: View {
    let entry: SurvivalGuideEntry
    let locale: Locale
    let progress: (completed: Int, total: Int, ratio: Double)

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.appPrimary.opacity(0.11))
                    .frame(width: 44, height: 44)
                Image(systemName: entry.category.symbolName)
                    .foregroundStyle(Color.appPrimary)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(entry.article.title.resolve(locale: locale))
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)

                HStack(spacing: 6) {
                    Text(entry.category.title.resolve(locale: locale))
                    if progress.total > 0 && progress.completed > 0 {
                        Text("•")
                        Text("\(progress.completed)/\(progress.total)")
                            .monospacedDigit()
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer(minLength: 4)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.bold))
                .foregroundStyle(.tertiary)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
        .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(survivalGuideString("guide_redesign_opens_focused", locale: locale))
    }
}

private struct SurvivalGuideFocusView: View {
    let entry: SurvivalGuideEntry
    @ObservedObject var viewModel: SurvivalGuideViewModel
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    private var article: SurvivalGuideArticle { entry.article }
    private var emergencyRegion: SurvivalGuideEmergencyRegion { viewModel.emergencyRegion(locale: locale) }
    private var isImmediateGuide: Bool { SurvivalGuideMode.now.contains(articleID: article.id) }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                header
                immediateActions

                if isImmediateGuide {
                    emergencyActions
                }

                if article.stepByStep.isEmpty == false {
                    detailSteps
                }

                if article.dontDo.isEmpty == false {
                    dontPanel
                }

                if article.checklist.isEmpty == false {
                    checklist
                }

                sourcePanel
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 36)
        }
        .background(Color.appSurfaceMuted.opacity(0.34))
        .navigationTitle(entry.category.title.resolve(locale: locale))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(entry.category.title.resolve(locale: locale), systemImage: entry.category.symbolName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.appPrimary)

            Text(normalized(article.title.resolve(locale: locale)))
                .font(.largeTitle.weight(.bold))
                .minimumScaleFactor(0.9)

            HStack(spacing: 8) {
                Label(viewModel.readDurationLabel(locale: locale, minutes: article.readMinutes), systemImage: "clock")
                Label(
                    "\(localized("guide_redesign_offline_available")) • \(format(localized("guide_redesign_minutes_format"), article.readMinutes))",
                    systemImage: "arrow.down.circle"
                )
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var immediateActions: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(localized("guide_redesign_do_this_now"))
                .font(.title2.weight(.bold))

            ForEach(Array(article.in30Seconds.prefix(3).enumerated()), id: \.offset) { index, item in
                HStack(alignment: .top, spacing: 12) {
                    Text("\(index + 1)")
                        .font(.headline.monospacedDigit())
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(Color.appPrimary, in: Circle())

                    Text(normalized(item.resolve(locale: locale)))
                        .font(.body.weight(.medium))
                        .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(16)
        .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.appPrimary.opacity(0.2), lineWidth: 1)
        }
    }

    @ViewBuilder
    private var emergencyActions: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(localized("guide_redesign_emergency_help"))
                .font(.headline)

            ForEach(emergencyRegion.contacts) { contact in
                Button {
                    guard let url = URL(string: "tel:\(contact.number)") else { return }
                    openURL(url)
                } label: {
                    HStack {
                        Image(systemName: "phone.fill")
                        VStack(alignment: .leading, spacing: 1) {
                            Text(format(localized("guide_redesign_call_format"), contact.number))
                                .font(.headline)
                            Text(contact.service.label(locale: locale))
                                .font(.caption)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(Color.red, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
    }

    private var detailSteps: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(localized("guide_redesign_step_by_step"))
                .font(.title3.weight(.bold))

            ForEach(Array(article.stepByStep.enumerated()), id: \.offset) { index, item in
                HStack(alignment: .top, spacing: 10) {
                    Text("\(index + 1).")
                        .font(.body.weight(.bold).monospacedDigit())
                        .foregroundStyle(Color.appPrimary)
                    Text(normalized(item.resolve(locale: locale)))
                        .font(.body)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var dontPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(localized("guide_redesign_dont_do"), systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.red)

            ForEach(Array(article.dontDo.enumerated()), id: \.offset) { _, item in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.red)
                        .padding(.top, 2)
                    Text(normalized(item.resolve(locale: locale)))
                        .font(.body)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(16)
        .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.red.opacity(0.2), lineWidth: 1)
        }
    }

    private var checklist: some View {
        let progress = viewModel.checklistProgress(for: article)

        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(localized("guide_redesign_checklist"))
                        .font(.title3.weight(.bold))
                    Text(
                        format(
                            localized("guide_redesign_completed_format"),
                            progress.completed,
                            progress.total
                        )
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(Int((progress.ratio * 100).rounded()))%")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(Color.appPrimary)
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
                    HStack(spacing: 12) {
                        Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(isChecked ? Color.appPrimary : Color.secondary)
                        Text(normalized(item.resolve(locale: locale)))
                            .font(.body)
                            .foregroundStyle(isChecked ? Color.secondary : Color.primary)
                            .strikethrough(isChecked)
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(isChecked ? localized("guide_redesign_completed") : localized("guide_redesign_not_completed"))
            }
        }
        .padding(16)
        .background(Color.appSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        }
    }

    private var sourcePanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(localized("guide_redesign_source_verification"), systemImage: "checkmark.shield")
                .font(.headline)

            if article.id == "G-001" {
                Text(emergencySourceSummary)
                    .font(.body)
            } else if let source = article.sourceNote {
                Text(normalized(source.resolve(locale: locale)))
                    .font(.body)
            }

            Label(
                localized("guide_redesign_reviewer_missing"),
                systemImage: "exclamationmark.triangle.fill"
            )
            .font(.footnote)
            .foregroundStyle(.orange)
        }
        .padding(16)
        .background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var emergencySourceSummary: String {
        let contacts = emergencyRegion.contacts.map { "\($0.service.label(locale: locale)): \($0.number)" }.joined(separator: " • ")
        return format(localized("guide_redesign_services_shown_format"), contacts)
    }

    private func normalized(_ text: String) -> String {
        var result = text
        let primary = emergencyRegion.primaryContact.number
        let contactNumbers = Set(emergencyRegion.contacts.map(\.number))
        for genericNumber in ["112", "911", "999", "000", "111"] where contactNumbers.contains(genericNumber) == false {
            result = result.replacingOccurrences(
                of: "\\b\(genericNumber)\\b",
                with: primary,
                options: .regularExpression
            )
        }
        return result
    }

    private func localized(_ key: String) -> String {
        survivalGuideString(key, locale: locale)
    }

    private func format(_ template: String, _ arguments: CVarArg...) -> String {
        String(format: template, arguments: arguments)
    }
}


#Preview {
    NavigationStack {
        SurvivalGuideView()
    }
}
