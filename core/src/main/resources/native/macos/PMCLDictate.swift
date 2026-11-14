import AppKit
import AVFoundation
import Foundation
import Speech

// 独立小应用里听写，系统权限框才能挂到 PMCL 上。
// 本机有听写能力时只在本机识别。录音不离开这台电脑，也不交给对话模型。
// 参数只有本机端口和一次性口令。check 不打开麦克风。

private let lock = NSLock()
private var closed = false
private var latest = ""
private var socketFd: Int32 = -1
private var silence: DispatchWorkItem?
private var deadline: DispatchWorkItem?
private let engine = AVAudioEngine()
private let request = SFSpeechAudioBufferRecognitionRequest()
private var task: SFSpeechRecognitionTask?
private var recognizer: SFSpeechRecognizer?
private var tapInstalled = false

private func jsonString(_ value: String) -> String {
    var out = "\""
    for scalar in value.unicodeScalars {
        switch scalar.value {
        case 0x5C: out += "\\\\"
        case 0x22: out += "\\\""
        case 0x0A: out += "\\n"
        case 0x0D: out += "\\r"
        case 0x09: out += "\\t"
        case 0..<0x20:
            out += String(format: "\\u%04x", scalar.value)
        default:
            out.unicodeScalars.append(scalar)
        }
    }
    out += "\""
    return out
}

private func emit(_ line: String) {
    let data = Data((line + "\n").utf8)
    lock.lock()
    let fd = socketFd
    lock.unlock()
    if fd < 0 { return }
    data.withUnsafeBytes { raw in
        guard let base = raw.baseAddress else { return }
        var sent = 0
        while sent < raw.count {
            let n = Darwin.write(fd, base + sent, raw.count - sent)
            if n <= 0 { return }
            sent += n
        }
    }
}

private func shutdownSocket() {
    lock.lock()
    let fd = socketFd
    socketFd = -1
    lock.unlock()
    if fd >= 0 {
        Darwin.shutdown(fd, SHUT_WR)
        Darwin.close(fd)
    }
}

private func fail(_ code: String) {
    lock.lock()
    if closed {
        lock.unlock()
        return
    }
    closed = true
    lock.unlock()
    silence?.cancel()
    deadline?.cancel()
    emit("{\"error\":\"\(code)\"}")
    shutdownSocket()
    exit(2)
}

private func finishUtterance() {
    if !Thread.isMainThread {
        DispatchQueue.main.async { finishUtterance() }
        return
    }
    lock.lock()
    if closed {
        lock.unlock()
        return
    }
    closed = true
    let text = latest
    lock.unlock()
    silence?.cancel()
    deadline?.cancel()
    if tapInstalled {
        engine.inputNode.removeTap(onBus: 0)
        tapInstalled = false
    }
    engine.stop()
    request.endAudio()
    task?.cancel()
    emit("{\"final\":\(jsonString(text))}")
    shutdownSocket()
    exit(0)
}

private func bumpSilence() {
    silence?.cancel()
    let item = DispatchWorkItem {
        finishUtterance()
    }
    silence = item
    DispatchQueue.main.asyncAfter(deadline: .now() + 1.6, execute: item)
}

private func connect(port: UInt16) -> Int32 {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    if fd < 0 { return -1 }
    var yes: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, socklen_t(MemoryLayout.size(ofValue: yes)))
    var addr = sockaddr_in()
    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = port.bigEndian
    _ = inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr)
    let ok = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    if ok != 0 {
        Darwin.close(fd)
        return -1
    }
    return fd
}

private func chooseRecognizer() -> SFSpeechRecognizer? {
    let locales = [Locale.current, Locale(identifier: "zh-CN"), Locale(identifier: "en-US")]
    for locale in locales {
        if let candidate = SFSpeechRecognizer(locale: locale), candidate.isAvailable {
            return candidate
        }
    }
    return nil
}

