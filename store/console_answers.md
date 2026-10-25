# Play Console answers

Answers for every Play Console declaration, matching what the code does. Verify each against the build you upload: the merged release manifest has zero `<uses-permission>` entries, and no networking code, SDK or analytics library is included (see `DECISIONS.md`, M0 and M6).

## App content

| Declaration | Answer |
| --- | --- |
| Privacy policy | Host `privacy_policy.html` (for example on GitHub Pages) and paste its URL. The same text is in the game under Settings › Privacy policy. |
| Ads | No, the app does not contain ads. |
| App access | All functionality is available without special access (no login, no account). |
| Content rating | Complete the IARC questionnaire with the answers below. |
| Target audience and content | See below. |
| News app | No. |
| COVID-19 contact tracing or status | No. |
| Data safety | See below. |
| Government app | No. |
| Financial features | None. |
| Health | Not a health app. |
| Actions on Google, Advertising ID | Not used. The game does not use the advertising ID (the permission is removed from the manifest). |

## Data safety

- Does your app collect or share any of the required user data types? **No.**
- Is all of the user data collected by your app encrypted in transit? Not applicable: no data is collected or transmitted.
- Do you provide a way for users to request that their data is deleted? Not applicable: nothing leaves the device. Players can delete their on-device progress with Settings › Reset progress, or by uninstalling.
- Resulting label: **No data collected. No data shared with third parties.**

Notes for reviewers: game progress is stored only on the device (DataStore). Android Auto Backup may include it in the player's own device backup, which Google does not treat as collection by the developer.

## Content rating (IARC questionnaire)

- Category: Game.
- Violence: none. (A seagull swoops down and lands; the turtles tuck into their shells. Nothing is hurt.)
- Fear: none. Sexuality or nudity: none. Language: none. Controlled substances: none.
- Gambling or simulated gambling: none. No loot boxes, no random paid rewards.
- User interaction: none. No chat, no sharing, no user-generated content.
- Shares location: no. Digital purchases: none.
- Expected ratings: ESRB Everyone, PEGI 3, USK 0, ClassInd L, IARC 3+.

## Target audience

Recommended: **13 and over** (or 13–15, 16–17 and 18+). This keeps review simplest. The game also meets the Families policy if you include younger age groups: it has no ads, no data collection, no third-party SDKs and no external links. If you choose any group under 13, also complete the Families declarations and keep the listing free of anything that implies the game is only for children.

## Store settings

- App or game: Game. Category: Puzzle.
- Contains ads: No. In-app purchases: No.
- Countries: all, unless you choose otherwise. The game is English-only; text is ready for translation.

## Release setup

- Play App Signing: on. Upload the AAB signed with your upload key (`tools/release/create_upload_key.sh`, then `./gradlew bundleRelease`).
- Device catalogue: no exclusions needed. The app requires OpenGL ES 3.0 and supports arm64-v8a, armeabi-v7a and x86_64, phones, tablets, foldables and ChromeOS.
- Personal developer accounts created after 13 November 2023: run a closed test with at least 12 opted-in testers for the last 14 continuous days before applying for production. Use `test_notes.md` to plan it and record feedback.
