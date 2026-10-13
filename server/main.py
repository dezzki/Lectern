"""GIGATON Research Reader API.

Personal, Tailscale-only PDF library server. Papers are mounted read-only at
PAPERS_ROOT. All user state (annotations, progress) lives in a SQLite database
under /data or DB_PATH. Every non-health route requires a bearer token.

The papers tree is NEVER written to: there are no create/update/delete/rename
routes for source files, and the mount is :ro in docker-compose.
"""

import hashlib
import json
import os
import re
import secrets
import sqlite3
import tarfile
import threading
import uuid
import zipfile
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from typing import Optional

import pypdfium2 as pdfium
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from PIL import Image
from pydantic import BaseModel, Field

PAPERS_ROOT = Path(os.getenv("PAPERS_ROOT", "/papers")).resolve()
DB_PATH = os.getenv("DB_PATH", "/data/reader.db")
API_TOKEN = os.getenv("READER_API_TOKEN", "")

SCHEMA_VERSION = 1
VALID_KINDS = {"highlight", "note", "ink", "bookmark"}
MAX_PAYLOAD_BYTES = 1_000_000  # 1 MB annotation payload
MAX_REQUEST_BYTES = 2_100_000  # covers payload + envelope
MAX_PAGE_WIDTH = 2000          # upper bound for server-rendered page PNGs

# Comic archives: a zip/rar/tar/7z of page images.
IMAGE_EXTS = {".jpg", ".jpeg", ".jfif", ".png", ".webp", ".gif", ".bmp", ".avif"}
COMIC_EXTS = {".cbz", ".cbr", ".cbt"}
MAX_COMIC_ENTRIES = 5000
MAX_ENTRY_BYTES = 64 * 1024 * 1024  # cap per image entry (blunts archive bombs)

app = FastAPI(
    title="GIGATON Research Reader API",
    version="1.0.0",
    description="Tailscale-only research PDF library for the GIGATON server.",
)


# --------------------------------------------------------------------------- #
# Database
# --------------------------------------------------------------------------- #

