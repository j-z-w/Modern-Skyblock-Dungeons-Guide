package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Secret Routes Mod route files (fowroutes.json / *routes.json).
 *
 * Format (observed):
 *   {
 *     "#origin": "...", "#copyright": "...", "Version": "1.1.0",
 *     "Room-Name-N[:variant]": [
 *       { "locations": [[x,y,z]...], "etherwarps": [...], "mines": [...],
 *         "interacts": [...], "tnts": [...], "enderpearls": [[x,y,z]],
 *         "enderpearlangles": [[yaw,pitch]], "bonzo_staffs": [...],
 *         "secret": {"type": "interact", "location": [x,y,z]} },
 *       ...
 *     ]
 *   }
 * Coordinates are room-relative to the clay corner of the canonical (SOUTH)
 * orientation, absolute in y. Secret types: item, interact, bat, exit,
 * exitroute, prince, dummy.
 */
public final class SrmRouteParser {

    public RoutePack parse(String id, String fileName, Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        String origin = str(root, "#origin");
        String copyright = str(root, "#copyright");
        RoutePack pack = new RoutePack(id, RoutePack.Format.SRM, fileName, origin, copyright);

        for (String key : root.keySet()) {
            if (key.startsWith("#") || key.equals("Version")) continue;
            JsonElement el = root.get(key);
            if (!el.isJsonArray()) continue;

            String roomKey = key;
            int variant = 0;
            int colon = key.indexOf(':');
            if (colon >= 0) {
                roomKey = key.substring(0, colon);
                try {
                    variant = Integer.parseInt(key.substring(colon + 1));
                } catch (NumberFormatException e) {
                    variant = 0;
                }
            }

            List<RouteStep> steps = new ArrayList<>();
            for (JsonElement stepEl : el.getAsJsonArray()) {
                if (!stepEl.isJsonObject()) continue;
                RouteStep step = parseStep(stepEl.getAsJsonObject());
                if (!step.actions.isEmpty() || step.secret != null) steps.add(step);
            }
            if (!steps.isEmpty()) {
                pack.addRoute(new CommunityRoute(roomKey, variant, steps));
            }
        }
        return pack;
    }

    private RouteStep parseStep(JsonObject step) {
        List<RouteAction> actions = new ArrayList<>();

        for (RouteVec3 v : vecs(step, "locations")) {
            actions.add(new RouteAction(RouteAction.Kind.WALK, v));
        }
        for (RouteVec3 v : vecs(step, "etherwarps")) {
            actions.add(new RouteAction(RouteAction.Kind.ETHERWARP, v, "etherwarp", Float.NaN, Float.NaN));
        }
        for (RouteVec3 v : vecs(step, "mines")) {
            actions.add(new RouteAction(RouteAction.Kind.BREAKER, v, "mine", Float.NaN, Float.NaN));
        }
        for (RouteVec3 v : vecs(step, "interacts")) {
            actions.add(new RouteAction(RouteAction.Kind.INTERACT, v, "interact", Float.NaN, Float.NaN));
        }
        for (RouteVec3 v : vecs(step, "tnts")) {
            actions.add(new RouteAction(RouteAction.Kind.SUPERBOOM, v, "superboom", Float.NaN, Float.NaN));
        }
        List<RouteVec3> pearls = vecs(step, "enderpearls");
        List<RouteVec3> angles = vecs(step, "enderpearlangles");
        for (int i = 0; i < pearls.size(); i++) {
            RouteVec3 angle = i < angles.size() ? angles.get(i) : null;
            actions.add(new RouteAction(RouteAction.Kind.PEARL, pearls.get(i), "pearl",
                    angle == null ? Float.NaN : (float) angle.x,
                    angle == null ? Float.NaN : (float) angle.y));
        }
        for (RouteVec3 v : vecs(step, "bonzo_staffs")) {
            actions.add(new RouteAction(RouteAction.Kind.BONZO, v, "bonzo", Float.NaN, Float.NaN));
        }

        RouteStep.Secret secret = null;
        JsonElement secEl = step.get("secret");
        if (secEl != null && secEl.isJsonObject()) {
            JsonObject sec = secEl.getAsJsonObject();
            String type = sec.has("type") ? sec.get("type").getAsString() : "unknown";
            RouteVec3 loc = vec(sec, "location");
            if (loc != null) secret = new RouteStep.Secret(type, loc);
        }
        return new RouteStep(actions, secret);
    }

    private static String str(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static RouteVec3 vec(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonArray()) return null;
        JsonArray a = el.getAsJsonArray();
        // flat [x,y,z] form (e.g. secret.location)
        if (a.size() == 3 && a.get(0).isJsonPrimitive()) return parseVec(el);
        List<RouteVec3> all = vecs(obj, key);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<RouteVec3> vecs(JsonObject obj, String key) {
        List<RouteVec3> out = new ArrayList<>();
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement pt : el.getAsJsonArray()) {
            RouteVec3 v = parseVec(pt);
            if (v != null) out.add(v);
        }
        return out;
    }

    static RouteVec3 parseVec(JsonElement el) {
        if (!el.isJsonArray()) return null;
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 2) return null;
        try {
            return new RouteVec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(),
                    a.size() >= 3 ? a.get(2).getAsDouble() : 0);
        } catch (Exception e) {
            return null;
        }
    }
}
