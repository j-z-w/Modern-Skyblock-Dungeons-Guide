/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.mixin;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
    // 26.3 throws when an offscreen scrolling gui element produces an empty scissor
    // intersection. Such an element cannot render anything, so dropping it is equivalent
    // and avoids the vanilla crash fixed upstream in 26.4.
    @Inject(method = "addElementToMesh", at = @At("HEAD"), cancellable = true)
    private void dungeonsguide$dropEmptyScissorElements(GuiElementRenderState element, CallbackInfo ci) {
        ScreenRectangle scissor = element.scissorArea();
        if (scissor != null && (scissor.width() <= 0 || scissor.height() <= 0)) {
            ci.cancel();
        }
    }
}
