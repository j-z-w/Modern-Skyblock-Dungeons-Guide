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

package kr.syeyoung.dungeonsguide.mod.features.richtext;

import kr.syeyoung.dungeonsguide.mod.config.guiconfig.location2.MarkerProvider;
import kr.syeyoung.dungeonsguide.mod.config.types.TCEnum;
import kr.syeyoung.dungeonsguide.mod.config.types.TCRTextStyleMap;
import kr.syeyoung.dungeonsguide.mod.events.annotations.DGEventHandler;
import kr.syeyoung.dungeonsguide.mod.events.impl.DGTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.SkyblockLeftEvent;
import kr.syeyoung.dungeonsguide.mod.features.AbstractHUDFeature;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.FeatureCollectDiagnostics;
import kr.syeyoung.dungeonsguide.mod.features.richtext.config.WidgetTextStyleConfig;
import kr.syeyoung.dungeonsguide.mod.gui.DomElement;
import kr.syeyoung.dungeonsguide.mod.gui.Widget;
import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.BreakWord;
import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.RichText;
import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.TextSpan;
import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.styles.ParentDelegatingTextStyle;
import kr.syeyoung.dungeonsguide.mod.gui.primitive.Position;
import kr.syeyoung.dungeonsguide.mod.gui.primitive.Rect;
import kr.syeyoung.dungeonsguide.mod.overlay.GUIRectPositioner;
import kr.syeyoung.dungeonsguide.mod.overlay.OverlayType;
import kr.syeyoung.dungeonsguide.mod.overlay.OverlayWidget;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.*;

public abstract class TextHUDFeature extends AbstractHUDFeature {
    protected TextHUDFeature(String category, String name, String description, String key) {
        super(category, name, description, key);

        addParameter("alignment", new FeatureParameter<>("alignment", "Alignment", "Alignment", RichText.TextAlign.LEFT, new TCEnum<>(RichText.TextAlign.values()), richText::setAlign));
        addParameter("newstyle", new FeatureParameter<>("newstyle", "TextStyle", "", styleMap, new TCRTextStyleMap(), this::updateStyle)
                .setWidgetGenerator((param) -> new WidgetTextStyleConfig(getDummyText(), styleMap)));
    }

    @Override
    public boolean isVisible() {
        return super.isVisible() && isHUDViewable();
    }

    private final RichText richText = new RichText(new TextSpan(
            ParentDelegatingTextStyle.ofDefault(),
            ""
    ), BreakWord.WORD, false, RichText.TextAlign.LEFT);

    @Override
    public OverlayWidget instantiateWidget() {
        return new OverlayWidget(richText, OverlayType.UNDER_CHAT, new GUIRectPositioner(this::getFeatureRect), getClass().getSimpleName());
    }
    private TextSpan lastRootSpan;
    private int lastSeenStyleRevision = -1;

    @DGEventHandler
    public void onTick0(DGTickEvent dgTickEvent) {
        try {
            checkVisibility();
            if (isHUDViewable()) {
                TextSpan asd = getText();
                // memoizing getText() implementations hand back the same instance every
                // tick; only reflatten + relayout when the span changed or a style was
                // edited in place (the config GUI mutates style fields directly)
                int revision = GLOBAL_STYLE_REVISION.get();
                if (asd != lastRootSpan || revision != lastSeenStyleRevision) {
                    lastRootSpan = asd;
                    lastSeenStyleRevision = revision;
                    richText.setRootSpan(asd);
                }
            }
        } catch (Exception e) {
            FeatureCollectDiagnostics.queueSendLogAsync(e);
            e.printStackTrace();
        }
    }

    @DGEventHandler(triggerOutOfSkyblock = true)
    public void onSkyblockExit(SkyblockLeftEvent skyblockLeftEvent) {
        try {
            checkVisibility();
        } catch (Exception e) {
            FeatureCollectDiagnostics.queueSendLogAsync(e);}
    }

    public static Font getFontRenderer() {
        return Minecraft.getInstance().font;
    }

    @RequiredArgsConstructor
    public static class TextHUDDemo extends Widget implements MarkerProvider {
        public final TextHUDFeature hudFeature;
        @Override
        public List<Position> getMarkers() {
            RichText.TextAlign change = hudFeature.<RichText.TextAlign>getParameter("alignment").getValue();
            Rect relBound = getDomElement().getRelativeBound();
            if (change == RichText.TextAlign.LEFT) {
                return Arrays.asList(
                        new Position(0, 0),
                        new Position(0, relBound.getHeight())
                );
            } else if (change == RichText.TextAlign.CENTER) {
                return Arrays.asList(
                        new Position(relBound.getWidth() /2, 0),
                        new Position(relBound.getWidth() /2, relBound.getHeight())
                );
            } else if (change == RichText.TextAlign.RIGHT) {
                return Arrays.asList(
                        new Position(relBound.getWidth(), 0),
                        new Position(relBound.getWidth(), relBound.getHeight())
                );
            }
            return null;
        }

