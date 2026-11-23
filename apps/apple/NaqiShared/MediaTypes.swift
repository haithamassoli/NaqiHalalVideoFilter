import Foundation
import UniformTypeIdentifiers

enum MediaTypes {
    static let webM = UTType(importedAs: "org.webmproject.webm", conformingTo: .movie)
    static let importable: [UTType] = [.movie, .audio, webM]

    static func isWebM(_ url: URL) -> Bool {
        url.pathExtension.lowercased() == "webm"
    }
}
