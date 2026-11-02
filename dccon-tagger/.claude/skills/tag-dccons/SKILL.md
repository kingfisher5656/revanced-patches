---
name: tag-dccons
description: Tag the user's DCCons (디시콘) with text, emotion and search keywords by looking at contact sheets, and save them to the Nextcloud folder the DCInside app syncs. Use when the user asks to tag DCCons, continue tagging, or check tagging progress.
---

Follow `tools/agent-tagger/TAGGING.md` exactly. Run the commands from `tools/agent-tagger`, view `work/sheet.png` with the Read tool, and submit with `--model claude-code`. Tag as many batches as the user asked for (default: keep going until they stop you), and finish with how many you tagged and how many remain.
