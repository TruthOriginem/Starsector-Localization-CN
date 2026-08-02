# 动态字体渲染

按游戏界面缩放（screenScale）在运行时生成中英混排位图字体，使任意缩放档位下文字保持
清晰。玩家端零配置，字表可编辑。仅 Windows。

## 背景

游戏 UI 使用 BMFont 位图字体（`.fnt` 度量文件 + PNG 图集）。汉化需要中文字形，原先
随包分发离线烘焙的 11 套位图，存在两个限制：字表固定，玩家无法自行补字；界面缩放下
必然模糊——游戏在 screenScale>1 时不请求更大字号，而是在几何变换层放大画面，1× 位图
被 GPU 拉伸。

本模块改为运行时从 TTF 栅格化：字表由 `chars.txt` 决定，并额外生成高分辨率图集供
游戏内渲染，实现物理像素 1:1 采样。

## 架构

```
游戏启动
  └─ 请求 graphics/fonts/{name}.fnt
     └─ ResourceLoader.openStream（ASM 注入）
        └─ DynFontOverrides.openStream          链 A：资源流拦截
           首次命中时初始化：检测缩放 → 校验缓存 → 调 native 生成 → 供流

游戏内文本渲染
  └─ BitmapFontRenderer.render（ASM 注入）
     └─ DynFontRenderHooks.resolveFont          链 B：渲染期高清切换
        替换渲染器的 font 字段为 hd 套
```

| 组件 | 位置 | 职责 |
|---|---|---|
| `ss_dyn_font.dll` | `native/dyn_font/` | FreeType 栅格化、度量计算、装箱、PNG 编码；11 套并行生成。CMake + MinGW + Ninja，FreeType 静态链接，PNG 编码使用 vendored fpng |
| `org.fossic.starsector.dynfont.*` | `src/main/java/.../dynfont/` | `DynFontOverrides`（资源拦截、缩放检测、缓存管理）、`DynFontRenderHooks`（渲染期切换）、`DynFontNatives`（JNI 绑定）、`DynFontLog` |
| `ResourceStreamDynFontPatch` | `patches/` | 在 `com.fs.util.C`（ResourceLoader）的 `openStream(String)` 入口插入拦截调用 |
| `RendererDynFontPatch` | `patches/` | 在主渲染器 render 入口插入 `resolveFont` / `adjustSize` |

运行时类注入 `starfarer_obf.jar`，由 `fs.common_obf.jar` 中的 hook 跨 jar 调用；两者
同属游戏固定 classpath。

### 双包模型

每套字体在 `screenScale > 1.001` 时生成两个包；`screenScale <= 1.001` 只生成基础包，
避免为不会使用的高清纹理占用约 200 MB 磁盘与生成时间：

| 包 | 内容 | 使用者 |
|---|---|---|
| `{name}.fnt` / `.png` | 纯 1× 渲染，与 screenScale 无关 | 启动器；游戏内布局层读其度量 |
| `{name}_hd.fnt` / `.png` | ×k 渲染的自洽包 | 仅游戏内渲染器 |

hd 套自洽：装箱框、offset、像素均为 ×k 渲染的真值，`scaleW/H` 为其图集实际尺寸。
布局度量（`xadvance`、`kerning`）由 `syncHdLayoutMetrics` 改写为 `round(1× 值 × k)`，
与基础包保持同比例。

替换字体不影响布局的原理：引擎按 `scale = requestedFontSize / font.nominalSize` 计算
绘制缩放。hd 套的 nominal 为 1× 的 k 倍，而 `requestedFontSize` 保持 1× 逻辑字号不变，
引擎因此得到 `1/k` 的补偿——quad 仍为 1× 逻辑尺寸，经几何层放大后以物理 1:1 采样高清
纹理。布局层（启动器、直接读取 font 度量的 UI 组件）始终只见基础包。

### 缩放比 k

k 与 screenScale 解耦：引擎补偿的是 `1/k`，故 k 只决定纹理密度，不影响布局。两类字体
规则不同（`generate.cpp`）：

