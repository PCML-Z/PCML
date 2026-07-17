//
//  ModrinthModels.swift
//  PCML
//
//  Modrinth 公开 API 的数据模型（iOS 直连，无需经过桌面端）
//  参考: https://docs.modrinth.com
//

import Foundation

// MARK: - 项目（模组/整合包/资源包等）

nonisolated struct ModrinthProject: Codable, Identifiable, Hashable, Sendable {
    let id: String                       // slug 用于展示，id 为内部
    let slug: String?
    let title: String
    let description: String
    let categories: [String]
    let clientSide: String               // "required" | "optional" | "unsupported"
    let serverSide: String
    let projectType: String              // "mod" | "modpack" | "resourcepack" | "shader"
    let downloads: Int
    let follows: Int?
    let iconUrl: String?
    let author: String
    let displayCategories: [String]?
    let versions: [String]               // version ids
    let dateCreated: Date?
    let dateModified: Date?

    enum CodingKeys: String, CodingKey {
        case id, slug, title, description, categories
        case clientSide = "client_side"
        case serverSide = "server_side"
        case projectType = "project_type"
        case downloads, follows
        case iconUrl = "icon_url"
        case author
        case displayCategories = "display_categories"
        case versions
        case dateCreated = "date_created"
        case dateModified = "date_modified"
    }
}

// MARK: - 搜索结果

nonisolated struct ModrinthSearchResult: Codable, Sendable {
    let hits: [ModrinthProject]
    let totalHits: Int
    let limit: Int
    let offset: Int

    enum CodingKeys: String, CodingKey {
        case hits
        case totalHits = "total_hits"
        case limit, offset
    }
}

/// 搜索 facet 辅助
nonisolated enum ModrinthFacet: Sendable {
    case projectType(String)
    case categories(String)
    case versions(String)

    var rawValue: String {
        switch self {
        case .projectType(let v): return "project_type:\(v)"
        case .categories(let v):  return "categories:\(v)"
        case .versions(let v):    return "versions:\(v)"
        }
    }
}

// MARK: - 版本（具体的文件）

nonisolated struct ModrinthVersion: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let projectId: String
    let name: String
    let versionNumber: String
    let gameVersions: [String]
    let loaders: [String]
    let versionType: String             // "release" | "beta" | "alpha"
    let downloads: Int
    let datePublished: Date
    let files: [ModrinthFile]

    enum CodingKeys: String, CodingKey {
        case id
        case projectId = "project_id"
        case name
        case versionNumber = "version_number"
        case gameVersions = "game_versions"
        case loaders
        case versionType = "version_type"
        case downloads
        case datePublished = "date_published"
        case files
    }
}

nonisolated struct ModrinthFile: Codable, Hashable, Sendable {
    let hashes: [String: String]
    let url: String
    let filename: String
    let primary: Bool
    let size: Int
}

// MARK: - 便捷

extension ModrinthProject {
    var displayIcon: URL? { iconUrl.flatMap(URL.init(string:)) }

    var typeLabel: String {
        switch projectType {
        case "mod":         return "模组"
        case "modpack":     return "整合包"
        case "resourcepack":return "资源包"
        case "shader":      return "光影"
        default:            return projectType
        }
    }

    var downloadsText: String {
        if downloads >= 1_000_000 { return String(format: "%.1fM", Double(downloads) / 1_000_000) }
        if downloads >= 1_000     { return String(format: "%.1fK", Double(downloads) / 1_000) }
        return "\(downloads)"
    }
}
