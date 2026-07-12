import AVFoundation
import Foundation

@MainActor
final class IosAudioPreviewPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var playingFileId: Int64?
    @Published private(set) var errorMessage: String?

    private var player: AVAudioPlayer?

    var isPlaying: Bool {
        player?.isPlaying == true
    }

    func toggle(fileId: Int64, url: URL) {
        if playingFileId == fileId, isPlaying {
            stop()
            return
        }
        play(fileId: fileId, url: url)
    }

    func stopIfUnavailable(availableFileIds: Set<Int64>) {
        guard let playingFileId, !availableFileIds.contains(playingFileId) else { return }
        stop()
    }

    func clearError() {
        errorMessage = nil
    }

    private func play(fileId: Int64, url: URL) {
        stop()
        do {
            try configureAudioSession()
            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.prepareToPlay()
            guard player.play() else {
                errorMessage = "Could not start audio playback."
                return
            }
            self.player = player
            playingFileId = fileId
            errorMessage = nil
        } catch {
            errorMessage = "Could not play \(url.lastPathComponent): \(error.localizedDescription)"
        }
    }

    private func stop() {
        player?.stop()
        player = nil
        playingFileId = nil
        deactivateAudioSession()
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default)
        try session.setActive(true)
    }

    private func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            if self.player === player {
                self.player = nil
                self.playingFileId = nil
                self.deactivateAudioSession()
            }
            if !flag {
                self.errorMessage = "Playback stopped before the file finished."
            }
        }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor in
            if self.player === player {
                self.player = nil
                self.playingFileId = nil
                self.deactivateAudioSession()
            }
            self.errorMessage = error?.localizedDescription ?? "Could not decode audio for playback."
        }
    }
}
