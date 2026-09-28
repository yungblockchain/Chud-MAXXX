import SwiftUI

/// Categories and items for one kind of content, loaded a category at a time and cached.
@MainActor
final class BrowserState: ObservableObject {
    let kind: ContentKind
    @Published var categories: [ContentCategory] = []
    @Published var selectedCategory: String? = nil
    @Published var items: [MediaItem] = []
    @Published var loading = false
    @Published var error: String? = nil
    private var cache: [String: [MediaItem]] = [:]

    init(kind: ContentKind) {
        self.kind = kind
    }

    func loadCategories(client: XtreamClient?) async {
        guard let client, categories.isEmpty else { return }
        loading = true
        do {
            categories = try await client.categories(kind)
            error = nil
            if selectedCategory == nil, let first = categories.first {
                await select(first.id, client: client)
            }
        } catch {
            self.error = "Couldn't load the categories. Check your connection and try again."
        }
        loading = false
    }

    func select(_ id: String, client: XtreamClient?) async {
        selectedCategory = id
        if let cached = cache[id] {
            items = cached
            return
        }
        guard let client else { return }
        loading = true
        items = []
        do {
            let loaded = try await client.items(kind, category: id)
            cache[id] = loaded
            if selectedCategory == id { items = loaded }
            error = nil
        } catch {
            self.error = "Couldn't load this category. Try again in a moment."
        }
        loading = false
    }
}


@MainActor
private struct CategoryColumn: View {
    @ObservedObject var state: BrowserState
    let client: XtreamClient?

    var body: some View {
        List(selection: Binding(
            get: { state.selectedCategory },
            set: { id in
                if let id { Task { await state.select(id, client: client) } }
            }
        )) {
            ForEach(state.categories) { category in
                Text(category.name)
                    .font(NeonFont.body(14))
                    .lineLimit(2)
                    .tag(category.id)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Neon.backgroundSoft.opacity(0.6))
        .frame(width: 240)
    }
}

// MARK: Live TV


@MainActor
struct LiveTVView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var state = BrowserState(kind: .live)
    @State private var query = ""

    private var shown: [MediaItem] {
        query.isEmpty ? state.items : state.items.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        HStack(spacing: 0) {
            CategoryColumn(state: state, client: model.client)
            VStack(alignment: .leading, spacing: 10) {
                NeonTitle(text: "Live TV")
                StatusText(state: state, empty: "No channels in this category.")
                List {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                        Button {
                            model.playLive(item, in: shown)
                        } label: {
                            ChannelRow(item: item, number: index + 1)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { FavoriteMenuItem(item: item) }
                    }
                }
                .scrollContentBackground(.hidden)
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
        }
        .searchable(text: $query, prompt: "Search channels in this category")
        .task { await state.loadCategories(client: model.client) }
    }
}


@MainActor
private struct ChannelRow: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    let number: Int
    @State private var hovering = false

    var body: some View {
        let now = Date()
        let programmes = model.epg[item.streamId] ?? []
        let current = programmes.first { $0.isOn(at: now) }
        let next = programmes.first { $0.start >= (current?.end ?? now) }
        HStack(spacing: 14) {
            Text("\(number)")
                .font(NeonFont.display(15))
                .foregroundColor(hovering ? Neon.onCyan : Neon.cyan)
                .frame(width: 44, alignment: .trailing)
            RemoteImage(url: item.icon, contentMode: .fit)
                .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name)
                    .font(NeonFont.body(15, bold: true))
                    .foregroundColor(hovering ? Neon.onCyan : Neon.text)
                    .lineLimit(1)
                if let current {
                    Text("Now: \(current.title)")
                        .font(NeonFont.body(13))
                        .foregroundColor(hovering ? Neon.onCyan.opacity(0.8) : Neon.textSecondary)
                        .lineLimit(1)
                    ProgressView(value: now.timeIntervalSince(current.start), total: current.end.timeIntervalSince(current.start))
                        .tint(hovering ? Neon.onCyan : Neon.cyan)
                }
                if let next {
                    Text("Next \(next.start.formatted(date: .omitted, time: .shortened)): \(next.title)")
                        .font(NeonFont.body(12))
                        .foregroundColor(hovering ? Neon.onCyan.opacity(0.7) : Neon.textMuted)
                        .lineLimit(1)
                }
            }
            Spacer()
            if model.isFavorite(item) {
                Image(systemName: "star.fill").foregroundColor(hovering ? Neon.onCyan : Neon.magenta)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .background(HudShape().fill(hovering ? Neon.cyan : Neon.surface.opacity(0.7)))
        .overlay(HudShape().stroke(Neon.cyan.opacity(hovering ? 1 : 0.15), lineWidth: 1))
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .task(id: item.streamId) { await model.loadEPG(for: item.streamId) }
    }
}

