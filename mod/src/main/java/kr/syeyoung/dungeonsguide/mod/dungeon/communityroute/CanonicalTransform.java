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
 * Maps source route coordinates (SRM/Odin room-relative, clay-corner frame)
 * into DG's canonical room frame (the frame OffsetPoint uses: rotation applied
 * at runtime by RoomMatcher).
 *
 * The transform is canonical = rot_r(x,z) + T, where rot_r is r 90-degree
 * clockwise turns about the room anchor corner and T is a constant offset.
 * r and T are FITTED per room from secret-position fingerprints
 * (RouteAlignmentTest measures how well this works); a global fallback is
 * used when a room has no usable fingerprint.
 */
public final class CanonicalTransform {
    public final int rotation;
    public final double tx;
    public final double ty;
    public final double tz;
    /** fraction of source secrets that landed within tolerance of a DG secret under this transform */
    public final double matchRate;
    /** number of source secrets the fit was scored against (0 = no fingerprint) */
    public final int totalSecrets;

    public CanonicalTransform(int rotation, double tx, double ty, double tz, double matchRate) {
        this(rotation, tx, ty, tz, matchRate, 0);
    }

    public CanonicalTransform(int rotation, double tx, double ty, double tz, double matchRate, int totalSecrets) {
        this.rotation = rotation;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.matchRate = matchRate;
        this.totalSecrets = totalSecrets;
    }

    public RouteVec3 apply(RouteVec3 src) {
        double[] xz = rot(rotation, src.x, src.z);
        return new RouteVec3(xz[0] + tx, src.y + ty, xz[1] + tz);
    }

    /** r quarter-turns clockwise (positive y-axis up, matching VectorUtils.rotateClockwise semantics) */
    public static double[] rot(int r, double x, double z) {
        switch (((r % 4) + 4) % 4) {
            case 0: return new double[]{x, z};
            case 1: return new double[]{-z, x};
            case 2: return new double[]{-x, -z};
            default: return new double[]{z, -x};
        }
    }

    public static final double TOLERANCE = 1.75;
    /**
     * Bat secrets get a wide tolerance: SRM records where the bat was killed
     * mid-flight while DG records the spawn point, so they legitimately
     * differ by several blocks.
     */
    public static final double BAT_TOLERANCE = 9.0;

    private static double toleranceFor(String secretType) {
        return secretType != null && secretType.toLowerCase(java.util.Locale.ROOT).contains("bat")
                ? BAT_TOLERANCE : TOLERANCE;
    }

    /**
     * Fits the best (rotation, T) so that as many srcSecrets as possible land
     * within their type's tolerance of a dgCanonicalSecret.
     * Returns a transform with matchRate = matched/srcSecrets.size(), or a
     * transform with matchRate 0 when either side is empty.
     */
    public static CanonicalTransform fit(List<RouteVec3> dgCanonicalSecrets, List<RouteStep.Secret> srcSecrets) {
        if (dgCanonicalSecrets == null || dgCanonicalSecrets.isEmpty()
                || srcSecrets == null || srcSecrets.isEmpty()) {
            return new CanonicalTransform(0, 0, 0, 0, 0, 0);
        }
        double bestScore = -1;
        CanonicalTransform best = new CanonicalTransform(0, 0, 0, 0, 0, srcSecrets.size());
        double tol2 = TOLERANCE * TOLERANCE;
        double batTol2 = BAT_TOLERANCE * BAT_TOLERANCE;

        for (int r = 0; r < 4; r++) {
            for (RouteStep.Secret src : srcSecrets) {
                double[] rxz = rot(r, src.pos.x, src.pos.z);
                for (RouteVec3 dg : dgCanonicalSecrets) {
                    double tx = dg.x - rxz[0];
                    double tz = dg.z - rxz[1];
                    double ty = dg.y - src.pos.y;
                    int matched = 0;
                    for (RouteStep.Secret s2 : srcSecrets) {
                        double[] r2 = rot(r, s2.pos.x, s2.pos.z);
                        double px = r2[0] + tx, py = s2.pos.y + ty, pz = r2[1] + tz;
                        double lim = toleranceFor(s2.type);
                        lim *= lim;
                        for (RouteVec3 d : dgCanonicalSecrets) {
                            double dx = px - d.x, dy = py - d.y, dz = pz - d.z;
                            if (dx * dx + dy * dy + dz * dz <= lim) {
                                matched++;
                                break;
                            }
                        }
                    }
                    double score = matched / (double) srcSecrets.size();
                    if (score > bestScore) {
                        bestScore = score;
                        best = new CanonicalTransform(r, tx, ty, tz, score, srcSecrets.size());
                    }
                }
            }
        }
        return best;
    }

    /** positional overload for callers with plain positions */
    public static CanonicalTransform fitPositions(List<RouteVec3> dgCanonicalSecrets, List<RouteVec3> srcSecrets) {
        List<RouteStep.Secret> secrets = new java.util.ArrayList<>();
        if (srcSecrets != null) {
            for (RouteVec3 v : srcSecrets) secrets.add(new RouteStep.Secret("?", v));
        }
        return fit(dgCanonicalSecrets, secrets);
    }

    /**
     * Global fallback used when a room has no secret fingerprint to fit.
     * rotation/tx/tz are the modal values found by RouteAlignmentTest across
     * all aligned rooms; ty must be supplied by the caller because it encodes
     * the absolute room height (ty = -roomBoundsMinY).
     */
    public static CanonicalTransform fallback(double boundsMinY) {
        return new CanonicalTransform(FALLBACK_ROTATION, FALLBACK_TX, -boundsMinY, FALLBACK_TZ, 0);
    }

    public static CanonicalTransform fallback(double boundsMinY, int totalSecrets) {
        return new CanonicalTransform(FALLBACK_ROTATION, FALLBACK_TX, -boundsMinY, FALLBACK_TZ, 0, totalSecrets);
    }

    // Modal values measured by RouteAlignmentTest over all aligned rooms and
    // packs (61/298 aligned rooms): r=0, T=(1,1). The SRM/Odin clay-corner
    // anchor sits 1 block inside DG's bounds-min on both axes; DG's canonical
    // frame needs no rotation.
    public static int FALLBACK_ROTATION = 0;
    public static double FALLBACK_TX = 1;
    public static double FALLBACK_TZ = 1;
}
