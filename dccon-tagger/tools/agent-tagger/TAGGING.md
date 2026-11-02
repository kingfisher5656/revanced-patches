# Tagging DCCons as an AI agent

You are tagging DCCons (디시콘, chat stickers from the Korean community DCInside) so the user can find them later by searching in the DCInside app. The patched app uploads the list of owned DCCons to Nextcloud as `catalog.json`; you write tags into `p_<pack>.json` files in the same folder, which the app and the userscript download.

All commands run from this folder (`tools/agent-tagger`) with `python3 dct.py …`. They need only Python 3 and, for the contact sheets, `swiftc` (any Mac with Xcode or the Command Line Tools).

## Loop

1. `python3 dct.py status` shows how many DCCons are left. If it says `catalog.json` is missing, stop and tell the user to set up Nextcloud in the app (디시콘 검색 → ⚙) and open the DCCon picker once.
2. `python3 dct.py next` downloads the next 16 untagged DCCons and draws them into `work/sheet.png`, numbered 1–16 under each image. It also prints each number's pack title and the uploader's name for it.
3. Look at `work/sheet.png` (Claude Code: Read the file; Codex: view the image). A label like `7 ▶24` means number 7 is animated with 24 frames; the sheet shows its middle frame. When an animated DCCon's text or action is unclear from one frame, run `python3 dct.py frames 7` and look at `work/frames.png`.
4. Write `work/answers.json` with one entry per number (format below).
5. `python3 dct.py submit --model <name>`, where `<name>` says who tagged: `claude-code` or `codex`, optionally with the model (`claude-code/opus-5.5`).
6. Repeat from step 2 until the user's requested amount is done or nothing is left. Report how many you tagged and how many remain.

Useful options: `next --pack <id or part of the title>` tags one pack; `next --count 25 --cols 5` makes bigger batches; `next --discard` throws away an unfinished batch.

## answers.json

```json
{
  "1": {
    "text": "감사합니다",
    "emotion": ["기쁨"],
    "action": ["절하기"],
    "keywords": ["감사", "고마워", "인사", "thanks", "thank you", "분홍머리", "니케"],
    "desc": "분홍 머리 캐릭터가 꾸벅 절하며 감사하다고 말한다."
  },
  "2": { "skip": true }
}
```

Use `{"skip": true}` only when an image failed to load (labelled `오류`) or is blank. Skipped DCCons stay untagged and come back in a later batch.

## Tagging rules

The pack title and the uploader's name are hints only; uploader names are often just numbers or meaningless.

- **text**: the words written in the image, copied exactly as shown (keep spelling, ㅋㅋ, punctuation). Empty string if there is none.
- **emotion**: 1–3 of the character's emotions, chosen from: 기쁨, 웃음, 신남, 슬픔, 울음, 화남, 짜증, 놀람, 당황, 부끄러움, 사랑, 귀여움, 졸림, 피곤, 무표정, 멍함, 의기양양, 비웃음, 겁먹음, 걱정, 혐오, 감동, 진지, 장난, 삐짐
- **action**: 0–4 actions or situations, e.g. 엄지척, 손인사, 먹기, 손가락질, 박수, 도망, 잠자기, 손하트, 절하기, 춤.
- **keywords**: 5–10 extra search words: when someone would send this sticker in a conversation (감사, 사과, 동의, 거절, 축하, 인사, 질문, 응원 …), the character's name or features, objects in the image, memes or catchphrases, plus 2–3 English synonyms.
- **desc**: one Korean sentence describing the sticker.

**Wide DCCons made of two halves.** Some packs split one wide picture into two DCCons meant to be sent side by side (e.g. a hand on the left one poking a cheek on the right one). The sheet keeps each pack's order, so the halves are usually next to each other; a half often looks cut off at its left or right edge. Tag each half for what it shows, but give both the same situation keywords plus `세트콘`, `합체콘` and the matching side (`왼쪽` or `오른쪽`), and say in `desc` what the combined picture shows and which number is its other half. If the other half isn't in this batch, still tag the half you see that way.

Write everything except the English synonyms in Korean. Tag what is actually visible; don't guess a character's name unless the pack title or the image makes it clear.

## Files

- `catalog.json` (written by the app): `{"packs": [{"id", "title", "icon", "dccons": [{"d", "alt", "img"}]}]}`.
- `p_<pack>.json` (written by `submit` and by the PC userscript): `{"id", "title", "icon", "s": {"<d>": {"img", "alt", "t", "e", "k", "d", "m", "at"}}}`. `submit` only adds or replaces the entries it tagged, so it never removes other taggers' work. Manual edits from the app live in `edits.json` and always win over AI tags; never touch that file.
- `work/` holds downloaded images and the current batch; it is safe to delete between batches.
