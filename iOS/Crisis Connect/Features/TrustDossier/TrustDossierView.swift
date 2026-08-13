import PencilKit
import SwiftUI
import UniformTypeIdentifiers
import Combine

@MainActor
final class TrustDossierViewModel: ObservableObject {
    @Published var dossiers: [TrustDossierSummary] = []
    @Published var policyPacks: [TrustDossierPolicyPack] = []
    @Published var selection: TrustDossierSummary.ID?
    @Published var isLoading = false
    @Published var error: String?

    private let api: TrustDossierAPI
    init(api: TrustDossierAPI = .shared) { self.api = api }

    var selected: TrustDossierSummary? { dossiers.first { $0.id == selection } ?? dossiers.first }

    func refresh() async {
        isLoading = true; defer { isLoading = false }
        do {
            let result = try await api.list(); dossiers = result.0; policyPacks = result.1
            if selection == nil || !dossiers.contains(where: { $0.id == selection }) { selection = dossiers.first?.id }
            error = nil
        } catch { self.error = error.localizedDescription }
    }

    func create(title: String, description: String, purpose: String, classification: String,
                jurisdiction: String, filePlanCode: String?) async -> Bool {
        await perform {
            try await api.create(title: title, description: description, purpose: purpose,
                                 classification: classification, jurisdiction: jurisdiction, filePlanCode: filePlanCode)
        }
    }

    func applyPolicy(_ pack: TrustDossierPolicyPack) async {
        guard let dossier = selected else { return }; _ = await perform { try await api.applyPolicy(pack: pack, to: dossier) }
    }

    func freeze() async {
        guard let dossier = selected else { return }; _ = await perform { try await api.freeze(dossier) }
    }

    func upload(data: Data, fileName: String, mediaType: String) async {
        guard let dossier = selected else { return }
        _ = await perform { try await api.upload(data: data, fileName: fileName, mediaType: mediaType, to: dossier) }
    }

    private func perform(_ operation: () async throws -> TrustDossierSummary) async -> Bool {
        isLoading = true; defer { isLoading = false }
        do {
            let updated = try await operation()
            dossiers = [updated] + dossiers.filter { $0.id != updated.id }; selection = updated.id; error = nil
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
}

struct TrustDossierCenterView: View {
    @StateObject private var model = TrustDossierViewModel()
    @State private var showingCreate = false
    @State private var showingImporter = false
    @State private var showingInk = false

    var body: some View {
        NavigationSplitView {
            Group {
                if model.dossiers.isEmpty && !model.isLoading {
                    ContentUnavailableView("No secure dossier", systemImage: "folder.badge.plus",
                        description: Text("Create a standalone official file without opening an incident."))
                } else {
                    List(model.dossiers, selection: $model.selection) { dossier in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(dossier.title).font(.headline).lineLimit(2)
                            Text("\(label(dossier.purpose)) · \(label(dossier.state))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .tag(dossier.id)
                        .accessibilityLabel("\(dossier.title), \(label(dossier.state))")
                    }
                    .refreshable { await model.refresh() }
                }
            }
            .navigationTitle("Secure Dossiers")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingCreate = true } label: { Label("New dossier", systemImage: "plus") }
                }
            }
        } detail: {
            if let dossier = model.selected {
                TrustDossierDetailView(dossier: dossier, policyPacks: model.policyPacks, busy: model.isLoading,
                    onUpload: { showingImporter = true }, onInk: { showingInk = true },
                    onPolicy: { pack in Task { await model.applyPolicy(pack) } },
                    onFreeze: { Task { await model.freeze() } })
                    .id(dossier.id + "-\(dossier.revision)")
            } else {
                ContentUnavailableView("Select a secure dossier", systemImage: "folder.badge.gearshape")
            }
        }
        .navigationSplitViewStyle(.balanced)
        .overlay { if model.isLoading { ProgressView().controlSize(.large).padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18)) } }
        .task { await model.refresh() }
        .sheet(isPresented: $showingCreate) { TrustDossierCreateView { draft in
            if await model.create(title: draft.title, description: draft.description, purpose: draft.purpose,
                                  classification: draft.classification, jurisdiction: draft.jurisdiction,
                                  filePlanCode: draft.filePlanCode.isEmpty ? nil : draft.filePlanCode) { showingCreate = false }
        } }
        .sheet(isPresented: $showingInk) { TrustDossierInkView { data in
            await model.upload(data: data, fileName: "handwritten-annotation.png", mediaType: "image/png")
            showingInk = false
        } }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.pdf, .png, .jpeg], allowsMultipleSelection: false) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            Task {
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                do {
                    let data = try Data(contentsOf: url, options: [.mappedIfSafe])
                    let type = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType)?.preferredMIMEType ?? "application/octet-stream"
                    await model.upload(data: data, fileName: url.lastPathComponent, mediaType: type)
                } catch { model.error = error.localizedDescription }
            }
        }
        .alert("Secure dossier", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK", role: .cancel) { model.error = nil }
        } message: { Text(model.error ?? "") }
    }

    private func label(_ value: String) -> String { value.replacingOccurrences(of: "_", with: " ").capitalized }
}

