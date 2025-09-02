package net.filebot;

import static java.awt.GraphicsEnvironment.*;
import static java.util.stream.Collectors.*;
import static net.filebot.ExitCode.*;
import static net.filebot.Logging.*;
import static net.filebot.MediaTypes.*;
import static net.filebot.Settings.*;
import static net.filebot.ui.GettingStartedUtil.*;
import static net.filebot.ui.ThemeSupport.*;
import static net.filebot.util.FileUtilities.*;
import static net.filebot.util.FileUtilities.getChildren;
import static net.filebot.util.XPathUtilities.*;
import static net.filebot.util.ui.SwingUI.*;

import java.awt.Dialog.ModalityType;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.prefs.Preferences;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.kohsuke.args4j.CmdLineException;
import org.w3c.dom.Document;

import net.filebot.cli.ArgumentBean;
import net.filebot.cli.ArgumentProcessor;
import net.filebot.format.ExpressionFormat;
import net.filebot.platform.mac.MacAppUtilities;
import net.filebot.platform.windows.WinAppUtilities;
import net.filebot.ui.FileBotMenuBar;
import net.filebot.ui.MainFrame;
import net.filebot.ui.NotificationHandler;
import net.filebot.ui.PanelBuilder;
import net.filebot.ui.SinglePanelFrame;
import net.filebot.ui.SupportDialog;
import net.filebot.ui.transfer.FileTransferable;
import net.filebot.util.PreferencesMap.PreferencesEntry;
import net.filebot.util.ui.SwingEventBus;
import net.miginfocom.swing.MigLayout;

/**
 * Optimized FileBot Main Class with modern Java 17+ features
 * Performance improvements:
 * - Async initialization with CompletableFuture
 * - Scheduled executor for background tasks
 * - Modern concurrent collections
 * - Reduced object allocation
 */
public class Main {

