/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2026 Modern Dungeons Guide contributors
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

package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import java.util.List;

/**
 * Tracks progress through an authored community route, in canonical room
 * coordinates. Render-only: advancement is decided purely from observable
 * client state (player position, secret-found flags, block-air probes and
 * teleport packets) and never causes the action itself.
 *
 * Progression model:
 *  - steps are visited in authored order; ambient steps never gate progress
 *  - a step completes when (a) its secret is found, (b) the player passes its
 *    last point, or (c) all its "gate" actions are satisfied:
 *      ETHERWARP : teleport landing within ~3.5b of the target, or walking to it
 *      BREAKER   : the authored block became air (mined / superboom)
 *      SUPERBOOM : the authored block became air, or player reached the spot
 *      PEARL     : player within ~3b (pearl landing spot)
 *      INTERACT  : player within ~2.5b (lever/door reach)
 *      BONZO     : player within ~3b
 *    WALK/MARKER actions are breadcrumbs and never required.
 *  - on first tick the tracker fast-forwards to the step whose first point is
 *    nearest the player (entering mid-route / joining late).
 */
public final class RouteStepTracker {

    public interface AirProbe {
        /** true if the block at this canonical position is air */
        boolean isAir(RouteVec3 canonicalPos);
    }

    public static final double PASS_RADIUS = 3.0;
    public static final double ACTION_RADIUS = 2.5;
    public static final double PEARL_RADIUS = 3.0;
    public static final double ETHERWARP_LAND_RADIUS = 3.5;
    public static final double ETHERWARP_WALK_RADIUS = 1.6;
    public static final double TELEPORT_MIN_DIST = 4.0;
    public static final double SEED_RADIUS = 8.0;

    private final List<RouteStep> steps;
    private final boolean[] stepDone;
    private final boolean[][] actionDone;
    private int current = -1; // first non-ambient step, resolved lazily
    private boolean seeded = false;

    public RouteStepTracker(List<RouteStep> canonicalSteps) {
        this.steps = canonicalSteps;
        this.stepDone = new boolean[steps.size()];
        this.actionDone = new boolean[steps.size()][];
        for (int i = 0; i < steps.size(); i++) {
            actionDone[i] = new boolean[steps.get(i).actions.size()];
            if (steps.get(i).ambient) stepDone[i] = true;
        }
        advance();
    }

    public int currentIndex() {
        return current;
    }

    public boolean isStepDone(int i) {
        return stepDone[i];
    }

    public boolean isActionDone(int step, int action) {
        return actionDone[step][action];
    }

    /** index of the step the player should be guided to, or -1 when the route is complete */
    public int guideStep() {
        return current;
    }

    private void advance() {
        if (current < 0) {
            int i = 0;
            while (i < steps.size() && stepDone[i]) i++;
            current = i >= steps.size() ? -1 : i;
            return;
        }
        while (current >= 0 && stepDone[current]) {
            int i = current + 1;
            while (i < steps.size() && stepDone[i]) i++;
            current = i >= steps.size() ? -1 : i;
        }
    }

    private void seed(RouteVec3 player) {
        seeded = true;
        int best = -1;
        double bestDist = SEED_RADIUS * SEED_RADIUS;
        for (int i = 0; i < steps.size(); i++) {
            RouteStep s = steps.get(i);
            if (s.ambient) continue;
            RouteVec3 p = s.firstPoint();
            if (p == null) continue;
            double d = p.distSq(player);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        if (best > 0) {
            for (int i = 0; i < best; i++) stepDone[i] = true;
        }
        advance();
    }

    /**
     * @param player     player position in canonical room coordinates
     * @param secretFound per-step flags: step.secret is collected (feature
     *                    resolves these from DG's ISecret mechanics)
     * @param probe      block-air probe in canonical coordinates
     */
    public void tick(RouteVec3 player, boolean[] secretFound, AirProbe probe) {
        if (player == null) return;
        if (!seeded) seed(player);

        for (int i = 0; i < steps.size(); i++) {
            if (!stepDone[i] && secretFound != null && i < secretFound.length && secretFound[i]) {
                stepDone[i] = true;
            }
        }
        if (current == -1) return;

        RouteStep step = steps.get(current);
        boolean allGates = true;
        for (int a = 0; a < step.actions.size(); a++) {
            if (actionDone[current][a]) continue;
            RouteAction action = step.actions.get(a);
            switch (action.kind) {
                case BREAKER:
                case SUPERBOOM:
                    if (probe != null && action.pos != null && probe.isAir(action.pos)) {
                        actionDone[current][a] = true;
                    } else if (action.pos != null && action.pos.isWithin(player, ACTION_RADIUS)) {
                        actionDone[current][a] = true;
                    }
                    break;
                case ETHERWARP:
                    // primarily completed by onTeleport; walking onto the spot counts too
                    if (action.pos != null && action.pos.isWithin(player, ETHERWARP_WALK_RADIUS)) {
                        actionDone[current][a] = true;
                    }
                    break;
                case PEARL:
                case BONZO:
                    if (action.pos != null && action.pos.isWithin(player, PEARL_RADIUS)) {
                        actionDone[current][a] = true;
                    }
                    break;
                case INTERACT:
                    if (action.pos != null && action.pos.isWithin(player, ACTION_RADIUS)) {
                        actionDone[current][a] = true;
                    }
                    break;
                default:
                    // WALK / MARKER breadcrumbs are informational only
                    actionDone[current][a] = true;
                    break;
            }
            if (isGate(action.kind) && !actionDone[current][a]) allGates = false;
        }
        if (allGates && hasGate(step)) {
            stepDone[current] = true;
        }
        RouteVec3 last = step.lastPoint();
        if (last != null && last.isWithin(player, PASS_RADIUS)) {
            stepDone[current] = true;
        }
        advance();
    }

    /**
     * Server teleported the player (etherwarp, pearl, bonzo, etc.). The feature
     * layer only calls this for packets moving the player TELEPORT_MIN_DIST+.
     * An ETHERWARP action in the current step is done when the landing position
     * is within ETHERWARP_LAND_RADIUS of the authored target.
     */
    public void onTeleport(RouteVec3 landing) {
        if (current == -1 || landing == null) return;
        RouteStep step = steps.get(current);
        boolean changed = false;
        for (int a = 0; a < step.actions.size(); a++) {
            if (actionDone[current][a]) continue;
            RouteAction action = step.actions.get(a);
            if (action.kind == RouteAction.Kind.ETHERWARP
                    && action.pos != null && action.pos.isWithin(landing, ETHERWARP_LAND_RADIUS)) {
                actionDone[current][a] = true;
                changed = true;
            }
        }
        if (changed) {
            boolean allGates = !hasGate(step);
            if (!allGates) {
                allGates = true;
                for (int a = 0; a < step.actions.size(); a++) {
                    RouteAction action = step.actions.get(a);
                    if (isGate(action.kind) && !actionDone[current][a]) {
                        allGates = false;
                        break;
                    }
                }
            }
            if (allGates) stepDone[current] = true;
            advance();
        }
    }

    static boolean isGate(RouteAction.Kind kind) {
        switch (kind) {
            case ETHERWARP:
            case BREAKER:
            case SUPERBOOM:
            case PEARL:
            case INTERACT:
            case BONZO:
                return true;
            default:
                return false;
        }
    }

    private boolean hasGate(RouteStep step) {
        for (RouteAction a : step.actions) {
            if (isGate(a.kind)) return true;
        }
        return false;
    }
}
