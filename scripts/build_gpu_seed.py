"""Builds backend/src/main/resources/seed/gpus.v2.json from the RightNow GPU Database.

RightNow (Apache-2.0, https://github.com/RightNow-AI/RightNow-GPU-Database) republishes
TechPowerUp's GPU database. This keeps only consumer desktop cards that GpuSpecValidator
accepts, so the seed loads with no drops, and spells them the way TechSpot does so the two
GPU sources share products rows where they can.

Stdlib only. The upstream commit is pinned so a rerun reproduces the committed seed:

    python scripts/build_gpu_seed.py                  # download the pinned commit
    python scripts/build_gpu_seed.py --input all-gpus.json
"""
import argparse
import json
import re
import sys
import urllib.request
from datetime import date
from pathlib import Path

UPSTREAM_COMMIT = "dfb3c9a54b092ad78681abb5a044e146398092a2"
UPSTREAM_URL = ("https://raw.githubusercontent.com/RightNow-AI/RightNow-GPU-Database/"
                f"{UPSTREAM_COMMIT}/data/all-gpus.json")
SOURCE = "seed:gpus.v2"
OUTPUT = Path(__file__).resolve().parent.parent / "backend/src/main/resources/seed/gpus.v2.json"

BRANDS = {"nvidia": "NVIDIA", "amd": "AMD", "intel": "Intel"}

# Consumer desktop lines only. Generation is the cleanest signal TechPowerUp gives: workstation,
# server, mining, embedded and mobile parts all sit in their own generations.
CONSUMER_GENERATION = {
    "nvidia": re.compile(r"^GeForce \d+(\(\d+\))?$"),
    "amd": re.compile(r"^(?!.*Mobile)[^()]*\((RX|HD|R5|R7|R9|Radeon VII)\b[^)]*\)$"),
    "intel": re.compile(r"^(Alchemist|Battlemage)\(Arc \d\)$"),
}
DESKTOP_SLOTS = {"Single-slot", "Dual-slot", "Triple-slot", "Quad-slot"}

# Mirrors gpu.validation.* defaults in application.properties.
EXCLUDED_NAME = re.compile(r"(?i)\b(laptop|mobile|max-q|notebook|quadro|tesla|instinct|radeon pro|firepro"
                           r"|rtx a\d+|ada generation)\b")
MIN_RELEASE_YEAR = 2010
BASE_CLOCK_MHZ = (100, 4000)
VRAM_GB = (0.5, 96)
TDP_WATTS = (10, 1000)

# gpus.v1 spellings, kept so catalogues already seeded from v1 (and TechSpot, which spells them
# the same way) match instead of gaining a second row for the same card.
ALIASES = {
    ("AMD", "Radeon RX 580"): "Radeon RX 580 8GB",
    ("Intel", "Arc A770"): "Arc A770 16GB",
    ("NVIDIA", "GeForce RTX 2060"): "GeForce RTX 2060 6GB",
    ("NVIDIA", "GeForce GTX 1660 SUPER"): "GeForce GTX 1660 Super",
}
# TechPowerUp publishes no launch prices; these are the v1 seed's TechSpot "Price at Launch".
LAUNCH_MSRP_USD = {
    ("NVIDIA", "GeForce GTX 1060 6GB"): 299,
    ("NVIDIA", "GeForce GTX 1660 Super"): 229,
    ("NVIDIA", "GeForce RTX 2060 6GB"): 349,
    ("NVIDIA", "GeForce RTX 3060 12GB"): 329,
    ("NVIDIA", "GeForce RTX 3070"): 499,
    ("NVIDIA", "GeForce RTX 4060"): 299,
    ("NVIDIA", "GeForce RTX 4070"): 599,
    ("NVIDIA", "GeForce RTX 5070"): 549,
    ("AMD", "Radeon RX 580 8GB"): 229,
    ("AMD", "Radeon RX 6600"): 329,
    ("AMD", "Radeon RX 7800 XT"): 499,
    ("Intel", "Arc A770 16GB"): 349,
}


def model_name(brand, raw):
    # TechSpot writes "RTX 3060 12GB" where TechPowerUp writes "RTX 3060 12 GB".
    name = re.sub(r"(\d) GB\b", r"\1GB", " ".join(raw.split()))
    return ALIASES.get((brand, name), name)


def whole(value):
    return int(value) if float(value).is_integer() else value


def card(raw, today):
    vendor = raw.get("vendor")
    if vendor not in BRANDS or not CONSUMER_GENERATION[vendor].match(raw.get("generation") or ""):
        return None
    if raw.get("slot") not in DESKTOP_SLOTS or not (raw.get("busInterface") or "").startswith("PCIe"):
        return None
    if EXCLUDED_NAME.search(raw["name"]):
        return None
    try:
        released = date.fromisoformat(raw.get("releaseDate", ""))
    except ValueError:
        return None
    base, vram, tdp = raw.get("baseClock"), raw.get("memorySize"), raw.get("tdp")
    if (released.year < MIN_RELEASE_YEAR or released > today or base is None or vram is None or tdp is None
            or not BASE_CLOCK_MHZ[0] <= base <= BASE_CLOCK_MHZ[1]
            or not VRAM_GB[0] <= vram <= VRAM_GB[1]
            or not TDP_WATTS[0] <= tdp <= TDP_WATTS[1]):
        return None
    boost = raw.get("boostClock")
    bus = raw.get("memoryBus")
    brand = BRANDS[vendor]
    name = model_name(brand, raw["name"])
    return {
        "brand": brand,
        "modelName": name,
        "releaseDate": released.isoformat(),
        "baseClockMhz": round(base),
        "boostClockMhz": round(boost) if boost is not None and boost >= base else None,
        "vramGb": whole(vram),
        "memoryType": raw.get("memoryType"),
        "memoryBusBits": bus if bus is not None and 32 <= bus <= 8192 else None,
        "tdpWatts": tdp,
        "launchMsrpUsd": LAUNCH_MSRP_USD.get((brand, name)),
        "architecture": raw.get("architecture"),
        "sourceUrl": raw.get("url") if (raw.get("url") or "").startswith("https://") else None,
    }


def build(gpus, today):
    cards, seen, duplicates = [], set(), []
    for raw in gpus:
        built = card(raw, today)
        if built is None:
            continue
        key = (built["brand"] + "|" + built["modelName"]).lower()
        if key in seen:
            duplicates.append(built["modelName"])
            continue
        seen.add(key)
        cards.append(built)
    cards.sort(key=lambda c: (c["brand"], c["releaseDate"], c["modelName"]))
    missing = set(LAUNCH_MSRP_USD) - {(c["brand"], c["modelName"]) for c in cards}
    if missing:
        sys.exit(f"v1 cards missing from the build: {sorted(missing)}")
    return cards, duplicates


def write(cards, path):
    lines = [json.dumps(c, ensure_ascii=False) for c in cards]
    body = ",\n    ".join(lines)
    path.write_text(f'{{\n  "source": "{SOURCE}",\n  "gpus": [\n    {body}\n  ]\n}}\n', encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", type=Path, help="local all-gpus.json instead of the pinned download")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if args.input:
        gpus = json.loads(args.input.read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(UPSTREAM_URL, timeout=60) as response:
            gpus = json.load(response)
    cards, duplicates = build(gpus, date.today())
    write(cards, args.output)
    print(f"{len(cards)} cards written to {args.output} ({len(duplicates)} duplicate names skipped)")
    for name in duplicates:
        print(f"  duplicate: {name}")


if __name__ == "__main__":
    main()
