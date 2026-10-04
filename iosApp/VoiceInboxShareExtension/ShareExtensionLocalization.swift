import Foundation

enum ShareExtensionL10n {
    static func text(_ key: String, fallback: String) -> String {
        NSLocalizedString(key, bundle: .main, value: fallback, comment: "")
    }

    static func format(_ key: String, fallback: String, _ arguments: CVarArg...) -> String {
        String(format: text(key, fallback: fallback), locale: .current, arguments: arguments)
    }

    static func plural(_ key: String, count: Int, fallback: String) -> String {
        format("\(key).\(pluralForm(for: count, languageCode: Locale.current.language.languageCode?.identifier))", fallback: fallback, count)
    }

    static func pluralForm(for count: Int, languageCode: String?) -> String {
        guard languageCode == "ru" else {
            return count == 1 ? "one" : "other"
        }

        let lastTwo = count % 100
        let last = count % 10
        if last == 1 && lastTwo != 11 {
            return "one"
        }
        if (2...4).contains(last) && !(12...14).contains(lastTwo) {
            return "few"
        }
        return "many"
    }
}
