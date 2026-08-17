# 动态字体渲染

本模块在 Windows 游戏本体启动时，根据最终界面缩放生成 11 套中英混排位图字体，并通过
精确代理接入原版 BMFont renderer。125%、150%、195%、200% 等缩放档都会使用对应物理
分辨率的图集；原版排版、高亮、阴影、混合与 mod 渲染路径继续保留。

玩家无需配置。修改 `graphics/fonts/dyn_font/chars.txt` 后重启即可重建；初始化、生成或
渲染发生异常时整套回退到汉化包内的静态字体。

## 实现概览

```text
ResourceLoader.openStream（ASM）
  └─ DynFontOverrides
     ├─ 检测并校验 screenScale
     ├─ 命中缓存或调用 ss_dyn_font.dll 生成字体
     └─ 进入游戏 GL context 后预热 11 套 exact 代理

BitmapFont / BitmapFontRenderer（ASM）
  ├─ DynFontRenderHooks：基础字体实例 -> exact 代理实例
  ├─ 对外保留逻辑 nominal，renderer 内部读取 raw nominal
  └─ DynFontQuadHooks：最终 glyph quad 相对共同原点吸附物理像素
```

| 组件 | 作用 |
|---|---|
| `native/dyn_font/ss_dyn_font.dll` | FreeType 栅格化、度量、单页 POT 装箱与 PNG 编码 |
| `DynFontOverrides` | 资源拦截、缩放检测、缓存、native 调用与 GL context 判定 |
| `DynFontRenderHooks` | exact 预热、代理原子映射与逻辑 nominal |
| `DynFontQuadHooks` / `PixelTransform` | 最终顶点的 framebuffer 像素吸附 |
| `ResourceStreamDynFontPatch` | 在资源流入口接入生成产物 |
| `BitmapFontLogicalNominalPatch` | 对外保留逻辑 nominal，提供内部 raw nominal |
| `RendererDynFontPatch` | 接入代理、即时 glyph 绘制与最终顶点 hook |

运行时类注入 `starfarer_obf.jar`，字体 renderer 位于 `fs.common_obf.jar`。

### 生成产物与精确代理

每套字体生成五个文件：

| 产物 | 用途 |
|---|---|
| `{name}.fnt` / `{name}_0.png` | 当前缩放下的普通度量字体资源 |
| `{name}_exact.fnt` / `{name}_exact_0.png` | 游戏本体使用的 exact 代理 |
| `{name}.dfnt` | 浮点 line/base、glyph、advance 与 kerning；同时用于完整性校验 |

图集按 `配置字号 × screenScale` 栅格化。exact FNT 的纹理仍是实际物理尺寸，但 nominal、
glyph 度量和虚拟图集尺寸统一乘固定倍率 `M=64`：

```text
proxyNominal = baseNominal × screenScale × 64
proxyMetric  = physicalMetric × 64
```

renderer 的字号倍率会抵消 `screenScale × 64`，因此基准字号下一个图集 texel 对应一个
framebuffer 像素。公开 nominal 仍返回逻辑字号，避免星图或 mod 使用 `nominal × factor`
派生字号时误读 64 倍代理；只有 renderer 内部读取 raw nominal。

启动器与游戏本体使用不同 GL context。exact 纹理只在调用栈确认进入游戏读条阶段后预热，
随后将 11 套基础实例到代理实例的映射以不可变快照一次发布。任一套失败则全部回退，避免
混用不同 UV 或度量。映射发布后只检查 11 个已知路径，兼容 mod 重新注册字体。

## 物理像素吸附

原版字体纹理使用 `GL_LINEAR`；quad 落在半像素相位时，即使图集尺寸正确也会发虚。动态
字体因此在最终 `glVertex2f` 前吸附物理像素，但不修改原版的扩张层、颜色、blend、shader、
纹理或 active texture。

### 当前规则

1. 每次 `renderer.render` 的第一个阴影或正文 pass 提供共同原点。
2. 后续 pass 与全部 glyph 都相对这个原点量化：

   ```text
   snappedEdge = round(renderOrigin) + round(edge - renderOrigin)
   ```

3. 所有字形都只吸附左上角，右下角跟随相同位移，物理宽高保持不变。

共同原点让移动文本整段同时跳到下一个像素，不再出现各字符交替移动、字间距抖动。全部
字形使用单角吸附，避免独立量化左右或上下边缘造成拉伸、压缩。

吸附只改绘制顶点，不改 pen、advance、kerning、测量或换行。代理字体绕过会固化首次像素
相位的 display list，继续执行原版即时 glyph 绘制；基础字体与 mod 自有字体保持原路径。

