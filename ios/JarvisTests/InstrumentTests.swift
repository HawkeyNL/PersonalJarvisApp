import UIKit
import XCTest
@testable import Jarvis

final class InstrumentTests: XCTestCase {
    private func components(_ color: UIColor, _ style: UIUserInterfaceStyle) -> [CGFloat] {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.resolvedColor(with: UITraitCollection(userInterfaceStyle: style)).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return [red, green, blue, alpha]
    }

    private func expected(_ rgba: UInt32) -> [CGFloat] {
        [UInt32(24), 16, 8, 0].map { (shift: UInt32) -> CGFloat in CGFloat((rgba >> shift) & 0xFF) / 255 }
    }

    /// Every token resolves to its own light value in light and dark value in dark,
    /// and keeps the CSS name.
    func testEveryTokenResolvesLightAndDark() {
        XCTAssertEqual(JV.Token.allCases.count, 34)
        for token in JV.Token.allCases {
            let kebab = token.rawValue.split(separator: "-").enumerated()
                .map { $0.offset == 0 ? String($0.element) : $0.element.capitalized }.joined()
            XCTAssertEqual(kebab, "\(token)", token.rawValue)
            for (style, rgba) in [(UIUserInterfaceStyle.light, token.rgba.light), (.dark, token.rgba.dark)] {
                zip(components(token.uiColor, style), expected(rgba)).forEach {
                    XCTAssertEqual($0, $1, accuracy: 0.001, "\(token.rawValue) \(style.rawValue)")
                }
            }
            // Only white-on-accent is the same in both appearances.
            if token != .textOnAccent { XCTAssertNotEqual(token.rgba.light, token.rgba.dark, token.rawValue) }
        }
    }

    private func luminance(_ rgba: UInt32) -> Double {
        func linear(_ shift: UInt32) -> Double {
            let value = Double((rgba >> shift) & 0xFF) / 255
            return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        let red: Double = 0.2126 * linear(24)
        let green: Double = 0.7152 * linear(16)
        let blue: Double = 0.0722 * linear(8)
        return red + green + blue
    }

    private func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let (x, y) = (luminance(a), luminance(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    /// The text pairs the app uses meet WCAG AA in both appearances.
    func testTextPairsMeetWCAGAA() {
        let text: [(JV.Token, JV.Token)] = [
            (.textPrimary, .bgPage), (.textPrimary, .bgSunken), (.textSecondary, .bgPage),
            (.textSecondary, .bgSurface), (.textSecondary, .bgSunken), (.accentText, .bgPage),
            (.accentText, .bgSurface), (.accentText, .accentSubtle), (.accentText, .bgSelected),
            (.textOnAccent, .accentSolid), (.textOnAccent, .accentSolidHover), (.textOnAccent, .dangerSolid),
            (.textOnAccent, .dangerSolidHover), (.dangerText, .bgSurface), (.dangerText, .bgPage),
            (.positiveText, .bgSurface), (.negativeText, .bgSurface), (.negativeText, .bgSunken),
        ]
        for (fg, bg) in text {
            XCTAssertGreaterThanOrEqual(contrast(fg.rgba.light, bg.rgba.light), 4.5, "\(fg.rawValue) on \(bg.rawValue) light")
            XCTAssertGreaterThanOrEqual(contrast(fg.rgba.dark, bg.rgba.dark), 4.5, "\(fg.rawValue) on \(bg.rawValue) dark")
        }
        // Input borders need 3:1 against the surface.
        XCTAssertGreaterThanOrEqual(contrast(JV.Token.borderStrong.rgba.light, JV.Token.bgSurface.rgba.light), 3)
        XCTAssertGreaterThanOrEqual(contrast(JV.Token.borderStrong.rgba.dark, JV.Token.bgSurface.rgba.dark), 3)
    }

    func testBundledPlexFacesRegister() {
        let faces = ["IBMPlexSans", "IBMPlexSans-Medm", "IBMPlexSans-SmBld", "IBMPlexMono", "IBMPlexMono-Medm"]
        XCTAssertEqual(JV.registeredFaces, Set(faces))
    }

    /// Stored raw values are persisted; changing them would reset the owner's choice.
    func testAppearanceValuesAndStyles() {
        XCTAssertEqual(Appearance.storageKey, "jarvis.appearance")
        XCTAssertEqual(Appearance.allCases.map(\.rawValue), ["system", "light", "dark"])
        XCTAssertEqual(Appearance.allCases.map(\.style), [.unspecified, .light, .dark])
    }
}
