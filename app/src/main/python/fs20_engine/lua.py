"""Análise lexical aproximada de Lua. Nunca interpreta ou executa código."""
import re


def inspect(data):
    if len(data) > 2 * 1024 * 1024:
        raise ValueError("Lua acima do limite de 2 MiB.")
    text = data.decode("utf-8-sig", errors="replace")
    if "\x00" in text:
        raise ValueError("Arquivo Lua contém bytes nulos.")
    stack, quote, line, errors = [], None, 1, []
    omitted_errors = 0
    for match in re.finditer(r'--\[=*\[.*?\]=*\]|--[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[(){}\[\]]|\n', text, re.S):
        token = match.group(0)
        if token == "\n":
            line += 1
        elif token.startswith("--") or token.startswith('"') or token.startswith("'"):
            continue
        elif token in "([{":
            stack.append((token, line))
        elif token in ")]}":
            pairs = {")": "(", "]": "[", "}": "{"}
            if not stack or stack[-1][0] != pairs[token]:
                if len(errors) < 100:
                    errors.append({"line": line, "message": "Delimitador sem par correspondente."})
                else:
                    omitted_errors += 1
            else:
                stack.pop()
    for _, start in stack:
        if len(errors) < 100:
            errors.append({"line": start, "message": "Delimitador não fechado."})
        else:
            omitted_errors += 1
    return {"format": "LUA", "status": "ANALISE_ESTATICA", "errors": errors,
            "omitted_errors": omitted_errors,
            "message": "Verificação lexical básica; não é validação por interpretador Lua."}