private func startEngine() {
    guard let chosen = chooseRecognizer() else {
        fail("speech_unavailable")
        return
    }
    recognizer = chosen
    request.shouldReportPartialResults = true
    if #available(macOS 14.0, *) {
        request.taskHint = .dictation
    }
    if chosen.supportsOnDeviceRecognition {
        request.requiresOnDeviceRecognition = true
    }
    let input = engine.inputNode
    var format = input.outputFormat(forBus: 0)
    if format.sampleRate == 0 || format.channelCount == 0 {
        format = input.inputFormat(forBus: 0)
    }
    if format.sampleRate == 0 || format.channelCount == 0 {
        fail("audio_failed")
        return
    }
    input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
        request.append(buffer)
    }
    tapInstalled = true
    engine.prepare()
    do {
        try engine.start()
    } catch {
        fail("audio_failed")
        return
    }
    task = chosen.recognitionTask(with: request) { result, error in
        lock.lock()
        let done = closed
        lock.unlock()
        if done { return }
        if let result {
            let text = result.bestTranscription.formattedString
            lock.lock()
            latest = text
            lock.unlock()
            if result.isFinal {
                finishUtterance()
                return
            }
            if !text.isEmpty {
                emit("{\"partial\":\(jsonString(text))}")
                bumpSilence()
            }
            return
        }
        if let error = error as NSError? {
            if error.domain == "kAFAssistantErrorDomain" &&
                (error.code == 216 || error.code == 203 || error.code == 1110 || error.code == 1101) {
                finishUtterance()
                return
            }
        }
        if error != nil {
            fail("speech_unavailable")
        }
    }
    emit("{\"status\":\"listening\"}")
    let limit = DispatchWorkItem { finishUtterance() }
    deadline = limit
    DispatchQueue.main.asyncAfter(deadline: .now() + 60, execute: limit)
}

private func requestMicrophone() {
    switch AVCaptureDevice.authorizationStatus(for: .audio) {
    case .authorized:
        startEngine()
    case .notDetermined:
        AVCaptureDevice.requestAccess(for: .audio) { ok in
            DispatchQueue.main.async {
                if ok { startEngine() } else { fail("mic_denied") }
            }
        }
    default:
        fail("mic_denied")
    }
}

private func requestSpeech() {
    SFSpeechRecognizer.requestAuthorization { status in
        DispatchQueue.main.async {
            if status == .authorized {
                requestMicrophone()
            } else {
                fail("speech_denied")
            }
        }
    }
}

private func watchStop() {
    let fd = socketFd
    DispatchQueue.global(qos: .userInitiated).async {
        var buffer = [UInt8](repeating: 0, count: 64)
        var pending = ""
        while true {
            let n = Darwin.read(fd, &buffer, buffer.count)
            if n <= 0 {
                DispatchQueue.main.async { finishUtterance() }
                return
            }
            pending += String(bytes: buffer[0..<n], encoding: .utf8) ?? ""
            if pending.contains("stop") {
                DispatchQueue.main.async { finishUtterance() }
                return
            }
        }
    }
}

final class DictateApp: NSObject, NSApplicationDelegate {
    let port: UInt16
    let token: String
    init(port: UInt16, token: String) {
        self.port = port
        self.token = token
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let fd = connect(port: port)
        if fd < 0 { exit(1) }
        socketFd = fd
        emit("{\"hello\":\(jsonString(token))}")
        emit("{\"status\":\"preparing\"}")
        watchStop()
        if #available(macOS 14.0, *) {
            NSApp.activate()
        } else {
            NSApp.activate(ignoringOtherApps: true)
        }
        requestSpeech()
    }
}

if CommandLine.arguments.count == 2 && CommandLine.arguments[1] == "check" {
    fputs("{\"ok\":true}\n", stdout)
    fflush(stdout)
    exit(0)
}

guard CommandLine.arguments.count == 3, let port = UInt16(CommandLine.arguments[1]), !CommandLine.arguments[2].isEmpty else {
    fputs("usage: PMCLDictate port token\n", stderr)
    exit(2)
}

let app = NSApplication.shared
let delegate = DictateApp(port: port, token: CommandLine.arguments[2])
app.setActivationPolicy(.accessory)
app.delegate = delegate
app.run()
