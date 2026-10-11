package com.micaftic.morpher.client.gui;

import com.micaftic.morpher.client.gui.button.FlatColorButton;
import com.micaftic.morpher.core.gui.RoulettePanelStyle;
import com.micaftic.morpher.util.InputUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.ArrayList;

/** Readable, scrollable diagnostics for every failed item in a batch. */
public final class CloudUploadFailureScreen extends Screen {
    public record Failure(String modelId, String fileName, Component reason) { }
    private final Screen parent;
    private final List<Failure> failures;
    private final List<FlatColorButton> controls = new ArrayList<>();
    private int index, scroll;

    public CloudUploadFailureScreen(Screen parent, List<Failure> failures, String selectedId) {
        super(label("failure_details"));
        this.parent = parent;
        this.failures = List.copyOf(failures);
        if (failures.isEmpty()) throw new IllegalArgumentException("No upload failures");
        for (int i = 0; i < failures.size(); i++) if (failures.get(i).modelId().equals(selectedId)) index = i;
    }
    private static Component label(String key) { return Component.translatable("gui.sparkle_morpher.cloud_upload." + key); }
    private int panelW() { return Math.max(1, Math.min(720, width - (width < 400 ? 16 : 28))); }
    private int panelH() { return Math.max(1, Math.min(390, height - (height < 300 ? 16 : 28))); }
    private int panelX() { return (width - panelW()) / 2; }
    private int panelY() { return (height - panelH()) / 2; }
    private Component details() {
        Failure failure = failures.get(index);
        return Component.literal(failure.modelId() + "\n" + failure.fileName() + "\n\n").append(failure.reason());
    }
    @Override protected void init() {
        clearWidgets();
        controls.clear();
        int x = width / 2 - 48, y = panelY() + panelH() - 28;
        FlatColorButton previous = icon(x, y, -2, 0, Component.translatable("gui.sparkle_morpher.cloud.manage.identity_panel.previous"), button -> {
            index--; scroll = 0; init();
        });
        previous.active = index > 0;
        FlatColorButton next = icon(x + 26, y, -3, 0, Component.translatable("gui.sparkle_morpher.cloud.manage.identity_panel.next"), button -> {
            index++; scroll = 0; init();
        });
        next.active = index + 1 < failures.size();
        icon(x + 52, y, 64, 48, label("copy_details"), button -> Minecraft.getInstance().keyboardHandler.setClipboard(details().getString()));
        icon(x + 78, y, 80, 32, Component.translatable("gui.back"), button -> onClose());
    }
    private FlatColorButton icon(int x, int y, int u, int v, Component tooltip, net.minecraft.client.gui.components.Button.OnPress action) {
        FlatColorButton button = addRenderableWidget(new CloudUploadWorkspaceScreen.PanelButton(x, y, 18, 18, u, v, tooltip, action));
        controls.add(button);
        return button;
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        g.fill(0, 0, width, height, RoulettePanelStyle.BG);
        RoulettePanelStyle.glassPanel(g, x, y, w, h);
        RoulettePanelStyle.fill(g, x, y, w, 18, RoulettePanelStyle.PANEL_ACTIVE);
        RoulettePanelStyle.drawCentered(g, font, title, width / 2, y + 5, RoulettePanelStyle.TEXT);
        String page = (index + 1) + " / " + failures.size();
        RoulettePanelStyle.drawCentered(g, font, Component.literal(page), width / 2, y + 27, RoulettePanelStyle.MUTED);
        int bodyY = y + 44, bodyH = Math.max(0, h - 86);
        RoulettePanelStyle.secondaryGlassPanel(g, x + 10, bodyY, w - 20, bodyH);
        var lines = font.split(details(), Math.max(1, w - 40));
        int visible = Math.max(0, (bodyH - 10) / 12);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        g.enableScissor(x + 11, bodyY + 1, x + w - 11, bodyY + Math.max(1, bodyH - 1));
        for (int i = 0; i < visible && scroll + i < lines.size(); i++)
            g.drawString(font, lines.get(scroll + i), x + 16, bodyY + 5 + i * 12, RoulettePanelStyle.TEXT, false);
        g.disableScissor();
        if (visible > 0 && lines.size() > visible) {
            int track = bodyH - 2, thumb = Math.max(8, track * visible / lines.size());
            int top = bodyY + 1 + (track - thumb) * scroll / Math.max(1, lines.size() - visible);
            g.fill(x + w - 13, top, x + w - 12, top + thumb, RoulettePanelStyle.BORDER);
        }
        super.render(g, mouseX, mouseY, partialTick);
        controls.forEach(button -> button.renderTooltip(g, this, mouseX, mouseY));
    }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy != 0 && mx >= panelX() + 10 && mx < panelX() + panelW() - 10 && my >= panelY() + 44 && my < panelY() + panelH() - 42) {
            scroll = Math.max(0, scroll + (sy > 0 ? -3 : 3)); return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }
    @Override public void onClose() { InputUtil.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
