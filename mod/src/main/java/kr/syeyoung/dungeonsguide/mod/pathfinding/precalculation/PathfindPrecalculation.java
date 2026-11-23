package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import lombok.Data;
import lombok.Getter;
import org.apache.commons.io.input.CountingInputStream;

import kr.syeyoung.dungeonsguide.mod.debug.SlowTaskTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.InflaterInputStream;

@Data
public class PathfindPrecalculation {
    private int version;
    private String id;
    private String file;

    private String generatedFrom;

    private AlgorithmSetting algorithmSetting;

    private String targetHash;
    private String targetId;
    private UUID roomUID;
    private String roomState;
    private List<OffsetVec3> targetLocations = new ArrayList<>();

    private int start;
    private boolean compressed;


    public PathfindPrecalculation(File f) throws IOException {
        parsePathfindV2Header(f);
        this.file = f.getAbsolutePath();
    }

    // file structure

    // R2DGPF
    // int version
    // utf ID
    // utf HASH
    // utf ROOM UID
    // utf ROOM STATE
    // utf generated From

    // ALGO
    // algorithm settings

    // TRGT
    // list TARGETS

    // NODE


    private void expectMagicValue(DataInputStream dis, String magicValue) throws IOException {
        byte[] bytes = new byte[magicValue.length()];
        int read = dis.read(bytes);
        if (read != bytes.length) throw new IllegalStateException("Expected magic value "+magicValue+" Instead got EOF?");
        String actual = new String(bytes);
        if (!actual.equals(magicValue)) throw new IllegalStateException("Expected magic value "+magicValue+" Instead got "+actual);
    }

    private int xStart, yStart, zStart, xLen, yLen, zLen;

