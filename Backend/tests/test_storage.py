from pathlib import Path

from app.storage import install_file, iter_file, safe_path


def test_opaque_bytes_are_installed_and_read_unchanged(tmp_path: Path) -> None:
    content = b"opaque-xchacha-ciphertext\x00\xff" * 100_000
    source = tmp_path / "incoming.part"
    source.write_bytes(content)
    root = tmp_path / "storage"
    destination = safe_path(root, "user/media/id/original/00000000.chunk")

    install_file(source, destination)

    assert not source.exists()
    assert b"".join(iter_file(destination, 8191)) == content


def test_storage_paths_cannot_escape_root(tmp_path: Path) -> None:
    root = tmp_path / "storage"
    try:
        safe_path(root, "../outside")
    except RuntimeError as exc:
        assert "escapes" in str(exc)
    else:
        raise AssertionError("unsafe path was accepted")
