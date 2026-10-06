<div align="center">

<img src="packaging/noctorium.png" width="96" alt="Noctorium">

# Noctorium CLI

**YouTube Music, SoundCloud, Bandcamp, Spotify and VK, in a terminal — and in every browser in the house.**

</div>

Noctorium in a terminal: the same library, queue, likes, playlists and lyrics as the desktop and the
phone, played with mpv and driven from the keyboard (or the mouse). And `noctorium web`, which turns the
computer it runs on into a music server for the house: open the link it prints on a phone, a tablet or
another computer, and the whole of Noctorium is there in the browser, playing out of that device.

```
noctorium                       the player, in this terminal
noctorium play daft punk        the player, already playing the first match
noctorium search boards of canada
noctorium web                   the web player, for any browser on your network
```

## What it does

- **Every service in one place.** Home, search and your library from YouTube Music, SoundCloud, Bandcamp,
  Spotify and VK, one queue for all of it. Spotify's songs play matched on YouTube Music, or with Premium
  in your own Spotify app, wherever it is open.
- **Made yours.** The speed, from half to double; carrying on with similar songs when the queue runs out;
  which services a search of all of them asks; a sleep timer that fades out; and every key.
- **A queue that keeps.** What autoplay will carry on with is shown under the queue, from the last song's own
  service or YouTube Music's radio, to play, keep or drop. What is next can be shuffled or cleared, the queue
  saved as a playlist, and it is still there, where it was left, the next time the player starts.
- **Your real accounts.** Likes go to the service; playlists are made, renamed, reordered, made public or
  private and deleted there.
- **Synced lyrics** from eight sources, lit up line by line as they are sung, with the source switched
  right on the lyrics (`[` and `]`).
- **Covers in the terminal**, drawn in half blocks, and all of Noctorium's nineteen themes — the Windows 98
  and XP ones drawn as themselves, title bars, bevels, taskbar and all — the seek bar in each of its eleven
  styles, and the accent taken from the cover if you like. The player bar is full, one compact row or a
  taskbar; Now playing is the cover beside the lyrics, a poster in big type, the cover alone or the lyrics
  large.
- **Scrobbling** to Last.fm and ListenBrainz, Discord presence, downloads to keep, a sleep timer,
  Noctorium Connect to move the music to another device.
