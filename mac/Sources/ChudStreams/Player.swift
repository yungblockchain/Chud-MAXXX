import AVKit
import Combine
import SwiftUI

/// Owns the AVPlayer across channel changes, seeks to resume points and saves progress.
@MainActor
final class PlayerController: ObservableObject {
    let player = AVPlayer()
    private var timeObserver: Any?
    private var statusWatch: AnyCancellable?
    private var resumeKey: String?
    private weak var model: AppModel?

    func load(_ request: PlaybackRequest, model: AppModel) {
        saveProgress()
        self.model = model
        resumeKey = request.resumeKey
        let item = AVPlayerItem(url: request.url)
        // Seek only once the item is ready; seeking earlier is ignored by AVPlayer.
        statusWatch = item.publisher(for: \.status)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                guard let self, status == .readyToPlay else { return }
                if request.startAt > 0 {
                    self.player.seek(to: CMTime(seconds: request.startAt, preferredTimescale: 600))
                }
                self.statusWatch = nil
            }
        player.replaceCurrentItem(with: item)
        player.play()
        if timeObserver == nil {
            timeObserver = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 5, preferredTimescale: 1),
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor in self?.saveProgress() }
            }
        }
    }

    func stop() {
        saveProgress()
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let observer = timeObserver {
            player.removeTimeObserver(observer)
            timeObserver = nil
        }
        statusWatch = nil
    }

    private func saveProgress() {
        guard let key = resumeKey, let model, let item = player.currentItem else { return }
        let position = player.currentTime().seconds
        let duration = item.duration.seconds
        guard position.isFinite, duration.isFinite, duration > 0 else { return }
        model.saveResume(key: key, seconds: position, duration: duration)
    }
}

/// Watches the keyboard while the player is open: arrows flip channels, Esc closes.
@MainActor
final class KeyMonitor: ObservableObject {
    private var monitor: Any?

    func start(_ handler: @escaping @MainActor (NSEvent) -> Bool) {
        stop()
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            handler(event) ? nil : event
        }
    }

    func stop() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
    }
}


@MainActor
struct PlayerScreen: View {
    @EnvironmentObject private var model: AppModel
    let request: PlaybackRequest
    @StateObject private var controller = PlayerController()
    @StateObject private var keys = KeyMonitor()
    @State private var bannerVisible = true

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            PlayerSurface(player: controller.player)
                .ignoresSafeArea()
            if bannerVisible {
                ChannelBanner(request: request)
                    .padding(28)
                    .transition(.opacity)
            }
            HStack {
                Spacer()
                Button {
                    model.stop()
                } label: {
                    Label("Close", systemImage: "xmark")
                }
                .buttonStyle(NeonButtonStyle())
                .help("Close the player (Esc)")
            }
            .padding(20)
        }
        .onAppear {
            controller.load(request, model: model)
            keys.start { event in handleKey(event) }
        }
        .onChange(of: request.id) { _ in
            controller.load(request, model: model)
        }
        .onDisappear {
            keys.stop()
            controller.stop()
        }
        .task(id: request.id) {
            withAnimation { bannerVisible = true }
            try? await Task.sleep(nanoseconds: 4_500_000_000)
            withAnimation { bannerVisible = false }
        }
    }

    private func handleKey(_ event: NSEvent) -> Bool {
        switch event.keyCode {
        case 53: // Esc
            model.stop()
            return true
        case 126, 116: // Up arrow, Page Up
            guard request.live else { return false }
            model.zap(1)
            return true
        case 125, 121: // Down arrow, Page Down
            guard request.live else { return false }
            model.zap(-1)
            return true
        case 34: // I: show the banner again
            withAnimation { bannerVisible.toggle() }
            return true
        default:
            return false
        }
    }
}

/// AVKit's player view: native controls, full-screen button, Picture in Picture and AirPlay.
private struct PlayerSurface: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.player = player
        view.controlsStyle = .floating
        view.showsFullScreenToggleButton = true
        view.allowsPictureInPicturePlayback = true
        view.videoGravity = .resizeAspect
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}

/// Channel number, name and what's on now and next, like the Fire TV app's banner.
@MainActor
private struct ChannelBanner: View {
    @EnvironmentObject private var model: AppModel
    let request: PlaybackRequest

    var body: some View {
        let now = Date()
        let programmes = request.streamId.flatMap { model.epg[$0] } ?? []
        let current = programmes.first { $0.isOn(at: now) }
        let next = programmes.first { $0.start >= (current?.end ?? now) }
        HStack(alignment: .center, spacing: 18) {
            if let index = request.channelIndex {
                ZStack {
                    Text("\(index + 1)").font(NeonFont.display(54)).foregroundColor(Neon.magenta).offset(x: 3, y: 2)
                    Text("\(index + 1)").font(NeonFont.display(54)).foregroundColor(Neon.cyan)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(request.title)
                    .font(NeonFont.body(22, bold: true))
                    .foregroundColor(Neon.text)
                    .lineLimit(1)
                if let subtitle = request.subtitle {
                    Text(subtitle).font(NeonFont.body(14)).foregroundColor(Neon.textSecondary)
                }
                if let current {
                    Text("Now: \(current.title)").font(NeonFont.body(15)).foregroundColor(Neon.textSecondary).lineLimit(1)
                    ProgressView(value: now.timeIntervalSince(current.start), total: current.end.timeIntervalSince(current.start))
                        .tint(Neon.cyan)
                        .frame(width: 280)
                }
                if let next {
                    Text("Next \(next.start.formatted(date: .omitted, time: .shortened)): \(next.title)")
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textMuted)
                        .lineLimit(1)
                }
                if request.live && request.channelList.count > 1 {
                    Text("Up and down arrows change channel")
                        .font(NeonFont.body(12))
                        .foregroundColor(Neon.textMuted)
                }
            }
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 16)
        .frame(maxWidth: 560, alignment: .leading)
        .background(HudShape(cut: 14).fill(Neon.background.opacity(0.86)))
        .overlay(HudShape(cut: 14).stroke(Neon.magenta.opacity(0.7), lineWidth: 1))
    }
}
