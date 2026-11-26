/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2023  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.features.impl.etc;


import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonObject;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.config.types.TCBoolean;
import kr.syeyoung.dungeonsguide.mod.features.FeatureParameter;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.SimpleFeature;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class FeatureCollectDiagnostics extends SimpleFeature {
    private final Map<String, Long> lastSent = new HashMap<>();
    public FeatureCollectDiagnostics() {
        super("Misc", "Collect Error Logs", "Enable to log mod errors to the client log", "misc.diagnostics_logcollection", true);
        addParameter("prompted", new FeatureParameter<Boolean>("prompted", "Was this prompted?", "Did this feature prompt for user apporval yet?", true, TCBoolean.INSTANCE));
    }

    @Override
    public void loadConfig(JsonObject jsonObject) {
        super.loadConfig(jsonObject);
    }

    @Override
    public void init() {
        // no approval prompt in this build: diagnostics never leave the client
    }

    public static final Executor executorService = Executors
            .newSingleThreadExecutor(new ThreadFactoryBuilder()
                    .setThreadFactory(DungeonsGuide.THREAD_FACTORY)
                    .setNameFormat("DG-Error-Reporter-%d").build());

    public static void queueSendLogAsync(Throwable t) {
        executorService.execute(() -> {
            try {
                FeatureRegistry.COLLECT_ERRORS.sendLogActually(t);
            } catch (Exception ignored) {ignored.printStackTrace();}
        });
    }

    private void sendLogActually(Throwable t) {
        if (!isEnabled()) return;

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        t.printStackTrace(pw);

        String trace = sw.toString();

        if (lastSent.getOrDefault(trace, 0L) + 60*1000 > System.currentTimeMillis()) { // don't send same thing for 1m
            return;
        }
        lastSent.put(trace, System.currentTimeMillis());

        // the dungeons.guide backend is dead; log locally instead of uploading
        System.err.println("[Dungeons Guide] Diagnostics (uncaught exception):");
        t.printStackTrace();
    }


}
