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

import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.launcher.Main;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers and parses community route files once, asynchronously.
 *
 * Search roots (AD10 — nothing is bundled, data stays in the user's configs):
 *   <configDir>/SecretRoutes/routes/*.json        (SRM)
 *   <configDir>/odin/dungeon-waypoints/*.json     (Odin)
 *   <configDir>/dungeonsguide/routes/*.json       (user drop-in; SRM or Odin format, sniffed)
 *
 * Known source ids for the selector:
 *   srm:fowroutes, srm:3ppopkaroutes, odin:<file>, dg:<file>
 */
public final class CommunityRouteLoader {
    private static final Logger logger = LogManager.getLogger("DG-CommunityRoutes");
    private static final CommunityRouteLoader INSTANCE = new CommunityRouteLoader();

    public static CommunityRouteLoader get() {
        return INSTANCE;
    }

    private final List<RoutePack> packs = new CopyOnWriteArrayList<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private ExecutorService executor;

    private CommunityRouteLoader() {
    }

    /** parsed packs, empty until the async load finishes */
    public List<RoutePack> packs() {
        return Collections.unmodifiableList(packs);
    }

    public boolean isLoaded() {
        return loaded.get();
    }

    public RoutePack findPack(String id) {
        for (RoutePack p : packs) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** kick off a one-shot async scan+parse; safe to call every tick */
    public void ensureLoaded() {
        if (loaded.get() || !loading.compareAndSet(false, true)) return;
        if (executor == null) {
            executor = DungeonsGuide.getDungeonsGuide().registerExecutorService(
                    Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "DG-CommunityRoutes");
                        t.setDaemon(true);
                        return t;
                    }));
        }
        executor.submit(this::loadAll);
    }

    private void loadAll() {
        List<RoutePack> found = new ArrayList<>();
        File configDir = Main.getConfigDir() == null ? null : Main.getConfigDir().getParentFile();
        if (configDir != null) {
            File srmDir = new File(configDir, "SecretRoutes" + File.separator + "routes");
            scanDir(srmDir, "srm", found);
            File odinDir = new File(configDir, "odin" + File.separator + "dungeon-waypoints");
            scanDir(odinDir, "odin", found);
        }
        if (Main.getConfigDir() != null) {
            File custom = new File(Main.getConfigDir(), "routes");
            scanDir(custom, "dg", found);
        }
        packs.addAll(found);
        loaded.set(true);
    }

    private void scanDir(File dir, String prefix, List<RoutePack> out) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            String base = f.getName().substring(0, f.getName().length() - ".json".length());
            try {
                RoutePack pack = parseFile(prefix + ":" + base, f, "dg".equals(prefix));
                if (pack != null && pack.roomCount() > 0) out.add(pack);
            } catch (Exception e) {
                logger.warn("failed to parse community route file {}", f, e);
            }
        }
    }

    private RoutePack parseFile(String id, File f, boolean sniff) throws IOException {
        try (Reader r = new FileReader(f)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            String content = sb.toString();
            RoutePack.Format fmt = sniff ? sniffFormat(content)
                    : (id.startsWith("odin:") ? RoutePack.Format.ODIN : RoutePack.Format.SRM);
            try (Reader r2 = new java.io.StringReader(content)) {
                if (fmt == RoutePack.Format.ODIN) {
                    return new OdinWaypointParser().parse(id, f.getName(), r2);
                }
                return new SrmRouteParser().parse(id, f.getName(), r2);
            }
        }
    }

    /** SRM files have array-valued room steps with "secret"/"locations"; Odin files have "blockPos" objects */
    static RoutePack.Format sniffFormat(String content) {
        return content.contains("\"blockPos\"") ? RoutePack.Format.ODIN : RoutePack.Format.SRM;
    }
}
