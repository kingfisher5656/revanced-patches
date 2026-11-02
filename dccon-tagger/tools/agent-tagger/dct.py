#!/usr/bin/env python3
"""DCCon tagging helper for AI agents (Claude Code, Codex, ...).

The patched DCInside app uploads catalog.json (every owned pack and DCCon) to the Nextcloud folder.
This tool picks untagged DCCons from it, draws them into one numbered contact sheet for the agent to look at,
and merges the agent's tags into p_<pack>.json, the same files the app and the userscript read.

    python3 dct.py status                 how many DCCons are tagged, per pack
    python3 dct.py next [--count 16]      start a batch: download images, draw work/sheet.png
    python3 dct.py frames <n>             frames of animated DCCon <n> in the batch, as work/frames.png
    python3 dct.py submit --model NAME    merge work/answers.json into the pack files

See TAGGING.md for the agent's instructions.
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")
IMAGES = os.path.join(WORK, "img")
BATCH = os.path.join(WORK, "batch.json")
ANSWERS = os.path.join(WORK, "answers.json")
SHEET = os.path.join(WORK, "sheet.png")
FRAMES = os.path.join(WORK, "frames.png")
SHEET_TOOL = os.path.join(WORK, "bin", "sheet")

# DCInside's image server refuses requests without a DCInside referer.
HEADERS = {"Referer": "https://m.dcinside.com/", "User-Agent": "Mozilla/5.0 (Linux; Android 14) DCConTagger"}


def fail(message):
    sys.exit("오류: " + message)


# region Data folder


def data_dir(args):
    path = args.data or os.environ.get("DCT_DATA")
    if not path:
        found = glob.glob(os.path.expanduser("~/Library/CloudStorage/Nextcloud-*/DCConTagger/data"))
        if not found:
            fail("Nextcloud의 DCConTagger/data 폴더를 찾지 못했습니다. --data <폴더> 또는 DCT_DATA로 지정하세요.")
        path = found[0]
    if not os.path.isdir(path):
        fail("폴더가 없습니다: " + path)
    return path


def read_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def write_json(path, value):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


def load_catalog(data):
    catalog = read_json(os.path.join(data, "catalog.json"))
    if not catalog or not catalog.get("packs"):
        fail("catalog.json이 없습니다. 디시 앱에서 디시콘 검색 ⚙에 Nextcloud를 설정하고 디시콘 피커를 한 번 여세요.")
    return catalog


def load_pack(data, pack_id):
    return read_json(os.path.join(data, f"p_{pack_id}.json"))


def is_tagged(pack_file, detail):
    entry = ((pack_file or {}).get("s") or {}).get(detail)
    return bool(entry) and "at" in entry and "err" not in entry


def untagged(data, catalog, pack_filter=None):
    """(pack, dccon) pairs without AI tags, in the picker's order."""
    out = []
    for pack in catalog["packs"]:
        if pack_filter and pack_filter != pack["id"] and pack_filter.lower() not in pack["title"].lower():
            continue
        pack_file = load_pack(data, pack["id"])
        out.extend((pack, d) for d in pack["dccons"] if not is_tagged(pack_file, d["d"]))
    return out


# endregion

# region Commands


def cmd_status(args):
    data = data_dir(args)
    catalog = load_catalog(data)
    total = tagged = 0
    rows = []
    for pack in catalog["packs"]:
        pack_file = load_pack(data, pack["id"])
        done = sum(1 for d in pack["dccons"] if is_tagged(pack_file, d["d"]))
        total += len(pack["dccons"])
        tagged += done
        if done < len(pack["dccons"]):
            rows.append((pack, done))
    uploaded = catalog.get("at", 0)
    age = f"{(time.time() * 1000 - uploaded) / 3600000:.1f}시간 전 업로드" if uploaded else "업로드 시각 모름"
    print(f"폴더: {data}")
    print(f"catalog.json: 팩 {len(catalog['packs'])}개, {age}")
    print(f"태그 완료 {tagged}/{total}, 남은 디시콘 {total - tagged}개")
    for pack, done in rows[: args.limit]:
        print(f"  {pack['id']:>8}  {done:>3}/{len(pack['dccons']):<3}  {pack['title']}")
    if len(rows) > args.limit:
        print(f"  … 외 {len(rows) - args.limit}개 팩")
    if os.path.exists(BATCH):
        print("진행 중인 배치가 있습니다 (work/batch.json). submit 하거나 next --discard 로 버리세요.")


def download(item):
    path = item["file"]
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return None
    try:
        request = urllib.request.Request(item["img"], headers=HEADERS)
        with urllib.request.urlopen(request, timeout=30) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
        return None
    except Exception as ex:  # noqa: BLE001 - reported per image
        return f"{item['n']}: {ex}"


def sheet_tool():
    source = os.path.join(HERE, "sheet.swift")
    if not os.path.exists(SHEET_TOOL) or os.path.getmtime(SHEET_TOOL) < os.path.getmtime(source):
        os.makedirs(os.path.dirname(SHEET_TOOL), exist_ok=True)
        print("시트 도구 컴파일 중 (처음 한 번)…", file=sys.stderr)
        subprocess.run(["swiftc", "-O", "-o", SHEET_TOOL, source], check=True)
    return SHEET_TOOL