- **The web player.** `noctorium web`, or `w` inside the player, serves Noctorium to the browsers on your
  network. The music plays out of whichever device opened it — a phone in your pocket gets its lock-screen
  controls — or out of this computer's speakers, with the page as a remote. The same page, with nothing
  installed and no accounts, is at [noctorium-music.vercel.app](https://noctorium-music.vercel.app).

## Getting it

One line does it, without admin rights. In PowerShell on Windows, and in a terminal on macOS or Linux:

```powershell
& ([scriptblock]::Create((irm https://noctorium.vercel.app/install))) --product cli
```

```bash
curl -fsSL https://noctorium.vercel.app/install | sh -s -- --product cli
```

That fetches the terminal installer from the latest release, checks it against the release's checksums and
runs it; `irm https://noctorium.vercel.app/install | iex` on its own asks which of Noctorium and the CLI you
want. With the installer already there, it is `noctorium-installer-cli --product cli`. If the website is
ever down, the scripts are also at `https://raw.githubusercontent.com/Noctorium/Noctorium-Installer/main/scripts/install.ps1` and `install.sh`
beside it — the same lines with that address in place of `https://noctorium.vercel.app/install`.

Or take the archive from the [latest release](https://github.com/Noctorium/Noctorium-Installer/releases/latest)
— `noctorium-cli-<version>-windows-x64.zip` or `noctorium-cli-<version>-linux-x64.tar.gz` — and put its
folder somewhere on your `PATH`. It carries its own Java runtime, so nothing else needs installing except,
on Linux, mpv from your distribution (`sudo apt install mpv`, `sudo dnf install mpv`, `sudo pacman -S mpv`).
On a Mac it uses the mpv inside Noctorium.app when that is installed, and fetches its own otherwise.
yt-dlp is fetched by Noctorium itself and kept current.

### Updates

The player and `noctorium web` look for a newer release once a day, and install it by themselves: the new
copy is checked against the release's checksums, unpacked beside the old one, and takes over when you quit
— never underneath a player that is running. `noctorium update` does the same on the spot, and
`noctorium update --check` only says whether there is one. Settings → *Update automatically* turns the daily
check off, and so does `NOCTORIUM_NO_UPDATE=1` in the environment. A copy in a folder that is not yours to
change — under Program Files, `/usr` or `/opt`, or a build of your own — is told about new releases and left
for whatever put it there to update.

## Signing in

A terminal cannot show Google's or SoundCloud's sign-in page, so the session comes from somewhere already
signed in, and never passes through anything but your own computer:

| From | How |
| --- | --- |
| Noctorium on this computer | `noctorium login youtube --from-desktop` — copies the desktop app's sign-in |
| Your phone | `noctorium login youtube --phone` — scan the code with the Noctorium app |
| Any signed-in browser | `noctorium login soundcloud --cookies cookies.txt` — an exported cookies.txt |

All three are in the player too, under Settings (`8`). Spotify and Last.fm are approved in a browser as
they are on the desktop.

Bandcamp needs no sign-in at all, since a fan's collection is public: `noctorium login bandcamp <name>`,
with the name from your `bandcamp.com/<name>` address, puts your collection and wishlist in the library, and
`noctorium logout bandcamp` takes them out again. Its songs are streamed for listening; to keep one, buy it
on its Bandcamp page (`o` opens it).

Spotify signs in on its own page, in a browser on this computer, which is where Spotify sends its answer:
`noctorium login spotify` for any account — the library, likes, search and two rows of Home, its songs
played matched on YouTube Music — or `noctorium login spotify --premium`, which also lets your Spotify app
play them. Then `noctorium spotify devices` lists where Spotify is open, `noctorium spotify device <name|any>`
picks one, and `noctorium spotify play-on <spotify|youtube>` switches between the two.

VK offers no music to other apps, so `noctorium login vk` uses your vk.ru session the way VK's own web
player does: the `p` cookie from login.vk.ru and `remixsid` from vk.ru, copied from a browser signed in to
VK, typed in when it asks or given with `--cookies "p=…; remixsid=…"`. That is against VK's terms, and VK
may ask you to confirm it is you, or freeze an account it thinks is automated; it says so before it asks.
Many songs do not play outside Russia, and VK's songs cannot be downloaded.

`noctorium settings` shows how it plays, and changes it: `noctorium settings speed 1.25`, `autoplay off`,
`autoplay-from youtube` (or `same`, the song's own service), `avoid-recent off`, `keep-queue off`, `fade 30`,
`hybrid vk off`.

## The keys

As they come; Settings › Keys changes any of them, and `?` always lists them as they are.

| | |
| --- | --- |
| `1`–`8`, `Tab` | Home, Search, Library, Queue, Now playing, Downloads, Devices, Settings |
| `/` | Search every service (or paste a link) |
| `↑` `↓` `Enter` | Choose, and play from there |
| `Space` `n` `p` | Play or pause, next, previous |
| `←` `→` | Back or on five seconds (thirty with Shift) |
| `+` `-` `m` | Volume, mute |
| `<` `>` | Slower, faster |
| `s` `r` | Shuffle, repeat |
| `a` `A` `P` | Add to the queue, play next, add to a playlist |
| `S` `U` `N` | On the Queue page: shuffle what is next, clear it, save the queue as a playlist |
| `a` `x` `R` | On autoplay's songs under the queue: keep one, drop one, look again |
| `l` `d` | Like on the real account, download to keep |
| `t` | The next theme |
| `w` | Start the web player |
| `z` | Sleep timer |
| `?` | All of them |

The arrows, Enter, Escape and Tab stay as they are. A key can only be given where it would not take one
of its jobs from something else, and Delete on a changed key in Settings › Keys puts it back.

## The web player

`noctorium web` starts a small server on this computer and prints its address, with a key in it, and a QR
code for your phone. The key is what lets a browser in: anyone on your network can reach the page, only
someone with the key can use it, so share it like a password. `--here-only` keeps it to this computer,
`--port` picks another port than 7300.

There is no Noctorium server anywhere else involved. The page is this program's own, your sessions stay
on this computer, and the audio comes through it — which is also why it works at all: YouTube refuses
streams to the addresses of the big hosting companies, and serves yours. The page itself is
[noctorium-web-player](https://github.com/Noctorium/noctorium-web-player).

## Where things are kept

In the desktop's data folder, under `cli/` — `%LOCALAPPDATA%\Noctorium\cli` on Windows,
`~/Library/Application Support/Noctorium/cli` on a Mac, `~/.local/share/noctorium/cli` on Linux — with its
own settings, so the two never write over each other. The queue is kept there between launches, in
`queue.json`, by the player and `noctorium web`; the other commands leave it alone. mpv and yt-dlp the desktop
already downloaded are used rather than fetched again. Secrets (Spotify, Last.fm, VK's session) go to DPAPI on Windows, the Keychain on a
Mac and the desktop keyring through `secret-tool` on Linux; on a machine with no keyring they are kept for the
session only, never written to a file.

## Building

JDK 21 and Node. Noctorium-Base comes in as the `base/` submodule (or a checkout beside this one), the web
player as `web/`:

```bash
git clone --recursive https://github.com/Noctorium/Noctorium-cli.git
cd Noctorium-cli
./gradlew run --args="search hello"     # or installDist, and build/install/noctorium/bin/noctorium
./gradlew cliArchive                    # the release archive, with its own runtime
```

`-PskipWeb` builds without the web player's page.

---

<sub>Free software under the GPL-3.0. Noctorium is an independent client and not affiliated with Google,
YouTube, SoundCloud, Bandcamp, Spotify, VK, Last.fm, ListenBrainz or Discord.</sub>
