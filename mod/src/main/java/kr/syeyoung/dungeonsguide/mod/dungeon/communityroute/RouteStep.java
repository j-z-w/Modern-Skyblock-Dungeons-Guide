package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import java.util.ArrayList;
import java.util.List;

/**
 * One step of an authored community route. A step groups the actions the
 * route author expected the player to perform in order to reach/collect a
 * secret (or exit the room).
 */
public final class RouteStep {
    /** type of the step's secret as authored: item, interact, bat, exit, exitroute, prince, dummy, ... */
    public static final class Secret {
        public final String type;
        public final RouteVec3 pos;

        public Secret(String type, RouteVec3 pos) {
            this.type = type;
            this.pos = pos;
        }
    }

    public final List<RouteAction> actions;
    public final Secret secret;
    /** ambient markers render whenever the room is shown but never gate progression */
    public final boolean ambient;

    public RouteStep(List<RouteAction> actions, Secret secret) {
        this(actions, secret, false);
    }

    public RouteStep(List<RouteAction> actions, Secret secret, boolean ambient) {
        this.actions = actions;
        this.secret = secret;
        this.ambient = ambient;
    }

    public List<RouteAction> actionsOf(RouteAction.Kind kind) {
        List<RouteAction> out = new ArrayList<>();
        for (RouteAction a : actions) {
            if (a.kind == kind) out.add(a);
        }
        return out;
    }

    /**
     * Position the step is "finished" at when the player reaches it.
     * Prefers the secret location, then the last authored point.
     */
    public RouteVec3 lastPoint() {
        if (secret != null && secret.pos != null) return secret.pos;
        if (!actions.isEmpty()) return actions.get(actions.size() - 1).pos;
        return null;
    }

    public RouteVec3 firstPoint() {
        if (!actions.isEmpty()) return actions.get(0).pos;
        if (secret != null) return secret.pos;
        return null;
    }
}