**矢量字体**（`vectorHdScale`）：不能直接取 screenScale。`.fnt` 的 `info size` 必须为
整数，`nominal_hd = round(nominal_1x × s)` 的取整误差会使字形缩放比与引擎补偿比不再
互逆，导致 quad 与字距同比例偏移并沿长文本累积。故先确定 `nominal_hd`，再取其精确比值
作为渲染与度量的统一缩放比。整数 s 下退化为 s 本身，余下仅各字形 advance 的 ±0.5px
独立舍入（有界，就近舍入不累积）。

**像素字体**（`pixelHdScale`）：必须为整数——strike 只能整数倍逐像素复制，且整数 k 下
nominal/lineHeight/base 恒为精确整数倍。取 `ceil(s − 0.1)`，下限 2。向上取整是因为密度
过剩仅多占显存，密度不足会退化为放大采样；0.1 容差用于吸收略高于整数的档位（游戏
scale 粒度为 0.05）。victor 改用矢量后已无规格走此路径，代码仅作为通用能力保留。

## 字体参数

11 套输出，参数定义于 `native/dyn_font/src/composer.cpp` 的 `makeSpecs()`。下表为 1×
基准值，各档按 `round(v×s)` 派生。游戏读条共加载 20 套，其余 9 套实机未见明显使用位置，
未纳入（见「已知限制」）。

**insignia 系**（西文 lte50549 + 中文 方正兰亭中粗黑，smooth=1 aa=4）

| 套名 | 西文字号 | 西文 x | 中文字号 | 中文 x | info/lh/base | upshiftPx |
|---|---|---|---|---|---|---|
| insignia15LTaa | 14.5 | 0 | 15 | 0 | 15 / 17 / 15 | 2 |
| insignia21LTaa | 15.0 | +1 | 16 | +1 | 18 / 18 / 16 | 2 |
| insignia25LTaa | 23.0 | 0 | 22 | +1 | 24 / 25 / 22 | 2 |

**orbitron 系**（西文 Orbitron VF + 中文 锐字逼格青春粗黑体简 2.0）

| 套名 | 西文字号 | wght | 西文 x | 中文字号 | cy | 中文 bold | info/lh/base | smooth/aa | upshiftPx |
|---|---|---|---|---|---|---|---|---|---|
| orbitron12condensed | 12.0 | 800 | +1 | 16 | 0 | 0.15 | −12 / 16 / 16 | 1 / 1 | 2 |
| orbitron20aa | 15.5 | 800 | +2 | 18 | +1 | 0.15 | 20 / 20 / 19 | 0 / 4 | 2 |
| orbitron20aabold | 16.0 | 800 | +1 | 18 | +1 | 0.15 | −20 / 20 / 19 | 0 / 4 | 2 |
| orbitron24aa | 18.0 | 800 | +1 | 20 | +1 | 0.15 | −24 / 24 / 21 | 0 / 4 | 0 |
| orbitron24aabold | 20.0 | 800 | +1 | 20 | +1 | 0.15 | 24 / 24 / 21 | 0 / 4 | 0 |

数字 `xadvance` 逐字符覆盖，抄录原版（原版为加宽等宽设计，个别字符略窄）：12c `1`=8
余 10；20 系 `1`=11 余 13；24 系 `1`=13、`7`=14 余 16。orbitron 五套均按 `_w800`
固化表应用 kerning。

**victor 系**（与 orbitron 系同源同策略：西文 Orbitron VF + 中文锐字，smooth=0 aa=1）

| 套名 | 西文字号 | wght | 西文 x | 中文字号 | cy | 中文 bold | info/lh/base | upshiftPx |
|---|---|---|---|---|---|---|---|---|
| victor10 | 10.0 | 900 | +1 | 11 | 0 | 0.17 | −10 / 10 / 9 | 1 |
| victor14 | 10.0 | 800 | +1 | 12 | 0 | 0.15 | −14 / 13 / 11 | 1 |
| victor16 | 13.5 | 800 | +1 | 17 | 0 | 0.15 | −20 / 18 / 16 | 2 |

