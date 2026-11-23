/*
 *     Dungeons Guide - The most intelligent Hypixel Skyblock Dungeons Mod
 *     Copyright (C) 2021  cyoung06
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kr.syeyoung.dungeonsguide.mod;

import kr.syeyoung.dungeonsguide.launcher.Main;
import kr.syeyoung.dungeonsguide.launcher.events.AuthChangedEvent;
import kr.syeyoung.dungeonsguide.launcher.gui.screen.GuiDisplayer;
import kr.syeyoung.dungeonsguide.mod.chat.ChatProcessor;
import kr.syeyoung.dungeonsguide.mod.chat.ChatTransmitter;
import kr.syeyoung.dungeonsguide.mod.commands.CommandDgDebug;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;
import kr.syeyoung.dungeonsguide.mod.commands.CommandRegistrationHelper;
import kr.syeyoung.dungeonsguide.mod.events.impl.RegisterCommandEvent;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import kr.syeyoung.dungeonsguide.mod.commands.CommandDungeonsGuide;
import kr.syeyoung.dungeonsguide.mod.config.Config;
import kr.syeyoung.dungeonsguide.mod.config.guiconfig.configv3.ConfigGuiScreenAdapter;
import kr.syeyoung.dungeonsguide.mod.dungeon.DungeonFacade;
import kr.syeyoung.dungeonsguide.mod.events.EventBus;
import kr.syeyoung.dungeonsguide.mod.events.annotations.AnnotatedListenerHelper;
import kr.syeyoung.dungeonsguide.mod.events.listenerlist.BasicEventBus;
import kr.syeyoung.dungeonsguide.mod.events.listener.DungeonListener;
import kr.syeyoung.dungeonsguide.mod.events.listener.PacketListener;
import kr.syeyoung.dungeonsguide.mod.features.AbstractFeature;
import kr.syeyoung.dungeonsguide.mod.features.FeatureRegistry;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.tooltip.Notification;
import kr.syeyoung.dungeonsguide.mod.features.impl.etc.tooltip.WidgetNotificationAutoClose;
import kr.syeyoung.dungeonsguide.mod.gui.GuiScreenAdapter;
import kr.syeyoung.dungeonsguide.mod.gui.PassthroughManager;
import kr.syeyoung.dungeonsguide.mod.gui.elements.GlobalHUDScale;
import kr.syeyoung.dungeonsguide.mod.gui.xml.DomElementRegistry;
import kr.syeyoung.dungeonsguide.mod.overlay.OverlayManager;
import kr.syeyoung.dungeonsguide.mod.shader.ShaderManager;
import kr.syeyoung.dungeonsguide.mod.utils.BlockCache;
import kr.syeyoung.dungeonsguide.mod.utils.TimeScoreUtil;
import kr.syeyoung.dungeonsguide.mod.utils.cursor.GLCursors;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.packs.resources.ResourceManager;
import kr.syeyoung.dungeonsguide.mod.utils.ProgressManager;
import kr.syeyoung.dungeonsguide.mod.events.annotations.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class DungeonsGuide {

    @Getter
    private static boolean firstTimeUsingDG = false;
    Logger logger = LogManager.getLogger("DungeonsGuide");

    public boolean verbose = false;
    private SkyblockStatus skyblockStatus;

    @Getter
    private File tempDir = new File(Main.getConfigDir(), "tmp");

    @Getter
    public static final ThreadGroup THREAD_GROUP = new ThreadGroup("Dungeons Guide");

    public static final DefaultThreadFactory THREAD_FACTORY = new DefaultThreadFactory();

    static class DefaultThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        DefaultThreadFactory() {
            group = THREAD_GROUP;
            namePrefix = "pool-" +
                    poolNumber.getAndIncrement() +
                    "-thread-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r,
                    namePrefix + threadNumber.getAndIncrement(),
                    0);
            if (t.isDaemon())
                t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    @Getter
    private DungeonFacade dungeonFacade;

    // registry data is guaranteed loaded once this is true; gates dungeon context creation so
    // listeners registered before init finishes can't build a context against empty registries
    @Getter
    private volatile boolean initialized = false;

    @Getter
    private BlockCache blockCache;

    @Getter
    private final EventBus eventBus = new BasicEventBus();

    public DungeonsGuide(){
        instance = this;
    }
    private static DungeonsGuide instance;

    public static DungeonsGuide getDungeonsGuide() {
        return instance;
    }

    @Getter
    CommandDungeonsGuide commandDungeonsGuide;
    CommandDgDebug commandDgDebug;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandEvent event) {
        CommandRegistrationHelper.registerCommands(event.dispatcher, commandDungeonsGuide);
        CommandRegistrationHelper.registerCommands(event.dispatcher, commandDgDebug);

        CommandNode<FabricClientCommandSource> dg = event.dispatcher.getRoot().getChild("dg");
        for (String alias : CommandDungeonsGuide.ALIASES) {
            event.dispatcher.register(ClientCommands.literal(alias)
                    .executes(ctx -> {
                        commandDungeonsGuide.openConfig();
                        return Command.SINGLE_SUCCESS;
                    })
                    .redirect(dg));
        }
    }


    public void registerEventsForge(Object object) {
        AnnotatedListenerHelper.registerListeners(eventBus, object);
    }

    public ExecutorService registerExecutorService(ExecutorService executorService) {
        return executorService;
    }

    public ScheduledExecutorService registerExecutorService(ScheduledExecutorService executorService) {
        return executorService;
    }

    public void init(File f) {
        ProgressManager.ProgressBar progressbar = ProgressManager.push("DungeonsGuide", 5);


        progressbar.step("Creating Configuration");

        tempDir.mkdirs();

        File configFile = new File(Main.getConfigDir(), "config.json");
        if (!configFile.exists()) {
            Main.getConfigDir().mkdirs();
            firstTimeUsingDG = true;
        }

        Config.f = configFile;
        // 1.8.9 enabled the main framebuffer's stencil here; 26.1's main target has none, DG's stencil is its own image (D74)

        this.dungeonFacade = new DungeonFacade();
        // room data + registries (~3s) load off the render thread, overlapped with everything below
        Thread facadeInit = new Thread(THREAD_GROUP, () -> dungeonFacade.init(), "DG-Init");
        facadeInit.start();

        registerEventsForge(this);

        progressbar.step("Loading Native Libraries");

        // waterboard is not loaded in this build: it only powered the excluded water puzzle solver
        for (String library : new String[]{"dptsp"}) {
            try {
                NativeLoader.extractLibraryAndLoad(library);
            } catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
                e.printStackTrace();
            }
        }

        progressbar.step("Registering Events & Commands");

        skyblockStatus = new SkyblockStatus();

        registerEventsForge(skyblockStatus);
        registerEventsForge(ChatTransmitter.INSTANCE);
        registerEventsForge(new BlockCache());
        registerEventsForge(PassthroughManager.INSTANCE);
        registerEventsForge(GuiDisplayer.INSTANCE); // was registered by the 1.8.9 loader

        FeatureRegistry.getFeatureList();



        // TODO: 1.8.9 cleared LaunchClassLoader's negative cache (invalidClasses, org.slf4j.LoggerFactory) here. there is no launchwrapper on fabric


        this.blockCache = new BlockCache();

        registerEventsForge(new DungeonListener());

        commandDungeonsGuide = new CommandDungeonsGuide();
        commandDgDebug = new CommandDgDebug();

        registerEventsForge(commandDgDebug);
        registerEventsForge(commandDungeonsGuide);

        registerEventsForge(new PacketListener());
        registerEventsForge(new Keybinds());

        registerEventsForge(ChatProcessor.INSTANCE);
        registerEventsForge(OverlayManager.getEventHandler());


        // no backend connection in this build: STOMP websocket is excluded


        // config load/save (and any feature loadConfig that consults them) reads the
        // preset/precalculation registries, so the room data thread must be done first
        try {
            facadeInit.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        progressbar.step("Loading Config");
        try {
            Config.loadConfig(null);
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (AbstractFeature abstractFeature : FeatureRegistry.getFeatureList()) {
            abstractFeature.init();
        }


        TimeScoreUtil.init();

        ProgressManager.pop(progressbar);

        initialized = true;

        // update checks hit the network (and a modal JOptionPane for mandatory updates); off the render thread
        new Thread(THREAD_GROUP, VersionInfo::checkAndOpen, "DG-VersionCheck").start();

        // first-run onboarding is skipped in this build: its pages configure excluded features
    }

    // hotswap fails in dev env due to intellij auto log collection or smth. it holds ref to stacktrace.


    // TODO: nothing calls this on 26.1 yet, used to be called by the loader on resource reload
    public void onResourceReload(ResourceManager a) {
        GLCursors.setupCursors();
        ShaderManager.onResourceReload();
        DomElementRegistry.onResourceManagerReload();
    }


    public SkyblockStatus getSkyblockStatus() {
        return skyblockStatus;
    }


    @SubscribeEvent
    public void onAuthChange(AuthChangedEvent changedEvent) {
        UUID uid = UUID.randomUUID();
        FeatureRegistry.NOTIFICATIONS.getRootWidget().updateNotification(uid, new WidgetNotificationAutoClose(uid, Notification.builder()
                .title("User Changed!")
                .description("Reloading Dungeons Guide for new player...")
                .titleColor(0xFFFFFF00)
                .build(), 10000));
    }

    public Class<? extends Screen> getModConfigGUI() {
        return ConfigGuiScreenAdapter.class;
    }
}
