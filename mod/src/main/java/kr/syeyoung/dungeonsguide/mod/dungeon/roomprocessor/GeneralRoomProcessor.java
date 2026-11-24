/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2021  cyoung06
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

package kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor;

import kr.syeyoung.dungeonsguide.mod.events.impl.RenderHUDEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.RenderWorldEvent;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import kr.syeyoung.dungeonsguide.mod.events.impl.LivingTickEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.LivingDeathEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.ScreenRenderEvent;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.WorldMutatingMechanicState;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.SkyblockStatus;
import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonActionContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.RoomPreset;
import kr.syeyoung.dungeonsguide.mod.pathfinding.world.CoordinateMapBackedPathfindWorld;
// import kr.syeyoung.dungeonsguide.mod.dungeon.roomedit.EditingContext;
// import kr.syeyoung.dungeonsguide.mod.dungeon.roomedit.gui.GuiDungeonAddSet;
// import kr.syeyoung.dungeonsguide.mod.dungeon.roomedit.gui.GuiDungeonRoomEdit;
// import kr.syeyoung.dungeonsguide.mod.dungeon.roomedit.gui.GuiDungeonValueEdit;
// import kr.syeyoung.dungeonsguide.mod.dungeon.roomedit.valueedit.ValueEditOffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.events.impl.BlockUpdateEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.KeyBindPressedEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.PlayerInteractEntityEvent;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.util.*;
import kr.syeyoung.dungeonsguide.mod.events.impl.PlayerInteractEvent;

