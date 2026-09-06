# SonixLink

An Android app that drives **Sonix Player** for the HiBy R3 Pro II from your phone, and browses its library.

---

## Functionalities

- **Finds the player on its own.** Two independent routes, so only one of them has to work: DNS-SD (`_sonixlink._tcp`) and a plain UDP beacon on port 7801.
- **Browses the whole library** in six tabs - Tracks, Albums, Artists, Album
  artists, Playlists, Favourites.
- **Search** with a page of its own split into tracks, albums and artists.
- **Controls**: previous, play/pause, next, the five playback modes,
  the favourite star and the queue.
- **Now-playing screen** with the cover sent by the player.
- **The phone's volume keys can change the device volume.** (While the app is open)
- **The accent color follows the player.** Change it there and every screen of the app changes with it, at once.

## How it works

The app downloads the player's **own SQLite index** once (`/api/db`) and queries
it locally. It also downloads the player's **own thumbnail store** the same way (`/api/covers`) so the covers can appear in the lists just like on the player.

> **Worth knowing:** the store holds thumbnails for rows the player has actually
drawn at least once. The more of the library has been browsed on the player, the
more artwork the app has; an album falls back to the first of its tracks that has
one, and the rest keep the icon.

What is transmitted after is the state and the commands, which are two lines of
JSON, plus the full artwork for the now-playing screen.

### The protocol

Plain HTTP/1.1 with JSON answers on port **7800**. No binary framing, no session,
no heartbeat; anything that looks odd opens in a browser and shows itself.

| Request | Answer |
| --- | --- |
| `GET /api/info` | name, model, firmware, track count, whether it is scanning, size and timestamp of the index and of the thumbnails, accent |
| `GET /api/state` | what is playing: state, mode, volume, position, duration, title/artist/album/path, format, battery, queue position |
| `GET /api/db` | the SQLite index, as a file (`503` while the player is scanning) |
| `GET /api/covers` | the thumbnail store, as a file (`404` before the player has drawn anything) |
| `GET /api/queue` | a window of paths around the playing track |
| `GET /api/favourites` | the favourites as they stand right now |
| `GET /api/art?path=` | one track's artwork, whole and undecoded |
| `POST /api/command?do=…` | `play`, `pause`, `toggle`, `next`, `prev`, `seek&value=<seconds>`, `volume&value=<0-100>`, `mode&value=<normal\|repeat_all\|repeat_one\|shuffle\|shuffle_repeat>`, `play_path&path=<path>[&list=<all\|album\|artist\|album_artist\|genre\|favourites\|playlist\|queue>&value=<name>]`, `favourite&path=<path>[&value=0\|1]`, `queue_index&value=<n>`, `scan` |

`play_path` carries the list the track was touched in, so the queue becomes that
list.

### Discovery

- **mDNS / DNS-SD** — the player answers for `_sonixlink._tcp.local` with
  PTR + SRV + TXT + A in one packet and re-announces every 30 s. This is what
  Android's `NsdManager` uses.
- **A plain UDP beacon** on port **7801**, broadcast every 2 s, reading
  `SONIXLINK1 <ip> <port> <name>`.

### If the app closes itself

The trace of the last crash goes to `last-crash.txt` in the app's own data, and
is shown on the next launch with a **Copy** button. That is the thing to send —
not "it closed itself".

## Requirements

- Android 7.0 (API 24) or newer
- A HiBy R3 Pro II running Sonix Player, with **Wireless --> SonixLink** switched
  on, on the same Wi-Fi network

## Building

Nothing beyond Android Studio (Koala or newer) is needed:

1. `File > Open`, and choose this folder.
2. Studio fetches Gradle 8.7 and the dependencies on the first sync.
3. `Run` on the phone, or `Build > Build APK(s)` for an APK to install by hand.