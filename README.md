# SonixLink

An Android app that drives **Sonix Player** for the HiBy R3 Pro II from your phone, and browses its library.

---

## Functionalities

- **Finds the player on its own.** Two independent routes, so only one of them has to work: DNS-SD (`_sonixlink._tcp`) and a plain UDP beacon on port 7801.
- **Browses the whole library** in seven tabs - Tracks, Albums, Artists, Album
  artists, Playlists, Favourites and Folders (the card's folders, as the
  player's own file browser lists them).
- **Search** with a page of its own split into tracks, albums and artists.
- **Controls**: previous, play/pause, next, the five playback modes,
  the favourite star and the queue.
- **Circle-play** on the lists the player has it on: play in random order, in
  sequence, or add a random track to the queue.
- **Selection mode** as on the player: a long press on a track, a record, an
  artist or a playlist, then add the chosen tracks to the queue, the
  favourites or a playlist (or take them out of the favourites or the
  playlist they are in).
- **The whole queue**, row for row as on the player, fetched page by page as
  you scroll and redrawn whenever the queue changes there.
- **A media notification** with the track playing on the player, its artwork,
  previous/play-pause/next and the position bar, also on the lock screen, so
  the app does not need to be opened to change track.
- **Over Wi-Fi or Bluetooth.** A player paired with the phone can be reached
  without a shared Wi-Fi network.
- **Now-playing screen** with the cover sent by the player.
- **The phone's volume keys can change the device volume**, with the app open
  and, through the media notification, from the lock screen.
- **The accent color follows the player.** Change it there and every screen of the app changes with it, at once.

## How it works

The app downloads the player's **own SQLite index** once (`/api/db`) and queries
it locally. The covers in the lists are the player's **own thumbnails**, fetched a
screenful at a time as the lists scroll (`/api/thumbs`) and kept on the phone:
the player's thumbnail file can be a hundred megabytes, and only the list of
which thumbnails exist (`/api/thumbkeys`, eight bytes each) is read at sync.

> **Worth knowing:** the store holds thumbnails for rows the player has actually
drawn at least once. The more of the library has been browsed on the player, the
more artwork the app has; an album falls back to the first of its tracks that has
one, and the rest keep the icon.

What is transmitted after is the state and the commands, which are two lines of
JSON, plus the full artwork for the now-playing screen.

### The protocol

Plain HTTP/1.1 with JSON answers on port **7800**. No binary framing, no session;
anything that looks odd opens in a browser and shows itself.

The player shows SonixLink's icon in its status bar for as long as requests keep
arriving (20 s after the last one). The app keeps a foreground service running
while it is connected -- the same one that owns the media notification -- which
reads the state every 2 s while the player plays and every 5 s in pause, and
says `/api/bye` when it disconnects.

| Request | Answer |
| --- | --- |
| `GET /api/info` | name, model, firmware, serial, track count, whether it is scanning, size, timestamp and fingerprint (`db_hash`, `covers_hash`) of the index and of the thumbnails, accent |
| `GET /api/state` | what is playing: state, mode, volume, position, duration, title/artist/album/path, format, battery, queue position |
| `GET /api/db` | the SQLite index, as a file (`503` while the player is scanning) |
| `GET /api/thumbkeys` | every thumbnail key the player holds, 8 bytes each (the 64-bit key, little-endian) |
| `GET /api/thumbs?keys=<k>,<k>…` | up to 64 thumbnails: for each, the 16-digit key, width and height (u16 LE), length (u32 LE), RGB565 pixels; 0×0 is "no artwork", unknown keys are left out |
| `GET /api/covers` | the whole thumbnail store, as a file (kept for older apps) |
| `GET /api/queue[?from=<n>]` | up to 200 paths of the queue: around the playing track, or from row `n`; with `count`, `position`, `revision` (changes whenever the queue does) and `pending` (the player has not fetched that stretch yet: ask again) |
| `GET /api/browse[?path=<dir>]` | one folder of the card, folders first: `{path, root, parent, entries:[{name, path, dir}]}`; confined to the card |
| `GET /api/ping` | `ok` -- the heartbeat, for a client that needs nothing else |
| `GET /api/bye` | the app is leaving: the player drops the icon at once |
| `GET /api/favourites` | the favourites as they stand right now |
| `GET /api/art?path=[&max=<px>]` | one track's artwork, whole and undecoded; with `max` fitted into that many pixels and sent as a JPEG of a few tens of KB. A fitted cover is made on a thread of its own: until it is ready the answer is `202` and the app asks again a moment later (the player also makes the next track's cover ahead, at the size last asked for) |
| `POST /api/command?do=…` | `play`, `pause`, `toggle`, `next`, `prev`, `seek&value=<seconds>`, `volume&value=<0-100>`, `mode&value=<normal\|repeat_all\|repeat_one\|shuffle\|shuffle_repeat>`, `play_path&path=<path>[&list=<all\|album\|artist\|album_artist\|genre\|favourites\|playlist\|queue\|folder>&value=<name>]`, `favourite&path=<path>[&value=0\|1]`, `queue_index&value=<n>`, `scan`, `play_all&list=<…\|albums>&value=<name>&how=<sequence\|shuffle\|random>` (the circle-play menu) |
| `POST /api/command?do=<queue_next\|favourites_add\|favourites_remove\|playlist_add\|playlist_remove>[&value=<playlist>]` | a selection of tracks, as the player's selection mode: the paths in the body, one a line |

