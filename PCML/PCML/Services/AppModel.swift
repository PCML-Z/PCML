//
//  AppModel.swift
//  PCML
//
//  全局应用状态：连接配置、桌面端数据、事件分发
//

import Foundation
import SwiftUI
import UIKit

@Observable
final class AppModel {
    // 连接配置
    var hostConfig: PmclHostConfig? {
        didSet { persistHostConfig() }
    }
    var connectionState: PmclConnectionState = .disconnected
    var isAuthenticated: Bool { token != nil }
    private var token: String? { KeychainStore.load() }

    // 桌面端数据
    var versions: [McVersionInfo] = []
    var launchState = LaunchState(running: false, versionId: nil, pid: nil, startedAt: nil)
    var latestStats: StatsTick?
    var statsHistory: [StatsTick] = []           // 最近 ~2 分钟
    var friends: [FriendInfo] = []

    // 安装进度
    var installTasks: [String: InstallProgressEvent] = [:]

    // 配对流程临时状态
    var pairing: PairingState = .idle

    // 错误提示
    var lastError: String?
    var showError: Bool = false

    private var subscriberId: UUID?
    private let maxStatsHistory = 120

    enum PairingState: Equatable {
        case idle
        case pairing
        case paired
        case failed(String)
    }

    init() {
        loadHostConfig()
    }

    // MARK: - 持久化

    private func persistHostConfig() {
        if let cfg = hostConfig {
            if let data = try? JSONEncoder().encode(cfg) {
                UserDefaults.standard.set(data, forKey: "pmcl.hostConfig")
            }
        } else {
            UserDefaults.standard.removeObject(forKey: "pmcl.hostConfig")
        }
    }

    private func loadHostConfig() {
        if let data = UserDefaults.standard.data(forKey: "pmcl.hostConfig"),
           let cfg = try? JSONDecoder().decode(PmclHostConfig.self, from: data) {
            hostConfig = cfg
        }
    }

    // MARK: - 设备名

    var deviceName: String {
        UserDefaults.standard.string(forKey: "pmcl.deviceName") ?? UIDevice.current.name
    }

    func setDeviceName(_ name: String) {
        UserDefaults.standard.set(name, forKey: "pmcl.deviceName")
    }

    // MARK: - 连接生命周期

    func startSubscriptions() {
        guard subscriberId == nil else { return }
        Task { @MainActor in
            subscriberId = await PmclClient.shared.subscribe { [weak self] event in
                Task { @MainActor in self?.handle(event) }
            }
            // 自动连接
            await self.autoConnect()
        }
    }

    func autoConnect() async {
        guard let cfg = hostConfig, let tk = token else { return }
        await PmclClient.shared.configureAndConnect(config: cfg, token: tk)
    }

    func handle(_ event: PmclEvent) {
        switch event {
        case .stateChanged(let s):
            connectionState = s
            if case .connected = s {
                Task { await refreshAll() }
            }
        case .launchState(let s):
            launchState = s
        case .statsTick(let s):
            latestStats = s
            statsHistory.append(s)
            if statsHistory.count > maxStatsHistory { statsHistory.removeFirst() }
        case .messageReceived(let m):
            // 增加对应好友未读；UI 侧自行处理
            if let idx = friends.firstIndex(where: { $0.id == m.friendId }) {
                let f = friends[idx]
                friends[idx] = FriendInfo(id: f.id, name: f.name, online: f.online,
                                           lastSeen: f.lastSeen, unread: f.unread + 1)
            }
        case .installProgress(let p):
            installTasks[p.taskId] = p
        case .raw:
            break
        }
    }

    // MARK: - 业务刷新

    func refreshAll() async {
        await refreshVersions()
        await refreshFriends()
        try? await PmclClient.shared.subscribeStats()
    }

    func refreshVersions() async {
        do { versions = try await PmclClient.shared.listVersions() }
        catch { /* 静默 */ }
    }

    func refreshFriends() async {
        do { friends = try await PmclClient.shared.getFriends() }
        catch { /* 静默 */ }
    }

    // MARK: - 配对

    func pair(host: String, port: Int, useTLS: Bool, code: String) async {
        pairing = .pairing
        let name = deviceName
        do {
            let resp = try await PmclClient.shared.pair(host: host, port: port, useTLS: useTLS,
                                                          code: code, deviceName: name)
            KeychainStore.save(token: resp.token)
            let cfg = PmclHostConfig(host: host, port: port, useTLS: useTLS,
                                     deviceName: name, serverName: resp.serverName)
            hostConfig = cfg
            pairing = .paired
            await PmclClient.shared.configureAndConnect(config: cfg, token: resp.token)
        } catch {
            pairing = .failed(error.localizedDescription)
        }
    }

    func unpair() async {
        KeychainStore.delete()
        await PmclClient.shared.disconnect()
        hostConfig = nil
        versions = []
        friends = []
        latestStats = nil
        statsHistory = []
        connectionState = .disconnected
    }

    // MARK: - 错误展示

    func presentError(_ message: String) {
        lastError = message
        showError = true
    }
}
