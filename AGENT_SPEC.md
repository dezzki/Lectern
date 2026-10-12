# GIGATON Research Reader — Product & Engineering Specification

**Purpose:** This document is the source of truth for an autonomous coding agent tasked with turning the current starter project into a complete, usable native Android research-PDF reader for the GIGATON home server. Read this before changing code. Implement the product, not merely a mockup or scaffold.

## 1. Product goal

Build a clean native Android app for reading research PDFs stored on the user's own server. The user wants to navigate the actual directory tree, open a PDF, read comfortably on a phone, annotate it, and resume where they stopped. This is a personal research library, not an ebook catalogue.

### Non-negotiable product rules

- **Native Android app**, implemented in Kotlin with Jetpack Compose.
- **Filesystem-first navigation.** Display folders and PDF files in their real directory structure. Folder tap opens that folder; PDF tap opens that PDF.
- **No catalogue/series model.** Do not group PDFs into books, series, collections, authors, or metadata-derived categories. Do not require imports or library scans.
- **Never move, rename, rewrite, or modify original PDFs.** Server mounts the papers directory read-only. All user-created state lives separately.
- Default behaviour is to stream/read from the server. Downloads are optional and per-paper, with a clear offline state and a way to remove a local copy.
- Access is intended over the user's Tailscale network. Do not add public internet exposure, port forwarding, or a public cloud dependency.
- Keep the UI focused, fast, low-clutter, and practical for long reading sessions.
- Do not silently substitute web/PWA for the requested native Android app.

## 2. Actual deployment environment

Server name: **GIGATON**.

- OS: Ubuntu Server LTS
- CPU/RAM: 3rd-generation Intel Core i5, 8 GB RAM
- Docker Engine and Docker Compose are installed.
- Tailscale is used for remote access.
- Server Tailscale IPv4 currently intended for this app: `100.82.221.72`
- Server LAN address previously used: `192.168.1.9`
- Research PDF root on host: `/storage/magnamite/learningPapers`
- An example subfolder is `/storage/magnamite/learningPapers/Harness_papers/` (preserve exact spelling/case and actual filesystem hierarchy).
- The secondary drive is called **Magnamite**, mounted at `/storage/magnamite`. Do not confuse it with the primary drive Miltank.
- Intended server project directory: `/opt/gigaton/compose/research-reader`
- Proposed API host port: `8088`, container port: `8080`
- Intended API base URL from Android over Tailscale: `http://100.82.221.72:8088`

These IPs are configuration values, not constants that should be scattered through source. Put the API URL in an editable app setting/build configuration. The user may change networks or addresses later.

## 3. Core user journeys

### A. Connect to server
1. First launch presents a simple server setup screen.
2. User can enter API base URL and API token.
3. Validate connection with the health endpoint plus an authenticated request.
4. Show understandable errors for unreachable server, invalid token, empty library, and unsupported response.
5. Persist settings securely on device. Do not log or display the token in normal logs/UI.

### B. Browse folders
1. Open at the configured library root.
2. Show folders and PDFs in a predictable list, with folders clearly distinguishable from files.
3. Folder navigation uses relative paths, breadcrumb/back navigation, and a clear root action.
4. Sort folders first, then PDFs alphabetically (unless a user-visible sorting option is later added).
5. Do not show false “series”, metadata cards, or grouping inferred from filenames.
6. Handle empty folders, loading, refresh, and network errors.

### C. Read PDF
1. Tapping a PDF opens an in-app PDF reader.
2. Render pages on demand; do not render every page into a bitmap at once. Keep memory use appropriate for an older phone and an 8 GB server.
3. Support vertical page scrolling, pinch zoom, page navigation, fit-width/readable sizing, and a page indicator.
4. Preserve reading position across app restarts and devices where practical through server-synced progress.
5. Keep reading UI chrome minimal; controls should be accessible without covering content.
6. A failed network request must produce a useful retry action, not a blank screen or crash.

### D. Annotate a paper
All annotations are tied to a stable paper-relative path and PDF page number, stored independently of the PDF bytes.

