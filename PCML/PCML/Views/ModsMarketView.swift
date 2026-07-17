//
//  ModsMarketView.swift
//  PCML
//
//  模组市场：直连 Modrinth 浏览，选中后可"下载到 PC"（通过桌面端安装）
//

import SwiftUI

struct ModsMarketView: View {
    @Environment(AppModel.self) private var app

    @State private var query = ""
    @State private var results: [ModrinthProject] = []
    @State private var loading = false
    @State private var page = 0
    @State private var total = 0
    @State private var typeFilter: String = "mod"
    @State private var sortIndex: Int = 0

    private let sorts = ["relevance", "downloads", "follows", "newest", "updated"]
    private let sortLabels = ["相关", "下载", "关注", "最新", "更新"]
    private let types: [(id: String, label: String, icon: String)] = [
        ("mod", "模组", "puzzlepiece.extension.fill"),
        ("modpack", "整合包", "shippingbox.fill"),
        ("resourcepack", "资源包", "paintpalette.fill"),
        ("shader", "光影", "sparkles")
    ]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                filterBar
                if loading && results.isEmpty {
                    loadingState
                } else if results.isEmpty {
                    emptyState
                } else {
                    resultList
                }
            }
            .navigationTitle("模组市场")
            .searchable(text: $query, prompt: "搜索模组、整合包…")
            .onSubmit(of: .search) { Task { await search(reset: true) } }
        }
        .task { await search(reset: true) }
    }

    // MARK: - 过滤栏

    private var filterBar: some View {
        VStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(types, id: \.id) { t in
                        Button {
                            typeFilter = t.id
                            Task { await search(reset: true) }
                        } label: {
                            Label(t.label, systemImage: t.icon)
                                .font(.caption.bold())
                                .padding(.horizontal, 12).padding(.vertical, 7)
                                .background(typeFilter == t.id ? Color.accentColor : Color(.tertiarySystemFill),
                                            in: Capsule())
                                .foregroundStyle(typeFilter == t.id ? .white : .primary)
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            Picker("排序", selection: $sortIndex) {
                ForEach(sortLabels.indices, id: \.self) { i in
                    Text(sortLabels[i]).tag(i)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .onChange(of: sortIndex) { _, _ in
                Task { await search(reset: true) }
            }
        }
        .padding(.vertical, 10)
        .background(Color(.systemBackground))
    }

    // MARK: - 结果列表

    private var resultList: some View {
        List {
            ForEach(results) { p in
                NavigationLink {
                    ModDetailView(project: p)
                } label: {
                    ModRow(project: p)
                }
            }
            if results.count < total {
                Button {
                    Task { await search(reset: false) }
                } label: {
                    HStack { Spacer(); ProgressView(); Text("加载更多"); Spacer() }
                }
            }
        }
        .listStyle(.plain)
    }

    // MARK: - 状态

    private var loadingState: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text("搜索中…").font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 44)).foregroundStyle(.tertiary)
            Text("没有找到结果").font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 搜索

    private func search(reset: Bool) async {
        if reset { page = 0 }
        loading = true
        var q = ModrinthAPI.SearchQuery()
        q.query = query
        q.facets = [.projectType(typeFilter)]
        q.limit = 20
        q.offset = page * 20
        q.sortBy = sorts[sortIndex]
        do {
            let r = try await ModrinthAPI.shared.search(q)
            if reset { results = r.hits } else { results.append(contentsOf: r.hits) }
            total = r.totalHits
            page += 1
        } catch {
            app.presentError(error.localizedDescription)
        }
        loading = false
    }
}

// MARK: - 模组行

private struct ModRow: View {
    let project: ModrinthProject

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: project.displayIcon) { img in
                img.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color(.tertiarySystemFill))
                    .overlay(Image(systemName: "shippingbox").foregroundStyle(.secondary))
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(project.title).font(.subheadline.bold()).lineLimit(1)
                Text(project.description).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                HStack(spacing: 8) {
                    Label(project.downloadsText, systemImage: "arrow.down.circle")
                    Text(project.author).font(.caption2)
                }
                .font(.caption2)
                .foregroundStyle(.tertiary)
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

#Preview {
    ModsMarketView().environment(AppModel())
}
