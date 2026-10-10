/*
 * qTrace — QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import java.time.Instant;
import java.util.Optional;

/**
 * Phase 5 — Expert validation dialog.
 *
 * Collects: validator name, scope, confidence level, free-text notes.
 * Returns a {@link ValidationStamp} that QTraceController stores and
 * Phase 6 embeds in the .qtrace JSON sidecar.
 */
public class ValidationStamper {

    // Catppuccin Mocha — matches QTraceSettingsDialog / QTracePanel
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String TEAL       = "#2dd4bf"; // the Stamp CTA colour of the panel
    private static final String FIELD =
        "-fx-background-color: " + BG_SURFACE + "; -fx-control-inner-background: " + BG_SURFACE + ";"
      + "-fx-text-fill: " + TEXT_MAIN + "; -fx-prompt-text-fill: " + TEXT_MUTED + ";"
      + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12;";
    private static final String COMBO =
        "-fx-background-color: " + BG_SURFACE + "; -fx-border-color: " + BORDER + ";"
      + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12;";
    private static final String MONO = "-fx-font-family: monospace; -fx-font-size: 11;";

    private ValidationStamper() {}

    private static Dialog<ValidationStamp> current; // the open stamp dialog, if any — FX thread only

    /** Closes the stamp dialog if one is open (= Cancel); true when it did. The Stamp button is a toggle. */
    static boolean closeIfOpen() {
        if (current == null || !current.isShowing()) return false;
        current.close();
        return true;
    }

    /**
     * Show the validation dialog and return the stamp if the user confirmed.
     *
     * @param owner    owning stage (for modal positioning)
     * @param gitHash  commit hash from Phase 4 (may be null if not yet committed)
     * @param imgHash  SHA-256 of the source image (may be null if still computing)
     * @param fidelity classifier fidelity computed by ActionLogger
     */
    public static final String[] STATUS_LABELS = {"0-To Begin", "1-In Progress", "2-Finished"};

    public static Optional<ValidationStamp> show(Stage owner, String gitHash, String imgHash,
                                                  String qpdataHash,
                                                  ClassifierFidelity fidelity,
                                                  String currentStatusLabel,
                                                  String defaultCaseId) {
        return show(owner, gitHash, imgHash, qpdataHash, fidelity, currentStatusLabel, defaultCaseId, null, null);
    }

    public static Optional<ValidationStamp> show(Stage owner, String gitHash, String imgHash,
                                                  String qpdataHash,
                                                  ClassifierFidelity fidelity,
                                                  String currentStatusLabel,
                                                  String defaultCaseId,
                                                  ReplaySkip.Summary replay) {
        return show(owner, gitHash, imgHash, qpdataHash, fidelity, currentStatusLabel, defaultCaseId, replay, null);
    }

    /**
     * @param replay what a replay of this session runs; shown (read-only) when the author took
     *               instructions out of it, so the validator signs knowing. Null: not shown.
     * @param restampsBasicId the qtb_… id of the basic record this certified stamp would certify
     *               (shown as one information line); null when the stamp certifies nothing.
     */
    public static Optional<ValidationStamp> show(Stage owner, String gitHash, String imgHash,
                                                  String qpdataHash,
                                                  ClassifierFidelity fidelity,
                                                  String currentStatusLabel,
                                                  String defaultCaseId,
                                                  ReplaySkip.Summary replay,
                                                  String restampsBasicId) {
        Dialog<ValidationStamp> dialog = new Dialog<>();
        dialog.initOwner(owner);
        // Not modal: the panel button that opened it must stay clickable to close it again.
        // The caller checks that the work didn't change while the dialog was open.
        dialog.initModality(javafx.stage.Modality.NONE);
        dialog.setTitle("qTrace — Validate & Stamp");
        dialog.setHeaderText(null); // replaced by the qTrace title bar built below

        // ── Passphrase unlock (before any dialog) ────────────────────────────
        // getEntitled() → null when the license is inactive, degrading the stamp
        // dialog to Core mode (no identity lock, no PIN/passphrase, no attestation).
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep != null && ep.hasEncryptedSigningKey() && ep.getDecryptedSigningKey() == null) {
            ep.promptPassphraseAndDecrypt(owner);
            if (ep.getDecryptedSigningKey() == null) return Optional.empty(); // cancelled or wrong passphrase
        }

