#include "composer.h"

#include <cmath>
#include <cstdlib>
#include <iostream>

using namespace dynfont;

namespace {

[[noreturn]] void fail(const char* expression, int line) {
    std::cerr << "CHECK failed at line " << line << ": " << expression << '\n';
    std::exit(1);
}

#define CHECK(expression) ((expression) ? static_cast<void>(0) : fail(#expression, __LINE__))

}  // namespace

int main() {
    // 125% 下 2px 上移目标为 -2.5px。CJK 精确底边为 12.75，但其
    // BMFont 整数 yoffset 会使整数底边成为 13；两条路必须各用自己的底边。
    BaselineDeltas deltas = calculateBaselineDeltas(10, 13, 10.0, 12.75, 2.5);
    // 整数路径按项目的 Python 兼容舍入：round(2.5)=2，故修正 +1
    // 后两底边差为 -2px；这与精确路径的 -2.5px 是各自契约。
    CHECK(deltas.integerDelta == 1);
    CHECK(std::abs(deltas.preciseDelta - 0.25) < 1e-12);
    CHECK(std::abs((10.0 + deltas.preciseDelta) - 12.75 + 2.5) < 1e-12);
}
