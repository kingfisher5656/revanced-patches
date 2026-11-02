# DCCon Tagger

A userscript that adds a search bar to the mobile DCInside DCCon panel. Stickers are found by AI tags: text in the image, emotion, action and situation. You can fix any sticker's tags by long-pressing it.

```
Windows PC (Firefox + Tampermonkey + KoboldCpp)   Your Nextcloud (over Tailscale)   Phone (Firefox + Tampermonkey)
  reads your DCCon packs, tags new stickers ──▶  DCConTagger/data/p_<pack>.json  ──▶  search
                                             ◀──▶ DCConTagger/data/edits.json ◀──▶  long-press → edit tags
```

There is one script file, `dccon-tagger.user.js`, for both devices. On the phone it adds search; on the PC it runs the tagger. The same search is also available inside the DCInside Android app through a Morphe patch (see step 4).

Instead of the PC's local AI, an AI coding agent such as Claude Code or Codex can do the tagging (see step 5):

```
DCInside app (Morphe patch)          Your Nextcloud                     Mac with Claude Code / Codex
  uploads your DCCon list ──▶  DCConTagger/data/catalog.json  ──▶  looks at contact sheets, writes tags
  search ◀──                   DCConTagger/data/p_<pack>.json ◀──
```

## Using it (phone)

- Open the DCCon panel and tap **🔍 디시콘 검색**. While you search, the pack tabs and grid are replaced by results; **✕** brings them back.
- What you can search:
  - Text in the image (`알겠어요`)
  - Emotion (`슬픔`, `화남`)
  - Situation (`감사`, `사과`, `인사`)
  - Character names or pack names
  - 초성 (`ㅇㄱㅇㅇ`)
- Separate words with spaces to narrow results (`미쿠 울음`). A few common words also match their synonyms, e.g. `고마워` ≈ `감사` ≈ `thanks`.
- **Tap** a result to send it, just like the normal grid. 더블콘 still works.
- **Long-press** any sticker, in results or in the normal grid, to edit its text and tags. Edits sync to your other devices and are never overwritten by the AI. **AI 태그로 되돌리기** undoes an edit.
- Dots on results: orange = not tagged by AI yet (only its title is searchable), navy = you edited it.
- With an empty search box you see your recently used stickers and recent searches.

## Setup

### 1. Nextcloud app password (once)

1. In Nextcloud's web page: avatar → **Personal settings → Security** → at the bottom, "Create new app password". Name it `DCCon Tagger`.
2. Note the **username** and the generated password. You'll enter both, plus your Nextcloud server address (for example `https://nextcloud.example.com`), in the script's settings on each device.

Both devices reach Nextcloud through Tailscale, so Tailscale must be on for syncing. Search itself works without it, from the phone's local copy.

### 2. Windows PC: the tagger

