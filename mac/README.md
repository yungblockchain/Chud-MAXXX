# CHUD STREAMS for Mac

A native Mac version of CHUD STREAMS, written in Swift and SwiftUI for macOS Ventura (13) and
later. It's built for a 2017 MacBook Pro: Ventura is the newest macOS those machines run, and
the code sticks to what Xcode 15.2 (the last Xcode for Ventura) supports. It runs natively on
the Intel chip and uses macOS's own video player, which decodes H.264 and HEVC in hardware, so
it stays light on 8 GB of RAM.

This is a separate app from the Fire TV one: Android apps can't run on a Mac, so it's new code
with the same look, not a port.

## What's in it

- **Xtream sign-in**: server, username and password, or paste your provider's M3U link into the
  server field. The password is kept in the macOS Keychain.
- **Live TV**: categories, channel numbers and logos, what's on now (with a progress bar) and next.
  Click a channel to watch.
- **Films and series**: poster grids by category, details pages with plot, rating, cast and
  director, "Resume from" and "Start over" for films, and a season picker with every episode for
  series, plus "Continue S2 E5" for the last episode you opened. Positions save every 5 seconds.
- **Guide**: a TiviMate-style timeline. Channels run down the side and time across the top, with
  each programme a block as wide as it is long and a cyan line at the current time. Scroll up and
  down through channels; swipe sideways on the trackpad (or hold Shift and scroll a mouse) to move
  through time, 24 hours back and 24 ahead, or use Earlier, Now and Later. Click a programme to
  see its details; double-click to watch it if it's on now, or to replay it if it carries the
  catch-up icon. Schedules load straight from your Xtream account as rows come into view.
- **Catch-up**: past programmes your provider has archived can be replayed from the start. These
  streams are MPEG-TS, which macOS's own player can't play, so they open in VLC.
- **Markets**: a read-only memecoin tracker using DEX Screener's public API (no key needed).
  Trending, Pump.fun, New, Watchlist and Search, filterable by chain (Solana, Base, Ethereum,
  BNB Chain). A table shows price, 5-minute, 1-hour and 24-hour change, market cap, liquidity and
  volume; click any column heading to sort. The side panel adds 6-hour change, fully diluted
  value, buys and sells, pool age, exchange, token address, boosts, description and the token's
  links. Double-click a row to add it to the watchlist or remove it; right-click to open it on
  DEX Screener or copy its address. Prices refresh every 30 seconds while the tab is open.
  Pump.fun and GMGN don't publish a documented public API, so the Pump.fun section uses
  pump.fun tokens from DEX Screener's data. Nothing in the tab trades or touches a wallet.
- **Favourites**: right-click any channel, film or series.
- **Search** within the current category (the search box at the top right).
- **Settings**: account status, expiry date, connections in use, sign out, and the live TV
  playback choice.
- **The player**: macOS's standard player controls, full screen, Picture in Picture and AirPlay,
  with a neon channel banner showing the number and what's on now and next.
- **The theme**: the same 90s cyberpunk anime look, logo, fonts and katakana as the Fire TV app.

Not in the Mac version yet: the Games tab.

## Keyboard

| Key | In the player |
| --- | --- |
| Up arrow / Page Up | Next channel |
| Down arrow / Page Down | Previous channel |
| I | Show or hide the channel banner |
| Space | Pause and play |
| Left / Right arrow | Skip back / forward (films and series) |
| Esc | Close the player |

## Formats

macOS's built-in player handles MP4, MOV and HLS streams (live TV uses HLS, which Xtream servers
provide). It can't decode MKV or AVI, which some providers use for films. For those, CHUD STREAMS
hands the video to VLC automatically, so install VLC once (free from videolan.org). If your
provider doesn't offer HLS for live TV, turn off "Play live TV in the built-in player" in
Settings and channels will open in VLC too.

## Download and install (easiest)

GitHub builds the app automatically from this folder, so there's nothing to compile.

1. On your Mac, open this link to download the app:
   `https://github.com/yungblockchain/Chud-Streams/releases/download/mac-latest/chud-streams-mac.zip`
2. Open the downloaded `chud-streams-mac.zip` (in Downloads) to unzip it, then drag
   **CHUD STREAMS** into your **Applications** folder.
3. The first time only: in Applications, **right-click** (or Control-click) CHUD STREAMS, choose
   **Open**, then click **Open** in the warning. macOS asks because the app isn't from the App
   Store or an Apple-registered developer. After that it opens normally.

If macOS says the app "is damaged and can't be opened", run this once in Terminal, then open
it again:

```
xattr -dr com.apple.quarantine "/Applications/CHUD STREAMS.app"
```

The same link always gives the newest version: download it again and replace the old app.

## Build it yourself (optional)

1. Install Apple's free build tools in Terminal with `xcode-select --install`, or install
   Xcode 15.2.
2. In Terminal, go into this `mac` folder: type `cd `, with a space, drag the folder onto the
   Terminal window, and press Return.
3. Run `./build-app.sh --install`. The first build takes a few minutes; the app then appears in
   Applications. Without `--install`, it's left in the `build` folder.

## Troubleshooting

- **"Swift isn't installed"** (building it yourself): run `xcode-select --install` first.
- **Keychain asks to allow access after an update**: each build has a new signature, so macOS
  checks before sharing the saved password with it. Click Always Allow.
- **A channel shows a black screen**: the provider may not offer HLS for it. Turn off "Play live
  TV in the built-in player" in Settings so channels open in VLC.
- **"Couldn't reach that server"**: check the port (usually `:8080` or `:80`) and whether the
  address should start with `https://`.

## Files

- `Sources/ChudStreams/XtreamAPI.swift`: the Xtream client (account, categories, streams, film
  and series info, programme listings, stream addresses).
- `AppModel.swift`: sign-in, Keychain, favourites, resume positions, programme cache, playback.
- `Browse.swift`: Live TV, films, series, details pages, favourites and settings.
- `Guide.swift`: the timeline guide, trackpad panning and catch-up.
- `Markets.swift`: the DEX Screener client, markets table, token details and watchlist.
- `Player.swift`: the video player, channel banner and keyboard control.
- `Theme.swift`: colours, fonts, the chamfered HUD shape, buttons and the scanline backdrop.
- `App.swift`: app entry, main window, sidebar and sign-in.
- `Resources/`: fonts, the logo badge and the 1024 px app icon.
- `build-app.sh`: compiles and packages the app.
