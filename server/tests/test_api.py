"""End-to-end API tests for the GIGATON Research Reader backend.

Runs against a throwaway fixture tree on tmp_path so the real library is never
touched. Environment variables are set before (re)importing server.main so the
module picks them up.
"""

import importlib
import os
import sys
from datetime import datetime, timedelta, timezone

import pypdfium2 as pdfium
import pytest

TEST_TOKEN = "test-token-0123456789abcdef"

PKG = os.path.join(os.path.dirname(__file__), "..")
if PKG not in sys.path:
    sys.path.insert(0, PKG)

import main  # noqa: E402


@pytest.fixture()
def api(tmp_path, monkeypatch):
    # --- fixture paper tree --------------------------------------------------
    root = tmp_path / "papers"
    (root / "Sub Folder").mkdir(parents=True)
    (root / "Sub Folder" / "nested.pdf").write_bytes(b"%PDF-1.4 nested\n")

    # A real, renderable 2-page PDF for the /api/meta + /api/page tests.
    (root / "Alpha.pdf").write_bytes(make_renderable_pdf(pages=2))

    (root / "beta.pdf").write_bytes(b"%PDF-1.4 beta\n")
    (root / "not_a_pdf.txt").write_text("ignore me")
    (root / "Empty_Dir").mkdir()
    outside = tmp_path / "secret.txt"
    outside.write_text("secret")
    (root / "escape_link").symlink_to(outside)

    monkeypatch.setenv("PAPERS_ROOT", str(root))
    monkeypatch.setenv("DB_PATH", str(tmp_path / "data" / "reader.db"))
    monkeypatch.setenv("READER_API_TOKEN", TEST_TOKEN)
    importlib.reload(main)

    from fastapi.testclient import TestClient

    with TestClient(main.app) as client:
        yield client

    importlib.reload(main)


def make_renderable_pdf(pages: int = 2) -> bytes:
    pdf = pdfium.PdfDocument.new()
    try:
        for _ in range(pages):
            pdf.new_page(width=612, height=792)
        import io as _io

        buffer = _io.BytesIO()
        pdf.save(buffer)
        return buffer.getvalue()
    finally:
        pdf.close()


def auth_headers():
    return {"Authorization": f"Bearer {TEST_TOKEN}"}


def iso(dt: datetime):
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds")


# --------------------------------------------------------------------------- #
# Health
# --------------------------------------------------------------------------- #