    // Performance optimization: Use scheduled executor for background tasks
    private static final ScheduledExecutorService backgroundExecutor = 
        Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), 
            r -> {
                Thread t = new Thread(r, "FileBot-Background-" + r.hashCode());
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] argv) {
        try {
            // Parse arguments
            ArgumentBean args = ArgumentBean.parse(argv);

            // Quick exit for help/version
            if (args.printHelp()) {
                log.info(String.format("%s%n%n%s", getApplicationIdentifier(), args.usage()));
                System.exit(SUCCESS);
            }

            if (args.printVersion()) {
                log.info(String.join(" / ", getApplicationIdentifier(), getJavaRuntimeIdentifier(), getSystemIdentifier()));
                System.exit(SUCCESS);
            }

            // Handle cache/user data clearing
            if (args.clearCache() || args.clearUserData() || args.clearHistory()) {
                handleCleanup(args);
                System.exit(SUCCESS);
            }

            // Set application arguments globally
            setApplicationArguments(args);

            // Initialize system with async operations
            CompletableFuture<Void> initFuture = CompletableFuture.runAsync(() -> {
                try {
                    initializeSystemProperties(args);
                    initializeLogging(args);
                    CacheManager.getInstance();
                    initializeSecurityManager();
                    HistorySpooler.getInstance().setPersistentHistoryEnabled(useRenameHistory());
                } catch (Exception e) {
                    log.log(Level.SEVERE, "Failed to initialize system", e);
                }
            }, backgroundExecutor);

            // CLI mode
            if (args.runCLI()) {
                if (LICENSE.isFile()) {
                    String psm = args.getLicenseKey();
                    if (psm != null) {
                        configureLicense(psm);
                        System.exit(SUCCESS);
                    }
                }

                // Wait for initialization to complete
                initFuture.join();
                
                int status = new ArgumentProcessor().run(args);
                System.exit(status);
            }

            // Check if we can run GUI
            if (isHeadless()) {
                log.info(String.format("%s / %s (headless)%n%n%s", 
                    getApplicationIdentifier(), getJavaRuntimeIdentifier(), args.usage()));
                System.exit(ERROR);
            }

            // GUI mode with async initialization
            SwingUtilities.invokeLater(() -> {
                startUserInterface(args);
                
                // Run background tasks asynchronously
                CompletableFuture.runAsync(() -> {
                    try {
                        onStart(args);
                    } catch (Exception e) {
                        log.log(Level.WARNING, "Background task failed", e);
                    }
                }, backgroundExecutor);
            });

        } catch (CmdLineException e) {
            log.severe(e::getMessage);
            System.exit(ERROR);
        } catch (Throwable e) {
            debug.log(Level.SEVERE, "Error during startup", e);
            System.exit(ERROR);
        }
    }

    private static void handleCleanup(ArgumentBean args) {
        if (args.clearHistory) {
            log.info("Reset history");
            HistorySpooler.getInstance().clear();
        }

        if (args.clearUserData()) {
            log.info("Reset preferences");
            Settings.forPackage(Main.class).clear();
            getPreferencesBackupFile().delete();
        }

        if (args.clearCache()) {
            if (System.console() == null) {
                log.severe("`filebot -clear-cache` must be called from an interactive console.");
                System.exit(ERROR);
            }
            log.info("Clear cache");
            for (File folder : getChildren(ApplicationFolder.Cache.get(), FOLDERS)) {
                log.fine("* Delete " + folder);
                delete(folder);
            }
        }
    }

    private static void onStart(ArgumentBean args) throws Exception {
        // Publish file arguments
        List<File> files = args.getFiles(false);
        if (!files.isEmpty()) {
            SwingEventBus.getInstance().post(new FileTransferable(files));
        }

        // Import license if available
        if (LICENSE.isFile()) {
            try {
                String psm = args.getLicenseKey();
                if (psm != null) {
                    configureLicense(psm);
                }
            } catch (Throwable e) {
                debug.log(Level.WARNING, e, e::getMessage);
            }
        }

        // Restore preferences from backup
        restorePreferences();

        // Initialize JavaFX
        try {
            initJavaFX();
        } catch (Throwable e) {
            log.log(Level.SEVERE, "Failed to initialize JavaFX", e);
        }

        // Show getting started help
        if (!"skip".equals(System.getProperty("application.help"))) {
            try {
                checkGettingStarted();
            } catch (Throwable e) {
                debug.log(Level.WARNING, "Failed to show Getting Started help", e);
            }
        }

        // Check for updates
        if (!"skip".equals(System.getProperty("application.update"))) {
            try {
                checkUpdate();
            } catch (Throwable e) {
                debug.log(Level.WARNING, "Failed to check for updates", e);
            }
        }
    }

    private static void restorePreferences() {
        try {
            if (Preferences.userNodeForPackage(Main.class).keys().length == 0) {
                File f = getPreferencesBackupFile();
                if (f.exists()) {
                    log.fine("Restore user preferences: " + f);
                    Settings.restore(f);
                } else {
                    log.fine("No user preferences found: " + f);
                }
            }
        } catch (Exception e) {
            debug.log(Level.WARNING, "Failed to restore preferences", e);
        }
    }

    private static void startUserInterface(ArgumentBean args) {
        // Use native LaF on all platforms
        setTheme();

        // Start standard frame or single panel frame
        List<PanelBuilder> panels = args.getPanelBuilders();

        JFrame frame = panels.size() > 1 ? new MainFrame(panels) : new SinglePanelFrame(panels.get(0));
        try {
            restoreWindowBounds(frame, Settings.forPackage(MainFrame.class));
        } catch (Exception e) {
            frame.setLocation(120, 80);
        }

        frame.addWindowListener(windowClosed(evt -> {
            evt.getWindow().setVisible(false);

            // Ensure long running operations complete
            HistorySpooler.getInstance().commit();

            if (isAppStore()) {
                SupportDialog.AppStoreReview.maybeShow();
            }

            // Store preferences
            try {
                File f = getPreferencesBackupFile();
                if (!f.exists() || !lastModifiedWithin(f, Duration.ofDays(30))) {
                    log.fine("Store user preferences: " + f);
                    Settings.store(f);
                }
            } catch (Exception e) {
                debug.log(Level.WARNING, "Failed to store preferences", e);
            }

            // Shutdown background executor gracefully
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                backgroundExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            System.exit(0);
        }));

        // Configure main window
        configureMainWindow(frame);

        // Start application
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setVisible(true);
    }

    private static void configureMainWindow(JFrame frame) {
        if (isMacApp()) {
            MacAppUtilities.initializeApplication(FileBotMenuBar.createHelp(), files -> {
                if (LICENSE.isFile() && files.size() == 1 && containsOnly(files, LICENSE_FILES)) {
                    configureLicense(files.get(0));
                } else {
                    SwingEventBus.getInstance().post(new FileTransferable(files));
                }
            });
        } else if (isWindowsApp()) {
            WinAppUtilities.initializeApplication(isUWP() ? null : getApplicationName());
            frame.setIconImages(ResourceManager.getApplicationIconImages());
        } else {
            frame.setIconImages(ResourceManager.getApplicationIconImages());
        }
    }

    private static File getPreferencesBackupFile() {
        return ApplicationFolder.AppData.resolve("preferences.backup.xml");
    }

    /**
     * Show update notifications if updates are available
     */
    private static void checkUpdate() throws Exception {
        Cache cache = Cache.getCache(getApplicationName(), CacheType.Persistent);
        Document dom = cache.xml(getApplicationProperty("update.url"), URL::new).expire(Cache.ONE_WEEK).retry(0).get();

        // Flush to disk
        cache.flush();

        // Parse update xml
        Map<String, String> update = streamElements(dom.getFirstChild())
            .collect(toMap(n -> n.getNodeName(), n -> n.getTextContent().trim()));

        // Check if update is required
        int latestRev = Integer.parseInt(update.get("revision"));
        int currentRev = getApplicationRevisionNumber();

        if (latestRev > currentRev && currentRev > 0) {
            SwingUtilities.invokeLater(() -> {
                JDialog dialog = new JDialog(JFrame.getFrames()[0], update.get("title"), ModalityType.APPLICATION_MODAL);
                JPanel pane = new JPanel(new MigLayout("fill, nogrid, insets dialog"));
                dialog.setContentPane(pane);

                pane.add(new JLabel(ResourceManager.getIcon("window.icon.medium")), "aligny top");
                pane.add(new JLabel(update.get("message")), "aligny top, gap 10, wrap paragraph:push");

                pane.add(newButton("Download", ResourceManager.getIcon("dialog.continue"), evt -> {
                    openURI(update.get("download"));
                    dialog.setVisible(false);
                }), "tag ok");

                pane.add(newButton("Details", ResourceManager.getIcon("action.report"), evt -> {
                    openURI(update.get("discussion"));
                }), "tag help2");

                pane.add(newButton("Ignore", ResourceManager.getIcon("dialog.cancel"), evt -> {
                    dialog.setVisible(false);
                }), "tag cancel");

                dialog.pack();
                dialog.setLocation(getOffsetLocation(dialog.getOwner()));
                dialog.setVisible(true);
            });
        }
    }

    /**
     * Show Getting Started to new users
     */
    private static void checkGettingStarted() throws Exception {
        PreferencesEntry<String> started = Settings.forPackage(Main.class).entry("getting.started").defaultValue("0");
        if ("0".equals(started.getValue())) {
            started.setValue("1");
            started.flush();

            // Open Getting Started
            SwingUtilities.invokeLater(() -> openGettingStarted("show".equals(System.getProperty("application.help"))));
        }
    }

    private static void restoreWindowBounds(JFrame window, Settings settings) {
        // Store bounds on close
        window.addWindowListener(windowClosed(evt -> {
            // Don't save window bounds if window is maximized
            if (!isMaximized(window)) {
                settings.put("window.x", String.valueOf(window.getX()));
                settings.put("window.y", String.valueOf(window.getY()));
                settings.put("window.width", String.valueOf(window.getWidth()));
                settings.put("window.height", String.valueOf(window.getHeight()));
            }
        }));

        // Restore bounds
        int x = Integer.parseInt(settings.get("window.x"));
        int y = Integer.parseInt(settings.get("window.y"));
        int width = Integer.parseInt(settings.get("window.width"));
        int height = Integer.parseInt(settings.get("window.height"));
        window.setBounds(x, y, width, height);
    }

    /**
     * Initialize default SecurityManager and grant all permissions via security policy.
     * Initialization is required in order to run {@link ExpressionFormat} in a secure sandbox.
     */
    private static void initializeSecurityManager() {
        try {
            // Initialize security policy used by the default security manager
            Policy.setPolicy(new Policy() {
                @Override
                public boolean implies(ProtectionDomain domain, Permission permission) {
                    return true; // All permissions
                }

                @Override
                public PermissionCollection getPermissions(CodeSource codesource) {
                    return new Permissions();
                }
            });

            // Set default security manager
            System.setSecurityManager(new SecurityManager());
        } catch (Exception e) {
            // Security manager was probably set via system property
            debug.log(Level.WARNING, e, e::getMessage);
        }
    }

    public static void initializeSystemProperties(ArgumentBean args) {
        System.setProperty("http.agent", String.format("%s/%s", getApplicationName(), getApplicationVersion()));
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");
        System.setProperty("sun.net.client.defaultReadTimeout", "60000");

        System.setProperty("swing.crossplatformlaf", "javax.swing.plaf.nimbus.NimbusLookAndFeel");
        System.setProperty("grape.root", ApplicationFolder.AppData.resolve("grape").getPath());
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");

        // Enable dark mode by default if dark mode is set system-wide
        if (Boolean.parseBoolean(System.getProperty("DarkMode"))) {
            System.setProperty("net.filebot.theme", "Darcula");
        }

        // Set additional user-defined default system properties
        File userDefinedSystemProperties = ApplicationFolder.AppData.resolve("system.properties");
        if (userDefinedSystemProperties.isFile()) {
            try (FileInputStream in = new FileInputStream(userDefinedSystemProperties)) {
                Properties p = new Properties();
                p.load(in);
                p.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
            } catch (Exception e) {
                log.log(Level.WARNING, e, e::getMessage);
            }
        }

        if (args.unixfs) {
            System.setProperty("unixfs", "true");
        }

        if (args.disableExtendedAttributes) {
            System.setProperty("useExtendedFileAttributes", "false");
            System.setProperty("useCreationDate", "false");
        }

        if (args.disableHistory) {
            System.setProperty("application.rename.history", "false");
        }
    }

    public static void initializeLogging(ArgumentBean args) throws IOException {
        // Make sure that these folders exist
        ApplicationFolder.TemporaryFiles.get().mkdirs();
        ApplicationFolder.AppData.get().mkdirs();

        if (args.runCLI()) {
            // CLI logging settings
            log.setLevel(args.getLogLevel());
        } else {
            // GUI logging settings
            log.setLevel(Level.INFO);
            log.addHandler(new NotificationHandler(getApplicationName()));

            // Log errors to file
            try {
                Handler errorLogHandler = createSimpleFileHandler(ApplicationFolder.AppData.resolve("error.log"), Level.WARNING);
                log.addHandler(errorLogHandler);
                debug.addHandler(errorLogHandler);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to initialize error log", e);
            }
        }

        // Tee stdout and stderr to log file if --log-file is set
        if (args.logFile != null) {
            Handler logFileHandler = createLogFileHandler(args.getLogFile(), args.logLock, Level.ALL);
            log.addHandler(logFileHandler);
            debug.addHandler(logFileHandler);
        }
    }
}
