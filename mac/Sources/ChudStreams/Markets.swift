import AppKit
import SwiftUI

// Markets: a read-only memecoin DEX tracker using DEX Screener's public API (no key needed).
// Limits: 60 requests a minute for boosts and profiles, 300 for pairs, tokens and search.
// Pump.fun tokens come from the same data (Solana tokens on pump.fun's exchanges, or mints
// ending in "pump"), since pump.fun and GMGN don't publish a documented public API.
// Nothing here trades, signs or touches a wallet.

struct SocialLink: Hashable {
    let label: String
    let url: URL
}

struct MarketPair: Identifiable, Hashable {
    let chainId: String
    let dexId: String
    let pairAddress: String
    let url: String?
    let baseAddress: String
    let baseName: String
    let baseSymbol: String
    let priceUsd: Double?
    let change5m: Double?
    let change1h: Double?
    let change6h: Double?
    let change24h: Double?
    let volume24h: Double?
    let liquidityUsd: Double?
    let marketCap: Double?
    let fdv: Double?
    let buys24h: Int?
    let sells24h: Int?
    let createdAt: Date?
    var imageUrl: String?
    var boosts: Int?
    var description: String?
    var links: [SocialLink]

    var id: String { "\(chainId):\(baseAddress.lowercased())" }
    var watchKey: String { "\(chainId):\(baseAddress)" }
    var isPumpFun: Bool { chainId == "solana" && (dexId.lowercased().contains("pump") || baseAddress.hasSuffix("pump")) }

    // Non-optional values for sorting table columns (missing values sort last).
    var sortPrice: Double { priceUsd ?? -1 }
    var sort5m: Double { change5m ?? -.greatestFiniteMagnitude }
    var sort1h: Double { change1h ?? -.greatestFiniteMagnitude }
    var sort24h: Double { change24h ?? -.greatestFiniteMagnitude }
    var sortMarketCap: Double { marketCap ?? fdv ?? -1 }
    var sortLiquidity: Double { liquidityUsd ?? -1 }
    var sortVolume: Double { volume24h ?? -1 }
}

struct TokenRef: Hashable {
    let chainId: String
    let tokenAddress: String
    var icon: String? = nil
    var description: String? = nil
    var boosts: Int? = nil

    var key: String { "\(chainId):\(tokenAddress.lowercased())" }

    static func fromWatchKey(_ key: String) -> TokenRef? {
        guard let colon = key.firstIndex(of: ":") else { return nil }
        let chain = String(key[..<colon])
        let address = String(key[key.index(after: colon)...])
        return chain.isEmpty || address.isEmpty ? nil : TokenRef(chainId: chain, tokenAddress: address)
    }
}

enum DexScreener {
    private static let base = "https://api.dexscreener.com"

    static func topBoosted() async throws -> [TokenRef] { refs(try await get("/token-boosts/top/v1")) }
    static func latestBoosted() async throws -> [TokenRef] { refs(try await get("/token-boosts/latest/v1")) }
    static func latestProfiles() async throws -> [TokenRef] { refs(try await get("/token-profiles/latest/v1")) }

    static func search(_ query: String) async throws -> [MarketPair] {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        let encoded = query.addingPercentEncoding(withAllowedCharacters: allowed) ?? query
        let root = try await get("/latest/dex/search?q=\(encoded)")
        let pairs = (root as? [String: Any])?["pairs"] as? [Any] ?? []
        return pairs.compactMap(parsePair)
    }

