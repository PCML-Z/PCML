package com.pmcl.ui.companion

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * 配对窗口打开时，让这台 Mac 以 BLE 外设广播 PMCL。
 * 广播和特征值都是 12 字节：6 位配对码、4 字节 IPv4、2 字节端口（大端）。
 * 手表扫到后仍用原来的 HTTP 配对，蓝牙只负责把地址交出去。
 * CoreBluetooth 必须由 LaunchServices 打开 .app，直接跑二进制会停在 unknown。
 */
internal object CompanionBleAdvertiser {
    private val lock = Any()
    private var generation = 0

    fun start(pairingCode: String, port: Int) {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("mac")) return
        val digits = pairingCode.filter { it.isDigit() }
        if (digits.length < 6) return
        val ip = listLocalIps().firstOrNull() ?: return
        val gen: Int
        synchronized(lock) {
            generation += 1
            gen = generation
            killLocked()
        }
        thread(name = "pmcl-ble", isDaemon = true) {
            try {
                val app = ensureApp()
                synchronized(lock) {
                    if (gen != generation) return@thread
                }
                ProcessBuilder(
                    "open", "-n", app.absolutePath, "--args",
                    digits.take(6), ip, port.toString()
                ).redirectErrorStream(true).start().waitFor()
            } catch (e: Exception) {
                System.err.println("[PMCL] 蓝牙广播没有启动: ${e.message}")
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation += 1
            killLocked()
        }
    }

    private fun killLocked() {
        val pidFile = File(baseDir(), "pid")
        val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        if (pid != null && pid > 0) {
            try {
                ProcessBuilder("kill", pid.toString()).start().waitFor()
            } catch (_: Exception) {
            }
        }
        pidFile.delete()
    }

    private fun baseDir(): File = File(System.getProperty("user.home"), ".pmcl/ble")

    private fun ensureApp(): File {
        val root = baseDir()
        val app = File(root, "PMCLBle.app")
        val macOs = File(app, "Contents/MacOS")
        val binary = File(macOs, "PMCLBle")
        val source = File(root, "PMCLBle.swift")
        val plist = File(app, "Contents/Info.plist")
        macOs.mkdirs()
        Files.write(plist.toPath(), INFO_PLIST.toByteArray(StandardCharsets.UTF_8))
        val previous = if (source.isFile) source.readText() else ""
        if (previous != SWIFT) {
            Files.write(source.toPath(), SWIFT.toByteArray(StandardCharsets.UTF_8))
            binary.delete()
        }
        if (!binary.isFile) {
            val compile = ProcessBuilder(
                "xcrun", "swiftc", "-O",
                "-framework", "CoreBluetooth",
                "-framework", "Foundation",
                "-o", binary.absolutePath,
                source.absolutePath
            ).redirectErrorStream(true).start()
            val log = compile.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            if (compile.waitFor() != 0) {
                throw IllegalStateException(log.ifBlank { "swiftc 失败" })
            }
            ProcessBuilder("codesign", "--force", "--sign", "-", app.absolutePath)
                .redirectErrorStream(true).start().waitFor()
        }
        return app
    }

    private const val INFO_PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>PMCLBle</string>
  <key>CFBundleIdentifier</key><string>com.pmcl.bleadvertiser</string>
  <key>CFBundleName</key><string>PMCLBle</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSUIElement</key><true/>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>Proxima ML 通过蓝牙让手表发现这台电脑。</string>
  <key>NSBluetoothPeripheralUsageDescription</key>
  <string>Proxima ML 通过蓝牙让手表发现这台电脑。</string>
</dict>
</plist>
"""

    private const val SWIFT = """import CoreBluetooth
import Foundation

let args = CommandLine.arguments
guard args.count == 4 else {
    fputs("usage: PMCLBle code ip port\n", stderr)
    exit(1)
}
let parts = args[2].split(separator: ".")
guard parts.count == 4, args[1].count == 6, let port = UInt16(args[3]) else {
    fputs("bad payload\n", stderr)
    exit(1)
}
var payload = Data(args[1].utf8)
for part in parts {
    guard let octet = UInt8(part, radix: 10) else {
        fputs("bad ip\n", stderr)
        exit(1)
    }
    payload.append(octet)
}
payload.append(UInt8(port >> 8))
payload.append(UInt8(port & 0xff))

let pidURL = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(".pmcl/ble/pid")
try? "\(ProcessInfo.processInfo.processIdentifier)".write(to: pidURL, atomically: true, encoding: .utf8)

final class Advertiser: NSObject, CBPeripheralManagerDelegate {
    let payload: Data
    var manager: CBPeripheralManager!
    init(payload: Data) {
        self.payload = payload
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: nil)
    }
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            fputs("bluetooth \(peripheral.state.rawValue)\n", stderr)
            return
        }
        let serviceUUID = CBUUID(string: "EC10")
        let characteristic = CBMutableCharacteristic(
            type: CBUUID(string: "EC11"),
            properties: [.read],
            value: payload,
            permissions: [.readable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [characteristic]
        peripheral.add(service)
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            fputs(error.localizedDescription + "\n", stderr)
            return
        }
        let serviceUUID = CBUUID(string: "EC10")
        peripheral.startAdvertising([
            CBAdvertisementDataLocalNameKey: "PMCL",
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataServiceDataKey: [serviceUUID: payload],
        ])
        fputs("advertising\n", stderr)
    }
}

let advertiser = Advertiser(payload: payload)
RunLoop.main.run()
"""
}
