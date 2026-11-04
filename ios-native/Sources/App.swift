import SwiftUI

@main
struct MySchiessbuchApp: App {
    // "-demo" (used for test screenshots) shows sample data kept apart from the real data.
    @State private var store = Store(demo: ProcessInfo.processInfo.arguments.contains("-demo"))
    @AppStorage("lang") private var lang: String = Locale.current.language.languageCode?.identifier == "de" ? "de" : "en"

    var body: some Scene {
        WindowGroup {
            MainView()
                .environment(store)
                .environment(\.lang, lang)
                .environment(\.locale, Tx(lang).locale)
                .tint(Color.accentColor)
        }
    }
}
