//
//  ContentView.swift
//  PCML
//
//  Created by peddlejumper on 2026/7/17.
//

import SwiftUI

/// 根视图：根据配对/连接状态路由到配对页或主界面
struct RootView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var bindableApp = app
        Group {
            if app.hostConfig != nil && app.isAuthenticated {
                MainTabView()
            } else {
                ConnectionSetupView()
            }
        }
        .animation(.default, value: app.hostConfig != nil)
        .alert("出错了", isPresented: $bindableApp.showError) {
            Button("好") { app.showError = false }
        } message: {
            Text(app.lastError ?? "")
        }
    }
}

#Preview {
    RootView()
        .environment(AppModel())
}