        @Override
        public List<Widget> build(DomElement buildContext) {
            TextSpan textSpan = hudFeature.getDummyText();

            RichText richText = new RichText(new TextSpan(
                    ParentDelegatingTextStyle.ofDefault(),
                    ""
            ), BreakWord.WORD, false, hudFeature.<RichText.TextAlign>getParameter("alignment").getValue());

            richText.setRootSpan(textSpan);

            return Collections.singletonList(richText);
        }
    }

    @Override
    public Widget instantiateDemoWidget() {
        return new TextHUDDemo(this);
    }


    public abstract boolean isHUDViewable();

    public TextSpan getDummyText() {
        return getText();
    }
    public abstract TextSpan getText();


    private Map<String, DefaultingDelegatingTextStyle> defaultStyleMap = new HashMap<>();
    private Map<String, DefaultingDelegatingTextStyle> styleMap = new HashMap<>();
    public void registerDefaultStyle(String name, DefaultingDelegatingTextStyle style) {
        defaultStyleMap.put(name, style);
        styleMap.put(name, DefaultingDelegatingTextStyle.derive("User Setting of "+name, () -> defaultStyleMap.get(name)));
    }
    public DefaultingDelegatingTextStyle getStyle(String name) {
        return styleMap.get(name);
    }

    // bumped on every updateStyle so getText() memos that captured style instances can
    // invalidate instead of showing the old style set until some other signature part changes
    private int styleGeneration = 0;

    protected final int getStyleGeneration() {
        return styleGeneration;
    }

    // GUI style edits mutate DefaultingDelegatingTextStyle fields in place (WidgetStyleGroup
    // lines) or swap FeatureRegistry.DEFAULT_STYLE without calling any feature's
    // updateStyle - there is no feature handle to invalidate through, so every edit bumps
    // this instead. HUDs compare it before trusting a memoized TextSpan, and memos like
    // FeatureActions fold it into their getText() signature
    private static final java.util.concurrent.atomic.AtomicInteger GLOBAL_STYLE_REVISION = new java.util.concurrent.atomic.AtomicInteger();

    public static int globalStyleRevision() {
        return GLOBAL_STYLE_REVISION.get();
    }

    public static void styleEdited() {
        GLOBAL_STYLE_REVISION.incrementAndGet();
    }

    public void updateStyle(Map<String, DefaultingDelegatingTextStyle> map) {
        styleGeneration++;
        styleEdited();
        styleMap.clear();
        Set<String> wasIn = new HashSet<>(map.keySet());
        Set<String> needsToBeIn = new HashSet<>(defaultStyleMap.keySet());
        needsToBeIn.removeAll(wasIn);
        for (Map.Entry<String, DefaultingDelegatingTextStyle> stringDefaultingDelegatingTextStyleEntry : map.entrySet()) {
            if (!defaultStyleMap.containsKey(stringDefaultingDelegatingTextStyleEntry.getKey())) continue;
            DefaultingDelegatingTextStyle newStyle = stringDefaultingDelegatingTextStyleEntry.getValue();
            newStyle.setName("User Setting of "+defaultStyleMap.get(stringDefaultingDelegatingTextStyleEntry.getKey()).name);
            newStyle.setParent(() -> defaultStyleMap.get(stringDefaultingDelegatingTextStyleEntry.getKey()));
            styleMap.put(stringDefaultingDelegatingTextStyleEntry.getKey(), newStyle);
        }
        for (String s : needsToBeIn) {
            styleMap.put(s, DefaultingDelegatingTextStyle.derive("User Setting of "+defaultStyleMap.get(s).name, () -> defaultStyleMap.get(s)));
            map.put(s, styleMap.get(s));
        }
    }

    @Override
    public void getTooltipForEditor(List<Widget> widgets) {
        super.getTooltipForEditor(widgets);
//        StyledTextRenderer.Alignment alignment = StyledTextRenderer.Alignment.valueOf(this.<String>getParameter("alignment").getValue());
//        MStringSelectionButton mStringSelectionButton = new MStringSelectionButton(Arrays.asList("LEFT", "CENTER", "RIGHT"), alignment.name());
//        mStringSelectionButton.setOnUpdate(() -> {
//            TextHUDFeature.this.<String>getParameter("alignment").setValue(mStringSelectionButton.getSelected());
//        });
//
//        mPanels.add(new MPassiveLabelAndElement("Alignment", mStringSelectionButton));
//        mPanels.add(new MPassiveLabelAndElement("Scale", new MFloatSelectionButton(TextHUDFeature.this.<Double>getParameter("scale").getValue()) {{
//            setOnUpdate(() ->{
//                TextHUDFeature.this.<Double>getParameter("scale").setValue(this.getData());
//            }); }
//        }));

//        return mPanels;
    }

    @Override
    public void onParameterReset() {

    }

}