        // ── Form fields ──────────────────────────────────────────────────────
        // If Compliance + valid license: identity is locked to the license holder
        LicenseInfo activeLicense = null;
        if (ep != null) activeLicense = ep.getActiveLicenseInfo();

        // Without Compliance a signed-in basic account names the validator (locked, self-declared).
        String accountName = null;
        if (ep == null) {
            String licence = QTraceConfig.get().getLicensePath();
            boolean hasLicence = licence != null && !licence.isBlank();
            Account.Info who = Account.counts(hasLicence, Account.signedIn()) ? Account.info() : null;
            accountName = who != null ? who.name() : null;
        }
        StampIdentity identity = StampIdentity.resolve(null, accountName, QTraceConfig.get().getValidatorName());

        String configuredValidator = (activeLicense != null)
            ? activeLicense.name()
            : identity.name();

        TextField validatorField = new TextField(configuredValidator);
        validatorField.setPromptText("Dr. Lastname / Analyst ID");
        validatorField.setPrefWidth(280);
        validatorField.setPrefHeight(30);
        validatorField.setStyle(FIELD);

        if (activeLicense != null) {
            // Identity certified — field is read-only: no frame, the name in the certified green
            validatorField.setEditable(false);
            validatorField.setFocusTraversable(false);
            validatorField.setStyle(
                "-fx-background-color: transparent; -fx-border-color: transparent;"
              + "-fx-text-fill: " + GREEN + "; -fx-font-size: 13; -fx-font-weight: bold; -fx-padding: 0;"
            );
            validatorField.setTooltip(new javafx.scene.control.Tooltip(
                "Identity locked — certified by your qTrace identity certificate.\n"
              + "Institution: " + activeLicense.institution() + "\n"
              + "Valid until: " + activeLicense.expiresAtFormatted()
            ));
        } else if (identity.locked()) {
            // A signed-in account, no certificate: the name is the account's and cannot be edited,
            // in the neutral colour — green is for a certified identity only.
            validatorField.setEditable(false);
            validatorField.setFocusTraversable(false);
            validatorField.setStyle(
                "-fx-background-color: transparent; -fx-border-color: transparent;"
              + "-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 13; -fx-font-weight: bold; -fx-padding: 0;"
            );
            validatorField.setTooltip(new javafx.scene.control.Tooltip(QTraceI18n.t("stamp.account.tooltip")));
        }

        TextField caseIdField = new TextField(defaultCaseId != null ? defaultCaseId : "");
        caseIdField.setPromptText("Case identifier (e.g. project name)");
        caseIdField.setPrefWidth(280);
        caseIdField.setPrefHeight(30);
        caseIdField.setStyle(FIELD);
        caseIdField.setTooltip(new javafx.scene.control.Tooltip(
            "Identifier for this case — pre-filled from project name, editable"));

        ComboBox<String> scopeBox = new ComboBox<>(FXCollections.observableArrayList(
            "Full Workflow",
            "Image QC",
            "Segmentation",
            "Feature Extraction",
            "Phenotyping",
            "Spatial Analysis"
        ));
        scopeBox.setValue("Full Workflow");
        scopeBox.setPrefHeight(30);
        scopeBox.setStyle(COMBO);

        ComboBox<String> confidenceBox = new ComboBox<>(FXCollections.observableArrayList(
            "High", "Medium", "Low"
        ));
        confidenceBox.setValue("High");
        confidenceBox.setPrefHeight(30);
        confidenceBox.setStyle(COMBO);

