<!-- Contributors: OpenAI Codex (privacy notice, #103; one-off Map location, #51; verification startup and Back sign-out, #31), text moved here from the README. -->
<!-- Contributors: Claude (Fable 5.2, Anthropic) (moved these notes out of the README and removed two outdated sentences, #340). -->

# App behavior notes

Details that used to sit in the README's Features section. The decisions behind them are in [`CONTEXT.md`](../CONTEXT.md).

## Privacy notice

Profile → **Privacy & data** opens the current collection/access summary and a dated full notice, both bundled for offline reading. Actions that are not built yet show **Not available yet**. The prototype explicitly marks the controller contact and provider processing countries/safeguards as draft details. The private contact button explains that the address is pending; once the team supplies it, set `privacy_contact_email` in `app/src/main/res/values/strings.xml` to enable a user-controlled email draft with no account data attached. Replace `privacy_notice_draft_body` with the confirmed details at the same time. A missing email app is handled in the screen.

## One-off location on the Map

For Map distance badges, the app requests only approximate location. On first entry, the Map requests permission once if it is not already granted. The location service reads one foreground snapshot and keeps coordinates only in screen memory; it never stores or uploads them. Leaving the Map or backgrounding the app cancels any pending request and discards the snapshot without showing a location error. On return, use the floating **Refresh distance** button for another one-off fix; permission prompts and location reads do not repeat automatically. Only whether the permission prompt was requested is saved. Denying permission keeps map browsing available. **Turn on** asks again when Android allows another request, or opens this app's settings after a permanent denial. Granting permission there starts one fix on return.

## Startup routing and email verification

Signed-out users see Welcome on launch. Unverified sessions reopen Verify Email; verified sessions load their profile before opening the app or profile setup. Association accounts follow their own onboarding (see [`CONTEXT.md`](../CONTEXT.md)). The screen sends a verification email, supports resend with a persistent cooldown, and checks verification on Continue or browser return. After verification, shared startup routing loads the profile and opens the app or the profile step; failed profile reads show Retry. Both the system Back action and the back icon sign out and reset the forms, so reopening shows Welcome. Change address also signs out and opens a fresh sign-up form. Verification uses Firebase’s browser action page, so no Hosting or App Links setup is needed.
