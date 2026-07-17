//
//  SettingsView.swift
//  PCML
//
//  设置：连接管理、解绑、设备名、关于
//

import SwiftUI
import UIKit

struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var showUnpairConfirm = false
    @State private var deviceName: String = ""

    var body: some View {
        NavigationStack {
            List {
                connectionSection
                deviceSection
                aboutSection
            }
            .navigationTitle("设置")
        }
        .onAppear { deviceName = app.deviceName }
    }

    // MARK: - 连接信息

    private var connectionSection: some View {
        Section("桌面端连接") {
            if let cfg = app.hostConfig {
                LabeledContent("服务器") {
                    Text(cfg.serverName ?? "未知").foregroundStyle(.secondary)
                }
                LabeledContent("地址") {
                    Text("\(cfg.host):\(cfg.port)").foregroundStyle(.secondary).monospaced()
                }
                LabeledContent("TLS") {
                    Text(cfg.useTLS ? "开启" : "关闭").foregroundStyle(.secondary)
                }
                LabeledContent("状态") {
                    statusLabel
                }
                Button("重新连接", systemImage: "arrow.clockwise") {
                    Task { await app.autoConnect() }
                }
                Button(role: .destructive) {
                    showUnpairConfirm = true
                } label: {
                    Label("解除配对", systemImage: "xmark.octagon")
                }
            } else {
                Text("未配对").foregroundStyle(.secondary)
            }
        }
        .alert("解除配对？", isPresented: $showUnpairConfirm) {
            Button("取消", role: .cancel) {}
            Button("解除", role: .destructive) {
                Task { await app.unpair() }
            }
        } message: {
            Text("将清除本机配对信息，需要重新输入配对码才能连接")
        }
    }

    private var statusLabel: some View {
        HStack(spacing: 6) {
            Circle().fill(app.connectionState == .connected ? .green : .gray).frame(width: 8, height: 8)
            Text(statusText).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var statusText: String {
        switch app.connectionState {
        case .disconnected: return "未连接"
        case .connecting: return "连接中"
        case .authenticating: return "鉴权中"
        case .connected: return "已连接"
        case .reconnecting(let n): return "重连中(\(n))"
        case .failed: return "失败"
        }
    }

    // MARK: - 设备

    private var deviceSection: some View {
        Section("本机") {
            TextField("设备名称", text: $deviceName)
                .onSubmit { app.setDeviceName(deviceName) }
            LabeledContent("系统版本") {
                Text(UIDevice.current.systemVersion).foregroundStyle(.secondary)
            }
            LabeledContent("机型") {
                Text(UIDevice.current.model).foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - 关于

    private var aboutSection: some View {
        Section("关于") {
            LabeledContent("版本") {
                Text(appVersion).foregroundStyle(.secondary)
            }
            LabeledContent("协议") {
                Text("WebSocket / WSS").foregroundStyle(.secondary)
            }
            Link(destination: URL(string: "https://modrinth.com")!) {
                Label("Modrinth API", systemImage: "safari")
            }
            Label("PCML 伴随模式", systemImage: "info.circle")
                .foregroundStyle(.secondary)
        }
    }

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(v) (\(b))"
    }
}

#Preview {
    SettingsView().environment(AppModel())
}
