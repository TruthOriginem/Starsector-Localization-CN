# 动态字体渲染

本模块在 Windows 游戏本体启动时，按最终界面缩放生成中英混排位图字体，并以精确代理
接入原版 BMFont renderer。目标是让 125%、150%、195%、200% 等缩放档都直接使用对应
分辨率的图集，同时保留原版排版、高亮、阴影、混合和 mod 渲染路径。

玩家无需配置；`graphics/fonts/dyn_font/chars.txt` 可编辑，重启后自动重新生成。
任何初始化或渲染异常都会回退汉化包内的 1× 静态中文字体。

## 当前架构

```text
ResourceLoader.openStream（ASM）
  └─ DynFontOverrides
     ├─ 读取 screenScale
     ├─ 校验或生成 11 套基础、exact 和 .dfnt 产物
     └─ 在游戏 GL context 的读条阶段预热 exact 纹理

BitmapFont / BitmapFontRenderer（ASM）
  ├─ DynFontRenderHooks：基础字体实例 -> exact 代理实例
  ├─ 公开 nominal 保持逻辑字号，renderer 内部读取 raw proxy nominal
  └─ DynFontQuadHooks：最终 glyph quad 按共同原点吸附到 framebuffer 像素
```

| 组件 | 位置 | 作用 |
|---|---|---|
| `ss_dyn_font.dll` | `native/dyn_font/` | FreeType 栅格化、排版度量、单页 POT 装箱和 PNG 编码 |
| `DynFontOverrides` | `src/main/java/.../dynfont/` | 资源拦截、缩放检测、缓存、native 调用和游戏 context 判定 |
| `DynFontRenderHooks` | 同上 | 预热、11 套代理的原子映射和逻辑 nominal |
| `DynFontQuadHooks` / `PixelTransform` | 同上 | 读取 GL 变换并对整段文本做刚性像素吸附 |
| `ResourceStreamDynFontPatch` | `src/main/java/.../patches/` | 在资源流入口接入生成产物 |
| `BitmapFontLogicalNominalPatch` | 同上 | 对外保留逻辑 nominal，为 renderer 增加 raw nominal getter |
| `RendererDynFontPatch` | 同上 | 接入代理、即时绘制副本和最终顶点 hook |

运行时类注入 `starfarer_obf.jar`；字体链本身位于 `fs.common_obf.jar`。两者都在游戏固定
classpath 中。

### 基础字体、exact 代理与精确度量

每套字体生成五个文件：

| 产物 | 用途 |
|---|---|
| `{name}.fnt` / `{name}_0.png` | 1× 基础字体；供启动器、身份识别和故障回退 |
| `{name}_exact.fnt` / `{name}_exact_0.png` | 按真实 screenScale 栅格化；游戏本体使用的精确代理 |
| `{name}.dfnt` | 带版本的精确浮点 line/base/glyph/kerning 数据；用于完整性校验 |

exact FNT 使用固定虚拟度量倍率 `M=64`。图集仍保持真实像素尺寸，FNT 中的 nominal、
glyph 几何、advance、kerning 和虚拟图集尺寸则同乘 64：

```text
proxyNominal = baseNominal × screenScale × 64
proxyMetric  = exactPhysicalMetric × 64
```

原 renderer 的 `requestedSize / proxyNominal` 与上层 screenScale 会抵消这两个倍率，基准
字号下一个 exact texel 对应一个 framebuffer 像素。公开 nominal getter 对代理仍返回
`baseNominal`，因此星图和 mod 用 `nominal × 0.2` 等任意系数派生字号时不会把 64 倍代理
字号误当成逻辑字号；只有 renderer 内部使用新增的 raw getter。

11 套 exact 纹理在游戏 GL context 的读条阶段全部注册并校验，基础到代理的映射随后通过
一个不可变快照一次发布。任一套失败则整套回退，避免同一界面混用两种 UV 或度量。映射
发布后只按 11 个已知路径处理重新注册的字体实例，不遍历游戏的普通 `HashMap`，以兼容 mod
并发注册字体。

启动器和游戏本体在同一 JVM 中使用不同 GL context。代理纹理只能在调用栈确认进入
`ResourceLoaderState` 或 `CombatMain` 后加载；若在启动器 context 中提前加载，进入游戏后
缓存的纹理 id 会失效并产生色块。

## 物理像素吸附

原版始终使用 `GL_LINEAR`。即使 exact 图集的采样倍率正确，quad 落在半像素相位时仍会
发虚，因此代理字体在最终 `glVertex2f` 前进行 framebuffer 像素吸附。

游戏原版按调用场景设置的 0/2/3 层字形四边形扩张保持不变；动态字体补丁只调整顶点的
像素相位，不再覆盖扩张层数。