private struct TrustDossierDetailView: View {
    let dossier: TrustDossierSummary
    let policyPacks: [TrustDossierPolicyPack]
    let busy: Bool
    let onUpload: () -> Void
    let onInk: () -> Void
    let onPolicy: (TrustDossierPolicyPack) -> Void
    let onFreeze: () -> Void

    private var canEdit: Bool { ["draft", "ready_to_freeze"].contains(dossier.state) }
    private var applicablePacks: [TrustDossierPolicyPack] {
        policyPacks.filter { pack in
            pack.status == "approved" && (pack.jurisdiction == dossier.policy.jurisdiction || pack.jurisdiction == "GLOBAL")
                && (pack.purpose == dossier.purpose || pack.purpose == "any")
                && ISO8601DateFormatter().date(from: pack.effectiveFrom).map { $0 <= Date() } == true
                && (pack.effectiveUntil.flatMap { ISO8601DateFormatter().date(from: $0) }.map { Date() < $0 } ?? true)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(dossier.title).font(.title2.bold())
                    Text(dossier.description.isEmpty ? "No description" : dossier.description).foregroundStyle(.secondary)
                    HStack { status(dossier.state, color: .appPrimary); status(dossier.classification, color: .appWarning) }
                }.appSurface(style: .elevated, padding: 18)

                VStack(alignment: .leading, spacing: 12) {
                    Label("1. Add documents", systemImage: "doc.badge.plus").font(.headline)
                    Text("PDF, PNG and JPEG are malware-scanned, converted to a passive representation and stored with the original WORM receipt.")
                        .font(.footnote).foregroundStyle(.secondary)
                    HStack {
                        Button(action: onUpload) { Label("Choose file", systemImage: "paperclip") }.buttonStyle(.borderedProminent)
                        Button(action: onInk) { Label("Write with pen", systemImage: "pencil.tip.crop.circle") }.buttonStyle(.bordered)
                    }.disabled(!canEdit || busy)
                    if dossier.components.isEmpty { Text("No document added yet.").font(.footnote).foregroundStyle(.secondary) }
                    ForEach(dossier.components) { item in
                        Label { VStack(alignment: .leading) { Text(item.fileName).lineLimit(1); Text("\(item.bytes / 1024) KB · \(item.sha256.prefix(16))…").font(.caption2).foregroundStyle(.secondary) } }
                            icon: { Image(systemName: "checkmark.shield") }.padding(.vertical, 4)
                    }
                }.appSurface(style: .regular, padding: 18)

                VStack(alignment: .leading, spacing: 12) {
                    Label("2. Apply institutional policy", systemImage: "checkmark.seal").font(.headline)
                    if dossier.policy.status == "accepted" {
                        Text(dossier.policy.policyId ?? "Policy recorded").font(.subheadline.bold())
                        Text("\(label(dossier.policy.signatureRequirement)) · \(label(dossier.policy.deliveryReceipt)) · \(dossier.recordPlan.retentionClass)")
                            .font(.footnote).foregroundStyle(.secondary)
                    } else if applicablePacks.isEmpty {
                        Text("No approved effective policy matches this jurisdiction and purpose. An authorized manager must publish one from the web policy library.")
                            .font(.footnote).foregroundStyle(.orange)
                    } else {
                        ForEach(applicablePacks) { pack in
                            Button { onPolicy(pack) } label: {
                                HStack { VStack(alignment: .leading) { Text(pack.name).font(.subheadline.bold()); Text("\(pack.content.retentionDays) days · \(label(pack.content.signatureRequirement))").font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right") }
                            }.buttonStyle(.plain).disabled(busy)
                        }
                    }
                }.appSurface(style: .regular, padding: 18)

                VStack(alignment: .leading, spacing: 12) {
                    Label("3. Freeze and continue", systemImage: "lock.doc").font(.headline)
                    Text("After freezing, document and policy contents cannot change. Qualified signature, organization seal and delivery continue through configured trust-service adapters.")
                        .font(.footnote).foregroundStyle(.secondary)
                    if canEdit {
                        Button(action: onFreeze) { Label("Freeze dossier", systemImage: "lock.fill") }
                            .buttonStyle(.borderedProminent)
                            .disabled(busy || dossier.policy.status != "accepted" || dossier.components.isEmpty)
                    } else if let digest = dossier.manifestSha256 {
                        Label("Frozen manifest \(digest.prefix(16))…", systemImage: "checkmark.shield.fill").foregroundStyle(.green)
                    }
                }.appSurface(style: .regular, padding: 18)

                Text("A handwritten mark is stored as an annotation/evidence document; it is not presented as a qualified electronic signature. Legal signing uses the configured qualified provider and retained validation report.")
                    .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 4)
            }.padding(AppTheme.screenPadding).frame(maxWidth: 760)
        }
        .background(AppScreenBackground())
        .navigationTitle("Dossier workspace")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func status(_ text: String, color: Color) -> some View { Text(label(text)).font(.caption.bold()).padding(.horizontal, 10).padding(.vertical, 6).background(color.opacity(0.14), in: Capsule()) }
    private func label(_ value: String) -> String { value.replacingOccurrences(of: "_", with: " ").capitalized }
}

