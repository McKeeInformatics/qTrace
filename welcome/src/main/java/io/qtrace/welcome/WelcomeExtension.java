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

package io.qtrace.welcome;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.BrowserOpener;
import io.qtrace.IssueReportDialog;
import io.qtrace.QTraceController;
import io.qtrace.QTracePlugin;
import io.qtrace.QTracePluginManager;
import io.qtrace.ModuleEntitlements;
import io.qtrace.ToolWelcomes;
import io.qtrace.ModuleNews;
import io.qtrace.ModuleUpdates;
import io.qtrace.Provisioner;
import io.qtrace.Provisioners;
import io.qtrace.QTraceConfig;
import io.qtrace.QTraceUpdater;
import io.qtrace.UploadInvite;
import io.qtrace.tools.PlayerBridge;
import io.qtrace.tools.PlayerWindow;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point of the welcome module (Qtrace-Load-Order 40, loader.md § 17): every welcome
 * window of qTrace, on every workstation — it ships with Core and the provisioning module.
 * <ul>
 * <li>Getting started, until the user signed in or chose to go on without an account; what its
 *     buttons do is the provisioning module's work (Core {@link Provisioners});</li>
 * <li>the organization's welcome, once, with a certificate (Welcome…);</li>
 * <li>the welcome of each tool when it first shows in the panel (Your tools…);</li>
 * <li>what the new version of an updated module brings (What's new…).</li>
 * </ul>
 */
public class WelcomeExtension implements QuPathExtension {

    private static final Logger log = LoggerFactory.getLogger(WelcomeExtension.class);
    private static final String TAG = "[qtrace-welcome] ";
    private static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    private static final Path STATE = Path.of(System.getProperty("user.home"), ".qTrace", "welcome.json");

    private static boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;

        List<MenuItem> menu = qupath.getMenu("Extensions>QTrace", true).getItems();
        MenuItem tools = new MenuItem("Your tools…");
        tools.setOnAction(e -> playTools(qupath, ModuleNews.installed().keySet()));
        MenuItem news = new MenuItem("What's new…");
        news.setOnAction(e -> playNews(qupath));
        MenuItem welcome = new MenuItem("Welcome…");
        welcome.setOnAction(e -> playWelcome(qupath));
        menu.addAll(0, List.of(welcome, tools, news));
        ToolWelcomes.register(module -> playTools(qupath, Set.of(module)));

        // While Getting started is to be shown it is the only window that opens by itself.
        boolean gettingStarted = addGettingStarted(qupath);
        startup(qupath, !gettingStarted);
    }

    // ── Getting started: the first window of a workstation without a certificate ──

    /**
     * Extensions > QTrace > Getting started… reopens it at any time. At startup it shows by
     * itself on a workstation with no certificate, until the user signed in or went on without
     * an account.
     *
     * @return true when it is shown at this startup
     */
    private static boolean addGettingStarted(QuPathGUI qupath) {
        Provisioner provisioner = Provisioners.current();
        if (provisioner == null) return false;
        MenuItem item = new MenuItem("Getting started…");
        item.setOnAction(e -> new TrunkPlayer(qupath, provisioner).show());
        qupath.getMenu("Extensions>QTrace", true).getItems().add(0, item);
        // The greyed Upload of a workstation without an account opens it too, where one signs in.
        UploadInvite.register(() -> new TrunkPlayer(qupath, provisioner).showAt(TrunkPlayer.ACCOUNT));
        if (!provisioner.gettingStartedPending()) return false;
        log.info(TAG + "no certificate yet: showing Getting started");
        // After QuPath's own Welcome window, like Core's update prompts.
        Platform.runLater(() -> QTraceUpdater.whenNoModalOpen("getting started",
            () -> new TrunkPlayer(qupath, provisioner).show()));
        return true;
    }

    // ── At startup: one window, one sequence ────────────────────────────────────

    /** The cards of the modules this workstation is served, as last fetched. */
    private static volatile List<ModuleUpdates.Card> cards = List.of();

    /**
     * What is to be read at this startup, played as one sequence in one window
     * ({@link WelcomeSequence}): the organization's welcome (once, with a certificate), then the
     * welcome of each tool new to this user, then what the updates bring. The last two only
     * when the user lets them show by themselves (Settings › Appearance); the tools still to be
     * read keep their gold square either way. {@code mayShow} false: nothing opens.
     */
    private static void startup(QuPathGUI qupath, boolean mayShow) {
        Map<String, String> updated = ModuleNews.updatedSinceLastShown();
        // Shown now or left for the menu: either way these versions are not news next time.
        ModuleNews.markShown();
        if (!updated.isEmpty()) log.info(TAG + "updated since last shown: {}", updated);
        boolean auto = mayShow && QTraceConfig.get().isShowModuleNewsAtStartup();
        boolean organization = mayShow && QTraceUpdater.licenseJwt() != null && !seen();

        CompletableFuture.supplyAsync(() -> {
            cards = moduleCards();
            return organization ? fetch() : null;
        }).exceptionally(e -> null).thenAccept(orgJson -> Platform.runLater(() -> {
            Set<String> unread = unreadTools();
            if (!unread.isEmpty()) log.info(TAG + "tool welcomes to read: {}", unread);
            ToolWelcomes.setUnread(unread);

            if (!PlayerBridge.webViewAvailable()) {
                // The plain dialog only knows the organization's welcome.
                if (orgJson != null) QTraceUpdater.whenNoModalOpen("welcome", () -> {
                    new WelcomeDialog(qupath).show(WelcomeSpec.parse(orgJson, WelcomeDialog.ACTIONS));
                    markSeen();
                });
                return;
            }
            List<ModuleUpdates.Card> tools = auto ? toolCards(unread) : List.of();
            String json = WelcomeSequence.of(orgJson,
                ToolContent.of(toolItems(tools)),
                auto ? NewsContent.of(newsItems(cards, updated)) : null);
            if (json == null) return;
            String title = orgJson != null ? "Welcome to qTrace" : !tools.isEmpty() ? toolsTitle(tools) : "What's new in qTrace";
            QTraceUpdater.whenNoModalOpen("welcome", () -> {
                if (orgJson != null) markSeen();
                play(qupath, title, json, () -> markRead(tools));
            });
        }));
    }

    /** The cards of the modules served here, as the provisioning module fetches them; none without it. */
    private static List<ModuleUpdates.Card> moduleCards() {
        Provisioner provisioner = Provisioners.current();
        return provisioner == null ? List.of() : provisioner.moduleCards();
    }

    /** The tools installed and served here whose welcome was not read at its current revision. */
    private static Set<String> unreadTools() {
        Map<String, Integer> written = new TreeMap<>();
        for (ModuleUpdates.Card c : cards) if (c.hasWelcome()) written.put(c.offer().module(), c.welcome().revision());
        Set<String> present = new TreeSet<>();
        for (String m : ModuleNews.installed().keySet()) if (ModuleEntitlements.isEntitled(m)) present.add(m);
        return ToolWelcomes.unreadHere(written, present);
    }

    // ── On demand: each welcome alone, from the menu or a gold square ────────────

    /** Extensions > QTrace > Welcome… — the organization's welcome, or the standard one. */
    private static void playWelcome(QuPathGUI qupath) {
        CompletableFuture.supplyAsync(WelcomeExtension::fetch).thenAccept(json -> {
            if (json == null) return;
            Platform.runLater(() -> {
                if (PlayerBridge.webViewAvailable()) play(qupath, "Welcome to qTrace", WelcomeSequence.of(json), () -> {});
                else new WelcomeDialog(qupath).show(WelcomeSpec.parse(json, WelcomeDialog.ACTIONS));
            });
        });
    }

    /**
     * Plays the welcome of these tools, among the ones that have one (Your tools…, or the click
     * on a button with a gold square). Closing the window is reading: the square goes away.
     */
    private static void playTools(QuPathGUI qupath, Set<String> modules) {
        List<ModuleUpdates.Card> shown = toolCards(modules);
        String json = WelcomeSequence.of(ToolContent.of(toolItems(shown)));
        if (json == null || !PlayerBridge.webViewAvailable()) return;
        play(qupath, toolsTitle(shown), json, () -> markRead(shown));
    }

    /** Extensions > QTrace > What's new… — what the installed versions bring; says so when there is nothing. */
    private static void playNews(QuPathGUI qupath) {
        CompletableFuture.supplyAsync(() -> NewsContent.of(newsItems(moduleCards(), ModuleNews.installed())))
            .exceptionally(e -> null)
            .thenAccept(news -> Platform.runLater(() -> {
                String json = WelcomeSequence.of(news);
                if (json == null || !PlayerBridge.webViewAvailable()) nothingNew(qupath);
                else play(qupath, "What's new in qTrace", json, () -> {});
            }));
    }

    private static List<ModuleUpdates.Card> toolCards(Set<String> modules) {
        return cards.stream().filter(c -> modules.contains(c.offer().module()) && c.hasWelcome()).toList();
    }

    private static String toolsTitle(List<ModuleUpdates.Card> tools) {
        return tools.size() == 1 ? tools.get(0).title() : "Your qTrace tools";
    }

    private static void markRead(List<ModuleUpdates.Card> tools) {
        for (ModuleUpdates.Card c : tools) ToolWelcomes.markRead(c.offer().module(), c.welcome().revision());
    }

    static List<ToolContent.Item> toolItems(List<ModuleUpdates.Card> cards) {
        List<ToolContent.Item> out = new ArrayList<>();
        for (ModuleUpdates.Card c : cards) {
            out.add(new ToolContent.Item(c.offer().module(), c.title(), c.tagline(), c.offer().version(), c.icon(),
                c.features(), c.welcome().slides().stream()
                    .map(s -> new ToolContent.Slide(s.title(), s.text(), s.image())).toList()));
        }
        return out;
    }

    /** The cards of these modules whose served version is the one installed here. */
    static List<NewsContent.Item> newsItems(List<ModuleUpdates.Card> cards, Map<String, String> modules) {
        List<NewsContent.Item> out = new ArrayList<>();
        for (ModuleUpdates.Card c : cards) {
            String installed = modules.get(c.offer().module());
            if (installed == null || !installed.equals(c.offer().version())) continue;
            out.add(new NewsContent.Item(c.offer().module(), c.title(), installed, c.icon(), c.whatsNew()));
        }
        return out;
    }

    private static void nothingNew(QuPathGUI qupath) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        if (qupath.getStage() != null) a.initOwner(qupath.getStage());
        a.setTitle("qTrace — What's new");
        a.setHeaderText("Nothing new to show");
        a.setContentText("No note was published for the versions installed here, or qtrace.ca could not be reached.");
        a.show();
    }

    /**
     * One welcome window, played by the HTML player served by qtrace.ca, with every action a
     * welcome may ask for. {@code closed} runs once the window is closed.
     */
    private static void play(QuPathGUI qupath, String title, String json, Runnable closed) {
        PlayerBridge bridge = new PlayerBridge();
        PlayerWindow w = new PlayerWindow(qupath.getStage(), title, SERVER + "/player/player.html", json, bridge);
        bridge.on("close", a -> w.close())
            .on("open-url", BrowserOpener::open)
            .on("open-player", a -> {
                QTraceController c = QTraceController.current();
                if (c != null) c.openReplayDialog();
            })
            .on("issue-report", a -> IssueReportDialog.show(qupath))
            .on("unlock-key", a -> {
                QTracePlugin p = QTracePluginManager.getEntitled();
                if (p != null) p.promptPassphraseAndDecrypt(w.stage());
            });
        w.stage().setOnHidden(e -> closed.run());
        w.show();
    }

    /** GET /api/onboarding?v=<this module's version>, or null (offline, inactive license). */
    private static String fetch() {
        try {
            String jwt = QTraceUpdater.licenseJwt();
            if (jwt == null) return null;
            byte[] b = QTraceUpdater.httpGetBytes(SERVER + "/api/onboarding?v=" + version(), jwt);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.info(TAG + "welcome unavailable: {}", e.toString());
            return null;
        }
    }

    static String version() {
        String v = WelcomeExtension.class.getPackage().getImplementationVersion();
        return v != null ? v : "1.0.0";
    }

    private static boolean seen() {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(STATE)).getAsJsonObject();
            return o.has("seen") && o.get("seen").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static void markSeen() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("seen", true);
            Files.createDirectories(STATE.getParent());
            Files.writeString(STATE, new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception ignored) {}
    }

    @Override
    public String getName() {
        return "qTrace Welcome";
    }

    @Override
    public String getDescription() {
        return "Your qTrace welcome: the first steps chosen for you and your organization.";
    }
}
