package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

/**
 * A single authored action inside a route step. Positions are in the source
 * route file's room-relative frame until transformed by CanonicalTransform.
 */
public final class RouteAction {
    public enum Kind {
        /** walk breadcrumb (SRM 'locations') */
        WALK,
        /** etherwarp target block (SRM 'etherwarps') */
        ETHERWARP,
        /** Dungeon Breaker block to mine (SRM 'mines'), any order within the step */
        BREAKER,
        /** superboom / TNT spot (SRM 'tnts') */
        SUPERBOOM,
        /** ender pearl spot; carries yaw/pitch when authored (SRM enderpearls+angles) */
        PEARL,
        /** right-click interact (lever / door / device) */
        INTERACT,
        /** bonzo staff jump spot */
        BONZO,
        /** untyped marker (Odin waypoints that do not classify) */
        MARKER
    }

    public final Kind kind;
    public final RouteVec3 pos;
    public final String label;
    /** look direction for PEARL, degrees; Float.NaN when not authored */
    public final float yaw;
    public final float pitch;

    public RouteAction(Kind kind, RouteVec3 pos, String label, float yaw, float pitch) {
        this.kind = kind;
        this.pos = pos;
        this.label = label;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public RouteAction(Kind kind, RouteVec3 pos) {
        this(kind, pos, null, Float.NaN, Float.NaN);
    }
}
