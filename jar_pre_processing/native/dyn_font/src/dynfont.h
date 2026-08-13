/*
 * ss_dyn_font — 公共类型与内置字体规格表。
 *
 * 规格说明见 jar_pre_processing/docs/dynamic-font.md「字体参数」一节。
 * 所有字号/调整值均为 1.0 缩放基准，生成时按游戏 scale 派生（见 composer.cpp）。
 *
 * 渲染语义的金标准是 fnt_composer（Python/PIL/freetype-py）：同参数下本库输出
 * 必须与其逐字形一致，任何看似可"改进"的怪癖（如 bbox 裁剪不补偿 xoffset）都
 * 必须原样保留。
 */
#pragma once

#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace dynfont {

// ── 字形图像：单通道 alpha（最终写 PNG 时展开为 (A,A,A,A)，与静态产物一致）──
struct GlyphImage {
    int w = 0;
    int h = 0;
    std::vector<uint8_t> alpha;  // w*h，行主序

    bool empty() const { return w <= 0 || h <= 0; }
};

// ── 单字形（度量语义与 fnt_composer 的 Glyph 一致）─────────────────────────
struct Glyph {
    uint32_t id = 0;
    int xoffset = 0;
    int yoffset = 0;
    int xadvance = 0;
    // 新渲染器使用的完整 pen advance（物理像素，FreeType 26.6 真值）；与旧
    // BMFont 的 xoffset+xadvance 兼容语义并存，迁移期互不推导。
    double preciseAdvance = std::numeric_limits<double>::quiet_NaN();
    double preciseBearingX = std::numeric_limits<double>::quiet_NaN();
    double preciseBearingY = std::numeric_limits<double>::quiet_NaN();
    GlyphImage img;

    // 装箱后填入
    int dstPage = 0;
    int dstX = 0;
    int dstY = 0;

    // overrides（-1 = 未覆盖；写 .fnt 与装箱尺寸都用覆盖值）
    int widthOverride = -1;
    int heightOverride = -1;

    int width() const { return widthOverride >= 0 ? widthOverride : img.w; }
    int height() const { return heightOverride >= 0 ? heightOverride : img.h; }
};

// ── 渲染模式（三条路径，见文档「渲染规则」）─────────────────────────────────────
enum class RenderMode {
    // ss4 超采样 + Lanczos 降采样 + FT_LOAD_TARGET_LIGHT，字号 = 基准×s 浮点直传。
    // 当前全部 insignia / orbitron / victor 西文与中文。
    LightAA,
    // 直渲 FT_LOAD_NO_HINTING|FT_LOAD_TARGET_MONO，字号 = ceil(基准×s) 整数。
    // 当前无输出规格引用，作为通用像素字体渲染能力保留。
    PixelCeil,
    // Zpix 自动：round(基准×s) ≤16 → 内嵌 strike 位图整数直读；
    //           >16 → 矢量轮廓 mono no-hint，字号 = 基准×s 浮点。
    // 当前无输出规格引用；旧 Zpix 字体源不再打包。
    ZpixAuto,
};

// ── 来源规格（值为 1.0 基准；派生规则见 composer.cpp）───────────────────────
struct SourceSpec {
    const char* file = nullptr;  // dyn_font 目录下的 TTF 文件名（UTF-8）
    double size = 0;             // 基准字号
    RenderMode mode = RenderMode::LightAA;
    double wght = 0;             // >0 时设置可变字体 wght 轴（Orbitron VF）
    double yAdjust = 0;          // 基准像素，生成时 round(y×s)；仅 cjk 源生效（西文垂直
                                 // 位置由 post_align + upshiftPx 全权，见 composeOutput）
    double xadvAdjust = 0;       // 基准像素，生成时 round(x×s)
    double bold = 0;             // 基准像素，生成时 ×s 后在超采样分辨率做方形膨胀
    bool latinOnly = false;      // char_range [32, 0x2FFF]（西文源）
    bool kerning = false;        // 从固化的 GPOS units 表取 kerning（orbitron 西文）
    // 数字等宽：把 0-9 的 advance 统一为其中最大值（字距已计入），窄字形居中。
    // Orbitron 的数字是比例宽度（'1' 仅 '0' 的一半），逐帧变化的读数会左右跳。
    // 与 OutputSpec::digitAdv 二选一：后者用于有原版等宽值要精确匹配的套。
    bool tabularDigits = false;
};

// ── 输出规格（一套 = 一个被拦截的 graphics/fonts/{name}.fnt）────────────────
struct OutputSpec {
    const char* name = nullptr;
    int infoSize = 0;            // 1x 带符号；写出 sign×round(|v|×s)（负号为原版 fnt 惯例）
    int lineHeight = 0;          // 1x；写出 round(v×s)
    int base = 0;
    int upshiftPx = 0;           // 基线规则：西文实心底对齐中文实心底后上飘 round(upshiftPx×s)
                                 // 物理像素（1x 逻辑值，任意 scale 下对齐关系恒定）
    // 数字 0-9 的 xadvance override（1x 基准，逐字符 round(v×s)；全 0 = 无 override）。
    // 值抄录原版 fnt 的数字 advance（原版为加宽等宽设计，个别字符如 1/7 略窄），
    // 保证数字列宽与静态位图版逐字符一致。
    int digitAdv[10] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    // info 行 smooth/aa 抄录原版 fnt（游戏可能据 smooth 决定纹理过滤，
    // victor 若 smooth=1 会线性过滤糊掉像素字）
    int smooth = 1;
    int aa = 1;
    SourceSpec west;
    SourceSpec cjk;
};

// 内置 11 套规格（参数表见文档「字体参数」）。
const std::vector<OutputSpec>& builtinSpecs();

// ── 生成配置 ────────────────────────────────────────────────────────────────
struct GenerateConfig {
    std::wstring typefacePath;   // 分发数据包（TTF + kerning 表，build.py 打包）
    std::wstring charsPath;  // chars.txt
    std::wstring outDir;     // 产物目录（.fnt + PNG；即缓存目录）
    double scale = 1.0;      // 游戏 screenScale（0.05 网格，≥1.0）
    int threads = 0;         // 0 = 硬件并发数
    std::string only;        // 非空：只生成该套（调试用）
    int atlasWidth = -1;     // -1 = auto（枚举 2^n 选面积最小，同 fnt_composer）
};

// 全量生成入口（generate.cpp）。返回 0 成功；错误信息经 log 输出。
int generateAll(const GenerateConfig& config);
bool isSupportedScreenScale(double scale) noexcept;

// ── 日志（CLI 直通 stderr；JNI 模式写文件，参照 ssime 的日志习惯）──────────
void logLine(const char* fmt, ...);
void setLogFile(const std::wstring& path);  // 空 = stderr

}  // namespace dynfont
