import AppKit
import Foundation
import Security

/// What the player should show. Live requests carry the channel list so up/down can flip channels.
struct PlaybackRequest: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let subtitle: String?
    let url: URL
    /// Where to save the position (films and episodes); nil for live TV.
    let resumeKey: String?
    let startAt: Double
    let live: Bool
    let channelList: [MediaItem]
    let channelIndex: Int?
    let streamId: Int?
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var credentials: XtreamCredentials? = nil
    @Published private(set) var account: AccountInfo? = nil
    @Published private(set) var favorites: [MediaItem] = []
    @Published private(set) var epg: [Int: [Programme]] = [:]
    /// Full listings for the timeline guide, by stream id. Absent means not loaded yet.
    @Published private(set) var listings: [Int: [Programme]] = [:]
    @Published var nowPlaying: PlaybackRequest? = nil
    @Published var notice: String? = nil
    @Published var preferHLS: Bool {
        didSet { defaults.set(preferHLS, forKey: Keys.preferHLS) }
    }

    private let defaults = UserDefaults.standard
    private var epgFetchedAt: [Int: Date] = [:]
    private var epgInFlight = Set<Int>()
    private var listingFetchedAt: [Int: Date] = [:]
    private var listingInFlight = Set<Int>()
    private var listingOrder: [Int] = []

    /// Formats the built-in macOS player handles; anything else is offered to VLC.
    static let nativeFormats: Set<String> = ["mp4", "m4v", "mov", "m3u8"]

    var client: XtreamClient? { credentials.map { XtreamClient(credentials: $0) } }

    init() {
        // Read through a local: `self` can't be used until every stored property has a value.
        let store = UserDefaults.standard
        preferHLS = (store.object(forKey: Keys.preferHLS) as? Bool) ?? true
        if let server = store.string(forKey: Keys.server),
           let username = store.string(forKey: Keys.username),
           let password = Keychain.load(account: "\(username)@\(server)") {
            credentials = XtreamCredentials(server: server, username: username, password: password)
        }
        if let data = store.data(forKey: Keys.favorites),
           let saved = try? JSONDecoder().decode([MediaItem].self, from: data) {
            favorites = saved
        }
    }

    // MARK: Account

    func signIn(server rawServer: String, username rawUser: String, password rawPassword: String) async throws {
        let fromLink = XtreamClient.credentials(fromLink: rawServer)
        guard let server = fromLink?.server ?? XtreamClient.normalizeServer(rawServer) else { throw XtreamError.badServer }
        let username = fromLink?.username ?? rawUser.trimmingCharacters(in: .whitespacesAndNewlines)
        let password = fromLink?.password ?? rawPassword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !username.isEmpty, !password.isEmpty else { throw XtreamError.missingLogin }
        let candidate = XtreamCredentials(server: server, username: username, password: password)
        let info = try await XtreamClient(credentials: candidate).accountInfo()
        guard info.isActive else { throw XtreamError.inactive(info.status ?? "inactive") }
        defaults.set(server, forKey: Keys.server)
        defaults.set(username, forKey: Keys.username)
        Keychain.save(password, account: "\(username)@\(server)")
        account = info
        credentials = candidate
    }

    func refreshAccount() async {
        guard let client else { return }
        account = try? await client.accountInfo()
    }

    func signOut() {
        if let current = credentials {
            Keychain.delete(account: "\(current.username)@\(current.server)")
        }
        defaults.removeObject(forKey: Keys.server)
        defaults.removeObject(forKey: Keys.username)
        nowPlaying = nil
        account = nil
        epg = [:]
        credentials = nil
    }

    // MARK: Favourites

    func isFavorite(_ item: MediaItem) -> Bool { favorites.contains { $0.id == item.id } }

    func toggleFavorite(_ item: MediaItem) {
        if isFavorite(item) {
            favorites.removeAll { $0.id == item.id }
        } else {
            favorites.append(item)
        }
        if let data = try? JSONEncoder().encode(favorites) { defaults.set(data, forKey: Keys.favorites) }
    }

    // MARK: Resume

    /// Saved position in seconds, or 0 if there's nothing worth resuming.
    func resumePosition(for key: String) -> Double {
        defaults.double(forKey: Keys.resumePrefix + key)
    }

    func saveResume(key: String, seconds: Double, duration: Double) {
        // Forget the position near the start or once it's effectively finished.
        if seconds < 30 || seconds > duration - 60 {
            defaults.removeObject(forKey: Keys.resumePrefix + key)
        } else {
            defaults.set(seconds, forKey: Keys.resumePrefix + key)
        }
    }

    func lastEpisode(forSeries seriesId: Int) -> Episode? {
        defaults.data(forKey: Keys.lastEpisodePrefix + String(seriesId))
            .flatMap { try? JSONDecoder().decode(Episode.self, from: $0) }
    }

    // MARK: Programme guide

    func loadEPG(for streamId: Int) async {
        if let fetched = epgFetchedAt[streamId], Date().timeIntervalSince(fetched) < 600,
           epg[streamId]?.first.map({ $0.end > Date() }) ?? true {
            return
        }
        guard let client, !epgInFlight.contains(streamId) else { return }
        epgInFlight.insert(streamId)
        defer { epgInFlight.remove(streamId) }
        let listings = (try? await client.shortEPG(streamId: streamId)) ?? []
        epgFetchedAt[streamId] = Date()
        epg[streamId] = listings.filter { $0.end > Date() }
    }

    /// Loads one channel's full listing for the guide as its row scrolls into view. Cached for
    /// 30 minutes; only the 300 most recently viewed channels are kept, to stay light on memory.
    func loadListing(for streamId: Int) async {
        if let fetched = listingFetchedAt[streamId], Date().timeIntervalSince(fetched) < 1800 { return }
        guard let client, !listingInFlight.contains(streamId) else { return }
        listingInFlight.insert(streamId)
        defer { listingInFlight.remove(streamId) }
        // A cancelled load (row scrolled away) isn't recorded, so it's retried next time.
        guard let all = try? await client.fullEPG(streamId: streamId) else { return }
        let now = Date()
        listings[streamId] = all.filter {
            $0.end > now.addingTimeInterval(-26 * 3600) && $0.start < now.addingTimeInterval(26 * 3600)
        }
        listingFetchedAt[streamId] = now
        listingOrder.removeAll { $0 == streamId }
        listingOrder.append(streamId)
        while listingOrder.count > 300 {
            let oldest = listingOrder.removeFirst()
            listingFetchedAt[oldest] = nil
            listings[oldest] = nil
        }
    }

    // MARK: Playback

    /// Replays an archived programme. Catch-up streams are MPEG-TS, which only VLC plays on a Mac.
    func playCatchUp(_ programme: Programme, on channel: MediaItem) {
        guard let client, let url = client.catchUpURL(streamId: channel.streamId, programme: programme) else {
            notice = "This programme can't be replayed: the provider didn't send its start time."
            return
        }
        openExternally(url)
    }

    func playLive(_ item: MediaItem, in list: [MediaItem]) {
        guard let client else { return }
        let formats = account?.allowedFormats ?? []
        let hls = preferHLS && (formats.isEmpty || formats.contains("m3u8"))
        guard let url = client.liveURL(item, hls: hls) else { return }
        guard hls else {
            openExternally(url)
            return
        }
        nowPlaying = PlaybackRequest(
            title: item.name,
            subtitle: nil,
            url: url,
            resumeKey: nil,
            startAt: 0,
            live: true,
            channelList: list,
            channelIndex: list.firstIndex(of: item),
            streamId: item.streamId
        )
        Task { await loadEPG(for: item.streamId) }
    }

    /// Up/down in the player: step through the list the channel was opened from.
    func zap(_ step: Int) {
        guard let current = nowPlaying, current.live, let index = current.channelIndex else { return }
        let count = current.channelList.count
        guard count > 1 else { return }
        let next = ((index + step) % count + count) % count
        playLive(current.channelList[next], in: current.channelList)
    }

    func playMovie(_ item: MediaItem, containerExtension: String?, fromStart: Bool) {
        guard let client, let url = client.movieURL(item, containerExtension: containerExtension) else { return }
        let key = "movie-\(item.streamId)"
        play(url: url, title: item.name, subtitle: nil, resumeKey: key, fromStart: fromStart)
    }

    func playEpisode(_ episode: Episode, of series: MediaItem, fromStart: Bool) {
        guard let client, let url = client.episodeURL(episode) else { return }
        if let data = try? JSONEncoder().encode(episode) {
            defaults.set(data, forKey: Keys.lastEpisodePrefix + String(series.streamId))
        }
        let label = "S\(episode.season) E\(episode.number ?? "?")"
        play(url: url, title: episode.title, subtitle: "\(series.name)  \(label)", resumeKey: "episode-\(episode.id)", fromStart: fromStart)
    }

    func stop() {
        nowPlaying = nil
    }

    private func play(url: URL, title: String, subtitle: String?, resumeKey: String, fromStart: Bool) {
        let ext = url.pathExtension.lowercased()
        guard AppModel.nativeFormats.contains(ext) else {
            openExternally(url)
            return
        }
        nowPlaying = PlaybackRequest(
            title: title,
            subtitle: subtitle,
            url: url,
            resumeKey: resumeKey,
            startAt: fromStart ? 0 : resumePosition(for: resumeKey),
            live: false,
            channelList: [],
            channelIndex: nil,
            streamId: nil
        )
    }

    /// Hands formats the macOS player can't decode (MKV, AVI, raw MPEG-TS) to VLC if it's installed.
    func openExternally(_ url: URL) {
        let workspace = NSWorkspace.shared
        if let vlc = workspace.urlForApplication(withBundleIdentifier: "org.videolan.vlc") {
            workspace.open([url], withApplicationAt: vlc, configuration: NSWorkspace.OpenConfiguration()) { _, _ in }
            notice = "Opened in VLC: this format needs VLC on a Mac."
        } else {
            notice = "This video's format (\(url.pathExtension.uppercased())) needs VLC. Install it free from videolan.org, then try again."
            if let site = URL(string: "https://www.videolan.org/vlc/") { workspace.open(site) }
        }
    }

    private enum Keys {
        static let server = "server"
        static let username = "username"
        static let favorites = "favorites"
        static let preferHLS = "preferHLS"
        static let resumePrefix = "resume."
        static let lastEpisodePrefix = "lastEpisode."
    }
}

/// Stores the provider password in the macOS Keychain rather than in plain preferences.
enum Keychain {
    private static let service = "app.chudstreams.mac"

    static func save(_ password: String, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = Data(password.utf8)
        SecItemAdd(item as CFDictionary, nil)
    }

    static func load(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
