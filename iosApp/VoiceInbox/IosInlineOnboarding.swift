import Foundation
import Shared
import SwiftUI

enum IosOnboardingHintLifecycle: String, Equatable {
    case active
    case dismissed
    case completed
}

struct IosOnboardingHintStore {
    static let lifecycleKey = "ios_inline_onboarding_v1.lifecycle"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> IosOnboardingHintLifecycle {
        guard let rawValue = defaults.string(forKey: Self.lifecycleKey),
              let lifecycle = IosOnboardingHintLifecycle(rawValue: rawValue) else {
            return .active
        }
        return lifecycle
    }

    func save(_ lifecycle: IosOnboardingHintLifecycle) {
        defaults.set(lifecycle.rawValue, forKey: Self.lifecycleKey)
    }
}

struct IosSetupHydration: Equatable {
    let modelKnown: Bool
    let outputKnown: Bool
    let folderKnown: Bool

    var allKnown: Bool {
        modelKnown && outputKnown && folderKnown
    }

    static let pending = IosSetupHydration(
        modelKnown: false,
        outputKnown: false,
        folderKnown: false
    )
    static let known = IosSetupHydration(
        modelKnown: true,
        outputKnown: true,
        folderKnown: true
    )
}

enum IosOnboardingStepKind: String, CaseIterable {
    case model
    case output
    case folder
}

struct IosOnboardingChecklistStep: Identifiable {
    let kind: IosOnboardingStepKind
    let label: String
    let complete: Bool
    let optional: Bool

    var id: String { kind.rawValue }

    var accessibilityLabel: String {
        let state = complete ? "Completed" : "Not completed"
        let optionalSuffix = optional ? ", optional" : ""
        return "\(state): \(label)\(optionalSuffix)"
    }
}

struct IosOnboardingHintAction {
    let label: String
    let enabled: Bool
    let kind: TaskActionKind
}

struct IosOnboardingHintPresentation {
    static let stableId = "onboarding:setup"
    static let hidden = IosOnboardingHintPresentation()

    let visible: Bool
    let title: String
    let explanation: String
    let downloadDisclosure: String?
    let steps: [IosOnboardingChecklistStep]
    let action: IosOnboardingHintAction?

    init(
        visible: Bool = false,
        title: String = "Set up Voice Inbox",
        explanation: String = "Follow these steps, or use the setup tasks above in any order.",
        downloadDisclosure: String? = nil,
        steps: [IosOnboardingChecklistStep] = [],
        action: IosOnboardingHintAction? = nil
    ) {
        self.visible = visible
        self.title = title
        self.explanation = explanation
        self.downloadDisclosure = downloadDisclosure
        self.steps = steps
        self.action = action
    }
}

enum IosOnboardingHintPresenter {
    static func present(
        lifecycle: IosOnboardingHintLifecycle,
        selection: IosShellCatalogSelection,
        hydration: IosSetupHydration,
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot
    ) -> IosOnboardingHintPresentation {
        guard lifecycle == .active,
              selection == .new,
              hydration.allKnown,
              !allStepsComplete(model: model, output: output, folder: folder) else {
            return .hidden
        }

        let action = nextAction(model: model, output: output, folder: folder)
        let networkActions = [TaskActionKind.downloadModel, TaskActionKind.retryModelDownload]
        return IosOnboardingHintPresentation(
            visible: true,
            downloadDisclosure: networkActions.contains { $0 == action.kind } && action.enabled
                ? "This action downloads the speech model to this device."
                : nil,
            steps: [
                IosOnboardingChecklistStep(
                    kind: .model,
                    label: "Install speech model",
                    complete: model.state == .ready,
                    optional: false
                ),
                IosOnboardingChecklistStep(
                    kind: .output,
                    label: "Configure automatic transcript export",
                    complete: output.state == .ready,
                    optional: true
                ),
                IosOnboardingChecklistStep(
                    kind: .folder,
                    label: "Select audio folder",
                    complete: folder.state == .ready,
                    optional: true
                ),
            ],
            action: action
        )
    }

    static func shouldComplete(
        lifecycle: IosOnboardingHintLifecycle,
        hydration: IosSetupHydration,
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot
    ) -> Bool {
        lifecycle == .active &&
            hydration.allKnown &&
            allStepsComplete(model: model, output: output, folder: folder)
    }

    private static func allStepsComplete(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot
    ) -> Bool {
        model.state == .ready && output.state == .ready && folder.state == .ready
    }

    private static func nextAction(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot
    ) -> IosOnboardingHintAction {
        if model.state == .installing {
            return IosOnboardingHintAction(
                label: "Installing speech model…",
                enabled: false,
                kind: .downloadModel
            )
        }
        if model.state != .ready, model.downloadAvailable {
            let retry = model.state == .invalid
            return IosOnboardingHintAction(
                label: retry ? "Retry setup" : "Start setup",
                enabled: true,
                kind: retry ? .retryModelDownload : .downloadModel
            )
        }
        if model.state != .ready {
            return IosOnboardingHintAction(
                label: "Install model from folder",
                enabled: true,
                kind: .importModel
            )
        }
        if output.state != .ready {
            return IosOnboardingHintAction(
                label: "Select Output File",
                enabled: true,
                kind: .selectOutput
            )
        }
        return IosOnboardingHintAction(
            label: "Select audio folder (optional)",
            enabled: folder.state != .scanning,
            kind: .selectFolder
        )
    }
}

struct IosOnboardingActionRequest {
    let stableId: String
    let kind: TaskActionKind
}

enum IosOnboardingActionAuthorizer {
    static func route(
        request: IosOnboardingActionRequest,
        presentation: IosOnboardingHintPresentation
    ) -> IosTaskActionRoute? {
        guard request.stableId == IosOnboardingHintPresentation.stableId,
              presentation.visible,
              let action = presentation.action,
              action.enabled,
              action.kind == request.kind else {
            return nil
        }
        return IosTaskActionRouter.route(action.kind)
    }
}

struct IosInlineOnboardingRow: View {
    let presentation: IosOnboardingHintPresentation
    let onDismiss: () -> Void
    let onAction: (IosOnboardingHintAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                Text(presentation.title)
                    .font(.headline)
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss setup guide")
                .accessibilityIdentifier("onboarding-dismiss")
            }

            Text(presentation.explanation)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if let disclosure = presentation.downloadDisclosure {
                Text(disclosure)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("onboarding-download-disclosure")
            }

            VStack(alignment: .leading, spacing: 8) {
                ForEach(presentation.steps) { step in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Image(systemName: step.complete ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(step.complete ? .green : .secondary)
                        Text(step.optional ? "\(step.label) · Optional" : step.label)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(step.accessibilityLabel)
                    .accessibilityIdentifier("onboarding-step-\(step.kind.rawValue)")
                }
            }

            if let action = presentation.action {
                Button(action.label) {
                    onAction(action)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!action.enabled)
                .accessibilityLabel(action.label)
                .accessibilityValue(action.enabled ? "Available" : "Unavailable")
                .accessibilityIdentifier("onboarding-action")
            }
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier(IosOnboardingHintPresentation.stableId)
    }
}
