package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import java.util.List;

/**
 * An ordered route through one room. roomKey is the source file's room name
 * (e.g. SRM "Grand-Library-4", Odin "Grand Library"); variant distinguishes
 * alternate routes for the same room (SRM "Room-N:1" -> variant 1).
 */
public final class CommunityRoute {
    public final String roomKey;
    public final int variant;
    public final List<RouteStep> steps;

    public CommunityRoute(String roomKey, int variant, List<RouteStep> steps) {
        this.roomKey = roomKey;
        this.variant = variant;
        this.steps = steps;
    }

    /** all authored secret locations of this route; used for room fingerprinting */
    public void collectSecrets(List<RouteVec3> out) {
        for (RouteStep step : steps) {
            if (step.secret != null && step.secret.pos != null) {
                out.add(step.secret.pos);
            }
        }
    }

    /**
     * Secrets whose type corresponds to a DG mechanic (item->itemdrop,
     * interact->lever/chest, bat->bat). "exit"/"exitroute"/"prince"/"dummy"
     * have no DG counterpart and would cap the achievable match rate, so
     * they're excluded from fingerprint fitting.
     */
    public void collectMatchableSecrets(List<RouteStep.Secret> out) {
        for (RouteStep step : steps) {
            if (step.secret != null && step.secret.pos != null && isMatchableType(step.secret.type)) {
                out.add(step.secret);
            }
        }
    }

    static boolean isMatchableType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("item") || t.contains("interact") || t.contains("bat")
                || t.contains("chest") || t.contains("essence") || t.contains("lever");
    }
}
