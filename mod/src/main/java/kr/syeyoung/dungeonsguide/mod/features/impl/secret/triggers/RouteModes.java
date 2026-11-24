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

package kr.syeyoung.dungeonsguide.mod.features.impl.secret.triggers;

import kr.syeyoung.dungeonsguide.mod.features.AbstractFeature;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The three route display modes (Display All / Display One / Smart Route) are mutually exclusive:
 * each one draws its own route(s) for the same room, so running more than one at a time renders
 * overlapping paths. The removed first-run onboarding wizard used to pick exactly one of them,
 * so exclusivity now lives in the feature layer:
 *  - {@link #onModeEnabled(AbstractFeature)} is called from each mode's {@code setEnabled} so
 *    enabling one in the config GUI turns the other two off.
 *  - {@link #reconcileLoadedModes()} runs after config load so a config enabling several modes at
 *    once (e.g. carried over from a build where the wizard or other defaults set them all)
 *    resolves to Smart Route only, which was the wizard's recommended pick.
 */
public final class RouteModes {
    private RouteModes() {}

    public static final String ALL_BROWSE_KEY = "secret.secretpathfind.allbrowse";
    public static final String AUTO_UPON_ENTER_KEY = "secret.autouponenter";
    public static final String SMART_ROUTE_KEY = "secret.smartroute";

    public static final List<String> KEYS = Arrays.asList(ALL_BROWSE_KEY, AUTO_UPON_ENTER_KEY, SMART_ROUTE_KEY);

    public static void onModeEnabled(AbstractFeature feature) {
        enforceExclusive(registeredModes(), feature);
    }

    public static void reconcileLoadedModes() {
        reconcile(registeredModes(), FeatureRegistry.getFeatureByKey(SMART_ROUTE_KEY));
    }

    private static List<AbstractFeature> registeredModes() {
        List<AbstractFeature> modes = new ArrayList<>(KEYS.size());
        for (String key : KEYS) {
            AbstractFeature feature = FeatureRegistry.getFeatureByKey(key);
            if (feature != null) modes.add(feature);
        }
        return modes;
    }

    static void enforceExclusive(List<? extends AbstractFeature> modes, AbstractFeature enabled) {
        for (AbstractFeature mode : modes) {
            if (mode != enabled) mode.setEnabled(false);
        }
    }

    static void reconcile(List<? extends AbstractFeature> modes, AbstractFeature preferred) {
        if (preferred == null) return;
        int enabledCount = 0;
        for (AbstractFeature mode : modes) {
            if (mode.isEnabled()) enabledCount++;
        }
        if (enabledCount <= 1) return;
        for (AbstractFeature mode : modes) {
            mode.setEnabled(mode == preferred);
        }
    }
}