原为 ZpixEX2_EX 点阵（中英文同源同字号、`pixelFont=true`、hd 走 strike 整数放大）。改用
矢量的原因：strike 整数放大在高缩放下仍是放大的点阵，清晰度不足。当前 victor 三套均使用
Orbitron VF + 锐字，旧的 `victor-pixel.ttf` 与 `ZpixEX2_EX.ttf` 不再作为构建输入，也不再写入
`typefaces.dat`。

`info`/`lineHeight`/`base` 沿用点阵版原值，**布局度量冻结**；中文字号取点阵版原字号
（victor16 例外取 17——锐字 advance/em≈0.963，`round(16×0.963)=15` 会让汉字排版窄 1px，
10/12 恰好进位故无须调整），故汉字 `xadvance` 与视觉大小不变，变的只是字形。西文字号与
`upshiftPx` 均为实机调校值。

字母 `xadvance` 随 Orbitron 字形自然产生（比例宽度，点阵版为等宽），英文串因此变宽，
中文与数字不受影响。victor10 按 `_w900` 固化表应用 kerning，victor14/victor16 使用
`_w800`；未被当前参数引用的旧字重表不再保留或打包。

数字不用 `digitAdv` 覆盖——victor 没有需要逐字符对齐的原版等宽设计。改由西文源的
`tabularDigits` 自动等宽：取 0-9 自然 `xadvance` 的最大值统一，窄字形在新宽度内居中。
该处理在叠加 `x` 之后执行，故字距对数字与字母一致生效，调 `sz`/`x` 时数字自动跟随。

参数语义：`x` 为该源字形的 `xadvance` 增量；`cy` 为中文字形的 `yoffset` 增量（西文无
独立 y 参数，见基线对齐）；`info` 取负值表示抄录原版的像素制标记；`smooth`/`aa` 抄录
原版 `.fnt`。

`bold` 在 4× 超采样分辨率做方形膨胀（`dilateMax`，PIL `ImageFilter.MaxFilter` 语义）。
**该滤镜不扩画布**，而输入是 FreeType 紧贴墨迹的 bbox，故膨胀只能向内填字腔、外轮廓
不动——它是微调手段，加大取值只会把笔画密的字填糊，不能用来整体加粗（实测 victor10
从 0.5 调到 2.0，`一`/`国`/`题` 的墨迹尺寸一个像素都没变）。整体加粗应调 `wght`。

### 渲染规则

- **全部 11 套**：4× 超采样 + Lanczos 降采样 + `FT_LOAD_TARGET_LIGHT`，字号浮点直传；
  orbitron / victor 的西文按 wght 轴实例化可变字体。
- `{` `}` 清零（游戏的高亮标记字符）；`starsector_xadvance_compat` 全局开启。

`RenderMode::ZpixAuto` 与 `PixelCeil` 两条路径、以及 `OutputSpec::pixelFont` 仍在代码中，
但已无规格引用（victor 改用矢量后 11 套全走 `LightAA`），仅保留为通用渲染能力；如需恢复
旧点阵方案，必须重新加入对应字体源。

### 基线对齐

西文无独立 y 参数，垂直位置由装箱后的 `post_align` 统一决定（`composer.cpp`）：

```
target = -round(upshiftPx × s)
bWest  = solidBottom('H')                    实心底 = alpha≥128 的最低行 +1
bCjk   = solidBottom(POST_ALIGN_CJK_REF)     基准汉字「舰」
delta  = target - (bWest - bCjk)
delta ≠ 0 时，所有 id < 0x3000 的字形 yoffset += delta
```

即先将西文实心底对齐至中文实心底，再整体上移 `upshiftPx` 逻辑像素。使用实心底而非
bbox 底，因后者会被抗锯齿灰边污染 2~3px。

两个基准字符均无条件注入字表（ASCII `H` 与 `POST_ALIGN_CJK_REF`），确保玩家替换字表
后对齐仍可执行。

> **位移只能整体施加。** 仅作用于部分字形的位置修正（居中、钳位等）会导致同一行内
> 字形高低不一。

### 战斗 HUD 目标信息栏宽度

