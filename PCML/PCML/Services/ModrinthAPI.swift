//
//  ModrinthAPI.swift
//  PCML
//
//  直连 Modrinth 公开 API（不需要桌面端 PMCL 即可浏览）
//

import Foundation

actor ModrinthAPI {
    static let shared = ModrinthAPI()

    private let baseURL = URL(string: "https://api.modrinth.com/v2")!
    private let session: URLSession
    private let decoder: JSONDecoder

    init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 15
        cfg.waitsForConnectivity = false
        cfg.httpAdditionalHeaders = [
            "User-Agent": "PCML-iOS/1.0 (lash.org.cn)"
        ]
        self.session = URLSession(configuration: cfg)
        let dec = JSONDecoder()
        // Modrinth 返回 ISO8601 时间（可能含毫秒）
        // 用 Date.ISO8601FormatStyle（Sendable）避免 ISO8601DateFormatter 的并发警告
        dec.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let s = try container.decode(String.self)
            // 先尝试含毫秒格式，再尝试标准格式
            if let d = try? Date(s, strategy: .iso8601.year().month().day().timeSeparator(.colon).timeZone(separator: .omitted).time(includingFractionalSeconds: true)) {
                return d
            }
            if let d = try? Date(s, strategy: .iso8601) {
                return d
            }
            throw DecodingError.dataCorruptedError(in: container,
                debugDescription: "Invalid date: \(s)")
        }
        self.decoder = dec
    }

    // MARK: - 搜索

    struct SearchQuery: Sendable {
        var query: String = ""
        var facets: [ModrinthFacet] = []
        var limit: Int = 20
        var offset: Int = 0
        var sortBy: String = "relevance"     // relevance | downloads | follows | newest | updated
        var sortOrder: String = "desc"       // asc | desc
    }

    func search(_ q: SearchQuery) async throws -> ModrinthSearchResult {
        var comps = URLComponents(url: baseURL.appendingPathComponent("search"), resolvingAgainstBaseURL: false)!
        var items: [URLQueryItem] = [
            .init(name: "limit", value: String(q.limit)),
            .init(name: "offset", value: String(q.offset)),
            .init(name: "index", value: q.sortBy)
        ]
        if !q.query.isEmpty {
            items.append(.init(name: "query", value: q.query))
        }
        // facet 用 [[]] 形式，组内 OR，组间 AND
        if !q.facets.isEmpty {
            let facetStr = "[" + q.facets.map { "[\"\($0.rawValue)\"]" }.joined(separator: ",") + "]"
            items.append(.init(name: "facets", value: facetStr))
        }
        comps.queryItems = items
        let (data, resp) = try await session.data(from: comps.url!)
        try ensureOK(resp, data: data)
        return try decoder.decode(ModrinthSearchResult.self, from: data)
    }

    // MARK: - 项目详情

    func project(idOrSlug: String) async throws -> ModrinthProject {
        let url = baseURL.appendingPathComponent("project").appendingPathComponent(idOrSlug)
        let (data, resp) = try await session.data(from: url)
        try ensureOK(resp, data: data)
        return try decoder.decode(ModrinthProject.self, from: data)
    }

    // MARK: - 项目版本列表

    func versions(projectId: String, gameVersion: String? = nil, loader: String? = nil) async throws -> [ModrinthVersion] {
        var comps = URLComponents(url: baseURL.appendingPathComponent("project")
            .appendingPathComponent(projectId).appendingPathComponent("version"), resolvingAgainstBaseURL: false)!
        var items: [URLQueryItem] = []
        if let v = gameVersion { items.append(.init(name: "game_versions", value: "[\"\(v)\"]")) }
        if let l = loader { items.append(.init(name: "loaders", value: "[\"\(l)\"]")) }
        if !items.isEmpty { comps.queryItems = items }
        let (data, resp) = try await session.data(from: comps.url!)
        try ensureOK(resp, data: data)
        return try decoder.decode([ModrinthVersion].self, from: data)
    }

    // MARK: - 错误处理

    private func ensureOK(_ resp: URLResponse, data: Data) throws {
        guard let http = resp as? HTTPURLResponse else {
            throw ModrinthError.invalidResponse
        }
        if (200...299).contains(http.statusCode) { return }
        let body = String(data: data, encoding: .utf8) ?? ""
        throw ModrinthError.http(http.statusCode, body)
    }
}

nonisolated enum ModrinthError: LocalizedError, Sendable {
    case invalidResponse
    case http(Int, String)
    case decode(String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse: return "无效响应"
        case .http(let code, let body): return "HTTP \(code): \(body)"
        case .decode(let msg): return "解析失败: \(msg)"
        }
    }
}
