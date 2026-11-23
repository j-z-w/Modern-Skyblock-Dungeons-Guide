package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One parsed route file: provenance + room-keyed routes.
 * No community data is bundled in the jar; packs are parsed at runtime from
 * the user's own installed mod config files (AD10).
 */
public final class RoutePack {
    public enum Format { SRM, ODIN, DG }

    /** stable id: "srm:fowroutes", "odin:Imported", "dg:custom/<file>" */
    public final String id;
    public final Format format;
    /** file name relative to its routes dir, for diagnostics */
    public final String fileName;
    /** provenance fields preserved from the file (SRM #origin / #copyright) */
    public final String origin;
    public final String copyright;

    private final Map<String, List<CommunityRoute>> byRoom = new LinkedHashMap<>();

    public RoutePack(String id, Format format, String fileName, String origin, String copyright) {
        this.id = id;
        this.format = format;
        this.fileName = fileName;
        this.origin = origin;
        this.copyright = copyright;
    }

    public void addRoute(CommunityRoute route) {
        byRoom.computeIfAbsent(route.roomKey, k -> new ArrayList<>()).add(route);
    }

    /** variants sorted by variant index; base (no ':n') is variant 0 */
    public List<CommunityRoute> routesFor(String roomKey) {
        List<CommunityRoute> routes = byRoom.get(roomKey);
        if (routes == null || routes.size() < 2) return routes;
        List<CommunityRoute> sorted = new ArrayList<>(routes);
        sorted.sort((a, b) -> Integer.compare(a.variant, b.variant));
        return sorted;
    }

    public Map<String, List<CommunityRoute>> allRooms() {
        return byRoom;
    }

    public int roomCount() {
        return byRoom.size();
    }
}
