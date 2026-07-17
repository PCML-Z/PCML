//
//  ModDetailView.swift
//  PCML
//
//  模组详情：查看版本列表，可"下载到 PC"（通过桌面端安装）
//

import SwiftUI

struct ModDetailView: View {
    @Environment(AppModel.self) private var app
    let project: ModrinthProject

    @State private var versions: [ModrinthVersion] = []
    @State private var loading = false
    @State private var installing: Set<String> = []      // version ids 正在安装
    @State private var selectedGameVersion: String?
    @State private var selectedLoader: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                headerCard
                statsRow
                versionsSection
            }
            .padding(16)
        }
        .navigationTitle(project.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadVersions() }
    }

    // MARK: - 头部

    private var headerCard: some View {
        HStack(spacing: 14) {
            AsyncImage(url: project.displayIcon) { img in
                img.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                RoundedRectangle(cornerRadius: 14).fill(Color(.tertiarySystemFill))
                    .overlay(Image(systemName: "shippingbox").font(.title).foregroundStyle(.secondary))
            }
            .frame(width: 80, height: 80)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

            VStack(alignment: .leading, spacing: 6) {
                Text(project.title).font(.title3.bold()).lineLimit(2)
                Text(project.author).font(.caption).foregroundStyle(.secondary)
                Text(project.typeLabel)
                    .font(.caption2.bold())
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.accentColor.opacity(0.15), in: Capsule())
                    .foregroundStyle(.tint)
            }
            Spacer()
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: - 统计

    private var statsRow: some View {
        HStack {
            statBlock("\(project.downloads)", "下载", "arrow.down.circle")
            Divider().frame(height: 32)
            statBlock(project.follows.map { "\($0)" } ?? "—", "关注", "heart")
            Divider().frame(height: 32)
            statBlock(project.categories.prefix(2).joined(separator: " "), "分类", "tag")
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func statBlock(_ value: String, _ label: String, _ icon: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon).foregroundStyle(.secondary)
            Text(value).font(.subheadline.bold()).lineLimit(1)
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - 版本

    private var versionsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("版本").font(.headline)
            if loading && versions.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding()
            } else {
                ForEach(filteredVersions) { v in
                    versionRow(v)
                }
            }
        }
    }

    private var filteredVersions: [ModrinthVersion] {
        versions.sorted { $0.datePublished > $1.datePublished }
    }

    private func versionRow(_ v: ModrinthVersion) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(v.name).font(.subheadline.bold()).lineLimit(1)
                Spacer()
                Text(v.versionType)
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(typeColor(v.versionType).opacity(0.15), in: Capsule())
                    .foregroundStyle(typeColor(v.versionType))
            }
            HStack(spacing: 6) {
                ForEach(v.gameVersions.prefix(3), id: \.self) { gv in
                    Text(gv).font(.caption2.monospaced())
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
                ForEach(v.loaders, id: \.self) { l in
                    Text(l).font(.caption2)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(Color.purple.opacity(0.12), in: Capsule())
                        .foregroundStyle(.purple)
                }
            }
            HStack {
                Text(formatDate(v.datePublished))
                    .font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Button {
                    Task { await installToPc(v) }
                } label: {
                    Group {
                        if installing.contains(v.id) {
                            ProgressView().tint(.white)
                        } else {
                            Label("下载到 PC", systemImage: "arrow.down.to.line")
                        }
                    }
                    .font(.caption.bold())
                    .frame(height: 22)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(installing.contains(v.id) || app.connectionState != .connected)
            }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func typeColor(_ t: String) -> Color {
        switch t {
        case "release": return .green
        case "beta": return .orange
        case "alpha": return .red
        default: return .gray
        }
    }

    private func formatDate(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.locale = Locale(identifier: "zh_CN")
        return f.string(from: d)
    }

    // MARK: - 加载与安装

    private func loadVersions() async {
        loading = true
        defer { loading = false }
        do {
            versions = try await ModrinthAPI.shared.versions(projectId: project.id)
        } catch {
            app.presentError(error.localizedDescription)
        }
    }

    private func installToPc(_ v: ModrinthVersion) async {
        installing.insert(v.id)
        defer { installing.remove(v.id) }
        let mcVersion = v.gameVersions.first ?? ""
        do {
            try await PmclClient.shared.installMod(source: "modrinth", projectId: project.id,
                                                    versionId: v.id, targetMcVersion: mcVersion)
            app.presentError("已发送到桌面端下载，进度见监控页")
        } catch {
            app.presentError(error.localizedDescription)
        }
    }
}
