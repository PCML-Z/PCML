//
//  PmclModels.swift
//  PCML
//
//  桌面端 PMCL 返回的状态/数据模型
//

import Foundation

// MARK: - 版本与启动

/// Minecraft 版本（来自 PMCL 已安装/可用列表）
nonisolated struct McVersionInfo: Codable, Identifiable, Hashable, Sendable {
    let id: String              // e.g. "1.20.4"
    let type: String            // "release" | "snapshot" | "old_beta" | ...
    let installed: Bool
    let lastPlayed: Date?

    var isRelease: Bool { type == "release" }
}

/// 启动状态
nonisolated struct LaunchState: Codable, Equatable, Sendable {
    let running: Bool
    let versionId: String?
    let pid: Int?
    let startedAt: Date?
}

// MARK: - 实时性能

/// 桌面端推送的性能数据（对应桌面 RuntimeManager）
nonisolated struct StatsTick: Codable, Equatable, Sendable {
    let cpuUsage: Double          // 0...1
    let memoryUsage: Double       // 已用 GB
    let memoryTotal: Double       // 总 GB
    let gpuName: String?
    let gpuUsage: Double?         // 0...1
    let networkRxKbps: Double?
    let networkTxKbps: Double?
    let gameCpuUsage: Double?     // 游戏进程占用
    let gameMemoryMb: Double?
    let fps: Int?
    let timestamp: Date
}

// MARK: - 好友与聊天

nonisolated struct FriendInfo: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let online: Bool
    let lastSeen: Date?
    let unread: Int
}

nonisolated struct ChatMessage: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let friendId: String
    let direction: String         // "sent" | "received"
    let text: String
    let timestamp: Date
}

// MARK: - 配对与连接配置

/// 持久化的连接配置（存储在 UserDefaults，token 存 Keychain）
nonisolated struct PmclHostConfig: Codable, Equatable, Sendable {
    var host: String              // IP 或主机名
    var port: Int                 // 默认 28520
    var useTLS: Bool              // 是否 wss
    var deviceName: String        // 本机名称
    var serverName: String?       // 配对成功后桌面端自报的名称

    var wsURL: URL {
        let scheme = useTLS ? "wss" : "ws"
        return URL(string: "\(scheme)://\(host):\(port)/pmcl")!
    }
}

// MARK: - 安装进度事件

nonisolated struct InstallProgressEvent: Codable, Equatable, Sendable {
    let taskId: String
    let stage: String             // "downloading" | "installing" | "done" | "error"
    let progress: Double          // 0...1
    let message: String?
}