import java.awt.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.syeyoung.dungeonsguide.mod.utils.TextUtils;
import kr.syeyoung.dungeonsguide.mod.utils.RenderUtils;
import kr.syeyoung.dungeonsguide.mod.utils.Tuple;
import net.minecraft.network.chat.Component;
import kr.syeyoung.dungeonsguide.mod.chat.ChatProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GeneralRoomProcessor implements RoomProcessor {

    @Getter
    private CoordinateMapBackedPathfindWorld pathfinderWorld;

    @Getter
    @Setter
    private DungeonRoom dungeonRoom;
    public GeneralRoomProcessor(DungeonRoom dungeonRoom) {
        this.dungeonRoom = dungeonRoom;

        roomPreset = dungeonRoom.getContext().getPreset().getRoomPreset(dungeonRoom.getDungeonRoomInfo().getUuid());
        algorithmSetting = roomPreset.getEffectiveAlgorithmSetting(dungeonRoom.getDungeonRoomInfo());

        // TODO: move pathfiner world to somewhere outside later.
        setupPathfinderWorld();
    }

    private void setupPathfinderWorld() {
        HashSet<BlockPos> poses = new HashSet<>();
        HashSet<BlockPos> breakerForbidden = new HashSet<>();
        for (DungeonMechanicState value : dungeonRoom.getMechanics().values()) {
            if (value instanceof DungeonTombState) {
                for (OffsetPoint offsetPoint : ((DungeonTombState) value).blockedPoints()) {
                    poses.add(offsetPoint.getBlockPos(dungeonRoom));
                }
            } else if (value instanceof DungeonBreakableWallState) {
                for (OffsetPoint offsetPoint : ((DungeonBreakableWallState) value).blockedPoints()) {
                    poses.add(offsetPoint.getBlockPos(dungeonRoom));
                }
            }
            // oneway doors are deliberately mineable (community routes mine them closed,
            // P4h proposal 2); tombs/breakable walls are superboomable via `poses` already.
            // Room-entrance doors and floor traps stay forbidden.
            if (value instanceof WorldMutatingMechanicState
                    && !(value instanceof DungeonOnewayDoorState)
                    && !(value instanceof DungeonTombState)
                    && !(value instanceof DungeonBreakableWallState)) {
                for (OffsetPoint offsetPoint : ((WorldMutatingMechanicState) value).blockedPoints()) {
                    breakerForbidden.add(offsetPoint.getBlockPos(dungeonRoom));
                }
            }
        }
        pathfinderWorld = new CoordinateMapBackedPathfindWorld(dungeonRoom.getCoordinateMap(), algorithmSetting, dungeonRoom.getRoomBounds(), poses, breakerForbidden);
    }


    @Override
    public void tick() {
        int locallyFound = 0;
        for (DungeonMechanicState value : dungeonRoom.getMechanics().values()) {
            if (value instanceof ISecret) {
                ((ISecret) value).tick(dungeonRoom);
                if (((ISecret) value).isFound(dungeonRoom)) locallyFound++;
            }
        }
        // transitions in the local found-count are tracked so the action bar's "N/M Secrets"
        // counter heuristic can tell a locally-detected find from one detection missed
        if (lastLocallyFound == -1) {
            lastLocallyFound = locallyFound;
        } else if (locallyFound != lastLocallyFound) {
            lastLocallyFound = locallyFound;
            lastLocalSecretChangeMs = System.currentTimeMillis();
        }
    }

    @Override
    public void drawScreen(RenderHUDEvent event) {
        // TODO: room editor is not in the first 26.1 build
//        if (FeatureRegistry.ADVANCED_ROOMEDIT.isEnabled() && FeatureRegistry.DEBUG.isEnabled()) {
//            FontRenderer fr = Minecraft.getInstance().font;

//            if (Minecraft.getInstance().hitResult == null) return;
//            Entity en = Minecraft.getInstance().hitResult.entityHit;
//            if (en == null) return;

//            ScaledResolution sr = new ScaledResolution(Minecraft.getInstance());
//            if (DungeonActionContext.getSpawnLocation().containsKey(en.getEntityId())) {
//                GlStateManager.enableBlend();
//                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
//                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
//                fr.drawString("Spawned at " + DungeonActionContext.getSpawnLocation().get(en.getEntityId()), sr.getScaledWidth() / 2, sr.getScaledHeight() / 2, 0xFFFFFFFF);
//            }
//        }
    }

    @Override
    public void drawWorld(RenderWorldEvent event) {
        // TODO: room editor is not in the first 26.1 build
//        if (FeatureRegistry.DEBUG.isEnabled() && (EditingContext.getEditingContext() != null && EditingContext.getEditingContext().getCurrent() instanceof GuiDungeonRoomEdit)) {
//            for (Map.Entry<String, DungeonMechanicState> value : dungeonRoom.getMechanics().entrySet()) {
//                if (value.getValue() == null) continue;
//                value.getValue().highlight(new Color(0,255,255,50), value.getKey(), event.partialTicks);
//            }
//        }

        // live position marker for tracked secret bats - the route line points at the
        // recorded spawn while the marker follows the bat itself (entity, sound ping, or
        // last-seen); render-only, nothing here mutates completion state. when Box Bats is
        // on it already boxes every live bat, so this only draws the label for a tracked
        // live entity - one box per bat either way
        boolean boxBats = FeatureRegistry.DUNGEON_BOX_BAT.isEnabled();
        for (Map.Entry<String, DungeonMechanicState> entry : dungeonRoom.getMechanics().entrySet()) {
            DungeonMechanicState mechanic = entry.getValue();
            if (!(mechanic instanceof DungeonSecretBatState)) continue;
            DungeonSecretBatState batState = (DungeonSecretBatState) mechanic;
            // one entity lookup serves both the marker position and the boxBats check -
            // the level getEntity call isn't free enough to run twice per bat per frame
            Entity tracked = batState.getTrackedLiveEntity();
            Vec3 live = batState.getLiveBatMarkerPos(tracked);
            if (live == null) continue;
            if (!(boxBats && tracked != null)) {
                RenderUtils.highlightBox(new AABB(live.x - 0.25, live.y, live.z - 0.25, live.x + 0.25, live.y + 0.45, live.z + 0.25),
                        BAT_MARKER_COLOR, event.partialTicks, false);
            }
            RenderUtils.drawTextAtWorld("Bat", (float) live.x, (float) live.y + 0.7f, (float) live.z,
                    0xFF00C8FF, 0.03f, false, true, event.partialTicks);
        }

        // small markers on every unfound secret in this room; the routed target keeps its
        // full block highlight + line, so these stay deliberately tiny
        if (FeatureRegistry.SECRET_MARK_ALL.isEnabled()) {
            for (Map.Entry<String, DungeonMechanicState> entry : dungeonRoom.getMechanics().entrySet()) {
                if (!(entry.getValue() instanceof ISecret)) continue;
                ISecret secret = (ISecret) entry.getValue();
                if (secret.isFound(dungeonRoom)) continue;
                kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint secretPoint = secret.getSecretPoint();
                if (secretPoint == null) continue;
                BlockPos pos = secretPoint.getBlockPos(dungeonRoom);
                RenderUtils.highlightBox(new AABB(pos.getX() + 0.375, pos.getY() + 0.3, pos.getZ() + 0.375,
                                pos.getX() + 0.625, pos.getY() + 0.55, pos.getZ() + 0.625),
                        SECRET_MARKER_COLOR, event.partialTicks, false);
            }
        }
    }

    // shared per-frame colors - one Color alloc per secret per frame added up
    private static final Color BAT_MARKER_COLOR = new Color(0, 200, 255, 80);
    private static final Color SECRET_MARKER_COLOR = new Color(255, 200, 0, 90);

    // chat triggers for secret completion, matched on stripped text
    static final String CHEST_ALREADY_SEARCHED = "This chest has already been searched!";
    static final String REDSTONE_KEY_FOUND = "You found a Secret Redstone Key!";
    static final String WIZARD_QUEST_DONE = "[NPC] Wizard: Oh my lovely crystal ball, mi so happy";

    @Override
    public void chatReceived(Component chat) {
        // compare on stripped text: the message content is the contract, not the exact
        // color-code layout Hypixel happens to wrap it in (a dropped §r used to silently
        // dead-end these detections; skyblocker matches the stripped string too)
        String strippedChat = TextUtils.stripColor(TextUtils.getFormattedText(chat));
        // the skull can stay in the world after a pickup (seen on Hypixel 2026-11), so the block check alone misses it.
        // the chat message is only sent to whoever clicked it, so the closest unfound essence near the player is the one
        if (strippedChat.startsWith("You found a Wither Essence!") && Minecraft.getInstance().player != null) {
            DungeonSecretEssenceState closest = null;
            double closestDist = 8 * 8;
            for (DungeonMechanicState mechanic : getDungeonRoom().getMechanics().values()) {
                if (!(mechanic instanceof DungeonSecretEssenceState)) continue;
                DungeonSecretEssenceState essence = (DungeonSecretEssenceState) mechanic;
                if (essence.isFound()) continue;
                BlockPos essencePos = essence.getData().getSecretPoint().getBlockPos(getDungeonRoom());
                double dist = Minecraft.getInstance().player.position().distanceToSqr(essencePos.getX() + 0.5, essencePos.getY() + 0.5, essencePos.getZ() + 0.5);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = essence;
                }
            }
            if (closest != null) {
                closest.setEssenceWasThere(true);
                closest.setFound(true);
            }
        }
        if (lastChest != null && strippedChat.equals(CHEST_ALREADY_SEARCHED)) {
            for (DungeonMechanicState mechanic : getDungeonRoom().getMechanics().values()) {
                if (mechanic instanceof DungeonSecretChestState) {
                    DungeonSecretChestState chest = (DungeonSecretChestState) mechanic;
                    if (chest.getSecretPoint().getBlockPos(getDungeonRoom()).equals(lastChest)) {
                        chest.markFound();
                    }
                } else if (mechanic instanceof DungeonSecretDoubleChestState) {
                    DungeonSecretDoubleChestState chest = (DungeonSecretDoubleChestState) mechanic;
                    if (chest.getData().getSecretPoint().getBlockPos(dungeonRoom).equals(lastChest)) {
                        chest.markFound();
                    } else if (chest.getData().getSecretPoint2().getBlockPos(dungeonRoom).equals(lastChest)) {
                        chest.markFound();
                    }
                }
            }
            lastChest = null;
        }
        if (strippedChat.equals(REDSTONE_KEY_FOUND)) {
            for (DungeonMechanicState value : getDungeonRoom().getMechanics().values()) {
                if (value instanceof DungeonRedstoneKeyState) {
                    ((DungeonRedstoneKeyState) value).setDidClickOnRedstoneKey(true);
                }
            }
        }
        if (strippedChat.equals(WIZARD_QUEST_DONE)) {
            for (DungeonMechanicState value : getDungeonRoom().getMechanics().values()) {
                if (value instanceof DungeonWizardState) {
                    ((DungeonWizardState) value).setDidCompleteQuest(true);
                }
            }
        }
    }

    private int stack = 0;
    private long secrets2 = 0;
    private int lastLocallyFound = -1;
    private long lastLocalSecretChangeMs = -1L;
    private int lastCounterSecrets = -1;
    private long lastChestInteractMs = -1L;
    @Override
    public void actionbarReceived(Component chat) {
        if (!SkyblockStatus.isOnDungeon()) return;
        if (dungeonRoom.getTotalSecrets() == -1) {
            ChatTransmitter.sendDebugChat(Component.literal(TextUtils.getFormattedText(chat).replace('§', '&') + " - received"));
        }
        if (!TextUtils.getFormattedText(chat).contains("/")) return;
        // TODO: 1.8.9's getPosition() floored (x, y + 0.5, z), (x + 0.5, y + 0.5, z + 0.5) for the local player; blockPosition() floors (x, y, z)
        BlockPos pos = Minecraft.getInstance().player.blockPosition();

        DungeonContext context = DungeonsGuide.getDungeonsGuide().getDungeonFacade().getContext();
        Point pt1 = context.getScaffoldParser().getDungeonMapLayout().worldPointToRoomPoint(pos.offset(2, 0, 2));
        Point pt2 = context.getScaffoldParser().getDungeonMapLayout().worldPointToRoomPoint(pos.offset(-2, 0, -2));
        if (!pt1.equals(pt2)) {
            stack = 0;
            secrets2 = -1;
            // stepping across the room border: secrets found while outside aren't attributable
            lastCounterSecrets = -1;
            return;
        }
        BlockPos pos2 = dungeonRoom.getRoomBounds().getMin().offset(5, 0, 5);

        int[] counter = parseSecretCounter(TextUtils.getFormattedText(chat));
        if (counter == null) return;
        int secrets = counter[1];
        int foundSecrets = counter[0];

        if (secrets2 == secrets) stack++;
        else {
            stack = 0;
            secrets2 = secrets;
        }

        if (stack == 4 && dungeonRoom.getTotalSecrets() != secrets) {
            dungeonRoom.setTotalSecrets(secrets);
            if (FeatureRegistry.DUNGEON_INTERMODCOMM.isEnabled())
                ChatProcessor.sendChatMessage("/pchat $DG-Comm " + pos2.getX() + "/" + pos2.getZ() + " " + secrets);
        }

        onRoomSecretCounter(foundSecrets);
    }

    private static final Pattern SECRET_COUNTER_PATTERN = Pattern.compile("(\\d+)/(\\d+)\\s+Secrets");

    // the action bar carries "N/M Secrets" - Hypixel colors the last M/M group green when the
    // room is done, so the whole thing is parsed on the color-stripped text rather than
    // anchored on §7 (an earlier §7 segment in the bar used to win the backward scan, and a
    // green §a group used to leave foundSecrets at -1 or throw NumberFormatException)
    static int[] parseSecretCounter(String formattedText) {
        String stripped = TextUtils.stripColor(formattedText);
        Matcher m = SECRET_COUNTER_PATTERN.matcher(stripped);
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Hypixel's action bar carries the room's own found-secret count ("N/M Secrets"). Bat kills are
    // the least reliable local detection: the kill is usually only signalled by a removal packet, the
    // bat can fly off, or a teammate can kill it out of range. So when the counter goes up while no
    // secret flipped to found locally in the last ~1s, the find is attributed to the closest
    // still-unfound secret with weak local detection near the player (bats first, then item drops /
    // wither essence; chests and keys have direct detection and are never marked this way).
    // Deliberately conservative: a wrong mark misroutes the player, so any ambiguity -> skip.
    private void onRoomSecretCounter(int foundSecrets) {
        if (foundSecrets < 0) return;
        // the local count is recomputed on every bar message so the delta is clamped against
        // what we can actually see - a teammate's persistent lead used to re-attribute a
        // secret on every action bar update
        int locallyFound = 0;
        for (DungeonMechanicState value : dungeonRoom.getMechanics().values()) {
            if (value instanceof ISecret && ((ISecret) value).isFound(dungeonRoom))
                locallyFound++;
        }
        if (lastLocallyFound != locallyFound) {
            lastLocallyFound = locallyFound;
            lastLocalSecretChangeMs = System.currentTimeMillis();
        }
        if (lastCounterSecrets == -1) {
            // first bar in this room / after a room border crossing: baseline only
            lastCounterSecrets = foundSecrets;
            return;
        }
        if (foundSecrets <= lastCounterSecrets) {
            // the counter resets when secrets in the room are (re)counted from zero
            lastCounterSecrets = foundSecrets;
            return;
        }
        int delta = unattributedDelta(foundSecrets, lastCounterSecrets, locallyFound);
        lastCounterSecrets = foundSecrets;
        if (delta <= 0) return;
        if (attributionSuppressed(System.currentTimeMillis(), lastChestInteractMs, lastLocalSecretChangeMs)) return;
        for (int i = 0; i < delta; i++) {
            if (!markClosestUndetectedSecret()) break;
        }
    }

    // a counter bump within this window of a chest interact / local detection change is the
    // ordinary detection path catching up, not a missing detection - attribute nothing
    static final long COUNTER_QUIET_MS = 1000L;

    static boolean attributionSuppressed(long now, long lastChestInteractMs, long lastLocalSecretChangeMs) {
        return (lastChestInteractMs > 0 && now - lastChestInteractMs < COUNTER_QUIET_MS)
                || (lastLocalSecretChangeMs > 0 && now - lastLocalSecretChangeMs < COUNTER_QUIET_MS);
    }

    // how many secrets the new counter value implies beyond both the previous counter value
    // and the live local found count
    static int unattributedDelta(int foundSecrets, int lastCounterSecrets, int locallyFound) {
        int baseline = lastCounterSecrets > 0 ? lastCounterSecrets : locallyFound;
        return Math.max(0, Math.min(foundSecrets - baseline, foundSecrets - locallyFound));
    }

    // a find is attributed at most ~a dozen blocks from where the player was when it counted;
    // inside that radius every candidate's presence is also entity/chunk tracked and verifiable
    static final double SECRET_COUNTER_RADIUS_SQR = 12 * 12;
    private static final double BAT_SCAN_RADIUS = 12;

    private boolean markClosestUndetectedSecret() {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) return false;
        Vec3 playerPos = Minecraft.getInstance().player.position();

        java.util.List<AttributionCandidate> candidates = new ArrayList<>();
        for (DungeonMechanicState mechanic : dungeonRoom.getMechanics().values()) {
            if (!(mechanic instanceof ISecret)) continue;
            ISecret secret = (ISecret) mechanic;
            if (secret.isFound(dungeonRoom)) continue;
            OffsetPoint secretPoint = secret.getSecretPoint();
            if (secretPoint == null) continue;
            BlockPos pos = secretPoint.getBlockPos(dungeonRoom);
            double dist = pos.distToLowCornerSqr(playerPos.x, playerPos.y, playerPos.z);
            if (dist > SECRET_COUNTER_RADIUS_SQR) continue;
            if (mechanic instanceof DungeonSecretBatState) {
                // a bat still alive near its spawn can't have been the counted kill -
                // and a bat the tracker still sees alive anywhere can't either (it can
                // fly well past the 12-block spawn scan)
                if (liveBatNear(pos)) continue;
                if (((DungeonSecretBatState) mechanic).getTrackedLiveEntity() != null) continue;
                candidates.add(new AttributionCandidate(mechanic, dist, AttributionCandidate.BAT));
            } else if (mechanic instanceof DungeonSecretItemDropState) {
                // DEFINITELY_NOT means the item was seen still sitting there, and a
                // still-visible drop entity means it hasn't been picked up
                if (((DungeonSecretItemDropState) mechanic).getSecretStatus(dungeonRoom) == DungeonSecretItemDropState.SecretStatus.DEFINITELY_NOT)
                    continue;
                if (DungeonSecretItemDropState.isDropPresent(Minecraft.getInstance().level, pos)) continue;
                candidates.add(new AttributionCandidate(mechanic, dist, AttributionCandidate.ITEM_DROP));
            } else if (mechanic instanceof DungeonSecretEssenceState) {
                if (((DungeonSecretEssenceState) mechanic).getSecretStatus(dungeonRoom) == DungeonSecretEssenceState.SecretStatus.DEFINITELY_NOT)
                    continue;
                // skull block still in place means nobody clicked it
                try {
                    if (LegacyBlock.SKULL.is(dungeonRoom.getCachedWorld().getBlockState(pos))) continue;
                } catch (IllegalStateException worldNotReady) {
                    continue; // chunks for this room aren't cached yet; can't verify
                }
                candidates.add(new AttributionCandidate(mechanic, dist, AttributionCandidate.ESSENCE));
            }
        }

        while (true) {
            DungeonMechanicState picked = pickCounterCandidate(candidates);
            if (picked == null) return false;
            String kind;
            if (picked instanceof DungeonSecretBatState) {
                // the authority marks it AND tombstones the anchor so the same kill's
                // late removal record can't complete a second bat secret; a false
                // return means a kill already covers this secret - the counter
                // increment belongs to a different one, so re-pick without it
                if (!kr.syeyoung.dungeonsguide.mod.dungeon.SecretBatKillTracker.markFromCounter((DungeonSecretBatState) picked)) {
                    candidates.removeIf(c -> c.mechanic == picked);
                    continue;
                }
                kind = "bat";
            } else if (picked instanceof DungeonSecretItemDropState) {
                ((DungeonSecretItemDropState) picked).setStatus(DungeonSecretItemDropState.SecretStatus.FOUND);
                ((DungeonSecretItemDropState) picked).setAbsolutelyFound(true);
                kind = "item drop";
            } else {
                ((DungeonSecretEssenceState) picked).setEssenceWasThere(true);
                ((DungeonSecretEssenceState) picked).setFound(true);
                kind = "essence";
            }
            ChatTransmitter.sendDebugChat(Component.literal("room secret counter attributed a find to a " + kind + " secret"));
            return true;
        }
    }

    private static boolean liveBatNear(BlockPos spawnPos) {
        return !Minecraft.getInstance().level.getEntitiesOfClass(Bat.class,
                new AABB(spawnPos).inflate(BAT_SCAN_RADIUS), e -> !e.isRemoved()).isEmpty();
    }

    static final class AttributionCandidate {
        static final int BAT = 0, ITEM_DROP = 1, ESSENCE = 2;
        final DungeonMechanicState mechanic;
        final double distSqr;
        final int kind;

        AttributionCandidate(DungeonMechanicState mechanic, double distSqr, int kind) {
            this.mechanic = mechanic;
            this.distSqr = distSqr;
            this.kind = kind;
        }
    }

    // bats first (weakest detection), then the nearest item drop / essence
    static DungeonMechanicState pickCounterCandidate(java.util.List<AttributionCandidate> candidates) {
        DungeonMechanicState best = null;
        int bestKind = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (AttributionCandidate c : candidates) {
            int rank = c.kind == AttributionCandidate.BAT ? 0 : 1;
            if (best == null || rank < bestKind || (rank == bestKind && c.distSqr < bestDist)) {
                best = c.mechanic;
                bestKind = rank;
                bestDist = c.distSqr;
            }
        }
        return best;
    }

    // secret-bat sound attribution moved to SecretBatKillTracker - the single
    // render-thread authority that owns all bat-secret completion signals

    @Override
    public boolean readGlobalChat() {
        return false;
    }


    @Override
    public void onPostGuiRender(ScreenRenderEvent.Post event) {

    }

    @Override
    public void onEntityUpdate(LivingTickEvent updateEvent) {
        if (updateEvent.entityLiving instanceof ArmorStand &&
                updateEvent.entityLiving.getName() != null &&
                // TODO: D5 entity name stopgap (D116)
                TextUtils.getFormattedText(updateEvent.entityLiving.getName()).contains("Mimic") &&
                !dungeonRoom.getContext().isGotMimic()) {
            dungeonRoom.getContext().setGotMimic(true);
//            ChatProcessor.sendChatMessage("/pc $DG-Mimic");
        }
    }


    private boolean last = false;
    private BlockPos lastChest;

    @Override
    public void onInteractBlock(PlayerInteractEvent event) {

        if (event.pos != null) {
            BlockState iBlockState = event.world.getBlockState(event.pos);
            if (LegacyBlock.CHEST.is(iBlockState) || LegacyBlock.TRAPPED_CHEST.is(iBlockState)) {
                lastChest = event.pos;
                lastChestInteractMs = System.currentTimeMillis();
            }
        }

        // TODO: room editor is not in the first 26.1 build
//        if (event.entityPlayer.getHeldItem() != null &&
//            event.entityPlayer.getHeldItem().getItem() == Items.stick &&
//                FeatureRegistry.ADVANCED_ROOMEDIT.isEnabled() &&
//                FeatureRegistry.DEBUG.isEnabled()) {
//            EditingContext ec = EditingContext.getEditingContext();
//            if (ec != null && ec.getCurrent() instanceof GuiDungeonAddSet) {
//                GuiDungeonAddSet gdas = (GuiDungeonAddSet) ec.getCurrent();
//                if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
//                    if (last)
//                        gdas.getEnd().setPosInWorld(getDungeonRoom(), event.pos);
//                    else
//                        gdas.getStart().setPosInWorld(getDungeonRoom(), event.pos);

//                    last = !last;
//                }
//            }
//            if (ec != null && ec.getCurrent() instanceof GuiDungeonValueEdit) {
//                GuiDungeonValueEdit gdas = (GuiDungeonValueEdit) ec.getCurrent();
//                if (gdas.getValueEdit() instanceof ValueEditOffsetPoint) {
//                    ValueEditOffsetPoint offsetPoint = (ValueEditOffsetPoint) gdas.getValueEdit();

//                    if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
//                        OffsetPoint offsetPoint1 = (OffsetPoint) offsetPoint.getParameter().getNewData();
//                        offsetPoint1.setPosInWorld(getDungeonRoom(), event.pos);
//                    }
//                }
//            }
//            try {
//                if (ec != null && event.pos != null && LegacyBlock.SPONGE.is(Minecraft.getInstance().level.getBlockState(event.pos))) {
//                    int nextId = 1;
//                    while (ec.getRoom().getDungeonRoomInfo().getMechanics().containsKey("ent-" + nextId)) nextId++;
//                    DungeonRoomDoor2State.DungeonRoomDoor2Data door2 = new DungeonRoomDoor2State.DungeonRoomDoor2Data();
//                    ec.getRoom().getDungeonRoomInfo().getMechanics().put("ent-" + nextId, door2);
//                    Direction enumFacing = event.face;
//                    enumFacing = enumFacing.rotateY();
//                    for (int x = -1; x <= 1; x++) {
//                        for (int y = 0; y < 4; y++) {
//                            BlockPos pos = event.pos.add(enumFacing.getFrontOffsetX() * x, y, enumFacing.getFrontOffsetZ() * x);
//                            door2.getBlocks().getOffsetPointList().add(new OffsetPoint(dungeonRoom, pos));
//                        }
//                    }
//                    door2.getPfPoint().setPosInWorld(dungeonRoom, event.pos.add(event.face.getDirectionVec()).add(event.face.getDirectionVec()));
//                    if (ec.getCurrent() instanceof GuiDungeonRoomEdit) {
//                        ((GuiDungeonRoomEdit) ec.getCurrent()).getSep().buildElements();
//                    }


//                }
//            } catch (Exception  e) {e.printStackTrace();}
//        }
    }

    @Override
    public void onEntityDeath(LivingDeathEvent deathEvent) {
        // TODO: room editor is not in the first 26.1 build
//        if (EditingContext.getEditingContext() != null && EditingContext.getEditingContext().getRoom() == getDungeonRoom()) {
//            if (deathEvent.entity instanceof Bat) {
//                for (GuiScreen screen : EditingContext.getEditingContext().getGuiStack()) {
//                    if (screen instanceof GuiDungeonRoomEdit) {
//                        DungeonSecretBatState.DungeonSecretBatData secret = new DungeonSecretBatState.DungeonSecretBatData();
//                        secret.setSecretPoint(new OffsetPoint(dungeonRoom,
//                                DungeonActionContext.getSpawnLocation().get(deathEvent.entity.getEntityId())
//                        ));
//                        ((GuiDungeonRoomEdit) screen).getSep().createNewMechanic("BAT-"+ UUID.randomUUID(), secret);
//                        return;
//                    }
//                }
//                if (EditingContext.getEditingContext().getCurrent() instanceof GuiDungeonRoomEdit) {
//                    DungeonSecretBatState.DungeonSecretBatData secret = new DungeonSecretBatState.DungeonSecretBatData();
//                    secret.setSecretPoint(new OffsetPoint(dungeonRoom,
//                            DungeonActionContext.getSpawnLocation().get(deathEvent.entity.getEntityId())
//                    ));
//                    ((GuiDungeonRoomEdit) EditingContext.getEditingContext().getCurrent()).getSep().createNewMechanic("BAT-"+ UUID.randomUUID(),
//                            secret);
//                }
//            }
//        }
    }

    @Override
    public void onKeybindPress(KeyBindPressedEvent keyInputEvent) {

    }

    @Override
    public void onInteract(PlayerInteractEntityEvent event) {

    }

    public static final BlockState STONE = LegacyBlock.STONE.state(2);
    @Override
    public void onBlockUpdate(BlockUpdateEvent blockUpdateEvent) {
        for (Tuple<BlockPos, BlockState> updatedBlock : blockUpdateEvent.getUpdatedBlocks()) {
            if (updatedBlock.getB().equals(STONE)) continue;
            BlockPos pos = updatedBlock.getA();
            // resetBlock sweeps +-2 x +-5 x +-2 around pos*2 in map coords; out-of-range updates are all no-ops
            if (pos.getX() * 2 + 2 < pathfinderWorld.getMinX() || pos.getX() * 2 - 2 >= pathfinderWorld.getMinX() + pathfinderWorld.getXwidth()
                    || pos.getY() * 2 + 5 < pathfinderWorld.getMinY() || pos.getY() * 2 - 5 >= pathfinderWorld.getMinY() + pathfinderWorld.getYwidth()
                    || pos.getZ() * 2 + 2 < pathfinderWorld.getMinZ() || pos.getZ() * 2 - 2 >= pathfinderWorld.getMinZ() + pathfinderWorld.getZwidth()) continue;
            pathfinderWorld.resetBlock(pos);
        }
    }

    @Override
    public void chunkUpdate(int chunkX, int chunkZ) {
        // resetChunk sweeps [chunkX*32-2, chunkX*32+34) x [chunkZ*32-2, chunkZ*32+34) in doubled
        // map coords; every invalidation outside the world bounds is a no-op, so skip entirely
        if (chunkX * 32 + 34 <= pathfinderWorld.getMinX() || chunkX * 32 - 2 >= pathfinderWorld.getMinX() + pathfinderWorld.getXwidth()
                || chunkZ * 32 + 34 <= pathfinderWorld.getMinZ() || chunkZ * 32 - 2 >= pathfinderWorld.getMinZ() + pathfinderWorld.getZwidth()) return;
        pathfinderWorld.resetChunk(chunkX, chunkZ);
    }



    @Getter
    private AlgorithmSetting algorithmSetting;
    @Getter
    private RoomPreset roomPreset;


    public static class Generator implements RoomProcessorGenerator<GeneralRoomProcessor> {
        @Override
        public GeneralRoomProcessor createNew(DungeonRoom dungeonRoom) {
            GeneralRoomProcessor defaultRoomProcessor = new GeneralRoomProcessor(dungeonRoom);
            return defaultRoomProcessor;
        }
    }
}
