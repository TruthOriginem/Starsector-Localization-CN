"""金标准逐字形 diff：native 产物 vs fnt_composer（Python）产物。

比对口径（"效果一致"的客观验收标准）：
  - info：size / lineHeight / base（face 为元数据，忽略）
  - 每字形（按 id 交集）：xoffset / yoffset / xadvance / width / height
    与图集像素（alpha 通道逐像素）
  - kerning：pair → amount 映射
图集内部排列（x/y/page）允许不同——Python 侧受 dict/set 迭代序影响，
布局不属效果语义。

用法：python diff_fnt.py <参考目录> <native目录> [name ...]
"""
import os
import re
import sys

from PIL import Image


def load_fnt(fnt_path):
    d = os.path.dirname(fnt_path)
    info, chars, kern, pages = {}, {}, {}, []
    for line in open(fnt_path, encoding="utf-8", errors="replace"):
        tag = line.split(" ", 1)[0]
        if tag == "info":
            info["size"] = int(re.search(r" size=(-?\d+)", line).group(1))
        elif tag == "common":
            for k in ("lineHeight", "base"):
                info[k] = int(re.search(rf"{k}=(-?\d+)", line).group(1))
        elif tag == "page":
            f = re.search(r'file="([^"]+)"', line).group(1)
            pages.append(Image.open(os.path.join(d, f)).convert("RGBA"))
        elif tag == "char":
            c = dict(re.findall(r"(\w+)=(-?\d+)", line))
            chars[int(c["id"])] = {k: int(v) for k, v in c.items()}
        elif tag == "kerning":
            k = dict(re.findall(r"(\w+)=(-?\d+)", line))
            kern[(int(k["first"]), int(k["second"]))] = int(k["amount"])
    return info, chars, kern, pages


def glyph_alpha(g, pages):
    if g["width"] <= 0 or g["height"] <= 0:
        return None
    crop = pages[g["page"]].crop(
        (g["x"], g["y"], g["x"] + g["width"], g["y"] + g["height"]))
    return list(crop.getchannel("A").getdata())


def diff_one(name, ref_dir, nat_dir):
    ref_fnt = os.path.join(ref_dir, f"{name}.fnt")
    nat_fnt = os.path.join(nat_dir, f"{name}.fnt")
    if not os.path.exists(ref_fnt) or not os.path.exists(nat_fnt):
        print(f"[{name}] MISSING: ref={os.path.exists(ref_fnt)} nat={os.path.exists(nat_fnt)}")
        return False

    ri, rc, rk, rp = load_fnt(ref_fnt)
    ni, nc, nk, np_ = load_fnt(nat_fnt)

    issues = []
    for k in ("size", "lineHeight", "base"):
        if ri.get(k) != ni.get(k):
            issues.append(f"info.{k}: ref={ri.get(k)} nat={ni.get(k)}")

    common = sorted(set(rc) & set(nc))
    only_ref = sorted(set(rc) - set(nc))
    only_nat = sorted(set(nc) - set(rc))

    metric_bad, pixel_bad, max_pix = [], [], 0
    for cid in common:
        rg, ng = rc[cid], nc[cid]
        mdiff = [f"{k}:{rg[k]}->{ng[k]}"
                 for k in ("xoffset", "yoffset", "xadvance", "width", "height")
                 if rg[k] != ng[k]]
        if mdiff:
            metric_bad.append((cid, mdiff))
            continue  # 尺寸不同像素必不同，只记度量
        ra, na = glyph_alpha(rg, rp), glyph_alpha(ng, np_)
        if ra is None and na is None:
            continue
        d = max((abs(a - b) for a, b in zip(ra, na)), default=0)
        if d > 0:
            ndiff = sum(1 for a, b in zip(ra, na) if a != b)
            pixel_bad.append((cid, d, ndiff, len(ra)))
            max_pix = max(max_pix, d)

    # kerning 只比两端都在 id 交集的 pair（pair 存在性由字表决定，
    # 字表差异已由 refOnly/natOnly 报告）
    common_set = set(common)
    kern_diff = {p: (rk.get(p), nk.get(p)) for p in set(rk) | set(nk)
                 if rk.get(p) != nk.get(p)
                 and p[0] in common_set and p[1] in common_set}

    ok = not issues and not metric_bad and not pixel_bad and not kern_diff
    status = "OK " if ok else "DIFF"
    print(f"[{name}] {status} common={len(common)} refOnly={len(only_ref)} natOnly={len(only_nat)}")
    for msg in issues:
        print(f"    {msg}")
    if metric_bad:
        print(f"    metric diff x{len(metric_bad)}:")
        for cid, md in metric_bad[:10]:
            print(f"      U+{cid:04X} {chr(cid)!r}: {', '.join(md)}")
        if len(metric_bad) > 10:
            print(f"      ... +{len(metric_bad) - 10} more")
    if pixel_bad:
        print(f"    pixel diff x{len(pixel_bad)} (max delta {max_pix}):")
        for cid, d, n, total in pixel_bad[:10]:
            print(f"      U+{cid:04X} {chr(cid)!r}: maxDelta={d} diffPx={n}/{total}")
        if len(pixel_bad) > 10:
            print(f"      ... +{len(pixel_bad) - 10} more")
    if kern_diff:
        print(f"    kerning diff x{len(kern_diff)}:")
        for (a, b), (rv, nv) in sorted(kern_diff.items())[:10]:
            print(f"      {chr(a)!r}+{chr(b)!r}: ref={rv} nat={nv}")
        if len(kern_diff) > 10:
            print(f"      ... +{len(kern_diff) - 10} more")
    return ok


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    ref_dir, nat_dir = sys.argv[1], sys.argv[2]
    names = sys.argv[3:] or [
        "insignia15LTaa", "insignia21LTaa", "insignia25LTaa",
        "orbitron12condensed", "orbitron20aa", "orbitron20aabold",
        "orbitron24aa", "orbitron24aabold",
        "victor10", "victor14", "victor16",
    ]
    results = [diff_one(n, ref_dir, nat_dir) for n in names]
    print(f"\n{sum(results)}/{len(results)} OK")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
