# 动态字体精确代理渲染设计

状态：代码与自动测试已完成，等待实机视觉与回归验收。本文记录从“双 BMFont 套替换”
迁移到“精确代理 BMFont + 最终物理像素吸附”的设计、边界、实施顺序和验收标准。
当前发布架构仍以 [dynamic-font.md](dynamic-font.md) 为准，实机确认后再合并文档并删除旧 A/B 产物。

## 问题与目标

当前实现生成基础 `.fnt` 与 `_hd.fnt`。游戏以整数 `nominalHd` 计算
`requestedSize / nominalHd`，上层 UI 再乘 `screenScale`。高清图集倍率
`k = nominalHd / nominal1x` 因整数 nominal 量化而常不等于 screenScale：

```text
最终每图集 texel 对应的物理像素 = screenScale / k
```

例如 victor10 在 195% 下使用 `k=2`，最终还会把图集缩小到 97.5%；200% 下恰好为 100%。这解释
了两档缩放间清晰度的突变。即使倍率为 100%，quad 落在半像素相位时，线性采样仍会模糊。

目标：

1. native 直接按 `配置字号 × screenScale` 的 FreeType 26.6 字号栅格化，不经整数 nominal 反推。
2. 基准字号下，exact 图集一个 texel 严格对应 framebuffer 一个物理像素。
3. 测量、换行、字符命中和绘制统一使用同一份 1/64 像素精度数据。
4. 任意请求字号都从 exact 高清图集缩放，不回退基础低清图集。
5. 保留游戏原版颜色、高亮、阴影、下划线、混合、shader 及 mod 兼容路径。
6. 最终可见 quad 对齐 framebuffer 像素边界，但不舍入 pen advance 或改变排版。
7. launcher、未接管字体或任何初始化失败完整执行原版路径。

## 已确认的架构决策

采用“精确代理 BitmapFont”，不重写整套 OpenGL 字体渲染器。

设固定虚拟度量倍率 `M=64`：

```text
atlasScreenScale = 生成 exact 图集时的真实 screenScale
baseNominal      = 调用方使用的逻辑基准字号
proxyNominal     = baseNominal × atlasScreenScale × M
proxyMetric      = exact 图集的物理度量 × M
```

游戏 renderer 的公共倍率仍为 `requestedSize / proxyNominal`，上层 UI 继续乘 screenScale。基准字号时：

```text
proxyMetric × baseNominal / proxyNominal × atlasScreenScale
= physicalMetric × M × baseNominal / (baseNominal × atlasScreenScale × M)
  × atlasScreenScale
= physicalMetric
```

因此精确代理既保留原版所有排版/绘制语义，又消除了 `screenScale/k` 的剩余缩放。`M=64` 与
FreeType 26.6 精度一致；FNT 字段保持 32 位整数范围，当前最大图集 8192 乘 64 仍远低于上限。

基础 `.fnt` 只用于 launcher、字体身份识别和故障回退。游戏 GL context 就绪后一次性加载11套
`{name}_exact.fnt/png`，建立：

```text
基础 BitmapFont 实例 -> exact 代理 BitmapFont 实例
代理 BitmapFont 实例 -> 自身
```

任一必需代理加载失败则整体回退基础字体，禁止同一 UI 内半套切换。

## native 产物

### exact PNG

- 单页 POT 图集，直接按真实 screenScale 栅格化。
- PNG 保持真实像素尺寸，不因 `M` 放大。
- `page` 文件为 `{name}_exact_0.png`。

### exact 代理 FNT

`{name}_exact.fnt` 仍由游戏原生 BMFont 解析器加载，但字段含义为64倍虚拟坐标：

- `info size`、`lineHeight`、`base` 乘 `M`；
- `x/y/width/height` 与虚拟 `scaleW/scaleH` 乘 `M`，UV 分子分母同比抵消；
- bearing、完整 advance 与 kerning 由精确浮点值乘 `M` 后一次舍入；
- 游戏实际 pen advance 是 `xoffset + xadvance`，因此代理写入
  `xadvance = round(advancePhysical × M) - xoffset`；
