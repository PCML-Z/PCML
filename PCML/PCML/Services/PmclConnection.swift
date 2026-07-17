//
//  PmclConnection.swift
//  PCML
//
//  与桌面端 PMCL 的 WebSocket 连接（actor，线程安全）
//

import Foundation

/// 连接状态
nonisolated enum PmclConnectionState: Equatable, Sendable {
    case disconnected
    case connecting
    case authenticating
    case connected
    case reconnecting(attempt: Int)
    case failed(String)
}

/// 事件流
nonisolated enum PmclEvent: Sendable {
    case stateChanged(PmclConnectionState)
    case launchState(LaunchState)
    case statsTick(StatsTick)
    case messageReceived(ChatMessage)
    case installProgress(InstallProgressEvent)
    case raw(PmclEnvelope)
}

actor PmclConnection {
    private var task: URLSessionWebSocketTask?
    private var session: URLSession
    private var config: PmclHostConfig?
    private var token: String?

    private(set) var state: PmclConnectionState = .disconnected {
        didSet { emit(.stateChanged(state)) }
    }

    // 请求/响应配对
    private var pending: [String: CheckedContinuation<PmclEnvelope, Error>] = [:]
    private var heartbeatTask: Task<Void, Never>?
    private var receiveTask: Task<Void, Never>?
    private var reconnectAttempts = 0

    // 事件订阅（非 isolated 的 AsyncStream 不便在 actor 内直接管理，改用回调集合）
    private var subscribers: [UUID: (PmclEvent) -> Void] = [:]

    init() {
        // 显式禁用所有代理：WebSocket 连接局域网/本机，系统代理会导致连接失败
        // 用字符串键名避免 kCFNetworkProxies* 常量在 iOS 上的可用性问题
        let config = URLSessionConfiguration.default
        config.connectionProxyDictionary = [
            "HTTPEnable": false,
            "HTTPSEnable": false,
            "SOCKSEnable": false,
            "ProxyAutoConfigEnable": false,
            "FTPEnable": false
        ]
        self.session = URLSession(configuration: config)
    }

    // MARK: - 订阅

    @discardableResult
    func subscribe(_ handler: @escaping (PmclEvent) -> Void) -> UUID {
        let id = UUID()
        subscribers[id] = handler
        return id
    }

    func unsubscribe(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    private nonisolated func emit(_ event: PmclEvent) {
        // 需要切回 actor 取订阅者；为减少跳转，使用 Task
        Task { await self.deliver(event) }
    }

    private func deliver(_ event: PmclEvent) {
        for handler in subscribers.values { handler(event) }
    }

    // MARK: - 连接

    func configure(_ config: PmclHostConfig, token: String?) {
        self.config = config
        self.token = token
    }

    func connect() async {
        guard let config else { return }
        disconnectInternal()
        state = .connecting
        var request = URLRequest(url: config.wsURL)
        request.timeoutInterval = 15
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let task = session.webSocketTask(with: request)
        self.task = task
        task.resume()
        state = .authenticating

        // 启动接收循环
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            await self?.receiveLoop()
        }
        // 启动心跳
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            await self?.heartbeatLoop()
        }
        // 立即发送 ping 验证连接，收到响应后 state 切到 .connected
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            _ = try? await self?.sendRaw(.request(action: PmclAction.ping))
        }
    }

    func disconnect() {
        disconnectInternal()
        state = .disconnected
    }

    private func disconnectInternal() {
        receiveTask?.cancel(); receiveTask = nil
        heartbeatTask?.cancel(); heartbeatTask = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        // 失败所有 pending 请求
        for (_, cont) in pending { cont.resume(throwing: PmclError.disconnected) }
        pending.removeAll()
    }

    // MARK: - 接收循环

    private func receiveLoop() async {
        while !Task.isCancelled {
            guard let task else { break }
            do {
                let msg = try await task.receive()
                switch msg {
                case .data(let d):
                    handleIncoming(d)
                case .string(let s):
                    if let d = s.data(using: .utf8) { handleIncoming(d) }
                @unknown default:
                    break
                }
            } catch {
                // 连接断开，触发重连
                await handleDisconnect(error: error)
                return
            }
        }
    }

    private func handleIncoming(_ data: Data) {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let envelope = try? decoder.decode(PmclEnvelope.self, from: data) else { return }

        // 收到任何有效消息即表示连接已建立
        if state == .authenticating { state = .connected }

        // 响应：匹配 pending 请求
        if envelope.type == "response" || envelope.type == "error" {
            if let id = envelope.id, let cont = pending.removeValue(forKey: id) {
                cont.resume(returning: envelope)
            }
        }
        // 事件：分发
        if envelope.type == "event" {
            handleEvent(envelope)
        }
        emit(.raw(envelope))
    }

    private func handleEvent(_ envelope: PmclEnvelope) {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let action = envelope.action, let payload = envelope.payload else { return }
        do {
            switch action {
            case PmclAction.launchState:
                let s = try payload.decode(LaunchState.self, using: decoder)
                emit(.launchState(s))
            case PmclAction.statsTick:
                let s = try payload.decode(StatsTick.self, using: decoder)
                emit(.statsTick(s))
            case PmclAction.messageReceived:
                let m = try payload.decode(ChatMessage.self, using: decoder)
                emit(.messageReceived(m))
            case PmclAction.installProgress:
                let p = try payload.decode(InstallProgressEvent.self, using: decoder)
                emit(.installProgress(p))
            default:
                break
            }
        } catch {
            // 解析失败，忽略
        }
    }

    private func handleDisconnect(error: Error) async {
        disconnectInternal()
        reconnectAttempts += 1
        let attempt = reconnectAttempts
        state = .reconnecting(attempt: attempt)
        // 指数退避，最大 30s
        let delay = min(pow(2.0, Double(attempt)), 30.0)
        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        if !Task.isCancelled { await connect() }
    }

    // MARK: - 心跳

    private func heartbeatLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 15_000_000_000) // 15s
            // 自定义 ping
            _ = try? await sendRaw(.request(action: PmclAction.ping))
        }
    }

    // MARK: - 发送请求

    func send(action: String, payload: Encodable? = nil) async throws -> PmclEnvelope {
        guard state == .connected || state == .authenticating else {
            // 尝试等待连接
            throw PmclError.notConnected
        }
        return try await sendRaw(.request(action: action, payload: payload))
    }

    private func sendRaw(_ envelope: PmclEnvelope) async throws -> PmclEnvelope {
        guard let task else { throw PmclError.notConnected }
        guard let id = envelope.id else { throw PmclError.notConnected }

        let encoder = JSONEncoder()
        let data = try encoder.encode(envelope)
        let msg: URLSessionWebSocketTask.Message = .data(data)

        // 先注册 continuation，避免响应早于注册到达
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<PmclEnvelope, Error>) in
            self.pending[id] = cont
            // 异步发送；失败则取消挂起的 continuation
            Task {
                do {
                    try await task.send(msg)
                } catch {
                    await self.failPending(id: id, error: error)
                }
            }
        }
    }

    private func failPending(id: String, error: Error) {
        if let cont = pending.removeValue(forKey: id) {
            cont.resume(throwing: error)
        }
    }
}

nonisolated enum PmclError: LocalizedError, Sendable {
    case notConnected
    case disconnected
    case server(code: String, message: String)
    case timeout

    var errorDescription: String? {
        switch self {
        case .notConnected: return "未连接到桌面端"
        case .disconnected: return "连接已断开"
        case .server(let c, let m): return "桌面端错误 [\(c)]: \(m)"
        case .timeout: return "请求超时"
        }
    }
}
