//
//  ConnectionSetupView.swift
//  PCML
//
//  首次配对：输入桌面端 IP/端口 + 6 位配对码
//

import SwiftUI

struct ConnectionSetupView: View {
    @Environment(AppModel.self) private var app

    @State private var host: String = ""
    @State private var port: String = "28520"
    @State private var code: String = ""
    @State private var useTLS: Bool = false
    @FocusState private var focusedField: Field?

    enum Field { case host, port, code }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    headerSection
                    formSection
                    pairButton
                    if case .failed(let msg) = app.pairing {
                        errorBanner(msg)
                    }
                    helpSection
                }
                .padding(20)
            }
            .navigationTitle("连接桌面端")
            .background(Color(.systemGroupedBackground))
        }
    }

    // MARK: - 头部

    private var headerSection: some View {
        VStack(spacing: 12) {
            Image(systemName: "app.connected.to.app.below.fill")
                .font(.system(size: 56, weight: .light))
                .foregroundStyle(.tint)
                .frame(height: 72)
            Text("PCML 伴随模式")
                .font(.title2.bold())
            Text("将 iPhone 连接到桌面端 PMCL，远程启动游戏、监控性能、浏览模组")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 8)
    }

    // MARK: - 表单

    private var formSection: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "network").foregroundStyle(.secondary).frame(width: 24)
                TextField("桌面端 IP 地址", text: $host)
                    .keyboardType(.decimalPad)
                    .textContentType(.URL)
                    .focused($focusedField, equals: .host)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .onSubmit { focusedField = .port }
            }
            .padding(.vertical, 14)
            .padding(.horizontal, 16)

            Divider().padding(.leading, 48)

            HStack {
                Image(systemName: "number").foregroundStyle(.secondary).frame(width: 24)
                TextField("端口", text: $port)
                    .keyboardType(.numberPad)
                    .focused($focusedField, equals: .port)
                    .submitLabel(.next)
                    .onSubmit { focusedField = .code }
                Toggle("TLS", isOn: $useTLS).labelsHidden()
                Text("TLS").font(.caption).foregroundStyle(.secondary)
            }
            .padding(.vertical, 14)
            .padding(.horizontal, 16)

            Divider().padding(.leading, 48)

            HStack {
                Image(systemName: "key.fill").foregroundStyle(.secondary).frame(width: 24)
                TextField("6 位配对码", text: $code)
                    .keyboardType(.numberPad)
                    .focused($focusedField, equals: .code)
                    .submitLabel(.go)
                    .onSubmit { Task { await doPair() } }
            }
            .padding(.vertical, 14)
            .padding(.horizontal, 16)
        }
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: - 配对按钮

    private var pairButton: some View {
        Button {
            Task { await doPair() }
        } label: {
            Group {
                if case .pairing = app.pairing {
                    ProgressView().tint(.white)
                } else {
                    Text("配对并连接")
                        .font(.headline)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 24)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!isFormValid || isPairing)
    }

    private var isPairing: Bool {
        if case .pairing = app.pairing { return true }
        return false
    }

    private var isFormValid: Bool {
        !host.isEmpty && !port.isEmpty && code.count == 6
    }

    // MARK: - 错误横幅

    private func errorBanner(_ msg: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(msg).font(.footnote).foregroundStyle(.primary)
            Spacer()
        }
        .padding(14)
        .background(Color.orange.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    // MARK: - 帮助

    private var helpSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("操作指引", systemImage: "questionmark.circle")
                .font(.subheadline.bold())
            VStack(alignment: .leading, spacing: 6) {
                step("1", "在桌面端 PMCL 打开 设置 → 伴随模式")
                step("2", "点击「生成配对码」获得 6 位数字")
                step("3", "确认桌面与手机在同一局域网")
                step("4", "在此页面填入 IP、端口与配对码")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func step(_ n: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(n)
                .font(.caption2.bold())
                .foregroundStyle(.white)
                .frame(width: 18, height: 18)
                .background(Color.accentColor, in: Circle())
            Text(text)
        }
    }

    // MARK: - 动作

    private func doPair() async {
        guard let portInt = Int(port), portInt > 0 && portInt < 65536 else {
            app.presentError("端口无效")
            return
        }
        await app.pair(host: host, port: portInt, useTLS: useTLS, code: code)
    }
}

#Preview {
    ConnectionSetupView()
        .environment(AppModel())
}
