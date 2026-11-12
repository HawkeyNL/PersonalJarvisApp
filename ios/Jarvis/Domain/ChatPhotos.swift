import Foundation
import ImageIO
import UIKit

/// A photo in the composer, before it is sent: shrunk, then uploaded to Core.
struct PhotoChip: Identifiable, Equatable {
    enum State: Equatable { case preparing, uploading, ready, failed }

    let id = UUID()
    var state = State.preparing
    /// The shrunk photo as shown in the chip, and later in the sent message.
    var preview: UIImage?
    /// Core's photo id, once uploaded.
    var photoId: UUID?
    var error: String?
    /// When the upload finished.
    var uploadedAt: Date?
}

/// A conversation photo that still exists on the Home Node. `expires_at` is null once kept.
struct PhotoMeta: Decodable, Equatable {
    let id: UUID
    let saved: Bool
    let expires_at: String?
    /// The id as messages carry it.
    var key: String { id.uuidString.lowercased() }
}

struct PhotoUpload: Decodable { let id: UUID }
struct PhotoSaved: Encodable { let saved: Bool }

/// Chat photos before upload, and the words for what went wrong; mirrors desktop chatPhotos.ts.
enum ChatPhotos {
    static let maxPhotos = 4
    /// Long side sent to Core; Core shrinks further to 1600 px and re-encodes.
    static let maxSide = 2048
    static let jpegQuality: CGFloat = 0.88
    /// Larger files are refused before decoding (a phone photo is far smaller).
    static let maxInputBytes = 30 * 1024 * 1024
    /// More pixels than this are refused before decoding (a 100 MP photo is already rare).
    static let maxInputPixels = 100_000_000
    static let unsupported = "This photo format isn't supported; export it as JPEG."
    static let coreTooOld = "Your Home Node needs Jarvis Core v0.0.83 to receive photos."
    static let tooLarge = "This photo is too large."
    static let unreadable = "This photo could not be read."
    static let unreachable = "Could not reach your Home Node."
    static let limit = "Up to \(maxPhotos) photos per message."
    static let nothingToPaste = "No photo on the clipboard."
    /// Core attaches an unsent photo only within 23 h of its upload.
    static let sendWithin: TimeInterval = 23 * 60 * 60
    static let tooOldToSend = "This photo waited too long; remove it and attach it again."

    struct Failure: LocalizedError, Equatable {
        let message: String
        var errorDescription: String? { message }
    }

    /// The long side to decode at: at most `maxSide`, never larger than the photo.
    static func side(width: Int, height: Int) -> Int { min(maxSide, max(width, height, 1)) }

    /// The photo as an upright JPEG of at most `maxSide` px. Transparency becomes white.
    static func downscale(_ data: Data) throws -> Data {
        guard data.count <= maxInputBytes else { throw Failure(message: tooLarge) }
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int, width > 0, height > 0
        else { throw Failure(message: unsupported) }
        guard width <= maxInputPixels / height else { throw Failure(message: tooLarge) }
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: side(width: width, height: height),
        ] as CFDictionary) else { throw Failure(message: unsupported) }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let size = CGSize(width: image.width, height: image.height)
        let flat = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIImage(cgImage: image).draw(in: CGRect(origin: .zero, size: size))
        }
        guard let jpeg = flat.jpegData(compressionQuality: jpegQuality) else { throw Failure(message: unsupported) }
        return jpeg
    }

    /// What to tell the owner when Core refuses an upload with `status`.
    static func uploadFailure(_ status: Int) -> String {
        switch status {
        case 404: coreTooOld
        case 400: unreadable
        case 415: unsupported
        case 413: tooLarge
        case 429: "Too many photos for now; try again later."
        case 503: "Photos are busy or unavailable on your Home Node; try again."
        case 507: "Photo storage on your Home Node is full."
        default: "The photo could not be uploaded."
        }
    }

    static func tooOld(_ uploadedAt: Date?, now: Date) -> Bool {
        uploadedAt.map { now.timeIntervalSince($0) > sendWithin } ?? false
    }

    /// Photos Core would no longer attach (uploaded over 23 h ago) fail in place.
    static func expired(_ chips: [PhotoChip], now: Date) -> [PhotoChip] {
        chips.map { chip in
            guard chip.state == .ready, tooOld(chip.uploadedAt, now: now) else { return chip }
            var failed = chip
            failed.state = .failed
            failed.error = tooOldToSend
            return failed
        }
    }

    /// Sending waits until every photo is uploaded; no photos is fine.
    static func allReady(_ chips: [PhotoChip]) -> Bool { chips.allSatisfy { $0.state == .ready } }

    /// Photos of a refused message go back ahead of any added since, while there is room;
    /// `dropped` are the rest, to delete on the Home Node.
    static func restore(_ taken: [PhotoChip], into chips: [PhotoChip]) -> (chips: [PhotoChip], dropped: [PhotoChip]) {
        let room = max(maxPhotos - chips.count, 0)
        return (Array(taken.prefix(room)) + chips, Array(taken.dropFirst(room)))
    }

    /// What to say when Core refused a new run outright (it stored nothing), or nil when the
    /// outcome is unknown. A 404 is a deleted conversation or a photo that is gone, already
    /// sent or too old.
    static func runRefusal(_ status: Int?) -> String? {
        switch status {
        case 400: "Your Home Node refused this message. Nothing was sent."
        case 404: "Your Home Node no longer has this conversation or one of these photos. Nothing was sent."
        default: nil
        }
    }

    /// Photo ids the messages carry that the conversation no longer has (deleted or expired).
    static func missing(_ messages: [ConversationMessage], photos: [PhotoMeta]) -> Set<String> {
        let live = Set(photos.map(\.key))
        return Set(messages.flatMap { $0.attachments ?? [] }.filter { !live.contains($0) })
    }

    /// How long a photo stays, for the full-screen view. Without metadata (a photo just sent)
    /// the general rule. Core reports a date for a kept photo too while no message carries it
    /// yet (upload + 24 h): then that date counts.
    static func expiryHint(_ meta: PhotoMeta?) -> String {
        if let meta, meta.saved, meta.expires_at == nil { return "Kept until you delete it." }
        guard let at = meta?.expires_at.flatMap(parseCoreDate) else { return "Photos are kept 90 days unless you keep them." }
        let day = at.formatted(.dateTime.day().month(.wide).year())
        return meta?.saved == true ? "Removed on \(day)." : "Removed on \(day) unless you keep it."
    }
}