def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def db() -> sqlite3.Connection:
    Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    con.execute(
        "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)"
    )
    con.execute("INSERT OR IGNORE INTO schema_version(version) VALUES (1)")
    con.execute(
        """
        CREATE TABLE IF NOT EXISTS annotations (
          id TEXT PRIMARY KEY,
          paper_path TEXT NOT NULL,
          page INTEGER NOT NULL,
          kind TEXT NOT NULL,
          payload TEXT NOT NULL,
          schema_version INTEGER NOT NULL DEFAULT 1,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          deleted INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    con.execute(
        "CREATE INDEX IF NOT EXISTS idx_annotations_paper ON annotations(paper_path)"
    )
    con.execute(
        """
        CREATE TABLE IF NOT EXISTS progress (
          paper_path TEXT PRIMARY KEY,
          page INTEGER NOT NULL DEFAULT 1,
          position TEXT,
          updated_at TEXT NOT NULL
        )
        """
    )
    con.commit()
    return con


# --------------------------------------------------------------------------- #
# Auth + path safety
# --------------------------------------------------------------------------- #

def auth(authorization: Optional[str] = Header(default=None)) -> None:
    if not API_TOKEN:
        raise HTTPException(
            503, "Server is missing READER_API_TOKEN configuration"
        )
    if not authorization:
        raise HTTPException(401, "Missing bearer token")
    if not secrets.compare_digest(authorization, "Bearer " + API_TOKEN):
        raise HTTPException(401, "Invalid or missing bearer token")


def safe_path(rel: str) -> Path:
    """Resolve a paper-relative path and guarantee it stays inside PAPERS_ROOT.

    Rejects absolute paths, NUL bytes, backslashes, .. traversal, and any
    symlink that would resolve outside the papers tree.
    """
    if not isinstance(rel, str):
        raise HTTPException(400, "Invalid path")
    if "\x00" in rel or "\\" in rel:
        raise HTTPException(400, "Invalid path")
    if rel.startswith("/"):
        raise HTTPException(400, "Absolute paths are not allowed")
    parts = Path(rel).parts
    if ".." in parts:
        raise HTTPException(400, "Path traversal is not allowed")

    target = (PAPERS_ROOT / rel).resolve()
    if target != PAPERS_ROOT and PAPERS_ROOT not in target.parents:
        raise HTTPException(400, "Path escapes the papers directory")
    if not target.exists():
        raise HTTPException(404, "Path not found")
    return target


class _BodySizeLimitMiddleware:
    """Pure ASGI middleware: rejects oversized PUT/POST bodies up front.

    Deliberately NOT a Starlette BaseHTTPMiddleware (whose response wrapper
    breaks FileResponse/StreamingResponse body streaming and truncates large
    PDF downloads). This middleware never touches the response body, so file
    streaming passes through untouched.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope["method"] in ("PUT", "POST"):
            headers = {k.decode("latin1"): v.decode("latin1") for k, v in (scope.get("headers") or [])}
            length = headers.get("content-length")
            if length is not None and length.isdigit() and int(length) > MAX_REQUEST_BYTES:
                body = json.dumps({"detail": "Request body too large"}).encode("utf-8")
                await send(
                    {
                        "type": "http.response.start",
                        "status": 413,
                        "headers": [
                            (b"content-type", b"application/json"),
                            (b"content-length", str(len(body)).encode("ascii")),
                        ],
                    }
                )
                await send({"type": "http.response.body", "body": body})
                return
        await self.app(scope, receive, send)


app.add_middleware(_BodySizeLimitMiddleware)


# --------------------------------------------------------------------------- #
# Health
# --------------------------------------------------------------------------- #

@app.get("/api/health")
def health():
    return {
        "ok": True,
        "root_exists": PAPERS_ROOT.is_dir(),
        "version": app.version,
        "schema_version": SCHEMA_VERSION,
    }


# --------------------------------------------------------------------------- #
# Browse + file streaming
# --------------------------------------------------------------------------- #

@app.get("/api/browse", dependencies=[Depends(auth)])
def browse(
    path: str = Query(default=""),
    limit: int = Query(default=0, ge=0, le=5000),
    offset: int = Query(default=0, ge=0),
):
    target = safe_path(path)
    if not target.is_dir():
        raise HTTPException(400, "Not a directory")

    items = []
    try:
        children = list(target.iterdir())
    except PermissionError as exc:
        raise HTTPException(403, "Permission denied reading this folder") from exc

    for p in sorted(children, key=lambda x: (not x.is_dir(), x.name.casefold())):
        if p.is_symlink():
            continue
        rel = p.relative_to(PAPERS_ROOT).as_posix()
        if p.is_dir():
            items.append({"name": p.name, "path": rel, "type": "folder"})
        elif p.is_file():
            kind = document_kind(p)
            if kind is None:
                continue
            stat = p.stat()
            items.append(
                {
                    "name": p.name,
                    "path": rel,
                    "type": kind,
                    "size": stat.st_size,
                    "modified": datetime.fromtimestamp(
                        stat.st_mtime, tz=timezone.utc
                    ).isoformat(timespec="seconds"),
                }
            )

    total = len(items)
    if limit > 0:
        items = items[offset : offset + limit]
    return {"path": path, "items": items, "total": total}


@app.get("/api/file", dependencies=[Depends(auth)])
def get_file(path: str = Query(...)):
    target = safe_path(path)
    if not target.is_file() or target.suffix.lower() != ".pdf":
        raise HTTPException(404, "PDF not found")
    return FileResponse(
        target,
        media_type="application/pdf",
        filename=target.name,
        content_disposition_type="inline",
    )


# --------------------------------------------------------------------------- #
# Server-side page rendering (Option B)
#
# The Android reader shows png pages rendered HERE, so the phone never needs a
# PDF engine (several devices ship broken ones). Renders are cached under
# /data/pagecache keyed by path+page+width+source mtime, so revisits are cheap
# and the source papers are never modified.
# --------------------------------------------------------------------------- #

_PAGE_CACHE_DIR = None

# PDFium is not thread-safe. FastAPI runs these sync endpoints in a thread
# pool, so two page requests arriving together would call PDFium from two
# threads at once, which corrupts its global state (every later load then fails
# with "Data format error") and can crash the process. All PDFium work is
# serialized through this lock.
_PDFIUM_LOCK = threading.Lock()


def page_cache_dir() -> Path:
    global _PAGE_CACHE_DIR
    if _PAGE_CACHE_DIR is None:
        _PAGE_CACHE_DIR = Path(DB_PATH).parent / "pagecache"
        _PAGE_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    return _PAGE_CACHE_DIR


def page_cache_path(path: str, page: int, width: int, mtime_ns: int, size: int, fmt: str) -> Path:
    key = hashlib.sha256(
        f"{path}|{page}|{width}|{fmt}|{mtime_ns}|{size}".encode("utf-8")
    ).hexdigest()
    return page_cache_dir() / f"{key}.{fmt}"


# --------------------------------------------------------------------------- #
# Comic archives (cbz / cbr / cbt / cb7)
#
# A comic is just an archive of page images. Pages are read straight out of the
# archive in memory and served as images, the same way PDF pages are, so the
# Android reader needs no changes. Nothing is ever extracted to disk.
# --------------------------------------------------------------------------- #

def document_kind(target: Path) -> Optional[str]:
    suffix = target.suffix.lower()
    if suffix == ".pdf":
        return "pdf"
    if suffix in COMIC_EXTS:
        return "comic"
    return None


def _natural_key(name: str):
    # "page2" sorts before "page10"
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", name)]


def _is_page_image(name: str) -> bool:
    base = name.rsplit("/", 1)[-1]
    if not base or base.startswith("."):
        return False
    if "__MACOSX" in name:
        return False
    return Path(base).suffix.lower() in IMAGE_EXTS


def list_comic_entries(target: Path) -> list:
    """Page image entries in natural order. Reads names only."""
    suffix = target.suffix.lower()
    names = []
    try:
        if suffix == ".cbz":
            with zipfile.ZipFile(target) as archive:
                for name in archive.namelist():
                    if name.endswith("/"):
                        continue
                    if _is_page_image(name):
                        names.append(name)
        elif suffix == ".cbt":
            with tarfile.open(target) as archive:
                for member in archive.getmembers():
                    if member.isfile() and _is_page_image(member.name):
                        names.append(member.name)
        elif suffix == ".cbr":
            import libarchive

            with libarchive.file_reader(str(target)) as archive:
                for entry in archive:
                    if entry.isfile and _is_page_image(entry.pathname):
                        names.append(entry.pathname)
        else:
            raise HTTPException(400, "Unsupported archive type")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(422, f"Could not read archive: {exc}") from exc

    names = sorted(names, key=_natural_key)
    if len(names) > MAX_COMIC_ENTRIES:
        raise HTTPException(413, "Archive has too many entries")
    return names


def read_comic_entry(target: Path, name: str) -> bytes:
    suffix = target.suffix.lower()
    if suffix == ".cbz":
        with zipfile.ZipFile(target) as archive:
            return archive.read(name)
    if suffix == ".cbt":
        with tarfile.open(target) as archive:
            handle = archive.extractfile(name)
            if handle is None:
                raise HTTPException(404, "Entry not found")
            return handle.read()
    if suffix == ".cbr":
        import libarchive

        with libarchive.file_reader(str(target)) as archive:
            for entry in archive:
                if entry.isfile and entry.pathname == name:
                    return b"".join(entry.get_blocks())
        raise HTTPException(404, "Entry not found")
    raise HTTPException(400, "Unsupported archive type")


def comic_meta(target: Path, path: str) -> dict:
    """Page count and pixel dimensions, cached because it reads every image."""
    stat = target.stat()
    cache = page_cache_dir() / (
        hashlib.sha256(f"meta|{path}|{stat.st_mtime_ns}|{stat.st_size}".encode("utf-8")).hexdigest()
        + ".json"
    )
    if cache.exists():
        try:
            return json.loads(cache.read_text())
        except (OSError, ValueError):
            pass

    names = list_comic_entries(target)
    if not names:
        raise HTTPException(422, "No page images found in this archive")
    pages = []
    total = 0
    for index, name in enumerate(names, start=1):
        data = read_comic_entry(target, name)
        total += len(data)
        if total > MAX_ENTRY_BYTES * 8:
            raise HTTPException(413, "Archive is too large")
        try:
            with Image.open(BytesIO(data)) as image:
                width, height = image.size
        except Exception as exc:
            raise HTTPException(422, f"Could not read page {index}: {exc}") from exc
        pages.append({"page": index, "width_pt": float(width), "height_pt": float(height)})

    result = {"path": path, "page_count": len(names), "pages": pages, "kind": "comic"}
    try:
        cache.write_text(json.dumps(result))
    except OSError:
        pass
    return result


def render_comic_page(target: Path, page: int, max_width: int, fmt: str) -> bytes:
    names = list_comic_entries(target)
    if page > len(names):
        raise HTTPException(404, f"Page {page} does not exist")
    data = read_comic_entry(target, names[page - 1])
    if len(data) > MAX_ENTRY_BYTES:
        raise HTTPException(413, "Page image is too large")
    try:
        with Image.open(BytesIO(data)) as image:
            image = image.convert("RGB")
            if image.width > max_width:
                scale = max_width / image.width
                image = image.resize(
                    (max_width, max(1, round(image.height * scale))),
                    Image.Resampling.LANCZOS,
                )
            buffer = BytesIO()
            if fmt == "jpeg":
                image.save(buffer, format="JPEG", quality=85, optimize=True)
            else:
                image.save(buffer, format="PNG")
            return buffer.getvalue()
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(422, f"Could not render page: {exc}") from exc


@app.get("/api/meta", dependencies=[Depends(auth)])
def get_page_meta(path: str = Query(...)):
    target = safe_path(path)
    kind = document_kind(target) if target.is_file() else None
    if kind is None:
        raise HTTPException(404, "Document not found")
    if kind == "comic":
        return comic_meta(target, path)
    with _PDFIUM_LOCK:
        pdf = None
        try:
            pdf = pdfium.PdfDocument(str(target), autoclose=True)
            pages = []
            for i in range(len(pdf)):
                page = pdf[i]
                w_pt, h_pt = page.get_size()
                pages.append(
                    {"page": i + 1, "width_pt": float(w_pt), "height_pt": float(h_pt)}
                )
            return {"path": path, "page_count": len(pdf), "pages": pages}
        except HTTPException as exc:
            raise exc
        except Exception as exc:
            raise HTTPException(422, f"Could not parse PDF: {exc}") from exc
        finally:
            if pdf is not None:
                try:
                    pdf.close()
                except Exception:
                    pass


@app.get("/api/page", dependencies=[Depends(auth)])
def get_page_image(
    request: Request,
    path: str = Query(...),
    page: int = Query(ge=1),
    max_width: int = Query(default=1100, ge=64, le=MAX_PAGE_WIDTH),
    fmt: str = Query(default="jpeg", alias="format"),
):
    target = safe_path(path)
    kind = document_kind(target) if target.is_file() else None
    if kind is None:
        raise HTTPException(404, "Document not found")
    fmt = fmt.lower()
    if fmt not in ("jpeg", "png"):
        raise HTTPException(400, "Unsupported format (use jpeg or png)")

    stat = target.stat()
    cache = page_cache_path(path, page, max_width, stat.st_mtime_ns, stat.st_size, fmt)
    media = "image/jpeg" if fmt == "jpeg" else "image/png"
    # Pages are immutable for a given file+page+width, so clients (and Coil on
    # the phone) can cache them aggressively. This matters a lot over a slow
    # Tailscale link: a page is fetched once, not on every scroll.
    etag = '"' + cache.stem + '"'
    headers = {"ETag": etag, "Cache-Control": "private, max-age=31536000, immutable"}
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers=headers)
    if cache.exists():
        return Response(cache.read_bytes(), media_type=media, headers=headers)

    if kind == "comic":
        data = render_comic_page(target, page, max_width, fmt)
        try:
            cache.write_bytes(data)
        except OSError:
            pass
        return Response(data, media_type=media, headers=headers)

    pdf = None
    with _PDFIUM_LOCK:
        try:
            pdf = pdfium.PdfDocument(str(target), autoclose=True)
            if page > len(pdf):
                raise HTTPException(404, f"Page {page} does not exist")
            pdf_page = pdf[page - 1]
            w_pt, _ = pdf_page.get_size()
            scale = max_width / w_pt if w_pt > 0 else 1.0
            bitmap = pdf_page.render(scale=scale, fill_color=(255, 255, 255, 255))
            image = bitmap.to_pil()
            buffer = BytesIO()
            if fmt == "jpeg":
                # JPEG is several times smaller than PNG for scanned/text pages,
                # which cuts transfer time on slow links.
                image.convert("RGB").save(buffer, format="JPEG", quality=85, optimize=True)
            else:
                image.save(buffer, format="PNG")
            data = buffer.getvalue()
            try:
                cache.write_bytes(data)
            except OSError:
                pass  # cache is best-effort
            return Response(data, media_type=media, headers=headers)
        except HTTPException as exc:
            raise exc
        except Exception as exc:
            raise HTTPException(422, f"Could not render page: {exc}") from exc
        finally:
            if pdf is not None:
                try:
                    pdf.close()
                except Exception:
                    pass