每个 render scope 读取一次 model-view、projection 与 viewport。仅轴对齐、可逆正交变换时
启用；旋转、shear、透视、奇异矩阵或查询失败都原样提交。游戏和 mod 可在同一帧切换矩阵、
FBO 或 viewport，因此保留两次 `glGetFloat` 与一次 `glGetInteger`，不做不安全的跨 scope
缓存。临时 buffer、数组与变换对象通过 `ThreadLocal` 复用。

移动仍以物理像素为最小网格；若上层动画只按整数逻辑坐标更新，高缩放下可表现为约
`screenScale` 个物理像素一步。这是上层位置精度与像素吸附的取舍。完全保留亚像素移动会
使静止文字明显变虚，因此没有采用。

## 字体参数

唯一权威配置是 `native/dyn_font/src/composer.cpp` 的 `makeSpecs()`。下表均为 1× 基准值，
字号允许小数并直接传入 FreeType 26.6，生成时再乘真实 `screenScale`。

### Insignia

西文：`lte50549.ttf`；中文：方正兰亭中粗黑；`smooth=1`、`aa=4`。

| 套名 | 西文字号 | 西文 xadv / bold / SS | 中文字号 | 中文 xadv / bold / SS | info / lh / base | 西文上移 |
|---|---:|---:|---:|---:|---:|---:|
| insignia15LTaa | 15.0 | 0 / 0 / 8× | 15 | 0 / 0.10 / 8× | 15 / 17 / 15 | 2 |
| insignia21LTaa | 17.0 | +1 / 0 / 4× | 16 | +1 / 0 / 4× | 18 / 18 / 16 | 2 |
| insignia25LTaa | 24.0 | 0 / 0 / 4× | 22 | +1 / 0 / 4× | 24 / 25 / 22 | 2 |

三套均使用来源 TTF 的 bearing、advance 与 kern 表。`0–9` 统一为本套最大自然 advance，
窄数字的新增留白平均分到两侧；任何涉及数字的 kerning 都会过滤。

### Orbitron

西文：Orbitron VF；中文：锐字逼格青春粗黑体简 2.0。

| 套名 | 西文字号 | wght | 西文 xadv | 中文字号 | 中文 y / bold | info / lh / base | smooth / aa | 上移 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| orbitron12condensed | 12.0 | 800 | +0.5 | 16 | 0 / 0.15 | −12 / 16 / 16 | 1 / 1 | 2 |
| orbitron20aa | 15.5 | 800 | +0.5 | 18 | +1 / 0.15 | 20 / 20 / 19 | 0 / 4 | 2 |
| orbitron20aabold | 16.0 | 800 | +0.5 | 18 | +1 / 0.15 | −20 / 20 / 19 | 0 / 4 | 2 |
| orbitron24aa | 18.0 | 800 | +0.5 | 20 | +1 / 0.15 | −24 / 24 / 21 | 0 / 4 | 0 |
| orbitron24aabold | 20.0 | 800 | +0.5 | 20 | +1 / 0.15 | 24 / 24 / 21 | 0 / 4 | 0 |

五套保留 Orbitron w800 的自然数字 advance 与 GPOS kerning，不做等宽覆写。

### Victor

西文同样使用 Orbitron VF，中文使用锐字；`smooth=0`、`aa=1`。

| 套名 | 西文字号 | wght | 西文 xadv | 中文字号 | 中文 bold | info / lh / base | 上移 |
|---|---:|---:|---:|---:|---:|---:|---:|
| victor10 | 10.0 | 900 | +1 | 11 | 0.17 | −10 / 10 / 9 | 1 |
| victor14 | 10.0 | 800 | +1 | 12 | 0.15 | −14 / 13 / 11 | 1 |
| victor16 | 13.5 | 800 | +1 | 17 | 0.15 | −20 / 18 / 16 | 2 |

Victor 保留旧静态字体的布局规格。数字等宽且居中；victor10 使用 w900 kerning，其余使用
w800。`a-z` 保留原码位但复用对应 `A-Z` 的图形、bearing 与 advance，kerning 同步展开到
大小写输入组合。

### 共同生成规则

- 矢量字形使用配置的 4×/8× 超采样、Lanczos 降采样与 `FT_LOAD_TARGET_LIGHT`。
- Insignia 使用 kern；Orbitron/Victor 使用按 `wght` 导出的 GPOS。kerning 由 `fontTools`
  自动生成，native 再按字号换算。
- `{`、`}` 的宽高、bearing 与 advance 清零，作为高亮用不可见边界字符。
- `bold` 在超采样 mask 上做方形膨胀，不扩大画布；整体字重优先调整 `wght`。
- 西文 `H` 与中文“舰”的实心底对齐，再应用表中的整套西文上移。
- 图集必须为单页、2 的幂；分页或非 POT 都会被拒绝。

## 字体度量兼容 Patch

