package kr.syeyoung.dungeonsguide.mod.dungeon.communityroute;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps DG roomdata rooms onto community-route room keys and derives the
 * canonical coordinate transform for the room.
 *
 * Step 1: exact / normalized name match (lowercase, alphanumerics only, with
 * the SRM "-<secretCount>" suffix dropped).
 * Step 2: secret-position fingerprint — try every unmatched route room, fit
 * a CanonicalTransform on the secrets, and accept the best one that aligns at
 * least FINGERPRINT_MIN of the route's secrets.
 * The transform itself is always fitted per-room when both sides have
 * secrets; a global fallback (RouteAlignmentTest's modal values) covers
 * rooms with no usable fingerprint.
 */
public final class RouteRoomMapper {
    /** minimum fraction of route secrets that must align for a fingerprint match */
    public static final double FINGERPRINT_MIN = 0.8;
    /** minimum secrets for a fingerprint match to be attempted */
    public static final int MIN_FINGERPRINT_SECRETS = 3;

    public static String normalize(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        String s = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        // SRM keys end with -<secretCount> e.g. Grand-Library-4
        int len = sb.length();
        int digits = 0;
        while (digits < len && Character.isDigit(sb.charAt(len - 1 - digits))) digits++;
        if (digits > 0 && digits < len) sb.setLength(len - digits);
        return sb.toString();
    }

    /** result of resolving a DG room against a route pack */
    public static final class Resolution {
        public final String routeKey;      // null when unmatched
        public final String how;           // "name" | "fingerprint" | "unmatched"
        public final CanonicalTransform transform;
        public final double matchRate;

        Resolution(String routeKey, String how, CanonicalTransform transform) {
            this.routeKey = routeKey;
            this.how = how;
            this.transform = transform;
            this.matchRate = transform == null ? 0 : transform.matchRate;
        }

        public boolean matched() {
            return routeKey != null;
        }
    }

    /**
     * Resolve one DG room against one pack.
     * boundsMinY is the room bounds' min Y in the live world (used only for
     * the fallback transform's y component; test code may pass the fitted
     * value instead).
     */
    public static Resolution resolve(DungeonRoomInfo info, RoutePack pack, double boundsMinY) {
        List<RouteVec3> dgSecrets = SecretFingerprint.extract(info);

        String nameKey = matchByName(info, pack);
        if (nameKey != null) {
            List<RouteStep.Secret> srcSecrets = packSecrets(pack, nameKey);
            CanonicalTransform t = CanonicalTransform.fit(dgSecrets, srcSecrets);
            if (t.matchRate == 0) {
                // no fingerprint to fit on (or none aligned): use the global
                // fallback orientation, trusting the name match
                t = CanonicalTransform.fallback(boundsMinY, srcSecrets.size());
            }
            return new Resolution(nameKey, "name", t);
        }

        if (dgSecrets.size() >= MIN_FINGERPRINT_SECRETS) {
            String bestKey = null;
            CanonicalTransform best = null;
            for (String key : pack.allRooms().keySet()) {
                List<RouteStep.Secret> srcSecrets = packSecrets(pack, key);
                if (srcSecrets.size() < MIN_FINGERPRINT_SECRETS) continue;
                CanonicalTransform t = CanonicalTransform.fit(dgSecrets, srcSecrets);
                if (best == null || t.matchRate > best.matchRate) {
                    best = t;
                    bestKey = key;
                }
            }
            if (best != null && best.matchRate >= FINGERPRINT_MIN) {
                return new Resolution(bestKey, "fingerprint", best);
            }
        }
        return new Resolution(null, "unmatched", null);
    }

    private static List<RouteStep.Secret> packSecrets(RoutePack pack, String roomKey) {
        List<RouteStep.Secret> out = new ArrayList<>();
        List<CommunityRoute> routes = pack.routesFor(roomKey);
        if (routes == null) return out;
        for (CommunityRoute route : routes) route.collectMatchableSecrets(out);
        return out;
    }

    /** returns matched route key or null */
    public static String matchByName(DungeonRoomInfo info, RoutePack pack) {
        String want = normalize(info.getName());
        if (want.isEmpty()) return null;
        // SRM keys are "Name-N" where N is the room's secret count — prefer the
        // key whose count matches DG's totalSecrets to disambiguate same-name rooms
        int secrets = info.getTotalSecrets();
        for (String key : pack.allRooms().keySet()) {
            if (normalize(key).equals(want) && key.endsWith("-" + secrets)) return key;
        }
        for (String key : pack.allRooms().keySet()) {
            if (normalize(key).equals(want)) return key;
        }
        return null;
    }
}
