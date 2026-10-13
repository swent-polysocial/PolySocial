<!-- Contributors: OpenAI Codex (M1 delivery checklist, current evidence and release procedure, #138). -->

# M1 delivery checkpoint

Owner: Mehdi (@mehdifddl). Prepare on Thursday **15 October 2026**. Deadline: **Friday 16 October 2026 at 08:45**, Lausanne time.

This checklist follows the [M1 handout updated on 10 October](https://github.com/swent-epfl/public/blob/main/project/M1.md). It records readiness; it does not claim that the release is already complete.

## Team deliverables

- [ ] Figma named version exactly **M1**, with its version-history link on Wiki Home and public viewing verified.
- [ ] Sprint 2 architecture reviewed and mirrored to the wiki (#139).
- [ ] Product Backlog and Sprint 3 backlog ready on the Scrum Board, checked by their human owners.
- [ ] Green main CI and Sonar, sufficient meaningful coverage and an end-to-end test (#136).
- [ ] Stable installable APK linked from Wiki Home through its successful Actions run (#137); installation and startup checked on a phone.
- [ ] Sprint 2 stand-up and team retrospective recorded in Steve by their owners.

## Individual checks

- [ ] Each member completes their Sprint 2 retrospective in Steve.
- [ ] Significant code PRs meet the course timing and merge requirements for both sprints.
- [ ] Significant reviews approve the exact versions that are eventually merged, with contributor/AI credit.

## Current evidence and dependencies

| Item | Evidence / next action | State on 14 October |
|---|---|---|
| Repository | [PolySocial](https://github.com/swent-polysocial/PolySocial) | Available |
| Scrum Board | [Project 1](https://github.com/orgs/swent-polysocial/projects/1) | Link known; board contents require authorized project access |
| Agreed live designs | [First proposal revamped](https://www.figma.com/design/7Gt7tmy1ikpNyNBh1P6QdY/App-Mockup?node-id=123-467) | This is the live file, not the final M1 version link |
| Architecture | [PR #170](https://github.com/swent-polysocial/PolySocial/pull/170), closing #139 | Source/PNG and wiki mirror prepared; teammate review and publication pending |
| Main CI baseline | [Run 38443382277](https://github.com/swent-polysocial/PolySocial/actions/runs/38443382277), commit `6d79c3ea67b9d223432a8dc85f13e0400eecbbdc` | Passed; this run has **no APK artifact** |
| APK publication | [PR #176](https://github.com/swent-polysocial/PolySocial/pull/176), closing #137 | [Team approval recorded](https://github.com/swent-polysocial/PolySocial/issues/137#issuecomment-6155218847); [PR CI passed](https://github.com/swent-polysocial/PolySocial/actions/runs/38468864043); phone check and integration pending |
| Privacy | [PR #173](https://github.com/swent-polysocial/PolySocial/pull/173), #103 | Prototype implemented with authorized draft contact/provider fields; follow #173 for final validation and review |
| Final build / `M1` branch | Latest green main after all intended Sprint 2 PRs are merged | Pending; no `M1` branch exists yet |
| Group chat | Team's confirmed link for the Tooling page | Mehdi confirmed it is not defined yet; explicit draft placeholder prepared until supplied |

The proposed [Wiki Home](m1-wiki/Home.md) and [Tooling](m1-wiki/Tooling.md) content is included in this PR for review. The local wiki mirror also contains the architecture proposed by #170. Publish the reviewed sources with final verified links during finalization; the draft group-chat field remains explicit until Mehdi supplies the link.

The main baseline is useful evidence, but it must not be used as the final M1 build while the intended Sprint 2 work remains unmerged. Board/Steve checks belong to their human owners; this task does not edit the board or invent meeting records.

## Finalization procedure

1. Confirm the intended Sprint 2 PRs are merged, including the reviewed architecture, privacy notice and APK publication change. Do not use the temporary merge exception from #94/#96 for new PRs.
2. Select the final successful **push-to-main** Actions run. Verify its commit, CI/Sonar results and a non-expired `polysocial-apk` artifact. Download the APK and check installation and startup on a phone; record who actually checked it.
3. Create the Figma named version **M1** after the final intended design changes. Copy the version-history link and verify it can be opened by everyone. The live-file URL above is not a substitute.
4. Create `M1` from the exact main commit of the selected successful run. Do not overwrite an existing branch or silently point it at a newer untested main commit.
5. Publish the reviewed Architecture Diagram and its SVG/PNG to the wiki. Fill Home with the named Figma version, the artifact-bearing Actions run and the `M1` branch. Fill the Tooling links, including the confirmed group chat.
6. Check every deliverable above against the handout. Tick #138 only for work that is actually verified, and let the human owner update its board status.

## Release verification commands

Replace the placeholders with the selected final run and its verified commit:

```powershell
gh run view <run-id> --repo swent-polysocial/PolySocial --json status,conclusion,headSha,url
gh api repos/swent-polysocial/PolySocial/actions/runs/<run-id>/artifacts
git fetch origin
git ls-remote --heads origin M1
git branch M1 <verified-run-sha>
git push origin M1
```

These commands are instructions for the conditional finalization step. They have not been executed to create a release before the dependencies above are met.