| Patch | 修正 |
|---|---|
| `CombatTargetInfoWidthPatch` | 战斗 HUD 距离/航速栏 58 → 80，避免三位数航速换行 |
| `FleetCardCrTextWidthPatch` | 舰队卡片 CR 百分比栏 26 → 40，容纳 `100%` |
| `NewGameSeedFieldWidthPatch` | 新生涯种子框 185 → 210，“粘贴”按钮同步右移 25 |
| `RendererHighlightRegexPatch` | 模糊高亮 fallback 安全引用动态文本，避免译文被当成正则表达式 |

Patch 都严格匹配目标类、方法描述符和相邻字节码结构；结构或数量漂移时构建失败。

## 资产、缓存与安全

`native/dyn_font/assets.json` 列出四个 TTF 和三份自动生成的 kerning 表。TTF、`.kern.txt`
为本地构建输入并被 Git 忽略；确定性生成的 `localization/graphics/fonts/dyn_font/typefaces.dat`
作为预构建分发资产入库。

```text
starsector-core/
├── native/windows/ss_dyn_font.dll
└── graphics/fonts/dyn_font/
    ├── typefaces.dat
    ├── chars.txt
    └── cache/s{scale}-{fingerprint}/
        ├── 11 × 5 个字体产物
        └── .complete
```

缓存指纹取 `spec version + typefaces.dat + chars.txt + DLL` 的 SHA-256 前 16 位；缩放单独写入
目录名。命中时仍逐项检查 55 个文件。输入变化会创建新指纹并删除旧指纹；同一指纹最多保留
最近三个缩放档。

缓存根及父目录必须位于游戏工作目录内，符号链接、junction/reparse point 与档内链接都不会
被跟随。`screenScale` 只接受有限的 1.0～3.0，并由 Java/native 双重校验。native 写盘前还会
验证单页、非空、基线、等宽数字、数字 kerning 与图集占用；异常统一返回错误码，不穿过 JVM。

## 构建与验证

```powershell
cd jar_pre_processing

# 修改 Java/ASM
python -X utf8 build.py jar

# 修改 native、字号、字重、字距或字体资产
python -X utf8 build.py dynfont jar

# Jar 预处理后恢复译文
cd ..
python -X utf8 para_tranz/para_tranz_script.py 2
```

`build.py dynfont` 会同步资产清单与 kerning、使用 CMake/Ninja/MinGW 构建，并在复制 DLL 前
强制运行 CTest。FreeType 固定为 2.13.2 commit
`920c5502cc3ddda88f6c7d85ee834ac611bb11cc`；版本错误或 checkout 不干净时停止构建。

```powershell
cd jar_pre_processing
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
.\mvnw.cmd clean test
python -X utf8 -m unittest discover -s tests -v
ctest --test-dir native/dyn_font/build -C Release --output-on-failure --no-tests=error
```

真实 Jar 构建后还需检查 `target/preprocess-work/preprocess-report.json`，所有 Patch 的
`expected/applied/verified` 必须相等。

离线生成单套字体：

```powershell
native\dyn_font\build\dynfont_cli.exe `
  --typefaces ..\localization\graphics\fonts\dyn_font\typefaces.dat `
  --chars ..\localization\graphics\fonts\dyn_font\chars.txt `
  --out <目录> --scale 1.95 --only victor10
```

## 日志、限制与历史决策

`starsector.log` 使用 GBK，动态字体前缀为 `[SS-DYNFONT]`；native 冷生成日志位于同目录的
`ss_dyn_font_native.log`。正常启动会依次记录动态字体初始化、游戏 GL context 就绪、
`11/11` exact 代理预热和代理映射发布。

当前限制：仅支持 64 位 Windows；启动器仍使用普通字体；只接管上述 11 套字体；原版仍以
Java `char` 查字；单页图集是硬限制；缩放变化后需要重启；旋转、透视或特殊矩阵文本保留
原版亚像素路径。

历史上尝试或评估过 1× 静态放大、旧 `_hd` 整数倍率、全局 `GL_NEAREST`、逐字独立吸附、
完全接管排版以及 SDF/MSDF/ClearType。它们分别存在非整数缩放二次采样、小字号破坏、移动
字距抖动或 renderer/mod 兼容面过大的问题。当前实现统一采用“真实缩放图集 + exact 代理 +
共同原点、全部字形单角吸附”的路径。

## 致谢

技术路线参考了 [KasumiNova](https://github.com/KasumiNova)（Hikari_Nova）的
[SSOptimizer](https://github.com/KasumiNova/SSOptimizer)（MIT）中按缩放生成 BMFont、在
renderer 入口替换字体实例与资源流拦截的思路。本模块为独立实现，不使用 Java Agent，也未
复制其代码。