// MARK: Films and series


@MainActor
struct LibraryBrowser: View {
    @EnvironmentObject private var model: AppModel
    let kind: ContentKind
    @StateObject private var state: BrowserState
    @State private var query = ""
    @State private var selected: MediaItem? = nil

    init(kind: ContentKind) {
        self.kind = kind
        _state = StateObject(wrappedValue: BrowserState(kind: kind))
    }

    private var shown: [MediaItem] {
        query.isEmpty ? state.items : state.items.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        HStack(spacing: 0) {
            CategoryColumn(state: state, client: model.client)
            VStack(alignment: .leading, spacing: 10) {
                NeonTitle(text: kind == .movie ? "Films" : "Series")
                StatusText(state: state, empty: "Nothing in this category.")
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 18)], spacing: 22) {
                        ForEach(shown) { item in
                            PosterCard(item: item) { selected = item }
                                .contextMenu { FavoriteMenuItem(item: item) }
                        }
                    }
                    .padding(.vertical, 8)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
        }
        .searchable(text: $query, prompt: kind == .movie ? "Search films in this category" : "Search series in this category")
        .task { await state.loadCategories(client: model.client) }
        .sheet(item: $selected) { item in
            DetailsView(item: item)
                .environmentObject(model)
        }
    }
}


@MainActor
struct PosterCard: View {
    let item: MediaItem
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                // A 2:3 box that the poster fills and is cropped to.
                Color.clear
                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                    .overlay(RemoteImage(url: item.icon, contentMode: .fill))
                    .clipShape(HudShape(cut: 12))
                    .overlay(HudShape(cut: 12).stroke(Neon.cyan.opacity(hovering ? 1 : 0.2), lineWidth: hovering ? 2 : 1))
                    .shadow(color: Neon.cyan.opacity(hovering ? 0.5 : 0), radius: 12)
                Text(item.name)
                    .font(NeonFont.body(13, bold: true))
                    .foregroundColor(hovering ? Neon.cyan : Neon.text)
                    .lineLimit(2)
            }
            .scaleEffect(hovering ? 1.03 : 1)
            .animation(.easeOut(duration: 0.12), value: hovering)
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}


