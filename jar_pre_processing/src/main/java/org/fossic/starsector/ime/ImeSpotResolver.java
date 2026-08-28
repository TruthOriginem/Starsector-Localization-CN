package org.fossic.starsector.ime;

import com.fs.starfarer.api.ui.TextFieldAPI;

/** 从游戏文本框计算候选窗物理像素位置；无法计算时返回 {@code null}。 */
@FunctionalInterface
interface ImeSpotResolver {
    ImeSpot resolve(TextFieldAPI field);
}