1. Set up a local vision AI. Pick **one**:
   - **KoboldCpp** (if you already run it): load a vision model **together with its matching mmproj file**. The mmproj is what lets KoboldCpp see images; without it the tagger refuses to run. Recommended: Qwen2.5-VL-7B-Instruct GGUF + its mmproj (or the 32B version for better accuracy). In the script's settings choose **OpenAI 호환 (KoboldCpp…)**; the address becomes `http://localhost:5001` and the model field can stay empty.
   - **Ollama**: install [Ollama for Windows](https://ollama.com/download), then double-click `setup-ollama-windows.bat`. It downloads the model and allows Firefox to talk to Ollama; afterwards quit Ollama from the tray and start it again.
   With an RTX 5090 you can also try `qwen2.5vl:32b`, which is slower but reads text and expressions better. Change the model name in the settings. Newer vision models such as `qwen3-vl` also work if Ollama has them.
2. Install Firefox, add the **Tampermonkey** extension, and install `dccon-tagger.user.js`. Open the Tampermonkey dashboard → "+" tab → paste the file → save.
3. Log in to DCInside in that Firefox.
4. Open **`https://gall.dcinside.com/#dct-tagger`**. The settings sheet opens automatically.
   - Fill in **Nextcloud 주소**, **사용자 이름**, **앱 비밀번호**. Leave **폴더** as `DCConTagger/data`. Tap **연결 확인 + 지금 동기화**; the log should say 동기화 완료. Then **저장**.
   - Tap **태깅 시작**. Progress shows in the log.
5. Leave that tab open. It tags new packs once an hour. Results upload to Nextcloud after every 25 stickers, so the phone sees progress as it goes. You can stop at any time; it continues where it left off.

If the log says Ollama refused the request (403), set the Windows environment variable `OLLAMA_ORIGINS` to `*`, then quit and restart Ollama from the tray.

### 3. Phone: Firefox for Android

1. Install the **Tampermonkey** add-on in Firefox for Android.
2. Install the script. Either:
   - Open `dccon-tagger.user.js` in the Nextcloud app, copy all of its text, then in Tampermonkey's dashboard tap **+** (new script), replace everything with the copied text and save; or
   - Open a URL ending in `.user.js` in Firefox (e.g. a Nextcloud share link to the file with `/download` added) and Tampermonkey will offer to install it.
3. Open any post on m.dcinside.com, open the DCCon panel → **🔍 디시콘 검색** → ⚙ (bottom right). Enter the same Nextcloud address, username and app password as on the PC, tap **연결 확인 + 지금 동기화**, then **저장**.

The phone reads your pack list itself, so search works right away by sticker title. AI tags fill in as the PC finishes packs.

### 4. Phone: the DCInside app (optional, instead of Firefox)

The Morphe patches (the `morphe-patches` folder next to this README, or the root of the GitHub repository) add the same search to the DCInside Android app (version 5.3.20) with a Morphe patch called **DCCon search**. It reads the same Nextcloud folder, so the PC tagger and edits from Firefox show up in the app too.

1. Build the patch bundle (once per change). You need a GitHub token, because Morphe's libraries are hosted on GitHub Packages:
   - On GitHub: Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate. Tick only `read:packages`.
   - Add two lines to `~/.gradle/gradle.properties`: `gpr.user=<your GitHub username>` and `gpr.key=<the token>`.
   - Install the Android SDK (Android Studio does this), then in the patches folder run `./gradlew :patches:buildAndroid`. The bundle is `patches/build/libs/patches-*.mpp`.
2. Patch the APK with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) on the Mac, or copy the `.mpp` to the phone and add it in Morphe Manager. Select the DCInside 5.3.20 APK (not included in the repository). Keep **Spoof Signature** selected; the app refuses to work without it. If you own many DCCon packs (around 90 or more), also select **Fix DCCon list loading**: without it the picker spins and then says 사용 가능한 디시콘이 없습니다. The app asks the server for the whole pack list at once; with that many packs the server gives up after 30 seconds and sends back an empty list. The patch loads the list one page at a time instead (about 4 seconds for 99 packs). Choose any other DCInside patches you like.

   To patch on the Mac without Morphe Manager (for testing), run `./gradlew :patches:patchApk --args="<apk> <patches.mpp> <output.apk> <keystore> - Morphe Morphe"` in the patches folder. It applies the default patch selection and signs with a Morphe Manager keystore exported from the phone, so `adb install -r` updates the installed app in place. The `-` is the keystore's empty store password; `Morphe`/`Morphe` are Manager's default alias and key password.
3. Uninstall the Play Store DCInside app (signatures differ), install the patched APK and log in.

Using it in the app:

- In the DCCon picker, tap the new 🔍 button left of the pack tabs. A search sheet opens.
- Search works like the userscript. **Tap** a result to pick it, as in the normal grid (더블콘 works). **Long-press** a result to edit its text and tags. Long-press in the app's own grid still adds to favorites, so edit tags from search results.
- ⚙ at the bottom right holds the Nextcloud settings. Enter the same address, username and app password as on the PC, tap **연결 확인 + 지금 동기화**, then **저장**.
- With an empty search box you see recently used DCCons, including ones picked from the normal grid.

### 5. Tagging with an AI agent (optional, instead of the PC tagger)

Claude Code, Codex or a similar agent can tag your DCCons by looking at them, on a Mac that syncs the Nextcloud folder with the Nextcloud desktop app. It writes the same `p_<pack>.json` files as the PC tagger, so both can be used, and your manual edits still win.

1. In the DCInside app, set up Nextcloud in DCCon search (⚙) as in step 4, then open the DCCon picker once. The app uploads `catalog.json`, the list of every DCCon you own, and uploads it again whenever the list changes.
2. Open this folder in Claude Code and ask it to tag your DCCons (for example "디시콘 200개 태그해 줘"). It uses the `tag-dccons` skill. In Codex, `AGENTS.md` points it to the same instructions.
3. The agent works in batches: `tools/agent-tagger/dct.py next` draws 16 untagged DCCons into one numbered image, the agent tags them, and `dct.py submit` saves the tags. `python3 tools/agent-tagger/dct.py status` shows the progress at any time.

The full instructions and tagging rules are in [tools/agent-tagger/TAGGING.md](tools/agent-tagger/TAGGING.md). Tags appear in the app after its next sync (every 10 minutes while searching, or right away with 연결 확인 + 지금 동기화).

## Notes

- Animated GIF stickers are tagged from their first frame.
- `선택 팩 다시 태깅…` in settings clears one pack's AI tags, for example after switching to a better model. Your manual edits are kept.
- All data lives in the `DCConTagger/data` folder of your Nextcloud. The phone keeps a local copy, so search works offline or without Tailscale; it catches up the next time it can reach Nextcloud.
- Errors in the log: "로그인 실패" means wrong username or app password; "연결할 수 없습니다" means Tailscale is off or the server is down.
