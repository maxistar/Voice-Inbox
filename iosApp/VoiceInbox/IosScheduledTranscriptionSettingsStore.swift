import Foundation
import Shared

final class IosScheduledTranscriptionSettingsStore {
    private enum Key {
        static let enabled = "scheduledTranscription.enabled"
        static let hour = "scheduledTranscription.hour"
        static let minute = "scheduledTranscription.minute"
    }

    private let defaults: UserDefaults
    private let rules = ScheduledTranscriptionRules.shared

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> ScheduledTranscriptionSettings {
        let enabled = defaults.bool(forKey: Key.enabled)
        let hour = defaults.object(forKey: Key.hour) as? Int ?? Int(rules.DEFAULT_HOUR)
        let minute = defaults.object(forKey: Key.minute) as? Int ?? Int(rules.DEFAULT_MINUTE)
        return normalize(enabled: enabled, hour: hour, minute: minute)
    }

    func save(enabled: Bool, hour: Int, minute: Int) -> ScheduledTranscriptionSettings {
        let settings = normalize(enabled: enabled, hour: hour, minute: minute)
        defaults.set(settings.enabled, forKey: Key.enabled)
        defaults.set(Int(settings.hour), forKey: Key.hour)
        defaults.set(Int(settings.minute), forKey: Key.minute)
        return settings
    }

    private func normalize(enabled: Bool, hour: Int, minute: Int) -> ScheduledTranscriptionSettings {
        let settings = ScheduledTranscriptionSettings(
            enabled: enabled,
            hour: Int32(hour),
            minute: Int32(minute)
        )
        return rules.normalize(settings: settings)
    }
}
