/*
 *     Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 *     Copyright (C) 2021  cyoung06
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics;

import com.google.common.collect.Sets;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.PrecalculatedMoveNearest;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicData;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.DungeonMechanicState;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.dunegonmechanic.ISecret;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics.predicates.PredicateBat;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonActionContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.*;
import kr.syeyoung.dungeonsguide.mod.dungeon.actions.tree.ActionDAGBuilder;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomprocessor.GeneralRoomProcessor;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.utils.RenderUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;

@Data
public class DungeonSecretBatState implements DungeonMechanicState, ISecret {
    private final DungeonSecretBatData data;
    private final DungeonRoom room;

    public DungeonSecretBatState(DungeonSecretBatData data, DungeonRoom room) {
        this.data = data;
        this.room = room;
    }

    private boolean didKillBat;

    // the secret bat this state is following. bats fly off their recorded spawn, so the
    // entity's live/last position is the real attribution anchor, not the waypoint
    private int trackedBatId = -1;
    private Vec3 lastSeenPos;
    private long lastAliveMs;
    // position of the most recent Hypixel secret-bat sound ping heard for this secret -
    // doubles as a live-position hint when no entity is tracked
    private Vec3 heardPos;
    private long heardMs;
    private int nearbyTicks;

    private static final double TRACK_RADIUS = 16;
    private static final double LIVE_SCAN_RADIUS = 12;
    private static final double PROXIMITY_RADIUS = 7;
    private static final int PROXIMITY_TICKS = 100;
    private static final long RECENTLY_ALIVE_MS = 10_000L;
    private static final long HEARD_FRESH_MS = 10_000L;

    public SecretStatus getSecretStatus(DungeonRoom dungeonRoom) {
        BlockPos bpos = data.secretPoint.getBlockPos(dungeonRoom);
        if (didKillBat) {
            return SecretStatus.FOUND;
        }
        Vec3 spawn = Vec3.atLowerCornerOf(bpos);
        // bats can die far from their recorded spawn (they fly off, or a teammate kills them);
        // wasKilledNear checks both the spawn and where the kill/removal was seen
        if (DungeonActionContext.wasKilledNear(spawn, 100)) {
            didKillBat = true;
            return SecretStatus.FOUND;
        }
        if (trackedBatId != -1 && DungeonActionContext.getKilleds().contains(trackedBatId)) {
            didKillBat = true;
            return SecretStatus.FOUND;
        }
        return SecretStatus.NOT_SURE;
    }

    @Override
    public boolean isFound(DungeonRoom dungeonRoom) {
        return getSecretStatus(dungeonRoom) == SecretStatus.FOUND;
    }

    // a Hypixel secret-bat sound ping (0.1f hurt/death) fired near this secret; the position
    // is a better live-position estimate than the spawn while the bat is being chased
    public void noteSecretBatHeard(Vec3 pos) {
        heardPos = pos;
        heardMs = System.currentTimeMillis();
    }

    // distance from pos to the closest of {recorded spawn, tracked bat's last position,
    // latest sound ping}; the room processor uses it to attribute a death ping
    public double distanceToSqrFromTrackedPoint(Vec3 pos, DungeonRoom dungeonRoom) {
        BlockPos bpos = data.secretPoint.getBlockPos(dungeonRoom);
        return distanceToSqrFromTrackedPoint(pos, Vec3.atCenterOf(bpos));
    }

    // spawn-center variant so the anchoring/freshness rules are testable without a room
    public double distanceToSqrFromTrackedPoint(Vec3 pos, Vec3 spawnCenter) {
        double d = pos.distanceToSqr(spawnCenter);
        if (lastSeenPos != null) d = Math.min(d, pos.distanceToSqr(lastSeenPos));
        // a stale ping is where the bat WAS, not where it is - anchoring attribution on
        // it lets a death at an old fight spot claim the secret long after it moved
        if (heardPos != null && System.currentTimeMillis() - heardMs <= HEARD_FRESH_MS) {
            d = Math.min(d, pos.distanceToSqr(heardPos));
        }
        return d;
    }

    // the still-loaded tracked bat entity, if any - FeatureBoxBats already draws a box on
    // every live bat, so the secret marker uses this to skip its own box (one box per bat)
    public Entity getTrackedLiveEntity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && trackedBatId != -1 && mc.level != null) {
            Entity e = mc.level.getEntity(trackedBatId);
            if (e != null && !e.isRemoved()) return e;
        }
        return null;
    }

    // live position for the world marker: the tracked entity while alive, else a fresh
    // sound ping position, else the last-seen position within the freshness window
    public Vec3 getLiveBatMarkerPos() {
        if (didKillBat) return null;
        Entity tracked = getTrackedLiveEntity();
        if (tracked != null) return tracked.position();
        long now = System.currentTimeMillis();
        if (heardPos != null && now - heardMs <= HEARD_FRESH_MS) return heardPos;
        if (lastSeenPos != null && now - lastAliveMs <= RECENTLY_ALIVE_MS) return lastSeenPos;
        return null;
    }

    // last-resort proximity (SRM-style) is only safe when every stronger signal is absent:
    // no live bat near the spawn and the tracked bat hasn't been seen alive recently.
    // documented false-positive: a bat that never rendered into the client is
    // indistinguishable from a killed one, so an early chunk unload can still trip this
    static boolean proximityFallbackAllowed(boolean liveBatNearSpawn, long lastAliveMs, long nowMs, long recentAliveWindowMs) {
        return !liveBatNearSpawn && (lastAliveMs <= 0 || nowMs - lastAliveMs > recentAliveWindowMs);
    }

    @Override
    public void tick(DungeonRoom dungeonRoom) {
        if (didKillBat) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        BlockPos bpos = data.secretPoint.getBlockPos(dungeonRoom);
        Vec3 center = Vec3.atCenterOf(bpos);

        // follow the bat while it's alive so a kill/removal can be attributed even after it
        // flew far from the spawn point
        if (trackedBatId != -1) {
            Entity e = mc.level.getEntity(trackedBatId);
            if (e != null && !e.isRemoved()) {
                lastSeenPos = e.position();
                lastAliveMs = now;
            } else {
                if (DungeonActionContext.getKilleds().contains(trackedBatId)) {
                    didKillBat = true;
                }
                trackedBatId = -1;
            }
        }
        if (trackedBatId == -1 && !didKillBat) {
            Bat nearest = null;
            double bestDist = TRACK_RADIUS * TRACK_RADIUS;
            for (Bat bat : mc.level.getEntitiesOfClass(Bat.class, new AABB(bpos).inflate(TRACK_RADIUS), en -> !en.isRemoved())) {
                double d = bat.distanceToSqr(center);
                if (d < bestDist) {
                    nearest = bat;
                    bestDist = d;
                }
            }
            if (nearest != null) {
                trackedBatId = nearest.getId();
                lastSeenPos = nearest.position();
                lastAliveMs = now;
            }
        }
        if (didKillBat) return;

        // death pings are consumed by GeneralRoomProcessor.onSecretBatSound - the room-
        // routed, closest-bat completer. re-checking them here unconsumed let one ping
        // mark every bat secret within 10 blocks (including across a wall) for 3s

        // any live bat hovering near the spawn is evidence the secret is still there
        boolean liveBatNearby = trackedBatId != -1
                || !mc.level.getEntitiesOfClass(Bat.class, new AABB(bpos).inflate(LIVE_SCAN_RADIUS), en -> !en.isRemoved()).isEmpty();

        // can't find the bat: SRM-style proximity as the last resort only
        // TODO: 1.8.9's getPosition() floored (x, y + 0.5, z), (x + 0.5, y + 0.5, z + 0.5) for the local player; blockPosition() floors (x, y, z)
        if (bpos.distSqr(mc.player.blockPosition()) < PROXIMITY_RADIUS * PROXIMITY_RADIUS
                && proximityFallbackAllowed(liveBatNearby, lastAliveMs, now, RECENTLY_ALIVE_MS)) {
            nearbyTicks++;
        } else {
            nearbyTicks = 0;
        }
        if (nearbyTicks > PROXIMITY_TICKS) {
            didKillBat = true;
        }
    }

    @Override
    public void buildAction(String action, ActionDAGBuilder builder, AlgorithmSetting algorithmSetting) throws PathfindImpossibleException {
        if (action.equalsIgnoreCase("navigate")) {
            builder = builder
                    .requires(new ActionMoveNearestAir(getRepresentingPoint()), algorithmSetting);
            for (String str : data.preRequisite) {
                if (str.isEmpty()) continue;
                builder.optional(new ActionChangeState(str.split(":")[0], str.split(":")[1]), algorithmSetting);
            }
            return;
        }
        if (!"found".equalsIgnoreCase(action))
            throw new PathfindImpossibleException(action + " is not valid state for secret");
        if (action.equals("found") && getSecretStatus(room) == SecretStatus.FOUND) return;

        // the ActionKill completion backs onto this state's richer signals (sound pings,
        // tracked entity, proximity) so the route step finishes when ANY of them fires
        ActionUtils.AtomicActionAccepter killStep = um -> um.requires(() -> {
            ActionKill actionKill = new ActionKill(data.secretPoint);
            actionKill.setRadius(10);
            actionKill.setPredicate(PredicateBat.INSTANCE);
            actionKill.setCompletedBy(() -> getSecretStatus(room) == SecretStatus.FOUND);
            return actionKill;
        });

        if (data.moveNearest != null) {
            ActionUtils.buildActionMoveAnd(builder, room, data.moveNearest, Collections.emptyList(), data.preRequisite, killStep, "MoveAndKill", algorithmSetting);
        } else {
            builder = builder.requires(killStep.build(new AtomicAction.Builder())
                    .requires(new ActionMoveNearestAir(data.secretPoint))
                    .build("MoveAndKill"), algorithmSetting);

            for (String str : data.preRequisite) {
                if (str.isEmpty()) continue;
                builder.optional(new ActionChangeState(str.split(":")[0], str.split(":")[1]), algorithmSetting);
            }
        }
    }

    @Override
    public void highlight(Color color, String name, float partialTicks) {
        BlockPos pos = getSecretPoint().getBlockPos(room);
        RenderUtils.highlightBlock(pos, color, partialTicks);
        RenderUtils.drawTextAtWorld(name, pos.getX() + 0.5f, pos.getY() + 0.375f, pos.getZ() + 0.5f, 0xFFFFFFFF, 0.03f, false, true, partialTicks);
        RenderUtils.drawTextAtWorld(getCurrentState(), pos.getX() + 0.5f, pos.getY() + 0f, pos.getZ() + 0.5f, 0xFFFFFFFF, 0.03f, false, true, partialTicks);

        // the bat the secret is tracking - follows the live/last-heard position, not the spawn
        Vec3 live = getLiveBatMarkerPos();
        if (live != null) {
            RenderUtils.highlightBox(new AABB(live.x - 0.25, live.y, live.z - 0.25, live.x + 0.25, live.y + 0.45, live.z + 0.25), color, partialTicks, false);
            RenderUtils.drawTextAtWorld("Bat", (float) live.x, (float) live.y + 0.7f, (float) live.z, 0xFFFFFFFF, 0.03f, false, true, partialTicks);
        }

        if (data.moveNearest != null && FeatureRegistry.DEBUG_AIR.isEnabled())
            data.moveNearest.render(partialTicks, room);
    }

    public enum SecretType {
        BAT, CHEST, ITEM_DROP, ESSENCE
    }

    @AllArgsConstructor
    @Getter
    public enum SecretStatus {
        DEFINITELY_NOT("definitely_not"), NOT_SURE("not_sure"), CREATED("created"), FOUND("found"), ERROR("error");

        private final String stateName;
    }


    @Override
    public String getCurrentState() {
        return getSecretStatus(room).getStateName();
    }

    @Override
    public Set<String> getAvailableActions() {
        SecretStatus status = getSecretStatus(room);
        if (status == SecretStatus.FOUND) return Sets.newHashSet("navigate");
        else return Sets.newHashSet("found", "navigate");
    }

    @Override
    public Set<String> getTotalPossibleStates() {
        return Sets.newHashSet("found"/*, "definitely_not", "not_sure", "created", "error"*/);
    }

    @Override
    public OffsetPoint getRepresentingPoint() {
        return data.secretPoint;
    }

    @Override
    public OffsetPoint getSecretPoint() {
        return data.secretPoint;
    }
    @Override
    public List<String> getPreRequisite() {
        return data.preRequisite;
    }

    @Data
    public static class DungeonSecretBatData implements DungeonMechanicData {
        private OffsetPoint secretPoint = new OffsetPoint(0, 0, 0);
        private PrecalculatedMoveNearest moveNearest;
        private List<String> preRequisite = new ArrayList<String>();


        public DungeonSecretBatData() {
        }

        @Override
        public DungeonSecretBatState createState(DungeonRoom room) {
            return new DungeonSecretBatState(this, room);
        }

        public DungeonSecretBatData clone() throws CloneNotSupportedException {
            DungeonSecretBatData data = new DungeonSecretBatData();
            data.secretPoint = (OffsetPoint) secretPoint.clone();
            data.moveNearest = moveNearest;
            data.preRequisite = new ArrayList<String>(preRequisite);
            ;
            return data;
        }
    }
}