    /// Market data for each token, in the order given, using each token's deepest pool.
    static func tokens(_ refs: [TokenRef]) async throws -> [MarketPair] {
        guard !refs.isEmpty else { return [] }
        var best: [String: MarketPair] = [:]
        let byChain = Dictionary(grouping: refs, by: { $0.chainId })
        for (chain, chainRefs) in byChain {
            var seen = Set<String>()
            let addresses = chainRefs.map(\.tokenAddress).filter { seen.insert($0).inserted }
            for start in stride(from: 0, to: addresses.count, by: 30) {
                let chunk = addresses[start..<min(start + 30, addresses.count)]
                let list = try await get("/tokens/v1/\(chain)/\(chunk.joined(separator: ","))") as? [Any] ?? []
                for pair in list.compactMap(parsePair) {
                    if let current = best[pair.id], (current.liquidityUsd ?? 0) >= (pair.liquidityUsd ?? 0) { continue }
                    best[pair.id] = pair
                }
            }
        }
        var seenKeys = Set<String>()
        return refs.compactMap { ref -> MarketPair? in
            guard var pair = best[ref.key], seenKeys.insert(ref.key).inserted else { return nil }
            if pair.imageUrl == nil { pair.imageUrl = ref.icon }
            pair.description = ref.description
            if pair.boosts == nil { pair.boosts = ref.boosts }
            return pair
        }
    }

    private static func refs(_ root: Any) -> [TokenRef] {
        let items: [Any]
        if let list = root as? [Any] { items = list } else if let one = root as? [String: Any] { items = [one] } else { items = [] }
        var seen = Set<String>()
        return items.compactMap { raw -> TokenRef? in
            guard let item = raw as? [String: Any],
                  let chain = str(item, "chainId"),
                  let address = str(item, "tokenAddress") else { return nil }
            let ref = TokenRef(
                chainId: chain,
                tokenAddress: address,
                icon: str(item, "icon"),
                description: str(item, "description"),
                boosts: num(item, "totalAmount").map { Int($0) }
            )
            return seen.insert(ref.key).inserted ? ref : nil
        }
    }

    private static func parsePair(_ raw: Any) -> MarketPair? {
        guard let pair = raw as? [String: Any],
              let baseToken = pair["baseToken"] as? [String: Any],
              let chain = str(pair, "chainId"),
              let address = str(baseToken, "address") else { return nil }
        let change = pair["priceChange"] as? [String: Any]
        let volume = pair["volume"] as? [String: Any]
        let txns = (pair["txns"] as? [String: Any])?["h24"] as? [String: Any]
        let info = pair["info"] as? [String: Any]
        var links: [SocialLink] = []
        for site in info?["websites"] as? [Any] ?? [] {
            if let entry = site as? [String: Any], let link = str(entry, "url"), let url = URL(string: link) {
                links.append(SocialLink(label: str(entry, "label") ?? "Website", url: url))
            }
        }
        for social in info?["socials"] as? [Any] ?? [] {
            if let entry = social as? [String: Any], let link = str(entry, "url"), let url = URL(string: link) {
                links.append(SocialLink(label: (str(entry, "type") ?? "Link").capitalized, url: url))
            }
        }
        return MarketPair(
            chainId: chain,
            dexId: str(pair, "dexId") ?? "",
            pairAddress: str(pair, "pairAddress") ?? "",
            url: str(pair, "url"),
            baseAddress: address,
            baseName: str(baseToken, "name") ?? "",
            baseSymbol: str(baseToken, "symbol") ?? "?",
            priceUsd: num(pair, "priceUsd"),
            change5m: num(change, "m5"),
            change1h: num(change, "h1"),
            change6h: num(change, "h6"),
            change24h: num(change, "h24"),
            volume24h: num(volume, "h24"),
            liquidityUsd: num(pair["liquidity"] as? [String: Any], "usd"),
            marketCap: num(pair, "marketCap"),
            fdv: num(pair, "fdv"),
            buys24h: num(txns, "buys").map { Int($0) },
            sells24h: num(txns, "sells").map { Int($0) },
            createdAt: num(pair, "pairCreatedAt").map { Date(timeIntervalSince1970: $0 / 1000) },
            imageUrl: str(info, "imageUrl"),
            boosts: num(pair["boosts"] as? [String: Any], "active").map { Int($0) },
            description: nil,
            links: links
        )
    }

