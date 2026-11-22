package kr.syeyoung.dungeonsguide.mod.pathfinding.precalculation;

import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetVec3;
import kr.syeyoung.dungeonsguide.mod.pathfinding.abilitysetting.AlgorithmSetting;
import kr.syeyoung.dungeonsguide.mod.pathfinding.pathfinder.IPathfinder;
import lombok.Data;
import lombok.Getter;
import org.apache.commons.io.input.CountingInputStream;

import java.io.*;
import java.lang.foreign.Arena;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    // volatile: pathfinders get created from the TSP cache threads and the route threads at the same time
    private volatile SoftReference<ReferenceCountedByteBufferWrapper> byteBufferWeakReference = new SoftReference<>(null);

    // off-heap buffer shared by every pathfinder of this precalculation, freed when the last one releases it.
    // 1.8.9 freed it through DirectByteBuffer's internal cleaner, which java 16+ no longer lets mods reach,
    // so the buffer lives in its own shared arena instead (shared, released from the Cleaner thread too)
    public static class ReferenceCountedByteBufferWrapper {
        // starts at 1, the reference of the pathfinder it was created for
        private final AtomicInteger reference = new AtomicInteger(1);
        private final Arena arena;

        // reads after the arena closed throw IllegalStateException rather than touching freed memory
        @Getter
        private final ByteBuffer byteBuffer;

        public ReferenceCountedByteBufferWrapper(Arena arena, ByteBuffer byteBuffer) {
            this.arena = arena;
            this.byteBuffer = byteBuffer;
        }

        // takes another reference, fails once the buffer was freed
        private boolean use() {
            while (true) {
                int current = reference.get();
                if (current <= 0) return false;
                if (reference.compareAndSet(current, current + 1)) return true;
            }
        }

        public void release() {
            if (reference.decrementAndGet() == 0) arena.close();
        }
    }

    public IPathfinder createPathfinder(int rotation) throws IOException {
        {
            ReferenceCountedByteBufferWrapper buffer = byteBufferWeakReference.get();
            if (buffer != null && buffer.use()) {
                return new PrecalculatedPathfinder(rotation, xStart, yStart, zStart, xLen, yLen, zLen, buffer);
            }
        }

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
                wrapper = new ReferenceCountedByteBufferWrapper(arena, buffer);
            } catch (Throwable t) {
                // an arena has no gc fallback, a missed close leaks the memory until the game exits
                arena.close();
                throw t;
            }
            byteBufferWeakReference = new SoftReference<>(wrapper);

            return new PrecalculatedPathfinder(rotation, xStart, yStart, zStart, xLen, yLen, zLen, wrapper);
        }
    }
}
