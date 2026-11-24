package kr.syeyoung.dungeonsguide.mod.events.listener;

import it.unimi.dsi.fastutil.ints.IntList;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.events.EventProcessResult;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.ListenerRegistration;
import kr.syeyoung.dungeonsguide.mod.events.impl.PacketProcessedEvent;
import kr.syeyoung.dungeonsguide.mod.events.impl.RawPacketReceivedEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// the real 26.x mechanism: netty handlers call PacketUtils.ensureRunningOnSameThread, which
// queues a PacketProcessor$ListenerAndPacket; Minecraft.runTick drains it via
// processQueuedPackets() before runAllTasks. PacketProcessorMixin and
// ClientPacketListenerBundlesMixin wrap the packet.handle(...) invokes there, so the tests
// exercise the actual queue (constructible headless: it only needs a thread to bind to)
// plus the exact helper the mixins call. the mixins themselves can't run in a unit test -
// mixin isn't applied outside the game - so mixinTargetsExist() pins the method handles
// the injections target (verified in both the 26.1.2 and 26.3 deobf jars)
public class PacketOrderingTest {

    @BeforeAll
    static void setup() {
        McBootstrap.boot();
        // PacketInjector fires through the static instance's event bus
        if (DungeonsGuide.getDungeonsGuide() == null) new DungeonsGuide();
    }

    // a PacketListener good enough for ListenerAndPacket.handle(): shouldHandleMessage
    // defaults to isAcceptingMessages
    private static class TestListener implements PacketListener {
        @Override
        public PacketFlow flow() {
            return PacketFlow.CLIENTBOUND;
        }

        @Override
        public ConnectionProtocol protocol() {
            return ConnectionProtocol.PLAY;
        }

        @Override
        public void onDisconnect(DisconnectionDetails details) {
        }

        @Override
        public boolean isAcceptingMessages() {
            return true;
        }
    }

    private static Packet<TestListener> recordingPacket(List<String> order, String name) {
        return new Packet<>() {
            @Override
            public PacketType<? extends Packet<TestListener>> type() {
                return null;
            }

            @Override
            public void handle(TestListener listener) {
                order.add(name);
            }
        };
    }

    private static ListenerRegistration<PacketProcessedEvent.Pre> onPre(List<String> order, String tag) {
        return DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(
                PacketProcessedEvent.Pre.class, ListenerPriority.FIRST,
                e -> {
                    order.add(tag + ":" + ((ClientboundRemoveEntitiesPacket) e.packet).entityIds().toIntArray()[0]);
                    return EventProcessResult.COMPLETE;
                });
    }

    private static ListenerRegistration<PacketProcessedEvent.Post> onPost(List<String> order, String tag) {
        return DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(
                PacketProcessedEvent.Post.class, ListenerPriority.FIRST,
                e -> {
                    order.add(tag + ":" + ((ClientboundRemoveEntitiesPacket) e.packet).entityIds().toIntArray()[0]);
                    return EventProcessResult.COMPLETE;
                });
    }

    // scheduleIfPossible defers to processQueuedPackets(), which invokes
    // ListenerAndPacket.handle() -> packet.handle(listener) on the drain thread:
    // the exact call PacketProcessorMixin wraps
    @Test
    void queuedPacketRunsInsideProcessQueuedPackets() {
        PacketProcessor processor = new PacketProcessor(Thread.currentThread());
        List<String> order = new ArrayList<>();
        processor.scheduleIfPossible(new TestListener(), recordingPacket(order, "vanilla"));

        assertTrue(order.isEmpty(), "packet was handled on the scheduling thread");
        processor.processQueuedPackets();
        assertEquals(List.of("vanilla"), order);
    }

    // mixins can't run under junit; pin the methods and call sites they wrap so a
    // minecraft update that reshapes the queue fails the build loudly instead of
    // silently dropping Pre/Post
    @Test
    void mixinTargetsExist() throws Exception {
        Class<?> listenerAndPacket = Class.forName("net.minecraft.network.PacketProcessor$ListenerAndPacket");
        assertNotNull(listenerAndPacket.getDeclaredMethod("handle"),
                "PacketProcessorMixin wraps Packet.handle inside this method");
        assertNotNull(ClientPacketListener.class.getDeclaredMethod("handleBundlePacket", ClientboundBundlePacket.class),
                "ClientPacketListenerBundlesMixin wraps sub-packet Packet.handle inside this method");
    }

