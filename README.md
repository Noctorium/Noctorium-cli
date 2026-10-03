<div align="center">

<img src="packaging/noctorium.png" width="96" alt="Noctorium">

# Noctorium CLI

**YouTube Music and SoundCloud, in a terminal — and in every browser in the house.**

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

- **Both services in one place.** Home, search and your library from YouTube Music and SoundCloud,
  your Spotify playlists played from either, one queue for all of it.
- **Your real accounts.** Likes go to the service; playlists are made, renamed, reordered, made public or
  private and deleted there.
- **Synced lyrics** from eight sources, lit up line by line as they are sung, with the source switched
  right on the lyrics (`[` and `]`).
- **Covers in the terminal**, drawn in half blocks, and all of Noctorium's nineteen themes, the seek bar
  in each of its six styles, and the accent taken from the cover if you like.
- **Scrobbling** to Last.fm and ListenBrainz, Discord presence, downloads to keep, a sleep timer,
  Noctorium Connect to move the music to another device.
- **The web player.** `noctorium web`, or `w` inside the player, serves Noctorium to the browsers on your
  network. The music plays out of whichever device opened it — a phone in your pocket gets its lock-screen
  controls — or out of this computer's speakers, with the page as a remote. The same page, with nothing
  installed and no accounts, is at [noctorium-music.vercel.app](https://noctorium-music.vercel.app).

## Getting it

One line does it, without admin rights. In PowerShell on Windows, and in a terminal on Linux:

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
yt-dlp is fetched by Noctorium itself and kept current.

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

## The keys

| | |
| --- | --- |
| `1`–`8`, `Tab` | Home, Search, Library, Queue, Now playing, Downloads, Devices, Settings |
| `/` | Search both services (or paste a link) |
| `↑` `↓` `Enter` | Choose, and play from there |
| `Space` `n` `p` | Play or pause, next, previous |
| `←` `→` | Back or on five seconds (thirty with Shift) |
| `+` `-` `m` | Volume, mute |
| `s` `r` | Shuffle, repeat |
| `a` `A` `P` | Add to the queue, play next, add to a playlist |
| `l` `d` | Like on the real account, download to keep |
| `t` | The next theme |
| `w` | Start the web player |
| `z` | Sleep timer |
| `?` | All of them |

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
`~/.local/share/noctorium/cli` on Linux — with its own settings, so the two never write over each other.
mpv and yt-dlp the desktop already downloaded are used rather than fetched again. Secrets (Spotify,
Last.fm) go to DPAPI on Windows and the desktop keyring through `secret-tool` on Linux; on a machine with
no keyring they are kept for the session only, never written to a file.

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
YouTube, SoundCloud, Spotify, Last.fm, ListenBrainz or Discord.</sub>