- **Text highlight:** user selects text (or uses a selection gesture) and saves a highlight with page and positional/text data sufficient to redraw it.
- **Typed note:** user can attach a note to a page or selected text; note can be edited and deleted.
- **Handwritten markup:** finger/stylus drawing overlay with multiple strokes, colour and stroke width if feasible. Save normalized coordinates so markup remains aligned at different zoom levels and screen sizes. Support undo of the latest unsaved stroke at minimum.
- Annotation records must survive app restarts and sync to the server.
- Clearly distinguish annotations from the original document. Never flatten or write annotations into the original PDF.
- Include an annotation list/outline for a paper so the user can jump to a saved note/highlight.
- Deleting or editing an annotation must update server state and UI consistently, including clear offline/error handling.

### E. Offline per-paper download
- Provide an explicit download action for an individual PDF.
- Show download progress, size if available, completion, failure, and whether a local copy exists.
- Reader prefers the local copy when the user has downloaded it, but still treats server path as the canonical identity.
- Allow removing the cached copy without deleting the server original or its annotations/progress.
- Store files in app-private storage by default. Handle insufficient storage and interrupted downloads.
- Do not download the entire library automatically.
- Annotations/progress should queue locally when offline and sync when connectivity returns. At minimum, make unsynced state visible and avoid silently losing edits.

### F. Resume reading
- Persist last page and optionally scroll/zoom position per paper.
- When reopening a paper, offer or perform a sensible resume behaviour.
- Store progress by stable relative path; do not rely only on display name, since names can collide.

## 4. Architecture

### Android client
- Kotlin + Jetpack Compose, native Android app.
- Use a maintained Android PDF rendering library if it materially improves selection, zoom, and annotation support. Android `PdfRenderer` is acceptable for an initial basic renderer, but it does not by itself provide a complete annotation/highlighting experience. Evaluate a suitable library and document any licensing implications.
- Separate UI, domain/repository, network, persistence, PDF rendering, and annotation overlay responsibilities.
- Use coroutines/Flow for asynchronous work.
- Use a local database (Room is preferred) for cached folder metadata as useful, reading progress, annotations/outbox, and download metadata. Keep PDF binary files in app-private files/cache storage, not SQLite blobs.
- Use Android secure storage appropriate for the API token (e.g. encrypted preferences/DataStore backed by Android Keystore where supported). Do not commit secrets.
- Provide a settings screen to edit URL/token and test connection.

Suggested logical modules/packages (adapt as useful; do not overengineer):
- `data/network`: API client, auth header, DTOs, error mapping
- `data/local`: Room entities/DAO, settings, local file/download manager
- `data/repository`: folder, paper, annotation, progress repositories
- `domain`: models and use cases
- `ui/browse`: folder tree/list
- `ui/reader`: PDF canvas/renderer and controls
- `ui/annotations`: highlight/note/ink tools and annotation navigation
- `ui/settings`: connection configuration

### Server API
- Python + FastAPI in Docker.
- SQLite database stored under `/data`, outside the read-only paper root.
- Mount host `/storage/magnamite/learningPapers` to container `/papers:ro`.
- All endpoints except health must require a bearer token.
- Validate every path and prevent traversal/symlink escape outside `/papers`.
- Stream PDF responses; never load a whole large PDF into Python memory unnecessarily.
- Never expose arbitrary filesystem paths or general-purpose file read/write APIs.
- Database writes must be transactional; validate request models and enforce reasonable payload limits.

The current starter backend in `server/main.py` has these initial endpoints:
- `GET /api/health` — unauthenticated health check
- `GET /api/browse?path=` — authenticated folder listing
- `GET /api/file?path=` — authenticated PDF stream
- `GET /api/annotations?paper_path=` — list annotations
- `PUT /api/annotations` — create/update annotation
- `DELETE /api/annotations/{annotation_id}` — delete annotation
- `GET /api/progress?paper_path=` — read progress
- `PUT /api/progress` — save progress

Inspect actual code and tests before assuming details. Extend versioning, validation, response contracts, pagination, and conflict handling where necessary. Keep API docs available in development; do not expose interactive docs unnecessarily beyond Tailscale.

### Data model expectations

Paper identity: normalized relative path from `/papers`, e.g. `Harness_papers/example.pdf`. Do not use absolute host paths or filename alone as identity.