@MainActor
struct DetailsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let item: MediaItem
    @State private var film: VodInfo? = nil
    @State private var series: SeriesInfo? = nil
    @State private var season = ""
    @State private var loading = true

    var body: some View {
        ZStack(alignment: .topTrailing) {
            NeonBackdrop()
            RemoteImage(url: film?.backdrop ?? series?.backdrop, contentMode: .fill)
                .opacity(0.25)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    header
                    if item.kind == .series { episodes }
                }
                .padding(32)
            }
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
            }
            .buttonStyle(NeonButtonStyle())
            .keyboardShortcut(.cancelAction)
            .padding(16)
        }
        .frame(minWidth: 820, idealWidth: 900, minHeight: 600, idealHeight: 680)
        .task { await load() }
    }

    // Details come from either the film or the series lookup. These small helpers keep each
    // expression simple, which the Swift compiler needs to type-check them quickly.
    private var displayTitle: String {
        if let title = film?.title { return title }
        if let title = series?.title { return title }
        return item.name
    }

    private var posterURL: String? {
        if let poster = film?.poster { return poster }
        if let poster = series?.poster { return poster }
        return item.icon
    }

    private var plotText: String? {
        if let plot = film?.plot { return plot }
        return series?.plot
    }

    private var castText: String? {
        if let cast = film?.cast { return cast }
        return series?.cast
    }

    private var metaText: String {
        var parts: [String] = []
        if let year = film?.year {
            parts.append(year)
        } else if let year = series?.year {
            parts.append(year)
        }
        var rating: String? = film?.rating
        if rating == nil { rating = series?.rating }
        if rating == nil { rating = item.rating }
        if let rating {
            parts.append("Rated \(rating)")
        }
        if let duration = film?.duration {
            parts.append(duration)
        }
        if let genre = film?.genre {
            parts.append(genre)
        } else if let genre = series?.genre {
            parts.append(genre)
        }
        return parts.joined(separator: "   ")
    }

    private var header: some View {
        let title: String = displayTitle
        let meta: String = metaText
        return HStack(alignment: .top, spacing: 28) {
            RemoteImage(url: posterURL, contentMode: .fill)
                .frame(width: 200, height: 300)
                .clipShape(HudShape(cut: 14))
                .overlay(HudShape(cut: 14).stroke(Neon.cyan.opacity(0.5), lineWidth: 1))
            VStack(alignment: .leading, spacing: 14) {
                NeonTitle(text: title, size: 28)
                if !meta.isEmpty {
                    Text(meta)
                        .font(NeonFont.body(14, bold: true))
                        .foregroundColor(Neon.magenta)
                }
                if let plot = plotText {
                    Text(plot)
                        .font(NeonFont.body(15))
                        .foregroundColor(Neon.textSecondary)
                        .frame(maxWidth: 560, alignment: .leading)
                }
                if let director = film?.director {
                    Text("Director: \(director)").font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
                }
                if let cast = castText {
                    Text("Cast: \(cast)").font(NeonFont.body(13)).foregroundColor(Neon.textMuted).lineLimit(2)
                }
                HStack(spacing: 12) {
                    actions
                    Button(model.isFavorite(item) ? "Remove from favourites" : "Add to favourites") {
                        model.toggleFavorite(item)
                    }
                    .buttonStyle(NeonButtonStyle())
                }
                .padding(.top, 6)
                if loading {
                    Text("Loading details…").font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
                }
            }
        }
    }

    @ViewBuilder
    private var actions: some View {
        if item.kind == .movie {
            let resume = model.resumePosition(for: "movie-\(item.streamId)")
            if resume > 0 {
                Button("Resume from \(clock(resume))") { playFilm(fromStart: false) }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                Button("Start over") { playFilm(fromStart: true) }
                    .buttonStyle(NeonButtonStyle())
            } else {
                Button("Play") { playFilm(fromStart: false) }
                    .buttonStyle(NeonButtonStyle(prominent: true))
            }
        } else if let last = model.lastEpisode(forSeries: item.streamId) {
            Button("Continue S\(last.season) E\(last.number ?? "?")") {
                model.playEpisode(last, of: item, fromStart: false)
                dismiss()
            }
            .buttonStyle(NeonButtonStyle(prominent: true))
        } else if let first = series?.seasons.first?.episodes.first {
            Button("Play first episode") {
                model.playEpisode(first, of: item, fromStart: false)
                dismiss()
            }
            .buttonStyle(NeonButtonStyle(prominent: true))
        }
    }

    @ViewBuilder
    private var episodes: some View {
        if let seasons = series?.seasons, !seasons.isEmpty {
            Picker("Season", selection: $season) {
                ForEach(seasons) { item in
                    Text("Season \(item.key)").tag(item.key)
                }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 640)
            let current = seasons.first { $0.key == season } ?? seasons[0]
            VStack(spacing: 8) {
                ForEach(current.episodes) { episode in
                    EpisodeRow(
                        episode: episode,
                        resume: model.resumePosition(for: "episode-\(episode.id)"),
                        lastWatched: model.lastEpisode(forSeries: item.streamId)?.id == episode.id
                    ) {
                        model.playEpisode(episode, of: item, fromStart: false)
                        dismiss()
                    }
                }
            }
        } else if !loading {
            Text("The provider hasn't listed any episodes for this series.")
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textMuted)
        }
    }

    private func load() async {
        guard let client = model.client else { return }
        if item.kind == .movie {
            film = try? await client.vodInfo(item.streamId)
        } else {
            series = try? await client.seriesInfo(item.streamId)
            let saved: String? = model.lastEpisode(forSeries: item.streamId)?.season
            let seasons: [Season] = series?.seasons ?? []
            if let match = seasons.first(where: { $0.key == saved }) {
                season = match.key
            } else {
                season = seasons.first?.key ?? ""
            }
        }
        loading = false
    }

    private func playFilm(fromStart: Bool) {
        model.playMovie(item, containerExtension: film?.containerExtension, fromStart: fromStart)
        dismiss()
    }
}


