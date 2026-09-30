> **Follow-up, same day:** versionCode **203**. The previous APK (build 3, versionCode 201) did not include Match Centre because the next compile failed on missing icon and settings imports. Those imports are fixed. Xtream login is kept on the device and Live TV / Films / Series no longer fall back to the sign-in screen while a saved login exists or while the library is still downloading. A missing WorkManager row is a failure, not "loaded". Addons moved into Settings. Debrid catalogs are the **Infinite** tab. **Match Centre** is a new tab (ESPN public scores, fixtures, lineups), in the side menu after Guide. The player has a scrub bar plus rewind/forward buttons; skip steps are 1, 5, 10, 30, 60 and 120 seconds; audio sync steps 1 ms. Mini player uses a TextureView so the menus stay visible. HDR/Dolby tunneling turns on once. Adding an addon no longer crashes on a duplicate list key.

> **This repository is Chud MAXXX**, a separate Fire TV app from earlier [CHUD STREAMS](https://github.com/yungblockchain/Chud-Streams).
> On-screen name: **Chud MAXXX**. Application id: `app.dial.maxxx`. Version name: `MAXXX` (versionCode 203).
> It installs beside CHUD STREAMS (`app.dial.tv`) instead of replacing it. The extension permission is `app.dial.maxxx.permission.BIND_EXTENSION_HOST` for the same reason.
> Earlier CHUD STREAMS is not changed by commits in this repo.
> Build and install notes that still say CHUD STREAMS in [DIAL.md](DIAL.md) describe the shared Fire TV shell. Use the names in this block when they disagree.
> If you are another model, the addon, debrid, and P2P patch notes below still apply. Do not merge this tree back onto `yungblockchain/Chud-Streams` unless asked.

# Chud MAXXX

Fire TV IPTV player (Xtream Codes and M3U) plus a Stremio-style addon browser: catalogs, metadata, debrid, and torrent playback.

Repository: [yungblockchain/Chud-Streams](https://github.com/yungblockchain/Chud-Streams)
Default branch: `main`
Addon commit: [`18fe8a37c6581dae39c65c606e4954978171f6b1`](https://github.com/yungblockchain/Chud-Streams/commit/18fe8a37c6581dae39c65c606e4954978171f6b1) (2026-09-30)
Parent of that commit: `b701f5e267a3c7787ab664435744bc9a59d0ba2f`

The phone module `app/smartphone` is upstream and is not the Firestick product. The native Mac app in `mac/` is a separate player. Do not port the addon work into either unless asked.

---

## If you are another LLM

Read this section before you touch Kotlin. The feature is already on `main`. Do not re-scaffold it, do not add a new Gradle module, and do not invent a second addon protocol.

### Product rules that are easy to break

1. **Only `app/tv` is the Firestick app.** Stremio code lives in `app/tv/src/main/java/com/m3u/tv/stremio/`. It follows the existing "Dial" pattern (Hilt, Compose TV, Room, ExoPlayer). It is not a library module.
2. **Infinite and Match Centre must work with an empty library.** `TvBrowsePane` used to force Xtream sign-in whenever `state.playlists` was empty, except Status, Markets, Games, and Claude. `TvDestination.Infinite` and `TvDestination.MatchCentre` are in that exception list, and so is a saved Xtream session (`signedIn` / `restoringLibrary`). The side menu item is **Infinite**, not Addons. Addon install, debrid tokens and P2P live in Settings → Addons (`StremioAddonsSettings`). If you add a destination and forget the exception, a fresh Fire TV cannot open it. Do not put the manage page back on the side menu.
3. **`TvDestination` is an exhaustive `when`.** Adding or removing an entry means updating every `when (destination)` / `when (shown)` in `TvScreens.kt`, the label map in `TvComponents.kt`, and the side-menu order in `TvStyle.kt`. The compiler will not always save you if a `when` has an `else`.
4. **The synthetic playlist must stay hidden.** Playback inserts a Room `Playlist` whose url is `StremioIds.PLAYLIST_URL` (`stremio://library`) and title `Addons`. `TvHomeViewModel.observePlaylists` filters that url out so it never appears under Live TV. Do not delete the filter. Do not use a normal `http` playlist url or it will show up as a source and fight the empty-library gate.
5. **Continue watching uses `relationId`, not the stream URL.** `StremioViewModel.rememberChannel` sets `relationId` to `"$type:$id"` (example `movie:tt1234567`) and looks the row up with `ChannelDao.getByPlaylistUrlAndRelationId`. The channel `url` is the resolved HTTP link and changes every play. If you key the row on the URL, resume and history break.
6. **Playback is `MediaCommand.Url`, then the existing player.** `playerManager.play(MediaCommand.Url(channelId, url, title))`. `App.kt` passes `onPlayResolved = { surface = TvSurface.Player }`. Do not start a second ExoPlayer inside the stremio package.
7. **Tokens are not hardcoded.** Real-Debrid and TorBox go through `SecretStore` / `SecretName.RealDebrid` and `SecretName.TorBox`, encrypted with an Android Keystore key. The same rows exist on the Addons page and under Settings, Services. Saving either token rewrites the installed Torrentio manifest.
8. **Do not break Xtream.** Live, Films, Series, Guide, Account, and the phone-pushed playlist path are unchanged except for the playlist filter and the empty-library exception. Addon HTTP uses its own `HttpURLConnection` helper. It does not go through the Xtream client.
9. **This commit was not compiled here.** The environment that wrote it could not reach Google's Maven repository. The first Android Studio or GitHub Actions build may still report small errors. Fix those at the line the compiler names. Do not "rewrite it cleaner" while doing that.
10. **GPL-3.0.** Same licence as M3UAndroid. Do not add proprietary blobs. If you share an APK you must share this source.

### Known gap: Torrentio bakes tokens into a plaintext URL

`TorrentioConfig.manifestUrl` builds:

```text
https://torrentio.strem.fun/realdebrid=TOKEN|torbox=KEY/manifest.json
```

That is how Torrentio expects to be configured, and it is what the addon is then asked to fetch. `SecretStore` encrypts the token. `StremioAddonStore` then saves the full manifest URL in plaintext SharedPreferences (`stremio_addons` / key `addons`). So a configured Torrentio install copies the token into that prefs file. Do not log manifest URLs. If you change this, keep Torrentio working: either keep the configured URL or inject the token only at request time and still call Torrentio's documented config path.

### What this patch does not do

- No DHT, no uTP, no protocol encryption, no peer upload. The built-in client is a sequential downloader.
- No second torrent at once. Starting playback cancels the previous `BuiltinTorrent` job and deletes `cacheDir/stremio-$hash.bin`.
- The OpenSubtitles v3 preset can be installed as a Stremio addon. Nothing in this patch reads its `subtitles` resource or attaches a subtitle track to ExoPlayer. The older `OpenSubtitles.kt` path is separate and still uses `SecretName.OpenSubtitles`.
- AIOStreams has no public one-tap manifest. The preset's `manifestUrl` is empty on purpose. The UI asks the person to paste the URL from their own AIOStreams config.
- The Stremio "The Movie Database" preset is a community addon (`94c8cb9f702d-tmdb-addon.baby-beamup.club`). It is not `TmdbClient.kt` and it does not use `SecretName.Tmdb`. `TmdbClient` is still the Xtream details metadata client.
- There is no web companion in this repository. A browser preview was tried elsewhere and is not part of `main`. Do not look for `src/routes` here.
- Subtitles, Trakt scrobbling, and addon `addon catalogs` (addons that install other addons) are not implemented.
- P2P on a stock Firestick is best-effort. Many peers will be unreachable. Real-Debrid, TorBox, or TorrServe on the LAN is the path that actually plays.

---

## Patch notes (2026-09-30)

**Add Stremio-style addons, debrid, and P2P on Fire TV.**

The side menu has an **Addons** item between Series and Guide. It installs Cinemeta, a TMDB catalog addon, Torrentio, and any pasted manifest (AIOStreams is the intended paste). Real-Debrid and TorBox tokens unrestrict magnets. If neither returns a file, playback asks TorrServe, then a built-in sequential peer client.

Commit message on `main`:

```text
Add Stremio-style addons, debrid, and P2P on Fire TV.

The Addons tab installs Cinemeta, TMDB, Torrentio, and pasted manifests such as AIOStreams. Real-Debrid and TorBox tokens unrestrict magnets. P2P uses TorrServe when it answers, otherwise a built-in sequential peer streamer.
```

### Files in that commit

| Path | Change |
| --- | --- |
| `app/tv/src/main/java/com/m3u/tv/stremio/StremioModels.kt` | added, 173 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/StremioHttp.kt` | added, 129 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/StremioClient.kt` | added, 213 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/StremioAddonStore.kt` | added, 131 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/DebridClients.kt` | added, 211 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/MagnetLinks.kt` | added, 84 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/TorrentEngine.kt` | added, 930 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/StremioViewModel.kt` | added, 387 lines |
| `app/tv/src/main/java/com/m3u/tv/stremio/StremioScreens.kt` | added, 508 lines |
| `app/tv/src/main/java/com/m3u/tv/TvStyle.kt` | `TvDestination.Addons` |
| `app/tv/src/main/java/com/m3u/tv/TvComponents.kt` | nav label `dial_nav_addons` |
| `app/tv/src/main/java/com/m3u/tv/TvScreens.kt` | empty-library exception + `StremioScreen` |
| `app/tv/src/main/java/com/m3u/tv/App.kt` | `onPlayResolved` switches to `TvSurface.Player` |
| `app/tv/src/main/java/com/m3u/tv/TvHomeViewModel.kt` | hide `stremio://library` |
| `app/tv/src/main/java/com/m3u/tv/SecretStore.kt` | `RealDebrid`, `TorBox` secret names |
| `app/tv/src/main/java/com/m3u/tv/ServicesSettings.kt` | settings rows + Torrentio resync |
| `app/tv/src/main/res/values/dial_strings.xml` | `dial_nav_addons`, `dial_addons_*`, debrid strings |
| `DIAL.md` | short user-facing summary |

No new Gradle dependency was added. HTTP is `HttpURLConnection`. JSON is the project's existing `kotlinx.serialization.json` (`JsonObject` / `JsonArray` / `JsonPrimitive`, not generated `@Serializable` classes). DI is Hilt constructor injection (`@Singleton` store, `@HiltViewModel` view model). Room DAOs (`ChannelDao`, `PlaylistDao`) and `PlayerManager` were already provided.

### What the person sees

Side menu order is `Search, Home, Live, Films, Series, Addons, Guide, Favorites, My Library, Markets, Claude, Games, Account, Settings`. The Addons icon is `Icons.Rounded.CloudDownload`. The label string is `dial_nav_addons` ("Addons").

Inside the tab, pages are `StremioPage`: `Browse`, `Details`, `Streams`, `Addons`. Back is handled inside the view model before the TV shell sees it:

- Streams goes back to Details
- Details goes back to Browse
- the manage page goes back to Browse
- Browse returns false so the shell can leave the tab

Browse shows up to three movie or series catalogs from each enabled addon that declares the `catalog` resource, 24 posters each. Catalogs whose only extra is `search` are skipped so search feeds do not look like empty shelves. A search box requires at least two characters and queries up to four catalogs per enabled addon.

Details loads `/meta/{type}/{id}.json` from the first enabled addon that has the `meta` resource (usually Cinemeta). If meta fails, the poster row is still shown from the catalog item. Series get a season picker from `videos[].season`. "Find streams" calls every enabled addon that has the `stream` resource.

Stream rows are sorted debrid-looking first, then quality (2160, 1080, 720, 480), then seeders parsed from the addon title. Choosing one resolves a URL and opens the existing player. The status line says which path won: the addon name, `Real-Debrid`, `TorBox`, `TorrServe`, or `P2P`.

The manage page (presets, paste-a-manifest, installed list, debrid fields, P2P toggle, TorrServe address) is `AddonsPage` inside the same tab. "Install Cinemeta and Torrentio" calls `installStarter()`.

### Preset addons (`AddonCatalogPresets`)

| id | Name | Manifest | Kind |
| --- | --- | --- | --- |
| `com.linvo.cinemeta` | Cinemeta | `https://v3-cinemeta.strem.io/manifest.json` | Metadata |
| `tmdb-addon` | The Movie Database | `https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json` | Metadata |
| `com.stremio.torrentio` | Torrentio | `https://torrentio.strem.fun/manifest.json` until a debrid key rewrites it | Stream |
| `aiostreams` | AIOStreams | empty string, must be pasted | Stream |
| `org.stremio.opensubtitlesv3` | OpenSubtitles v3 | `https://opensubtitles-v3.strem.io/manifest.json` | Subtitles |

Any other manifest URL that starts with `http` is fetched and stored. `stremio://` addon URLs are not accepted by `installAwait`.

---

## How the addon protocol is called

Client: `StremioClient` in `StremioClient.kt`. Transport: `StremioHttp`. User-Agent on addon and debrid calls: `ChudStreams/1.1 (Android TV; Stremio)`. Timeouts: connect 15s, read 25s. Redirects are followed by `HttpURLConnection`. Non-2xx throws `StremioHttpException` with the body trimmed to 240 characters.

Base URL is `manifestUrl` with a trailing slash and a trailing `/manifest.json` removed (`String.stremioBaseUrl`).

| Call | URL |
| --- | --- |
| Manifest | `{base}/manifest.json` |
| Catalog | `{base}/catalog/{type}/{id}.json` |
| Catalog extra | `{base}/catalog/{type}/{id}/{key}={value}&{key}={value}.json` |
| Meta | `{base}/meta/{type}/{id}.json` |
| Stream | `{base}/stream/{type}/{id}.json` |

`type` and `id` are URL-encoded with `+` turned into `%20`. Extras are the same. This matches the Stremio addon SDK path form, not a query-string form.

Manifest parsing accepts both shapes Stremio actually sends:

- `resources`: an array of strings, or an array of objects with `name`
- `catalogs[].extra` or `catalogs[].extraSupported`: strings, or objects with `name`
- `types`: used when present, otherwise the distinct catalog types

Catalog items read `id`, `name` or `title`, `poster`, `background` or `poster`, `posterShape`, `releaseInfo` or `year`, `imdbRating`, `description`.

Meta reads `videos[]` with `id`, `title` or `name`, `season`, `episode`, `released`, `thumbnail`, `overview`. IMDb id is `imdb_id`, then `imdbId`, then `id` if it starts with `tt`. Genres, director, and cast may be a JSON array or a comma-separated string.

A stream is kept when it has `url` or `infoHash`. A magnet URL, or an info hash, becomes `magnet:?xt=urn:btih:{hash}`. `fileIdx` is passed through. Quality is the first of `2160p`, `1080p`, `720p`, `480p`, `360p` in `name` + `title`. Size is parsed from a floppy-disk emoji prefix. Seeders are parsed from a person emoji prefix (`👤`). `behaviorHints.bingeGroup` is stored and not used yet.

`StreamSource.playableUrl` is the HTTP url if it starts with `http`, otherwise the magnet, otherwise `magnet:?xt=urn:btih:{infoHash}`.

`StreamSource.isDebrid` is a heuristic, not a protocol field. It is true when the URL contains `real-debrid`, `rdcdn`, or `torbox`, or the display name contains `RD` or `TorBox`, or a short name contains `TB`. It only affects sort order.

### Installed addon storage

`StremioAddonStore` is a `@Singleton` over SharedPreferences file `stremio_addons`.

| Key | Meaning | Default |
| --- | --- | --- |
| `addons` | JSON array of installed addons, including catalogs and resources | empty |
| `p2p_enabled` | built-in / TorrServe fallback allowed | `true` |
| `torrserve_url` | TorrServe base URL | `http://127.0.0.1:8090` |

`upsert` replaces an addon with the same id (case-insensitive) or the same manifest URL. `setEnabled` flips `enabled` without refetching. `enabled` is stored as a JSON boolean and read back with `content != "false"`, so a missing flag counts as on.

The store exposes `StateFlow<List<InstalledAddon>>`. The view model collects it. The UI does not read preferences itself.

---

## Debrid

Both clients live in `DebridClients.kt`. Failures throw `DebridException` with a short message that the UI shows on the stream page.

### Real-Debrid

Base: `https://api.real-debrid.com/rest/1.0`
Auth header: `Authorization: Bearer {token}`
Token hint shown in the UI: `real-debrid.com/apitoken`

| Step | Call |
| --- | --- |
| Account check is not automatic on save | `GET /user`. Premium when `type` equals `premium`. |
| Host link that looks restricted | `POST /unrestrict/link` form field `link`. Returns `download` or `link`. |
| Magnet | `POST /torrents/addMagnet` form field `magnet`. Then `POST /torrents/selectFiles/{id}` with `files=all`. Poll `GET /torrents/info/{id}` every 1.5s, up to 40 times. Status `downloaded` unrestricted the chosen link. Status `magnet_error`, `error`, `virus`, or `dead` fails immediately. |

File choice: `fileIdx` if it is in range, otherwise the file with the largest `bytes`. Restricted host check (`looksRestricted`) matches `real-debrid.com`, `/d/`, `rapidgator`, `uploaded.`, `nitroflare`, `1fichier`.

### TorBox

Base: `https://api.torbox.app/v1/api`
Auth header: `Authorization: Bearer {token}`
The request-download call also puts `token` in the query string, which is what that endpoint expects.

| Step | Call |
| --- | --- |
| Account | `GET /user/me`. Premium when `data.premium` is the string `true` or `plan` is greater than 0. |
| Cache probe | `GET /torrents/checkcached?hash={hash}&format=object` |
| Create | `POST /torrents/createtorrent` form fields `magnet`, `seed=1`, `allow_zip=false` |
| List | `GET /torrents/mylist`, match `hash` case-insensitively, else the magnet, else the last item |
| Link | `GET /torrents/requestdl?token=…&torrent_id=…&zip_link=false&file_id=…` |

Polling is 30 times with 1.2s between tries. Ready when `download_state` is `completed` or `cached`, or `cached` is the string `true`, or `files` is non-empty. File choice is `fileIdx` or the largest `size`. The download URL is JSON `data` as a string, or `data.url`.

There is no AllDebrid, Premiumize, or EasyDebrid client. Add one by following `TorBoxClient` and inserting it in `StreamResolver` before the P2P branch.

### Torrentio configuration

`TorrentioConfig.manifestUrl(realDebrid, torbox)`:

- no keys: `https://torrentio.strem.fun/manifest.json`
- with keys: `https://torrentio.strem.fun/{options joined by |}/manifest.json`
- options are `realdebrid={token}` and `torbox={key}`, Real-Debrid first

`StremioViewModel.syncTorrentio` and `ServicesSettingsViewModel.syncTorrentio` both do this when a debrid secret is saved or removed, but only if an installed addon id contains `torrentio` or its manifest URL contains `torrentio.strem.fun`. On success the new manifest is upserted. On failure `replaceTorrentio` rewrites the stored URL without a refetch so the next stream call still hits the configured addon.

---

## Playback resolution order

`StreamResolver.resolve` in `TorrentEngine.kt`. First match returns `ResolvedPlayback(url, via)`.

1. **Direct HTTP** when `source.url` is `http://` or `https://` and the source is not a magnet and has no info hash. If a Real-Debrid token exists and the URL looks like a file-host link, try `unrestrict` first. If that throws, the original URL is **not** used; the exception falls through only for magnets. For a plain HTTP url, a failed unrestrict is swallowed by `runCatching` and then the original URL is returned. Read the function before changing this. The `runCatching` only wraps the unrestrict call.
2. **Magnet** from `source.magnet`, else `magnet:?xt=urn:btih:` plus `infoHash`, else an HTTP field that is actually a magnet.
3. **Real-Debrid** `resolveMagnet` if a token is saved. Failure is remembered and the next provider is tried.
4. **TorBox** `resolveMagnet` if a key is saved. Same.
5. **P2P**, only when `StremioAddonStore.p2pEnabled` is true (default true).
   - If `TorrServeClient.alive(torrServeUrl)` then TorrServe.
   - Else `BuiltinTorrent.stream`.
6. If P2P is off, throw `DebridException` with the last debrid error, or "No debrid account for this torrent. Add Real-Debrid or TorBox, or turn on P2P."
7. A leftover HTTP url returns as the addon name. Otherwise "This stream has no link."

### TorrServe

`TorrServeClient` speaks the YouROK TorrServer HTTP API. Default base `http://127.0.0.1:8090`.

- Alive: `GET {base}/echo` or, if that fails, `GET {base}`. Any HTTP status from 200 to 399 counts. `StremioHttp.ping` never throws.
- Add: `POST {base}/torrent/add` JSON `{"action":"add","link":"{magnet}","save_to_db":false}`. A failed add is logged as `ChudTorrServe` and ignored. Playback still returns a stream URL.
- Play URL: `{base}/stream?link={infohash}&index={fileIdx or 0}&play`

The Fire TV must be able to open that host. `AndroidManifest.xml` already has `android.permission.INTERNET` and `android:usesCleartextTraffic="true"`, which is required for `http://127.0.0.1`.

The Addons page can edit the address and press "Test TorrServe", which only checks `alive`. It does not add a magnet.

### Built-in torrent engine

`BuiltinTorrent` is not libtorrent. It is a small client in `TorrentEngine.kt` for the case where TorrServe is down and the person still wants a magnet to play.

**Session.** One `Session` at a time, held in a process-wide `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. `stream()` cancels the previous job, closes the previous session, and deletes `cacheDir/stremio-{hexhash}.bin` before creating it again. The file is the selected video only, not the whole torrent. `RandomAccessFile` length is set to the chosen file's length after metadata arrives.

**Local player URL.** A daemon thread binds `ServerSocket` to `127.0.0.1` on port 0 once. The URL returned to ExoPlayer is `http://127.0.0.1:{port}/stream`. The HTTP handler supports `Range`. It waits up to 20 seconds for each requested piece (`Session.awaitPiece`) and then copies bytes from the file offset. MIME is guessed from the file extension (`mkv`, `mp4`, `webm`, `avi`, `mov`, `m4v`, else `video/mp4`). Path other than `/stream` is 404.

**Metadata deadline.** `stream()` waits at most 32 seconds for `session.ready`. On timeout it cancels the job and throws "Peers didn't send the torrent in time...". `ready` completes inside `applyMetadata` only after a video file has been chosen. Completing it is guarded so two peers cannot publish twice.

**Trackers.** Magnet `tr` values first, then `DEFAULT_TRACKERS`:

- `udp://tracker.opentrackr.org:1337/announce`
- `udp://open.stealth.si:80/announce`
- `udp://tracker.torrent.eu.org:451/announce`
- `udp://explodie.org:6969/announce`
- `https://tracker.tamersunion.org:443/announce`

HTTP(S) announce uses BEP 3 compact peers (`compact=1`, `numwant=50`, `event=started`, `port=6881`, `left=1000000000`). The info hash and peer id are percent-encoded with `rawQuery`, not `URLEncoder`, so raw bytes survive. The body is bencoded. Compact peers are 6-byte IPv4 groups. Dictionary peers (`ip`, `port`) are also accepted. There is no IPv6 peer list.

UDP announce is BEP 15: connect magic `0x41727101980`, action 0, then action 1 announce. Socket timeout is 4 seconds. Only IPv4 peers from the response are read, starting at offset 20, 6 bytes each.

**Peer id.** `-CS1100-` plus 12 random lowercase letters or digits. Azureus-style.

**Handshake.** 68 bytes. pstrlen 19, `BitTorrent protocol`, reserved byte at index 25 set to `0x10` (BEP 10 extension bit), info hash at offset 28, peer id at offset 48. A peer whose hash does not match is dropped. The remote extension bit is `theirs[25] & 0x10`.

**Metadata (BEP 9 / BEP 10).** Extended handshake payload is the bencode `d1:md11:ut_metadatai1ee` with message id 20 and extension id 0. Local ut_metadata id is 1. The client sends `interested` (message id 2). It does not implement the full unchoke/choke state machine beyond that. Metadata pieces are 16 KiB. Requests are `d8:msg_typei0e5:piecei{N}ee`. Replies are split by a small bencode walker (`splitBencode`) into the dict and the raw piece. `msg_type` 1 is data. When the blob reaches `metadata_size`, it is bdecoded as the torrent `info` dict.

**File choice.** Single-file torrents use `info.length` and `info.name`. Multi-file torrents walk `info.files` and keep a running byte offset. `fileIdx` wins when it is in range. Otherwise the largest file whose extension is `mkv`, `mp4`, `avi`, `webm`, `m4v`, `mov`, `ts`, or `m2ts`, ignoring paths that contain `sample`. If none match, the largest file of any type.

**Pieces.** Only the chosen file's piece range is downloaded (`firstPiece` through `lastPiece`). Block size is 16 KiB (`BLOCK`). Up to 36 peers are asked for metadata. Up to 16 peers then download pieces. Webseeds (`ws` on the magnet) are fetched with HTTP `Range` in parallel. Piece claims are synchronized so two peers do not write the same piece. `mark` sets a `BitSet` and notifies waiters. The HTTP server blocks on `awaitPiece` so ExoPlayer can start before the file is finished. That is the sequential streaming behavior.

**Bencode.** `Bencode.decode` returns `Long`, `ByteArray` (not `String`), `List`, or `Map<String, Any>`. Dict keys are decoded as UTF-8 strings. Values that are strings stay `ByteArray`. Callers that want a name cast and decode UTF-8. Do not "fix" this by making every string a Kotlin `String` or the piece-hash blobs will corrupt.

**Not implemented, on purpose or by limit.** DHT (BEP 5), PEX, uTP, MSE encryption, seeding, multi-file playback, more than one session, IPv6 UDP announce, magnet `xs` exact source as a separate path (webseeds are `ws` only), and a persistent resume file. The cache file is deleted at the start of the next attempt for that hash.

---

## UI structure

`StremioScreen(onPlaying: () -> Unit)` is the only composable the shell calls. It takes a `StremioViewModel` from Hilt. Pages:

| Composable | Role |
| --- | --- |
| `BrowsePage` | shelves, search, button into the manage page, empty-state string `dial_addons_empty` |
| `PosterCard` | one catalog poster |
| `DetailsPage` | backdrop, plot, cast, seasons, "Find streams" |
| `StreamsPage` | sorted links, resolving line `dial_addons_resolving` |
| `AddonsPage` | presets, paste, enable/remove, debrid `SecretRow`s, P2P, TorrServe |

Focusables are the existing `FocusFrame`, `TvActionButton`, and `DialTextField`. Text fields on this page pass `readOnly = false` because the person must type a URL or a token. That is an exception to the Fire TV rule that text boxes stay read-only until OK. Do not copy that exception onto search boxes elsewhere without reading `DIAL.md`.

Strings to keep stable (they are the UI contract):

- `dial_nav_addons`
- `dial_addons_manage`, `dial_addons_search`, `dial_addons_search_go`, `dial_addons_starter`
- `dial_addons_loading`, `dial_addons_empty`, `dial_addons_play`, `dial_addons_season`, `dial_addons_streams`, `dial_addons_resolving`
- `dial_addons_presets_hint`, `dial_addons_paste`, `dial_addons_install`, `dial_addons_installed`, `dial_addons_installed_header`, `dial_addons_remove`
- `dial_addons_p2p`, `dial_addons_p2p_on`, `dial_addons_p2p_off`
- `dial_addons_torrserve`, `dial_addons_torrserve_test`, `dial_addons_torrserve_up`, `dial_addons_torrserve_down`
- `dial_services_section_debrid`, `dial_services_realdebrid`, `dial_services_realdebrid_hint`, `dial_services_torbox`, `dial_services_torbox_hint`

### Shell wiring, line by line

`TvStyle.kt` inserts `Addons(Icons.Rounded.CloudDownload)` after `Series`.

`TvComponents.kt` maps `TvDestination.Addons` to `R.string.dial_nav_addons`.

`TvScreens.kt` `TvBrowsePane`:

- new parameter `onPlayResolved: () -> Unit = {}`
- empty-playlist sign-in is skipped when `destination == TvDestination.Addons`
- `TvDestination.Addons -> StremioScreen(onPlaying = onPlayResolved)`

`App.kt` passes `onPlayResolved = { surface = TvSurface.Player }` next to the other browse callbacks.

`TvHomeViewModel.observePlaylists` adds `.filterNot { it.url == StremioIds.PLAYLIST_URL }` beside the existing EPG filter.

`SecretStore.SecretName` adds `RealDebrid("real_debrid_token")` and `TorBox("torbox_api_key")`. Encryption is unchanged: AES-GCM, Android Keystore, stored as `base64(iv):base64(ciphertext)` in prefs file `secrets`.

`ServicesSettingsViewModel` now depends on `StremioAddonStore`. `save` and `remove` call `syncTorrentio()` for those two secrets. The Services screen has a "Debrid" section with the same two key rows as the Addons page. Either screen writes the same `SecretStore` entries.

Deep link names still go through `tvDestinationFromExtra`. `addons` maps because it is `TvDestination.entries` name matching. No extra string was added.

---

## Data written into Room

On first successful resolve, if missing:

```text
Playlist(
  title = "Addons",
  url = "stremio://library",
  source = DataSource.M3U,
)
```

Then `ChannelDao.insertOrReplace` with:

| Field | Value |
| --- | --- |
| `url` | the resolved HTTP URL (debrid CDN, TorrServe, or `http://127.0.0.1:{port}/stream`) |
| `category` | `Series` if the opened item type is `series`, otherwise `Films` |
| `title` | the title captured when the item or episode was opened |
| `cover` | catalog poster, else meta poster |
| `playlistUrl` | `stremio://library` |
| `id` | existing row id for this relation, else 0 so Room assigns one |
| `relationId` | `movie:{id}` or `series:{id}` or `{type}:{episodeId}` after "Find streams" on an episode |

If `insertOrReplace` returns 0, the code reads the row back by playlist url and relation id. If that is also missing, play throws "Couldn't save this title to the library".

`MediaCommand.Url` needs a real channel id because the existing player and continue-watching store are channel-based. That is why a fake playlist exists at all.

---

## Magnet helpers

`MagnetLinks` in `MagnetLinks.kt`.

- `infoHash` accepts a magnet (`xt=urn:btih:`) or a bare hash.
- 40 hex characters pass through, lowercased.
- 32 character BitTorrent base32 (`A-Z`, `2-7`) converts to 20-byte hex.
- `magnet(hash, displayName, trackers)` builds `magnet:?xt=urn:btih:{hash}&dn=…&tr=…`. An input that is already a magnet is returned unchanged.
- `queryValues` decodes repeated keys. Used for `tr` and `ws`.
- `qualityFrom` is the same 2160/1080/720/480/360 regex the stream parser uses.

---

## What to edit for the usual follow-ups

| Ask | Where |
| --- | --- |
| Add a preset addon | `AddonCatalogPresets` in `StremioModels.kt`, and a string only if the description should be translated. The description is currently hardcoded English in the preset, not in `dial_strings.xml`. |
| Accept a new manifest field | `StremioClient.parseManifest` and `InstalledAddon`. Then persist it in `StremioAddonStore.save` and `readAddons`. Both sides must change or the field dies on process restart. |
| Add a debrid provider | New client next to `TorBoxClient`, a `SecretName`, rows in `AddonsPage` and `ServicesSettingsScreen`, and a step in `StreamResolver.resolve` **before** the P2P branch. Mirror `syncTorrentio` if that provider has a Torrentio config flag. |
| Change sort order | `StremioViewModel.loadStreams` comparator. `isDebrid` is only a name/URL heuristic. |
| Turn P2P off by default | `StremioAddonStore` key `p2p_enabled` default in the getter (`prefs.getBoolean(KEY_P2P, true)`). |
| Replace the built-in client with libtorrent | Keep `BuiltinTorrent.stream`'s contract: it must return an HTTP URL ExoPlayer can open, or change `StreamResolver` and the player together. Do not return a `magnet:` to `MediaCommand.Url`. |
| Show the addon playlist in the library | Remove the filter in `observePlaylists` only if you also want it in Live TV. You probably do not. |
| Subtitles from the OpenSubtitles addon | There is no hook. A new call would be `/subtitles/{type}/{id}.json` and a side path into the existing player. Do not pretend the preset already does this. |

### Invariants for a refactor

- `StremioIds.PLAYLIST_URL` stays `stremio://library` unless you migrate existing Room rows. Changing the string orphans continue-watching.
- `relationId` format stays `{type}:{id}` for the same reason.
- Resolver order stays debrid, then TorrServe, then built-in, unless the product decision changes. P2P must remain gated by `p2pEnabled`.
- Addon JSON parsing stays lenient. Real addon manifests mix strings and objects. A strict `@Serializable` model will fail on the first Cinemeta or Torrentio response that uses the other shape.
- Do not move network calls onto the main dispatcher. `StremioHttp` already uses `Dispatchers.IO`.
- Do not put debrid tokens in git, in `BuildConfig`, or in a log line.

---

## Build and install

Unchanged from [DIAL.md](DIAL.md). Short version:

- Android Studio: open this folder, module `app.tv`, SDK 37, build the APK.
- GitHub Actions: workflow "Build CHUD STREAMS for Fire TV".
- Install on a Fire TV Stick 4K Max with Developer Options and ADB, or with Downloader pointed at `https://github.com/yungblockchain/Chud-MAXXX/releases/latest/download/chud-maxxx.apk` after the Fire TV workflow has published a build. That APK does not replace earlier CHUD STREAMS.
- Stable signing matters. A new key means uninstalling and losing accounts. See DIAL.md.

The addon code uses only APIs already on the TV app (Hilt, coroutines, serialization, Room, Media3 via `PlayerManager`). It does not add a native `.so`.

---

## Older Fire TV work (already on main before this patch)

The commits under `b701f5e2` and earlier are the Xtream Fire TV shell: sign-in on the TV, large Xtream libraries, VLC as an external player, details pages, a TiviMate-style guide, player settings, 4K/HDR via SurfaceView, a markets tab, a games tab, an optional Claude tab, and the cyberpunk theme. That behavior is documented in [DIAL.md](DIAL.md). Do not revert those files while editing addons.

Notable commits immediately before the addon patch, newest first:

- `b701f5e2` Fire TV: category mark is redrawn in place
- `8fbc6427` Fire TV: set the focus layer's shape
- `570ccfec` Fire TV: one layer per focus frame, steadier category marks, Home label
- `3bc0c43e` Fire TV redesign review fixes (per-tab focus, playlist switching, empty categories)
- `d49726b7` Live TV, Films, Series and Search tabs

The `mac-dev` branch is behind `main` and does not contain the addon commit. Do not assume it does.

---

## Licence

GNU GPL v3. See [LICENSE](LICENSE). Upstream project: [oxyroid/M3UAndroid](https://github.com/oxyroid/M3UAndroid). Fonts under SIL OFL in `licenses/`.

The original upstream README is below so the fork history stays visible.

---

<div align="center">

# M3UAndroid

**A simple IPTV player for Android phones, tablets, and TV.**

[![Release](https://img.shields.io/github/v/release/oxyroid/M3UAndroid?label=release)](https://github.com/oxyroid/M3UAndroid/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/github/license/oxyroid/M3UAndroid)](LICENSE)
[![Telegram](https://img.shields.io/badge/Telegram-Channel-26A5E4?logo=telegram&logoColor=white)](https://t.me/m3u_android)

</div>

---

M3UAndroid helps you watch IPTV streams from your own playlist sources.

It is made for users who want a clean, practical, ad-free IPTV app that works well on Android devices, including Android TV.

## Download

- [GitHub Release](https://github.com/oxyroid/M3UAndroid/releases/latest)
- [Nightly Build](https://nightly.link/oxyroid/M3UAndroid/workflows/android/master/artifact.zip)
- [Telegram Channel](https://t.me/m3u_android)

## Extensions

- [Extension documentation (English)](docs/extensions/README.md)
- [插件文档（简体中文）](docs/extensions/README.zh-CN.md)

## License

M3UAndroid is an open-source project licensed under the [GNU General Public License v3.0](LICENSE).