Annotation fields should include at least:
- unique ID (UUID recommended)
- paper relative path
- zero- or one-based page number, consistently documented across all clients
- kind: `highlight`, `note`, `ink`, or `bookmark`
- structured payload JSON, schema-versioned where useful
- created and updated timestamps
- optional deleted/sync state if offline-first sync requires it

Recommended payload examples:
- highlight: selected text, normalized rectangles/quads, colour
- note: text, optional anchor/position and selected text
- ink: one or more strokes, each with normalized points and style
- bookmark: label and optional location

Progress should include paper relative path, last page, and optional normalized scroll/zoom/position, updated timestamp.

If both server and device can edit the same annotation, define deterministic conflict behaviour (last-write-wins with timestamps is acceptable for this personal app; do not silently overwrite local unsynced edits). Add schema versioning for future migration.

## 5. Security and privacy

- Tailscale-only intended access; no public port forwarding.
- Bind the Docker published port to the Tailscale IP when available, or document a safer alternative that still restricts access to the tailnet. Do not bind all interfaces without explaining and enforcing the firewall/network implications.
- Bearer token required on every non-health API route. Generate a strong random token; never use the example placeholder in production.
- Use HTTPS if deployed outside a trusted Tailscale-only path. HTTP is acceptable only for the current tailnet setup, with a clear configuration note.
- Keep the paper mount read-only (`:ro`). Do not provide delete/rename/upload endpoints for source papers.
- Restrict CORS to known origins if CORS is used; a native Android client does not need permissive browser CORS.
- Do not put tokens in source control, APK constants, screenshots, logs, or crash reports. For a personal app, user-entered token stored securely on-device is preferred.
- Validate relative paths, reject absolute paths and traversal, avoid following symlinks outside root, and return safe error messages.
- Set upload/body/request size limits for annotation payloads and use pagination for large folders/annotation lists if needed.

## 6. UI direction

Aim for a utilitarian, quiet, reading-first interface. No social features, recommendations, online accounts, public sharing, AI summaries, cloud subscriptions, metadata scrapers, or unnecessary gamification unless explicitly requested later.

Browse screen:
- Top bar with current folder/breadcrumb and back navigation.
- Folder rows first; PDF rows beneath them.
- Name, optional file size/modified date, and offline-download indicator.
- Refresh/retry and a settings entry.

Reader screen:
- PDF occupies the screen.
- Minimal top controls: back, short filename, page count/page jump, bookmark/annotation actions.
- A compact annotation tool mode: select/highlight, note, pen, eraser/undo as supported.
- Save/sync state should be visible but not noisy.
- Support light/dark app chrome; do not invert PDF page colours by default.

## 7. Current starter project status

The existing project is an incomplete scaffold, not a finished app. The agent must inspect and improve it rather than assume every requirement is already implemented.

Current files include:
- `server/main.py`: initial FastAPI browse/file/annotation/progress API
- `server/Dockerfile`, `server/requirements.txt`
- `docker-compose.yml`, `.env.example`
- `android/`: Kotlin/Compose app scaffold, folder browser, simple PDF view using `PdfRenderer`

Previously completed checks:
- Python syntax check and backend smoke tests passed for health, auth, browsing, PDF stream, and traversal rejection.

Known gaps to close:
- Android build has not yet been verified because Gradle/Android SDK were not available in the original build environment.
- No APK was produced.
- Android settings/secure token entry are not complete.
- PDF rendering currently has a basic implementation and must be made memory-efficient/lazy for large documents.
- Interactive text selection/highlights, typed notes, handwriting overlay, annotation sync UI, reading progress sync, and robust offline/download management are not complete.
- Automated tests, migration strategy, and a polished error/loading/empty state system are incomplete.

Do not claim a feature is implemented merely because a backend endpoint exists. Verify end-to-end behaviour on the Android client.

## 8. Implementation order

Work in vertical slices and keep the app runnable at every stage.

### Phase 1 — Make the current baseline build and connect
1. Inspect all existing files and correct any compile/runtime issues.
2. Make API URL and token configurable through the settings screen.
3. Add API client, authenticated health/connect test, browse root/subfolders, and PDF streaming.
4. Deploy the server with read-only paper mount and persistent SQLite volume.
5. Produce a debug APK or provide exact reproducible build steps if the environment genuinely cannot build it.

