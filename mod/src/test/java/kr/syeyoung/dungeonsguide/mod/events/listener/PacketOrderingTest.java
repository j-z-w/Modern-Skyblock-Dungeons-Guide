package kr.syeyoung.dungeonsguide.mod.events.listener;

import it.unimi.dsi.fastutil.ints.IntList;
import kr.syeyoung.dungeonsguide.mod.DungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.McBootstrap;
import kr.syeyoung.dungeonsguide.mod.events.EventProcessResult;
import kr.syeyoung.dungeonsguide.mod.events.ListenerPriority;
import kr.syeyoung.dungeonsguide.mod.events.ListenerRegistration;
import kr.syeyoung.dungeonsguide.mod.events.impl.PacketProcessedEvent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

// simulates Connection.channelRead0 with minecraft's pendingRunnables replaced by an
// ArrayDeque: the Pre task enqueued at HEAD must run before the vanilla handler task,
// and the Post task enqueued at TAIL must run after it - FIFO, exactly like
// ReentrantBlockableEventLoop.pendingRunnables drains in runAllTasks
public class PacketOrderingTest {
    private Deque<Runnable> pendingRunnables;
    private BiConsumer<String, Runnable> originalSink;

    @BeforeAll
    static void setup() {
        McBootstrap.boot();
        // PacketInjector fires through the static instance's event bus
        if (DungeonsGuide.getDungeonsGuide() == null) new DungeonsGuide();
    }

    @BeforeEach
    void swapSink() {
        pendingRunnables = new ArrayDeque<>();
        originalSink = PacketInjector.scheduledTaskSink;
        PacketInjector.scheduledTaskSink = (name, task) -> pendingRunnables.offer(task);
    }

    @AfterEach
    void restoreSink() {
        PacketInjector.scheduledTaskSink = originalSink;
    }

    private void drainRenderThread() {
        Runnable r;
        while ((r = pendingRunnables.poll()) != null) r.run();
    }

    @Test
    void preRunsBeforeVanillaAndPostAfter() {
        List<String> order = new ArrayList<>();
        var bus = DungeonsGuide.getDungeonsGuide().getEventBus();
        ListenerRegistration<PacketProcessedEvent.Pre> regPre = bus.registerListener(
                PacketProcessedEvent.Pre.class, ListenerPriority.FIRST,
                e -> { order.add("pre"); return EventProcessResult.COMPLETE; });
        ListenerRegistration<PacketProcessedEvent.Post> regPost = bus.registerListener(
                PacketProcessedEvent.Post.class, ListenerPriority.FIRST,
                e -> { order.add("post"); return EventProcessResult.COMPLETE; });
        try {
            // netty thread: ConnectionMixin HEAD -> vanilla schedules -> ConnectionMixin TAIL
            Packet<?> packet = PacketInjector.onPacketReceived(new ClientboundRemoveEntitiesPacket(IntList.of(4)));
            pendingRunnables.offer(() -> order.add("vanilla")); // ensureRunningOnSameThread
            PacketInjector.afterPacketReceived(packet);

            drainRenderThread();
            assertEquals(List.of("pre", "vanilla", "post"), order);
        } finally {
            bus.unregisterListener(regPre);
            bus.unregisterListener(regPost);
        }
    }

    // bundled sub-packets recurse through the same path; their events bracket the bundle's
    // vanilla handling the same way
    @Test
    void bundledPacketKeepsOrdering() {
        List<String> order = new ArrayList<>();
        var bus = DungeonsGuide.getDungeonsGuide().getEventBus();
        ListenerRegistration<PacketProcessedEvent.Pre> regPre = bus.registerListener(
                PacketProcessedEvent.Pre.class, ListenerPriority.FIRST,
                e -> { order.add("pre"); return EventProcessResult.COMPLETE; });
        ListenerRegistration<PacketProcessedEvent.Post> regPost = bus.registerListener(
                PacketProcessedEvent.Post.class, ListenerPriority.FIRST,
                e -> { order.add("post"); return EventProcessResult.COMPLETE; });
        try {
            Packet<?> packet = PacketInjector.onPacketReceived(new ClientboundBundlePacket(
                    List.of(new ClientboundRemoveEntitiesPacket(IntList.of(9)))));
            pendingRunnables.offer(() -> order.add("vanilla"));
            PacketInjector.afterPacketReceived(packet);

            drainRenderThread();
            assertEquals(List.of("pre", "vanilla", "post"), order);
        } finally {
            bus.unregisterListener(regPre);
            bus.unregisterListener(regPost);
        }
    }

    // packets without listeners must not queue render-thread work at all
    @Test
    void nonTargetedPacketSchedulesNothing() {
        List<String> events = new ArrayList<>();
        var bus = DungeonsGuide.getDungeonsGuide().getEventBus();
        ListenerRegistration<PacketProcessedEvent.Pre> regPre = bus.registerListener(
                PacketProcessedEvent.Pre.class, ListenerPriority.FIRST,
                e -> { events.add("pre"); return EventProcessResult.COMPLETE; });
        try {
            Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> stray =
                    new Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>() {
                        @Override
                        public net.minecraft.network.protocol.PacketType<? extends Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>> type() {
                            return null;
                        }

                        @Override
                        public void handle(net.minecraft.network.protocol.game.ClientGamePacketListener listener) {}
                    };
            Packet<?> packet = PacketInjector.onPacketReceived(stray);
            PacketInjector.afterPacketReceived(packet);

            assertSame(stray, packet);
            assertTrue(pendingRunnables.isEmpty(), "untargeted packet queued render-thread work");
            drainRenderThread();
            assertTrue(events.isEmpty());
        } finally {
            bus.unregisterListener(regPre);
        }
    }
}