    /// Throws on network errors and rate limits so the screen can say the update failed.
    private static func get(_ path: String) async throws -> Any {
        guard let url = URL(string: base + path) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("ChudStreams/1.0 (Macintosh)", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let code = (response as? HTTPURLResponse)?.statusCode, (200...299).contains(code) else {
            throw URLError(.badServerResponse)
        }
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }
}

func num(_ dict: [String: Any]?, _ key: String) -> Double? {
    guard let value = dict?[key] else { return nil }
    if let number = value as? NSNumber { return number.doubleValue }
    if let text = value as? String { return Double(text) }
    return nil
}

// MARK: Model

/// A chain filter option; a nil id means every chain.
struct ChainOption: Hashable {
    let id: String?
    let label: String
}

enum MarketChains {
    static let all: [ChainOption] = [
        ChainOption(id: nil, label: "All chains"),
        ChainOption(id: "solana", label: "Solana"),
        ChainOption(id: "base", label: "Base"),
        ChainOption(id: "ethereum", label: "Ethereum"),
        ChainOption(id: "bsc", label: "BNB Chain"),
    ]
}

enum MarketSection: String, CaseIterable, Identifiable {
    case trending = "Trending"
    case pumpFun = "Pump.fun"
    case new = "New"
    case watchlist = "Watchlist"
    case search = "Search"

    var id: String { rawValue }
}

@MainActor
final class MarketsModel: ObservableObject {
    @Published var section: MarketSection = .trending
    @Published var chain: String? = nil
    @Published var query = ""
    @Published private(set) var items: [MarketPair] = []
    @Published private(set) var loading = false
    @Published private(set) var failed = false
    @Published private(set) var updatedAt: Date? = nil
    @Published private(set) var watchlist: [String]

    private let defaults = UserDefaults.standard
    private static let watchlistKey = "markets.watchlist"

    init() {
        watchlist = UserDefaults.standard.stringArray(forKey: MarketsModel.watchlistKey) ?? []
    }

    var shown: [MarketPair] {
        items.filter { pair in
            (chain == nil || pair.chainId == chain) && (section != .watchlist || watchlist.contains(pair.watchKey))
        }
    }

    func isWatched(_ pair: MarketPair) -> Bool { watchlist.contains(pair.watchKey) }

    func toggleWatch(_ pair: MarketPair) {
        if let index = watchlist.firstIndex(of: pair.watchKey) {
            watchlist.remove(at: index)
        } else {
            watchlist.append(pair.watchKey)
        }
        defaults.set(watchlist, forKey: MarketsModel.watchlistKey)
    }

    func selectSection(_ next: MarketSection) {
        guard next != section else { return }
        section = next
        items = []
        failed = false
        updatedAt = nil
    }

    func refresh() async {
        let requested = section
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if requested == .search && text.isEmpty {
            items = []
            return
        }
        loading = true
        defer { loading = false }
        do {
            let loaded = try await load(requested, query: text)
            guard section == requested else { return }
            items = loaded
            failed = false
            updatedAt = Date()
        } catch {
            // Keep the last good data on screen; the status line says the update failed.
            if section == requested { failed = true }
        }
    }

    private func load(_ section: MarketSection, query: String) async throws -> [MarketPair] {
        switch section {
        case .trending:
            return try await DexScreener.tokens(Array(try await DexScreener.topBoosted().prefix(60)))
        case .new:
            return try await DexScreener.tokens(Array(try await DexScreener.latestProfiles().prefix(60)))
        case .pumpFun:
            let candidates = try await DexScreener.latestProfiles()
                + (try await DexScreener.latestBoosted())
                + (try await DexScreener.topBoosted())
            var seen = Set<String>()
            let refs = candidates
                .filter { $0.chainId == "solana" && $0.tokenAddress.hasSuffix("pump") }
                .filter { seen.insert($0.tokenAddress).inserted }
            return try await DexScreener.tokens(Array(refs.prefix(60)))
        case .watchlist:
            return try await DexScreener.tokens(watchlist.compactMap(TokenRef.fromWatchKey))
        case .search:
            let found = try await DexScreener.search(query)
            var bestByToken: [String: MarketPair] = [:]
            for pair in found where (bestByToken[pair.id]?.liquidityUsd ?? -1) < (pair.liquidityUsd ?? 0) {
                bestByToken[pair.id] = pair
            }
            return Array(bestByToken.values.sorted { ($0.liquidityUsd ?? 0) > ($1.liquidityUsd ?? 0) }.prefix(60))
        }
    }
}

// MARK: Formatting

enum MarketFormat {
    private static let subscripts: [Character] = ["₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉"]

