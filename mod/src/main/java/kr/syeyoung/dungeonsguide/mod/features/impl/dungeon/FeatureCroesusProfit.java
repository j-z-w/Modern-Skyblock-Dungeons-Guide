package kr.syeyoung.dungeonsguide.mod.features.impl.dungeon;

import kr.syeyoung.dungeonsguide.mod.SkyblockStatus;
import kr.syeyoung.dungeonsguide.mod.config.types.AColor;
import kr.syeyoung.dungeonsguide.mod.config.types.TCBoolean;
import kr.syeyoung.dungeonsguide.mod.events.annotations.DGEventHandler;
import kr.syeyoung.dungeonsguide.mod.events.impl.ScreenRenderEvent;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.richtext.DefaultTextHUDFeatureStyleFeature;
import kr.syeyoung.dungeonsguide.mod.features.richtext.DefaultingDelegatingTextStyle;
import kr.syeyoung.dungeonsguide.mod.features.richtext.NullTextStyle;
import kr.syeyoung.dungeonsguide.mod.features.richtext.TextHUDFeature;
import kr.syeyoung.dungeonsguide.mod.gui.elements.richtext.TextSpan;
import kr.syeyoung.dungeonsguide.mod.gui.renderer.RenderingContext;
import kr.syeyoung.dungeonsguide.mod.overlay.OverlayType;
import kr.syeyoung.dungeonsguide.mod.utils.SkyblockPriceCache;
import kr.syeyoung.dungeonsguide.mod.utils.TextUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FeatureCroesusProfit extends TextHUDFeature {
    private static final Pattern SCREEN_TITLE = Pattern.compile("^(?:Master )?Catacombs - [FloorVI\\d ]*$");
    private static final Pattern CROESUS_BROWSER_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Croesus$");
    private static final Pattern CHEST_COST = Pattern.compile("^([\\d,.]+) Coins$");
    private static final Pattern ENCHANTED_BOOK = Pattern.compile("^Enchanted Book \\((.+) ([IVXLCDM0-9]+)\\)$");
    private static final Pattern ESSENCE = Pattern.compile("^(.+) Essence x(\\d+)$");
    private static final Pattern SHARD = Pattern.compile("^(.+) Shard x(\\d+)$");
    private static final Pattern QUANTITY = Pattern.compile("^(.+?) x(\\d+)$");

    private static final Map<String, String> ITEM_REPLACEMENTS = new HashMap<>();
    static {
        ITEM_REPLACEMENTS.put("Shiny Wither Chestplate", "WITHER_CHESTPLATE");
        ITEM_REPLACEMENTS.put("Shiny Wither Leggings", "WITHER_LEGGINGS");
        ITEM_REPLACEMENTS.put("Shiny Necron's Handle", "NECRON_HANDLE");
        ITEM_REPLACEMENTS.put("Necron's Handle", "NECRON_HANDLE");
        ITEM_REPLACEMENTS.put("Shiny Wither Helmet", "WITHER_HELMET");
        ITEM_REPLACEMENTS.put("Shiny Wither Boots", "WITHER_BOOTS");
        ITEM_REPLACEMENTS.put("Wither Shield", "WITHER_SHIELD_SCROLL");
        ITEM_REPLACEMENTS.put("Implosion", "IMPLOSION_SCROLL");
        ITEM_REPLACEMENTS.put("Shadow Warp", "SHADOW_WARP_SCROLL");
        ITEM_REPLACEMENTS.put("Necron Dye", "DYE_NECRON");
        ITEM_REPLACEMENTS.put("Livid Dye", "DYE_LIVID");
        ITEM_REPLACEMENTS.put("Giant's Sword", "GIANTS_SWORD");
        ITEM_REPLACEMENTS.put("Spirit Stone", "SPIRIT_DECOY");
        ITEM_REPLACEMENTS.put("Warped Stone", "AOTE_STONE");
    }

    public final FeatureParameter<Boolean> includeEssence = new FeatureParameter<Boolean>("include_essence", "Include Essence Value", "Include dungeon essence in chest value using Bazaar instant-sell", true, TCBoolean.INSTANCE);
    public final FeatureParameter<Boolean> highlightSecond = new FeatureParameter<Boolean>("highlight_second", "Highlight Second-Best Chest", "Also highlight the second-best profitable chest in yellow", true, TCBoolean.INSTANCE);
    public final FeatureParameter<Boolean> showHud = new FeatureParameter<Boolean>("show_hud", "Show Chest Breakdown HUD", "Show the Odin-style chest profit breakdown in the top-left HUD", true, TCBoolean.INSTANCE);
    public final FeatureParameter<Boolean> minimized = new FeatureParameter<Boolean>("minimized", "Minimized HUD", "Only show chest profits without per-item values", false, TCBoolean.INSTANCE);

    private volatile List<ChestData> chestData = Collections.emptyList();

    public FeatureCroesusProfit() {
        super("Dungeon HUD", "Croesus Chest Profit", "Highlights profitable chests in the Croesus menu and shows an itemized profit breakdown. Bazaar items use top-of-book instant-sell prices; other items use current lowest BIN. Lowest BIN data is courtesy of lb.tricked.pro.", "dungeon.croesusprofit");
        setEnabled(true);
        addParameter("include_essence", includeEssence);
        addParameter("highlight_second", highlightSecond);
        addParameter("show_hud", showHud);
        addParameter("minimized", minimized);

        registerDefaultStyle("chestWood", colorStyle("Feature Default - Wood Chest", 0xFFFFFFFF));
        registerDefaultStyle("chestIron", colorStyle("Feature Default - Iron Chest", 0xFFE0E0E0));
        registerDefaultStyle("chestGold", colorStyle("Feature Default - Gold Chest", 0xFFFFAA00));
        registerDefaultStyle("chestDiamond", colorStyle("Feature Default - Diamond Chest", 0xFF55FFFF));
        registerDefaultStyle("chestEmerald", colorStyle("Feature Default - Emerald Chest", 0xFF55FF55));
        registerDefaultStyle("chestObsidian", colorStyle("Feature Default - Obsidian Chest", 0xFFAA00AA));
        registerDefaultStyle("chestBedrock", colorStyle("Feature Default - Bedrock Chest", 0xFFAAAAAA));
        registerDefaultStyle("chestDefault", colorStyle("Feature Default - Chest", 0xFFFFFFFF));
        registerDefaultStyle("separator", colorStyle("Feature Default - Separator", 0xFF555555));
        registerDefaultStyle("profitLabel", colorStyle("Feature Default - Profit Label", 0xFFFFAA00));
        registerDefaultStyle("profitPositive", colorStyle("Feature Default - Positive Profit", 0xFF55FF55));
        registerDefaultStyle("profitNegative", colorStyle("Feature Default - Negative Profit", 0xFFFF5555));
        registerDefaultStyle("itemName", colorStyle("Feature Default - Item Name", 0xFFAAAAAA));
        registerDefaultStyle("itemValue", colorStyle("Feature Default - Item Value", 0xFF55FF55));
    }

    private static DefaultingDelegatingTextStyle colorStyle(String name, int color) {
        return DefaultingDelegatingTextStyle.derive(name, () -> FeatureRegistry.DEFAULT_STYLE.getStyle(DefaultTextHUDFeatureStyleFeature.Styles.DEFAULT))
                .setTextShader(new AColor(color, true));
    }

    @Override
    protected OverlayType getOverlayType() {
        return OverlayType.OVER_ANY;
    }

    @Override
    public boolean isHUDViewable() {
        if (!showHud.getValue() || chestData.isEmpty() || !SkyblockStatus.isOnSkyblock()) return false;
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen instanceof ContainerScreen && SCREEN_TITLE.matcher(screen.getTitle().getString()).matches();
    }

    @DGEventHandler
    public void onGuiPostRender(ScreenRenderEvent.Post rendered) {
        if (!(rendered.gui instanceof ContainerScreen screen)) return;
        String title = screen.getTitle().getString();
        if (CROESUS_BROWSER_TITLE.matcher(title).matches()) {
            SkyblockPriceCache.refreshIfNeeded();
            return;
        }
        if (!SCREEN_TITLE.matcher(title).matches()) return;
        if (!(screen.getMenu() instanceof ChestMenu menu)) return;

        SkyblockPriceCache.refreshIfNeeded();

        int rows = menu.getContainer().getContainerSize() / 9;
        int ySize = 114 + rows * 18;
        int left = (rendered.gui.width - 176) / 2;
        int top = (rendered.gui.height - ySize) / 2;
        RenderingContext context = rendered.context;

        if (!SkyblockPriceCache.hasPrices()) {
            context.drawString("DG: loading dungeon chest prices...", left, top - 10, 0xFFFFFF00, false);
            return;
        }
        if (SkyblockPriceCache.getLastError() != null) {
            context.drawString("DG: some chest prices are unavailable", left, top - 10, 0xFFFFFF00, false);
        }

        List<ChestData> chests = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < Math.min(17, menu.slots.size()); slotIndex++) {
            Slot slot = menu.getSlot(slotIndex);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || stack.getItem() != Items.PLAYER_HEAD) continue;
            ChestData chest = parseChest(stack, slotIndex);
            if (chest != null) chests.add(chest);
        }
        if (chests.isEmpty()) return;
        chests.sort((a, b) -> Double.compare(b.profit, a.profit));
        chestData = Collections.unmodifiableList(new ArrayList<>(chests));

        int highlights = highlightSecond.getValue() ? 2 : 1;
        int rank = 0;
        for (ChestData chest : chests) {
            if (chest.profit <= 0 || rank >= highlights) break;
            Slot slot = menu.getSlot(chest.slotIndex);
            int x = left + slot.x;
            int y = top + slot.y;
            int color = rank == 0 ? 0x6600FF00 : 0x66FFFF00;
            context.drawRect(x, y, x + 16, y + 16, color);

            String label = (chest.profit >= 0 ? "+" : "") + TextUtils.format(Math.round(chest.profit));
            context.pose().pushMatrix();
            context.pose().translate((float) (x + 1), (float) (y + 11));
            context.pose().scale(0.5f, 0.5f);
            context.drawString(label, 0, 0, rank == 0 ? 0xFF55FF55 : 0xFFFFFF55, false);
            context.pose().popMatrix();
            rank++;
        }
    }

    private ChestData parseChest(ItemStack stack, int slotIndex) {
        List<String> lore = TextUtils.getFormattedLore(stack);
        if (lore.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(TextUtils.stripColor(line).trim());
        }

        int contentsIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("Contents".equals(lines.get(i))) {
                contentsIndex = i;
                break;
            }
        }
        if (contentsIndex < 0) return null;

        int contentsEnd = lines.size();
        for (int i = contentsIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                contentsEnd = i;
                break;
            }
        }

        double cost = 0;
        for (int i = contentsEnd + 1; i < lines.size(); i++) {
            Matcher matcher = CHEST_COST.matcher(lines.get(i));
            if (matcher.matches()) {
                try {
                    cost = Double.parseDouble(matcher.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    cost = 0;
                }
                break;
            }
        }

        double value = 0;
        List<ChestItem> items = new ArrayList<>();
        for (int i = contentsIndex + 1; i < contentsEnd; i++) {
            String line = lines.get(i);
            double price = parseItemValue(line);
            value += price;
            items.add(new ChestItem(line, price));
        }
        String chestName = stack.getHoverName().getString();
        return new ChestData(chestName, items, value - cost, slotIndex);
    }

    private double parseItemValue(String line) {
        Matcher enchantedBook = ENCHANTED_BOOK.matcher(line);
        if (enchantedBook.matches()) {
            int level = parseLevel(enchantedBook.group(2));
            return positiveOrZero(SkyblockPriceCache.priceForEnchantedBook(enchantedBook.group(1), level));
        }

        Matcher essence = ESSENCE.matcher(line);
        if (essence.matches()) {
            if (!includeEssence.getValue()) return 0;
            String key = "ESSENCE_" + normalize(essence.group(1));
            return positiveOrZero(SkyblockPriceCache.priceFor(key)) * parseInt(essence.group(2));
        }

        Matcher shard = SHARD.matcher(line);
        if (shard.matches()) {
            String key = "SHARD_" + normalize(shard.group(1).replace("'s", ""));
            return positiveOrZero(SkyblockPriceCache.priceFor(key)) * parseInt(shard.group(2));
        }

        boolean starred = line.contains("✪");
        String name = line.replace("✪", "").trim();
        int quantity = 1;
        Matcher quantityMatcher = QUANTITY.matcher(name);
        if (quantityMatcher.matches()) {
            name = quantityMatcher.group(1);
            quantity = parseInt(quantityMatcher.group(2));
        }
        return positiveOrZero(priceForItemName(name, starred)) * quantity;
    }

    private static double priceForItemName(String name, boolean starred) {
        String replacement = ITEM_REPLACEMENTS.get(name);
        if (replacement != null) return SkyblockPriceCache.priceFor(replacement);

        String base = normalize(name.replace(" -", ""));
        String withoutApostrophe = base.replace("'", "");
        String withoutPossessive = base.replace("'S", "");
        if (starred) {
            double starredPrice = firstPrice("STARRED_" + withoutApostrophe, "STARRED_" + withoutPossessive);
            if (starredPrice >= 0) return starredPrice;
        }
        return firstPrice(withoutApostrophe, withoutPossessive);
    }

    private static double firstPrice(String first, String second) {
        double price = SkyblockPriceCache.priceFor(first);
        if (price >= 0) return price;
        return SkyblockPriceCache.priceFor(second);
    }

    @Override
    public TextSpan getDummyText() {
        return buildText(Arrays.asList(
                new ChestData("Bedrock", Arrays.asList(
                        new ChestItem("Enchanted Book (One For All V)", 85_000_000),
                        new ChestItem("Wither Catalyst", 12_000_000),
                        new ChestItem("Necron's Handle", 550_000_000)
                ), 632_000_000, 0),
                new ChestData("Obsidian", Arrays.asList(
                        new ChestItem("Enchanted Book (Legion V)", 45_000_000),
                        new ChestItem("Wither Blood", 8_500_000)
                ), 46_500_000, 1),
                new ChestData("Diamond", Arrays.asList(
                        new ChestItem("Wither Essence x10", 2_000_000),
                        new ChestItem("Recombobulator 3000", 6_500_000)
                ), 5_000_000, 2)
        ));
    }

    @Override
    public TextSpan getText() {
        return buildText(chestData);
    }

    private TextSpan buildText(List<ChestData> chests) {
        TextSpan root = new TextSpan(new NullTextStyle(), "");
        for (ChestData chest : chests) {
            root.addChild(new TextSpan(getStyle(chestStyle(chest.name)), String.format(Locale.US, "%-8s", chest.name)));
            root.addChild(new TextSpan(getStyle("separator"), "- "));
            root.addChild(new TextSpan(getStyle("profitLabel"), "Profit: "));
            root.addChild(new TextSpan(getStyle(chest.profit >= 0 ? "profitPositive" : "profitNegative"), formatNumber(chest.profit) + "\n"));
            if (!minimized.getValue()) {
                for (ChestItem item : chest.items) {
                    root.addChild(new TextSpan(getStyle("itemName"), "  " + item.name + ": "));
                    root.addChild(new TextSpan(getStyle("itemValue"), formatNumber(item.price) + "\n"));
                }
            }
        }
        return root;
    }

    private static String chestStyle(String chestName) {
        String lower = chestName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("wood")) return "chestWood";
        if (lower.startsWith("iron")) return "chestIron";
        if (lower.startsWith("gold")) return "chestGold";
        if (lower.startsWith("diamond")) return "chestDiamond";
        if (lower.startsWith("emerald")) return "chestEmerald";
        if (lower.startsWith("obsidian")) return "chestObsidian";
        if (lower.startsWith("bedrock")) return "chestBedrock";
        return "chestDefault";
    }

    private static String formatNumber(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    private static String normalize(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static double positiveOrZero(double price) {
        return price > 0 ? price : 0;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int parseLevel(String level) {
        if (level.chars().allMatch(Character::isDigit)) return parseInt(level);
        int value = 0;
        int previous = 0;
        for (int i = level.length() - 1; i >= 0; i--) {
            int current = switch (level.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            if (current < previous) value -= current;
            else value += current;
            previous = current;
        }
        return value <= 0 ? 1 : value;
    }

    private static class ChestItem {
        private final String name;
        private final double price;

        private ChestItem(String name, double price) {
            this.name = name;
            this.price = price;
        }
    }

    private static class ChestData {
        private final String name;
        private final List<ChestItem> items;
        private final double profit;
        private final int slotIndex;

        private ChestData(String name, List<ChestItem> items, double profit, int slotIndex) {
            this.name = name;
            this.items = items;
            this.profit = profit;
            this.slotIndex = slotIndex;
        }
    }
}
