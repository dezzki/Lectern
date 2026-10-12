"""GIGATON Research Reader API.

Personal, Tailscale-only PDF library server. Papers are mounted read-only at
PAPERS_ROOT. All user state (annotations, progress) lives in a SQLite database
under /data or DB_PATH. Every non-health route requires a bearer token.

The papers tree is NEVER written to: there are no create/update/delete/rename
routes for source files, and the mount is :ro in docker-compose.
"""

import json
import os
import secrets
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

PAPERS_ROOT = Path(os.getenv("PAPERS_ROOT", "/papers")).resolve()
DB_PATH = os.getenv("DB_PATH", "/data/reader.db")
API_TOKEN = os.getenv("READER_API_TOKEN", "")

SCHEMA_VERSION = 1
VALID_KINDS = {"highlight", "note", "ink", "bookmark"}
MAX_PAYLOAD_BYTES = 1_000_000  # 1 MB annotation payload
MAX_REQUEST_BYTES = 2_100_000  # covers payload + envelope

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
        elif p.is_file() and p.suffix.lower() == ".pdf":
            stat = p.stat()
            items.append(
                {
                    "name": p.name,
                    "path": rel,
                    "type": "pdf",
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