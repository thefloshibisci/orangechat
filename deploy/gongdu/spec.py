"""Construct a Zeabur schema without placing the access token in source control."""

import json
from pathlib import Path


def build_schema(auth_token: str) -> dict:
    # Blank AUTH_TOKEN explicitly disables upstream authentication. Never submit
    # the checked-in, empty template directly to the provisioning mutation.
    if len(auth_token) < 32 or auth_token != auth_token.strip():
        raise ValueError("Provide a generated access token with at least 32 characters")
    path = Path(__file__).with_name("zeabur-service.schema.json")
    schema = json.loads(path.read_text(encoding="utf-8"))
    entry = next(item for item in schema["env"] if item["key"] == "AUTH_TOKEN")
    entry["default"] = auth_token
    return schema