private struct TrustDossierDraft { var title = ""; var description = ""; var purpose = "official_correspondence"; var classification = "internal"; var jurisdiction = "TR"; var filePlanCode = "" }

private struct TrustDossierCreateView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft = TrustDossierDraft()
    let onCreate: (TrustDossierDraft) async -> Void
    var body: some View {
        NavigationStack {
            Form {
                Section("What are you preparing?") { TextField("Dossier name", text: $draft.title); TextField("Short description", text: $draft.description, axis: .vertical) }
                Section("Classification") {
                    Picker("Purpose", selection: $draft.purpose) { Text("Official correspondence").tag("official_correspondence"); Text("Field report").tag("field_report"); Text("Handover").tag("handover"); Text("Inspection").tag("inspection"); Text("Resource transaction").tag("resource_transaction"); Text("Other").tag("other") }
                    Picker("Confidentiality", selection: $draft.classification) { Text("Public").tag("public"); Text("Internal").tag("internal"); Text("Confidential").tag("confidential"); Text("Restricted").tag("restricted") }
                    TextField("Jurisdiction (TR, DE, GLOBAL…)", text: $draft.jurisdiction).textInputAutocapitalization(.characters)
                    TextField("File-plan code (optional)", text: $draft.filePlanCode)
                }
            }.navigationTitle("New secure dossier").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Create") { Task { await onCreate(draft) } }.disabled(draft.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) } }
        }.presentationDetents([.large])
    }
}

private struct TrustDossierInkView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var canvas = PKCanvasView()
    @State private var exporting = false
    let onExport: (Data) async -> Void
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text("Handwritten annotation — not a qualified electronic signature")
                    .font(.footnote.weight(.semibold)).foregroundStyle(.orange).padding(12).frame(maxWidth: .infinity).background(.orange.opacity(0.08))
                PencilCanvas(canvas: $canvas).background(Color.white).accessibilityLabel("Handwritten annotation canvas")
            }
            .navigationTitle("Write with pen")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Attach annotation") { Task { exporting = true; let bounds = canvas.drawing.bounds.insetBy(dx: -24, dy: -24); let image = canvas.drawing.image(from: bounds.isEmpty ? CGRect(x: 0, y: 0, width: 1024, height: 768) : bounds, scale: 2); if let data = image.pngData() { await onExport(data) }; exporting = false } }.disabled(canvas.drawing.strokes.isEmpty || exporting) } }
        }
    }
}

private struct PencilCanvas: UIViewRepresentable {
    @Binding var canvas: PKCanvasView
    func makeUIView(context: Context) -> PKCanvasView { canvas.drawingPolicy = .anyInput; canvas.backgroundColor = .white; canvas.tool = PKInkingTool(.pen, color: .black, width: 4); return canvas }
    func updateUIView(_ uiView: PKCanvasView, context: Context) {}
}
