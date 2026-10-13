<!-- Contributors: OpenAI Codex (recorded the team Figma asset exports for #103). -->

# Privacy screen assets

The team owns the design in [First proposal revamped](https://www.figma.com/design/7Gt7tmy1ikpNyNBh1P6QdY/App-Mockup?node-id=124-468).

OpenAI Codex exported the original Figma SVG assets to transparent PNGs at six times their natural size using the bundled Sharp runtime. Compose displays them at their original 18, 20 or 22 dp size; the paths and appearance were not redrawn. The images under `drawable-nodpi` require no network access. Their file format does not support contributor comments, so credit is recorded here and in the PR.

| Resource prefix `privacy_` | Figma node | Display size |
|---|---|---|
| `back` | 124:469 | 22 dp |
| `person`, `pin`, `message`, `check` | 124:475, 124:480, 124:485, 124:489 | 18 dp |
| `download`, `info`, `delete` | 124:498, 124:504, 124:511 | 20 dp |
| `chevron` | 124:501 / 124:508 | 18 dp |
| `shield` | 136:554 | 20 dp |

Issue #103 updates the prototype wording to the current collection/access behavior. The attendance toggle stays Off and shows the unavailable message; it does not imply consent for matching. Mehdi authorized explicit draft contact/provider fields for the school prototype. The full notice's contact action uses the existing message icon; it opens an email draft when the team's private address is supplied, and otherwise explains that configuration is pending.