def test_health_is_unauthenticated(api):
    r = api.get("/api/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["root_exists"] is True
    assert body["version"] == "1.0.0"
    assert body["schema_version"] == 1


def test_health_reports_missing_root(api, monkeypatch, tmp_path):
    monkeypatch.setenv("PAPERS_ROOT", str(tmp_path / "nope"))
    importlib.reload(main)
    r = api.get("/api/health")
    assert r.status_code == 200
    assert r.json()["root_exists"] is False
    importlib.reload(main)


# --------------------------------------------------------------------------- #
# Auth
# --------------------------------------------------------------------------- #

def test_browse_requires_token(api):
    assert api.get("/api/browse").status_code == 401
    assert api.get("/api/browse", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert api.get("/api/browse", headers={"Authorization": "Basic abc"}).status_code == 401


def test_all_routes_require_token(api):
    r = api.get("/api/file", params={"path": "Alpha.pdf"})
    assert r.status_code == 401
    r = api.get("/api/annotations", params={"paper_path": "Alpha.pdf"})
    assert r.status_code == 401
    r = api.get("/api/progress", params={"paper_path": "Alpha.pdf"})
    assert r.status_code == 401


def test_missing_token_config_returns_503(api, monkeypatch):
    monkeypatch.setenv("READER_API_TOKEN", "")
    importlib.reload(main)
    r = api.get("/api/browse")
    assert r.status_code == 503
    importlib.reload(main)


# --------------------------------------------------------------------------- #
# Browse
# --------------------------------------------------------------------------- #

def test_browse_root_sorts_folders_first_then_pdfs(api):
    r = api.get("/api/browse", headers=auth_headers())
    assert r.status_code == 200
    body = r.json()
    types = [i["type"] for i in body["items"]]
    assert types == ["folder", "folder", "pdf", "pdf"]

    pdfs = [i for i in body["items"] if i["type"] == "pdf"]
    assert [p["name"] for p in pdfs] == ["Alpha.pdf", "beta.pdf"]

    folders = [i for i in body["items"] if i["type"] == "folder"]
    assert {f["name"] for f in folders} == {"Sub Folder", "Empty_Dir"}
    # Paths are POSIX and relative to /papers
    assert folders[0]["path"] == "Empty_Dir"


def test_browse_subfolder(api):
    r = api.get("/api/browse", params={"path": "Sub Folder"}, headers=auth_headers())
    assert r.status_code == 200
    items = r.json()["items"]
    assert items == [
        {"name": "nested.pdf", "path": "Sub Folder/nested.pdf", "type": "pdf",
         "size": len(b"%PDF-1.4 nested\n"), "modified": items[0]["modified"]}
    ]


def test_browse_empty_folder(api):
    r = api.get("/api/browse", params={"path": "Empty_Dir"}, headers=auth_headers())
    assert r.status_code == 200
    assert r.json()["items"] == []


def test_browse_pagination(api):
    r = api.get("/api/browse", params={"limit": 2, "offset": 0}, headers=auth_headers())
    body = r.json()
    assert len(body["items"]) == 2
    assert body["total"] == 4


def test_browse_skips_symlinks(api):
    r = api.get("/api/browse", headers=auth_headers())
    names = [i["name"] for i in r.json()["items"]]
    assert "escape_link" not in names


def test_browse_missing_folder_404(api):
    r = api.get("/api/browse", params={"path": "nope"}, headers=auth_headers())
    assert r.status_code == 404


def test_browse_non_directory(api):
    r = api.get("/api/browse", params={"path": "Alpha.pdf"}, headers=auth_headers())
    assert r.status_code == 400


# --------------------------------------------------------------------------- #
# Path safety
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize("bad", ["..", "../..", "sub/../../etc"])
def test_traversal_rejected(api, bad):
    r = api.get("/api/browse", params={"path": bad}, headers=auth_headers())
    assert r.status_code == 400


def test_urlencoded_traversal_rejected(api):
    # A raw encoded `..%2F..` is decoded by the server to `../..` and must be
    # rejected, never dereferenced.
    r = api.get("/api/browse?path=..%2F..", headers=auth_headers())
    assert r.status_code == 400


def test_absolute_path_rejected(api):
    r = api.get("/api/browse", params={"path": "/etc/passwd"}, headers=auth_headers())
    assert r.status_code == 400


def test_nul_byte_rejected(api):
    r = api.get("/api/browse", params={"path": "a\x00b"}, headers=auth_headers())
    assert r.status_code == 400


def test_symlink_escape_rejected(api):
    r = api.get("/api/file", params={"path": "escape_link"}, headers=auth_headers())
    assert r.status_code == 400
    r = api.get("/api/browse", params={"path": "escape_link"}, headers=auth_headers())
    assert r.status_code == 400


# --------------------------------------------------------------------------- #
# File streaming
# --------------------------------------------------------------------------- #

def test_file_stream_returns_pdf_bytes(api):
    # beta.pdf is a tiny hand-crafted fixture with exact known bytes.
    r = api.get("/api/file", params={"path": "beta.pdf"}, headers=auth_headers())
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/pdf"
    assert r.content == b"%PDF-1.4 beta\n"

    # Alpha.pdf is a real renderable PDF now.
    r = api.get("/api/file", params={"path": "Alpha.pdf"}, headers=auth_headers())
    assert r.status_code == 200
    assert r.content.startswith(b"%PDF")


def test_file_rejects_non_pdf(api):
    r = api.get("/api/file", params={"path": "not_a_pdf.txt"}, headers=auth_headers())
    assert r.status_code == 404


def test_file_rejects_folder(api):
    r = api.get("/api/file", params={"path": "Sub Folder"}, headers=auth_headers())
    assert r.status_code == 404


# --------------------------------------------------------------------------- #
# Annotations
# --------------------------------------------------------------------------- #

def test_annotation_lifecycle(api):
    h = auth_headers()
    payload = {"text": "Key result", "color": "#FFEB3B",
               "quads": {"x0": 0.1, "y0": 0.1, "x1": 0.9, "y1": 0.2}}
    body = {
        "id": "ann-1",
        "paper_path": "Alpha.pdf",
        "page": 3,
        "kind": "highlight",
        "payload": payload,
        "schema_version": 1,
        "created_at": iso(datetime.now(timezone.utc) - timedelta(minutes=5)),
        "updated_at": iso(datetime.now(timezone.utc) - timedelta(minutes=1)),
    }
    r = api.put("/api/annotations", json=body, headers=h)
    assert r.status_code == 200
    assert r.json() == {"ok": True, "id": "ann-1", "applied": True}

    r = api.get("/api/annotations", params={"paper_path": "Alpha.pdf"}, headers=h)
    assert r.status_code == 200
    rows = r.json()
    assert len(rows) == 1
    assert rows[0]["id"] == "ann-1"
    assert rows[0]["kind"] == "highlight"
    assert rows[0]["page"] == 3
    assert rows[0]["payload"] == payload
    assert rows[0]["schema_version"] == 1
    assert rows[0]["deleted"] is False

    # update
    body["updated_at"] = iso(datetime.now(timezone.utc))
    body["payload"] = {"text": "Edited", "color": "#FF0000", "quads": {}}
    r = api.put("/api/annotations", json=body, headers=h)
    assert r.status_code == 200
    assert r.json()["applied"] is True

    r = api.get("/api/annotations", params={"paper_path": "Alpha.pdf"}, headers=h)
    assert r.json()[0]["payload"]["text"] == "Edited"

    # delete
    r = api.delete("/api/annotations/ann-1", headers=h)
    assert r.status_code == 200
    r = api.get("/api/annotations", params={"paper_path": "Alpha.pdf"}, headers=h)
    assert r.json() == []

    # deleting a missing annotation 404s
    r = api.delete("/api/annotations/ann-1", headers=h)
    assert r.status_code == 404


def test_annotation_lww_keeps_newer_edit(api):
    h = auth_headers()
    old = iso(datetime.now(timezone.utc) - timedelta(hours=2))
    newer = iso(datetime.now(timezone.utc) - timedelta(hours=1))
    body = {
        "id": "ann-2", "paper_path": "Alpha.pdf", "page": 1, "kind": "note",
        "payload": {"text": "v1"}, "updated_at": old,
    }
    api.put("/api/annotations", json=body, headers=h)

    body["payload"] = {"text": "v2"}; body["updated_at"] = newer
    api.put("/api/annotations", json=body, headers=h)

    # An older edit must not clobber the newer one.
    body2 = dict(body); body2["payload"] = {"text": "stale"}; body2["updated_at"] = old
    r = api.put("/api/annotations", json=body2, headers=h)
    assert r.json()["applied"] is False

    rows = api.get("/api/annotations", params={"paper_path": "Alpha.pdf"}, headers=h).json()
    assert rows[0]["payload"]["text"] == "v2"


def test_annotation_validation(api):
    h = auth_headers()
    base = {"paper_path": "Alpha.pdf", "page": 1, "kind": "note", "payload": {"text": "x"}}

    r = api.put("/api/annotations", json={**base, "page": 0}, headers=h)
    assert r.status_code == 422

    r = api.put("/api/annotations", json={**base, "kind": "meme"}, headers=h)
    assert r.status_code == 400

    r = api.put("/api/annotations", json={**base, "paper_path": "Sub Folder"},
                headers=h)
    assert r.status_code == 400

    r = api.put("/api/annotations", json={**base, "payload": {"x": "b"}, "schema_version": 99}, headers=h)
    # schema_version is informational on the server; accept extraneous but sane values


def test_annotation_payload_size_limit(api):
    h = auth_headers()
    big = {"blob": "x" * 1_100_000}
    r = api.put("/api/annotations",
                json={"id": "big", "paper_path": "Alpha.pdf", "page": 1,
                      "kind": "note", "payload": big},
                headers=h)
    assert r.status_code == 413


def test_request_body_size_middleware(api):
    h = auth_headers()
    huge = "y" * 3_000_000
    r = api.put("/api/annotations",
                json={"id": "huge", "paper_path": "Alpha.pdf", "page": 1,
                      "kind": "note", "payload": {"blob": huge}},
                headers=h)
    assert r.status_code == 413


def test_annotations_scoped_to_paper(api):
    h = auth_headers()
    api.put("/api/annotations", json={
        "id": "a", "paper_path": "Alpha.pdf", "page": 1, "kind": "bookmark",
        "payload": {"label": "p1"}}, headers=h)
    api.put("/api/annotations", json={
        "id": "b", "paper_path": "Sub Folder/nested.pdf", "page": 2, "kind": "note",
        "payload": {"text": "n"}}, headers=h)

    rows = api.get("/api/annotations", params={"paper_path": "Alpha.pdf"}, headers=h).json()
    assert [r["id"] for r in rows] == ["a"]
    rows = api.get("/api/annotations", params={"paper_path": "Alpha.pdf", "kind": "note"}, headers=h).json()
    assert rows == []


# --------------------------------------------------------------------------- #
# Progress
# --------------------------------------------------------------------------- #

def test_progress_defaults_to_page_1(api):
    h = auth_headers()
    r = api.get("/api/progress", params={"paper_path": "Alpha.pdf"}, headers=h)
    assert r.status_code == 200
    body = r.json()
    assert body["page"] == 1
    assert body["position"] is None


def test_progress_lifecycle(api):
    h = auth_headers()
    api.put("/api/progress", json={
        "paper_path": "Alpha.pdf", "page": 7,
        "position": {"scroll": 0.42, "scale": 1.0}}, headers=h)
    r = api.get("/api/progress", params={"paper_path": "Alpha.pdf"}, headers=h)
    body = r.json()
    assert body["page"] == 7
    assert body["position"] == {"scroll": 0.42, "scale": 1.0}


def test_progress_requires_valid_paper(api):
    h = auth_headers()
    r = api.put("/api/progress", json={"paper_path": "not_a_pdf.txt", "page": 2}, headers=h)
    assert r.status_code == 400
    r = api.put("/api/progress", json={"paper_path": "Alpha.pdf", "page": 0}, headers=h)
    assert r.status_code == 422


# --------------------------------------------------------------------------- #
# Server-side page rendering (Option B)
# --------------------------------------------------------------------------- #

def test_page_meta(api):
    r = api.get("/api/meta", params={"path": "Alpha.pdf"}, headers=auth_headers())
    assert r.status_code == 200
    body = r.json()
    assert body["path"] == "Alpha.pdf"
    assert body["page_count"] == 2
    assert len(body["pages"]) == 2
    for p in body["pages"]:
        assert p["width_pt"] > 0 and p["height_pt"] > 0
    assert body["pages"][0]["page"] == 1


def test_page_image_renders_png(api):
    r = api.get("/api/page", params={"path": "Alpha.pdf", "page": 1, "max_width": 600},
                headers=auth_headers())
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    assert r.content[:8] == b"\x89PNG\r\n\x1a\n"  # PNG signature
    assert len(r.content) > 500  # real rendered content, not an empty stub


def test_page_image_cache_hit(api):
    h = auth_headers()
    a = api.get("/api/page", params={"path": "Alpha.pdf", "page": 2, "max_width": 300}, headers=h)
    b = api.get("/api/page", params={"path": "Alpha.pdf", "page": 2, "max_width": 300}, headers=h)
    assert a.status_code == 200 and b.status_code == 200
    assert a.content == b.content


def test_page_image_out_of_range(api):
    h = auth_headers()
    assert api.get("/api/page", params={"path": "Alpha.pdf", "page": 0}, headers=h).status_code == 422
    assert api.get("/api/page", params={"path": "Alpha.pdf", "page": 99}, headers=h).status_code == 404


def test_page_requires_valid_pdf(api):
    h = auth_headers()
    assert api.get("/api/page", params={"path": "not_a_pdf.txt", "page": 1}, headers=h).status_code == 404
    assert api.get("/api/meta", params={"path": "not_a_pdf.txt"}, headers=h).status_code == 404


def test_page_endpoints_require_auth(api):
    assert api.get("/api/meta", params={"path": "Alpha.pdf"}).status_code == 401
    assert api.get("/api/page", params={"path": "Alpha.pdf", "page": 1}).status_code == 401


def test_page_meta_traversal_rejected(api):
    assert api.get("/api/meta", params={"path": "../.."}, headers=auth_headers()).status_code == 400
    assert api.get("/api/page", params={"path": "../../etc/passwd", "page": 1},
                   headers=auth_headers()).status_code == 400


def test_page_max_width_capped(api):
    r = api.get("/api/page", params={"path": "Alpha.pdf", "page": 1, "max_width": 20000},
                headers=auth_headers())
    assert r.status_code == 422  # le=2000 bound enforced by validation


# --------------------------------------------------------------------------- #
# No mutation of source papers
# --------------------------------------------------------------------------- #

def test_no_write_routes_exist(api):
    routes = {f"{sorted(r.methods)[0]} {r.path}" for r in api.app.routes if hasattr(r, "methods")}
    bad = [r for r in routes if r.startswith(("POST ", "DELETE ")) or
           any(m in r for m in ("/upload", "delete", "rename"))]
    # DELETE /api/annotations/{id} is fine; it mutates only the annotation store.
    assert not [r for r in bad if "annotations" not in r]