import AVFoundation
import MediaPlayer
import Network
import UIKit

final class RadioManager {
    static let shared = RadioManager()

    private let streamURL = URL(string: "https://servidor37-2.brlogic.com:7144/live")!
    private var player: AVPlayer?
    private var itemStatusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var reconnectWorkItem: DispatchWorkItem?
    private var reconnectAttempt = 0
    private var networkAvailable = true
    private var volume: Float = 0.85

    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "PlataformaLivre.Network")

    var stateChanged: ((Bool, String) -> Void)?
    private(set) var isActive = false
    private(set) var isPlaying = false
    private(set) var isReconnecting = false
    private(set) var currentMessage = "Toque no botão para iniciar a transmissão."

    private init() {
        configureRemoteCommands()
        startNetworkMonitor()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    func play(volume: Float) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.volume = max(0, min(1, volume))
            self.isActive = true
            self.isPlaying = false
            self.isReconnecting = false
            self.reconnectAttempt = 0
            self.cancelReconnect()
            self.configureAudioSession()
            self.connect(message: "Conectando ao Plataforma Livre...")
        }
    }

    func stop() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.isActive = false
            self.isPlaying = false
            self.isReconnecting = false
            self.reconnectAttempt = 0
            self.cancelReconnect()
            self.releasePlayer()
            self.currentMessage = "Transmissão parada. Ao tocar novamente, o app reconecta ao ponto atual do ao vivo."
            self.updateNowPlaying(rate: 0)
            self.notifyState()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func setVolume(_ value: Float) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.volume = max(0, min(1, value))
            self.player?.volume = self.volume
        }
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            scheduleReconnect(message: "Reconectando ao vivo...")
        }
    }

    private func connect(message: String) {
        guard isActive else { return }

        cancelReconnect()
        releasePlayer()
        isPlaying = false
        isReconnecting = message.lowercased().contains("reconect")
        currentMessage = message
        notifyState()
        updateNowPlaying(rate: 0)

        guard networkAvailable else {
            scheduleReconnect(message: "Sem internet. Reconectando ao vivo...")
            return
        }

        let item = AVPlayerItem(url: streamURL)
        let newPlayer = AVPlayer(playerItem: item)
        newPlayer.volume = volume
        player = newPlayer

        itemStatusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self, weak item] observedItem, _ in
            guard let self, let item, observedItem === item else { return }
            DispatchQueue.main.async {
                guard self.isActive, self.player?.currentItem === item else { return }
                switch item.status {
                case .readyToPlay:
                    self.player?.play()
                    self.isPlaying = true
                    self.isReconnecting = false
                    self.reconnectAttempt = 0
                    self.currentMessage = "Você está ouvindo o Plataforma Livre ao vivo."
                    self.updateNowPlaying(rate: 1)
                    self.notifyState()
                case .failed:
                    self.scheduleReconnect(message: "Reconectando ao vivo...")
                default:
                    break
                }
            }
        }

        timeControlObservation = newPlayer.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            guard let self else { return }
            DispatchQueue.main.async {
                guard self.isActive, self.player === player else { return }
                if player.timeControlStatus == .waitingToPlayAtSpecifiedRate && self.isPlaying {
                    self.currentMessage = "Reconectando ao vivo..."
                    self.isReconnecting = true
                    self.notifyState()
                }
            }
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playbackStalled(_:)),
            name: .AVPlayerItemPlaybackStalled,
            object: item
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(failedToPlay(_:)),
            name: .AVPlayerItemFailedToPlayToEndTime,
            object: item
        )
    }

    private func scheduleReconnect(message: String) {
        guard isActive else { return }

        isPlaying = false
        isReconnecting = true
        currentMessage = message
        updateNowPlaying(rate: 0)
        notifyState()
        cancelReconnect()

        reconnectAttempt += 1
        let exponent = min(reconnectAttempt - 1, 4)
        let delay = min(30.0, 1.5 * pow(2.0, Double(exponent)))

        let work = DispatchWorkItem { [weak self] in
            guard let self, self.isActive else { return }
            self.connect(message: "Reconectando ao vivo...")
        }
        reconnectWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func cancelReconnect() {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
    }

    private func releasePlayer() {
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil
        timeControlObservation?.invalidate()
        timeControlObservation = nil

        if let item = player?.currentItem {
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemPlaybackStalled, object: item)
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemFailedToPlayToEndTime, object: item)
        }

        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
    }

    private func startNetworkMonitor() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let available = path.status == .satisfied
            DispatchQueue.main.async {
                let wasAvailable = self.networkAvailable
                self.networkAvailable = available

                if !available && self.isActive {
                    self.releasePlayer()
                    self.scheduleReconnect(message: "Sem internet. Reconectando ao vivo...")
                } else if available && !wasAvailable && self.isActive && !self.isPlaying {
                    self.reconnectAttempt = 0
                    self.connect(message: "Reconectando ao vivo...")
                }
            }
        }
        monitor.start(queue: monitorQueue)
    }

    private func configureRemoteCommands() {
        let commands = MPRemoteCommandCenter.shared()

        commands.playCommand.isEnabled = true
        commands.stopCommand.isEnabled = true
        commands.pauseCommand.isEnabled = false
        commands.togglePlayPauseCommand.isEnabled = false

        commands.playCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.play(volume: self.volume)
            return .success
        }

        commands.stopCommand.addTarget { [weak self] _ in
            self?.stop()
            return .success
        }
    }

    private func updateNowPlaying(rate: Float) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: "Plataforma Livre",
            MPMediaItemPropertyArtist: "Centro Cultural UERJ",
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: rate
        ]

        if let image = UIImage(named: "AppLogo") {
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            info[MPMediaItemPropertyArtwork] = artwork
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func notifyState() {
        stateChanged?(isActive, currentMessage)
    }

    @objc private func playbackStalled(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isActive else { return }
            self.releasePlayer()
            self.scheduleReconnect(message: "Reconectando ao vivo...")
        }
    }

    @objc private func failedToPlay(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isActive else { return }
            self.releasePlayer()
            self.scheduleReconnect(message: "Reconectando ao vivo...")
        }
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let info = notification.userInfo,
              let rawType = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: rawType) else { return }

        if type == .ended, isActive {
            configureAudioSession()
            if !isPlaying {
                connect(message: "Reconectando ao vivo...")
            }
        }
    }
}
