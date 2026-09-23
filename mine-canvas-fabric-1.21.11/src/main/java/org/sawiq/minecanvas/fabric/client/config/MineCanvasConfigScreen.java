package org.sawiq.minecanvas.fabric.client.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;
import org.sawiq.minecanvas.fabric.client.video.VideoPlayer;
import org.sawiq.minecanvas.fabric.client.video.VideoScreenManager;

import java.awt.Desktop;
import java.nio.file.Files;
import java.util.List;

public final class MineCanvasConfigScreen extends Screen {
    private static final List<Integer> CACHE_LIMITS = List.of(1, 4, 8, 16, 32, 64);
    private final Screen parent;
    private final MineCanvasClientConfig config = MineCanvasClientConfig.get();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentWidth;
    private ConfigWidgets.Cache cacheWidget;
    private boolean cacheRefreshInFlight;
    private boolean cacheClearInFlight;
    private long nextCacheRefreshAt;

    public MineCanvasConfigScreen(Screen parent) {
        super(Component.translatable("config.minecanvas.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(450, width - 12));
        panelHeight = Math.max(1, Math.min(360, height - 12));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        int padding = panelWidth >= 300 ? 18 : Math.max(0, Math.min(8, (panelWidth - 1) / 4));
        contentX = panelX + padding;
        contentWidth = panelWidth - padding * 2;
        int headerHeight = Math.max(1, Math.min(45, panelHeight * 15 / 100));
        int footerHeight = Math.max(1, Math.min(36, panelHeight * 12 / 100));
        int gap = panelHeight >= 320 ? 3 : panelHeight >= 180 ? 1 : 0;
        int rows = 5;
        int rowHeight = Math.max(1, Math.min(26, (panelHeight - headerHeight - footerHeight - gap * (rows - 1)) / rows));
        panelHeight = Math.min(panelHeight, headerHeight + rowHeight * rows + gap * (rows - 1) + footerHeight);
        panelY = (height - panelHeight) / 2;
        int y = panelY + headerHeight;

        addRenderableWidget(new ConfigWidgets.Slider(contentX, y, contentWidth, rowHeight, font,
                Component.translatable("config.minecanvas.local_volume"), ConfigWidgets.Icon.SPEAKER,
                config.localVolumePercent, value -> config.localVolumePercent = value));
        y += rowHeight + gap;
        int halfGap = 3;
        int leftWidth = (contentWidth - halfGap) / 2;
        addRenderableWidget(new ConfigWidgets.Toggle(contentX, y, leftWidth, rowHeight, font,
                Component.translatable("config.minecanvas.render_video"), ConfigWidgets.Icon.PLAY,
                config.renderVideo, value -> config.renderVideo = value));
        addRenderableWidget(new ConfigWidgets.Toggle(contentX + leftWidth + halfGap, y,
                contentWidth - leftWidth - halfGap, rowHeight, font,
                Component.translatable("config.minecanvas.actionbar_timeline"), ConfigWidgets.Icon.TIMELINE,
                config.actionbarTimeline, value -> config.actionbarTimeline = value));
        y += rowHeight + gap;
        addRenderableWidget(new ConfigWidgets.Toggle(contentX, y, contentWidth, rowHeight, font,
                Component.translatable("config.minecanvas.prefetch_videos"), ConfigWidgets.Icon.DOWNLOAD,
                config.prefetchVideos, value -> config.prefetchVideos = value));
        y += rowHeight + gap;
        addRenderableWidget(new ConfigWidgets.CycleBox<>(contentX, y, contentWidth, rowHeight, font,
                Component.translatable("config.minecanvas.max_cache_gib"), ConfigWidgets.Icon.DOWNLOAD,
                CACHE_LIMITS, nearestCacheLimit(config.maxCacheGiB), value -> value + " GiB", value -> config.maxCacheGiB = value));
        y += rowHeight + gap;
        cacheWidget = addRenderableWidget(new ConfigWidgets.Cache(contentX, y, contentWidth, rowHeight, font,
                0, 0));
        int controlsWidth = Math.min(170, Math.max(104, contentWidth / 2));
        int clearWidth = Math.min(72, controlsWidth / 2);
        int openWidth = controlsWidth - clearWidth - 3;
        int controlsX = contentX + contentWidth - controlsWidth - 6;
        int actionHeight = Math.max(1, Math.min(20, rowHeight - 6));
        addRenderableWidget(new ConfigWidgets.Action(controlsX, y + (rowHeight - actionHeight) / 2,
                openWidth, actionHeight, font,
                Component.translatable("config.minecanvas.open_cache_folder"), false, this::openVideoCache));
        addRenderableWidget(new ConfigWidgets.Action(controlsX + openWidth + 3,
                y + (rowHeight - actionHeight) / 2, clearWidth, actionHeight,
                font, Component.translatable("config.minecanvas.clear_cache"), false, this::clearVideoCache));
        refreshCacheInfo();

        int doneHeight = Math.max(1, Math.min(22, footerHeight - 7));
        int doneY = panelY + panelHeight - doneHeight - 5;
        addRenderableWidget(new ConfigWidgets.Action(contentX + 13, doneY, contentWidth - 26, doneHeight,
                font, Component.translatable("gui.done"), false, this::onClose));
        addRenderableWidget(new ConfigWidgets.Action(panelX + panelWidth - 25, panelY + 8, 17, 17,
                font, Component.literal("×"), true, this::onClose));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        ConfigWidgets.drawPanel(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.enableScissor(panelX, panelY, panelX + panelWidth, panelY + panelHeight);
        drawHeader(graphics);
        drawLandscape(graphics);
        graphics.disableScissor();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawHeader(GuiGraphics graphics) {
        int iconWidth = 30;
        int iconHeight = 20;
        int gap = 8;
        int titleWidth = font.width(title);
        int groupX = contentX + (contentWidth - iconWidth - gap - titleWidth) / 2;
        int centerY = panelY + 22;
        int iconY = centerY - iconHeight / 2;
        int titleX = groupX + iconWidth + gap;
        int titleY = centerY - font.lineHeight / 2;
        ConfigWidgets.filmFrame(graphics, groupX, iconY);
        graphics.drawString(font, title, titleX + 1, titleY + 1, 0x55382432, false);
        graphics.drawString(font, title, titleX, titleY, 0xFF4A3846, false);
        ConfigWidgets.sparkle(graphics, groupX - 8, iconY - 5, 0xFFF2C9D2);
        ConfigWidgets.sparkle(graphics, titleX + titleWidth + 3, iconY - 5, 0xFFF2C9D2);
    }

    private static int nearestCacheLimit(int value) {
        return CACHE_LIMITS.stream().min((a, b) -> Integer.compare(Math.abs(a - value), Math.abs(b - value))).orElse(16);
    }

    private void clearVideoCache() {
        if (cacheClearInFlight) return;
        cacheClearInFlight = true;
        cacheWidget.setClearing();
        Thread worker = new Thread(() -> {
            long freed = VideoPlayer.clearCache();
            Minecraft.getInstance().execute(() -> {
                cacheClearInFlight = false;
                VideoScreenManager.clearDeletePromptHistory();
                VideoPlayer.CacheInfo cache = VideoPlayer.getCacheInfo();
                cacheWidget.setInfo(cache.fileCount(), cache.cacheSizeBytes(), freed);
            });
        }, "MineCanvas-Cache-Clear");
        worker.setDaemon(true);
        worker.start();
    }

    private void openVideoCache() {
        Thread worker = new Thread(() -> {
            boolean opened = false;
            try {
                var directory = FabricLoader.getInstance().getGameDir().resolve("mine-canvas-cache");
                Files.createDirectories(directory);
                opened = openCacheFolder(directory);
            } catch (Exception ignored) { }
            boolean result = opened;
            Minecraft.getInstance().execute(() -> cacheWidget.setOpenResult(result));
        }, "MineCanvas-Cache-Open");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean openCacheFolder(java.nio.file.Path directory) {
        String target = directory.toAbsolutePath().toString();
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Windows")) return openDesktop(directory) || start("explorer", target);
        if (os.startsWith("Linux")) return start("xdg-open", target)
                || start("/run/current-system/sw/bin/xdg-open", target) || start("gio", "open", target);
        return openDesktop(directory) || os.startsWith("Mac") && start("open", target);
    }

    private static boolean openDesktop(java.nio.file.Path directory) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory.toFile());
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static boolean start(String... command) {
        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() >= nextCacheRefreshAt) refreshCacheInfo();
    }

    private void refreshCacheInfo() {
        if (cacheRefreshInFlight) return;
        cacheRefreshInFlight = true;
        nextCacheRefreshAt = System.currentTimeMillis() + 1000L;
        Thread worker = new Thread(() -> {
            VideoPlayer.CacheInfo cache = VideoPlayer.getCacheInfo();
            Minecraft.getInstance().execute(() -> {
                cacheRefreshInFlight = false;
                if (cacheWidget != null) cacheWidget.setInfo(cache.fileCount(), cache.cacheSizeBytes());
            });
        }, "MineCanvas-Cache-Info");
        worker.setDaemon(true);
        worker.start();
    }

    private void drawLandscape(GuiGraphics graphics) {
        int bottom = panelY + panelHeight - 2;
        int top = bottom - Math.min(45, panelHeight / 6);
        graphics.fill(panelX + panelWidth - 52, top + 4, panelX + panelWidth - 47, top + 9, 0xFFF7E7C6);
        ConfigWidgets.mountain(graphics, panelX + 3, bottom, Math.max(28, panelWidth / 3), top + 7, 0x559C869B);
        ConfigWidgets.mountain(graphics, panelX + panelWidth / 3, bottom, Math.max(36, panelWidth / 2), top, 0x667E6B80);
        ConfigWidgets.tree(graphics, panelX + 17, bottom - 2, 0x887E6B80);
        ConfigWidgets.tree(graphics, panelX + panelWidth - 37, bottom - 1, 0x887E6B80);
        ConfigWidgets.tree(graphics, panelX + panelWidth - 21, bottom - 2, 0x667E6B80);
    }

    @Override
    public void onClose() {
        MineCanvasClientConfig.save();
        minecraft.setScreen(parent);
    }
}