战斗中按 R 锁定舰船后，目标框的距离和航速均使用 `victor10.fnt`。原版在
`com/fs/starfarer/renderers/A/null.class` 中把两个数值栏宽度固定为 58 px，航速文本格式为
`%4d su/s`。旧静态 victor10 的空格与数字有效前进宽度相同，1～4 位数字的整串宽度恒为
57 px；动态 victor10 的空格为 4 px、等宽数字为 9 px，宽度会随有效数字增加：两位数
57 px、三位数 62 px、四位数约 67 px。三位数起超过栏宽，文本组件便在 `su/s` 前换行。

`CombatTargetInfoWidthPatch` 以 `RANGE`、`SPEED`、`----m`、`----m/s` 四个字符串共同锚定
唯一的布局初始化方法，只将紧邻 `setSize(FF)` 的两处 `58.0f` 改为 `80.0f`。两列保持等宽，
可容纳五位航速及更大的距离读数；中文标签与数值栏合计仍小于目标框约 128 px 的右侧布局
预算。Patch 要求恰好应用并验证两处，游戏升级导致结构漂移时构建会直接失败，不会误改类中
其他常量。这里不通过缩小字号或调整全局 `xadvance` 规避，否则会影响所有使用 victor10 的
战斗 HUD 文本。

## 实现约束

### 图集尺寸必须是 2 的幂

游戏纹理层对非 POT 图集会进行 padding，而 `.fnt` 的归一化 UV（`x / scaleW`）无法感知
这一点，字形将整体采样至错位区域。装箱器按 2^n 枚举，天然满足；**任何在装箱之后改动
页尺寸的机制都会破坏该性质**。

图集分页同样不可用：BMFont 的 common 行只有一组 `scaleW/H`，且游戏的 `.fnt` 解析器
硬编码只读取一行 page，多页产物在解析时会数组越界。故 `validatePack` 将分页视为生成
失败。

### 渲染期切换的两项要求

替换渲染器的 font 字段需满足：

1. **决策恒定**。缩放在启动时确定，映射建立后不变。否则长文本会被 GLListManager 烘焙
   进 display list（`len×(copies+1)>20` 触发），若烘焙时使用 1× 字体的 UV，之后替换
   font 并绑定新纹理，重放时即产生错位。
2. **时机整齐**。按套懒加载会使各套在不同时刻切换，先渲染的文本同样会烘焙出不一致的
   display list。故 `preloadAllHd` 在首个可切换时刻一次性加载全部 hd 套并建立全部映射。

预加载主动注册原字体（路径由已知规格名构造并保留原始大小写——BitmapFontManager 以
路径字符串为 key，大小写不同会产生第二个 font 实例），不依赖游戏的加载进度。预加载
对每套隔离异常：任一套失败即清空映射、整体回退 1×，避免半 hd 半 1× 的混合状态。

### 读条阶段预热

`preloadAllHd` 挂在渲染入口上，而 `AppDriver.begin` 要等 `ResourceLoaderState.init` 整个
跑完才进入渲染循环——**读条期间一次 render 都没有**。因此渲染侧最早的可切换时刻就是主菜单
第一帧，11 张图集的解码上传（实测数秒）必然砸在那一帧，表现为进入主菜单后卡顿并当场替换
字体。

故在判据翻转处（即 `ResourceLoaderState.init` 内）先行完成两件事：

1. **就地复检缩放**。启动器阶段 `initialize()` 读到的可能是玩家改设置之前的旧值，此处同步
   重新生成（读条里阻塞只是让读条条多走一两秒，渲染线程上则绝不可阻塞）。
2. **`warmUpHdTextures()`**：逐套 `loadOrRegister` 全部 `*_hd.fnt`，把图集喂进显存，**不**
   建立映射。

映射仍留到首帧的 `preloadAllHd` 建立，届时两侧 `loadOrRegister` 全部命中 BitmapFontManager
缓存，开销可忽略。预热只注册 `*_hd.fnt`（游戏永不注册这些路径），不碰原字体，故不会与读条
正在进行的注册相互覆盖。

