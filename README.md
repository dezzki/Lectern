# GIGATON Research Reader

A native Android research-PDF reader for PDFs stored on the GIGATON home server
(user: `100.82.221.72` on the Tailscale network). It navigates the **real folder
tree** under `/storage/magnamite/learningPapers`, streams PDFs from the server,
and keeps annotations/progress in a separate database — the original PDFs are
never modified.

Reading flow: folder-first browse → tap a PDF → lazy rendered reader with pinch
zoom → highlight (drag a box), pen (ink strokes), typed notes, bookmarks,
per-paper annotation outline, reading-progress resume, and optional per-paper
offline downloads.

> **Read [`AGENT_SPEC.md`](AGENT_SPEC.md) first.** It is the source of truth for
> the product, security and acceptance criteria.

## Repository layout

```text
gigaton-reader/
├── AGENT_SPEC.md             # Full product and engineering specification
├── README.md                 # This file
├── .env.example              # Environment template; no real secret
├── docker-compose.yml        # Server deployment (FastAPI + SQLite)
├── server/
│   ├── main.py               # FastAPI API (annotations/progress/browse/file)
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── requirements-dev.txt  # pytest + httpx
│   └── tests/test_api.py     # 32 backend tests
└── android/                  # Kotlin/Compose Android app (single module :app)
```

## Server deployment on GIGATON

Requirements: Docker Engine + Compose, Tailscale running, the papers directory
exists at `/storage/magnamite/learningPapers`.

The intended project directory is `/opt/gigaton/compose/research-reader`.

```bash
# 1. Create the deployment directory and data volume
sudo mkdir -p /opt/gigaton/compose/research-reader/data
sudo test -d /storage/magnamite/learningPapers

# 2. Copy the project (docker-compose.yml, server/, .env.example) into it
cd /opt/gigaton/compose/research-reader

# 3. Generate a real token (never commit this) and a 600-permission .env
printf 'READER_API_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env
chmod 600 .env

# 4. Make the SQLite volume writable by the container user (uid 10001, gid 1001)
sudo chown -R 10001:1001 data

# 5. Build and start
docker compose up -d --build
docker compose logs -f        # Ctrl-C to detach
```

The compose file binds host port `8088` **to the Tailscale interface only**
(`100.82.221.72:8088:8080` by default). The container mounts
`/storage/magnamite/learningPapers:/papers:ro` (read-only) and persists its
SQLite database under `./data`.

### Two container identity notes (important)

- The unprivileged container user is built with **gid 1001**, matching the host
  `gigatonshare` group, so papers like
  `Harness_papers/01_ReAct_2210.03629.pdf` (owner `gigatonshare`, mode
  `rw-rw----`) are readable. The library stays read-only; no `chmod 777` is
  needed.
- The `data/` directory must be owned by the container user
  (`sudo chown -R 10001:1001 data`) or the database cannot be created.

### If Tailscale's interface is not up yet

```bash
SERVER_BIND_IP=127.0.0.1 docker compose up -d
```

Never set `SERVER_BIND_IP=0.0.0.0` without a firewall rule that restricts
access to the tailnet — this service is personal and must not be exposed on the
public internet.

### Verify

```bash
curl http://100.82.221.72:8088/api/health                            # no token needed
curl -H "Authorization: Bearer $READER_API_TOKEN" \
     "http://100.82.221.72:8088/api/browse?path=Harness_papers"
```

## API routes

| Method | Route | Purpose | Auth |
|---|---|---|---|
| GET | `/api/health` | Health + root-exists probe | none |
| GET | `/api/browse?path=&limit=&offset=` | List folders first, then PDFs, under the root | bearer |
| GET | `/api/file?path=` | Stream a PDF (never mutated) | bearer |
| GET | `/api/annotations?paper_path=` | List annotations for a paper | bearer |
| PUT | `/api/annotations` | Create/update annotation (last-write-wins) | bearer |
| DELETE | `/api/annotations/{id}` | Delete annotation | bearer |
| GET | `/api/progress?paper_path=` | Read reading progress | bearer |
| PUT | `/api/progress` | Save reading progress | bearer |

Path handling: every path is resolved inside `/papers`; absolute paths, `..`
traversal, NUL bytes and symlink escapes are rejected. Annotation payloads are
validated JSON with a 1 MB limit; oversized request bodies are rejected
(413). All user data lives in `/data/reader.db` — there are **no** write routes
for source PDFs.

## Android build & install

### Reproducible build (Linux / macOS / Windows with Android Studio)

Requirements: JDK 17, Android SDK Platform 35, Gradle 8.9 (any Gradle ≥ 8.9).

```bash
cd android
./gradlew :app:assembleDebug            # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # JVM unit tests (API client, payloads, file naming)
```

Or open the `android/` folder in Android Studio and run the `app` configuration.

### Install on a phone

1. Put the phone on the same Tailscale tailnet as GIGATON.
2. `adb install app-debug.apk` (or copy the APK to the phone and open it).
3. First launch → **Connect** screen:
   - API base URL: `http://100.82.221.72:8088`
   - API token: the `READER_API_TOKEN` from the server `.env`
   - Tap **Save & Connect**. The app validates health then an authenticated
     browse of the root folder.

The token is stored in Keystore-backed encrypted preferences and is never
logged or shown again in the UI.

### Reader features in the app

- **Browse**: folders first, then PDFs, alphabetical and case-insensitive; tap
  a folder to enter it, back-arrow / breadcrumbs to leave, refresh, and a sync
  status line (`N change(s) pending sync`).
