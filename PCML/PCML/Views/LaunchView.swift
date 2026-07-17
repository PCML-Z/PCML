//
//  LaunchView.swift
//  PCML
//
//  远程启动/停止 Minecraft，显示版本列表与运行状态
//

import SwiftUI

struct LaunchView: View {
    @Environment(AppModel.self) private var app
    @State private var searchText = ""
    @State private var launching = false
    @State private var killing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    statusCard
                    if !app.versions.isEmpty {
                        versionListSection
                    } else {
                        emptyState
                    }
                }
                .padding(16)
            }
            .navigationTitle("远程启动")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await app.refreshVersions() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(app.connectionState != .connected)
                }
            }
            .refreshable {
                await app.refreshAll()
            }
            .overlay { connectionGate }
        }
    }

    // MARK: - 状态卡片

    private var statusCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(app.launchState.running ? Color.green.opacity(0.15) : Color.gray.opacity(0.12))
                        .frame(width: 56, height: 56)
                    Image(systemName: app.launchState.running ? "play.fill" : "moon.zzz.fill")
                        .font(.title2)
                        .foregroundStyle(app.launchState.running ? .green : .secondary)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(app.launchState.running ? "游戏运行中" : "未运行")
                        .font(.headline)
                    if let v = app.launchState.versionId {
                        Text("版本 \(v)").font(.subheadline).foregroundStyle(.secondary)
                    } else {
                        Text("选择版本后远程启动")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if app.launchState.running {
                    Button(role: .destructive) {
                        Task { await stopGame() }
                    } label: {
                        Group {
                            if killing {
                                ProgressView().tint(.white)
                            } else {
                                Text("停止")
                            }
                        }
                        .frame(width: 64, height: 32)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .disabled(killing)
                }
            }
            connectionPill
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var connectionPill: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(connectionColor)
                .frame(width: 8, height: 8)
            Text(connectionText)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let name = app.hostConfig?.serverName {
                Text("· \(name)").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if app.launchState.running, let start = app.launchState.startedAt {
                HStack(spacing: 4) {
                    Text(start, style: .relative)
                    Text("前启动")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }

    private var connectionColor: Color {
        switch app.connectionState {
        case .connected, .authenticating: return .green
        case .connecting, .reconnecting: return .orange
        case .failed, .disconnected: return .gray
        }
    }

    private var connectionText: String {
        switch app.connectionState {
        case .disconnected: return "未连接"
        case .connecting: return "连接中…"
        case .authenticating: return "鉴权中…"
        case .connected: return "已连接"
        case .reconnecting(let n): return "重连中 (\(n))"
        case .failed(let m): return "失败: \(m)"
        }
    }

    // MARK: - 版本列表

    private var versionListSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("版本列表").font(.headline)
                Spacer()
                Text("\(filteredVersions.count) 个").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(filteredVersions) { v in
                VersionRow(version: v) {
                    Task { await launchGame(v) }
                }
            }
        }
    }

    private var filteredVersions: [McVersionInfo] {
        let base = app.versions.sorted { $0.isRelease && !$1.isRelease }
        if searchText.isEmpty { return base }
        return base.filter { $0.id.localizedCaseInsensitiveContains(searchText) }
    }

    // MARK: - 空状态

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray")
                .font(.system(size: 40))
                .foregroundStyle(.tertiary)
            Text("没有版本信息")
                .font(.subheadline).foregroundStyle(.secondary)
            Text("下拉刷新或确认桌面端已安装 Minecraft")
                .font(.caption).foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    // MARK: - 连接门控

    @ViewBuilder private var connectionGate: some View {
        if app.connectionState != .connected && app.connectionState != .authenticating {
            VStack(spacing: 12) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 36))
                    .foregroundStyle(.orange)
                Text("未连接到桌面端")
                    .font(.headline)
                Button("重新连接") {
                    Task { await app.autoConnect() }
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(40)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.ultraThinMaterial)
        }
    }

    // MARK: - 动作

    private func launchGame(_ v: McVersionInfo) async {
        launching = true
        defer { launching = false }
        do {
            try await PmclClient.shared.launch(versionId: v.id)
        } catch {
            app.presentError(error.localizedDescription)
        }
    }

    private func stopGame() async {
        killing = true
        defer { killing = false }
        do { try await PmclClient.shared.kill() }
        catch { app.presentError(error.localizedDescription) }
    }
}

// MARK: - 版本行

private struct VersionRow: View {
    let version: McVersionInfo
    let onLaunch: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: version.isRelease ? "shippingbox" : "flask.fill")
                .font(.title3)
                .foregroundStyle(version.isRelease ? Color.accentColor : Color.orange)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(version.id).font(.body.monospaced())
                HStack(spacing: 6) {
                    Text(version.isRelease ? "正式版" : "快照")
                        .font(.caption2)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(version.isRelease ? Color.green.opacity(0.15) : Color.orange.opacity(0.15),
                                    in: Capsule())
                    if version.installed {
                        Label("已安装", systemImage: "checkmark.circle.fill")
                            .font(.caption2)
                            .foregroundStyle(.green)
                    }
                }
            }
            Spacer()
            Button("启动", systemImage: "play.fill", action: onLaunch)
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(!version.installed)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 14)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

#Preview {
    LaunchView().environment(AppModel())
}
