package net.minecraft.launcher.ui.popups.profile;

import com.mojang.launcher.OperatingSystem;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.minecraft.launcher.profile.Profile;
import net.minecraft.launcher.ui.popups.profile.ProfileEditorPopup;
import net.minecraft.launcher.utils.JavaLocator;

public class ProfileJavaPanel
extends JPanel {
    private final ProfileEditorPopup editor;
    private final JCheckBox javaPathCustom = new JCheckBox("Executável:");
    private final JTextField javaPathField = new JTextField();
    private final JButton detectJavaButton = new JButton("Detectar JAVA");
    private final JCheckBox javaArgsCustom = new JCheckBox("Argumentos JVM:");
    private final JTextField javaArgsField = new JTextField();

    public ProfileJavaPanel(ProfileEditorPopup editor) {
        this.editor = editor;
        this.setLayout(new GridBagLayout());
        this.setBorder(BorderFactory.createTitledBorder("Configurações Java (Avançado)"));

        // Estilo success cross-platform — bypassa o L&F nativo via BasicButtonUI
        detectJavaButton.setForeground(Color.WHITE);
        detectJavaButton.setFont(detectJavaButton.getFont().deriveFont(Font.BOLD, 12f));
        detectJavaButton.setFocusPainted(false);
        detectJavaButton.setOpaque(true);
        detectJavaButton.setContentAreaFilled(false);
        detectJavaButton.setBorderPainted(false);
        detectJavaButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        detectJavaButton.setUI(new SolidButtonUI(new Color(0, 130, 80)));
        detectJavaButton.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));

        this.createInterface();
        this.fillDefaultValues();
        this.addEventHandlers();
    }

    protected void createInterface() {
    	GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 2, 2, 2);
        constraints.anchor = GridBagConstraints.WEST;

        // Linha 0: Checkbox Executable, Campo Executable, Botão Detectar
        constraints.gridy = 0;

        constraints.gridx = 0;
        constraints.weightx = 0.0; // Checkbox não expande
        constraints.fill = GridBagConstraints.NONE;
        add(this.javaPathCustom, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0; // Campo expande
        constraints.fill = GridBagConstraints.HORIZONTAL;
        add(this.javaPathField, constraints);

        constraints.gridx = 2; // Nova coluna para o botão
        constraints.weightx = 0.0; // Botão não expande
        constraints.fill = GridBagConstraints.NONE;
        add(this.detectJavaButton, constraints); // Adiciona o novo botão

        // Linha 1: Checkbox JVM Arguments e Campo JVM Arguments
        constraints.gridy = 1;

        constraints.gridx = 0;
        constraints.gridwidth = 1; // Reset gridwidth
        constraints.weightx = 0.0;
        constraints.fill = GridBagConstraints.NONE;
        add(this.javaArgsCustom, constraints);

        constraints.gridx = 1;
        constraints.gridwidth = 2; // Campo de argumentos ocupa as 2 colunas restantes
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        add(this.javaArgsField, constraints);
    }

    protected void fillDefaultValues() {
        String javaPath = this.editor.getProfile().getJavaPath();
        // Garante que javaPath é diferente de null, e que aponta para um java válido
        // Caso contrário, usa o java do sistema (que foi bem sucedido em abrir o launcher, então deve funcionar)
        if (javaPath != null && new File(javaPath).isFile()) {
            this.javaPathCustom.setSelected(true);
            this.javaPathField.setText(javaPath);
        } else {
            this.javaPathCustom.setSelected(false);
            this.javaPathField.setText(OperatingSystem.getCurrentPlatform().getJavaDir());
        }
        this.updateJavaPathState();
        String args = this.editor.getProfile().getJavaArgs();
        if (args != null) {
            this.javaArgsCustom.setSelected(true);
            this.javaArgsField.setText(args);
        } else {
            this.javaArgsCustom.setSelected(false);
            this.javaArgsField.setText("-Xmx1G -XX:+UseConcMarkSweepGC -XX:+CMSIncrementalMode -XX:-UseAdaptiveSizePolicy -Xmn128M");
        }
        this.updateJavaArgsState();
    }

    protected void addEventHandlers() {
        this.javaPathCustom.addItemListener(new ItemListener(){

            @Override
            public void itemStateChanged(ItemEvent e) {
                ProfileJavaPanel.this.updateJavaPathState();
            }
        });
        this.javaPathField.getDocument().addDocumentListener(new DocumentListener(){

            @Override
            public void insertUpdate(DocumentEvent e) {
                ProfileJavaPanel.this.updateJavaPath();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                ProfileJavaPanel.this.updateJavaPath();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                ProfileJavaPanel.this.updateJavaPath();
            }
        });
        this.javaArgsCustom.addItemListener(new ItemListener(){

            @Override
            public void itemStateChanged(ItemEvent e) {
                ProfileJavaPanel.this.updateJavaArgsState();
            }
        });
        this.javaArgsField.getDocument().addDocumentListener(new DocumentListener(){

            @Override
            public void insertUpdate(DocumentEvent e) {
                ProfileJavaPanel.this.updateJavaArgs();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                ProfileJavaPanel.this.updateJavaArgs();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                ProfileJavaPanel.this.updateJavaArgs();
            }
        });
        
        this.detectJavaButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                detectAndSetJavaPath();
            }
        });
        
     // Listener para o checkbox javaPathCustom (ajustado para não limpar o campo se desmarcado após detecção)
        this.javaPathCustom.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                // A lógica de updateJavaPathState já lida com isso,
                // mas é importante que ela não apague um caminho detectado se o usuário desmarcar
                // e remarcar o checkbox sem intenção de resetar para o padrão do OS.
                updateJavaPathState();
            }
        });
        
     // Listener para o campo javaPathField (para quando o usuário edita manualmente)
        this.javaPathField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { updateJavaPathFromField(); }
            @Override
            public void removeUpdate(DocumentEvent e) { updateJavaPathFromField(); }
            @Override
            public void changedUpdate(DocumentEvent e) { updateJavaPathFromField(); }
        });
        
        
    }
    
    private void detectAndSetJavaPath() {
        // Resolver o macrosoftBaseDir real (diretório ".macrosoft" ao lado do launcher)
        // a partir do working directory do launcher (".macrosoft/<context>"), cujo parent é ".macrosoft".
        Path macrosoftBaseDir = null;
        try {
            java.io.File workDir = editor.getMinecraftLauncher().getLauncher().getWorkingDirectory();
            if (workDir != null) {
                Path parent = workDir.toPath().getParent();
                if (parent != null) {
                    macrosoftBaseDir = parent;
                }
            }
        } catch (Exception ignored) {
            // Fallback: JavaLocator usará heurísticas de user.dir
        }

        List<String> javaPaths = JavaLocator.findJava8Installations(macrosoftBaseDir);

        if (javaPaths.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Nenhuma instalação do Java foi encontrada automaticamente.\n" +
                "Por favor, defina o caminho manualmente ou instale um Java pelo gerenciador.",
                "Detecção de Java", JOptionPane.INFORMATION_MESSAGE);
        } else if (javaPaths.size() == 1) {
            String foundPath = shortenJavaPath(javaPaths.get(0));
            this.javaPathField.setText(foundPath);
            this.javaPathCustom.setSelected(true);
            updateJavaPathState();
            JOptionPane.showMessageDialog(this,
                "Java encontrado e configurado:\n" + foundPath,
                "Detecção de Java", JOptionPane.INFORMATION_MESSAGE);
        } else {
            List<String> shortened = new java.util.ArrayList<>();
            for (String p : javaPaths) shortened.add(shortenJavaPath(p));
            JList<String> list = new JList<>(shortened.toArray(new String[0]));
            JScrollPane scrollPane = new JScrollPane(list);
            scrollPane.setPreferredSize(new Dimension(450, 150));
            int option = JOptionPane.showOptionDialog(
                this,
                scrollPane,
                "Várias instalações do Java encontradas",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null, null, null
            );
            if (option == JOptionPane.OK_OPTION) {
                String selectedPath = list.getSelectedValue();
                if (selectedPath != null) {
                    this.javaPathField.setText(selectedPath);
                    this.javaPathCustom.setSelected(true);
                    updateJavaPathState();
                }
            }
        }
    }

    /**
     * Se o caminho absoluto contiver o segmento ".macrosoft" (seguido de ".java"),
     * retorna o caminho a partir de ".macrosoft" (inclusive).
     * Caso contrário, retorna o caminho original.
     */
    private static String shortenJavaPath(String absolutePath) {
        if (absolutePath == null) return null;
        try {
            Path target = Paths.get(absolutePath).toAbsolutePath().normalize();
            // Percorre os componentes do path procurando ".macrosoft"
            for (int i = 0; i < target.getNameCount(); i++) {
                if (".macrosoft".equals(target.getName(i).toString())) {
                    // Retorna o sub-path a partir de ".macrosoft" inclusive
                    return target.subpath(i, target.getNameCount()).toString();
                }
            }
        } catch (Exception ignored) {}
        return absolutePath;
    }


    private void updateJavaPathFromField() {
        // Este método é chamado quando o usuário digita no campo.
        // Se o checkbox customizado estiver marcado, atualiza o perfil.
        if (this.javaPathCustom.isSelected()) {
            this.editor.getProfile().setJavaDir(this.javaPathField.getText());
        }
    }

    private void updateJavaPathState() {
        if (this.javaPathCustom.isSelected()) {
            this.javaPathField.setEnabled(true);
            // Se o campo estiver vazio ao marcar o checkbox, preenche com o padrão do OS.
            // Caso contrário, mantém o valor atual (que pode ter sido definido pela detecção ou manualmente).
            if (this.javaPathField.getText().trim().isEmpty()) {
                 this.javaPathField.setText(OperatingSystem.getCurrentPlatform().getJavaDir());
            }
            this.editor.getProfile().setJavaDir(this.javaPathField.getText());
        } else {
            this.javaPathField.setEnabled(false);
            // Não limpa o campo de texto, apenas o valor no perfil.
            // O usuário pode querer desmarcar temporariamente.
            this.editor.getProfile().setJavaDir(null);
        }
    }

    private void updateJavaPath() {
        if (this.javaPathCustom.isSelected()) {
            this.editor.getProfile().setJavaDir(this.javaPathField.getText());
        } else {
            this.editor.getProfile().setJavaDir(null);
        }
    }

    /*
    private void updateJavaPathState() {
        if (this.javaPathCustom.isSelected()) {
            this.javaPathField.setEnabled(true);
            this.editor.getProfile().setJavaDir(this.javaPathField.getText());
        } else {
            this.javaPathField.setEnabled(false);
            this.editor.getProfile().setJavaDir(null);
        }
    }*/

    private void updateJavaArgs() {
        if (this.javaArgsCustom.isSelected()) {
            this.editor.getProfile().setJavaArgs(this.javaArgsField.getText());
        } else {
            this.editor.getProfile().setJavaArgs(null);
        }
    }

    private void updateJavaArgsState() {
        if (this.javaArgsCustom.isSelected()) {
            this.javaArgsField.setEnabled(true);
            this.editor.getProfile().setJavaArgs(this.javaArgsField.getText());
        } else {
            this.javaArgsField.setEnabled(false);
            this.editor.getProfile().setJavaArgs(null);
        }
    }

    /**
     * UI de botão sólido com cor customizada — bypassa o L&F nativo,
     * garantindo aparência consistente em Windows, Linux e macOS.
     */
    private static class SolidButtonUI extends javax.swing.plaf.basic.BasicButtonUI {
        private final Color baseColor;

        SolidButtonUI(Color baseColor) { this.baseColor = baseColor; }

        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            c.setOpaque(false);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b   = (AbstractButton) c;
            ButtonModel    model = b.getModel();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color fill = !b.isEnabled()    ? baseColor.darker().darker()
                       : model.isPressed() ? baseColor.darker()
                       : model.isRollover() ? brighten(baseColor, 20)
                       : baseColor;

            int arc = 8;
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), arc, arc);
            g2.setColor(baseColor.darker());
            g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, arc, arc);
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        protected void paintButtonPressed(Graphics g, AbstractButton b) { /* evita repintura do L&F */ }

        private static Color brighten(Color c, int amt) {
            return new Color(Math.min(255, c.getRed()   + amt),
                             Math.min(255, c.getGreen() + amt),
                             Math.min(255, c.getBlue()  + amt));
        }
    }

}
