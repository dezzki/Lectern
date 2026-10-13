# Lectern

A reader for research papers stored on my home server.

Lectern is two pieces that work together:

- `server/` is a FastAPI service that runs on the server (GIGATON). It lists the paper
  folders, renders PDF pages to PNG, and stores annotations and reading progress in a
  SQLite database.
- `android/` is a Kotlin app built with Jetpack Compose. It browses the folder tree and
  displays the pages the server renders.

The service only reads the paper files. It never writes to them, and there are no upload,
rename, or delete endpoints for source documents.

## Why the server renders the pages

Android's built-in `PdfRenderer` does not work on every device. On the phone I tested with,
it opened a file, reported the page count, and then drew blank pages or threw while
rendering a page. Bundling a PDF library in the app ran into other failures on the same
device.

Rendering on the server with PDFium (through `pypdfium2`) avoids all of that. The phone
receives finished PNGs, so it does not need a PDF engine at all. The app is smaller
(about 19 MB instead of 64 MB), and pages look the same on every device.

## What the app does

- Browses folders and PDFs under the configured library root, using the real directory
  structure. There is no catalogue, no import step, and no grouping based on file names.
- Reads server-rendered pages with vertical scrolling, pinch and button zoom, a page
  indicator, and jump-to-page.
- Resumes at the last page you read. Progress is saved on the server, so it carries over
  between devices.
- Annotates papers with highlights (drag a box over a region), pen strokes, typed notes,
  and bookmarks. Annotations are stored separately from the PDF and are never written into
  the file.
- Downloads a single paper for offline reading by caching all of its page images. The
  source PDF on the server is untouched.
- Stores the API URL and token in Android's encrypted preferences. The token is not shown
  or logged after you enter it.

## Supported files

- **PDF** — rendered page by page with PDFium.
- **CBZ / CBR / CBT** — comic archives (zip / rar / tar of images). Pages are read straight
  out of the archive in natural order (`page2` before `page10`) and served as images, so
  the app treats them exactly like PDFs. Nothing is extracted to disk.

CBR needs `libarchive` (installed in the image). `.cb7` (7-Zip archives) is not supported
yet; it would need the `py7zr` package. EPUB is not supported — it needs a layout engine,
which is a different kind of reader.

## Repository layout

```
Lectern/
├── AGENT_SPEC.md            Product and engineering notes
├── README.md                This file
├── .env.example             Environment template, no real secret
├── docker-compose.yml       Server deployment
├── server/
│   ├── main.py              FastAPI app
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── requirements-dev.txt
│   └── tests/test_api.py    Backend tests
└── android/                 Android app (single module, :app)
```

## Server

Requirements: Docker Engine, Docker Compose, Tailscale on the host, and the papers
directory on disk. The intended install directory is `/opt/gigaton/compose/research-reader`.

### Deploy

```bash
sudo mkdir -p /opt/gigaton/compose/research-reader/data
sudo test -d /storage/miltank/Media/Read

# copy docker-compose.yml, server/, and .env.example into that directory, then:
cd /opt/gigaton/compose/research-reader
printf 'READER_API_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env
chmod 600 .env

# the container runs as uid 10001, gid 1001; give it the data volume
sudo chown -R 10001:1001 data

docker compose up -d --build
docker compose logs -f
```

`docker-compose.yml` binds port 8088 to the Tailscale address `100.82.221.72` by default.
Change `SERVER_BIND_IP` if that address moves. Do not bind `0.0.0.0` unless a firewall rule
already limits access to the tailnet.

The container mounts `/storage/miltank/Media/Read:/papers:ro` and keeps its database
under `./data`.

### Container user

The image runs as uid 10001 with gid 1001, and `docker-compose.yml` adds the host groups
`1000` and `1001` to it. That combination lets it read files owned by the host user
(uid 1000) as well as older files owned by the `gigatonshare` group (gid 1001), without
changing ownership on the host. If you move the library and files end up in a group the
container does not have, add that gid to `group_add` — the symptom is HTTP 422 with
"File access error" in the API, or "Permission denied" for the container user.

The `data` directory has to be writable by the container user, which is why the `chown`
step above is there.

