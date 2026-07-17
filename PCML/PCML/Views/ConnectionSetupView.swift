//
//  ConnectionSetupView.swift
//  PCML
//
//  首次配对：输入桌面端 IP/端口 + 6 位配对码
//

import SwiftUI

struct ConnectionSetupView: View {
    @Environment(AppModel.self) private var app

    @State private var code: String = ""
    @FocusState private var focusedField: Field?

    enum Field { case code }

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
                Image(systemName: "key.fill").foregroundStyle(.secondary).frame(width: 24)
                TextField("配对码 000-000 XXXXX-XXXXX-XXXXX", text: $code)
                    .keyboardType(.asciiCapable)
                    .autocorrectionDisabled()
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
        !code.trimmingCharacters(in: .whitespaces).isEmpty
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
                step("1", "在桌面端 PMCL 标题栏点击手机图标")
                step("2", "复制弹出的配对码（已包含 IP 信息）")
                step("3", "确认桌面与手机在同一局域网")
                step("4", "在此页面粘贴配对码并配对")
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
        await app.pair(code: code.trimmingCharacters(in: .whitespaces))
    }
}

#Preview {
    ConnectionSetupView()
        .environment(AppModel())
}