    // the exact composition the mixins produce: handleVanillaPacket(packet, packet::handle)
    @Test
    void preAndPostStrictlyBracketVanillaHandle() {
        List<String> order = new ArrayList<>();
        ListenerRegistration<PacketProcessedEvent.Pre> regPre = onPre(order, "pre");
        ListenerRegistration<PacketProcessedEvent.Post> regPost = onPost(order, "post");
        try {
            PacketInjector.handleVanillaPacket(
                    new ClientboundRemoveEntitiesPacket(IntList.of(4)),
                    () -> order.add("vanilla"));
            assertEquals(List.of("pre:4", "vanilla", "post:4"), order);
        } finally {
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPre);
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPost);
        }
    }

    // vanilla packet handlers throw; Post must still fire so nothing stays half-processed
    @Test
    void postFiresWhenVanillaHandleThrows() {
        List<String> order = new ArrayList<>();
        ListenerRegistration<PacketProcessedEvent.Pre> regPre = onPre(order, "pre");
        ListenerRegistration<PacketProcessedEvent.Post> regPost = onPost(order, "post");
        try {
            assertThrows(RuntimeException.class, () -> PacketInjector.handleVanillaPacket(
                    new ClientboundRemoveEntitiesPacket(IntList.of(6)),
                    () -> {
                        order.add("vanilla");
                        throw new RuntimeException("handler boom");
                    }));
            assertEquals(List.of("pre:6", "vanilla", "post:6"), order);
        } finally {
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPre);
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPost);
        }
    }

    // the bundle itself is untargeted, so the outer wrap runs it bare; inside, the
    // handleBundlePacket loop (as transformed by ClientPacketListenerBundlesMixin)
    // brackets each sub-packet - TWO packets, which the old single-queue mock masked
    @Test
    void bundleSubPacketsEachBracketed() {
        List<String> order = new ArrayList<>();
        ListenerRegistration<PacketProcessedEvent.Pre> regPre = onPre(order, "pre");
        ListenerRegistration<PacketProcessedEvent.Post> regPost = onPost(order, "post");
        try {
            ClientboundBundlePacket bundle = new ClientboundBundlePacket(
                    List.<Packet<? super ClientGamePacketListener>>of(
                            new ClientboundRemoveEntitiesPacket(IntList.of(9)),
                            new ClientboundRemoveEntitiesPacket(IntList.of(11))));

            PacketInjector.handleVanillaPacket(bundle, () -> {
                for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                    int id = ((ClientboundRemoveEntitiesPacket) sub).entityIds().toIntArray()[0];
                    PacketInjector.handleVanillaPacket(sub, () -> order.add("vanilla:" + id));
                }
            });

            assertEquals(List.of(
                    "pre:9", "vanilla:9", "post:9",
                    "pre:11", "vanilla:11", "post:11"), order);
        } finally {
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPre);
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPost);
        }
    }

    // packets without listeners must not pay for events at all
    @Test
    void nonTargetedPacketGetsNoEvents() {
        List<String> order = new ArrayList<>();
        List<String> events = new ArrayList<>();
        ListenerRegistration<PacketProcessedEvent.Pre> regPre = DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(
                PacketProcessedEvent.Pre.class, ListenerPriority.FIRST,
                e -> { events.add("pre"); return EventProcessResult.COMPLETE; });
        ListenerRegistration<PacketProcessedEvent.Post> regPost = DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(
                PacketProcessedEvent.Post.class, ListenerPriority.FIRST,
                e -> { events.add("post"); return EventProcessResult.COMPLETE; });
        try {
            PacketInjector.handleVanillaPacket(recordingPacket(order, "handle"), () -> order.add("vanilla"));
            assertEquals(List.of("vanilla"), order);
            assertTrue(events.isEmpty(), "untargeted packet fired packet events");
        } finally {
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPre);
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(regPost);
        }
    }

    // the netty side of the injector is unchanged: RawPacketReceivedEvent still fires per
    // targeted packet and can replace it before vanilla sees it
    @Test
    void rawEventStillFiresOnReceive() {
        List<String> order = new ArrayList<>();
        ListenerRegistration<RawPacketReceivedEvent> reg = DungeonsGuide.getDungeonsGuide().getEventBus().registerListener(
                RawPacketReceivedEvent.class, ListenerPriority.FIRST,
                e -> { order.add("raw"); return EventProcessResult.COMPLETE; });
        try {
            Packet<?> packet = PacketInjector.onPacketReceived(new ClientboundRemoveEntitiesPacket(IntList.of(3)));
            assertEquals(List.of("raw"), order);
            assertNotNull(packet);
        } finally {
            DungeonsGuide.getDungeonsGuide().getEventBus().unregisterListener(reg);
        }
    }
}