递归进入 `D.super()` 是安全的：预热由资源流拦截调用，位置在 `C.openStream()` 内，外层此刻
尚未开始使用它那两个静态解析游标（`Object` / `o00000`）——它在拿到流之后才 `readLine()` 并
重新设置它们。同步重生成会整体替换产物映射，故拦截返回前需重取一次文件路径，避免把已被缓存
清理删除的旧路径交给游戏。

### 切换时机与 GL context

启动器与游戏本体运行于同一 JVM 进程，但使用**不同的 GL context**（启动器收尾时
`GLLauncher` 调 `Display.destroy()`，游戏本体再 `Display.create()`）：前者加载的纹理 id
在后者中全部失效，故 hd 套必须等待游戏 context 建立。

判据取**调用栈上是否存在 `CombatMain` 或 `ResourceLoaderState`**。游戏读条注册字体的栈是
`ResourceLoaderState.init → AppDriver.begin → CombatMain.main`，启动器的是
`GLLauncher.prepare → loadFont → GLLauncher$2.run`，两者互斥且这两个类名未被混淆。该事件
位于启动读条阶段，早于 `Global.getSettings()` 可用的时点。判据失败方向是安全的：认不出来
只会一直返回 false，hd 不加载、全程 1×。

**不可退回「基础包 `.fnt` 被二次请求」一类的启发式。** 启动器与游戏同进程，玩家在启动器内
改设置后启动器会在同一 JVM 内重启，`GLLauncher.prepare` 遂第二次加载同一批字体，而静态状态
不会重置——旧判据因此在启动器阶段就误判翻转，hd 套被装进启动器的 GL context；进入游戏后
context 已换，而 `BitmapFontManager`（`com/fs/graphics/A/D`）的 HashMap 是 static 且无 clear，
游戏读条只重新注册原版路径，`*_hd.fnt` 永远停留在失效的纹理 id 上，屏幕上即整片色块。

同进程还意味着玩家在启动器内修改缩放后仅重建 UI，静态状态不会重置。`recheckScaleForGame()`
在游戏阶段复检一次，不一致时由后台线程重新生成（native 生成耗时数秒，不可阻塞渲染
线程）并整体替换产物映射；期间 `isHdReady()` 为 false，渲染侧不加载 hd 套。

### 渲染语义一致性

native 的渲染结果以 Python 实现 `fnt_composer` 为金标准，同参数下逐字形一致。以下行为
系为保持一致而刻意保留，**不得"修正"**：

1. FreeType 版本固定 **2.13.2**（`build.py` 按 `VER-2-13-2` tag 拉取源码）。
2. PIL 带 mask 的 paste 在透明底上等效于 `alpha = MULDIV255(A, A)`（Pillow 的四舍五入
   除 255 位技巧，非截断除）。中间调被平方压暗是既定观感的组成部分，由
   `squareAlphaInPlace` 复刻。
3. Lanczos 复刻自 Pillow 12.2.0 `Resample.c` 的 8bpc 单通道路径：22bit 定点、半像素
   中心、窗口 `(int)(x+0.5)` 舍入、边界窗口重归一。
4. 所有对应 Python `round()` 的取整使用 banker's rounding（half-to-even，`nearbyint`）。
5. 自有 C++ 目标使用 `-O2` 与经 CMake `CheckIPOSupported` 验证的 IPO/LTO（A/B 基准中
   `-O3` 对该工作负载反而退化）；FreeType/fpng 保持其 Release 默认优化。构建维持通用
   x86-64 目标，不使用 `-march=native`。自有渲染核心继续启用 `-ffp-contract=off`，禁止
   会改变金标准结果的 FMA 收缩。
6. bbox 裁剪只将 y0 记入 yoffset，x 方向不补偿 xoffset。

GPOS kerning 走离线固化表：FreeType 的 `FT_Get_Kerning` 只读 kern 表，而 Orbitron 的
字偶距位于 GPOS。`tools/export_kerning.py` 按 wght 实例化导出 font units，native 仅做
`round(units×size/upm)` 像素化。固化表是忽略的构建产物，不入 Git。

字体与字重只有一份权威配置：`composer.cpp` 的 `builtinSpecs()`。重编时
`dynfont_cli --list-assets` 将实际字体及 kerning 依赖导出为入库的 `assets.json`；`build.py`
按清单生成新增字重、删除不再引用的表，并只打包清单列出的文件。因此修改字重后运行
`python build.py dynfont jar` 即可，不再手工维护 Python 字重列表。

