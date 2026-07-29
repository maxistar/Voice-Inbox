import Foundation

enum IosAudioMetadataFormatter {
    static func format(
        timestamp: Date?,
        sizeBytes: Int64?,
        durationUs: Int64?,
        locale: Locale = .current,
        timeZone: TimeZone = .current
    ) -> String? {
        var parts: [String] = []
        if let timestamp, timestamp.timeIntervalSince1970 > 0 {
            let formatter = DateFormatter()
            formatter.locale = locale
            formatter.timeZone = timeZone
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            parts.append(formatter.string(from: timestamp))
        }
        if let sizeBytes, sizeBytes > 0 {
            let formatter = ByteCountFormatter()
            formatter.allowedUnits = [.useKB, .useMB, .useGB]
            formatter.countStyle = .file
            parts.append(formatter.string(fromByteCount: sizeBytes))
        }
        if let durationUs, durationUs > 0 {
            parts.append(formatDuration(durationUs))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " • ")
    }

    private static func formatDuration(_ durationUs: Int64) -> String {
        let totalSeconds = durationUs / 1_000_000
        let hours = totalSeconds / 3_600
        let minutes = (totalSeconds % 3_600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
        }
        return String(format: "%lld:%02lld", minutes, seconds)
    }
}
