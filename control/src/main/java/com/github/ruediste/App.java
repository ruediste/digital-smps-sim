package com.github.ruediste;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.inject.Inject;
import javax.swing.*;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.ruediste.mode.Mode;
import com.github.ruediste.mode.Mode.ModeInstance;
import com.github.ruediste.serial.AvailableDevicesAppController;
import com.github.ruediste.serial.RealSerialConnection;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Hello world!
 *
 */
public class App {

    private final Logger log = LoggerFactory.getLogger(App.class);

    @Inject
    UiRefresh refresh;

    @Inject
    AvailableDevicesAppController availableDevicesAppController;

    @Inject
    Injector injector;

    @Inject
    Persistence persistence;

    @Inject
    InterfaceSerializer interfaceSerializer;

    private List<Mode<?>> modes = new ArrayList<>();

    JPanel modeContainer;
    JCheckBox autoSend;

    private class ModeInstanceController<TSettings extends Serializable> {
        ModeInstance<TSettings> instance;
        TSettings settings;

        ModeInstanceController(Mode<TSettings> mode) {
            try {
                settings = (TSettings) persistence.settings.get(mode.name);
            } catch (Exception e) {
                log.error("Unable to load settings for " + mode.name, e);
                persistence.settings.clear();
                persistence.commit();
            }
            if (settings == null)
                settings = mode.createDefaultSettings();

            persistence.currentMode.set(mode.name);
            persistence.commit();
            instance = mode.createInstance();
            modeContainer.removeAll();
            modeContainer.add(instance.initialize(settings, () -> {
                persistence.settings.put(mode.name, settings);
                persistence.commit();
                if (autoSend.isSelected()) {
                    send();
                }
            }));
            modeContainer.revalidate();
            modeContainer.repaint();
        }

        public void send() {
            var msg = instance.toConfigMessage(settings);
            System.out.println("Send " + msg);
            if (con == null) {
                con = new RealSerialConnection(serialConnectionPath.toString(), 115000);
            }
            var baos = new ByteArrayOutputStream();
            interfaceSerializer.serialize(msg, baos);
            try {
                con.sendBytes(baos.toByteArray());
            } catch (Exception e) {
                try {
                    con.close();
                } catch (Exception e1) {
                    // swallow
                }
                con = new RealSerialConnection(serialConnectionPath.toString(), 115000);
                con.sendBytes(baos.toByteArray());
            }
        }
    }

    RealSerialConnection con;
    ModeInstanceController<?> modeInstanceController;

    List<Path> serialConnections;
    Path serialConnectionPath;

    private void createAndShowGUI() {
        // Create and set up the window.
        JFrame frame = new JFrame("FW Controller");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.PAGE_AXIS));
        frame.setContentPane(mainPanel);
        JComboBox<String> modeComboBox;
        {
            JPanel topPanel = new JPanel();
            // topPanel.setLayout(new FlowLayout(topPanel, BoxLayout.LINE_AXIS));
            mainPanel.add(topPanel);

            // mode
            modeComboBox = new JComboBox<String>(modes.stream().map(x -> x.name).toArray(l -> new String[l]));
            modeComboBox.setMaximumSize(modeComboBox.getPreferredSize());
            topPanel.add(modeComboBox);
            modeComboBox.addActionListener(e -> showMode(modes.get(modeComboBox.getSelectedIndex())));

            // config send control
            autoSend = new JCheckBox("Auto Send");
            autoSend.setSelected(persistence.autoSend.get() == Boolean.TRUE);
            autoSend.addActionListener(e -> {
                persistence.autoSend.set(autoSend.isSelected());
                persistence.commit();
                if (autoSend.isSelected())
                    modeInstanceController.send();
            });
            topPanel.add(autoSend);

            JButton send = new JButton("Send");
            send.addActionListener(e -> modeInstanceController.send());
            topPanel.add(send);

            // serial connections
            serialConnections = availableDevicesAppController.getCurrentSerialConnections().stream()
                    .sorted(Comparator.comparing(x -> x.toString())).toList();
            serialConnectionPath = serialConnections.stream().findFirst().orElse(null);
            var connectionsComboBox = new JComboBox<String>(
                    serialConnections.stream().map(x -> x.toString()).toArray(l -> new String[l]));

            topPanel.add(connectionsComboBox);
            connectionsComboBox.addActionListener(e -> {
                con = null;
                serialConnectionPath = serialConnections.get(connectionsComboBox.getSelectedIndex());
            });
            availableDevicesAppController.onChange.add(() -> SwingUtilities.invokeLater(() -> {
                serialConnections = availableDevicesAppController.getCurrentSerialConnections().stream()
                        .sorted(Comparator.comparing(x -> x.toString())).toList();
                connectionsComboBox.setModel(new DefaultComboBoxModel<>(
                        serialConnections.stream().map(x -> x.toString()).toArray(l -> new String[l])));
            }));
        }

        // mode ui
        modeContainer = new JPanel();
        mainPanel.add(modeContainer);

        // load mode
        {
            var modeName = persistence.currentMode.get();
            int index = 0;
            if (modeName != null) {
                for (int i = 0; i < modes.size(); i++) {
                    if (modes.get(i).name.equals(modeName)) {
                        index = i;
                    }
                }
            }
            modeComboBox.setSelectedIndex(index);
            showMode(modes.get(index));
        }

        // Display the window.
        frame.pack();
        frame.setVisible(true);
    }

    public <T extends Serializable> void showMode(Mode<T> mode) {
        this.modeInstanceController = new ModeInstanceController<>(mode);
    }

    public static void main(String[] args) throws IOException {
        var injector = Guice.createInjector(new AbstractModule() {
            @Provides
            public DB provideMapDb() {
                return DBMaker.fileDB("control.db").transactionEnable().make();
            }

        });
        injector.getInstance(App.class).start();

    }

    public void start() throws IOException {
        persistence.initialize();
        availableDevicesAppController.start();

        // scan available modes
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages(Mode.class.getPackageName())
                .scan()) {
            for (var modeInfo : scanResult.getSubclasses(Mode.class)) {
                if (modeInfo.isAbstract())
                    continue;
                modes.add((Mode<?>) injector.getInstance(modeInfo.loadClass()));
            }
            modes.sort(Comparator.comparing(x -> x.name));
        }

        // Schedule a job for the event-dispatching thread:
        // creating and showing this application's GUI.
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createAndShowGUI();
            }
        });
    }
}
