//
//  MedicalInfoSection.swift
//  Crisis Connect
//
//  Profile editor for the optional emergency medical details. Local-only; shared exclusively
//  over the encrypted rescue link during an active SOS (see MedicalInfoStore).
//

import SwiftUI

struct MedicalInfoSection: View {
    @State private var isExpanded = false
    @State private var info = MedicalInfoStore.load()
    @State private var showsSaved = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "cross.case.fill")
                        .font(.headline)
                        .foregroundStyle(Color.appPrimary)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.appPrimary.opacity(0.12)))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(LocalizedStringKey("MEDICAL_INFO_TITLE"))
                            .font(.headline.weight(.semibold))
                            .foregroundStyle(.primary)
                        Text(LocalizedStringKey("MEDICAL_INFO_SUBTITLE"))
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            .buttonStyle(.plain)

            if isExpanded {
                VStack(spacing: 10) {
                    medicalField(
                        "MEDICAL_INFO_BLOOD_LABEL",
                        text: $info.bloodType,
                        limit: MedicalInfoStore.maxBloodLength
                    )
                    medicalField(
                        "MEDICAL_INFO_ALLERGIES_LABEL",
                        text: $info.allergies,
                        limit: MedicalInfoStore.maxFieldLength
                    )
                    medicalField(
                        "MEDICAL_INFO_MEDICATION_LABEL",
                        text: $info.medication,
                        limit: MedicalInfoStore.maxFieldLength
                    )
                    medicalField(
                        "MEDICAL_INFO_NOTES_LABEL",
                        text: $info.notes,
                        limit: MedicalInfoStore.maxFieldLength
                    )
                    Button {
                        MedicalInfoStore.save(info)
                        info = MedicalInfoStore.load()
                        withAnimation { showsSaved = true }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            withAnimation { showsSaved = false }
                        }
                    } label: {
                        Text(LocalizedStringKey(showsSaved ? "MEDICAL_INFO_SAVED" : "MEDICAL_INFO_SAVE"))
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(showsSaved ? .green : Color.appPrimary)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.appRowBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.primary.opacity(0.05), lineWidth: 1)
        )
    }

    private func medicalField(
        _ titleKey: String,
        text: Binding<String>,
        limit: Int
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(titleKey))
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
            TextField("", text: Binding(
                get: { text.wrappedValue },
                set: { text.wrappedValue = String($0.prefix(limit)) }
            ))
            .textFieldStyle(.plain)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
        }
    }
}