### API

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/health` | Health check. No token required. |
| GET | `/api/browse?path=&limit=&offset=` | List folders and PDFs under the root |
| GET | `/api/meta?path=` | Page count and page sizes for a PDF or comic |
| GET | `/api/page?path=&page=&max_width=&format=` | One rendered page image (JPEG by default, `format=png` available) |
| GET | `/api/file?path=` | Stream the original PDF |
| GET | `/api/annotations?paper_path=` | List annotations for a paper |
| PUT | `/api/annotations` | Create or update an annotation |
| DELETE | `/api/annotations/{id}` | Delete an annotation |
| GET | `/api/progress?paper_path=` | Read reading progress |
| PUT | `/api/progress` | Save reading progress |

Every route except `/api/health` needs `Authorization: Bearer <READER_API_TOKEN>`.

Paths are resolved inside the papers root. Absolute paths, `..`, NUL bytes, and symlinks
that point outside the root are rejected. Annotation payloads are JSON and capped at 1 MB.
Rendered pages are cached under `data/pagecache` and keyed by file, page, width, and the
source file's timestamp, so editing a PDF invalidates its cache.

## Android app

Build requirements: JDK 17 and Android SDK 35.

```bash
cd android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
```

Install on a phone that is on the tailnet:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, open the Connect screen and enter:

- API base URL: `http://100.82.221.72:8088`
- API token: the `READER_API_TOKEN` from the server's `.env`

The app checks `/api/health` and then lists the root folder to confirm the token works.

## Offline reading

Tapping the download icon next to a PDF caches every page image for that paper. After
that, the paper opens without a network. Tapping the icon again removes the cached pages.
Nothing on the server changes.

Annotations and progress you make while offline are queued in a local database and pushed
to the server on the next successful request. The browse screen shows how many changes are
waiting and the last sync error, if any.

## Backups

- `data/reader.db` holds annotations and reading progress. Back it up.
- The PDFs stay in the library folder and are backed up with the rest of that drive.

## Updating

Copy the new files into the install directory and rebuild:

```bash
cd /opt/gigaton/compose/research-reader
docker compose up -d --build --force-recreate
```

## Troubleshooting

- **The app cannot connect.** Check that Tailscale is up on both devices, that the
  container is running (`docker compose ps`), and that the server address in the app
  matches the host's current Tailscale IP.
- **401 from the API.** The token in the app has to match `READER_API_TOKEN`. Restart the
  container after changing the `.env` file.
- **A folder looks empty.** Confirm the container mounted the papers directory and that
  the container user can read it.
- **Pages do not load.** Test the endpoints directly:
  `curl -H "Authorization: Bearer $TOKEN" "http://100.82.221.72:8088/api/meta?path=Harness_papers/01_ReAct_2210.03629.pdf"`.
  A 422 with "File access error" usually means the container cannot read the file (see
  "Container user"). A 404 means the path is wrong.
- **Slow or timing out over a mobile connection.** Page images are the bulk of the traffic.
  They are served as JPEG (about half the size of PNG) with `immutable` cache headers, so
  each page is downloaded once and then served from the phone's cache. Check the path with
  `tailscale ping <phone>` on the server: on mobile it is often a few hundred milliseconds,
  so the first load of a paper is bound by that link, not by the server.
- **`unable to open database file` in the logs.** The `data` directory is not writable by
  the container user. Run `sudo chown -R 10001:1001 data`.

## Tests

Backend, from `server/`:

```bash
python -m pytest tests -q
```

Covers health, authentication, folder browsing and pagination, PDF streaming, page
metadata and page rendering, path traversal and symlink rejection, annotation
create/update/delete with payload limits, and reading progress.

Android, from `android/`:

```bash
./gradlew :app:testDebugUnitTest
```

Covers the API client (response parsing, auth header, error mapping), the page image URL,
annotation payload serialization, the route argument encoding, and the download file
naming.

## Credits

- PDFium through [pypdfium2](https://github.com/pypdfium2-team/pypdfium2) (Apache-2.0 /
  BSD-3-Clause) for page rendering.
- [Coil](https://coil-kt.github.io/coil/) (Apache-2.0) for image loading.
- FastAPI and Uvicorn.

Personal project. No public hosting, no accounts, no telemetry.