每个 renderer render scope 只读取一次 model-view、projection 和 viewport。只有变换为
轴对齐、可逆正交变换且没有额外矩阵时启用；旋转、shear、透视、奇异矩阵或 GL 查询失败
均原样提交。buffer、矩阵数组和变换对象由渲染线程的 `ThreadLocal` 复用，Java 热路径不
产生临时对象。

当前采用**整段文本共同原点**，不是每个字独立按屏幕相位吸附：

```text
snappedEdge = round(renderOrigin) + round(edge - renderOrigin)
```

第一个阴影或正文 pass 的窗口坐标原点成为本次 render 的共同原点；后续 pass 和全部 glyph
都相对它量化。这样整段文本移动时所有字符同时移动，字间距不会随着小数相位逐字跳动。
每个 quad 的另一条边同样相对共同原点吸附，非空字形至少保留一个物理像素。

代价是移动仍是离散的：理论网格为一个物理像素；如果上层动画本身只按整数逻辑坐标更新，
在高缩放下屏幕步长会约为 screenScale 个物理像素。该阶梯感来自上层位置精度和像素对齐，
不是字间距变化。关闭所有吸附能获得连续亚像素移动，但实测静止文字明显变虚，因此保留
当前折中。

吸附不改变 pen、advance、kerning、测量或换行。原版长文本 display list 会固化首次绘制
位置的像素相位，所以仅代理字体绕过字体 display list，继续执行原版即时 glyph 绘制；
基础字体和 mod 自有字体仍走原缓存。

每个 scope 保留两次 `glGetFloat` 和一次 `glGetInteger`。游戏和 mod 可在同一帧相邻文本间
切换矩阵、FBO 或 viewport，LWJGL2 没有可靠的 Java 状态镜像；按帧缓存会把文字吸附到错误
framebuffer。当前实现不改 shader、program、纹理、blend、颜色或 active texture，因而保持
GraphicsLib、BoxUtil 等自定义渲染路径兼容。

## 字体规格

唯一权威配置是 `native/dyn_font/src/composer.cpp` 的 `makeSpecs()`。以下均为 1× 基准值；
字号可为小数并直接传给 FreeType 26.6，生成 exact 时再乘真实 screenScale。

### Insignia

西文：`lte50549.ttf`；中文：方正兰亭中粗黑；`smooth=1`、`aa=4`。

| 套名 | 西文字号 | 西文 xadv / bold / SS | 中文字号 | 中文 xadv / bold / SS | info / lineHeight / base | 西文上移 |
|---|---:|---:|---:|---:|---:|---:|
| insignia15LTaa | 15.0 | 0 / 0.13 / 8× | 15 | 0 / 0.08 / 8× | 15 / 17 / 15 | 2 |
| insignia21LTaa | 17.0 | +1 / 0 / 4× | 16 | +1 / 0 / 4× | 18 / 18 / 16 | 2 |
| insignia25LTaa | 24.0 | 0 / 0 / 4× | 22 | +1 / 0 / 4× | 24 / 25 / 22 | 2 |

三套西文字号与静态版继承的原版 Insignia FNT 对齐，并使用 `lte50549.ttf`
自带的 kern 表（101 对，其中 94 对 ASCII）。

### Orbitron

西文：Orbitron VF；中文：锐字逼格青春粗黑体简 2.0。

| 套名 | 西文字号 | wght | 西文 xadv | 中文字号 | 中文 y | 中文 bold | info / lh / base | smooth / aa | 上移 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| orbitron12condensed | 12.0 | 800 | +0.5 | 16 | 0 | 0.15 | −12 / 16 / 16 | 1 / 1 | 2 |
| orbitron20aa | 15.5 | 800 | +0.5 | 18 | +1 | 0.15 | 20 / 20 / 19 | 0 / 4 | 2 |
| orbitron20aabold | 16.0 | 800 | +0.5 | 18 | +1 | 0.15 | −20 / 20 / 19 | 0 / 4 | 2 |
| orbitron24aa | 18.0 | 800 | +0.5 | 20 | +1 | 0.15 | −24 / 24 / 21 | 0 / 4 | 0 |
| orbitron24aabold | 20.0 | 800 | +0.5 | 20 | +1 | 0.15 | 24 / 24 / 21 | 0 / 4 | 0 |

Orbitron 数字 advance 保持原版数值栏设计：12c 的 `1=8`、其余 `10`；20 系的 `1=11`、
其余 `13`；24 系的 `1=13`、`7=14`、其余 `16`。这些是最终 advance，不再叠加 `+0.5`。
五套均使用 w800 GPOS 固化 kerning。

### Victor

西文同样使用 Orbitron VF，中文使用锐字；`smooth=0`、`aa=1`。

