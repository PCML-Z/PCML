//
//  StatsView.swift
//  PCML
//
//  实时性能监控：CPU/内存/GPU/网络，桌面端 PMCL 推送
//

import SwiftUI

struct StatsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if let s = app.latestStats {
                        summaryHeader(s)
                        metricGrid(s)
                        if !app.statsHistory.isEmpty {
                            historyChart
                        }
                    } else {
                        waitingState
                    }
                }
                .padding(16)
            }
            .navigationTitle("实时监控")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { try? await PmclClient.shared.subscribeStats() }
                    } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(app.connectionState != .connected)
                }
            }
        }
    }

    // MARK: - 头部摘要

    private func summaryHeader(_ s: StatsTick) -> some View {
        HStack(spacing: 16) {
            metricRing(value: s.cpuUsage, label: "CPU", color: .blue)
            if let gpu = s.gpuUsage {
                metricRing(value: gpu, label: "GPU", color: .purple)
            }
            metricRing(value: memoryRatio(s), label: "内存", color: .orange)
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func metricRing(value: Double, label: String, color: Color) -> some View {
        VStack(spacing: 6) {
            ZStack {
                Circle().stroke(color.opacity(0.15), lineWidth: 8)
                Circle()
                    .trim(from: 0, to: min(value, 1.0))
                    .stroke(color, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.4), value: value)
                Text("\(Int(value * 100))%")
                    .font(.subheadline.bold())
            }
            .frame(width: 64, height: 64)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func memoryRatio(_ s: StatsTick) -> Double {
        guard s.memoryTotal > 0 else { return 0 }
        return min(s.memoryUsage / s.memoryTotal, 1.0)
    }

    // MARK: - 指标网格

    private func metricGrid(_ s: StatsTick) -> some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            MetricCard(title: "内存", value: String(format: "%.1f / %.1f GB", s.memoryUsage, s.memoryTotal),
                       systemImage: "memorychip", color: .orange)
            if let fps = s.fps {
                MetricCard(title: "游戏 FPS", value: "\(fps)", systemImage: "speedometer", color: .green)
            }
            if let rx = s.networkRxKbps {
                MetricCard(title: "下载", value: formatKbps(rx), systemImage: "arrow.down.circle", color: .blue)
            }
            if let tx = s.networkTxKbps {
                MetricCard(title: "上传", value: formatKbps(tx), systemImage: "arrow.up.circle", color: .indigo)
            }
            if let name = s.gpuName {
                MetricCard(title: "GPU", value: name, systemImage: "cpu", color: .purple)
            }
            if let gc = s.gameCpuUsage {
                MetricCard(title: "游戏 CPU", value: "\(Int(gc * 100))%", systemImage: "gamecontroller", color: .pink)
            }
            if let gm = s.gameMemoryMb {
                MetricCard(title: "游戏内存", value: String(format: "%.0f MB", gm), systemImage: "internaldrive", color: .teal)
            }
        }
    }

    private func formatKbps(_ kbps: Double) -> String {
        if kbps >= 1024 { return String(format: "%.1f MB/s", kbps / 1024) }
        return String(format: "%.0f KB/s", kbps)
    }

    // MARK: - 历史曲线

    private var historyChart: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("CPU 历史 (~2 分钟)").font(.headline)
            cpuSparkline
                .frame(height: 90)
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var cpuSparkline: some View {
        Canvas { context, size in
            let values = app.statsHistory.suffix(60).map { $0.cpuUsage }
            guard values.count > 1 else { return }
            let stepX = size.width / CGFloat(values.count - 1)
            var path = Path()
            for (i, v) in values.enumerated() {
                let x = CGFloat(i) * stepX
                let y = size.height - CGFloat(v) * size.height
                if i == 0 { path.move(to: .init(x: x, y: y)) }
                else { path.addLine(to: .init(x: x, y: y)) }
            }
            context.stroke(path, with: .color(.blue), lineWidth: 2)
            // 填充
            var fill = path
            fill.addLine(to: .init(x: size.width, y: size.height))
            fill.addLine(to: .init(x: 0, y: size.height))
            fill.closeSubpath()
            context.fill(fill, with: .color(.blue.opacity(0.12)))
        }
    }

    // MARK: - 等待态

    private var waitingState: some View {
        VStack(spacing: 14) {
            ProgressView()
            Text("等待桌面端推送数据…")
                .font(.subheadline).foregroundStyle(.secondary)
            Text("确保桌面端 PMCL 已开启伴随模式")
                .font(.caption).foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 80)
    }
}

// MARK: - 指标卡

private struct MetricCard: View {
    let title: String
    let value: String
    let systemImage: String
    let color: Color

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(color)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.subheadline.bold()).lineLimit(1)
            }
            Spacer()
        }
        .padding(14)
        .background(Color(.tertiarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

#Preview {
    StatsView().environment(AppModel())
}
