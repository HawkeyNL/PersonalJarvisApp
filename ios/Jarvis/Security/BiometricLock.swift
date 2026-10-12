import Foundation
import LocalAuthentication

enum LocalUnlockResult: Equatable {
    case unlocked
    case cancelled
    case denied
    case unavailable(String)
}

struct BiometricLock {
    static let biometricsRequired = "Biometrics required. Set up Face ID or Touch ID to sign in, change models or decide pairing requests."
    static let biometricsLocked = "Face ID or Touch ID is locked after failed attempts. Unlock the iPhone with its passcode, then try again."

    /// Whether Face ID / Touch ID is enrolled and usable right now.
    static var biometricsAvailable: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    /// Face ID / Touch ID only, for every signature: no device passcode fallback
    /// and no fallback button. Lockout fails closed. On success it returns the
    /// evaluated context, which unlocks the device key without a second prompt.
    func biometricOnly(reason: String) async -> (result: LocalUnlockResult, context: LAContext?) {
        let context = LAContext()
        context.localizedFallbackTitle = ""
        context.localizedCancelTitle = "Cancel"
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            let locked = error?.code == LAError.Code.biometryLockout.rawValue
            return (.unavailable(locked ? Self.biometricsLocked : Self.biometricsRequired), nil)
        }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
                ? (.unlocked, context) : (.denied, nil)
        } catch let error as LAError {
            switch error.code {
            case .appCancel, .systemCancel, .userCancel, .userFallback: return (.cancelled, nil)
            case .biometryLockout: return (.unavailable(Self.biometricsLocked), nil)
            case .biometryNotAvailable, .biometryNotEnrolled: return (.unavailable(Self.biometricsRequired), nil)
            default: return (.denied, nil)
            }
        } catch {
            return (.denied, nil)
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