### Phase 2 — Reliable reader and progress
1. Implement efficient lazy page rendering, zoom and navigation.
2. Handle large PDFs, errors, rotations, and lifecycle changes.
3. Save/restore reading progress through server API and local cache.
4. Add bookmarks and jump-to-page.

### Phase 3 — Annotation system
1. Finalize a documented, versioned annotation payload schema.
2. Implement highlight selection/geometry persistence and redraw.
3. Implement typed notes with create/edit/delete.
4. Implement ink overlay with normalized stroke coordinates, undo, and redraw.
5. Sync annotation changes to server and expose a per-paper annotation list.
6. Test annotations after closing/reopening app and paper.

### Phase 4 — Offline and sync reliability
1. Per-paper download/cache UI and robust file naming/path mapping.
2. Open downloaded PDFs offline.
3. Local durable queue for progress/annotation writes made offline.
4. Retry sync on connectivity restoration and show pending/error states.
5. Prevent server updates from destroying unsynced local changes.

### Phase 5 — hardening and handoff
1. Automated backend tests, Android unit tests, and manual end-to-end checklist.
2. Test bad token, unavailable server, empty folders, path traversal, large PDFs, interrupted downloads, offline annotation, app restart, and annotation persistence.
3. Add setup/deployment/update/backup docs.
4. Build and deliver a runnable debug APK when toolchain permits, plus reproducible source/build instructions.
5. Report honestly what was tested and what remains.

## 9. Definition of done

The app is not complete until all of the following are true:

- [ ] Android project builds successfully using documented commands.
- [ ] User can configure URL and token without editing source.
- [ ] App connects to GIGATON through Tailscale.
- [ ] User can browse the real directory tree under `learningPapers` without grouping or importing files.
- [ ] User can open and read a multi-page PDF smoothly without loading every page bitmap into memory.
- [ ] Last page/progress survives app restart and syncs to server.
- [ ] Text highlight can be created, persisted, redrawn, and deleted.
- [ ] Typed note can be created, edited, persisted, reopened, and deleted.
- [ ] Handwritten markup can be drawn, persisted, redrawn, and deleted.
- [ ] Annotations are tied to the correct paper path and page and never alter source PDF bytes.
- [ ] User can download one paper, read it offline, and remove the local copy without deleting the server file.
- [ ] Offline edits are not silently lost and eventually sync or visibly report failure.
- [ ] API requires a token except health and prevents path traversal.
- [ ] Paper root is mounted read-only; no source-PDF mutation routes exist.
- [ ] README covers server deployment, Android build/install, configuration, troubleshooting, backup, and updates.
- [ ] Agent provides a concise handoff listing files changed, exact test/build results, APK location if created, deployment commands, and known limitations.

## 10. Agent operating instructions

1. Read this document and the existing `README.md`, then inspect the complete repository before editing.
2. Preserve the user's requirements above. Do not ask questions for decisions already specified here. Use sensible defaults for minor implementation details.
3. Prefer a working end-to-end feature over large amounts of speculative architecture. Keep the design simple enough for the stated server hardware.
4. Do not remove existing user data or run destructive commands. Never delete, rename, reorganize, or modify files under `/storage/magnamite/learningPapers`.
5. Do not expose the service publicly or add third-party cloud dependencies.
6. Add tests alongside changes. Run every available test/build command and distinguish passing tests from tests not run.
7. Do not leave fake buttons, placeholder annotation tools, hard-coded secrets, or unimplemented claims in the UI.
8. If a requirement cannot be completed in the current environment, finish the maximum safe, testable portion and give exact remaining steps; do not misrepresent it as done.
9. Keep setup commands explicit and copy-pastable. Explain which commands run on GIGATON and which run on the Android development machine.
10. At handoff, summarize actual functionality and verification, not intentions.

## 11. Expected deliverables

- Complete Android source project.
- Complete FastAPI server source and tests.
- Docker Compose deployment suitable for GIGATON.
- `.env.example` with placeholders only and instructions to generate a real token.
- A debug APK if build tooling is available, or reproducible build instructions.
- Updated README with installation, configuration, operations, backup, updates, and troubleshooting.
- A test report and known limitations list.