## 生成与缓存

### 数据包

TTF 与自动生成的 kerning 固化表位于 `native/dyn_font/fonts/`，由 `build.py` 按
`native/dyn_font/assets.json` 打包为单文件分发。两者均为本机构建资产，不入 Git；
`assets.json` 是随 native 预编译产物提交的依赖快照，支持无需 C++ 工具链的日常 `jar` 构建。
格式（小端，见 `pack_reader.h`）：

```
"SSDF" | uint32 version | uint32 count | count × ( uint16 nameLen | name | uint64 size | payload )
```

按名排序、无时间戳，输入不变则输出字节稳定。

### 运行时布局

```
starsector-core/
├── native/windows/ss_dyn_font.dll   java.library.path，System.loadLibrary 加载
└── graphics/fonts/dyn_font/
    ├── typefaces.dat                 数据包（TTF + kerning 表）
    ├── chars.txt                     字符集，玩家可编辑，重启生效
    └── cache/s{scale}-{指纹}/         生成产物
```

### 缓存策略

指纹以带域、带长度的元组编码计算：
`SHA-256(SPEC_VERSION, typefaces.dat, chars.txt, dll)` 取前 16 位，**不含 scale**——scale
仅体现在目录名前缀。同一份安装的所有缩放档因此共享一个指纹，且文件名/内容边界不会产生
裸拼接歧义。计算前后会复核每个输入的长度和 mtime；冷生成期间发生变化的结果不会发布。

每档含版本化 `.complete` manifest，记录精确 scale、预期文件集合及每个输出的
size/mtime/SHA-256。`screenScale <= 1.001` 必须恰好认领 22 个基础 `.fnt/.png`，更高缩放
必须认领 44 个基础+HD 文件。命中时先校验 manifest 与元数据；元数据变化或距上次完整校验
超过 7 天时重新计算全部输出 SHA-256，任何损坏都回到冷生成。

发布、认领、touch 与剪枝共用 cache-root 文件锁；每个已认领目录另持有跨进程共享
`.in-use.lock` lease 直到 JVM 退出。因此另一个同时运行的游戏实例不会删除仍在使用的档，
同一进程切换过的旧 scale 也会一直保留到退出。清理规则：

1. 删除未被 lease 占用的失效指纹档；
2. 同指纹的非活跃档按最近使用 mtime 保留最多 3 个，活跃档不计入硬淘汰；
3. 超过 24 小时的临时生成目录回收；junction/reparse point 与符号链接只删除链接本身，
   绝不递归进入目标。

单个含 HD 的档约 200 MB，故档数上限是必要的。只读安装目录仍可用共享锁认领完整缓存，
但跳过 touch/剪枝。清理属磁盘维护，任何文件操作失败仅记录日志，不中断生成；缓存命中路径
同样执行清理。旧版、不实现 lease 协议的并发进程无法获得该保护。

`chars.txt` 的可编辑性要求指纹取其**内容哈希**而非 mtime。dll 内容亦在指纹内，故重新
编译 native 会使全部缓存失效。

### 失败处理

设计契约为**任何异常均静默降级为原版位图字体**——包内仍分发完整的静态中文位图字体，
最坏情况下中文显示不受影响。

- 链 A 位于资源加载热路径，异常仅记录一次日志，此后按未命中处理；
- 数据包/dll 缺失、ABI 版本不匹配、native 生成失败 → 禁用动态字体；
- 链 B 任何异常 → 永久降级为不切换；
- 后台重新生成失败 → 保留原有产物。

该契约要求 native 侧不得在已知产物有问题时返回 0，故设三道防线：

