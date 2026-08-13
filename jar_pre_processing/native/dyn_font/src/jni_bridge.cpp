/*
 * ss_dyn_font.dll 的 JNI 入口。
 *
 * 对应 Java 类：org.fossic.starsector.dynfont.DynFontNatives（Java 薄层，
 * 注入 starfarer_obf.jar；见 jar_pre_processing 的动态字体运行时模块）。
 *
 * 设计：JNI 面最小化 —— native 把 .fnt+PNG 产物直接写进输出目录（即缓存目录），
 * Java 侧 openStream hook 从该目录读文件供流，不跨 JNI 传图集字节。
 */
#include <jni.h>

#include <exception>
#include <string>

#include "dynfont.h"

namespace {

std::wstring jstringToWide(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) {
        return {};
    }
    jsize len = env->GetStringLength(s);
    static_assert(sizeof(jchar) == sizeof(wchar_t), "wchar_t must be 16-bit on Windows");
    std::wstring out(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(len));
    env->ReleaseStringChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

// ABI 版本（Java 侧校验，dll 与 Java 薄层不匹配时降级禁用）
JNIEXPORT jint JNICALL
Java_org_fossic_starsector_dynfont_DynFontNatives_nativeVersion(JNIEnv*, jclass) {
    return 9;  // v9：每套字体新增精确度量 .dfnt 与 exact 高清图集
}

/*
 * 全量生成 11 套字体到 outDir。
 *
 * @param typefacePath 分发数据包（graphics/fonts/dyn_font/typefaces.dat，build.py 打包）
 * @param charsPath chars.txt 路径
 * @param outDir 产物目录（.fnt + PNG；同时作为日志目录）
 * @param scale 游戏 screenScale（0.05 网格，≥1.0）
 * @param only 非空时只生成该套（调试）
 * @return 0 成功；非 0 失败（详情见 outDir/ss_dyn_font_native.log）
 *         2 = C++ 标准异常；3 = 未知异常
 *
 * 异常屏障是强制的：C++ 异常若逃出 native 方法，不会变成 Java 异常，而是穿过
 * JVM 帧一路走到 UnhandledExceptionFilter —— 整个游戏进程静默死亡，Java 侧的
 * catch(Throwable) 完全够不着。收敛为非 0 返回码后，走既有的
 * 「rc != 0 → IOException → disable() → 回退原版位图字体」降级链。
 */
JNIEXPORT jint JNICALL
Java_org_fossic_starsector_dynfont_DynFontNatives_nativeGenerate(
        JNIEnv* env, jclass, jstring typefacePath, jstring charsPath, jstring outDir,
        jdouble scale, jstring only, jstring logPath) {
    try {
        dynfont::GenerateConfig config;
        config.typefacePath = jstringToWide(env, typefacePath);
        config.charsPath = jstringToWide(env, charsPath);
        config.outDir = jstringToWide(env, outDir);
        config.scale = scale;

        std::wstring onlyW = jstringToWide(env, only);
        config.only.assign(onlyW.begin(), onlyW.end());  // 规格名均为 ASCII

        // 日志路径由 Java 侧按游戏的 logs 目录构造（与 starsector.log 同级），
        // 而非写在产物目录内 —— 那里玩家不会翻到
        dynfont::setLogFile(jstringToWide(env, logPath));
        return dynfont::generateAll(config);
    } catch (const std::exception& e) {
        dynfont::logLine("[error] native 异常: %s", e.what());
        return 2;
    } catch (...) {
        dynfont::logLine("[error] native 未知异常");
        return 3;
    }
}

}  // extern "C"