- **Reader**: pages are rendered **on demand** only for visible pages, with a
  shared thread-safe renderer; vertical scrolling, pinch zoom and `+`/`−`
  zoom, fit-width default, page indicator, jump-to-page, and resume at your
  last page (synced across devices).
- **Annotations**: bottom toolbar → Off / Highlight / Pen / Note.
  - Highlight: drag a box over the region; stored as normalized coordinates
    and redrawn at any zoom.
  - Pen: finger/stylus ink strokes, saved per stroke; **Undo** removes the last
    stroke.
  - Note: tap to attach a typed note to a page position.
  - Bookmark: top-bar icon bookmarks the current page.
  - **Annotations list** (top bar ¶ button): grouped outline with
    page/summary, edit (notes/highlights), delete, and **jump to page**.
- **Offline**: each PDF has a download button; download progress bar; once
  downloaded the reader uses the local copy and it opens offline. Tapping the
  download icon again removes the local copy — the server original is never
  touched. Non-downloaded papers are streamed into a short-lived app cache.

Notes: Android's `PdfRenderer` doesn't expose an embedded-text layer, so
"text highlight" is a rectangle/region selection gesture (normalized quads),
not an OS text-extraction select. This is a documented platform limitation, not
a missing feature.

Rendering engine: since some devices ship a broken platform `PdfRenderer`
(blank/white or "could not be rendered" pages), the reader renders pages with
the **bundled PDFium engine** (`com.github.barteksc:pdfium-android:1.9.0`,
Apache-2.0) and only falls back to the platform renderer if PDFium cannot open
a file. The app is bigger because of the bundled native libraries (~64 MB);
that is the trade-off for consistent rendering across devices.

## Sync & offline behaviour

- Edits (annotations, deletes, progress) are written to the local Room database
  **first**, then queued in an outbox and pushed to the server.
- If the server is unreachable, the change stays queued and the browse screen
  shows the pending count and the last sync error. Nothing is silently
  dropped; sync is retried on every subsequent API call and app state reflects
  it.
- Server-side annotations use last-write-wins on `updated_at`; the client does
  not overwrite unsynced local edits during refresh.

## Backup

- Back up `/opt/gigaton/compose/research-reader/data/reader.db` — it contains
  all annotations and reading progress.
- The PDFs are unchanged on the existing Magnamite storage and should be backed
  up by your normal plan (e.g. the same archive you use for
  `/storage/magnamite`).

## Updates

```bash
cd /opt/gigaton/compose/research-reader
# copy the new server/ files in, then:
docker compose up -d --build
# schema changes, if any, are handled by server/main.py initialization; the DB
# file persists under ./data
```

## Troubleshooting

- **Can't connect from the app** — Tailscale connected on both ends? Is
  `100.82.221.72` the server's current Tailscale IP (`tailscale ip` on the
  server)? Is port 8088 free (`ss -ltn`)? Is the container healthy
  (`docker compose ps`)?
- **401 / "Incorrect token"** — the app token must equal `READER_API_TOKEN` in
  the server `.env`; restart the container after changing it
  (`docker compose up -d --force-recreate`).
- **Blank library / "This folder is empty"** — the container mounted the
  papers root read-only (`docker inspect research-reader-api` → mounts), the
  `data/` volume is writable, and `/storage/magnamite/learningPapers` contains
  the PDFs.
- **Container can't read PDFs (permission denied in logs)** — the image's
  `appuser` must be in gid 1001 (the default image already is). Rebuild if you
  changed the Dockerfile: `docker compose build --no-cache server`.
- **Database not created (`unable to open database file`)** — fix the `data/`
  ownership: `sudo chown -R 10001:1001 data`.
- **App keeps stopping when opening a paper (v1.0.0 / v1.0.1)** — that was a
  navigation bug, not the server: paper paths were passed through
  `Uri.encode()`, which leaves `/` and `&` raw, so AndroidX Navigation threw on
  every PDF tap. **v1.0.2** encodes the route argument as URL-safe Base64 and
  is the version to run. The Connect screen also now shows a **Copy crash log**
  button — if anything still crashes, copy it and paste it to the developer;
  the stack trace is written to the app's `crash.log`.
- **Changed address** — update `SERVER_BIND_IP` in compose and the API base URL
  in the app's Connect screen; `docker compose up -d` to re-bind.
- **Offline edits queued but not syncing** — the browse screen shows the
  pending count; connect to the tailnet and make any browse/refresh action to
  retry.

## Tests run (in this repository's CI environment)

- Backend: `python -m pytest server/tests -q` → **32 passed**
  (health, auth/401/503, browse sorting+pagination, PDF streaming,
  traversal/symlink/absolute-path rejection, annotation CRUD + LWW + size
  limits, progress CRUD, no source-mutation routes).
- Android: `gradle :app:compileDebugKotlin`, `:app:testDebugUnitTest`
  (API-client parsing/auth/error mapping, payload serialization, safe file
  naming), `:app:assembleDebug` → debug APK.
- Deployed end-to-end on GIGATON at `100.82.221.72:8088`: health, browse of the
  real `Harness_papers/` tree (10 PDFs), byte-identical PDF streaming, and
  annotation/progress persistence were verified with `curl`.

Interactive on-device behaviour (pinch zoom feel, drawing latency) was not
exercised on a physical Android device in this environment — that is the
remaining manual acceptance step.