| 套名 | 西文字号 | wght | 西文 xadv | 中文字号 | 中文 y | 中文 bold | info / lh / base | 上移 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| victor10 | 10.0 | 900 | +1 | 11 | 0 | 0.17 | −10 / 10 / 9 | 1 |
| victor14 | 10.0 | 800 | +1 | 12 | 0 | 0.15 | −14 / 13 / 11 | 1 |
| victor16 | 13.5 | 800 | +1 | 17 | 0 | 0.15 | −20 / 18 / 16 | 2 |

Victor 的 `info/lineHeight/base` 保持旧静态字体的布局规格。数字取 0～9 自然 advance 的
最大值统一，并把窄字形在单元格内居中；victor10 使用 w900 kerning，victor14/16 使用
w800。`a-z` 保留原码位，但复制对应 `A-Z` 的图形、bearing 和 advance，kerning 也展开到
大小写输入组合，因此输入小写仍可查字，视觉统一为大写。

### 共同渲染规则

- 全部矢量字体使用规格指定的 4×/8× 超采样、Lanczos 降采样和
  `FT_LOAD_TARGET_LIGHT`。
- Insignia 使用来源 TTF 的 kern 表；Orbitron/Victor 通过可变字体 `wght` 轴
  取字重并使用 GPOS。两类 kerning 都由 `fontTools` 离线导出，native 按字号像素化。
- `{`、`}` 的宽高、bearing 和 advance 清零，作为游戏高亮用的不可见边界字符。
- `bold` 是在超采样 mask 上做方形膨胀的微调，不扩大画布；整体字重应优先调整 `wght`。
- 西文以 `H`、中文以“舰”的 alpha≥128 实心底对齐，再把整套西文上移表中的逻辑像素值；
  不允许只移动部分字形。
- 图集必须是单页、2 的幂。游戏只解析一行 page，分页会加载失败；非 POT 图集又会被纹理层
  padding，导致 FNT 的 UV 错位。

## 动态字体相关 Patch

除核心资源与 renderer hook 外，当前分支包含四项由字体度量变化暴露的兼容修正：

| Patch | 修正 |
|---|---|
| `CombatTargetInfoWidthPatch` | 战斗 HUD 距离/航速栏由 58 扩到 80，防止三位数航速把 `su/s` 换行 |
| `FleetCardCrTextWidthPatch` | 舰队列表卡片的 CR 百分比栏由 26 扩到 40，容纳动态 victor10 的 `100%` |
| `NewGameSeedFieldWidthPatch` | 新生涯种子框由 185 扩到 210，同时把“粘贴”按钮右移 25，保持左侧标签位置不变 |
| `RendererHighlightRegexPatch` | 给两条模糊高亮 fallback 的动态文本加安全 quoting，保留原有精确搜索，避免译文被当作正则表达式 |

所有 Patch 都按目标类、方法描述符和相邻调用结构严格匹配；数量或结构漂移时构建失败，不会
猜测修改其它常量。

## 生成、分发与缓存

`native/dyn_font/assets.json` 是规格实际引用的资产清单。当前包含四个 TTF，以及
Insignia kern、Orbitron w800/w900 GPOS 三份自动生成的 kerning 表。TTF 与
`.kern.txt` 是本机构建输入并被 Git 忽略；确定性打包后的
`localization/graphics/fonts/dyn_font/typefaces.dat` 是入库的预构建分发资产。

```text
starsector-core/
├── native/windows/ss_dyn_font.dll
└── graphics/fonts/dyn_font/
    ├── typefaces.dat
    ├── chars.txt
    └── cache/s{scale}-{fingerprint}/
        ├── 11 ×（基础 FNT/PNG + exact FNT/PNG + DFNT）
        └── .complete
```

指纹为 `SHA-256(spec version + typefaces.dat + chars.txt + DLL)` 的前 16 个十六进制字符；
scale 单独写在目录名前缀中。命中 `.complete` 时仍会逐项检查 55 个产物，缺失则重建。
输入内容变化会生成新指纹并清掉旧指纹目录；同一指纹最多保留最近三个缩放档。

缓存根及其父目录的真实路径必须留在游戏工作目录内；缓存根必须是普通目录，符号链接、
Windows junction/reparse point 和档内链接都不会被跟随。清理失败只记录日志，不中断字体
生成。screenScale 只接受有限的 1.0～3.0，Java 与 native 双重校验。

native 还会在写盘前验证单页、产物非空、基线和图集占用；JNI 入口及并行任务捕获所有 C++
异常并返回错误码，避免异常穿过 JVM 帧导致进程终止。损坏的数据包长度、非法 UTF-8 字表、
UTF-16 BOM、截断或非法 `.dfnt` 都会被拒绝并走静态字体回退。

