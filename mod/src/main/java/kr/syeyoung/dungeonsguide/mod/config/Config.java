/*
 * Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 * Copyright (C) 2021  cyoung06
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

package kr.syeyoung.dungeonsguide.mod.config;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import kr.syeyoung.dungeonsguide.mod.features.AbstractFeature;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.FeatureCollectDiagnostics;
import kr.syeyoung.dungeonsguide.mod.features.impl.secret.triggers.RouteModes;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class Config {
    public static JsonObject configuration;

    public static File f;

    private static final Executor configSaver = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
            .setThreadFactory(DungeonsGuide.THREAD_FACTORY)
            .setNameFormat("DG-ConfigSaver-%d").build());


    public static synchronized void loadConfig(File f) throws IOException {
        Config.f = f == null ? Config.f : f;
        try (Reader reader = new InputStreamReader(new FileInputStream(Config.f))) {
            configuration = (JsonObject) new JsonParser().parse(reader);
        } catch (FileNotFoundException e) {
            configuration = new JsonObject();
        } catch (Exception e) {
            // an unreadable config is kept for recovery instead of being overwritten with defaults below
            File backup = new File(Config.f.getParentFile(), Config.f.getName() + ".corrupt-" + System.currentTimeMillis());
            if (Config.f.renameTo(backup))
                System.err.println("Dungeons Guide: config could not be read, saved a copy as " + backup.getName());
            e.printStackTrace();
            configuration = new JsonObject();
        }
        if (configuration == null) configuration = new JsonObject();
        for (AbstractFeature feature : FeatureRegistry.getFeatureList()) {
            JsonObject object = configuration.getAsJsonObject(feature.getKey());
            if (object != null) feature.loadConfig(object);
        }
        // the route display modes are mutually exclusive; a config that enables several at once
        // resolves to Smart Route (see RouteModes)
        RouteModes.reconcileLoadedModes();

        saveConfig();
    }

    public static void scheduleConfigSave() {
        configSaver.execute(() -> {
            try {
                Config.saveConfig();
            } catch (IOException e) {
                FeatureCollectDiagnostics.queueSendLogAsync(e);
                e.printStackTrace();
            }
        });
    }

    // synchronized with loadConfig: the async saver and a direct save must never share the .tmp file
    public static synchronized void saveConfig() throws IOException {
        for (AbstractFeature feature : FeatureRegistry.getFeatureList()) {
            JsonObject object = feature.saveConfig();
            configuration.add(feature.getKey(), object);
        }

        String str = new Gson().toJson(configuration);
        // write a sibling temp file and swap it in, so an interrupted save never truncates the live config
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp);
                 BufferedWriter bos = new BufferedWriter(new OutputStreamWriter(fos))) {
                bos.write(str);
                bos.flush();
                fos.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp.toPath());
            throw e;
        }


        PathfindPresetRegistry.getINSTANCE().saveAll();
    }
}