@MainActor
private struct EpisodeRow: View {
    let episode: Episode
    let resume: Double
    let lastWatched: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 16) {
                Text("E\(episode.number ?? "?")")
                    .font(NeonFont.display(16))
                    .foregroundColor(hovering ? Neon.onCyan : Neon.cyan)
                    .frame(width: 56, alignment: .leading)
                VStack(alignment: .leading, spacing: 4) {
                    Text(episode.title)
                        .font(NeonFont.body(15, bold: true))
                        .foregroundColor(hovering ? Neon.onCyan : Neon.text)
                    if let plot = episode.plot {
                        Text(plot)
                            .font(NeonFont.body(13))
                            .foregroundColor(hovering ? Neon.onCyan.opacity(0.8) : Neon.textSecondary)
                            .lineLimit(2)
                    }
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    if lastWatched {
                        Text("Last watched").font(NeonFont.body(12, bold: true)).foregroundColor(hovering ? Neon.onCyan : Neon.magenta)
                    }
                    if resume > 0 {
                        Text("Resume \(clock(resume))").font(NeonFont.body(12)).foregroundColor(hovering ? Neon.onCyan : Neon.textSecondary)
                    }
                    if let duration = episode.duration {
                        Text(duration).font(NeonFont.body(12)).foregroundColor(hovering ? Neon.onCyan : Neon.textMuted)
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HudShape().fill(hovering ? Neon.cyan : Neon.surface.opacity(0.8)))
            .overlay(HudShape().stroke(Neon.cyan.opacity(hovering ? 1 : 0.15), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}

// MARK: Favourites


@MainActor
struct FavoritesView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selected: MediaItem? = nil

    var body: some View {
        let live = model.favorites.filter { $0.kind == .live }
        let onDemand = model.favorites.filter { $0.kind != .live }
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                NeonTitle(text: "Favourites")
                if model.favorites.isEmpty {
                    Text("Right-click any channel, film or series and choose Add to favourites.")
                        .font(NeonFont.body(15))
                        .foregroundColor(Neon.textSecondary)
                }
                if !live.isEmpty {
                    Text("Channels").font(NeonFont.display(16)).foregroundColor(Neon.magenta)
                    ForEach(Array(live.enumerated()), id: \.element.id) { index, item in
                        Button {
                            model.playLive(item, in: live)
                        } label: {
                            ChannelRow(item: item, number: index + 1)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { FavoriteMenuItem(item: item) }
                    }
                }
                if !onDemand.isEmpty {
                    Text("Films and series").font(NeonFont.display(16)).foregroundColor(Neon.magenta)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 18)], spacing: 22) {
                        ForEach(onDemand) { item in
                            PosterCard(item: item) { selected = item }
                                .contextMenu { FavoriteMenuItem(item: item) }
                        }
                    }
                }
            }
            .padding(28)
        }
        .sheet(item: $selected) { item in
            DetailsView(item: item).environmentObject(model)
        }
    }
}

