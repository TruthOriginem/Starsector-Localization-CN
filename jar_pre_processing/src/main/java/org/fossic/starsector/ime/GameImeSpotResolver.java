package org.fossic.starsector.ime;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;

/** 使用游戏 UI 逻辑坐标和实际屏幕像素尺寸计算候选窗位置。 */
final class GameImeSpotResolver implements ImeSpotResolver {
    @Override
    public ImeSpot resolve(TextFieldAPI field) {
        LabelAPI label = field.getTextLabelAPI();
        PositionAPI textPosition = label != null ? label.getPosition() : null;
        PositionAPI fieldPosition = field.getPosition();
        PositionAPI basis = textPosition != null ? textPosition : fieldPosition;
        if (basis == null) {
            return null;
        }

        float caretX = basis.getX() + basis.getWidth();
        float caretBottom = basis.getY();
        float height = basis.getHeight();
        if (height <= 0f && fieldPosition != null) {
            height = fieldPosition.getHeight();
        }

        SettingsAPI settings = Global.getSettings();
        float logicalHeight = settings.getScreenHeight();
        float pixelHeight = settings.getScreenHeightPixels();
        if (logicalHeight <= 0f || pixelHeight <= 0f) {
            return null;
        }
        float scale = pixelHeight / logicalHeight;
        return new ImeSpot(
                Math.round(caretX * scale),
                Math.round(pixelHeight - (caretBottom + height) * scale),
                Math.round(height * scale));
    }
}