    private void parsePathfindV2Header(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(fis);
            CountingInputStream countingInputStream = new CountingInputStream(bufferedInputStream);
            DataInputStream dis = new DataInputStream(countingInputStream);


            expectMagicValue(dis, "DGPFRES2");
            this.version = dis.readInt();
            this.id = dis.readUTF();
            this.targetHash = dis.readUTF();
            this.targetId = dis.readUTF();
            this.roomUID = UUID.fromString(dis.readUTF());
            this.roomState = dis.readUTF();
            this.generatedFrom = dis.readUTF();


            expectMagicValue(dis, "ALGO");
            algorithmSetting = AlgorithmSetting.deserialize(dis);

            expectMagicValue(dis, "TRGT");
            int targetSize = dis.readInt();
            targetLocations = new ArrayList<>();
            for (int i = 0; i < targetSize; i ++) {
                targetLocations.add(new OffsetVec3(
                        dis.readInt() / 2.0, (dis.readInt() - 140) / 2.0, dis.readInt() / 2.0
                ));
            }

            expectMagicValue(dis, "NODE");
            this.compressed = dis.readBoolean();
            this.start = countingInputStream.getCount();

            DataInputStream dataInputStream;
            if (compressed) {
                InflaterInputStream gzipInputStream = new InflaterInputStream(dis);
                dataInputStream = new DataInputStream(gzipInputStream);
            } else {
                dataInputStream = new DataInputStream(dis);
            }
            this.xStart = dataInputStream.readShort();
            this.yStart = dataInputStream.readShort();
            this.zStart = dataInputStream.readShort();
            this.xLen = dataInputStream.readShort();
            this.yLen = dataInputStream.readShort();
            this.zLen = dataInputStream.readShort();
            dataInputStream.close();
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("DG-Precalc");

    // counts synchronous .pfres node reads, so tests can prove a caller never does file IO
    public static final AtomicInteger SYNC_FILE_LOADS = new AtomicInteger();

    // volatile: pathfinders get created from the TSP cache threads and the route threads at the same time.
    // only buffers are cached here - null means "not resident, the next blocking createPathfinder reads the file"
    private volatile ReferenceCountedByteBufferWrapper cachedBuffer;
    private final Object bufferLock = new Object();
    private CompletableFuture<Boolean> pendingLoad;

    // off-heap buffer shared by every pathfinder of this precalculation, freed when the last one releases it.
    // 1.8.9 freed it through DirectByteBuffer's internal cleaner, which java 16+ no longer lets mods reach,
    // so the buffer lives in its own shared arena instead (shared, released from the Cleaner thread too)
    public static class ReferenceCountedByteBufferWrapper {
        private int reference;
        private final Arena arena;
        // pinned buffers (async preloads) stay resident at 0 references until the LRU evicts them;
        // unpinned buffers (synchronous loads) keep the old semantic: freed the moment the last
        // pathfinder releases, so the buffer lifecycle is unchanged for those callers
        private boolean keepAlive;
        private boolean evicted;
        private boolean freed;

        // reads after the arena closed throw IllegalStateException rather than touching freed memory
        @Getter
        private final ByteBuffer byteBuffer;

        public ReferenceCountedByteBufferWrapper(Arena arena, ByteBuffer byteBuffer) {
            this(arena, byteBuffer, false);
        }

        public ReferenceCountedByteBufferWrapper(Arena arena, ByteBuffer byteBuffer, boolean keepAlive) {
            this.arena = arena;
            this.byteBuffer = byteBuffer;
            this.keepAlive = keepAlive;
            this.reference = 1;
        }

        // takes another reference, fails once the buffer was freed
        public synchronized boolean use() {
            if (freed) return false;
            reference++;
            return true;
        }

        public synchronized void release() {
            if (--reference == 0 && (!keepAlive || evicted)) free();
        }

        // drop the residency pin: frees now if nothing holds it, on the last release() otherwise
        public synchronized void evict() {
            evicted = true;
            keepAlive = false;
            if (reference == 0) free();
        }

        // a freshly loaded buffer that lost the publish race - nobody ever held it
        public synchronized void discard() {
            free();
        }

        private void free() {
            if (!freed) {
                freed = true;
                arena.close();
            }
        }

        public synchronized boolean isFreed() {
            return freed;
        }

        public synchronized int getReferenceCount() {
            return reference;
        }
    }

    // bytes the resident node buffer occupies, for the LRU budget
    public long getBufferSizeBytes() {
        return (long) xLen * yLen * zLen * 8;
    }

    public boolean isLoaded() {
        ReferenceCountedByteBufferWrapper buffer = cachedBuffer;
        return buffer != null && !buffer.isFreed();
    }

    // drops the resident buffer; live pathfinders keep theirs until they close
    public void evictBuffer() {
        ReferenceCountedByteBufferWrapper buffer;
        synchronized (bufferLock) {
            buffer = cachedBuffer;
            cachedBuffer = null;
        }
        if (buffer != null) buffer.evict();
        PrecalcBufferCache.untouch(this);
    }

    // creates a pathfinder only if the node buffer is already resident - never touches the disk,
    // this is the only variant the render thread is allowed to call
    public IPathfinder createPathfinderIfLoaded(int rotation) {
        ReferenceCountedByteBufferWrapper buffer = cachedBuffer;
        if (buffer == null || !buffer.use()) return null;
        PrecalcBufferCache.touch(this);
        return new PrecalculatedPathfinder(rotation, xStart, yStart, zStart, xLen, yLen, zLen, buffer);
    }

    // asynchronous load: dedupes with other callers, completes true once the buffer is resident.
    // the calling thread never does file IO
    public CompletableFuture<Boolean> preload() {
        if (isLoaded()) {
            PrecalcBufferCache.touch(this);
            return CompletableFuture.completedFuture(true);
        }
        synchronized (bufferLock) {
            if (pendingLoad == null) {
                CompletableFuture<Boolean> future = CompletableFuture
                        .supplyAsync(this::loadBlocking, PrecalcBufferCache.loader());
                pendingLoad = future;
                future.whenComplete((r, t) -> {
                    synchronized (bufferLock) {
                        if (pendingLoad == future) pendingLoad = null;
                    }
                });
            }
            return pendingLoad;
        }
    }

    // loads the node buffer on the calling thread and pins it as resident. returns false on IO errors
    public boolean loadBlocking() {
        if (isLoaded()) {
            PrecalcBufferCache.touch(this);
            return true;
        }
        ReferenceCountedByteBufferWrapper wrapper;
        try {
            wrapper = readBufferFromFile(true);
        } catch (IOException e) {
            return false;
        }
        boolean won = false;
        synchronized (bufferLock) {
            if (cachedBuffer == null || cachedBuffer.isFreed()) {
                cachedBuffer = wrapper;
                wrapper.release(); // park at 0 references; keepAlive keeps it resident
                won = true;
            }
        }
        if (!won) wrapper.discard();
        PrecalcBufferCache.touch(this);
        return true;
    }

    private ReferenceCountedByteBufferWrapper readBufferFromFile(boolean keepAlive) throws IOException {
        SYNC_FILE_LOADS.incrementAndGet();
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            fileInputStream.skip(start);
            DataInputStream dataInputStream;
            if (compressed) {
                BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
                InflaterInputStream gzipInputStream = new InflaterInputStream(bufferedInputStream);
                dataInputStream = new DataInputStream(gzipInputStream);
            } else {
                BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
                dataInputStream = new DataInputStream(bufferedInputStream);
            }
            int xStart = dataInputStream.readShort();
            int yStart = dataInputStream.readShort();
            int zStart = dataInputStream.readShort();
            int xLen = dataInputStream.readShort();
            int yLen = dataInputStream.readShort();
            int zLen = dataInputStream.readShort();

//            byte[] b = new byte[xLen * yLen * zLen * 8];
//            dataInputStream.readFully(b);

            Arena arena = Arena.ofShared();
            ReferenceCountedByteBufferWrapper wrapper;
            try {
                ByteBuffer buffer = arena.allocate((long) xLen * yLen * zLen * 8).asByteBuffer(); // use off-heap buffer.
                ReadableByteChannel channel = Channels.newChannel(dataInputStream);
                while (channel.read(buffer) > 0);
                wrapper = new ReferenceCountedByteBufferWrapper(arena, buffer, keepAlive);
            } catch (Throwable t) {
                // an arena has no gc fallback, a missed close leaks the memory until the game exits
                arena.close();
                throw t;
            }
            return wrapper;
        }
    }

    public IPathfinder createPathfinder(int rotation) throws IOException {
        IPathfinder loaded = createPathfinderIfLoaded(rotation);
        if (loaded != null) return loaded;

        // every render-thread caller goes through createPathfinderIfLoaded + preload; reaching here
        // on the render thread means a caller skipped them - keep working but flag it loudly
        if (SlowTaskTracker.isRenderThread()) {
            LOGGER.warn("blocking .pfres load on the render thread: {}", file);
        }

        ReferenceCountedByteBufferWrapper fresh = readBufferFromFile(false);
        ReferenceCountedByteBufferWrapper buffer;
        synchronized (bufferLock) {
            ReferenceCountedByteBufferWrapper existing = cachedBuffer;
            if (existing != null && existing.use()) {
                buffer = existing;
            } else {
                cachedBuffer = buffer = fresh;
            }
        }
        if (buffer != fresh) {
            // someone published a resident buffer while we were reading; ours was never used
            fresh.discard();
        }
        PrecalcBufferCache.touch(this);

        return new PrecalculatedPathfinder(rotation, xStart, yStart, zStart, xLen, yLen, zLen, buffer);
    }
}
