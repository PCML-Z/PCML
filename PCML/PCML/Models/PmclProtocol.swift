//
//  PmclProtocol.swift
//  PCML
//
//  iOS <-> 桌面端 PMCL WebSocket 通信协议定义
//

import Foundation

// MARK: - 顶层消息信封

/// WebSocket 传输的消息信封，区分请求/响应/事件/错误
nonisolated struct PmclEnvelope: Codable, Sendable {
    let type: String              // "request" | "response" | "event" | "error"
    let id: String?               // 请求/响应配对的 UUID
    let action: String?           // 动作名
    let payload: PmclJSONValue?   // 任意 JSON 载荷
    let error: PmclErrorPayload?

    enum CodingKeys: String, CodingKey {
        case type, id, action, payload, error
    }

    init(type: String, id: String? = nil, action: String? = nil,
         payload: PmclJSONValue? = nil, error: PmclErrorPayload? = nil) {
        self.type = type
        self.id = id
        self.action = action
        self.payload = payload
        self.error = error
    }
}

nonisolated struct PmclErrorPayload: Codable, Sendable {
    let code: String
    let message: String
}

// MARK: - 支持任意 JSON 值的包装类型

/// 用于在 Codable 边界上承载任意 JSON（对象/数组/标量），方便先收后解
nonisolated enum PmclJSONValue: Codable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([PmclJSONValue])
    case object([String: PmclJSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        if let v = try? c.decode(Bool.self) { self = .bool(v); return }
        if let v = try? c.decode(Double.self) { self = .number(v); return }
        if let v = try? c.decode(String.self) { self = .string(v); return }
        if let v = try? c.decode([PmclJSONValue].self) { self = .array(v); return }
        if let v = try? c.decode([String: PmclJSONValue].self) { self = .object(v); return }
        throw DecodingError.typeMismatch(PmclJSONValue.self, .init(codingPath: decoder.codingPath,
            debugDescription: "Unknown JSON value"))
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null:        try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    // 便捷解码到具体类型
    func decode<T: Decodable>(_ type: T.Type, using decoder: JSONDecoder) throws -> T {
        let data = try JSONSerialization.data(withJSONObject: self.anyValue, options: [])
        return try decoder.decode(T.self, from: data)
    }

    var anyValue: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let v): return v
        case .number(let v): return v
        case .string(let v): return v
        case .array(let v): return v.map { $0.anyValue }
        case .object(let v): return v.mapValues { $0.anyValue }
        }
    }
}

// MARK: - 动作名常量

enum PmclAction {
    // 鉴权
    static let auth = "auth"

    // 启动控制
    static let listVersions = "listVersions"
    static let launch = "launch"
    static let kill = "kill"
    static let launchState = "launchState"        // 事件：游戏运行状态变化

    // 实时监控
    static let subscribeStats = "subscribeStats"
    static let unsubscribeStats = "unsubscribeStats"
    static let statsTick = "statsTick"            // 事件：性能数据推送

    // 模组市场（下载到 PC）
    static let installMod = "installMod"
    static let installProgress = "installProgress" // 事件

    // 好友聊天
    static let getFriends = "getFriends"
    static let getMessages = "getMessages"
    static let sendMessage = "sendMessage"
    static let messageReceived = "messageReceived" // 事件

    // 系统
    static let ping = "ping"
    static let pong = "pong"
}

// MARK: - 请求载荷

nonisolated struct PmclAuthPayload: Codable, Sendable {
    let token: String
    let deviceName: String
}

nonisolated struct PmclLaunchPayload: Codable, Sendable {
    let versionId: String
    let account: String?          // 可选，指定账号
}

nonisolated struct PmclInstallModPayload: Codable, Sendable {
    let source: String            // "modrinth" | "curseforge"
    let projectId: String
    let versionId: String
    let targetMcVersion: String
}

nonisolated struct PmclSendMessagePayload: Codable, Sendable {
    let friendId: String
    let text: String
}

nonisolated struct PmclGetMessagesPayload: Codable, Sendable {
    let friendId: String
    let limit: Int
}

// MARK: - 请求构造辅助

extension PmclEnvelope {
    static func request(action: String, payload: Encodable? = nil, id: String = UUID().uuidString) -> PmclEnvelope {
        let jsonValue: PmclJSONValue?
        if let payload {
            let data = try? JSONEncoder().encode(payload)
            if let data,
               let obj = try? JSONSerialization.jsonObject(with: data, options: []) {
                jsonValue = PmclJSONValue.fromAny(obj)
            } else {
                jsonValue = nil
            }
        } else {
            jsonValue = nil
        }
        return PmclEnvelope(type: "request", id: id, action: action, payload: jsonValue)
    }
}

extension PmclJSONValue {
    static func fromAny(_ any: Any) -> PmclJSONValue {
        if any is NSNull { return .null }
        if let v = any as? Bool { return .bool(v) }
        if let v = any as? Int { return .number(Double(v)) }
        if let v = any as? Double { return .number(v) }
        if let v = any as? String { return .string(v) }
        if let v = any as? [Any] { return .array(v.map { PmclJSONValue.fromAny($0) }) }
        if let v = any as? [String: Any] { return .object(v.mapValues { PmclJSONValue.fromAny($0) }) }
        return .null
    }
}