`play_path` carries the list the track was touched in, so the queue becomes that
list.

### Bluetooth

The same requests travel over an RFCOMM link. With SonixLink and Bluetooth both
on, the player registers an SDP service with the UUID
`8d6e3a52-4c1f-4b7e-9a2d-5f0c7e1b3a90` on RFCOMM channel **22**; the app
connects to it by UUID, falls back to channel 22 directly when the phone's SDP
lookup does not find it, and sends one
request after another on the same link (`Connection: keep-alive`, every answer
with a `Content-Length`). The player closes a link idle for 30 s and the app
opens it again on the next request.

The phone has to be **paired** with the player first, from the phone's
Bluetooth settings. Then **Connect over Bluetooth** on the first screen lists
the paired devices. Over Bluetooth the index downloads more slowly than over
Wi-Fi (a few hundred KB/s).

Over Bluetooth every request goes one after another on the same link, so the
app keeps what crosses it small: covers come scaled by the player (640 px,
about 30-60 KB instead of the megabytes a cover can be in the file), the
thumbnails eight at a time with a pause between requests, the cover of a track
already skipped past is never fetched, the state read and the commands go ahead
of any cover or thumbnail still waiting, and the notification's service does
not read the state again when a screen of the app has just read it.

Headphones can stay connected to the player at the same time: the phone is
treated as the remote control, never as the audio device, and connecting
headphones does not push it off. If the phone starts sending its own sound to
the player after pairing, switch off **Media audio** (and **Calls**) for the
player in the phone's Bluetooth settings: SonixLink needs neither.

### The order of the lists

The lists read in the player's own order: `/api/info` and `/api/state` carry
`sort` -- which lists the player has reversed (Z-A) or put by date added, and
whether an artist's tracks are grouped by record -- and the app orders its
copies the same way, redrawing a tab when that changes on the player. An album
opens by disc and track number, and two records with the same name are two
albums, told apart by the `album_key` the player writes beside every track.
A track tapped in the app gets the queue the player's own list would give.

### What is downloaded, and when

The index is kept on the phone and downloaded again only when its fingerprint
(`db_hash` in `/api/info`) changes; the list of thumbnail keys likewise with
`covers_hash`, and thumbnails already fetched are never fetched again. The
fingerprint covers what the app reads -- the library tables and the playlists,
the thumbnail keys -- and not what the player writes on its own all the time
(where playback was left, the saved queue), so a connection with nothing new
on the player downloads nothing. The copy on the phone is tied to the player's
serial, so the same player over Wi-Fi and over Bluetooth shares it.

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
  on, on the same Wi-Fi network or paired with the phone over Bluetooth

## Building

Nothing beyond Android Studio (Koala or newer) is needed:

1. `File > Open`, and choose this folder.
2. Studio fetches Gradle 8.7 and the dependencies on the first sync.
3. `Run` on the phone, or `Build > Build APK(s)` for an APK to install by hand.