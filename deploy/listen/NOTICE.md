# Source and permissions

The source adapter uses these modules from **分你一只耳机 / one-earbud**, copyright 2026 Lili & Kravis.

- Repository https://github.com/isle0306ral-hub/one-earbud
- Pinned commit `ac2ae71916ed1f696e68c9a0edd15b7c7454209e`
- Vendored files `server/music/netease.js`, `qr.js`, and `store.js`
- Original `LICENSE` and `README.md` are retained in `vendor/one-earbud/`.
- `SOURCE.json` records the pinned commit and local vendored file hashes.

Local patches to the pinned modules make provider business errors explicit, bound response sizes and total request time, close interrupted responses, and propagate failed login-cookie deletion. `SOURCE.json` preserves the upstream blob hashes and lists the patched local blob hashes separately.

The upstream LICENSE and package metadata specify MIT. Its README separately says the project is for personal use with an AI companion and asks users to avoid commercial use. This adapter is prepared for the requested private instance. Clarify that inconsistent wording with the author before a commercial distribution.

The product combination draws on the music-room and song-journal ideas discussed for **eryu**, copyright 2026 Evelyn & River. This implementation does not include eryu source code or visual assets. Eryu's current CC BY-NC-SA 4.0 terms are documented at https://github.com/thefloshibisci/eryu/blob/main/NOTICE.md.

The source client uses NetEase Cloud Music's unofficial interfaces. Music availability follows the signed-in account, region, copyright availability, and upstream interface behavior.
