/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2023  cyoung06 (syeyoung)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.styles;

import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.fonts.FontRenderer;
import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.shaders.Shader;

public class CompiledTextStyle implements ITextStyle {
    public Double size;
    public Double topAscent;
    public Double bottomAscent;

    public boolean bold;
    public boolean italics;
    public boolean strikeThrough;
    public boolean underline;
    public boolean outline;
    public boolean shadow;
    public boolean background;

    public Shader backgroundShader;
    public Shader textShader;
    public Shader strikeThroughShader;
    public Shader underlineShader;
    public Shader outlineShader;
    public Shader shadowShader;


    public FontRenderer fontRenderer;

    public CompiledTextStyle(ITextStyle from) {
        this.size = from.getSize();
        this.topAscent = from.getTopAscent();
        this.bottomAscent = from.getBottomAscent();

        this.bold = from.isBold();
        this.italics = from.isItalics();
        this.strikeThrough = from.isStrikeThrough();
        this.underline = from.isUnderline();
        this.outline = from.isOutline();
        this.shadow = from.isShadow();
        this.background = from.hasBackground();

        this.backgroundShader = from.getBackgroundShader();
        this.textShader = from.getTextShader();
        this.strikeThroughShader = from.getStrikeThroughShader();
        this.underlineShader = from.getUnderlineShader();
        this.outlineShader = from.getOutlineShader();
        this.shadowShader = from.getShadowShader();
        this.fontRenderer = from.getFontRenderer();
    }


    @Override
    public Boolean hasBackground() {
        return background;
    }

    @Override
    public Double getSize() {
        return size;
    }

    @Override
    public Double getTopAscent() {
        return topAscent;
    }

    @Override
    public Double getBottomAscent() {
        return bottomAscent;
    }

    @Override
    public Boolean isBold() {
        return bold;
    }

    @Override
    public Boolean isItalics() {
        return italics;
    }

    @Override
    public Boolean isStrikeThrough() {
        return strikeThrough;
    }

    @Override
    public Boolean isUnderline() {
        return underline;
    }

    @Override
    public Boolean isOutline() {
        return outline;
    }

    @Override
    public Boolean isShadow() {
        return shadow;
    }

    @Override
    public Shader getBackgroundShader() {
        return backgroundShader;
    }

    @Override
    public Shader getTextShader() {
        return textShader;
    }

    @Override
    public Shader getStrikeThroughShader() {
        return strikeThroughShader;
    }

    @Override
    public Shader getUnderlineShader() {
        return underlineShader;
    }

    @Override
    public Shader getOutlineShader() {
        return outlineShader;
    }

    @Override
    public Shader getShadowShader() {
        return shadowShader;
    }

    @Override
    public FontRenderer getFontRenderer() {
        return fontRenderer;
    }

    // RichText.setRootSpan skips a relayout when the flattened content is unchanged;
    // shaders/font renderers compare by identity which only makes the check conservative
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompiledTextStyle)) return false;
        CompiledTextStyle that = (CompiledTextStyle) o;
        return java.util.Objects.equals(size, that.size) && java.util.Objects.equals(topAscent, that.topAscent)
                && java.util.Objects.equals(bottomAscent, that.bottomAscent)
                && bold == that.bold && italics == that.italics && strikeThrough == that.strikeThrough
                && underline == that.underline && outline == that.outline && shadow == that.shadow
                && background == that.background
                && backgroundShader == that.backgroundShader && textShader == that.textShader
                && strikeThroughShader == that.strikeThroughShader && underlineShader == that.underlineShader
                && outlineShader == that.outlineShader && shadowShader == that.shadowShader
                && fontRenderer == that.fontRenderer;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(size, topAscent, bottomAscent, bold, italics, strikeThrough, underline,
                outline, shadow, background, backgroundShader, textShader, strikeThroughShader, underlineShader,
                outlineShader, shadowShader, fontRenderer);
    }
}
