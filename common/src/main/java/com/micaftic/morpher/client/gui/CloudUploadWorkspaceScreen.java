package com.micaftic.morpher.client.gui;

import com.micaftic.morpher.client.ClientModelManager;
import com.micaftic.morpher.client.gui.button.FlatColorButton;
import com.micaftic.morpher.core.gui.RoulettePanelStyle;
import com.micaftic.morpher.client.upload.ModelUploadSession;
import com.micaftic.morpher.client.upload.picker.FilePickerCoordinator;
import com.micaftic.morpher.cloud.client.CloudClientRuntime;
import com.micaftic.morpher.cloud.client.CloudInstanceRegistry;
import com.micaftic.morpher.core.storage.ModelStoragePaths;
import net.minecraft.Util;
import com.micaftic.morpher.util.InputUtil;
import com.micaftic.morpher.util.ModelIdUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Unified local model upload workspace, shared by all entry points. */
public class CloudUploadWorkspaceScreen extends Screen implements ModelUploadSession.Listener {
    private static final int TEXT = RoulettePanelStyle.TEXT, MUTED = RoulettePanelStyle.MUTED,
            ACCENT = RoulettePanelStyle.BORDER, ERROR = RoulettePanelStyle.RED;
    private final Screen parent;
    private final Map<String, Path> sources = new LinkedHashMap<>();
    private final Map<String, FilePickerCoordinator.PickedFile> localFiles = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, String> states = new HashMap<>();
    private final Map<String, Component> failures = new HashMap<>();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final List<FlatColorButton> settings = new ArrayList<>();
    private final List<FlatColorButton> controls = new ArrayList<>();
    private String instanceId, uploadInstanceId, activeId, activeUploadModelId;
    private ModelUploadSession activeSession, handledSession;
    private CompletableFuture<PreparedUpload> preparation;
    private boolean publicVisibility, uploading, listening, refreshing;
    private UploadTab activeTab = UploadTab.CLOUD_MODELS;
    private long nextPickedFileId;
    private EditBox search;
    private PanelButton primary, failureDetails;
    private int scroll;
    private Component status = Component.empty();

    public CloudUploadWorkspaceScreen(Screen parent, Collection<String> modelIds) {
        super(Component.translatable("gui.sparkle_morpher.cloud_upload.title"));
        this.parent = parent;
        reloadSnapshot();
        for (String id : modelIds) if (sources.containsKey(id)) selected.add(id);
        instanceId = CloudManagementScreen.management().registry().selected()
                .map(CloudInstanceRegistry.CloudInstanceProfile::instanceId).orElse("");
    }