        TextArea notesArea = new TextArea();
        notesArea.setPromptText("Commit title — summary of this stamp; anomalies, caveats, deviations from SOP…");
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);
        notesArea.setStyle(FIELD);

        // Attestation checkbox only shown when Compliance is present (signing has legal meaning)
        CheckBox attestationBox = ep != null ? new CheckBox(ValidationStamp.SIGNING_MEANING) : null;
        if (attestationBox != null) {
            attestationBox.setWrapText(true);
            attestationBox.setMaxWidth(360);
            attestationBox.setTextFill(javafx.scene.paint.Color.web(TEXT_MAIN));
        }

        ComboBox<String> statusBox = new ComboBox<>(
            FXCollections.observableArrayList(STATUS_LABELS));
        String preselect = "1-In Progress";
        if (currentStatusLabel != null) {
            for (String s : STATUS_LABELS) if (s.equals(currentStatusLabel)) { preselect = s; break; }
        }
        statusBox.setValue(preselect);
        statusBox.setPrefHeight(30);
        statusBox.setStyle(COMBO);

        // Read-only provenance display
        Label gitLabel = new Label(gitHash != null ? gitHash : "(not committed yet)");
        Label imgLabel = new Label(imgHash != null ? imgHash.substring(0, 16) + "…" : "(pending)");
        gitLabel.setStyle(MONO + "-fx-text-fill: " + TEXT_SUB + ";");
        imgLabel.setStyle(MONO + "-fx-text-fill: " + TEXT_SUB + ";");

        // Classifier_Fidelity — computed from ActionLogger, shown read-only
        String fidelityColor = switch (fidelity) {
            case HIGH        -> "#a6e3a1"; // green
            case DEGRADED    -> "#fab387"; // orange
            case COMPROMISED -> "#f38ba8"; // red
        };
        Label fidelityLabel = new Label(fidelity.name());
        fidelityLabel.setStyle(MONO + "-fx-font-weight: bold; -fx-text-fill: " + fidelityColor + ";");

        // ── Layout ───────────────────────────────────────────────────────────
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        grid.setPadding(new Insets(18, 20, 14, 20));

        int row = 0;
        Label validatorLabel = activeLicense != null
            ? styledLabel("Validator  ✓", GREEN)
            : fieldLabel("Validator *");
        grid.add(validatorLabel, 0, row); grid.add(validatorField, 1, row++);

        // Public key row — only when certified
        if (activeLicense != null && !activeLicense.validatorKey().isBlank()) {
            Label vkLabel = fieldLabel("Validator key");
            Label vkValue = new Label(activeLicense.validatorKeyShort());
            vkValue.setStyle(MONO + "-fx-text-fill: " + TEXT_MUTED + ";");
            vkValue.setTooltip(new javafx.scene.control.Tooltip(
                "ED25519 public key (full):\n" + activeLicense.validatorKey()
              + "\n\nThis key uniquely identifies you as a certified qTrace validator.\n"
              + "It will be used for cryptographic stamp signing and blockchain anchoring."));
            grid.add(vkLabel, 0, row); grid.add(vkValue, 1, row++);
        }
        grid.add(fieldLabel("Case ID"),              0, row); grid.add(caseIdField,    1, row++);
        grid.add(fieldLabel("Scope"),                0, row); grid.add(scopeBox,       1, row++);
        grid.add(fieldLabel("Confidence"),           0, row); grid.add(confidenceBox,  1, row++);
        grid.add(fieldLabel("Workflow status"),      0, row); grid.add(statusBox,      1, row++);
        grid.add(fieldLabel("Notes / commit title"), 0, row); grid.add(notesArea,      1, row++);
        if (attestationBox != null) {
            grid.add(fieldLabel("Attestation *"),    0, row); grid.add(attestationBox, 1, row++);
        }

        // What the stamp binds to — read-only, set apart in a card under the form.
        GridPane facts = new GridPane();
        facts.setHgap(14);
        facts.setVgap(6);
        facts.setPadding(new Insets(10, 14, 10, 14));
        facts.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-border-color: " + BORDER + ";"
            + "-fx-border-radius: 8; -fx-background-radius: 8;");
        int f = 0;
        facts.add(factLabel("Git hash"),            0, f); facts.add(gitLabel,      1, f++);
        facts.add(factLabel("Image SHA-256"),       0, f); facts.add(imgLabel,      1, f++);
        facts.add(factLabel("Classifier fidelity"), 0, f); facts.add(fidelityLabel, 1, f++);
        if (replay != null && replay.skipped() > 0) {
            Label replayLabel = new Label(QTraceI18n.f("stamp.replay.some",
                replay.replayed(), replay.total(), replay.skipped()));
            replayLabel.setStyle(MONO + "-fx-font-weight: bold; -fx-text-fill: #fab387;");
            facts.add(factLabel(QTraceI18n.t("stamp.replay.label")), 0, f); facts.add(replayLabel, 1, f);
        }
        javafx.scene.layout.VBox factsBox = new javafx.scene.layout.VBox(facts);
        factsBox.setPadding(new Insets(0, 20, 8, 20));

        // Same shell as Settings: title bar on the darker surface, content, Cancel + solid action.
        Label title = new Label("Validate & Stamp");
        title.setStyle("-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 13; -fx-font-weight: bold;");
        Label sub = new Label("Your name, a scope and a signature are attached to exactly what was done on this image.");
        sub.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 11;");
        javafx.scene.layout.VBox header = new javafx.scene.layout.VBox(2, title, sub);
        header.setPadding(new Insets(12, 20, 12, 20));
        header.setStyle("-fx-background-color: " + BG_SURFACE + ";");

        // Blocks added by modules (StampSections), just above the Stamp button.
        java.util.List<StampSection> sections = StampSections.create();

        DialogPane pane = dialog.getDialogPane();
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(header, grid);
        if (ep == null) {
            // No certificate comes with this stamp: said once, discreetly (no passphrase, no PIN).
            Label notCertified = new Label(QTraceI18n.t("stamp.notcertified.line"));
            notCertified.setStyle("-fx-text-fill: " + TEXT_MUTED + "; -fx-font-size: 11;");
            javafx.scene.layout.VBox note = new javafx.scene.layout.VBox(notCertified);
            note.setPadding(new Insets(0, 20, 8, 20));
            content.getChildren().add(note);
        }
        if (ep != null && restampsBasicId != null) {
            // A certified stamp over a basic one: it says which record it certifies.
            Label certifies = new Label(QTraceI18n.f("stamp.restamp.line", restampsBasicId));
            certifies.setWrapText(true);
            certifies.setStyle("-fx-text-fill: " + TEXT_MUTED + "; -fx-font-size: 11;");
            javafx.scene.layout.VBox note = new javafx.scene.layout.VBox(certifies);
            note.setPadding(new Insets(0, 20, 8, 20));
            content.getChildren().add(note);
        }
        content.getChildren().add(factsBox);
        pane.setContent(content);
        pane.setPadding(Insets.EMPTY);
        // -fx-base makes every control (drop-down lists, check box, scroll bars) dark with light text.
        pane.setStyle("-fx-background-color: " + BG_BASE + "; -fx-base: " + BG_BASE + ";"
            + "-fx-control-inner-background: " + BG_SURFACE + "; -fx-accent: " + BLUE + ";"
            + "-fx-focus-color: " + BLUE + "; -fx-faint-focus-color: transparent;");
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button cancelBtn = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancelBtn.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;"
            + "-fx-text-fill: " + TEXT_SUB + "; -fx-cursor: hand; -fx-font-size: 12;");
        Button stampBtn = (Button) pane.lookupButton(ButtonType.OK);
        stampBtn.setText("Stamp");
        stampBtn.setStyle("-fx-background-color: " + TEAL + "; -fx-text-fill: " + BG_BASE + ";"
            + "-fx-background-radius: 6; -fx-cursor: hand; -fx-font-size: 12; -fx-font-weight: bold;"
            + "-fx-padding: 6 22 6 22;");

        // Compliance: Stamp blocked until validator named AND attestation checked (21 CFR §11.50)
        // Core: Stamp blocked only until validator name entered (no cryptographic signing)
        Node okBtn = stampBtn;
        Runnable updateOk = () -> okBtn.setDisable(
            validatorField.getText().trim().isEmpty()
            || (attestationBox != null && !attestationBox.isSelected())
            || !StampSections.allReady(sections)
        );
        for (StampSection s : sections) s.ready().addListener((obs, o, n) -> updateOk.run());
        if (activeLicense == null) {
            validatorField.textProperty().addListener((obs, o, n) -> updateOk.run());
        }
        if (attestationBox != null) {
            attestationBox.selectedProperty().addListener((obs, o, n) -> updateOk.run());
        }
        updateOk.run();

        if (!sections.isEmpty()) centerActions(pane, content, sections, stampBtn, cancelBtn);

        // ── Result converter ─────────────────────────────────────────────────
        // Capture license info for signing (evaluated once, used in converter)
        final LicenseInfo licenseForSign = activeLicense;

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            String sel = statusBox.getValue();
            int idx = 1;
            for (int i = 0; i < STATUS_LABELS.length; i++)
                if (STATUS_LABELS[i].equals(sel)) { idx = i; break; }

            // Build unsigned stamp first (signature field requires the timestamp to be fixed)
            ValidationStamp unsigned = new ValidationStamp(
                validatorField.getText().trim(),
                Instant.now(),
                scopeBox.getValue(),
                confidenceBox.getValue(),
                notesArea.getText().trim(),
                gitHash,
                imgHash,
                qpdataHash,
                caseIdField.getText().trim(),
                fidelity.name(),
                idx,
                sel,
                null,  // signature — filled below
                licenseForSign != null ? licenseForSign.validatorKey() : null
            );

            // Sign if a key is available
            String sig = StampSigner.sign(unsigned);

            if (sig == null) return unsigned; // no key configured — stamp is unsigned

            // Return stamped copy with signature attached
            return new ValidationStamp(
                unsigned.validator(), unsigned.timestamp(),
                unsigned.scope(), unsigned.confidence(), unsigned.notes(),
                unsigned.gitHash(), unsigned.imageHash(), unsigned.qpdataSha256(),
                unsigned.caseId(),
                unsigned.classifierFidelity(),
                unsigned.statusIndex(), unsigned.statusLabel(),
                sig,
                unsigned.validatorKeyPub()
            );
        });

        current = dialog;
        try {
            return dialog.showAndWait();
        } finally {
            if (current == dialog) current = null;
        }
    }

    /**
     * With a module block: the block, then Stamp centred under it and Cancel as a link below.
     * The dialog's own buttons stay the ones that act (result converter, close) — hidden, and
     * fired by the centred ones.
     */
    private static void centerActions(DialogPane pane, javafx.scene.layout.VBox content,
                                      java.util.List<StampSection> sections, Button stampBtn, Button cancelBtn) {
        Node bar = pane.lookup(".button-bar");
        if (bar == null) {
            // Unknown dialog layout: keep the standard buttons, the blocks above them.
            for (StampSection s : sections) content.getChildren().add(s.node());
            return;
        }
        bar.setVisible(false);
        bar.setManaged(false);
        if (bar instanceof javafx.scene.layout.Region r) { // DialogPane still counts its height
            r.setMinHeight(0); r.setPrefHeight(0); r.setMaxHeight(0);
        }

        Button stamp = new Button("Stamp");
        stamp.setStyle(stampBtn.getStyle() + "-fx-padding: 8 44 8 44;");
        stamp.disableProperty().bind(stampBtn.disableProperty());
        stamp.setOnAction(e -> stampBtn.fire());
        stampBtn.setDefaultButton(false);
        stamp.setDefaultButton(true);

        // A real cancel button, not a link: Esc must cancel here as it does on the standard one,
        // which no longer answers once its bar is hidden.
        Button cancel = new Button("Cancel");
        cancel.setStyle(cancelBtn.getStyle());
        cancel.setOnAction(e -> cancelBtn.fire());
        cancelBtn.setCancelButton(false);
        cancel.setCancelButton(true);

        javafx.scene.layout.VBox actions = new javafx.scene.layout.VBox(10);
        actions.setAlignment(javafx.geometry.Pos.CENTER);
        actions.setPadding(new Insets(8, 20, 16, 20));
        for (StampSection s : sections) actions.getChildren().add(s.node());
        actions.getChildren().addAll(stamp, cancel);
        javafx.scene.layout.VBox.setMargin(stamp, new Insets(6, 0, 0, 0));
        content.getChildren().add(actions);
    }

    private static Label fieldLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 12;");
        return lbl;
    }

    private static Label factLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: " + TEXT_MUTED + "; -fx-font-size: 11;");
        lbl.setMinWidth(120);
        return lbl;
    }

    private static javafx.scene.control.Label styledLabel(String text, String hexColor) {
        javafx.scene.control.Label lbl = new javafx.scene.control.Label(text);
        lbl.setStyle("-fx-text-fill: " + hexColor + "; -fx-font-weight: bold;");
        return lbl;
    }
}
