"""Inspeção de ZIP sem extração ou escrita no armazenamento."""
import io
import re
import zipfile

MAX_ENTRIES = 20000
MAX_EXPANDED = 256 * 1024 * 1024
MAX_REPORTED = 500


def inspect(data):
    entries, unsafe, total, unsafe_count, safe_count = [], [], 0, 0, 0
    try:
        with zipfile.ZipFile(io.BytesIO(data), "r") as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ENTRIES:
                raise ValueError("ZIP excede o limite de 20 mil itens.")
            for info in infos:
                name = info.filename.replace("\\", "/")
                shown_name = name[:512]
                if name.startswith("/") or re.match(r"^[A-Za-z]:/", name) or any(part == ".." for part in name.split("/")):
                    unsafe_count += 1
                    if len(unsafe) < MAX_REPORTED:
                        unsafe.append(shown_name)
                    continue
                total += info.file_size
                if total > MAX_EXPANDED:
                    raise ValueError("ZIP excede 256 MiB descompactados.")
                safe_count += 1
                if len(entries) < MAX_REPORTED:
                    entries.append({"name": shown_name, "size": info.file_size, "compressed": info.compress_size})
    except zipfile.BadZipFile as exc:
        raise ValueError("O arquivo selecionado não contém um ZIP válido.") from exc
    return {"format": "ZIP", "status": "ANALISADO", "entries": entries,
            "unsafe_entries": unsafe, "expanded_bytes": total,
            "omitted_unsafe_entries": max(0, unsafe_count - len(unsafe)),
            "omitted_entries": max(0, safe_count - len(entries)),
            "message": "Nenhum item foi extraído nem alterado."}