// MARK: Settings


@MainActor
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                NeonTitle(text: "Settings")
                VStack(alignment: .leading, spacing: 10) {
                    Text("Account").font(NeonFont.display(16)).foregroundColor(Neon.magenta)
                    if let credentials = model.credentials {
                        setting("Login", "\(credentials.username) on \(credentials.server)")
                    }
                    if let account = model.account {
                        setting("Status", statusText(account))
                        setting("Expires", expiryText(account.expiry))
                        if let active = account.activeConnections, let max = account.maxConnections {
                            setting("Connections", "\(active) of \(max) in use")
                        }
                    }
                    Button("Sign out") { model.signOut() }
                        .buttonStyle(NeonButtonStyle())
                }
                .padding(18)
                .neonPanel()
                .frame(maxWidth: 640, alignment: .leading)

                VStack(alignment: .leading, spacing: 10) {
                    Text("Playback").font(NeonFont.display(16)).foregroundColor(Neon.magenta)
                    Toggle("Play live TV in the built-in player (HLS)", isOn: $model.preferHLS)
                        .toggleStyle(.switch)
                        .font(NeonFont.body(14))
                    Text("Films in MKV or AVI, and live channels when this is off, open in VLC because macOS's own player can't decode them. VLC is free from videolan.org.")
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textMuted)
                        .frame(maxWidth: 560, alignment: .leading)
                    Text("In the player: up and down arrows change channel, Esc closes, Space pauses, left and right skip.")
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textMuted)
                }
                .padding(18)
                .neonPanel()
                .frame(maxWidth: 640, alignment: .leading)
            }
            .padding(28)
        }
    }

    private func statusText(_ account: AccountInfo) -> String {
        let status: String = account.status ?? "Active"
        return account.isTrial ? "\(status) (trial)" : status
    }

    private func expiryText(_ expiry: Date?) -> String {
        guard let expiry else { return "Never" }
        let date: String = expiry.formatted(date: .abbreviated, time: .omitted)
        let days: Int = Calendar.current.dateComponents([.day], from: Date(), to: expiry).day ?? 0
        if days < 0 { return "\(date) (expired)" }
        return "\(date) (\(days) days left)"
    }

    private func setting(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label).font(NeonFont.body(14)).foregroundColor(Neon.textMuted).frame(width: 110, alignment: .leading)
            Text(value).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.text)
        }
    }
}

// MARK: Shared bits


@MainActor
struct FavoriteMenuItem: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem

    var body: some View {
        Button(model.isFavorite(item) ? "Remove from favourites" : "Add to favourites") {
            model.toggleFavorite(item)
        }
    }
}


@MainActor
private struct StatusText: View {
    @ObservedObject var state: BrowserState
    let empty: String

    var body: some View {
        Group {
            if let error = state.error {
                Text(error).foregroundColor(Neon.danger)
            } else if state.loading {
                Text("Loading…").foregroundColor(Neon.textSecondary)
            } else if state.items.isEmpty && state.selectedCategory != nil {
                Text(empty).foregroundColor(Neon.textMuted)
            }
        }
        .font(NeonFont.body(13))
    }
}

/// AsyncImage with a neutral placeholder, for logos and posters that may be missing or broken.
@MainActor
struct RemoteImage: View {
    let url: String?
    let contentMode: ContentMode

    var body: some View {
        AsyncImage(url: url.flatMap { URL(string: $0) }) { phase in
            switch phase {
            case .success(let image):
                image.resizable().aspectRatio(contentMode: contentMode)
            default:
                ZStack {
                    Neon.surfaceRaised
                    Image(systemName: "sparkles.tv").foregroundColor(Neon.textMuted)
                }
            }
        }
    }
}

func clock(_ seconds: Double) -> String {
    let total = Int(seconds)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let secs = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, secs)
        : String(format: "%d:%02d", minutes, secs)
}
