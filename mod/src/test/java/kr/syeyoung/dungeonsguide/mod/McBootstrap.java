package kr.syeyoung.dungeonsguide.mod;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

// vanilla registries + datafixer, without a client. the same two calls a dedicated server makes at startup
public class McBootstrap {
    private static boolean bootstrapped = false;

    public static synchronized void boot() {
        if (bootstrapped) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
