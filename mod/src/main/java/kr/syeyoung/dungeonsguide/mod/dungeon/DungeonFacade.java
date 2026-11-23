/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2022  cyoung06 (syeyoung)
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

package kr.syeyoung.dungeonsguide.mod.dungeon;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.PathfindPrecalculationRegistry;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoomInfoRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.FeatureCollectDiagnostics;
import kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation.TSPCacheRegistry;
import lombok.Getter;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public class DungeonFacade {

    @Getter
    private DungeonContext context;

    public void setContext(DungeonContext context) {
        if (this.context != null)
            this.context.cleanup();
        this.context = context;
    }

    public void init() {
        // pfres scanning is independent of room loading, overlap it
        Thread precalcThread = new Thread(() -> {
            try {
                new File(Main.getConfigDir(), "precalculations").mkdirs();
                new PathfindPrecalculationRegistry(new File(Main.getConfigDir(), "precalculations"));
                System.out.println(PathfindPrecalculationRegistry.getINSTANCE().getLoaded().size());
            } catch (IOException e) {
                FeatureCollectDiagnostics.queueSendLogAsync(e);
                e.printStackTrace();
            }
        }, "DG-PrecalcInit");
        precalcThread.setDaemon(true);
        precalcThread.start();

        try {
            new File(Main.getConfigDir(), "roomdatas").mkdirs();
            DungeonRoomInfoRegistry.loadAll(new File(Main.getConfigDir(), "roomdatas"));
        } catch (BadPaddingException | InvalidKeyException | NoSuchPaddingException | IllegalBlockSizeException |
                 IOException | NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            FeatureCollectDiagnostics.queueSendLogAsync(e);
            e.printStackTrace();
        }

        // presets resolve precalculations and the tsp cache iterates presets, so both go after the scan
        try {
            precalcThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            new File(Main.getConfigDir(), "presets").mkdirs();
            new PathfindPresetRegistry(new File(Main.getConfigDir(), "presets"));
            new File(Main.getConfigDir(), "tspCache").mkdirs();
            new TSPCacheRegistry(new File(Main.getConfigDir(), "tspCache"));
        } finally {
            // a crash anywhere above - before or inside registry construction - must
            // still release workers parked on the init latch, or every route solve
            // waits out the full cache-wait budget on a registry that will never exist
            TSPCacheRegistry.markInitComplete();
        }
    }
}