| 防线 | 位置 | 拦截对象 |
|---|---|---|
| JNI 异常屏障 | `jni_bridge.cpp` 两个入口，以及 `generateAll` 的 async 任务体 | C++ 异常。异常若逃出 native 方法不会转为 Java 异常，而是穿过 JVM 帧到达 `UnhandledExceptionFilter`，导致进程终止，Java 侧的 `catch(Throwable)` 无法拦截。收敛为 rc=2/3 后走既有降级链 |
| 产物自检 | `generate.cpp` `validatePack`，写盘前 | 页数 >1、产物为空、基线未对齐；并在图集纵向占用超过上限 80% 时预警 |
| 字表编码自检 | `chars_file.cpp` | UTF-16 BOM；非法 UTF-8 序列占比过高。判据不能仅检查是否存在非 ASCII 码点——GBK 的双字节汉字有相当比例会误命中 UTF-8 首字节前缀并解出无效码点 |

`pack_reader` 另对条目声明长度做上界校验：`size` 为文件中直读的 uint64，损坏时会导致
`vector` 构造抛出 `bad_alloc`。

## 构建

```powershell
cd jar_pre_processing
python build.py dynfont      # 重编 native，并从规格刷新 assets.json 与 kerning
python build.py jar          # 同步 kerning、完整 Java 注入管线与产物分发
python build.py dynfont jar  # 修改 native 字体规格后的标准完整流程
```

构建编排回归测试：

```powershell
python -X utf8 -m unittest discover -s tests -v
```

修改 jar 后须重新写回译文，否则分发的是原文状态：

```powershell
cd ..
python -X utf8 para_tranz/para_tranz_script.py 2
```

离线验证（无需启动游戏）：

```bash
./native/dyn_font/build/dynfont_cli.exe \
  --typefaces ../localization/graphics/fonts/dyn_font/typefaces.dat \
  --chars ../localization/graphics/fonts/dyn_font/chars.txt \
  --out <目录> --scale 2.0 [--only <套名>]
```

依赖 CMake + Ninja + MinGW-w64（仅重新编译 native 时）。

## 日志

- **游戏日志**（`starsector.log`，GBK 编码）：前缀 `[SS-DYNFONT]`。关键记录：

  ```
  动态字体已启用: scale=1.0, 22 个文件, 初始化耗时 xxx ms
  动态字体已启用: scale=1.5, 44 个文件, 初始化耗时 xxx ms
  检测到游戏 GL context 就绪（游戏本体正在加载字体）
  读条阶段预热高清套: 11/11 套纹理已就位，耗时 xxxx ms
  预加载高清套: 11/11 套已建立映射，耗时 xx ms
  ```

  预热与预加载的套数应一致；预热生效时后者耗时应在数十毫秒量级——若仍是数秒，说明预热未
  命中，高清套又落回首帧加载。

  验收标准：映射数应等于预期套数，且该行之后不应再出现 `渲染切换高清套:`——出现即
  表示存在走懒路径的套。映射不全时会输出告警。

- **原生日志**（`<游戏 logs 目录>/ss_dyn_font_native.log`，与 `starsector.log` 同级；
  路径由 Java 侧读取 `com.fs.starfarer.settings.paths.logs` 后经 JNI 传入）：逐套生成
  耗时、图集尺寸、hd 的 k 值与纹理密度。每次生成时重写。其中的 `[warning]` 行会被转抄
  进游戏日志。

## 参考耗时

全字表 6754 字，11 套并行（32 核实测）：

| scale | 冷生成 | 备注 |
|---|---|---|
| 1.25 | 1.3 s | |
| 1.5 | 1.5 s | |
| 2.0 | 2.2 s | |
| 3.0 | 4.1 s | insignia25LTaa_hd 图集纵向已用 86% |

缓存命中约 60 ms。

## 已知限制

- **仅 Windows**。dll 为 win64，Linux/macOS 静默回退原版位图字体。
- **启动器在高缩放下模糊**，与原版一致。启动器的 GL context 与游戏不同，且直接读取
  字体度量进行排版，无法使用 hd 套。
- **未接管的 9 套仍为原版位图**：`arial10` / `arial12bold` / `arial16bold` /
  `small_fonts8` / `insignia16` / `insignia16a` / `orbitron10` / `orbitron12` /
  `orbitron20bold` / `victor21`。它们在游戏读条时同样被加载，但实机未见明显使用位置，
  故未纳入。若后续发现某处确实在用且在高缩放下发虚，按 `makeSpecs()` 的现有模式补一
  套规格即可，机制上无新增内容。
