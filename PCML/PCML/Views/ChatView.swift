//
//  ChatView.swift
//  PCML
//
//  好友聊天：通过桌面端 PMCL 中转
//

import SwiftUI

struct ChatView: View {
    @Environment(AppModel.self) private var app
    @State private var conversations: [FriendInfo] = []

    var body: some View {
        NavigationStack {
            Group {
                if app.connectionState == .connected {
                    friendsList
                } else {
                    disconnectedState
                }
            }
            .navigationTitle("好友")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await app.refreshFriends() }
                    } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .refreshable { await app.refreshFriends() }
        }
    }

    private var friendsList: some View {
        List {
            if app.friends.isEmpty {
                Text("桌面端暂无好友")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(app.friends) { f in
                    NavigationLink {
                        ChatConversationView(friend: f)
                    } label: { FriendRow(friend: f) }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var disconnectedState: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 40))
                .foregroundStyle(.tertiary)
            Text("未连接桌面端")
                .font(.subheadline).foregroundStyle(.secondary)
            Text("聊天需通过桌面端 PMCL 中转")
                .font(.caption).foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct FriendRow: View {
    let friend: FriendInfo

    var body: some View {
        HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                Circle()
                    .fill(Color.accentColor.opacity(0.15))
                    .overlay(Text(String(friend.name.prefix(1))).font(.headline).foregroundStyle(.tint))
                    .frame(width: 44, height: 44)
                Circle()
                    .fill(friend.online ? Color.green : Color.gray)
                    .frame(width: 12, height: 12)
                    .overlay(Circle().stroke(Color(.systemBackground), lineWidth: 2))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(friend.name).font(.body)
                if let last = friend.lastSeen, !friend.online {
                    Text("最近 \(last.formatted(.relative(presentation: .named)))")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if friend.unread > 0 {
                Text("\(friend.unread)")
                    .font(.caption2.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(Color.red, in: Capsule())
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - 单个会话

struct ChatConversationView: View {
    @Environment(AppModel.self) private var app
    let friend: FriendInfo

    @State private var messages: [ChatMessage] = []
    @State private var input = ""
    @State private var loading = false
    @State private var sending = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(messages) { m in
                            MessageBubble(message: m).id(m.id)
                        }
                    }
                    .padding(12)
                }
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }
            inputBar
        }
        .navigationTitle(friend.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var inputBar: some View {
        HStack(spacing: 10) {
            TextField("发送消息…", text: $input, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...4)
            Button {
                Task { await send() }
            } label: {
                Image(systemName: sending ? "ellipsis" : "arrow.up.circle.fill")
                    .font(.title2)
            }
            .disabled(input.trimmingCharacters(in: .whitespaces).isEmpty || sending)
        }
        .padding(10)
        .background(Color(.systemBackground))
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            messages = try await PmclClient.shared.getMessages(friendId: friend.id)
        } catch {
            app.presentError(error.localizedDescription)
        }
    }

    private func send() async {
        let text = input.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        sending = true
        input = ""
        do {
            try await PmclClient.shared.sendMessage(friendId: friend.id, text: text)
            // 乐观插入
            messages.append(ChatMessage(id: UUID().uuidString, friendId: friend.id,
                                        direction: "sent", text: text, timestamp: Date()))
        } catch {
            app.presentError(error.localizedDescription)
        }
        sending = false
    }
}

private struct MessageBubble: View {
    let message: ChatMessage

    private var isSent: Bool { message.direction == "sent" }

    var body: some View {
        HStack {
            if isSent { Spacer(minLength: 60) }
            Text(message.text)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(isSent ? Color.accentColor : Color(.secondarySystemBackground),
                            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .foregroundStyle(isSent ? .white : .primary)
                .font(.body)
            if !isSent { Spacer(minLength: 60) }
        }
    }
}

#Preview {
    ChatView().environment(AppModel())
}
