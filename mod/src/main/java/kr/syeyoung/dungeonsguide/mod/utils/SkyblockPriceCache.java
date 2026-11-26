package kr.syeyoung.dungeonsguide.mod.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public final class SkyblockPriceCache {
    private static final long REFRESH_INTERVAL_MS = 120_000;
    private static final String LOWEST_BIN_URL = "https://lb.tricked.pro/lowestbins.json.gz?price=available&type=all";
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DG-Price-Cache");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

    private static volatile Map<String, Double> lowestBins = Collections.emptyMap();
    private static volatile Map<String, Double> bazaarInstaSell = Collections.emptyMap();
    private static volatile long lastAttempt;
    private static volatile long lastSuccessfulRefresh;
    private static volatile String lastError;

    private SkyblockPriceCache() {
    }

    public static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastAttempt < (lastSuccessfulRefresh == 0 ? 15_000 : REFRESH_INTERVAL_MS)) return;
        if (!refreshInProgress.compareAndSet(false, true)) return;
        lastAttempt = now;
        EXECUTOR.submit(() -> {
            try {
                String error = null;
                try {
                    lowestBins = fetchLowestBins();
                } catch (Exception e) {
                    error = "lowest bins: " + e.getMessage();
                }
                try {
                    bazaarInstaSell = fetchBazaarInstaSell();
                } catch (Exception e) {
                    error = error == null ? "bazaar: " + e.getMessage() : error + ", bazaar: " + e.getMessage();
                }
                lastError = error;
                if (error == null) lastSuccessfulRefresh = System.currentTimeMillis();
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    public static boolean hasPrices() {
        return !lowestBins.isEmpty() || !bazaarInstaSell.isEmpty();
    }

    public static String getLastError() {
        return lastError;
    }

    public static double priceFor(String itemKey) {
        Double bazaar = bazaarInstaSell.get(bazaarProductKey(itemKey));
        if (bazaar != null) return bazaar;
        return lowestBins.getOrDefault(itemKey, -1.0);
    }

    public static double priceForEnchantedBook(String enchantName, int level) {
        String normalized = enchantName.toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        double ultimate = priceFor("ENCHANTED_BOOK-ULTIMATE_" + normalized + "-" + level);
        if (ultimate >= 0) return ultimate;
        return priceFor("ENCHANTED_BOOK-" + normalized + "-" + level);
    }

    private static String bazaarProductKey(String itemKey) {
        if (!itemKey.startsWith("ENCHANTED_BOOK-")) return itemKey.replace('-', ':');
        String rest = itemKey.substring("ENCHANTED_BOOK-".length());
        int levelSeparator = rest.lastIndexOf('-');
        if (levelSeparator < 0) return "ENCHANTMENT_" + rest;
        return "ENCHANTMENT_" + rest.substring(0, levelSeparator) + "_" + rest.substring(levelSeparator + 1);
    }

    private static Map<String, Double> fetchLowestBins() throws IOException {
        JsonObject object = readJson(LOWEST_BIN_URL, true);
        Map<String, Double> prices = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                prices.put(entry.getKey(), entry.getValue().getAsDouble());
            }
        }
        return prices;
    }

    private static Map<String, Double> fetchBazaarInstaSell() throws IOException {
        JsonObject root = readJson(BAZAAR_URL, false);
        if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
            throw new IOException("bazaar response was unsuccessful");
        }
        Map<String, Double> prices = new HashMap<>();
        for (Map.Entry<String, JsonElement> productEntry : root.getAsJsonObject("products").entrySet()) {
            if (!productEntry.getValue().isJsonObject()) continue;
            JsonObject product = productEntry.getValue().getAsJsonObject();
            if (!product.has("sell_summary") || !product.get("sell_summary").isJsonArray()) continue;
            for (JsonElement element : product.getAsJsonArray("sell_summary")) {
                if (!element.isJsonObject()) continue;
                JsonObject order = element.getAsJsonObject();
                if (order.has("pricePerUnit")) {
                    prices.put(productEntry.getKey(), order.get("pricePerUnit").getAsDouble());
                    break;
                }
            }
        }
        return prices;
    }

    private static JsonObject readJson(String url, boolean alwaysGzip) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "Modern-Dungeons-Guide");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        InputStream inputStream = connection.getInputStream();
        if (alwaysGzip || "gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            inputStream = new GZIPInputStream(inputStream);
        }
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
