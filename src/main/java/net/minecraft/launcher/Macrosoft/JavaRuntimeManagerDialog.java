package net.minecraft.launcher.Macrosoft;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JavaRuntimeManagerDialog extends JDialog {

    public interface InstallationListener {
        void onInstalled(JavaRuntimeManager.InstalledRuntime runtime) throws Exception;
    }

    // ── Cores (espelham MacrosoftModpackBrowser) ───────────────────────────
    private static final Color BG          = new Color(22, 13, 28);
    private static final Color PANEL_BG    = new Color(35, 22, 45);
    private static final Color BORDER_CLR  = new Color(70, 50, 90);
    private static final Color TEXT_WHITE  = Color.WHITE;
    private static final Color TEXT_TEAL   = new Color(1, 131, 129);
    private static final Color TEXT_GRAY   = new Color(170, 170, 170);
    private static final Color SEL_BG      = new Color(70, 50, 100);
    private static final Color BTN_INSTALL = new Color(0, 110, 75);
    private static final Color BTN_REMOVE  = new Color(180, 40, 40);
    private static final Color BTN_NEUTRAL = new Color(55, 90, 140);
    private static final Color BTN_CLOSE   = new Color(55, 50, 65);

    // ── Estado ─────────────────────────────────────────────────────────────
    private final Path macrosoftBaseDir;
    private final List<JavaRuntimeManager.JavaRuntimeOption> platformOptions;
    private final InstallationListener installationListener;

    private final DefaultListModel<String> listModel    = new DefaultListModel<>();
    private final JList<String>            runtimeList  = new JList<>(listModel);
    private       List<Row>                rows         = new ArrayList<>();
    private final JProgressBar             installProgress = new JProgressBar(0, 100);
    private final JLabel                   progressLabel   = new JLabel("Pronto");

    private static class Row {
        final JavaRuntimeManager.JavaRuntimeOption  option;
        final JavaRuntimeManager.InstalledRuntime   installed;
        Row(JavaRuntimeManager.JavaRuntimeOption o, JavaRuntimeManager.InstalledRuntime i) {
            this.option = o; this.installed = i;
        }
    }

    // ── Construtor ─────────────────────────────────────────────────────────
    public JavaRuntimeManagerDialog(JFrame owner,
                                    Path macrosoftBaseDir,
                                    List<JavaRuntimeManager.JavaRuntimeOption> allApiOptions) {
        this(owner, macrosoftBaseDir, allApiOptions, null);
    }

    public JavaRuntimeManagerDialog(JFrame owner,
                                    Path macrosoftBaseDir,
                                    List<JavaRuntimeManager.JavaRuntimeOption> allApiOptions,
                                    InstallationListener installationListener) {
        super(owner, "Gerenciar Java (Macrosoft)", true);
        this.macrosoftBaseDir = macrosoftBaseDir;
        this.platformOptions  = JavaRuntimeManager.filterForCurrentPlatform(allApiOptions);
        this.installationListener = installationListener;

        // ── Painel raiz ────────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(8, 10));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        // ── Cabeçalho ──────────────────────────────────────────────────────
        JLabel desc = new JLabel("Selecione um Java da plataforma atual para instalar/remover.");
        desc.setForeground(TEXT_TEAL);
        desc.setFont(desc.getFont().deriveFont(Font.BOLD, 13f));

        // ── Aviso informativo ──────────────────────────────────────────────
        JLabel notice = new JLabel(
            "<html><b>ℹ️ Aviso:</b> As instalações de Java <b>não afetam o sistema operacional</b>. " +
            "Elas ficam contidas exclusivamente em <code>.macrosoft/.java/</code> " +
            "e são utilizadas apenas por este launcher.</html>"
        );
        notice.setForeground(new Color(200, 185, 220));
        notice.setFont(notice.getFont().deriveFont(Font.PLAIN, 11f));
        notice.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_CLR),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        notice.setOpaque(true);
        notice.setBackground(PANEL_BG);

        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.setBackground(BG);
        header.add(desc,   BorderLayout.NORTH);
        header.add(notice, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        // ── Lista ──────────────────────────────────────────────────────────
        runtimeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runtimeList.setVisibleRowCount(8);
        runtimeList.setBackground(PANEL_BG);
        runtimeList.setForeground(TEXT_WHITE);
        runtimeList.setSelectionBackground(SEL_BG);
        runtimeList.setSelectionForeground(TEXT_WHITE);
        runtimeList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        runtimeList.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        runtimeList.setCellRenderer(new DarkListCellRenderer());

        JScrollPane scroll = new JScrollPane(runtimeList);
        scroll.getViewport().setBackground(PANEL_BG);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        scroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        scroll.getHorizontalScrollBar().setUI(new DarkScrollBarUI());

        // ── Barra de progresso ─────────────────────────────────────────────
        installProgress.setStringPainted(true);
        installProgress.setValue(0);
        installProgress.setString("Pronto");
        installProgress.setForeground(TEXT_TEAL);
        installProgress.setBackground(new Color(40, 28, 52));
        installProgress.setBorderPainted(false);
        installProgress.setFont(new Font("SansSerif", Font.BOLD, 11));

        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.PLAIN, 12f));
        progressLabel.setForeground(TEXT_GRAY);

        JPanel progressPanel = new JPanel(new BorderLayout(0, 4));
        progressPanel.setBackground(BG);
        progressPanel.add(progressLabel,    BorderLayout.NORTH);
        progressPanel.add(installProgress,  BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setBackground(BG);
        center.add(scroll,         BorderLayout.CENTER);
        center.add(progressPanel,  BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        // ── Botões ─────────────────────────────────────────────────────────
        JButton refreshBtn   = styledButton("↺ Atualizar",    BTN_NEUTRAL);
        JButton installBtn   = styledButton("⬇ Instalar",     BTN_INSTALL);
        JButton uninstallBtn = styledButton("🗑 Desinstalar", BTN_REMOVE);
        JButton closeBtn     = styledButton("Fechar",          BTN_CLOSE);

        refreshBtn.addActionListener(e -> refreshList());
        installBtn.addActionListener(e -> installSelected());
        uninstallBtn.addActionListener(e -> uninstallSelected());
        closeBtn.addActionListener(e -> dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BG);
        actions.add(refreshBtn);
        actions.add(installBtn);
        actions.add(uninstallBtn);
        actions.add(closeBtn);
        root.add(actions, BorderLayout.SOUTH);

        refreshList();
        setSize(720, 380);
        setLocationRelativeTo(owner);
    }

    // ── Lógica (igual à original) ──────────────────────────────────────────
    private void refreshList() {
        listModel.clear();
        rows = new ArrayList<>();
        Map<String, JavaRuntimeManager.InstalledRuntime> installedById =
            JavaRuntimeManager.mapInstalledById(macrosoftBaseDir);

        if (platformOptions.isEmpty()) {
            listModel.addElement("Nenhuma opção de Java disponível para este sistema no JSON do servidor.");
            installProgress.setValue(0);
            installProgress.setString("Sem opções");
            progressLabel.setText("Sem opções para esta plataforma");
            return;
        }

        // Sugestão estética: na janela da lista de javas, deixar a coluna de nomes dos javas com tamanho adaptável (pra não ficar com muitos espaços à direita);
        // 1: Descobrir qual é o maior tamanho de 'id' na lista de nomes dos javas
        int maxIdLength = 0;
        for (JavaRuntimeManager.JavaRuntimeOption option : platformOptions) {
            if (option.id != null && option.id.length() > maxIdLength) {
                maxIdLength = option.id.length();
            }
        }

        // 2: Adicionar um espaço entre a coluna de nomes e a de URLs, para não ficar colado. Sugerido: 4 espaços;
        int columnWidth = maxIdLength + 4;

        // 3: Montar a string de formatação dinamicamente com os tamanhos calculados.
        String dynamicFormat = "%s  %-" + columnWidth + "s  %s";

        for (JavaRuntimeManager.JavaRuntimeOption option : platformOptions) {
            JavaRuntimeManager.InstalledRuntime installed = installedById.get(option.id);
            String status   = (installed == null) ? "[ ]" : "[✔]";
            String line     = String.format(dynamicFormat, status, option.id, urlResourceName(option.url));
            listModel.addElement(line);
            rows.add(new Row(option, installed));
        }

        if (!rows.isEmpty()) runtimeList.setSelectedIndex(0);
        installProgress.setValue(0);
        installProgress.setString("Pronto");
        progressLabel.setText("Pronto");
    }

    private void installSelected() {
        Row row = selectedRow();
        if (row == null) return;
        final JavaRuntimeManager.JavaRuntimeOption option = row.option;

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        runtimeList.setEnabled(false);
        installProgress.setValue(0);
        installProgress.setString("0%");
        progressLabel.setText("Preparando instalação de " + option.id + "...");

        new SwingWorker<JavaRuntimeManager.InstalledRuntime, Void>() {
            @Override
            protected JavaRuntimeManager.InstalledRuntime doInBackground() throws Exception {
                return JavaRuntimeManager.install(macrosoftBaseDir, option, (stage, percent, detail) ->
                    SwingUtilities.invokeLater(() -> {
                        installProgress.setValue(Math.max(0, Math.min(100, percent)));
                        installProgress.setString(percent + "%");
                        String icon = "download".equals(stage) ? "⬇" : "extract".equals(stage) ? "📦" : "✅";
                        progressLabel.setText(icon + "  " + detail);
                    })
                );
            }
            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                runtimeList.setEnabled(true);
                try {
                    JavaRuntimeManager.InstalledRuntime runtime = get();
                    installProgress.setValue(100);
                    installProgress.setString("100%");
                    progressLabel.setText("✅  Instalação concluída");
                    String configuredMessage = "";
                    if (installationListener != null) {
                        try {
                            installationListener.onInstalled(runtime);
                            configuredMessage = "\n\nEste Java foi definido automaticamente no perfil da modpack.";
                        } catch (Exception configError) {
                            JOptionPane.showMessageDialog(JavaRuntimeManagerDialog.this,
                                "O Java foi instalado, mas não foi possível defini-lo no perfil:\n"
                                    + configError.getMessage(),
                                "Java instalado", JOptionPane.WARNING_MESSAGE);
                        }
                    }
                    JOptionPane.showMessageDialog(JavaRuntimeManagerDialog.this,
                        "Java instalado com sucesso:\n" + runtime.javaExecutable + configuredMessage,
                        "Instalação concluída", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    installProgress.setValue(0);
                    installProgress.setString("Erro");
                    progressLabel.setText("❌  Falha na instalação");
                    JOptionPane.showMessageDialog(JavaRuntimeManagerDialog.this,
                        "Falha ao instalar Java:\n" + ex.getMessage(),
                        "Erro", JOptionPane.ERROR_MESSAGE);
                }
                refreshList();
            }
        }.execute();
    }

    private void uninstallSelected() {
        Row row = selectedRow();
        if (row == null) return;
        if (row.installed == null) {
            JOptionPane.showMessageDialog(this, "Esse Java ainda não está instalado.",
                "Desinstalação", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
            "Deseja remover o Java '" + row.installed.id + "'?",
            "Confirmar desinstalação", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            JavaRuntimeManager.uninstall(macrosoftBaseDir, row.installed);
            JOptionPane.showMessageDialog(this, "Java removido com sucesso.",
                "Desinstalação", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Falha ao remover Java:\n" + ex.getMessage(),
                "Erro", JOptionPane.ERROR_MESSAGE);
        }
        refreshList();
    }

    private Row selectedRow() {
        int index = runtimeList.getSelectedIndex();
        if (index < 0 || index >= rows.size()) {
            JOptionPane.showMessageDialog(this, "Selecione uma opção de Java na lista.",
                "Gerenciador de Java", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return rows.get(index);
    }

    // ── Utilitários de UI ──────────────────────────────────────────────────
    private static JButton styledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setForeground(TEXT_WHITE);
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setRolloverEnabled(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setUI(new SolidButtonUI(bg));
        btn.setBorder(BorderFactory.createEmptyBorder(7, 15, 7, 15));
        return btn;
    }

    /** Renderer que coloriza itens instalados em verde teal e não-instalados em cinza. */
    private static class DarkListCellRenderer extends DefaultListCellRenderer {
        private static final Color INSTALLED_FG = new Color(80, 200, 160);
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                       int index, boolean isSelected, boolean cellHasFocus) {
            JLabel lbl = (JLabel) super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus);
            lbl.setBackground(isSelected ? SEL_BG : PANEL_BG);
            String text = value == null ? "" : value.toString();
            lbl.setForeground(isSelected ? TEXT_WHITE
                : text.startsWith("[✔]") ? INSTALLED_FG : TEXT_GRAY);
            lbl.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return lbl;
        }
    }

    /** ScrollBar mínima com fundo escuro. */
    private static class DarkScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor      = new Color(90, 65, 120);
            trackColor      = new Color(40, 28, 52);
            thumbDarkShadowColor = trackColor;
            thumbHighlightColor  = thumbColor;
            thumbLightShadowColor = thumbColor;
        }
        @Override protected JButton createDecreaseButton(int o) { return invisibleButton(); }
        @Override protected JButton createIncreaseButton(int o) { return invisibleButton(); }
        private JButton invisibleButton() {
            JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b;
        }
    }

    // Classe interna para estilos dos botões, para não perder formatação ao abrir janela de configuração do perfil;
    // É uma gambiarra braba;
    private static class SolidButtonUI extends javax.swing.plaf.basic.BasicButtonUI {
        private final Color baseColor;

        public SolidButtonUI(Color baseColor) {
            this.baseColor = baseColor;
        }

        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            c.setOpaque(false);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel model = b.getModel();

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color fill = baseColor;

            if (!b.isEnabled()) {
                fill = baseColor.darker().darker();
            } else if (model.isPressed()) {
                fill = baseColor.darker();
            } else if (model.isRollover()) {
                fill = brighten(baseColor, 18);
            }

            int arc = 8;

            // fundo
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), arc, arc);

            // borda
            g2.setColor(baseColor.darker());
            g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, arc, arc);

            g2.dispose();

            super.paint(g, c);
        }

        @Override
        protected void paintButtonPressed(Graphics g, AbstractButton b) {
            // não deixa o Swing pintar por cima
        }

        private static Color brighten(Color color, int amount) {
            return new Color(
                    Math.min(255, color.getRed() + amount),
                    Math.min(255, color.getGreen() + amount),
                    Math.min(255, color.getBlue() + amount)
            );
        }
    }

    // ── Utilitários ────────────────────────────────────────────────────────
    /**
     * Retorna apenas o nome do recurso de uma URL (último segmento do path).
     * Se o path terminar com '/', usa o penúltimo segmento.
     * Exemplo: "https://host/jre-8u202/jrexpto.tar.gz" → "jrexpto.tar.gz"
     */
    private static String urlResourceName(String url) {
        if (url == null || url.isEmpty()) return url;
        String path = url;
        // remove query string / fragment
        int q = path.indexOf('?'); if (q >= 0) path = path.substring(0, q);
        int f = path.indexOf('#'); if (f >= 0) path = path.substring(0, f);
        // remove trailing slashes
        while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

}