- **后台重新生成期间使用基础包渲染**。此窗口内绘制的长文本会将 1× 的 UV 烘焙进
  display list，若之后被重放则与 hd 纹理不匹配。根治方式是将缩放复检提前至启动器阶段
  的 `openStream` 首次命中处，使游戏阶段不再触发重新生成。
- **单页图集上限**。字表约 6.7k 字时 scale≥3.5 会分页，分页产物游戏无法加载，会被
  `validatePack` 拦截并降级。scale 3.0 下已用至 8192 高度的约 87%。提高
  `packer.cpp` 的宽度枚举上限（4096→8192）可缓解，但需先验证目标 GPU 的
  `GL_MAX_TEXTURE_SIZE`——LWJGL2 时代的设备仅保证 4096。

---

## 附录：游戏字体链的反编译结论

0.98a-RC8 Windows 实测。整条字体链位于 `fs.common_obf.jar`（90 类的小 jar），不在
汉化管线原先处理的 `starfarer_obf.jar` / `starfarer.api.jar` 中。

| 还原名 | 混淆名 | 关键成员 |
|---|---|---|
| ResourceLoader | `com.fs.util.C` | `openStream(String)→InputStream`（实例方法、synchronized；按签名匹配，类中唯一） |
| BitmapFontManager | `com.fs.graphics.A.D` | getFont = `static Ò00000(String)→F`（纯 map.get）；register = `static super(String,String)`；注册表 = `static HashMap Ò00000` |
| BitmapFont | `com.fs.graphics.A.F` | nominal = `Õ00000()I`；纹理 getter = `øO0000()Lcom/fs/graphics/Object;` |
| BitmapGlyph | `com.fs.graphics.A.oOOO` | 10 个 int 度量 + 4 个 float UV |
| BitmapFontRenderer | `com.fs.graphics.A.oo` + 254×`O`（274 字符） | render 入口 `Õ00000()V`；setFontSize `Ô00000(F)V`；font 字段 `float.new`；requestedFontSize 字段 `float` |

渲染模型：

- 所有绘制按 `scale = requestedFontSize / font.nominalSize(info size)` 计算。
- **screenScale 不进入字体链**：requested 恒为 1× 逻辑字号，放大在几何变换层完成——
  逻辑坐标系不变，物理像素 ×s。
- **启动器与部分 UI 组件不经 renderer 排版**：`GLLauncher` 及其组件直接读取
  `A.D.getFont(path)` 的度量计算布局，任何向布局层暴露 ×s 度量的方案都会破坏它们。
- **display list 烘焙**：长文本加阴影（`len×(copies+1)>20`）经 GLListManager 缓存，
  重放时不重走取字形路径。
- **纹理过滤恒为 GL_LINEAR**：两个 jar 字节码全扫描 `GL_NEAREST(9728)` 零命中，
  TextureLoader 中 6 处均为 `GL_LINEAR(9729)`。`.fnt` 的 `smooth` 字段不控制纹理过滤。

其它约束：

- `.fnt` 的 `face` 值不得含空格——游戏解析器按空格切分 token，含空格会使后续字段错位。
- 游戏以 `-noverify` 启动，ASM 分支注入无需补充 StackMapTable 帧。
- 游戏自带 Zulu OpenJDK 17.0.10 LTS，Java 运行时类可使用 Java 17 语言特性。

## 致谢

本模块的技术路线借鉴了 [KasumiNova](https://github.com/KasumiNova)（Hikari_Nova）的
开源项目 [SSOptimizer](https://github.com/KasumiNova/SSOptimizer)（MIT 许可）中的运行时
字体缩放实现思路，包括：按屏幕缩放生成高分辨率 BMFont、在渲染入口替换字体实例而保持
`requestedFontSize` 不变以复用引擎自身的 scale 补偿、以及资源流拦截供流的整体结构。
本模块为独立实现（静态字节码注入 + native 生成器 + 随汉化包分发，非 Java Agent 路线），
未复制其代码。
