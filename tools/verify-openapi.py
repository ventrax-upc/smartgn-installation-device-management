"""Verify a running service's OpenAPI against its endpoint documentation."""

import json
import os
from pathlib import Path
from urllib.request import urlopen


def main():
    root = Path(__file__).resolve().parents[1]
    expected = json.loads((root / "src/main/resources/openapi/operations.json").read_text(encoding="utf-8"))
    url = os.environ.get("OPENAPI_URL")
    if not url:
        raise SystemExit("Set OPENAPI_URL to the running service's /v3/api-docs URL")
    with urlopen(url, timeout=90) as response:
        api = json.load(response)
    assert api["info"]["title"] == expected["title"], "Incorrect API title"
    assert api["info"]["version"] == expected["version"] == "v1", "Incorrect API version"
    tags = {tag["name"]: tag.get("description") for tag in api["tags"]}
    assert tags == expected["tags"], "Incorrect tag descriptions"
    actual = {
        (method.upper(), path): operation
        for path, item in api["paths"].items()
        for method, operation in item.items()
        if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
    }
    definitions = {(item["method"], item["path"]): item for item in expected["operations"]}
    assert len(definitions) == len(expected["operations"]), "Duplicate endpoint documentation"
    assert actual.keys() == definitions.keys(), "Undocumented or obsolete endpoint definitions"
    for key, definition in definitions.items():
        operation = actual[key]
        assert operation["summary"] == definition["summary"], f"Incorrect summary: {key}"
        assert operation["description"] == definition["description"], f"Incorrect description: {key}"
        assert operation["tags"] == [definition["tag"]], f"Incorrect group: {key}"
        security = [{definition["security"]: []}] if definition["security"] else []
        assert operation.get("security", []) == security, f"Incorrect authentication: {key}"
        responses = operation["responses"]
        assert str(definition["success"]) in responses, f"Incorrect success response: {key}"
        if definition["success"] == 204:
            assert not responses["204"].get("content"), f"Unexpected 204 body: {key}"
    print(f'{expected["title"]}: v1, {len(tags)} groups, {len(actual)} documented operations verified')


if __name__ == "__main__":
    main()
