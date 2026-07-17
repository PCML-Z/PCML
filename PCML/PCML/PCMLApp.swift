//
//  PCMLApp.swift
//  PCML
//
//  Created by peddlejumper on 2026/7/17.
//

import SwiftUI

@main
struct PCMLApp: App {
    @State private var appModel = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appModel)
                .task {
                    appModel.startSubscriptions()
                }
        }
    }
}