    /// "$1,234.56", "$0.1234", or DEX Screener's "$0.0₅1234" for very small prices.
    static func price(_ value: Double?) -> String {
        guard let value, value > 0 else { return "—" }
        if value >= 1 {
            let formatter = NumberFormatter()
            formatter.numberStyle = .decimal
            formatter.minimumFractionDigits = 2
            formatter.maximumFractionDigits = 2
            return "$" + (formatter.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value))
        }
        if value >= 0.001 { return String(format: "$%.4f", value) }
        var zeros = Int(floor(-log10(value)))
        if value * pow(10, Double(zeros)) >= 1 { zeros -= 1 }
        let significant = String(String(format: "%.0f", value * pow(10, Double(zeros + 4))).prefix(4))
        let count = String(String(zeros).compactMap { digit in digit.wholeNumberValue.map { subscripts[$0] } })
        return "$0.0\(count)\(significant)"
    }

    /// "$950", "$12.3K", "$4.56M", "$1.20B".
    static func usd(_ value: Double?) -> String {
        guard let value else { return "—" }
        switch abs(value) {
        case 1e9...: return String(format: "$%.2fB", value / 1e9)
        case 1e6...: return String(format: "$%.2fM", value / 1e6)
        case 1e3...: return String(format: "$%.1fK", value / 1e3)
        default: return String(format: "$%.0f", value)
        }
    }

    static func percent(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%+.1f%%", value)
    }

    static func age(_ date: Date?) -> String {
        guard let date else { return "—" }
        let minutes = max(0, Int(Date().timeIntervalSince(date) / 60))
        if minutes < 60 { return "\(minutes)m" }
        if minutes < 48 * 60 { return "\(minutes / 60)h" }
        return "\(minutes / (24 * 60))d"
    }

    static func chain(_ id: String) -> String {
        MarketChains.all.first { $0.id == id }?.label ?? id.capitalized
    }

    static func shortAddress(_ address: String) -> String {
        address.count <= 12 ? address : "\(address.prefix(6))…\(address.suffix(4))"
    }
}

// MARK: Views

@MainActor
struct MarketsView: View {
    @StateObject private var markets = MarketsModel()
    @State private var selectedId: MarketPair.ID? = nil
    @State private var sortOrder: [KeyPathComparator<MarketPair>] = []

