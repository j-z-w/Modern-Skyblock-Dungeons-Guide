package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parser for Odin "dungeon-waypoints" packs (e.g. Imported.json).
 *
 * Format (observed):
 *   { "Room Name": [ { "blockPos": {"x":5,"y":63,"z":23}, "color": "#FF5555FF",
 *     "filled": false, "depth": false, "aabb": {minX..maxZ}, "title": "1 item",
 *     "type": "SECRET"|"NORMAL"|"ETHERWARP"|missing }, ... ], ... }
 *
 * Odin waypoints have no explicit step structure. A leading integer in the
 * title ("1 item", "2 down") is treated as authored route order: waypoints
 * sharing a number form one step; unnumbered waypoints become ambient
 * markers rendered for the whole room. Waypoint kind is inferred from the
 * type field plus title keywords (boom/mine/warp/pearl/item/bat/...).
 * Coordinates are room-relative to Odin's clay-corner frame (canonical
 * orientation = room facing NORTH), y absolute.
 */
public final class OdinWaypointParser {

    private static class Wp {
        RouteVec3 pos;
        String title;
        String type;
        String color;
        boolean filled;
        boolean depth;
        double minX, minY, minZ, maxX, maxY, maxZ;
    }

    public RoutePack parse(String id, String fileName, Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        RoutePack pack = new RoutePack(id, RoutePack.Format.ODIN, fileName,
                "Odin waypoint pack", "Odin project (BSD-3-Clause), community waypoint authors");

        for (Map.Entry<String, JsonElement> room : root.entrySet()) {
            if (!room.getValue().isJsonArray()) continue;
            List<Wp> wps = new ArrayList<>();
            for (JsonElement el : room.getValue().getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                Wp wp = parse(el.getAsJsonObject());
                if (wp != null) wps.add(wp);
            }
            if (wps.isEmpty()) continue;

            Map<Integer, List<Wp>> numbered = new TreeMap<>();
            List<Wp> ambient = new ArrayList<>();
            for (Wp wp : wps) {
                Integer order = leadingInt(wp.title);
                if (order == null) ambient.add(wp);
                else numbered.computeIfAbsent(order, k -> new ArrayList<>()).add(wp);
            }

            List<RouteStep> steps = new ArrayList<>();
            for (Map.Entry<Integer, List<Wp>> e : numbered.entrySet()) {
                steps.add(toStep(e.getValue(), e.getKey()));
            }
            for (Wp wp : ambient) {
                // one ambient step per marker so secret-like markers (unnumbered
                // "bat"/"chest"/"item" titles) still contribute a fingerprint secret
                RouteStep.Secret secret = isSecretLike(wp)
                        ? new RouteStep.Secret(wp.title == null ? "secret" : wp.title, wp.pos) : null;
                steps.add(new RouteStep(List.of(toAction(wp, classify(wp))), secret, true));
            }

            pack.addRoute(new CommunityRoute(room.getKey(), 0, steps));
        }
        return pack;
    }

    private RouteStep toStep(List<Wp> wps, int order) {
        List<RouteAction> actions = new ArrayList<>();
        RouteStep.Secret secret = null;
        for (Wp wp : wps) {
            RouteAction.Kind kind = classify(wp);
            actions.add(toAction(wp, kind));
            if (secret == null && kind == RouteAction.Kind.MARKER && isSecretLike(wp)) {
                secret = new RouteStep.Secret(wp.title == null ? "secret" : wp.title, wp.pos);
            }
        }
        return new RouteStep(actions, secret);
    }

    private RouteAction toAction(Wp wp, RouteAction.Kind kind) {
        return new RouteAction(kind, wp.pos,
                wp.title == null ? kind.name().toLowerCase() : wp.title,
                Float.NaN, Float.NaN);
    }

    static RouteAction.Kind classify(Wp wp) {
        String type = wp.type == null ? "" : wp.type.toUpperCase();
        if (type.equals("ETHERWARP")) return RouteAction.Kind.ETHERWARP;
        if (type.equals("SUPERBOOM") || type.equals("TNT")) return RouteAction.Kind.SUPERBOOM;
        if (type.equals("SECRET")) return RouteAction.Kind.MARKER; // secret-like -> becomes step.secret

        String t = wp.title == null ? "" : wp.title.toLowerCase();
        if (contains(t, "boom", "tnt")) return RouteAction.Kind.SUPERBOOM;
        if (contains(t, "mine", "break", "stonk", "breaker")) return RouteAction.Kind.BREAKER;
        if (contains(t, "warp", "ether")) return RouteAction.Kind.ETHERWARP;
        if (contains(t, "pearl")) return RouteAction.Kind.PEARL;
        if (contains(t, "bonzo")) return RouteAction.Kind.BONZO;
        if (isSecretLike(wp)) return RouteAction.Kind.MARKER;
        if (contains(t, "lever", "interact", "click")) return RouteAction.Kind.INTERACT;
        return RouteAction.Kind.MARKER;
    }

    private static boolean isSecretLike(Wp wp) {
        String type = wp.type == null ? "" : wp.type.toUpperCase();
        if (type.equals("SECRET")) return true;
        String t = wp.title == null ? "" : wp.title.toLowerCase();
        return contains(t, "item", "bat", "chest", "essence", "secret");
    }

    private static boolean contains(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private static Integer leadingInt(String title) {
        if (title == null) return null;
        int i = 0;
        while (i < title.length() && Character.isDigit(title.charAt(i))) i++;
        if (i == 0) return null;
        try {
            return Integer.parseInt(title.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Wp parse(JsonObject o) {
        JsonObject bp = o.has("blockPos") && o.get("blockPos").isJsonObject()
                ? o.getAsJsonObject("blockPos") : null;
        if (bp == null) return null;
        Wp wp = new Wp();
        wp.pos = new RouteVec3(dbl(bp, "x"), dbl(bp, "y"), dbl(bp, "z"));
        wp.title = o.has("title") && o.get("title").isJsonPrimitive() ? o.get("title").getAsString() : null;
        wp.type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : null;
        wp.color = o.has("color") && o.get("color").isJsonPrimitive() ? o.get("color").getAsString() : null;
        wp.filled = o.has("filled") && o.get("filled").isJsonPrimitive() && o.get("filled").getAsBoolean();
        wp.depth = !o.has("depth") || !o.get("depth").isJsonPrimitive() || o.get("depth").getAsBoolean();
        if (o.has("aabb") && o.get("aabb").isJsonObject()) {
            JsonObject bb = o.getAsJsonObject("aabb");
            wp.minX = dbl(bb, "minX"); wp.minY = dbl(bb, "minY"); wp.minZ = dbl(bb, "minZ");
            wp.maxX = dbl(bb, "maxX"); wp.maxY = dbl(bb, "maxY"); wp.maxZ = dbl(bb, "maxZ");
        } else {
            wp.maxX = wp.maxY = wp.maxZ = 1;
        }
        return wp;
    }

    private static double dbl(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
    }
}