- `{`、`}` 保持零宽控制字符，数字等宽和数字 kerning 禁用由 native 精确产物表达。

### `.dfnt`

`.dfnt` 继续写出，供产物自检、诊断及未来无法由原版对象表达的扩展数据使用。格式为带版本号的
小端二进制：magic、atlasScale、baseNominal、lineHeight/base、atlas 尺寸、glyph 浮点 bearing/advance
及浮点 kerning。Java 解析器严格拒绝截断、重复 glyph/kerning、非法数值和越界纹理矩形。

格式版本、生成语义版本、native DLL、字体包和字表进入缓存指纹。`.complete` 命中时仍逐套检查
基础 FNT/PNG、exact FNT/PNG 与 `.dfnt`，旧或不完整缓存不能复用。

## 运行时替换

### 时机

launcher 和游戏使用不同 GL context，代理纹理绝不能在 launcher 加载。资源流栈确认进入
`ResourceLoaderState/CombatMain` 后：

1. 阻塞复检游戏最终 screenScale；
2. 缩放变化则在读条阶段重生成 exact 产物；
3. 一次性注册全部 exact FNT，使纹理进入正确 GL context；
4. 建立全套基础到代理映射；
5. 之后 renderer 构造和换字体 setter 立即替换，不能等首帧 render 才替换。

这样 text setter 内缓存的宽高、自动换行、命中测试和实际绘制从对象创建起就是同一套代理数据。

### 测量与排版

主 renderer 下列入口都直接读取当前 BitmapFont，因此无需逐个复制原版算法：

| 签名 | 用途 | 代理行为 |
|---|---|---|
| `Ò00000(String):float` | 多行高度 | 代理 lineHeight |
| `Õ00000(String):float` | 字符串宽度 | 代理 bearing + advance + kerning |
| `Ò00000(int,int):int` | 最近字符命中 | 代理 glyph 矩形和累计位置 |
| `o00000(int):Vector2f` | 字符位置 | 代理累计 advance/kerning |
| `Ò00000(int):float` | glyph 宽 | 代理 glyph width |
| `ô00000(int):float` | glyph 高 | 代理 glyph height |
| `return/Ò00000(float,float)` | 自动换行/裁剪 | 代理 advance/lineHeight |

地图标签及 mod 可以用 `renderer.font.nominal × scale` 派生任意小的 requestedSize。
因此不能用数值阈值猜测字号来源。BitmapFont 的公开 nominal getter 对代理字体
始终返回 base logical nominal；Patch 额外保留 raw proxy nominal getter，仅供原版 renderer
内部缩放/测量使用。这使原版星图和 mod 的 0.2、0.01 等系数都天然正确。

### supplementary Unicode

游戏 renderer 以 Java `char` 索引 glyph，原版数组模型无法直接表达 U+FFFF 以上 codepoint。当前
字表只有3个 supplementary 字符。首版明确保持现有行为：它们由 `?` fallback 显示，不在此次改造中
扩大为 codepoint renderer；否则会从代理改造升级为完整文本迭代器重写，需要单独设计。

## 最终 quad 物理像素吸附

公开 render 入口仍由原版代码负责纹理绑定、颜色、alpha、高亮、阴影、下划线、blend 和 shader。
Patch 只在代理字体的最终 glyph `glVertex2f` 提交处改变坐标。

### 吸附算法

在模型视图和投影合成后仍为轴对齐、可逆的正交变换时：

1. render scope 开始时读取 model-view、projection 和 viewport；
2. 收集每四个顶点构成的 glyph quad；
3. 将左右/上下边界变换到 window 坐标；
4. 分别吸附到物理像素边界，非空字形至少保留一个像素；
5. 反算为当前对象空间坐标，UV 保持不变；
6. 正文与阴影使用相同算法。

吸附仅改变可见 quad，不改变 pen、advance、kerning、测量或换行。旋转、shear、透视、奇异矩阵、
非四顶点序列或 GL 查询失败时原样提交。hook 只读 GL 状态，不切换 program、active texture、纹理、
blend 或颜色，因此 GraphicsLib/BoxUtil 保持原版兼容路径。

### display list