    private var rows: [MarketPair] {
        sortOrder.isEmpty ? markets.shown : markets.shown.sorted(using: sortOrder)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                NeonTitle(text: "Markets")
                Picker("Section", selection: Binding(
                    get: { markets.section },
                    set: { markets.selectSection($0) }
                )) {
                    ForEach(MarketSection.allCases) { section in
                        Text(section.rawValue).tag(section)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(maxWidth: 440)
                Picker("Chain", selection: $markets.chain) {
                    ForEach(MarketChains.all, id: \.label) { chain in
                        Text(chain.label).tag(chain.id)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .frame(width: 140)
                Spacer()
                Button {
                    Task { await markets.refresh() }
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .buttonStyle(NeonButtonStyle())
                .disabled(markets.loading)
            }
            if markets.section == .search {
                HStack(spacing: 10) {
                    TextField("Token name, symbol or contract address", text: $markets.query)
                        .textFieldStyle(.plain)
                        .font(NeonFont.body(15))
                        .padding(10)
                        .neonPanel()
                        .frame(maxWidth: 520)
                        .onSubmit { Task { await markets.refresh() } }
                    Button("Search") { Task { await markets.refresh() } }
                        .buttonStyle(NeonButtonStyle(prominent: true))
                }
            }
            Text(status)
                .font(NeonFont.body(13))
                .foregroundColor(markets.failed ? Neon.danger : Neon.textSecondary)
            HStack(alignment: .top, spacing: 18) {
                table
                Group {
                    if let pair = rows.first(where: { $0.id == selectedId }) ?? markets.items.first(where: { $0.id == selectedId }) {
                        TokenDetail(pair: pair, watched: markets.isWatched(pair)) { markets.toggleWatch(pair) }
                    } else {
                        Text("Select a token to see its details. Double-click to add it to your watchlist or remove it. Right-click for more.")
                            .font(NeonFont.body(14))
                            .foregroundColor(Neon.textSecondary)
                            .padding(18)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                            .neonPanel()
                    }
                }
                .frame(width: 320)
            }
        }
        .padding(24)
        .task(id: markets.section) {
            // Refresh every 30 seconds while the tab is open; search waits for a query.
            while !Task.isCancelled {
                await markets.refresh()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private var status: String {
        if markets.failed && rows.isEmpty { return "Couldn't reach DEX Screener. Trying again in 30 seconds." }
        if markets.loading && rows.isEmpty { return "Loading market data…" }
        if rows.isEmpty {
            switch markets.section {
            case .watchlist: return "Your watchlist is empty. Double-click any token to add it."
            case .search: return "Search for a token by name, symbol or contract address."
            default: return "Nothing to show for this chain right now."
            }
        }
        if markets.failed { return "The last update failed, so these prices may be out of date. Trying again in 30 seconds." }
        let time = markets.updatedAt?.formatted(date: .omitted, time: .standard) ?? "—"
        return "Updated \(time). Data from DEX Screener, refreshed every 30 seconds. Click a column heading to sort."
    }

    private var table: some View {
        Table(rows, selection: $selectedId, sortOrder: $sortOrder) {
            TableColumn("Token", value: \.baseSymbol) { pair in
                HStack(spacing: 8) {
                    RemoteImage(url: pair.imageUrl, contentMode: .fill)
                        .frame(width: 22, height: 22)
                        .clipShape(Circle())
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 4) {
                            Text(pair.baseSymbol).font(NeonFont.body(13, bold: true))
                            if markets.isWatched(pair) {
                                Image(systemName: "star.fill").font(.system(size: 9)).foregroundColor(Neon.magenta)
                            }
                        }
                        Text("\(MarketFormat.chain(pair.chainId))  \(pair.dexId)")
                            .font(NeonFont.body(11))
                            .foregroundColor(Neon.textMuted)
                    }
                }
            }
            .width(min: 150, ideal: 190)
            TableColumn("Price", value: \.sortPrice) { pair in
                Text(MarketFormat.price(pair.priceUsd)).font(NeonFont.body(13, bold: true))
            }
            .width(min: 90, ideal: 110)
            TableColumn("5m", value: \.sort5m) { pair in ChangeLabel(value: pair.change5m) }
                .width(min: 56, ideal: 64)
            TableColumn("1h", value: \.sort1h) { pair in ChangeLabel(value: pair.change1h) }
                .width(min: 56, ideal: 64)
            TableColumn("24h", value: \.sort24h) { pair in ChangeLabel(value: pair.change24h) }
                .width(min: 60, ideal: 70)
            TableColumn("Market cap", value: \.sortMarketCap) { pair in
                Text(MarketFormat.usd(pair.marketCap ?? pair.fdv)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
            TableColumn("Liquidity", value: \.sortLiquidity) { pair in
                Text(MarketFormat.usd(pair.liquidityUsd)).font(NeonFont.body(13))
            }
            .width(min: 76, ideal: 88)
            TableColumn("Volume 24h", value: \.sortVolume) { pair in
                Text(MarketFormat.usd(pair.volume24h)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
        }
        .tableStyle(.inset(alternatesRowBackgrounds: false))
        .scrollContentBackground(.hidden)
        .background(HudShape(cut: 12).fill(Neon.surface.opacity(0.55)))
        .contextMenu(forSelectionType: MarketPair.ID.self) { ids in
            if let pair = markets.items.first(where: { ids.contains($0.id) }) {
                Button(markets.isWatched(pair) ? "Remove from watchlist" : "Add to watchlist") { markets.toggleWatch(pair) }
                if let link = pair.url, let url = URL(string: link) {
                    Button("Open on DEX Screener") { NSWorkspace.shared.open(url) }
                }
                Button("Copy token address") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(pair.baseAddress, forType: .string)
                }
            }
        } primaryAction: { ids in
            if let pair = markets.items.first(where: { ids.contains($0.id) }) { markets.toggleWatch(pair) }
        }
    }
}

@MainActor
private struct ChangeLabel: View {
    let value: Double?

    var body: some View {
        Text(MarketFormat.percent(value))
            .font(NeonFont.body(13, bold: true))
            .foregroundColor(value == nil ? Neon.textMuted : (value! >= 0 ? Neon.positive : Neon.danger))
    }
}

@MainActor
private struct TokenDetail: View {
    let pair: MarketPair
    let watched: Bool
    let onToggleWatch: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    RemoteImage(url: pair.imageUrl, contentMode: .fill)
                        .frame(width: 48, height: 48)
                        .clipShape(Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(pair.baseSymbol).font(NeonFont.body(20, bold: true)).foregroundColor(Neon.text)
                        Text(pair.baseName).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary).lineLimit(1)
                    }
                }
                Text(MarketFormat.price(pair.priceUsd))
                    .font(NeonFont.display(26))
                    .foregroundColor(Neon.cyan)
                HStack(spacing: 16) {
                    change("5m", pair.change5m)
                    change("1h", pair.change1h)
                    change("6h", pair.change6h)
                    change("24h", pair.change24h)
                }
                VStack(spacing: 6) {
                    stat("Market cap", MarketFormat.usd(pair.marketCap))
                    stat("Fully diluted value", MarketFormat.usd(pair.fdv))
                    stat("Liquidity", MarketFormat.usd(pair.liquidityUsd))
                    stat("24h volume", MarketFormat.usd(pair.volume24h))
                    if let buys = pair.buys24h, let sells = pair.sells24h {
                        stat("24h trades", "\(buys) buys, \(sells) sells")
                    }
                    stat("Pool age", MarketFormat.age(pair.createdAt))
                    stat("Chain and exchange", "\(MarketFormat.chain(pair.chainId))  \(pair.dexId)")
                    stat("Token address", MarketFormat.shortAddress(pair.baseAddress))
                    if let boosts = pair.boosts, boosts > 0 {
                        stat("Active boosts", "\(boosts)")
                    }
                }
                if let description = pair.description, !description.isEmpty {
                    Text(description)
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textSecondary)
                        .lineLimit(6)
                }
                if !pair.links.isEmpty {
                    HStack(spacing: 10) {
                        ForEach(pair.links.prefix(4), id: \.self) { link in
                            Link(link.label, destination: link.url)
                                .font(NeonFont.body(13, bold: true))
                                .foregroundColor(Neon.magenta)
                        }
                    }
                }
                HStack(spacing: 10) {
                    Button(watched ? "Remove from watchlist" : "Add to watchlist", action: onToggleWatch)
                        .buttonStyle(NeonButtonStyle(prominent: !watched))
                    if let link = pair.url, let url = URL(string: link) {
                        Link("DEX Screener", destination: url)
                            .font(NeonFont.body(13, bold: true))
                            .foregroundColor(Neon.cyan)
                    }
                }
            }
            .padding(18)
        }
        .neonPanel()
    }

    private func change(_ label: String, _ value: Double?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(NeonFont.body(11)).foregroundColor(Neon.textMuted)
            ChangeLabel(value: value)
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            Spacer()
            Text(value).font(NeonFont.body(13, bold: true)).foregroundColor(Neon.text)
        }
    }
}
