<!-- Contributors: OpenAI Codex (recorded the team Figma asset exports and shared-vector reuse for #103). -->

# Privacy screen assets

The team owns the design in [First proposal revamped](https://www.figma.com/design/7Gt7tmy1ikpNyNBh1P6QdY/App-Mockup?node-id=124-468).

The screen reuses the team's existing Figma vectors for Back, check, chevron, info, pin and shield (`ic_back`, `ic_check`, `ic_chevron_right`, `ic_info`, `ic_pin`, `ic_shield`). Their original contributor headers remain unchanged. The privacy UI supplies its text/icon color, so the shared info and pin vectors keep the notice's appearance.

For the four icons without a shared equivalent, OpenAI Codex exported the original Figma SVG assets to transparent PNGs at six times their natural size using the bundled Sharp runtime. Compose displays them at their original 18 or 20 dp size. The images under `drawable-nodpi` require no network access; their format does not support contributor comments, so credit is recorded here and in the PR.

| Resource prefix `privacy_` | Figma node | Display size |
|---|---|---|
| `person`, `message` | 124:475, 124:485 | 18 dp |
| `download`, `delete` | 124:498, 124:511 | 20 dp |

Issue #103 updates the prototype wording to the current collection/access behavior. The attendance toggle stays Off and shows the unavailable message; it does not imply consent for matching. Mehdi authorized explicit draft contact/provider fields for the school prototype. The full notice's contact action uses the existing message icon; it opens an email draft when the team's private address is supplied, and otherwise explains that configuration is pending.
