import Foundation
import LocalAuthentication

enum LocalUnlockResult: Equatable {
    case unlocked
    case cancelled
    case denied
    case unavailable(String)
}

struct BiometricLock {
    static let biometricsRequired = "Biometrics required. Set up Face ID or Touch ID to approve or deny pairing requests."

    /// Whether Face ID / Touch ID is enrolled and usable right now.
    static var biometricsAvailable: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    /// Face ID / Touch ID only, for pairing decisions: no device passcode
    /// fallback and no fallback button. Lockout fails closed.
    func biometricOnly(reason: String) async -> LocalUnlockResult {
        let context = LAContext()
        context.localizedFallbackTitle = ""
        context.localizedCancelTitle = "Cancel"
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return .unavailable(Self.biometricsRequired)
        }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
                ? .unlocked : .denied
        } catch let error as LAError {
            switch error.code {
            case .appCancel, .systemCancel, .userCancel, .userFallback: return .cancelled
            case .biometryNotAvailable, .biometryNotEnrolled, .biometryLockout: return .unavailable(Self.biometricsRequired)
            default: return .denied
            }
        } catch {
            return .denied
        }
    }

    func unlock(reason: String) async -> LocalUnlockResult {
        let context = LAContext()
        context.localizedCancelTitle = "Stay Locked"
        var error: NSError?

        // Jarvis mobile permits the system device credential fallback through
        // Apple's owner-authentication policy; no credential enters this app.
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            return .unavailable("Device authentication is not available.")
        }

        do {
            if try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) {
                return .unlocked
            }
            return .denied
        } catch let error as LAError {
            switch error.code {
            case .appCancel, .systemCancel, .userCancel:
                return .cancelled
            case .biometryNotAvailable, .biometryNotEnrolled, .passcodeNotSet:
                return .unavailable("Set up Face ID, Touch ID, or a device passcode to unlock Jarvis.")
            default:
                return .denied
            }
        } catch {
            return .denied
        }
    }
}
