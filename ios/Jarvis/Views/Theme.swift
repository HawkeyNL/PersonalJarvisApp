import CoreText
import SwiftUI
import UIKit

// Jarvis design language, shared with the desktop redesign. Dark only.
// Colors mirror the desktop tokens; every status color is paired with text.
enum JarvisTheme {
    static let background = Color(hex: 0x01090A)
    static let panel = Color(hex: 0x05241C)
    static let accent = Color("AccentColor")

    // Text, brightest first.
    static let text1 = Color(hex: 0xF4FFFA)
    static let text2 = Color(hex: 0xE6F4EE)
    static let text3 = Color(hex: 0xD3E8DF)
    static let text4 = Color(hex: 0xC4DCD2)
    static let text5 = Color(hex: 0xA9C4B9)
    static let text6 = Color(hex: 0x8FB3A3)
    static let idle = Color(hex: 0x8995A0)
    static let warn = Color(hex: 0xF5B84A)
    static let danger = Color(hex: 0xF87171)

    static let tile = Color(red: 8 / 255.0, green: 40 / 255.0, blue: 32 / 255.0).opacity(0.6)

    static let card = LinearGradient(
        colors: [Color(red: 10 / 255.0, green: 52 / 255.0, blue: 40 / 255.0).opacity(0.7),
                 Color(red: 4 / 255.0, green: 28 / 255.0, blue: 22 / 255.0).opacity(0.8)],
        startPoint: .top, endPoint: .bottom)
    static let panelFill = LinearGradient(
        colors: [Color(red: 5 / 255.0, green: 36 / 255.0, blue: 28 / 255.0).opacity(0.9),
                 Color(red: 3 / 255.0, green: 22 / 255.0, blue: 18 / 255.0).opacity(0.92)],
        startPoint: .top, endPoint: .bottom)
    static let control = Color(red: 4 / 255.0, green: 30 / 255.0, blue: 24 / 255.0).opacity(0.7)
}

extension Color {
    /// sRGB color from a 0xRRGGBB literal.
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

/// Exo 2 (variable, bundled) for body text, Rajdhani for the wordmark and
/// titles. Every face falls back to the matching system font if it is not
/// registered, so a missing font never hides text.
enum JarvisFont {
    /// `weight` is the CSS weight (300–600) on Exo 2's `wght` axis.
    static func body(_ size: CGFloat, _ weight: CGFloat = 400) -> Font {
        exo(size, weight: weight, italic: false)
    }

    static func italic(_ size: CGFloat) -> Font {
        exo(size, weight: 300, italic: true)
    }

    static func display(_ size: CGFloat, semibold: Bool = true) -> Font {
        Font.custom(semibold ? "Rajdhani-SemiBold" : "Rajdhani-Medium", size: size)
    }

    private static func exo(_ size: CGFloat, weight: CGFloat, italic: Bool) -> Font {
        let name = italic ? "Exo2-Italic" : "Exo2-Regular"
        let variation = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)
        let wghtAxis = 0x7767_6874 // 'wght'
        let descriptor = UIFontDescriptor(fontAttributes: [.name: name, variation: [wghtAxis: weight]])
        let font = UIFont(descriptor: descriptor, size: size)
        guard font.fontName.hasPrefix("Exo2") else {
            let fallback: Font = .system(size: size, weight: systemWeight(weight))
            return italic ? fallback.italic() : fallback
        }
        return Font(UIFontMetrics.default.scaledFont(for: font) as CTFont)
    }

    private static func systemWeight(_ weight: CGFloat) -> Font.Weight {
        if weight >= 600 { return .semibold }
        if weight >= 500 { return .medium }
        if weight <= 300 { return .light }
        return .regular
    }
}

extension Tone {
    var color: Color {
        switch self {
        case .ok: JarvisTheme.accent
        case .idle: JarvisTheme.idle
        case .warn: JarvisTheme.warn
        case .error: JarvisTheme.danger
        }
    }

    var labelColor: Color {
        self == .idle ? Color(hex: 0x9AA7AD) : color
    }
}
