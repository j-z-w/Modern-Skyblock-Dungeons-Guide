package kr.syeyoung.dungeonsguide.mod.dungeon.data.mechanics;

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonContext;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.DungeonRoomInfo;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.LegacyBlock;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.OffsetPoint;
import kr.syeyoung.dungeonsguide.mod.dungeon.data.RoomDataConverter;
import kr.syeyoung.dungeonsguide.mod.dungeon.map.DungeonRoomScaffoldParser;
import kr.syeyoung.dungeonsguide.mod.dungeon.roomfinder.DungeonRoom;
import kr.syeyoung.dungeonsguide.mod.dungeon.world.DRIWorld;
import kr.syeyoung.dungeonsguide.mod.pathfinding.preset.PathfindPresetRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DungeonSecretDoubleChestStateTest {
    private static final String KNIGHT_ROOMDATA = "/roomdata/9890fbac-d382-4525-ab88-fe1ebc118702.roomdata.cbor";

    @BeforeAll
    static void bootstrap() {
        McBootstrap.boot();
        new DungeonsGuide();
    }

    private static class ChestWorld extends DRIWorld {
        private final Map<BlockPos, ChestBlockEntity> chests = new HashMap<>();
        private final Map<BlockPos, BlockState> overrides = new HashMap<>();

        ChestWorld(DungeonRoomInfo dungeonRoomInfo) {
            super(dungeonRoomInfo, PathfindPresetRegistry.DEFAULT_PRESET);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState override = overrides.get(pos);
            return override == null ? super.getBlockState(pos) : override;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return chests.get(pos);
        }

        void overrideBlock(BlockPos pos, ChestType type) {
            BlockState state = super.getBlockState(pos);
            overrides.put(pos, state.setValue(ChestBlock.TYPE, type));
        }

        void placeSingleChest(BlockPos pos) {
            overrides.put(pos, Blocks.CHEST.defaultBlockState());
        }

        void addChest(BlockPos pos) {
            chests.put(pos, new ChestBlockEntity(pos, getBlockState(pos)));
        }

        void openAllChestLids() {
            for (Map.Entry<BlockPos, ChestBlockEntity> entry : chests.entrySet()) {
                ChestBlockEntity chest = entry.getValue();
                chest.triggerEvent(1, 1);
                ChestBlockEntity.lidAnimateTick(null, entry.getKey(), chest.getBlockState(), chest);
            }
        }
    }

    private static class Fixture {
        final ChestWorld world;
        final DungeonContext context;
        final DungeonRoom room;

        Fixture() throws Exception {
            CBORMapper objectMapper = new CBORMapper();
            DungeonRoomInfo info;
            try (InputStream is = DungeonsGuide.class.getResourceAsStream(KNIGHT_ROOMDATA)) {
                info = objectMapper.readValue(is, DungeonRoomInfo.class);
            }
            RoomDataConverter.convert(info, LegacyBlock.stateTable());
            info.packWorld();
            world = new ChestWorld(info);
            context = new DungeonContext("TEST DG", world, PathfindPresetRegistry.DEFAULT_PRESET, false);
            context.setScaffoldParser(new DungeonRoomScaffoldParser(null, context));
            room = new DungeonRoom(context);
        }

        void close() {
            context.cleanup();
        }
    }

    private static Fixture fixture() throws Exception {
        return new Fixture();
    }

    private static DungeonSecretDoubleChestState doubleChest(DungeonRoom room) {
        return (DungeonSecretDoubleChestState) room.getMechanics().get("chests-1");
    }

    @Test
    void lidFanoutWithoutInteractionDoesNotOpenEitherHalf() throws Exception {
        Fixture fixture = fixture();
        try {
            DungeonSecretDoubleChestState chest = doubleChest(fixture.room);
            BlockPos first = chest.getData().getSecretPoint().getBlockPos(fixture.room);
            BlockPos second = chest.getData().getSecretPoint2().getBlockPos(fixture.room);
            fixture.world.addChest(first);
            fixture.world.addChest(second);

            chest.tick(fixture.room);
            fixture.world.openAllChestLids();
            chest.tick(fixture.room);

            assertFalse(chest.isFound(fixture.room));
            assertEquals(0, chest.getFoundSecretCount(fixture.room));
            assertEquals(DungeonSecretChestState.LastMeasuredChestStatus.UNOPEN, chest.getFirstHalfStatus());
            assertEquals(DungeonSecretChestState.LastMeasuredChestStatus.UNOPEN, chest.getSecondHalfStatus());
        } finally {
            fixture.close();
        }
    }

    @Test
    void eachDoubleChestHalfRequiresItsOwnInteraction() throws Exception {
        Fixture fixture = fixture();
        try {
            DungeonSecretDoubleChestState chest = doubleChest(fixture.room);
            BlockPos first = chest.getData().getSecretPoint().getBlockPos(fixture.room);
            BlockPos second = chest.getData().getSecretPoint2().getBlockPos(fixture.room);
            fixture.world.addChest(first);
            fixture.world.addChest(second);

            chest.noteChestInteraction(first);
            fixture.world.openAllChestLids();
            chest.tick(fixture.room);

            assertFalse(chest.isFound(fixture.room));
            assertEquals(1, chest.getFoundSecretCount(fixture.room));
            assertEquals(DungeonSecretChestState.LastMeasuredChestStatus.OPENED, chest.getFirstHalfStatus());
            assertEquals(DungeonSecretChestState.LastMeasuredChestStatus.UNOPEN, chest.getSecondHalfStatus());
            assertEquals("created", chest.getCurrentState());

            chest.noteChestInteraction(second);
            chest.tick(fixture.room);

            assertTrue(chest.isFound(fixture.room));
            assertEquals(2, chest.getFoundSecretCount(fixture.room));
            assertEquals(DungeonSecretChestState.LastMeasuredChestStatus.OPENED, chest.getFirstHalfStatus());
            assertEquals(DungeonSecretChestState.LastMeasuredChestStatus.OPENED, chest.getSecondHalfStatus());
            assertEquals("found", chest.getCurrentState());
        } finally {
            fixture.close();
        }
    }

    @Test
    void alreadySearchedMarksOnlyTheSearchedHalf() throws Exception {
        Fixture fixture = fixture();
        try {
            DungeonSecretDoubleChestState chest = doubleChest(fixture.room);
            BlockPos first = chest.getData().getSecretPoint().getBlockPos(fixture.room);
            BlockPos second = chest.getData().getSecretPoint2().getBlockPos(fixture.room);
            fixture.world.addChest(first);
            fixture.world.addChest(second);

            chest.markFound(first);
            assertFalse(chest.isFound(fixture.room));
            assertEquals(1, chest.getFoundSecretCount(fixture.room));

            chest.markFound(second);
            assertTrue(chest.isFound(fixture.room));
            assertEquals(2, chest.getFoundSecretCount(fixture.room));
        } finally {
            fixture.close();
        }
    }

    @Test
    void separatelyModeledDoubleChestHalvesUseTheClickedPosition() throws Exception {
        Fixture fixture = fixture();
        try {
            DungeonSecretDoubleChestState doubleChest = doubleChest(fixture.room);
            OffsetPoint firstPoint = doubleChest.getData().getSecretPoint();
            OffsetPoint secondPoint = doubleChest.getData().getSecretPoint2();
            BlockPos first = firstPoint.getBlockPos(fixture.room);
            BlockPos second = secondPoint.getBlockPos(fixture.room);
            fixture.world.overrideBlock(first, ChestType.SINGLE);
            fixture.world.overrideBlock(second, ChestType.SINGLE);
            fixture.world.addChest(first);
            fixture.world.addChest(second);

            DungeonSecretChestState.DungeonSecretChestData firstData = new DungeonSecretChestState.DungeonSecretChestData();
            firstData.setSecretPoint(firstPoint);
            DungeonSecretChestState firstHalf = new DungeonSecretChestState(firstData, fixture.room);
            DungeonSecretChestState.DungeonSecretChestData secondData = new DungeonSecretChestState.DungeonSecretChestData();
            secondData.setSecretPoint(secondPoint);
            DungeonSecretChestState secondHalf = new DungeonSecretChestState(secondData, fixture.room);

            fixture.world.openAllChestLids();
            firstHalf.tick(fixture.room);
            secondHalf.tick(fixture.room);
            assertFalse(firstHalf.isFound(fixture.room));
            assertFalse(secondHalf.isFound(fixture.room));

            firstHalf.noteChestInteraction();
            firstHalf.tick(fixture.room);
            secondHalf.tick(fixture.room);
            assertTrue(firstHalf.isFound(fixture.room));
            assertFalse(secondHalf.isFound(fixture.room));

            secondHalf.noteChestInteraction();
            secondHalf.tick(fixture.room);
            assertTrue(secondHalf.isFound(fixture.room));
        } finally {
            fixture.close();
        }
    }

    @Test
    void singleChestLidStillCountsWithoutALocalInteraction() throws Exception {
        Fixture fixture = fixture();
        try {
            DungeonSecretDoubleChestState doubleChest = doubleChest(fixture.room);
            BlockPos first = doubleChest.getData().getSecretPoint().getBlockPos(fixture.room);
            BlockPos singlePos = first.offset(8, 0, 0);
            fixture.world.placeSingleChest(singlePos);
            fixture.world.addChest(singlePos);

            DungeonSecretChestState.DungeonSecretChestData data = new DungeonSecretChestState.DungeonSecretChestData();
            data.setSecretPoint(new OffsetPoint(singlePos.getX(), singlePos.getY() - 70, singlePos.getZ()));
            DungeonSecretChestState singleChest = new DungeonSecretChestState(data, fixture.room);

            fixture.world.openAllChestLids();
            singleChest.tick(fixture.room);

            assertTrue(singleChest.isFound(fixture.room));
        } finally {
            fixture.close();
        }
    }
}
