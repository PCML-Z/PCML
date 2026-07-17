//
//  PmclClient.swift
//  PCML
//
//  高层 API：封装连接、配对、启动、监控、聊天等业务调用
//

import Foundation

actor PmclClient {
    static let shared = PmclClient()

    let connection: PmclConnection
    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    init() {
        self.connection = PmclConnection()
        self.session = URLSession(configuration: .default)
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        self.decoder = dec
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        self.encoder = enc
    }

    // MARK: - 配对（HTTP）

    struct PairResponse: Codable, Sendable {
        let token: String
        let serverName: String
    }

    /// 用配对码换取 token（HTTP POST）
    func pair(host: String, port: Int, useTLS: Bool, code: String, deviceName: String) async throws -> PairResponse {
        let scheme = useTLS ? "https" : "http"
        guard let url = URL(string: "\(scheme)://\(host):\(port)/pmcl/pair") else {
            throw PmclError.notConnected
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body = ["code": code, "deviceName": deviceName]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        do {
            let (data, resp) = try await session.data(for: request)
            guard let http = resp as? HTTPURLResponse else { throw PmclError.notConnected }
            guard (200...299).contains(http.statusCode) else {
                let msg = String(data: data, encoding: .utf8) ?? "配对失败"
                throw PmclError.server(code: "PAIR_\(http.statusCode)", message: msg)
            }
            return try decoder.decode(PairResponse.self, from: data)
        } catch let e as PmclError {
            throw e
        } catch {
            throw PmclError.server(code: "NETWORK", message: error.localizedDescription)
        }
    }

    // MARK: - 连接管理

    func configureAndConnect(config: PmclHostConfig, token: String) async {
        await connection.configure(config, token: token)
        await connection.connect()
    }

    func disconnect() async {
        await connection.disconnect()
    }

    func subscribe(_ handler: @escaping (PmclEvent) -> Void) async -> UUID {
        await connection.subscribe(handler)
    }

    func unsubscribe(_ id: UUID) async {
        await connection.unsubscribe(id)
    }

    // MARK: - 启动控制

    func listVersions() async throws -> [McVersionInfo] {
        let env = try await connection.send(action: PmclAction.listVersions)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
        guard let payload = env.payload else { return [] }
        return try payload.decode([McVersionInfo].self, using: decoder)
    }

    func launch(versionId: String, account: String? = nil) async throws {
        let payload = PmclLaunchPayload(versionId: versionId, account: account)
        let env = try await connection.send(action: PmclAction.launch, payload: payload)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
    }

    func kill() async throws {
        let env = try await connection.send(action: PmclAction.kill)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
    }

    // MARK: - 性能监控

    func subscribeStats() async throws {
        let env = try await connection.send(action: PmclAction.subscribeStats)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
    }

    func unsubscribeStats() async throws {
        let env = try await connection.send(action: PmclAction.unsubscribeStats)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
    }

    // MARK: - 模组安装到 PC

    func installMod(source: String, projectId: String, versionId: String, targetMcVersion: String) async throws {
        let payload = PmclInstallModPayload(source: source, projectId: projectId,
                                            versionId: versionId, targetMcVersion: targetMcVersion)
        let env = try await connection.send(action: PmclAction.installMod, payload: payload)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
    }

    // MARK: - 聊天

    func getFriends() async throws -> [FriendInfo] {
        let env = try await connection.send(action: PmclAction.getFriends)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
        guard let payload = env.payload else { return [] }
        return try payload.decode([FriendInfo].self, using: decoder)
    }

    func getMessages(friendId: String, limit: Int = 50) async throws -> [ChatMessage] {
        let payload = PmclGetMessagesPayload(friendId: friendId, limit: limit)
        let env = try await connection.send(action: PmclAction.getMessages, payload: payload)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
        guard let payload = env.payload else { return [] }
        return try payload.decode([ChatMessage].self, using: decoder)
    }

    func sendMessage(friendId: String, text: String) async throws {
        let payload = PmclSendMessagePayload(friendId: friendId, text: text)
        let env = try await connection.send(action: PmclAction.sendMessage, payload: payload)
        if let err = env.error { throw PmclError.server(code: err.code, message: err.message) }
    }
}
