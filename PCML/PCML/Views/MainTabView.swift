//
//  MainTabView.swift
//  PCML
//
//  主界面 Tab 框架
//

import SwiftUI

struct MainTabView: View {
    @Environment(AppModel.self) private var app
    @State private var selection: Tab = .launch

    enum Tab: Hashable { case launch, stats, mods, chat, settings }

    var body: some View {
        TabView(selection: $selection) {
            LaunchView()
                .tabItem { Label("启动", systemImage: "play.circle.fill") }
                .tag(Tab.launch)

            StatsView()
                .tabItem { Label("监控", systemImage: "chart.line.uptrend.xyaxis") }
                .tag(Tab.stats)

            ModsMarketView()
                .tabItem { Label("模组", systemImage: "shippingbox.fill") }
                .tag(Tab.mods)

            ChatView()
                .tabItem {
                    Label("聊天", systemImage: "bubble.left.and.bubble.right.fill")
                        .badge(totalUnread)
                }
                .tag(Tab.chat)

            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape.fill") }
                .tag(Tab.settings)
        }
    }

    private var totalUnread: Int {
        app.friends.reduce(0) { $0 + $1.unread }
    }
}

#Preview {
    MainTabView()
        .environment(AppModel())
}