def cmd_next(args):
    data = data_dir(args)
    if os.path.exists(BATCH):
        if not args.discard:
            fail("진행 중인 배치가 있습니다. 먼저 submit 하세요. 버리려면 --discard 를 붙이세요.")
        os.remove(BATCH)
    if os.path.exists(ANSWERS):
        os.remove(ANSWERS)

    catalog = load_catalog(data)
    todo = untagged(data, catalog, args.pack)
    if not todo:
        print("태그할 디시콘이 없습니다. 모두 끝났습니다." if not args.pack else "이 팩에는 태그할 디시콘이 없습니다.")
        return

    os.makedirs(IMAGES, exist_ok=True)
    items = []
    for n, (pack, d) in enumerate(todo[: args.count], start=1):
        items.append({
            "n": n, "pack": pack["id"], "packTitle": pack["title"], "packIcon": pack.get("icon", ""),
            "d": d["d"], "alt": d.get("alt", ""), "img": d["img"],
            "file": os.path.join(IMAGES, f"{pack['id']}_{d['d']}"),
        })
    with ThreadPoolExecutor(max_workers=6) as pool:
        errors = [e for e in pool.map(download, items) if e]
    if errors:
        print("다운로드 실패 (시트에 '오류'로 표시):", *errors, sep="\n  ", file=sys.stderr)

    subprocess.run([sheet_tool(), "grid", SHEET, str(args.cols), str(args.cell)] + [i["file"] for i in items], check=True)
    write_json(BATCH, {"created": int(time.time() * 1000), "items": items})

    print(f"남은 디시콘 {len(todo)}개 중 {len(items)}개로 배치를 만들었습니다.")
    print(f"시트: {SHEET}")
    print("번호. [팩 이름] 업로더가 붙인 이름")
    for i in items:
        print(f"{i['n']:>3}. [{i['packTitle']}] {i['alt']}")
    print(f"답을 {ANSWERS} 에 쓰고 submit 하세요. 형식은 TAGGING.md 참고.")


def batch_items():
    batch = read_json(BATCH)
    if not batch:
        fail("진행 중인 배치가 없습니다. 먼저 next 를 실행하세요.")
    return batch["items"]


def cmd_frames(args):
    items = {i["n"]: i for i in batch_items()}
    item = items.get(args.n) or fail(f"배치에 {args.n}번이 없습니다.")
    subprocess.run([sheet_tool(), "frames", FRAMES, "4", "200", item["file"]], check=True)
    print(f"{args.n}번 [{item['packTitle']}] {item['alt']} 의 프레임: {FRAMES}")


def as_list(value):
    if value is None:
        return []
    if isinstance(value, str):
        value = [value]
    out = []
    for v in value:
        v = str(v).strip()
        if v and v not in out:
            out.append(v)
    return out


def cmd_submit(args):
    data = data_dir(args)
    items = batch_items()
    answers = read_json(ANSWERS) or fail(f"{ANSWERS} 가 없습니다.")
    if isinstance(answers, list):
        answers = {str(a.get("n")): a for a in answers}
    answers = {str(k): v for k, v in answers.items()}

    missing = [i["n"] for i in items if str(i["n"]) not in answers]
    if missing:
        fail("답이 없는 번호: " + ", ".join(map(str, missing)) + " (건너뛰려면 {\"skip\": true})")

    now = int(time.time() * 1000)
    by_pack = {}
    skipped = 0
    for item in items:
        answer = answers[str(item["n"])]
        if answer.get("skip"):
            skipped += 1
            continue
        entry = {
            "img": item["img"],
            "alt": item["alt"],
            "t": str(answer.get("text", "")).strip(),
            "e": as_list(answer.get("emotion")),
            "k": as_list(as_list(answer.get("action")) + as_list(answer.get("keywords"))),
            "d": str(answer.get("desc", "")).strip(),
            "m": args.model,
            "at": now,
        }
        by_pack.setdefault(item["pack"], []).append((item, entry))

    for pack_id, entries in by_pack.items():
        path = os.path.join(data, f"p_{pack_id}.json")
        first = entries[0][0]
        pack_file = read_json(path) or {"id": pack_id, "title": first["packTitle"], "icon": first["packIcon"], "s": {}}
        pack_file.setdefault("s", {})
        for item, entry in entries:
            pack_file["s"][item["d"]] = entry
        write_json(path, pack_file)

    os.remove(BATCH)
    os.remove(ANSWERS)
    written = sum(len(e) for e in by_pack.values())
    remaining = len(untagged(data, load_catalog(data)))
    print(f"{written}개 저장 ({len(by_pack)}개 팩), 건너뜀 {skipped}개. 남은 디시콘 {remaining}개.")


# endregion


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", help="Nextcloud DCConTagger/data 폴더 (기본: 자동으로 찾음)")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("status", help="태그 진행 상황")
    p.add_argument("--limit", type=int, default=20, help="보여줄 팩 수")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("next", help="다음 배치 만들기")
    p.add_argument("--count", type=int, default=16)
    p.add_argument("--cols", type=int, default=4)
    p.add_argument("--cell", type=int, default=200, help="시트 칸 크기(px)")
    p.add_argument("--pack", help="이 팩만 (ID 또는 이름 일부)")
    p.add_argument("--discard", action="store_true", help="진행 중인 배치를 버리고 새로 만들기")
    p.set_defaults(func=cmd_next)

    p = sub.add_parser("frames", help="움직이는 디시콘의 프레임 보기")
    p.add_argument("n", type=int)
    p.set_defaults(func=cmd_frames)

    p = sub.add_parser("submit", help="answers.json을 팩 파일에 저장")
    p.add_argument("--model", required=True, help="태그를 단 에이전트/모델 이름 (예: claude-code, codex)")
    p.set_defaults(func=cmd_submit)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
