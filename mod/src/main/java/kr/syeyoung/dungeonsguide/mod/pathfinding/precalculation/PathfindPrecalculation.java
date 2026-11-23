package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
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

// identity equals/hashCode: instances are LRU keys in PrecalcBufferCache, so the mutable
// buffer state below must never feed into hashing. no setters either - parsed once.
@Getter
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
    // only buffers are cached here - null means "not resident, the next blocking createPathfinder reads the file".
    // every published buffer is pinned (keepAlive): the LRU owns residency, unpinned buffers
    // used to poison its byte accounting when their pathfinders closed
    private volatile ReferenceCountedByteBufferWrapper cachedBuffer;
    private final Object bufferLock = new Object();
    private CompletableFuture<Boolean> pendingLoad;
    // the queued handle + priority of the task behind pendingLoad, so an on-demand request
    // can move a still-queued prefetch ahead of older speculative work (promoteLoad)
    private PrecalcBufferCache.PrioritizedLoad pendingTask;
    private int pendingTaskPriority;

    // io failures get a cooldown instead of a 20hz disk retry loop from the display engines.
    // lastLoadFailureNanos is only meaningful once hasLoadFailure is set: nanoTime has an
    // arbitrary origin (can be < 5s after boot, or negative) so a 0 default would
    // false-trigger the backoff on a precalc that never failed
    private volatile boolean hasLoadFailure;
    private volatile long lastLoadFailureNanos;
    private static final long FAILURE_BACKOFF_NANOS = Long.parseLong(
            System.getProperty("dg.precalc.failureBackoffNanos", "5000000000")); // 5 s

    private boolean inFailureBackoff() {
        return hasLoadFailure && System.nanoTime() - lastLoadFailureNanos < FAILURE_BACKOFF_NANOS;
    }

    private void recordLoadFailure() {
        lastLoadFailureNanos = System.nanoTime();
        hasLoadFailure = true;
    }

    // off-heap buffer shared by every pathfinder of this precalculation, freed when the last one releases it.
    // 1.8.9 freed it through DirectByteBuffer's internal cleaner, which java 16+ no longer lets mods reach,
    // so the buffer lives in its own shared arena instead (shared, released from the Cleaner thread too)
    public static class ReferenceCountedByteBufferWrapper {
        private int reference;
        private final Arena arena;
        // published buffers are pinned: they stay resident at 0 references until the LRU
        // evicts them. eviction drops the pin so the buffer is freed on the last release
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

        public synchronized boolean isEvicted() {
            return evicted;
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
        return buffer != null && !buffer.isFreed() && !buffer.isEvicted();
    }

    // what the LRU should account: the currently published buffer, or null if it was
    // evicted/replaced between a caller's check and its touch()
    ReferenceCountedByteBufferWrapper residentBuffer() {
        ReferenceCountedByteBufferWrapper buffer = cachedBuffer;
        return buffer != null && !buffer.isFreed() && !buffer.isEvicted() ? buffer : null;
    }

    // drops the resident buffer; live pathfinders keep theirs until they close.
    // goes through the cache so the LRU removal and the buffer eviction are one atomic
    // step - a reload racing between them used to leak the new buffer outside the LRU
    public void evictBuffer() {
        PrecalcBufferCache.evict(this);
    }

    // PrecalcBufferCache calls this while holding its own monitor (the LRU entry is already
    // removed there, so no untouch). lock order is always PrecalcBufferCache -> bufferLock ->
    // wrapper: nothing holding bufferLock may call back into the cache
    void evictBufferInternal() {
        ReferenceCountedByteBufferWrapper buffer;
        synchronized (bufferLock) {
            buffer = cachedBuffer;
            cachedBuffer = null;
        }
        if (buffer != null) buffer.evict();
    }

    // creates a pathfinder only if the node buffer is already resident - never touches the disk,
    // this is the only variant the render thread is allowed to call
    public IPathfinder createPathfinderIfLoaded(int rotation) {
        ReferenceCountedByteBufferWrapper buffer;
        synchronized (bufferLock) {
            buffer = cachedBuffer;
            if (buffer == null || buffer.isEvicted() || !buffer.use()) return null;
        }
        // touch outside bufferLock: the cache can evict (and so wants bufferLock) from inside touch
        PrecalcBufferCache.touch(this);
        return new PrecalculatedPathfinder(rotation, xStart, yStart, zStart, xLen, yLen, zLen, buffer);
    }

    // asynchronous load: dedupes with other callers, completes true once the buffer is resident.
    // the calling thread never does file IO. failed loads are not retried for the backoff window
    public CompletableFuture<Boolean> preload() {
        return preload(PrecalcBufferCache.PRIORITY_ONDEMAND);
    }

    public CompletableFuture<Boolean> preload(int priority) {
        CompletableFuture<Boolean> result = null;
        ReferenceCountedByteBufferWrapper resident = null;
        synchronized (bufferLock) {
            ReferenceCountedByteBufferWrapper buffer = cachedBuffer;
            if (buffer != null && !buffer.isFreed() && !buffer.isEvicted()) {
                resident = buffer;
            } else if (pendingLoad == null) {
                if (inFailureBackoff()) {
                    result = CompletableFuture.completedFuture(false);
                } else {
                    // the future is completed inside the submitted task: even a synchronous
                    // completion can't null pendingLoad out from under the caller's return
                    CompletableFuture<Boolean> created = new CompletableFuture<>();
                    pendingLoad = created;
                    result = created;
                    try {
                        pendingTask = PrecalcBufferCache.submitPrioritized(priority, () -> {
                            try {
                                created.complete(loadBlocking());
                            } catch (Throwable t) {
                                created.completeExceptionally(t);
                            }
                        });
                        pendingTaskPriority = priority;
                    } catch (RuntimeException rejected) {
                        // a dead loader pool must not leave pendingLoad stuck non-null
                        if (pendingLoad == created) {
                            pendingLoad = null;
                            pendingTask = null;
                        }
                        throw rejected;
                    }
                    created.whenComplete((r, t) -> {
                        synchronized (bufferLock) {
                            if (pendingLoad == created) {
                                pendingLoad = null;
                                pendingTask = null;
                            }
                        }
                    });
                }
            } else if (priority < pendingTaskPriority && pendingTask != null) {
                // the caller actually needs this load now: re-offer its still-queued
                // speculative prefetch at the higher priority so it doesn't wait behind
                // every earlier prefetch of the room. no-op once a loader dequeued it
                PrecalcBufferCache.promoteLoad(pendingTask, priority);
                pendingTaskPriority = priority;
            }
            if (resident == null && result == null) result = pendingLoad;
        }
        if (resident != null) {
            PrecalcBufferCache.touch(this);
            return CompletableFuture.completedFuture(true);
        }
        return result;
    }

    // loads the node buffer on the calling thread and pins it as resident. returns false on IO errors
    public boolean loadBlocking() {
        ReferenceCountedByteBufferWrapper resident = residentBuffer();
        if (resident != null) {
            PrecalcBufferCache.touch(this);
            return true;
        }
        ReferenceCountedByteBufferWrapper wrapper;
        try {
            wrapper = readBufferFromFile();
        } catch (IOException e) {
            recordLoadFailure();
            LOGGER.warn("failed to load precalculation {}: {}", file, e.toString());
            return false;
        }
        ReferenceCountedByteBufferWrapper winner;
        synchronized (bufferLock) {
            ReferenceCountedByteBufferWrapper existing = cachedBuffer;
            if (existing == null || existing.isFreed() || existing.isEvicted()) {
                cachedBuffer = wrapper;
                wrapper.release(); // park at 0 references; keepAlive keeps it resident
                winner = wrapper;
            } else {
                winner = existing;
            }
        }
        if (winner == wrapper) {
            PrecalcBufferCache.touch(this);
        } else {
            wrapper.discard();
            PrecalcBufferCache.touch(this);
        }
        return true;
    }

    private ReferenceCountedByteBufferWrapper readBufferFromFile() throws IOException {
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
                wrapper = new ReferenceCountedByteBufferWrapper(arena, buffer, true);
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

        // join an in-flight async load instead of duplicating the file read + inflate
        CompletableFuture<Boolean> pending;
        synchronized (bufferLock) {
            pending = pendingLoad;
        }
        if (pending != null) {
            try {
                pending.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException ignored) {
            }
            loaded = createPathfinderIfLoaded(rotation);
            if (loaded != null) return loaded;
        }

        if (inFailureBackoff()) {
            throw new IOException("recent .pfres load failure, backing off: " + file);
        }

        ReferenceCountedByteBufferWrapper fresh;
        try {
            fresh = readBufferFromFile();
        } catch (IOException e) {
            // same backoff contract as loadBlocking: callers that hit the disk before
            // preload() must not turn into a retry storm either
            recordLoadFailure();
            throw e;
        }
        ReferenceCountedByteBufferWrapper buffer;
        synchronized (bufferLock) {
            ReferenceCountedByteBufferWrapper existing = cachedBuffer;
            if (existing != null && !existing.isEvicted() && existing.use()) {
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
