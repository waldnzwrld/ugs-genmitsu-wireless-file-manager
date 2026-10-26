package com.willwinder.ugs.nbp.genmitsusd;

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.lib.lookup.CentralLookup;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import com.willwinder.universalgcodesender.model.BackendAPI;
import org.netbeans.api.settings.ConvertAsProperties;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Window for the Genmitsu module's SD card: list / upload / delete files, load one, and
 * start / pause / resume / stop it. "Load" mirrors the vendor app's (confusingly named)
 * Upload button: it only picks the file that Start will run; nothing is sent to the module.
 */
@ConvertAsProperties(dtd = "-//com.willwinder.ugs.nbp.genmitsusd//GenmitsuSd//EN", autostore = false)
@TopComponent.Description(preferredID = "GenmitsuSdTopComponent", persistenceType = TopComponent.PERSISTENCE_ALWAYS)
@TopComponent.Registration(mode = "output", openAtStartup = false)
@ActionID(category = LocalizingService.CATEGORY_WINDOW, id = "com.willwinder.ugs.nbp.genmitsusd.GenmitsuSdTopComponent")
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(displayName = "Genmitsu SD Card", preferredID = "GenmitsuSdTopComponent")
public final class GenmitsuSdTopComponent extends TopComponent {
    private final BackendAPI backend = CentralLookup.getDefault().lookup(BackendAPI.class);
    private final GenmitsuModule module = new GenmitsuModule(backend);
    // One background worker: module exchanges are serialized anyway, and order matters.
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "genmitsu-sd"));

    private final FileTableModel files = new FileTableModel();
    private final JTable table = new JTable(files);
    private final JLabel loadedLabel = new JLabel("none");
    private final JLabel jobInfo = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final List<JButton> busyButtons = new ArrayList<>();
    private String loaded;
    private volatile boolean polling;

    public GenmitsuSdTopComponent() {
        setName("Genmitsu SD Card");
        setToolTipText("Files on the Genmitsu Wi-Fi module's SD card");
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel job = new JPanel();
        job.setLayout(new BoxLayout(job, BoxLayout.X_AXIS));
        job.add(new JLabel("Loaded: "));
        job.add(loadedLabel);
        job.add(Box.createHorizontalStrut(12));
        job.add(jobInfo);
        job.add(Box.createHorizontalGlue());
        job.add(button("Start", false, this::start));
        job.add(button("Pause", false, () -> control("rp", "Paused")));
        job.add(button("Resume", false, () -> control("rc", "Resumed")));
        JButton stop = button("Stop", false, () -> control("ed", "Stopped"));
        stop.setForeground(new java.awt.Color(0xf7768e));
        job.add(stop);
        add(job, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(1).setMaxWidth(110);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(button("Refresh", true, this::refresh));
        actions.add(button("Upload…", true, this::upload));
        actions.add(button("Load", true, this::load));
        actions.add(button("Delete", true, this::delete));
        progressBar.setVisible(false);
        actions.add(progressBar);
        actions.add(status);
        add(actions, BorderLayout.SOUTH);
    }

    private JButton button(String label, boolean disableWhileBusy, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        if (disableWhileBusy) {
            busyButtons.add(b);
        }
        return b;
    }

    @Override
    public void componentOpened() {
        refresh();
    }

    // --- actions (EDT) -------------------------------------------------------------------

    private void refresh() {
        run("Reading SD card…", () -> {
            List<GenmitsuModule.SdFile> list = module.list();
            ui(() -> {
                files.set(list);
                if (loaded != null && list.stream().noneMatch(f -> f.fname().equals(loaded))) {
                    setLoaded(null);
                }
                say(list.size() + " files");
            });
        });
    }

    private void upload() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("G-code", "nc", "gcode", "ngc", "tap", "fnc", "txt"));
        File current = backend.getGcodeFile();
        if (current != null) {
            chooser.setSelectedFile(current); // default to the file open in UGS
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<File> chosen = new ArrayList<>();
        for (File f : chooser.getSelectedFiles().length > 0 ? chooser.getSelectedFiles() : new File[]{chooser.getSelectedFile()}) {
            if (files.has(f.getName()) && !confirm("\"" + f.getName() + "\" is already on the card. Overwrite?")) {
                continue;
            }
            chosen.add(f);
        }
        if (chosen.isEmpty()) {
            return;
        }
        run("Uploading…", () -> {
            for (File f : chosen) {
                byte[] data = Files.readAllBytes(f.toPath());
                ui(() -> {
                    progressBar.setValue(0);
                    progressBar.setVisible(true);
                    say("Uploading " + f.getName() + "…");
                });
                try {
                    module.upload(f.getName(), data, done -> ui(() -> {
                        progressBar.setValue((int) (1000 * done / Math.max(1, data.length)));
                        say(String.format(Locale.ROOT, "Uploading %s… %d%%", f.getName(), 100 * done / Math.max(1, data.length)));
                    }));
                } finally {
                    ui(() -> progressBar.setVisible(false));
                }
            }
            List<GenmitsuModule.SdFile> list = module.list();
            ui(() -> {
                files.set(list);
                say("Uploaded " + chosen.size() + (chosen.size() == 1 ? " file" : " files"));
            });
        });
    }

    private void load() {
        GenmitsuModule.SdFile f = selected();
        if (f != null) {
            setLoaded(f.fname());
            say("Loaded " + f.fname());
        }
    }

    private void delete() {
        GenmitsuModule.SdFile f = selected();
        if (f == null || !confirm("Delete \"" + f.fname() + "\" from the SD card?")) {
            return;
        }
        run("Deleting " + f.fname() + "…", () -> {
            module.remove(f.fname());
            List<GenmitsuModule.SdFile> list = module.list();
            ui(() -> {
                files.set(list);
                if (f.fname().equals(loaded)) {
                    setLoaded(null);
                }
                say("Deleted " + f.fname());
            });
        });
    }

    private void start() {
        if (loaded == null) {
            say("Load a file first");
            return;
        }
        String fname = loaded;
        if (!confirm("Start \"" + fname + "\" from the SD card? The machine will move.")) {
            return;
        }
        run("Starting " + fname + "…", () -> {
            module.control("be", fname);
            ui(() -> say("Started " + fname));
            pollProgress();
        });
    }

    private void control(String cmd, String done) {
        run(null, () -> {
            module.control(cmd, null);
            ui(() -> say(done));
            if (!"ed".equals(cmd)) {
                pollProgress();
            }
        });
    }

    // The module only answers po while a job runs, so poll until it goes quiet.
    private void pollProgress() {
        if (polling) {
            return;
        }
        polling = true;
        worker.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject p = module.progress();
                    if (p == null) {
                        stopPolling();
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (String k : new String[]{"state", "progress", "prog", "time", "total"}) {
                        if (p.has(k)) {
                            sb.append(sb.length() > 0 ? " · " : "").append(k).append(": ").append(p.get(k).getAsString());
                        }
                    }
                    ui(() -> jobInfo.setText(sb.toString()));
                    worker.schedule(this, 3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    stopPolling();
                }
            }

            private void stopPolling() {
                polling = false;
                ui(() -> jobInfo.setText(" "));
            }
        });
    }

    // --- plumbing ------------------------------------------------------------------------

    private interface Task {
        void run() throws Exception;
    }

    private void run(String busyText, Task task) {
        if (busyText != null) {
            setBusy(true);
            say(busyText);
        }
        worker.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                ui(() -> say("Error: " + e.getMessage()));
            } finally {
                if (busyText != null) {
                    ui(() -> setBusy(false));
                }
            }
        });
    }

    private void setBusy(boolean busy) {
        busyButtons.forEach(b -> b.setEnabled(!busy));
    }

    private void setLoaded(String fname) {
        loaded = fname;
        loadedLabel.setText(fname == null ? "none" : fname);
        files.setLoaded(fname);
    }

    private GenmitsuModule.SdFile selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            say("Select a file first");
            return null;
        }
        return files.at(row);
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "Genmitsu SD Card", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private void say(String text) {
        status.setText(text);
    }

    private static void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    void writeProperties(java.util.Properties p) {
        p.setProperty("version", "1.0");
    }

    void readProperties(java.util.Properties p) {
    }

    private static final class FileTableModel extends AbstractTableModel {
        private List<GenmitsuModule.SdFile> rows = List.of();
        private String loaded;

        void set(List<GenmitsuModule.SdFile> list) {
            rows = list.stream().sorted(Comparator.comparing(GenmitsuModule.SdFile::fname, String.CASE_INSENSITIVE_ORDER)).toList();
            fireTableDataChanged();
        }

        void setLoaded(String fname) {
            loaded = fname;
            fireTableDataChanged();
        }

        boolean has(String fname) {
            return rows.stream().anyMatch(f -> f.fname().equals(fname));
        }

        GenmitsuModule.SdFile at(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Name" : "Size";
        }

        @Override
        public Object getValueAt(int r, int c) {
            GenmitsuModule.SdFile f = rows.get(r);
            if (c == 0) {
                return f.fname().equals(loaded) ? f.fname() + "  ● loaded" : f.fname();
            }
            long n = f.bytes();
            return n < 1000 ? n + " B" : n < 1_000_000 ? String.format(Locale.ROOT, "%.1f kB", n / 1e3) : String.format(Locale.ROOT, "%.2f MB", n / 1e6);
        }
    }
}
