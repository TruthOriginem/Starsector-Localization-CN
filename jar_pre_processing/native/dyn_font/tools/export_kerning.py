"""按构建清单导出字体的 kern/GPOS kerning 固化表（font units）。

FreeType 的 FT_Get_Kerning 只读传统 kern 表，Orbitron 的字偶距在 GPOS，
故由 fontTools 离线提取（可变字体先按 wght 实例化），以 font units 固化为
文本文件随字体分发；native 运行时按渲染字号像素化（amount = round(units×size/upm)）。

提取逻辑与 fnt_composer core/kern_extractor.py 一致（kern 表先、GPOS 后覆盖），
只是保留 units 不做像素化、不按字表过滤（native 侧运行时过滤）。

字重与输出表名不在本脚本中维护；build.py 从 native 字体规格生成资产清单，再通过可重复的
``--table WEIGHT FILENAME`` 参数传入当前真正使用的组合。
"""
import argparse
import io
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

def extract_units(ttf_path: Path, weight: int) -> tuple[int, dict[tuple[int, int], int]]:
    with ttf_path.open("rb") as f:
        font = TTFont(io.BytesIO(f.read()), lazy=False)
    if weight > 0 and "fvar" in font:
        font = instancer.instantiateVariableFont(font, {"wght": weight})

    upm = font["head"].unitsPerEm
    cmap = font.getBestCmap()
    name_to_char: dict[str, int] = {}
    for code, gname in cmap.items():
        name_to_char.setdefault(gname, code)

    pairs: dict[tuple[int, int], int] = {}

    def add(gn1: str, gn2: str, units: int):
        c1, c2 = name_to_char.get(gn1), name_to_char.get(gn2)
        if c1 is None or c2 is None or not units:
            return
        pairs[(c1, c2)] = units

    if "kern" in font:
        for st in font["kern"].kernTables:
            for (g1, g2), v in getattr(st, "kernTable", {}).items():
                add(g1, g2, v)

    if "GPOS" in font and font["GPOS"].table.LookupList:
        for lookup in font["GPOS"].table.LookupList.Lookup:
            subtables = lookup.SubTable
            if lookup.LookupType == 9:
                subtables = [st.ExtSubTable for st in subtables]
                if not subtables or subtables[0].__class__.__name__ != "PairPos":
                    continue
            elif lookup.LookupType != 2:
                continue
            for st in subtables:
                if st.Format == 1:
                    for cov_glyph, pair_set in zip(st.Coverage.glyphs, st.PairSet):
                        for rec in pair_set.PairValueRecord:
                            v = rec.Value1.XAdvance if rec.Value1 and hasattr(rec.Value1, "XAdvance") else 0
                            add(cov_glyph, rec.SecondGlyph, v)
                elif st.Format == 2:
                    class1 = st.ClassDef1.classDefs if st.ClassDef1 else {}
                    class2 = st.ClassDef2.classDefs if st.ClassDef2 else {}
                    cov = set(st.Coverage.glyphs)
                    c1_map: dict[int, list[str]] = {}
                    for g in cov:
                        c1_map.setdefault(class1.get(g, 0), []).append(g)
                    c2_map: dict[int, list[str]] = {}
                    for g in name_to_char:
                        c2_map.setdefault(class2.get(g, 0), []).append(g)
                    for i, row in enumerate(st.Class1Record):
                        g1s = c1_map.get(i)
                        if not g1s:
                            continue
                        for j, rec in enumerate(row.Class2Record):
                            v = rec.Value1.XAdvance if rec.Value1 and hasattr(rec.Value1, "XAdvance") else 0
                            if not v:
                                continue
                            for g1 in g1s:
                                for g2 in c2_map.get(j, []):
                                    add(g1, g2, v)

    return upm, pairs


def render_table(font_path: Path, weight: int) -> bytes:
    upm, pairs = extract_units(font_path, weight)
    weight_label = f" wght={weight}" if weight > 0 else ""
    lines = [
        f"# GPOS kerning units, {font_path.stem}{weight_label} (fontTools export)",
        f"upm {upm}",
        *(f"{c1} {c2} {units}" for (c1, c2), units in sorted(pairs.items())),
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


def export_tables(
    font_path: Path,
    output_dir: Path,
    tables: list[tuple[int, str]],
) -> tuple[Path, ...]:
    output_dir.mkdir(parents=True, exist_ok=True)
    outputs = []
    for weight, filename in tables:
        data = render_table(font_path, weight)
        output = output_dir / filename
        changed = not output.is_file() or output.read_bytes() != data
        if changed:
            output.write_bytes(data)
        pair_count = max(0, data.count(b"\n") - 2)
        state = "updated" if changed else "unchanged"
        print(f"[kern] {output.name}: {pair_count} pairs ({state})")
        outputs.append(output)
    return tuple(outputs)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", required=True, type=Path, help="TTF/OTF 字体源路径")
    parser.add_argument("--output-dir", required=True, type=Path, help="固化表输出目录")
    parser.add_argument(
        "--table", required=True, action="append", nargs=2, metavar=("WEIGHT", "FILENAME"),
        help="字重与输出文件名；可重复。WEIGHT=0 表示静态字体/不实例化可变轴",
    )
    args = parser.parse_args()
    tables = []
    for raw_weight, filename in args.table:
        try:
            weight = int(raw_weight)
        except ValueError:
            parser.error(f"WEIGHT 必须是非负整数: {raw_weight!r}")
        if weight < 0:
            parser.error(f"WEIGHT 必须是非负整数: {weight}")
        if Path(filename).name != filename or not filename.endswith(".kern.txt"):
            parser.error(f"FILENAME 必须是当前目录下的 *.kern.txt 文件名: {filename!r}")
        tables.append((weight, filename))
    if len({filename for _, filename in tables}) != len(tables):
        parser.error("FILENAME 不可重复")
    args.tables = tables
    return args


def main() -> None:
    args = parse_args()
    export_tables(args.font, args.output_dir, args.tables)


if __name__ == "__main__":
    main()
