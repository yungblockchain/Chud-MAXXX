import SwiftUI

@main
@MainActor
struct ChudStreamsApp: App {
    @StateObject private var model = AppModel()

    init() {
        NeonFont.registerBundledFonts()
    }

    var body: some Scene {
        WindowGroup("CHUD STREAMS") {
            RootView()
                .environmentObject(model)
                .frame(minWidth: 1000, minHeight: 640)
                .preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
    }
}

enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case live = "Live TV"
    case guide = "Guide"
    case movies = "Films"
    case series = "Series"
    case favorites = "Favourites"
    case markets = "Markets"
    case settings = "Settings"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .live: return "tv"
        case .guide: return "calendar"
        case .movies: return "film"
        case .series: return "square.stack.3d.up"
        case .favorites: return "star"
        case .markets: return "chart.line.uptrend.xyaxis"
        case .settings: return "gearshape"
        }
    }
}


@MainActor
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var section: AppSection? = .live

    var body: some View {
        ZStack {
            NeonBackdrop()
            if model.credentials == nil {
                SignInView()
            } else {
                NavigationSplitView {
                    Sidebar(selection: $section)
                        .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 260)
                } detail: {
                    ZStack {
                        NeonBackdrop()
                        switch section ?? .live {
                        case .live: LiveTVView()
                        case .guide: GuideView()
                        case .movies: LibraryBrowser(kind: .movie)
                        case .series: LibraryBrowser(kind: .series)
                        case .favorites: FavoritesView()
                        case .markets: MarketsView()
                        case .settings: SettingsView()
                        }
                    }
                }
                .task { await model.refreshAccount() }
            }
            if let request = model.nowPlaying {
                PlayerScreen(request: request)
                    .transition(.opacity)
                    .zIndex(1)
            }
            if let notice = model.notice {
                NoticeBanner(text: notice) { model.notice = nil }
                    .zIndex(2)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.nowPlaying?.id)
    }
}


@MainActor
private struct Sidebar: View {
    @Binding var selection: AppSection?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 10) {
                if let badge = BrandImage.badge {
                    Image(nsImage: badge)
                        .resizable()
                        .frame(width: 44, height: 44)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text("CHUD").font(NeonFont.display(18)).foregroundColor(Neon.cyan)
                    Text("STREAMS").font(NeonFont.display(12)).foregroundColor(Neon.magenta)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 24)

            List(selection: $selection) {
                ForEach(AppSection.allCases) { item in
                    Label(item.rawValue, systemImage: item.symbol)
                        .font(NeonFont.body(15, bold: true))
                        .tag(item)
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)

            Text("チャッド・ストリームズ")
                .font(.system(size: 11))
                .foregroundColor(Neon.magenta.opacity(0.8))
                .padding(.horizontal, 14)
                .padding(.bottom, 14)
        }
        .background(Neon.backgroundSoft.opacity(0.92))
    }
}


@MainActor
private struct NoticeBanner: View {
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        VStack {
            Spacer()
            HStack(spacing: 14) {
                Text(text)
                    .font(NeonFont.body(14))
                    .foregroundColor(Neon.text)
                Button("OK", action: onDismiss)
                    .buttonStyle(NeonButtonStyle())
            }
            .padding(14)
            .neonPanel(highlighted: true)
            .padding(.bottom, 24)
        }
        .task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            onDismiss()
        }
    }
}


@MainActor
struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        HStack(alignment: .center, spacing: 56) {
            VStack(alignment: .leading, spacing: 18) {
                if let badge = BrandImage.badge {
                    Image(nsImage: badge).resizable().frame(width: 150, height: 150)
                }
                NeonTitle(text: "CHUD STREAMS", size: 38)
                Text("Sign in with your Xtream account. Your provider gave you a server address, a username and a password, or paste the whole M3U link into the server field and the rest is read from it.")
                    .font(NeonFont.body(16))
                    .foregroundColor(Neon.textSecondary)
                    .frame(maxWidth: 420, alignment: .leading)
            }
            VStack(alignment: .leading, spacing: 14) {
                field("Server", text: $server, prompt: "http://example.com:8080")
                field("Username", text: $username, prompt: "")
                VStack(alignment: .leading, spacing: 6) {
                    Text("Password").font(NeonFont.body(13, bold: true)).foregroundColor(Neon.textSecondary)
                    SecureField("", text: $password)
                        .textFieldStyle(.plain)
                        .padding(10)
                        .neonPanel()
                        .onSubmit(signIn)
                }
                HStack(spacing: 12) {
                    Button(working ? "Checking…" : "Sign in", action: signIn)
                        .buttonStyle(NeonButtonStyle(prominent: true))
                        .disabled(working)
                        .keyboardShortcut(.defaultAction)
                }
                if let error {
                    Text(error)
                        .font(NeonFont.body(14))
                        .foregroundColor(Neon.danger)
                        .frame(maxWidth: 380, alignment: .leading)
                }
            }
            .frame(width: 380)
        }
        .padding(48)
    }

    private func field(_ label: String, text: Binding<String>, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(NeonFont.body(13, bold: true)).foregroundColor(Neon.textSecondary)
            TextField(prompt, text: text)
                .textFieldStyle(.plain)
                .font(NeonFont.body(15))
                .padding(10)
                .neonPanel()
                .onSubmit(signIn)
        }
    }

    private func signIn() {
        guard !working else { return }
        working = true
        error = nil
        Task {
            do {
                try await model.signIn(server: server, username: username, password: password)
            } catch {
                self.error = error.localizedDescription
            }
            working = false
        }
    }
}
