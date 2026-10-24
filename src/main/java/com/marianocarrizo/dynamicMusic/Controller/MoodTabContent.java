package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.model.Scene;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/**
 * Content of one mood tab (or the On demand tab) in the scene editor: track picker
 * (moods only) and the aura controls for the slot. Every edit is written straight into the
 * active scene and reported to the {@link SceneHost}.
 */
final class MoodTabContent extends VBox {

    private final AuraSlot slot;
    private final TrackType trackType; // null for ON_DEMAND
    private final SceneHost host;

    // Blocks listeners while controls are being filled from the model
    private boolean updating = false;

    private Label lblTrack;
    private final ToggleGroup presetGroup = new ToggleGroup();
    private final Map<AuraPreset, ToggleButton> presetButtons = new EnumMap<>(AuraPreset.class);
    private final ColorPicker cpPrimary = new ColorPicker();
    private final ColorPicker cpSecondary = new ColorPicker();
    private final CheckBox cbVignette = new CheckBox("Vignette");
    private final CheckBox cbEdgeGlow = new CheckBox("Edge glow");
    private final CheckBox cbEmbers = new CheckBox("Embers");
    private final CheckBox cbPulse = new CheckBox("Background pulse");
    private final Slider sldIntensity = new Slider(0, 1, 0.5);
    private final Slider sldReactivity = new Slider(0, 1, 0.5);
    private final Label lblIntensity = new Label();
    private final Label lblReactivity = new Label();

    MoodTabContent(AuraSlot slot, SceneHost host) {
        this.slot = slot;
        this.host = host;
        this.trackType = slot == AuraSlot.ON_DEMAND ? null : TrackType.valueOf(slot.name());

        setSpacing(14);
        setPadding(new Insets(14));

        if (trackType != null) getChildren().add(buildTrackSection());
        getChildren().add(buildPresetSection());
        getChildren().add(buildColorSection());
        getChildren().add(buildLayerSection());
        getChildren().add(buildLevelSection());
    }

    // --- Sections ---

    private VBox buildTrackSection() {
        lblTrack = new Label("No track assigned");
        lblTrack.getStyleClass().add("lbl-preview-track");
        lblTrack.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lblTrack, Priority.ALWAYS);