    private void reloadSnapshot() {
        sources.clear();
        ClientModelManager.snapshotLocalCustomSources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> sources.put(entry.getKey(), entry.getValue()));
        Set<String> knownIds = new HashSet<>(sources.keySet());
        knownIds.addAll(localFiles.keySet());
        selected.retainAll(knownIds);
        states.keySet().retainAll(knownIds);
        failures.keySet().retainAll(knownIds);
        sources.keySet().forEach(id -> states.putIfAbsent(id, "waiting"));
        scroll = 0;
    }

    private int panelW() { return Math.max(1, Math.min(720, width - (width < 400 ? 16 : 28))); }
    private int panelH() { return Math.max(1, Math.min(390, height - (height < 300 ? 16 : 28))); }
    private int panelX() { return (width - panelW()) / 2; }
    private int panelY() { return (height - panelH()) / 2; }
    private boolean compact() { return panelH() < 300; }
    private int tabY() { return panelY() + (compact() ? 20 : 24); }
    private int targetY() { return tabY() + (compact() ? 20 : 24); }
    private int searchY() { return targetY() + (compact() ? 20 : 24); }
    private int listShellY() { return searchY() + (compact() ? 20 : 26); }
    private int listY() { return listShellY() + 18; }
    private int listH() { return Math.max(0, panelY() + panelH() - (compact() ? 40 : 54) - listY()); }
    private int rowH() { return compact() ? 18 : 28; }
    private int rows() { return Math.max(0, listH() / rowH()); }
    private Component fit(Component text, int width) {
        return font.width(text) <= width ? text : Component.literal(font.plainSubstrByWidth(text.getString(),
                Math.max(0, width - font.width("…"))) + "…");
    }
    private Component label(String key) { return Component.translatable("gui.sparkle_morpher.cloud_upload." + key); }
    private Component targetLabel() {
        String name = CloudManagementScreen.management().registry().find(instanceId)
                .map(CloudManagementScreen::displayName).orElse("—");
        return Component.translatable("gui.sparkle_morpher.cloud_upload.target", name);
    }
    private Component visibilityLabel() {
        return Component.translatable("gui.sparkle_morpher.import.visibility." + (publicVisibility ? "public" : "private"));
    }
    private FlatColorButton setting(int x, int y, int w, int h, Component label, net.minecraft.client.gui.components.Button.OnPress action) {
        FlatColorButton button = addRenderableWidget(new PanelButton(x, y, w, h, -1, 0, label, action));
        settings.add(button);
        controls.add(button);
        return button;
    }
    private PanelButton iconControl(int x, int y, int u, int v, Component tooltip, Button.OnPress action) {
        PanelButton button = addRenderableWidget(new PanelButton(x, y, 18, 18, u, v, tooltip, action));
        controls.add(button);
        return button;
    }
    private PanelButton iconSetting(int x, int y, int u, int v, Component tooltip, Button.OnPress action) {
        PanelButton button = iconControl(x, y, u, v, tooltip, action);
        settings.add(button);
        return button;
    }

    @Override
    protected void init() {
        String query = search == null ? "" : search.getValue();
        clearWidgets();
        settings.clear();
        controls.clear();
        if (!listening) { ModelUploadSession.addListener(this); listening = true; }
        int x = panelX() + 10, y = panelY(), w = Math.max(1, panelW() - 20);
        iconSetting(x, tabY(), 0, 0, label("tab.cloud_models"), button -> setTab(UploadTab.CLOUD_MODELS))
                .setSelected(activeTab == UploadTab.CLOUD_MODELS);
        iconSetting(x + 26, tabY(), 64, 48, label("tab.local_files"), button -> setTab(UploadTab.LOCAL_FILES))
                .setSelected(activeTab == UploadTab.LOCAL_FILES);
        boolean localTab = activeTab == UploadTab.LOCAL_FILES;
        int targetW = Math.max(1, w - 52);
        setting(x, targetY(), targetW, 18, fit(targetLabel(), targetW - 12), button -> cycleTarget())
                .setTooltipLines(List.of(targetLabel()));
        iconSetting(x + w - 44, targetY(), publicVisibility ? 0 : 80, 48, visibilityLabel(), button -> {
            publicVisibility = !publicVisibility;
            states.replaceAll((id, state) -> "waiting");
            failures.clear();
            status = Component.empty();
            init();
        });
        iconSetting(x + w - 18, targetY(), 96, 48, label("login"), button -> openAccount());
        int searchW = Math.max(1, w - (localTab ? 26 : 52));
        search = new EditBox(font, x + 5, searchY() + 5, Math.max(1, searchW - 10), 10, label("search"));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setTextColorUneditable(MUTED);
        search.setMaxLength(128);
        search.setHint(label("search"));
        search.setValue(query);
        search.setResponder(value -> scroll = 0);
        addRenderableWidget(search);
        if (localTab) {
            iconSetting(x + w - 18, searchY(), 0, 32, label("choose_files"), button -> openFilePicker());
        } else {
            iconSetting(x + w - 44, searchY(), 48, 16,
                    Component.translatable("gui.sparkle_morpher.upload_custom_folder.refresh"), button -> refresh());
            iconSetting(x + w - 18, searchY(), 64, 0,
                    Component.translatable("gui.sparkle_morpher.upload_custom_folder.open_folder"), button -> openFolder());
        }
        for (int row = 0; row < rows(); row++) {
            int rowIndex = row;
            addRenderableWidget(new RowHitboxButton(x + 3, listY() + row * rowH(), 19, rowH(), button -> selectRow(rowIndex, false)));
            addRenderableWidget(new RowHitboxButton(x + 22, listY() + row * rowH(), Math.max(1, w - 25), rowH(), button -> selectRow(rowIndex, true)));
        }
        int bottom = y + panelH() - 28;
        int toolbarX = width / 2 - ((localTab ? 6 : 5) * 26 - 8) / 2;
        iconSetting(toolbarX, bottom, 112, 48, label("select_all"), button -> { selected.addAll(filtered()); updateButtons(); });
        iconSetting(toolbarX + 26, bottom, 48, 32, label("clear_selection"), button -> clearSelection());
        int actionX = toolbarX + 52;
        if (localTab) {
            iconSetting(actionX, bottom, 112, 32, label("remove_short"), button -> removeSelectedFiles());
            actionX += 26;
        }
        failureDetails = iconControl(actionX, bottom, 96, 48, label("failure_details"), button -> openFailure(null));
        primary = iconControl(actionX + 26, bottom, 64, 16, label("start"), button -> { if (uploading) cancel(); else begin(); });
        iconControl(actionX + 52, bottom, 80, 32, Component.translatable("gui.back"), button -> onClose());
        updateButtons();
    }

    private void setTab(UploadTab tab) {
        if (uploading || activeTab == tab) return;
        activeTab = tab;
        scroll = 0;
        init();
    }

    private void openFilePicker() {
        status = Component.empty();
        Component error = FilePickerCoordinator.pickYsmFile();
        if (error != null) status = error;
    }

    private void clearSelection() {
        selected.removeAll(activeTab == UploadTab.LOCAL_FILES ? localFiles.keySet() : sources.keySet());
        updateButtons();
    }

    private void removeSelectedFiles() {
        if (uploading) return;
        for (String id : new ArrayList<>(selected)) {
            if (localFiles.remove(id) != null) {
                selected.remove(id);
                states.remove(id);
                failures.remove(id);
            }
        }
        updateButtons();
    }

    private void selectRow(int row, boolean inspectFailure) {
        if (uploading || refreshing) return;
        List<String> visible = filtered();
        int index = scroll + row;
        if (row >= rows() || index >= visible.size()) return;
        String id = visible.get(index);
        if (inspectFailure && failures.containsKey(id)) { openFailure(id); return; }
        if (!selected.add(id)) selected.remove(id);
        else if ("complete".equals(states.get(id))) states.put(id, "waiting");
        updateButtons();
    }

    private List<String> filtered() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        Collection<String> items = activeTab == UploadTab.LOCAL_FILES ? localFiles.keySet() : sources.keySet();
        return items.stream().filter(id -> query.isEmpty() || rowName(id).toLowerCase(Locale.ROOT).contains(query)).toList();
    }
    private String rowName(String id) {
        FilePickerCoordinator.PickedFile file = localFiles.get(id);
        return file == null ? id : file.fileName();
    }
    private void updateButtons() {
        if (failureDetails != null) failureDetails.active = !uploading && !failures.isEmpty();
        settings.forEach(button -> button.active = !uploading && !refreshing);
        if (search != null) search.setEditable(!uploading);
        if (primary != null) {
            primary.setMessage(label(uploading ? "cancel" : "start"));
            primary.setTooltipLines(List.of(label(uploading ? "cancel" : "start")));
            primary.setIcon(64, uploading ? 32 : 16);
            primary.active = uploading || (!refreshing && filtered().stream().anyMatch(id -> selected.contains(id) && !"complete".equals(states.get(id))));
        }
    }
    private void cycleTarget() {
        var profiles = CloudManagementScreen.management().registry().profiles();
        if (uploading || profiles.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).instanceId().equals(instanceId)) index = i;
        instanceId = profiles.get((index + 1) % profiles.size()).instanceId();
        states.replaceAll((id, state) -> "waiting");
        failures.clear();
        status = Component.empty();
        init();
    }
    private void openAccount() {
        if (parent instanceof ModernPlayerModelScreen panel) {
            InputUtil.setScreen(panel);
            panel.openCloudAccountForUpload(this, instanceId);
        } else CloudManagementScreen.open(this);
    }
    private void refresh() {
        if (uploading || refreshing) return;
        refreshing = true;
        status = Component.translatable("gui.sparkle_morpher.import.state.folder_reloading");
        updateButtons();
        ClientModelManager.reloadLocalModels(error -> Minecraft.getInstance().execute(() -> {
            refreshing = false;
            status = error == null ? Component.translatable("gui.sparkle_morpher.import.state.folder_reloaded") : error;
            if (error == null) reloadSnapshot();
            updateButtons();
        }));
    }
    private void openFolder() {
        try {
            Files.createDirectories(ModelStoragePaths.custom());
            Util.getPlatform().openFile(ModelStoragePaths.custom().toFile());
        } catch (IOException error) {
            status = Component.translatable("gui.sparkle_morpher.import.error.open_folder", error.getMessage());
        }
    }
    private void begin() {
        if (uploading || refreshing) return;
        List<String> tabSelection = filtered().stream().filter(selected::contains).toList();
        if (tabSelection.isEmpty()) { status = label("empty_selection"); return; }
        if (CloudClientRuntime.uploadTransport(instanceId) == null) { status = label("unavailable"); return; }
        ModelUploadSession current = ModelUploadSession.getInstance();
        if (current != null && !current.isTerminal()) {
            status = Component.translatable("gui.sparkle_morpher.import.error.in_progress"); return;
        }
        pending.clear();
        for (String id : tabSelection) if (!"complete".equals(states.get(id))) {
            pending.add(id); states.put(id, "waiting"); failures.remove(id);
        }
        uploadInstanceId = instanceId;
        uploading = true;
        updateButtons();
        startNext();
    }
    private void startNext() {
        activeSession = null;
        activeId = pending.poll();
        if (activeId == null) {
            uploading = false;
            long failed = selected.stream().filter(failures::containsKey).count();
            status = failed == 0 ? label("complete") : Component.translatable("gui.sparkle_morpher.cloud_upload.partial_failure", failed);
            updateButtons(); return;
        }
        String id = activeId;
        FilePickerCoordinator.PickedFile picked = localFiles.get(id);
        Path source = sources.get(id);
        if (picked != null) {
            activeUploadModelId = modelIdFromFileName(picked.fileName());
            if (activeUploadModelId.isBlank()) {
                fail(Component.translatable("gui.sparkle_morpher.import.error.model_id_from_filename", picked.fileName()));
                return;
            }
        } else {
            activeUploadModelId = id;
        }
        states.put(id, "preparing");
        status = Component.translatable("gui.sparkle_morpher.cloud_upload.preparing", rowName(id));
        preparation = CompletableFuture.supplyAsync(() -> {
            try {
                if (picked != null) {
                    Path temp = Files.createTempFile("spm-cloud-file-", ".upload");
                    try {
                        Files.write(temp, picked.data());
                        return new PreparedUpload(temp, picked.fileName(), true);
                    } catch (IOException | RuntimeException error) { Files.deleteIfExists(temp); throw error; }
                }
                if (source == null || !Files.exists(source)) throw new IOException("Local model source is missing: " + id);
                if (!Files.isDirectory(source)) return new PreparedUpload(source, source.getFileName().toString(), false);
                Path temp = Files.createTempFile("spm-cloud-folder-", ".zip");
                try {
                    Files.write(temp, FilePickerCoordinator.packDirectory(source).data());
                    return new PreparedUpload(temp, id + ".zip", true);
                } catch (IOException | RuntimeException error) { Files.deleteIfExists(temp); throw error; }
            } catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
        });
    }
    @Override
    public void tick() {
        super.tick();
        FilePickerCoordinator.PickedFile picked;
        boolean added = false;
        while ((picked = FilePickerCoordinator.pollCompleted()) != null) {
            String key = "picked-file-" + (++nextPickedFileId);
            localFiles.put(key, picked);
            states.put(key, "waiting");
            selected.add(key);
            added = true;
        }
        Component pickerError = FilePickerCoordinator.consumeLastError();
        if (!pickerError.getString().isEmpty()) status = pickerError;
        if (added) {
            activeTab = UploadTab.LOCAL_FILES;
            init();
        }
        updateButtons();
        if (!uploading || preparation == null || !preparation.isDone()) return;
        var ready = preparation;
        preparation = null;
        PreparedUpload prepared = null;
        try {
            prepared = ready.join();
            Component error = ModelUploadSession.start(activeUploadModelId, prepared.fileName(), prepared.source(),
                    publicVisibility ? "PUBLIC" : "PRIVATE", uploadInstanceId, prepared.temporary());
            if (error != null) { prepared.cleanUp(); fail(error); }
            else {
                states.put(activeId, "uploading");
                activeSession = ModelUploadSession.getInstance();
                handleSession(activeSession);
            }
        } catch (RuntimeException error) {
            if (prepared != null) prepared.cleanUp();
            fail(Component.literal(CloudManagementScreen.errorText(error)));
        }
    }
    private void fail(Component reason) { states.put(activeId, "failed"); failures.put(activeId, reason); startNext(); }
    private void openFailure(String selectedId) {
        var details = failures.keySet().stream()
                .map(id -> new CloudUploadFailureScreen.Failure(id, rowName(id), failures.get(id))).toList();
        if (!uploading && !details.isEmpty()) InputUtil.setScreen(new CloudUploadFailureScreen(this, details, selectedId));
    }
    private void cancel() {
        if (!uploading) return;
        uploading = false;
        pending.forEach(id -> states.put(id, "cancelled"));
        pending.clear();
        if (activeId != null) states.put(activeId, "cancelled");
        // supplyAsync cancellation cannot stop IO; clean its eventual result instead.
        if (preparation != null) { preparation.thenAccept(PreparedUpload::cleanUp); preparation = null; }
        if (activeSession != null && ModelUploadSession.getInstance() == activeSession && !activeSession.isTerminal())
            ModelUploadSession.failCurrent(label("cancelled"));
        activeSession = null; activeId = null;
        status = label("cancelled"); updateButtons();
    }
    @Override
    public void onSessionUpdate(ModelUploadSession session) { Minecraft.getInstance().execute(() -> handleSession(session)); }
    private void handleSession(ModelUploadSession session) {
        if (!uploading || session == null || session != activeSession) return;
        if (!session.isTerminal()) { status = session.getMessage(); return; }
        if (handledSession == session) return;
        handledSession = session;
        if (session.getState() == ModelUploadSession.State.FAILED) fail(session.getMessage());
        else { states.put(activeId, "complete"); failures.remove(activeId); ModelUploadSession.clearIfTerminal(); startNext(); }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        g.fill(0, 0, width, height, RoulettePanelStyle.BG);
        RoulettePanelStyle.glassPanel(g, x, y, w, h);
        RoulettePanelStyle.fill(g, x, y, w, 18, RoulettePanelStyle.PANEL_ACTIVE);
        RoulettePanelStyle.drawCentered(g, font, fit(title, w - 20), width / 2, y + 5, TEXT);
        String account = CloudManagementScreen.management().accountId(instanceId);
        Component accountText = Component.translatable("gui.sparkle_morpher.cloud_upload.account", account.isBlank() ? "—" : account);
        Component shown = fit(accountText, Math.max(1, w - 82));
        g.drawString(font, shown, x + w - 10 - font.width(shown), tabY() + 5, MUTED, false);
        if (RoulettePanelStyle.inside(mouseX, mouseY, x + 66, tabY(), w - 76, 18))
            g.renderComponentTooltip(font, List.of(accountText), mouseX, mouseY);
        int innerX = x + 10, innerW = Math.max(1, w - 20);
        int searchW = Math.max(1, innerW - (activeTab == UploadTab.LOCAL_FILES ? 26 : 52));
        RoulettePanelStyle.fill(g, innerX, searchY(), searchW, 18, 0x66303030);
        RoulettePanelStyle.border(g, innerX, searchY(), searchW, 18,
                search.isFocused() ? RoulettePanelStyle.BORDER : 0x33FFFFFF);
        List<String> visible = filtered();
        long selectedVisible = visible.stream().filter(selected::contains).count();
        RoulettePanelStyle.secondaryGlassPanel(g, innerX, listShellY(), innerW, listH() + 18);
        RoulettePanelStyle.fill(g, innerX, listShellY(), innerW, 16, RoulettePanelStyle.PANEL_ACTIVE);
        Component listTitle = label(activeTab == UploadTab.LOCAL_FILES ? "tab.local_files" : "tab.cloud_models");
        int titleWidth = Math.min(font.width(listTitle), innerW / 3);
        g.drawString(font, fit(listTitle, titleWidth), innerX + 5, listShellY() + 5, TEXT, false);
        Component count = Component.translatable("gui.sparkle_morpher.cloud_upload.selection", selectedVisible, visible.size());
        Component shownCount = fit(count, Math.max(1, innerW - titleWidth - 24));
        g.drawString(font, shownCount, innerX + innerW - 5 - font.width(shownCount), listShellY() + 5, MUTED, false);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, visible.size() - rows())));
        g.enableScissor(innerX + 1, listY(), innerX + innerW - 1, listY() + listH());
        if (visible.isEmpty()) g.drawString(font, fit(label("no_results"), innerW - 14), innerX + 7, listY() + 6, MUTED, false);
        List<Component> rowTooltip = null;
        for (int i = 0; i < rows() && scroll + i < visible.size(); i++) {
            String id = visible.get(scroll + i);
            int rowY = listY() + i * rowH();
            boolean checked = selected.contains(id);
            boolean hovered = RoulettePanelStyle.inside(mouseX, mouseY, innerX + 3, rowY, innerW - 6, rowH());
            RoulettePanelStyle.fill(g, innerX + 3, rowY + 1, innerW - 6, rowH() - 2,
                    hovered ? RoulettePanelStyle.PANEL_HOVER : checked ? RoulettePanelStyle.PANEL_ACTIVE : 0x55303030);
            if (checked) RoulettePanelStyle.drawIcon(g, RoulettePanelStyle.Glyph.CHECK, innerX + 5, rowY + (rowH() - 16) / 2);
            else RoulettePanelStyle.border(g, innerX + 8, rowY + (rowH() - 9) / 2, 9, 9, MUTED);
            int textWidth = Math.max(1, innerW - 112);
            g.drawString(font, fit(Component.literal(rowName(id)), textWidth), innerX + 25, rowY + 5, TEXT, false);
            FilePickerCoordinator.PickedFile picked = localFiles.get(id);
            Path source = sources.get(id);
            String file = picked == null ? (source == null ? rowName(id) : source.getFileName().toString()) : picked.fileName();
            if (rowH() >= 28) g.drawString(font, fit(Component.literal(picked == null ? file : formatBytes(picked.data().length)), textWidth), innerX + 25, rowY + 17, MUTED, false);
            String state = states.getOrDefault(id, "waiting");
            Component stateText = label("state." + state);
            if (id.equals(activeId) && activeSession != null && !activeSession.isTerminal())
                stateText = Component.literal(Math.round(activeSession.getProgress() * 100) + "%");
            g.drawString(font, fit(stateText, 72), innerX + innerW - 80, rowY + (rowH() - 8) / 2,
                    state.equals("failed") ? ERROR : state.equals("complete") ? TEXT : MUTED, false);
            if (hovered) {
                List<Component> tooltip = new ArrayList<>(List.of(Component.literal(rowName(id)), Component.literal(file)));
                if (failures.containsKey(id)) tooltip.add(failures.get(id));
                if (failures.containsKey(id)) tooltip.add(label("inspect_failure"));
                rowTooltip = tooltip;
            }
        }
        if (rows() > 0 && visible.size() > rows()) {
            int thumbH = Math.max(8, listH() * rows() / visible.size());
            int thumbY = listY() + (listH() - thumbH) * scroll / Math.max(1, visible.size() - rows());
            g.fill(innerX + innerW - 3, thumbY, innerX + innerW - 2, thumbY + thumbH, RoulettePanelStyle.BORDER);
        }
        g.disableScissor();
        int footerY = listY() + listH() + 2;
        if (uploading && activeSession != null) g.fill(innerX, footerY,
                innerX + (int) (innerW * activeSession.getProgress()), footerY + 1, ACCENT);
        g.drawString(font, fit(status, innerW - 10), innerX + 5, footerY + 1, failures.isEmpty() ? MUTED : ERROR, false);
        if (!status.getString().isBlank() && RoulettePanelStyle.inside(mouseX, mouseY, innerX, footerY, innerW, 14))
            g.renderComponentTooltip(font, List.of(status), mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
        controls.forEach(button -> button.renderTooltip(g, this, mouseX, mouseY));
        if (rowTooltip != null) g.renderComponentTooltip(font, rowTooltip, mouseX, mouseY);
    }
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return super.mouseClicked(mx, my, button);
    }
    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (mx >= panelX() + 10 && mx < panelX() + panelW() - 10 && my >= listY() && my < listY() + listH() && scrollY != 0) {
            scroll = Math.max(0, Math.min(Math.max(0, filtered().size() - rows()), scroll + (scrollY > 0 ? -1 : 1))); return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
    @Override
    public void removed() {
        cancel();
        FilePickerCoordinator.cancelPicking();
        if (listening) { ModelUploadSession.removeListener(this); listening = false; }
    }
    @Override
    public void onClose() { cancel(); InputUtil.setScreen(parent); }
    @Override
    public boolean isPauseScreen() { return false; }
    private record PreparedUpload(Path source, String fileName, boolean temporary) {
        void cleanUp() { if (temporary) try { Files.deleteIfExists(source); } catch (IOException ignored) { } }
    }

    private static String modelIdFromFileName(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        String stem = dot <= 0 ? fileName : fileName.substring(0, dot);
        return ModelIdUtil.normalizeImportModelId(stem);
    }

    private static String formatBytes(int bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }

    private enum UploadTab { CLOUD_MODELS, LOCAL_FILES }

    /** Same glass, border and icon treatment as the custom roulette editor. */
    static final class PanelButton extends FlatColorButton {
        private int iconU, iconV;
        private boolean selected;

        PanelButton(int x, int y, int width, int height, int u, int v, Component tooltip, Button.OnPress onPress) {
            super(x, y, width, height, tooltip, onPress);
            setIcon(u, v);
            setTooltipLines(List.of(tooltip));
        }

        void setIcon(int u, int v) { iconU = u; iconV = v; }
        @Override public void setSelected(boolean value) { selected = value; }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hover = active && isHoveredOrFocused();
            RoulettePanelStyle.fill(g, getX(), getY(), getWidth(), getHeight(), !active ? 0x44282828
                    : hover ? RoulettePanelStyle.PANEL_HOVER : selected ? RoulettePanelStyle.PANEL_ACTIVE : 0x66303030);
            RoulettePanelStyle.border(g, getX(), getY(), getWidth(), getHeight(), !active ? 0x22888888
                    : hover ? RoulettePanelStyle.RED : selected ? RoulettePanelStyle.RED_SOFT : 0x33FFFFFF);
            if (iconU >= 0) {
                int iconX = getX() + (getWidth() - 16) / 2, iconY = getY() + (getHeight() - 16) / 2;
                g.blit(RoulettePanelStyle.MODEL_PANEL_ICONS, iconX, iconY, 16, 16, iconU, iconV, 16, 16, 128, 64);
            } else if (iconU == -2 || iconU == -3) {
                int direction = iconU == -2 ? -1 : 1;
                int centerX = getX() + getWidth() / 2, centerY = getY() + getHeight() / 2;
                for (int i = 0; i < 5; i++) {
                    int dx = centerX + direction * (2 - i);
                    RoulettePanelStyle.fill(g, dx, centerY - i, 2, 1, active ? RoulettePanelStyle.TEXT : RoulettePanelStyle.MUTED);
                    RoulettePanelStyle.fill(g, dx, centerY + i, 2, 1, active ? RoulettePanelStyle.TEXT : RoulettePanelStyle.MUTED);
                }
            } else {
                var font = Minecraft.getInstance().font;
                Component text = Component.literal(RoulettePanelStyle.trim(font, getMessage().getString(), Math.max(1, getWidth() - 10)));
                RoulettePanelStyle.drawCentered(g, font, text, getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                        active ? RoulettePanelStyle.TEXT : RoulettePanelStyle.MUTED);
            }
        }
    }

    private static final class RowHitboxButton extends Button {
        private RowHitboxButton(int x, int y, int width, int height, Button.OnPress onPress) {
            super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        }
        @Override public void renderWidget(GuiGraphics extractor, int mouseX, int mouseY, float partialTick) { }
    }
}