吸附依赖最终屏幕位置，原版长文本 display list 会把第一次位置的相位烘焙后在其他位置复用。
因此只对代理字体绕过原版字体 display list，改走原版即时 quad 生成；基础字体、launcher 和 mod 字体
仍使用原缓存。若 profiling 证明即时路径有显著成本，再设计以物理相位为键的缓存。

## ASM 与逻辑边界

Patch 只负责精确结构校验和最小挂接：

```text
renderer 构造/换字体：font = resolveFont(font)
BitmapFont 公开 nominal：logicalNominal(font, rawNominal)
renderer 内部缩放：font.$dynfontRawNominal()
公开 render 入口：beginQuadScope(font, extraTransform)
每个 draw pass：translate(x, y)
代理专用即时绘制副本：按四顶点组吸附后仍调用原版 glVertex2f
公开 render 的所有正常出口：endQuadScope()
display list 判据：代理字体强制即时路径
```

原版即时绘制和 glyph quad 方法保持原样；Patch 克隆代理专用副本，只在副本中改写
12 处顶点提交。因此未接管字体不会在每个顶点上经过 hook。

代理生成、身份映射、字号归一化、矩阵判断与坐标吸附逻辑放在
`org.fossic.starsector.dynfont` 并以单元测试覆盖。Patch 验证目标字段/方法签名、纹理 getter、
glyph quad 私有方法、`glVertex2f` 数量及注入调用数；结构漂移时构建失败。

## 兼容与回退

- 只接管已知11套动态字体实例；mod 自有字体完全不变。
- mod 通过游戏 renderer 使用这些11套字体时会获得精确代理语义，这是预期行为。
- 不重写 GL 状态或 shader，只读矩阵和 viewport。
- 代理缺字整套使用自身 `?` glyph，不逐字符混用基础字体；`?` 缺失则整套关闭。
- launcher 固定使用基础 FNT。
- 缓存、代理加载、反射或 GL scope 异常只记录一次并永久 fail-open。
- 运行时映射建立后不变；screenScale 改动需重启，与游戏自身规则一致。

## 测试优先顺序

1. **格式测试**：`.dfnt` 往返/损坏拒绝；代理 FNT 64倍 nominal、UV、bearing、advance 和 kerning。
2. **native 金标准**：195% 精确字号、等宽数字、基线、单页边界、代理字段与 exact PNG 对应。
3. **映射测试**：全套原子启用、部分失败整体回退、launcher 门控、字号归一化。
4. **Patch 测试**：构造/setter、scope、display-list、vertex 锚点；缺失/重复锚点 fail loud。
5. **坐标测试**：195/200%、平移、正负轴缩放、退化矩阵、旋转回退、最小宽高。
6. **真实 Jar 构建**：report 中 expected/applied/verified 完全相等，运行时类均被注入。
7. **实机矩阵**：100/125/150/195/200%，窗口/全屏；菜单、intel、tooltip、战斗 HUD、地图缩放、
   输入框、长文本和高亮。
8. **mod 回归**：依赖库 + Console + Nexerelin + Polaris，重点观察 GraphicsLib/BoxUtil、存档读取、
   标题画面及游玩至少十分钟后的纹理稳定性。

## 实施阶段与停止条件

### A. 数据层

完成 exact PNG、代理 FNT、`.dfnt`、缓存完整性和 ABI/spec 失效机制。

### B. 精确代理

在正确 GL context 一次性加载代理，在 renderer 构造/换字体时替换，并修正 nominal 派生字号。

### C. 像素吸附

只对代理字体禁用原版 display list，在最终 quad 提交前加入 framebuffer 物理像素吸附。

### D. 清理迁移

实机确认后删除 `_hd.fnt`、`k` 与旧首帧 swap 逻辑，更新 `dynamic-font.md`。在此之前保留旧生成物
用于 A/B，但构建的实际注入只能启用一种路径。

出现以下情况必须与用户确认而不自行扩大范围：需要改 mod 自有字体；需要完整支持 supplementary
codepoint；必须引入 shader/离屏缓冲；单页 POT 图集无法容纳常用缩放字表；或像素吸附必须改变
advance/换行才能达到可接受效果。
