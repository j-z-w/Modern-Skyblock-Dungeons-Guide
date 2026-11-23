package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import com.google.common.collect.Sets;
import com.sun.nio.file.ExtendedWatchEventModifier;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSettingRegistry;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class PathfindPrecalculationRegistry {
    // writes are rare (loadAll / WatchService register/unregister) while readers run
    // every tick: copy-on-write keeps iteration safe without synchronizing readers.
    // register/unregister are still synchronized so multi-map updates stay atomic
    @Getter
    private List<PathfindPrecalculation> loaded = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Map<String, List<PathfindPrecalculation>> byId = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, PathfindPrecalculation> byFile = new java.util.concurrent.ConcurrentHashMap<>();
    @Getter
    private Map<UUID, List<PathfindPrecalculation>> byRoom = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, List<PathfindPrecalculation>> byHash = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, List<PathfindPrecalculation>> byId2 = new java.util.concurrent.ConcurrentHashMap<>();

    @Getter
    private static volatile PathfindPrecalculationRegistry INSTANCE;

    public PathfindPrecalculationRegistry(File dir) throws IOException {
        this(dir, true);
    }

    // watch=false for tests: skips the filesystem WatchService thread, loadAll still runs
    PathfindPrecalculationRegistry(File dir, boolean watch) throws IOException {
        if (INSTANCE != null) throw new IllegalStateException("Already initialized");
        PathfindPrecalculationRegistry.INSTANCE = this;

        loadAll(dir);

        if (!watch) return;
        new Thread(DungeonsGuide.THREAD_GROUP, () -> {
            try {
                FileSystem fs = FileSystems.getDefault();
                WatchService ws = fs.newWatchService();

                Path pTemp = dir.toPath();
                WatchEvent.Kind[] kinds = new WatchEvent.Kind[] {StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY};
                Files.walkFileTree(pTemp, new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (Files.isDirectory(dir))
                            dir.register(ws, kinds);
                        return FileVisitResult.CONTINUE;
                    }
                });

                pTemp.register(ws, kinds);

                while(!Thread.interrupted())
                {
                    WatchKey k;
                    try {
                        k = ws.take();
                    } catch (InterruptedException e) {
                        break;
                    }
                    try {
                        for (WatchEvent<?> e : k.pollEvents()) {
                            if (e.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                            // the event only carries the name, relative to the directory the key watches
                            Path c = ((Path) k.watchable()).resolve((Path) e.context());
                            // one bad event (a .pfres still being written) must not drop the rest of the batch
                            try {
                                if (e.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(c)) {
                                    c.register(ws, kinds);
                                    Files.walkFileTree(c, new SimpleFileVisitor<Path>() {
                                        @Override
                                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                            if (file.getFileName().toString().endsWith(".pfres"))
                                                register(new PathfindPrecalculation(file.toFile()));
                                            return FileVisitResult.CONTINUE;
                                        }
                                    });
                                    continue;
                                }

                                if (!c.getFileName().toString().endsWith(".pfres")) continue;

                                if (e.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                                    register(new PathfindPrecalculation(c.toFile()));
                                } else if (e.kind()== StandardWatchEventKinds.ENTRY_DELETE) {
                                    PathfindPrecalculation precalculation = getByFile(c.toFile().getAbsolutePath());
                                    if (precalculation != null) unregister(precalculation);
                                } else if (e.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                                    PathfindPrecalculation precalculation = getByFile(c.toFile().getAbsolutePath());
                                    if (precalculation != null) unregister(precalculation);
                                    register(new PathfindPrecalculation(c.toFile()));
                                }
                                System.out.printf("%s %d %s\n", e.kind(), e.count(), c);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        // a key that isn't reset never reports again
                        k.reset();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }).start();
    }

    public synchronized void register(PathfindPrecalculation precalculation) {
        byId.computeIfAbsent(precalculation.getId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(precalculation);
        byFile.put(precalculation.getFile(), precalculation);
        byRoom.computeIfAbsent(precalculation.getRoomUID(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(precalculation);
        byHash.computeIfAbsent(precalculation.getTargetHash(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(precalculation);
        byId2.computeIfAbsent(precalculation.getTargetId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(precalculation);
        loaded.add(precalculation);


        AlgorithmSettingRegistry.registerAlgorithmSetting(precalculation.getAlgorithmSetting());
    }

    public PathfindPrecalculation getById(String id) {
        List<PathfindPrecalculation> list  =getsById(id);
        return list.isEmpty() ? null : list.get(0);
    }
    public List<PathfindPrecalculation> getsById(String id) {
        List<PathfindPrecalculation> list = byId.get(id);
        if (list == null) return Collections.emptyList();
        return list;
    }
    public PathfindPrecalculation getByTargetId(String targetid) {
        List<PathfindPrecalculation> list  =getsByTargetId(targetid);
        return list.isEmpty() ? null : list.get(0);
    }
    public List<PathfindPrecalculation> getsByTargetId(String targetid) {
        List<PathfindPrecalculation> list = byId2.get(targetid);
        if (list == null) return Collections.emptyList();
        return list;
    }
    public List<PathfindPrecalculation> getsByHash(String hash) {
        List<PathfindPrecalculation> list = byHash.get(hash);
        if (list == null) return Collections.emptyList();
        return list;
    }

    public List<PathfindPrecalculation> getByRoom(UUID roomUID) {
        return byRoom.getOrDefault(roomUID, Collections.emptyList());
    }

    public PathfindPrecalculation getByFile(String file) {
        return byFile.get(file);
    }

    public synchronized void unregister(PathfindPrecalculation precalculation) {
        // a deleted/replaced .pfres must not keep its node buffer pinned in the LRU
        precalculation.evictBuffer();
        List<PathfindPrecalculation> ids = byId.get(precalculation.getId());
        if (ids != null) {
            ids.remove(precalculation);
            if (ids.isEmpty()) byId.remove(precalculation.getId(), ids);
        }

        byFile.remove(precalculation.getFile());
        List<PathfindPrecalculation> rooms = byRoom.get(precalculation.getRoomUID());
        if (rooms != null) rooms.remove(precalculation);
        List<PathfindPrecalculation> hashes = byHash.get(precalculation.getTargetHash());
        if (hashes != null) hashes.remove(precalculation);
        List<PathfindPrecalculation> targets = byId2.get(precalculation.getTargetId());
        if (targets != null) targets.remove(precalculation);
        loaded.remove(precalculation);
    }


    public void loadAll(File dir) throws IOException {
        byId.clear();
        byHash.clear();
        byRoom.clear();
        byId2.clear();
        byFile.clear();

        try {
            Files.walk(dir.toPath(), FileVisitOption.FOLLOW_LINKS)
                    .forEach(path -> {
                        if (!path.getFileName().toString().endsWith(".pfres")) return;
                        try {
                            PathfindPrecalculation pathfindCache = new PathfindPrecalculation(path.toFile());
                            register(pathfindCache);
                        } catch (Exception e) {
                            System.out.println(path.getFileName());
                            e.printStackTrace();
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



}