# --------------------------------------------------------------------------- #
# Annotations
# --------------------------------------------------------------------------- #

class AnnotationIn(BaseModel):
    id: Optional[str] = None
    paper_path: str
    page: int = Field(ge=1)
    kind: str
    payload: dict = Field(default_factory=dict)
    schema_version: int = 1
    created_at: Optional[str] = None
    updated_at: Optional[str] = None

    def validate_ts(self, value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        try:
            datetime.fromisoformat(value)
            return value
        except ValueError:
            raise HTTPException(400, "Invalid timestamp format")


@app.get("/api/annotations", dependencies=[Depends(auth)])
def get_annotations(
    paper_path: str,
    kind: Optional[str] = Query(default=None),
):
    safe_path(paper_path)  # ensure the paper exists and is inside /papers
    if kind is not None and kind not in VALID_KINDS:
        raise HTTPException(400, "Unsupported annotation kind")
    with db() as con:
        if kind:
            rows = con.execute(
                "SELECT * FROM annotations WHERE paper_path=? AND kind=? "
                "AND deleted=0 ORDER BY page, created_at",
                (paper_path, kind),
            ).fetchall()
        else:
            rows = con.execute(
                "SELECT * FROM annotations WHERE paper_path=? AND deleted=0 "
                "ORDER BY page, created_at",
                (paper_path,),
            ).fetchall()
    return [_row_to_annotation(r) for r in rows]


@app.put("/api/annotations", dependencies=[Depends(auth)])
def put_annotation(a: AnnotationIn):
    a.validate_ts(a.created_at)
    a.validate_ts(a.updated_at)

    p = safe_path(a.paper_path)
    if not p.is_file() or p.suffix.lower() != ".pdf":
        raise HTTPException(400, "paper_path must identify a PDF")
    if a.kind not in VALID_KINDS:
        raise HTTPException(400, "Unsupported annotation kind")

    payload_json = json.dumps(a.payload, separators=(",", ":"))
    if len(payload_json.encode("utf-8")) > MAX_PAYLOAD_BYTES:
        raise HTTPException(413, "Annotation payload too large")

    annotation_id = (a.id or "").strip() or uuid.uuid4().hex
    created_at = a.created_at or _now()
    updated_at = a.updated_at or _now()

    with db() as con:
        existing = con.execute(
            "SELECT updated_at FROM annotations WHERE id=?", (annotation_id,)
        ).fetchone()
        if existing is not None and a.updated_at is not None:
            # Deterministic last-write-wins by client timestamp: never let an
            # older edit silently overwrite a newer one.
            if a.updated_at < existing["updated_at"]:
                return {"ok": True, "id": annotation_id, "applied": False}

        con.execute(
            """
            INSERT INTO annotations
              (id, paper_path, page, kind, payload, schema_version,
               created_at, updated_at, deleted)
            VALUES (?,?,?,?,?,?,?,?,0)
            ON CONFLICT(id) DO UPDATE SET
              paper_path=excluded.paper_path,
              page=excluded.page,
              kind=excluded.kind,
              payload=excluded.payload,
              schema_version=excluded.schema_version,
              updated_at=excluded.updated_at,
              deleted=0
            """,
            (
                annotation_id,
                a.paper_path,
                a.page,
                a.kind,
                payload_json,
                a.schema_version,
                created_at,
                updated_at,
            ),
        )
    return {"ok": True, "id": annotation_id, "applied": True}


@app.delete("/api/annotations/{annotation_id}", dependencies=[Depends(auth)])
def delete_annotation(annotation_id: str):
    with db() as con:
        cur = con.execute(
            "DELETE FROM annotations WHERE id=?", (annotation_id,)
        )
        if cur.rowcount == 0:
            raise HTTPException(404, "Annotation not found")
    return {"ok": True}


def _row_to_annotation(row: sqlite3.Row) -> dict:
    payload = row["payload"]
    try:
        parsed = json.loads(payload)
    except (ValueError, TypeError):
        parsed = {}
    return {
        "id": row["id"],
        "paper_path": row["paper_path"],
        "page": row["page"],
        "kind": row["kind"],
        "payload": parsed,
        "schema_version": row["schema_version"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "deleted": bool(row["deleted"]),
    }


# --------------------------------------------------------------------------- #
# Reading progress
# --------------------------------------------------------------------------- #

class ProgressIn(BaseModel):
    paper_path: str
    page: int = Field(ge=1)
    position: Optional[dict] = None
    updated_at: Optional[str] = None


@app.get("/api/progress", dependencies=[Depends(auth)])
def get_progress(paper_path: str):
    safe_path(paper_path)
    with db() as con:
        row = con.execute(
            "SELECT * FROM progress WHERE paper_path=?", (paper_path,)
        ).fetchone()
    if row is None:
        return {
            "paper_path": paper_path,
            "page": 1,
            "position": None,
            "updated_at": None,
        }
    return {
        "paper_path": row["paper_path"],
        "page": row["page"],
        "position": json.loads(row["position"]) if row["position"] else None,
        "updated_at": row["updated_at"],
    }


@app.put("/api/progress", dependencies=[Depends(auth)])
def put_progress(p: ProgressIn):
    target = safe_path(p.paper_path)
    if not target.is_file() or target.suffix.lower() != ".pdf":
        raise HTTPException(400, "paper_path must identify a PDF")

    updated_at = p.updated_at or _now()
    position_json = (
        json.dumps(p.position, separators=(",", ":")) if p.position else None
    )
    if position_json and len(position_json.encode("utf-8")) > MAX_PAYLOAD_BYTES // 2:
        raise HTTPException(413, "Progress payload too large")

    with db() as con:
        con.execute(
            """
            INSERT INTO progress (paper_path, page, position, updated_at)
            VALUES (?,?,?,?)
            ON CONFLICT(paper_path) DO UPDATE SET
              page=excluded.page,
              position=excluded.position,
              updated_at=excluded.updated_at
            """,
            (p.paper_path, p.page, position_json, updated_at),
        )
    return {"ok": True}