<!-- Contributors: Mehdi (@mehdifddl), human author; OpenAI Codex (prepared the mockup and documented the Figma publication blocker, #235). -->

# Edit profile proposal (#235)

Draft for [PR #476](https://github.com/swent-polysocial/PolySocial/pull/476), prepared on behalf of Mehdi (@mehdifddl), with OpenAI Codex assistance. The screenshots below are mockups, not device-test results. No teammate approval is claimed.

![Nine Edit profile states](edit-profile.png)

[Editable SVG](edit-profile.svg) for importing into the team's Figma file when editing is restored. The SVG uses Plus Jakarta Sans, the existing product font; it contains editable text and does not embed the font binary.

The nine states cover unchanged and edited values, section and year menus, saving, offline failure, failed save, loading and failed loading. Offline and save failures preserve the form and offer Retry. Scope is section, year and interests; username and bio belong to later issues.

## Figma publication blocker — 19 November 2026

Figma displays: “Ce fichier fait partie d'une équipe verrouillée.” It states that all team work is currently in consultation-only mode. The connected account reports a View seat and the write tool fails with “Can't call createSection in read-only mode”. This is an external team-access blocker, not completion of the Figma acceptance criterion.

Once the team is unlocked, import the SVG into [App Mockup](https://www.figma.com/design/7Gt7tmy1ikpNyNBh1P6QdY/App-Mockup?node-id=123-467), check its font and editable layers, and replace this temporary reference in PR #476 and issue #235 with the native frame link. The Figma acceptance criterion remains open until that happens and the design is reviewed.