## 构建与验证

```powershell
cd jar_pre_processing

# 只改 Java/ASM 时
python -X utf8 build.py jar

# 修改 native、字体规格、字号、字重或字距后
python -X utf8 build.py dynfont jar

# Jar 预处理后重新写回译文
cd ..
python -X utf8 para_tranz/para_tranz_script.py 2
```

`build.py dynfont` 会从 native CLI 刷新 `assets.json`，生成清单需要的 kerning、删除不再引用的
kerning，使用 CMake/Ninja/MinGW 构建，并在复制 DLL 前强制运行 CTest。FreeType 固定为
2.13.2 的 commit `920c5502cc3ddda88f6c7d85ee834ac611bb11cc`，checkout 版本不符或有
未提交内容时构建直接停止；自有 C++ 使用 `-O2`、可用时启用 IPO/LTO，并禁用 FMA 收缩以
保持金标准输出。

单独运行测试：

```powershell
cd jar_pre_processing
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
.\mvnw.cmd clean test
python -X utf8 -m unittest discover -s tests -v
ctest --test-dir native/dyn_font/build -C Release --output-on-failure --no-tests=error
```

真实 Jar 构建后检查 `target/preprocess-work/preprocess-report.json`，所有 Patch 的
`expected/applied/verified` 必须相等。字体参数也由 native `font_spec_test` 覆盖，PNG、FNT、
DFNT、基线、字距、缓存安全和像素变换分别有自动测试。

离线生成单套字体：

```powershell
native\dyn_font\build\dynfont_cli.exe `
  --typefaces ..\localization\graphics\fonts\dyn_font\typefaces.dat `
  --chars ..\localization\graphics\fonts\dyn_font\chars.txt `
  --out <目录> --scale 1.95 --only victor10
```

## 日志与排障

游戏日志 `starsector-core/starsector.log` 使用 GBK 编码，动态字体前缀为 `[SS-DYNFONT]`。
正常启动应包含：

```text
动态字体已启用: scale=1.5, 55 个文件, 初始化耗时 ... ms
检测到游戏 GL context 就绪（游戏本体正在加载字体）
读条阶段预热 exact 代理: 11/11 套纹理及度量已校验，耗时 ... ms
精确代理映射已原子启用: 11 套，metricScale=64, atlasScale=1.5
```

native 生成详情写入与 `starsector.log` 同级的 `ss_dyn_font_native.log`，每次冷生成重写；
其中 `[warning]` 会转抄进游戏日志。缓存命中通常只需几十毫秒；冷生成耗时与 CPU、字表和
scale 相关，常见桌面多核机器约为数秒。

## 兼容边界

- 仅支持 64 位 Windows；其它系统自动使用静态字体。
- 启动器仍显示 1× 字体，在高缩放下与原版一样可能偏模糊。
- 只接管上述 11 套字体实例；mod 自有字体不变。mod 若主动使用这些路径，会获得同一代理语义。
- 原版 renderer 仍以 Java `char` 查 glyph；U+FFFF 以上字符继续显示 `?` fallback。
- 单页图集是硬限制。扩充字表导致单页无法容纳时会拒绝产物并回退，而不是生成不可加载的分页。
- 缩放在启动时确定；运行中修改需要重启。启动器内改缩放会在进入游戏读条时复检并重新生成。
- 像素吸附只适用于轴对齐 UI 文本；带额外矩阵、旋转或透视的文字保留原版亚像素路径。

## 历史方案

- 最早的离线 1× 静态图集在高缩放下会被直接放大，现仅作为回退。
- 旧 `_hd.fnt/png` 以整数 nominal 计算倍率 `k`，在 195% 等非整数缩放下仍有二次缩放，已由
  exact 代理和 64 倍虚拟度量取代；旧产物不再生成。
- 将纹理过滤全局改为 `GL_NEAREST` 虽在部分字号清晰，但小字号字形破坏明显，已经撤回。
- 最初每个 glyph 独立吸附屏幕像素会让移动文本的字间距交替跳动，现改为整段共同原点。
- 完整接管字体排版/绘制，或引入 SDF/MSDF/ClearType，会扩大 shader、混合、mod 和颜色语义的
  兼容面；当前精确代理复用原 renderer，未采用这些路线。

## 致谢

技术路线参考了 [KasumiNova](https://github.com/KasumiNova)（Hikari_Nova）的
[SSOptimizer](https://github.com/KasumiNova/SSOptimizer)（MIT）中按缩放生成 BMFont、在
renderer 入口替换字体实例和资源流拦截的思路。本模块为独立实现：使用静态 ASM 注入、native
生成器和随汉化包分发的资产，不使用 Java Agent，也未复制其代码。