        Button change = new Button("Change...");
        change.getStyleClass().add("btn-secondary");
        change.setOnAction(e -> chooseTrack());
        Button clear = new Button("Clear");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> host.clearTrack(trackType));

        HBox row = new HBox(8, lblTrack, change, clear);
        row.setAlignment(Pos.CENTER_LEFT);
        return section("TRACK", row);
    }

    private VBox buildPresetSection() {
        HBox row = new HBox(6);
        for (AuraPreset p : AuraPreset.values()) {
            ToggleButton tb = new ToggleButton(capitalize(p.name()));
            tb.getStyleClass().addAll("btn-secondary", "btn-preset");
            tb.setToggleGroup(presetGroup);
            tb.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tb, Priority.ALWAYS);
            // A click on the selected toggle would deselect it: always keep one selected
            tb.setOnAction(e -> {
                tb.setSelected(true);
                if (!updating) pickPreset(p);
            });
            presetButtons.put(p, tb);
            row.getChildren().add(tb);
        }
        return section("PRESET", row);
    }

    private VBox buildColorSection() {
        cpPrimary.setOnAction(e -> {
            if (!updating) edit(a -> a.setPrimaryColor(toHex(cpPrimary.getValue())));
        });
        cpSecondary.setOnAction(e -> {
            if (!updating) edit(a -> a.setSecondaryColor(toHex(cpSecondary.getValue())));
        });
        HBox row = new HBox(10,
                labeled("Primary", cpPrimary),
                labeled("Secondary", cpSecondary));
        row.setAlignment(Pos.CENTER_LEFT);
        return section("COLORS", row);
    }

    private VBox buildLayerSection() {
        cbVignette.setOnAction(e -> { if (!updating) edit(a -> a.setVignette(cbVignette.isSelected())); });
        cbEdgeGlow.setOnAction(e -> { if (!updating) edit(a -> a.setEdgeGlow(cbEdgeGlow.isSelected())); });
        cbEmbers.setOnAction(e -> { if (!updating) edit(a -> a.setEmbers(cbEmbers.isSelected())); });
        cbPulse.setOnAction(e -> { if (!updating) edit(a -> a.setBackgroundPulse(cbPulse.isSelected())); });
        HBox row = new HBox(16, cbVignette, cbEdgeGlow, cbEmbers, cbPulse);
        row.setAlignment(Pos.CENTER_LEFT);
        return section("LAYERS", row);
    }

    private VBox buildLevelSection() {
        sldIntensity.valueProperty().addListener((o, ov, nv) -> {
            lblIntensity.setText(percent(nv.doubleValue()));
            if (!updating) edit(a -> a.setIntensity(nv.doubleValue()));
        });
        sldReactivity.valueProperty().addListener((o, ov, nv) -> {
            lblReactivity.setText(percent(nv.doubleValue()));
            if (!updating) edit(a -> a.setReactivity(nv.doubleValue()));
        });
        return section("LEVELS",
                levelRow("Intensity", sldIntensity, lblIntensity),
                levelRow("Reactivity", sldReactivity, lblReactivity));
    }

    // --- Model sync ---

    /** Fills every control from the given scene without triggering edits. */
    void refresh(Scene scene) {
        updating = true;
        try {
            if (trackType != null) {
                String path = scene.getAssignments().get(trackType);
                lblTrack.setText(path == null ? "No track assigned" : new File(path).getName());
            }
            AuraConfig aura = scene.getAura(slot);
            ToggleButton tb = presetButtons.get(aura.getPreset());
            if (tb != null) tb.setSelected(true);
            cpPrimary.setValue(Color.web(aura.getPrimaryColor()));
            cpSecondary.setValue(Color.web(aura.getSecondaryColor()));
            cbVignette.setSelected(aura.isVignette());
            cbEdgeGlow.setSelected(aura.isEdgeGlow());
            cbEmbers.setSelected(aura.isEmbers());
            cbPulse.setSelected(aura.isBackgroundPulse());
            sldIntensity.setValue(aura.getIntensity());
            sldReactivity.setValue(aura.getReactivity());
            lblIntensity.setText(percent(aura.getIntensity()));
            lblReactivity.setText(percent(aura.getReactivity()));
        } finally {
            updating = false;
        }
    }

    /** Updates only the track label. */
    void refreshTrack(Scene scene) {
        if (trackType == null) return;
        String path = scene.getAssignments().get(trackType);
        lblTrack.setText(path == null ? "No track assigned" : new File(path).getName());
    }

    private void pickPreset(AuraPreset preset) {
        Scene scene = host.session().getActiveScene();
        AuraConfig old = scene.getAura(slot);
        AuraConfig fresh = preset.defaults(); // colors, layers and intensity reset; reactivity is kept
        fresh.setReactivity(old.getReactivity());
        scene.setAura(slot, fresh);
        refresh(scene);
        host.onAuraEdited(slot);
    }

    private void edit(java.util.function.Consumer<AuraConfig> change) {
        AuraConfig aura = host.session().getActiveScene().getAura(slot);
        change.accept(aura);
        host.onAuraEdited(slot);
    }

    private void chooseTrack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select track for " + capitalize(slot.name()));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Audio (mp3, wav, flac)", "*.mp3", "*.wav", "*.flac"));
        String current = host.session().getActiveScene().getAssignments().get(trackType);
        File dir = null;
        if (current != null) dir = new File(current).getParentFile();
        if (dir == null || !dir.isDirectory()) {
            String last = host.session().getLastFolderPath();
            if (last != null) dir = new File(last);
        }
        if (dir != null && dir.isDirectory()) chooser.setInitialDirectory(dir);
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) host.assignTrack(trackType, file);
    }

    // --- Layout helpers ---

    private static VBox section(String title, javafx.scene.Node... content) {
        Label header = new Label(title);
        header.getStyleClass().add("lbl-session-title");
        VBox box = new VBox(6);
        box.getChildren().add(header);
        box.getChildren().addAll(content);
        return box;
    }

    private static VBox labeled(String text, javafx.scene.Node control) {
        Label l = new Label(text);
        l.getStyleClass().add("lbl-preview-track");
        return new VBox(4, l, control);
    }

    private static HBox levelRow(String text, Slider slider, Label value) {
        Label name = new Label(text);
        name.setMinWidth(80);
        slider.getStyleClass().add("spotify-slider");
        value.getStyleClass().add("lbl-time");
        HBox.setHgrow(slider, Priority.ALWAYS);
        HBox row = new HBox(10, name, slider, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String percent(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String capitalize(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
