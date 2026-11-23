/*
 * PROTON AI -- single file, no backend.
 * First launch asks for API keys; they are stored locally in ~/.proton-ai.properties.
 * javac main.java && java main
 */

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class main {

    /* ============================================================
     *  PROVIDER / API KEY CONFIGURATION
     *  Keys are entered on first launch and stored locally in
     *  ~/.proton-ai.properties. Nothing is baked into the source.
     * ============================================================ */
    enum Provider { NVIDIA_GROQ, GROQ }

    static volatile Provider provider = Provider.GROQ;
    static volatile String groqKey = "";
    static volatile String nvidiaKey = "";

    static final String NVIDIA_BASE = "https://integrate.api.nvidia.com/v1";
    static final String NVIDIA_MODEL = "nvidia/nemotron-3.5-super-vl-120b-a12b";
    static final String NVIDIA_FALLBACK = "nvidia/nemotron-3-super-120b-a12b";

    /** How long the chrome-less splash stays on screen (spec: 8-10 s). */
    static final int SPLASH_MS = 9000;

    public static void main(String[] args) {
        System.setProperty("swing.aatext", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");

        EventQueue.invokeLater(() -> {
            // 1) Square 420x420 chrome-less splash, centered on screen.
            final LoadingWindow loading = new LoadingWindow(SPLASH_MS);
            loading.setVisible(true);
            loading.toFront();
            loading.start();

            // 2) After the splash: first-run setup, or straight into the app.
            javax.swing.Timer timer = new javax.swing.Timer(SPLASH_MS, e -> {
                loading.dispose();
                loadSavedConfig();
                if (hasSavedConfig()) {
                    launchMain();
                } else {
                    new SetupWindow(false).setVisible(true);
                }
            });
            timer.setRepeats(false);
            timer.start();
        });
    }

    static void loadSavedConfig() {
        String p = Prefs.getString("provider", "");
        groqKey = Prefs.getString("groqKey", "");
        nvidiaKey = Prefs.getString("nvidiaKey", "");
        if (p.isEmpty()) p = nvidiaKey.isEmpty() ? "groq" : "nvidia+groq";
        provider = ("nvidia+groq".equals(p) && !nvidiaKey.isEmpty())
                ? Provider.NVIDIA_GROQ : Provider.GROQ;
    }

    static boolean hasSavedConfig() {
        return !groqKey.isEmpty() && !groqKey.startsWith("PASTE");
    }

    static void launchMain() {
        ProtonFrame frame;
        try {
            frame = new ProtonFrame();
        } catch (Throwable t) {
            t.printStackTrace();
            JOptionPane.showMessageDialog(null, "Could not start PROTON:\n" + t,
                    "PROTON", JOptionPane.ERROR_MESSAGE);
            return;
        }
        showWithFade(frame);
        UpdateKit.startAutoWatch();
    }

    static void restartApp() {
        try {
            // 1) Packaged Hermes-style EXE: relaunch the native launcher directly (no console window)
            if ("true".equalsIgnoreCase(System.getProperty("proton.packaged"))) {
                String jpkgExe = System.getProperty("jpackage.app-path", "");
                if (!jpkgExe.isEmpty() && new File(jpkgExe).isFile()) {
                    new ProcessBuilder(jpkgExe).start();
                    System.exit(0);
                    return;
                }
                String appDir = System.getProperty("proton.appdir", "");
                String exeName = System.getProperty("proton.exe", "");
                if (!appDir.isEmpty() && !exeName.isEmpty()) {
                    File exe = new File(new File(appDir).getParentFile(), exeName);
                    if (exe.isFile()) {
                        new ProcessBuilder(exe.getAbsolutePath()).start();
                        System.exit(0);
                        return;
                    }
                }
            }

            // 2) Running from source / dev mode (unchanged)
            String javaBin = System.getProperty("java.home") + File.separator
                    + "bin" + File.separator + "java";
            String cp = System.getProperty("java.class.path");
            File source = new File(System.getProperty("user.dir", "."), "main.java");
            File codeRoot = null;
            try {
                codeRoot = new File(main.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
            } catch (Throwable ignored) {}

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            if (codeRoot != null && codeRoot.isDirectory() && new File(codeRoot, "main.class").isFile()) {
                cmd.add("-cp");
                cmd.add(codeRoot.getAbsolutePath());
                cmd.add("main");
            } else if (codeRoot != null && codeRoot.isFile()) {
                cmd.add("-jar");
                cmd.add(codeRoot.getAbsolutePath());
            } else if (source.isFile()) {
                cmd.add("-cp");
                cmd.add(cp == null || cp.isEmpty() ? "." : cp);
                cmd.add(source.getAbsolutePath());
            } else {
                cmd.add("-cp");
                cmd.add(cp == null || cp.isEmpty() ? "." : cp);
                cmd.add("main");
            }
            new ProcessBuilder(cmd).start();
        } catch (Throwable ignored) {
            // Keep the current window alive if the relaunch could not be started.
            return;
        }
        System.exit(0);
    }

    /** Soft 320 ms opacity fade-in so the splash hands off cleanly to the UI. */
    private static void showWithFade(JFrame frame) {
        final long t0 = System.nanoTime();
        final javax.swing.Timer fade = new javax.swing.Timer(16, null);
        try {
            frame.setOpacity(0f);
        } catch (Throwable ignored) {
            frame.setVisible(true);
            return;
        }
        fade.addActionListener(e -> {
            float p = Math.min(1f, (System.nanoTime() - t0) / 320_000_000f);
            try { frame.setOpacity(p); } catch (Throwable ignored) {}
            if (p >= 1f) fade.stop();
        });
        frame.setVisible(true);
        fade.start();
    }
}

/* =============================================================== */
/*  LOADING WINDOW -- square, chrome-less splash                   */
/* =============================================================== */

@SuppressWarnings("serial")
class LoadingWindow extends JFrame {

    private static final int SIZE = 420;

    private final int          durationMs;
    private final SplashPanel  splash;
    private final long         t0 = System.nanoTime();
    private javax.swing.Timer  anim;

    LoadingWindow(int durationMs) {
        super("PROTON AI");
        this.durationMs = Math.max(1500, durationMs);

        // Undecorated => no title bar at all: no restore, no minimize, no close.
        setUndecorated(true);
        setResizable(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        try { setBackground(Theme.BG); } catch (Throwable ignored) {}

        splash = new SplashPanel();
        setContentPane(splash);

        // Exactly 420x420, dead center of the screen.
        setSize(SIZE, SIZE);
        setLocationRelativeTo(null);

        // Swallow any close request while the splash is up.
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { /* ignore */ }
        });
    }

    /** Start the 60 FPS paint loop (call once, on the EDT). */
    void start() {
        if (anim != null) return;
        anim = new javax.swing.Timer(16, e -> splash.repaint());
        anim.setCoalesce(true);
        anim.start();
    }

    private float elapsedMs() {
        return (System.nanoTime() - t0) / 1_000_000f;
    }

    /* ------------------------------------------------------------ */

    private final class SplashPanel extends JPanel {

        SplashPanel() {
            setOpaque(true);
            setBackground(Theme.BG);
            setDoubleBuffered(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                                    RenderingHints.VALUE_STROKE_PURE);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                                    RenderingHints.VALUE_RENDER_QUALITY);

                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;

                float ms = elapsedMs();
                float t  = Math.min(1f, ms / durationMs);   // 0..1 timeline
                float in = Math.min(1f, ms / 450f);         // content fade-in

                paintBackdrop(g2, w, h, ms);

                Composite base = g2.getComposite();
                if (in < 1f) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, in));
                }

                paintOrb(g2, w / 2f, 148f, 46f, ms, t);
                paintWordmark(g2, w);
                paintStatus(g2, w, t, ms);
                paintProgress(g2, w, t);

                g2.setComposite(base);
                paintFrame(g2, w, h, t);
            } finally {
                g2.dispose();
            }
        }

        /* ---- soft aurora backdrop ---- */
        private void paintBackdrop(Graphics2D g2, int w, int h, float ms) {
            g2.setPaint(new GradientPaint(0, 0, new Color(0x0B0920), 0, h, new Color(0x04030A)));
            g2.fillRect(0, 0, w, h);

            float drift = (float) Math.sin(ms * 0.00055) * 26f;
            float cx = w / 2f + drift;

            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(cx, 140f), 235f,
                    new float[]{0f, 0.55f, 1f},
                    new Color[]{Theme.alpha(new Color(0x3D3BFF), 0.20),
                                Theme.alpha(new Color(0x6A2BFF), 0.07),
                                new Color(0, 0, 0, 0)}));
            g2.fill(new Ellipse2D.Float(cx - 235f, 140f - 235f, 470f, 470f));

            float drift2 = (float) Math.cos(ms * 0.00042) * 30f;
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(w - 58f + drift2, h - 44f), 210f,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{Theme.alpha(new Color(0xFF2FA8), 0.10),
                                Theme.alpha(new Color(0xA855F7), 0.04),
                                new Color(0, 0, 0, 0)}));
            g2.fill(new Ellipse2D.Float(w - 58f + drift2 - 210f, h - 44f - 210f, 420f, 420f));
        }

        /* ---- animated plasma orb + orbital sweep ---- */
        private void paintOrb(Graphics2D g2, float cx, float cy, float r, float ms, float t) {
            // outer bloom
            for (int i = 3; i >= 1; i--) {
                float rr = r + 10f + i * 15f + (float) Math.sin(ms * 0.002f + i) * 2f;
                g2.setPaint(new RadialGradientPaint(
                        new Point2D.Float(cx, cy), rr,
                        new float[]{0f, 0.70f, 1f},
                        new Color[]{Theme.alpha(new Color(0x5F6BFF), 0.055f),
                                    Theme.alpha(new Color(0xC44DFF), 0.020f),
                                    new Color(0, 0, 0, 0)}));
                g2.fill(new Ellipse2D.Float(cx - rr, cy - rr, rr * 2f, rr * 2f));
            }

            // orbiting bright dot
            double dotAng = ms * 0.0016;
            float px = cx + (float) Math.cos(dotAng) * (r + 20f);
            float py = cy + (float) Math.sin(dotAng) * (r + 20f);
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(px, py), 9f,
                    new float[]{0f, 1f},
                    new Color[]{Theme.alpha(new Color(0xFF9AE9), 0.55f),
                                new Color(0, 0, 0, 0)}));
            g2.fill(new Ellipse2D.Float(px - 9f, py - 9f, 18f, 18f));
            g2.setColor(new Color(0xFFC8F4));
            g2.fill(new Ellipse2D.Float(px - 1.9f, py - 1.9f, 3.8f, 3.8f));

            // plasma core
            float pulse = 1f + (float) Math.sin(ms * 0.0032f) * 0.045f;
            float core = r * pulse;
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(cx - core * 0.26f, cy - core * 0.30f), core * 1.55f,
                    new float[]{0f, 0.38f, 0.72f, 1f},
                    new Color[]{new Color(0xF4F1FF), new Color(0x8EA0FF),
                                new Color(0x6B38E8), new Color(0x160F33)}));
            g2.fill(new Ellipse2D.Float(cx - core, cy - core, core * 2f, core * 2f));

            // thin highlight cap
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(cx - core * 0.34f, cy - core * 0.42f), core * 0.62f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(255, 255, 255, 150), new Color(255, 255, 255, 0)}));
            g2.fill(new Ellipse2D.Float(cx - core * 0.95f, cy - core * 1.0f,
                                        core * 1.25f, core * 1.05f));

            // rotating sweep ring (outer)
            float rot1 = ms * 0.060f;
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setPaint(new LinearGradientPaint(
                    cx - r - 12f, cy - r - 12f, cx + r + 12f, cy + r + 12f,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{Theme.alpha(new Color(0x5AD7FF), 0f),
                                Theme.alpha(new Color(0xA05FFF), 0.95f),
                                Theme.alpha(new Color(0xFF73EE), 0f)}));
            AffineTransform old = g2.getTransform();
            g2.rotate(Math.toRadians(rot1), cx, cy);
            g2.draw(new Arc2D.Float(cx - r - 10f, cy - r - 10f,
                                    (r + 10f) * 2f, (r + 10f) * 2f, 18f, 286f, Arc2D.OPEN));
            g2.setTransform(old);

            // counter-rotating inner ring
            g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 255, 255, 52));
            g2.rotate(Math.toRadians(-ms * 0.040f), cx, cy);
            g2.draw(new Arc2D.Float(cx - r + 7f, cy - r + 7f,
                                    (r - 7f) * 2f, (r - 7f) * 2f, 130f, 240f, Arc2D.OPEN));
            g2.setTransform(old);

            // barely-there static track
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(255, 255, 255, 20));
            g2.draw(new Ellipse2D.Float(cx - r - 2f, cy - r - 2f,
                                        (r + 2f) * 2f, (r + 2f) * 2f));
        }

        /* ---- PROTON wordmark ---- */
        private void paintWordmark(Graphics2D g2, int w) {
            String word = "PROTON";
            float  sp   = 7.5f;
            Font   f    = Fnt.get(Font.BOLD, 30f);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();

            float total = 0f;
            for (int i = 0; i < word.length(); i++) {
                total += fm.charWidth(word.charAt(i));
                if (i < word.length() - 1) total += sp;
            }

            float x     = (w - total) / 2f;
            float baseY = 262f;
            g2.setPaint(new LinearGradientPaint(
                    x, baseY - 28f, x + total, baseY,
                    new float[]{0f, 0.48f, 1f},
                    new Color[]{new Color(0xEFEFFF), new Color(0xB9C2FF), new Color(0xF3C9FF)}));
            for (int i = 0; i < word.length(); i++) {
                g2.drawString(String.valueOf(word.charAt(i)), x, baseY);
                x += fm.charWidth(word.charAt(i)) + sp;
            }

            // subtitle
            g2.setFont(Fnt.get(Font.PLAIN, 9f));
            g2.setColor(Theme.TEXT3);
            drawSpacedCenter(g2, "AI WORKSTATION", w, 283f, 3.4f);
        }

        /* ---- rotating status line ---- */
        private void paintStatus(Graphics2D g2, int w, float t, float ms) {
            String msg;
            if      (t < 0.30f) msg = "loading workspace";
            else if (t < 0.52f) msg = "spinning up the orb";
            else if (t < 0.74f) msg = "syncing memory and skills";
            else if (t < 0.92f) msg = "calibrating audio";
            else                msg = "ready";

            StringBuilder sb = new StringBuilder(msg);
            int dots = t >= 0.92f ? 0 : (int) ((ms / 380f) % 4f);
            for (int i = 0; i < dots; i++) sb.append('.');

            g2.setFont(Fnt.get(Font.PLAIN, 11f));
            g2.setColor(t >= 0.92f ? new Color(0x7CF7C4) : Theme.TEXT2);
            drawCentered(g2, sb.toString(), w, 332f);
        }

        /* ---- gradient progress bar + percentage ---- */
        private void paintProgress(Graphics2D g2, int w, float t) {
            int   barW = 260, barH = 4;
            float x = (w - barW) / 2f, y = 360f;

            g2.setColor(new Color(255, 255, 255, 20));
            g2.fill(new RoundRectangle2D.Float(x, y, barW, barH, barH, barH));

            // ease-in-out so the bar feels mechanical, not linear
            float e = t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;
            float fw = Math.max(barH, barW * e);

            g2.setPaint(new LinearGradientPaint(
                    x, y, x + barW, y,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(0x3D7BFF), new Color(0xA855F7), new Color(0xFF4DD0)}));
            g2.fill(new RoundRectangle2D.Float(x, y, fw, barH, barH, barH));

            // travelling sheen on the filled part
            float sheen = x + ((System.nanoTime() / 1_000_000L) % 1400L) / 1400f * barW;
            if (sheen < x + fw) {
                g2.setPaint(new LinearGradientPaint(
                        Math.max(x, sheen - 34f), y, Math.min(x + fw, sheen + 34f), y,
                        new float[]{0f, 0.5f, 1f},
                        new Color[]{new Color(255, 255, 255, 0),
                                    new Color(255, 255, 255, 120),
                                    new Color(255, 255, 255, 0)}));
                g2.fill(new RoundRectangle2D.Float(x, y, fw, barH, barH, barH));
            }

            g2.setFont(Fnt.get(Font.PLAIN, 10f));
            g2.setColor(Theme.TEXT3);
            drawCentered(g2, Math.round(e * 100f) + "%", w, 386f);
        }

        /* ---- window edge + brand hairline ---- */
        private void paintFrame(Graphics2D g2, int w, int h, float t) {
            g2.setColor(new Color(255, 255, 255, 26));
            g2.drawRect(0, 0, w - 1, h - 1);

            float lineW = Math.max(2f, w * Math.min(1f, t * 1.6f));
            g2.setPaint(new LinearGradientPaint(
                    0, 0, w, 0,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(0x3D7BFF), new Color(0xA855F7), new Color(0xFF4DD0)}));
            g2.fill(new RoundRectangle2D.Float(0, 0, lineW, 2f, 2f, 2f));
        }

        /* ---- small text helpers ---- */
        private void drawCentered(Graphics2D g2, String s, int w, float baseY) {
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, (w - fm.stringWidth(s)) / 2f, baseY);
        }

        private void drawSpacedCenter(Graphics2D g2, String s, int w, float baseY, float sp) {
            FontMetrics fm = g2.getFontMetrics();
            float total = 0f;
            for (int i = 0; i < s.length(); i++) {
                total += fm.charWidth(s.charAt(i));
                if (i < s.length() - 1) total += sp;
            }
            float x = (w - total) / 2f;
            for (int i = 0; i < s.length(); i++) {
                String ch = String.valueOf(s.charAt(i));
                g2.drawString(ch, x, baseY);
                x += fm.charWidth(s.charAt(i)) + sp;
            }
        }
    }
}

/* =============================================================== */
/*  THEME + FONTS                                                  */
/* =============================================================== */

final class Theme {
    static final Color BG       = new Color(0x04030A);
    static final Color TEXT     = new Color(0xEEF0F7);
    static final Color TEXT2    = new Color(0x9A9CB4);
    static final Color TEXT3    = new Color(0x5F6178);
    static final Color TEXT_DIM = new Color(0x63657D);
    static final Color GREY     = new Color(0x43455A);

    static final Color[] PAL_A = {
        new Color(0x3D7BFF), new Color(0x5F6BFF), new Color(0x8F5BFF),
        new Color(0xC44DFF), new Color(0xFF4FD0), new Color(0xFF9AE9)
    };
    static final Color[] PAL_B = {
        new Color(0x5AD7FF), new Color(0x55A0FF), new Color(0x6E78FF),
        new Color(0xA05FFF), new Color(0xDC4BFF), new Color(0xFF73EE)
    };
    static final float[] PAL_FR = {0f, 0.22f, 0.44f, 0.64f, 0.84f, 1f};

    static Color mix(Color a, Color b, float t) {
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return new Color(
            clamp255(a.getRed()   + (b.getRed()   - a.getRed())   * t),
            clamp255(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            clamp255(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t),
            clamp255(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }
    static int clamp255(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }
    static Color alpha(Color c, double a) {
        if (a < 0) a = 0; else if (a > 1) a = 1;
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (a * 255));
    }
    static Color[] palette(float t) {
        Color[] out = new Color[PAL_A.length];
        for (int i = 0; i < out.length; i++) out[i] = mix(PAL_A[i], PAL_B[i], t);
        return out;
    }
}

final class Fnt {
    static final String FAM;
    static {
        String pick = "SansSerif";
        try {
            String[] prefer = {"Segoe UI Variable Display", "Segoe UI", "Inter",
                               "Helvetica Neue", "SF Pro Text", "Arial"};
            List<String> avail = Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                                   .getAvailableFontFamilyNames());
            for (String p : prefer) if (avail.contains(p)) { pick = p; break; }
        } catch (Throwable ignored) {}
        FAM = pick;
    }
    static Font get(int style, float size) {
        return new Font(FAM, style, Math.max(9, Math.round(size)));
    }
}

/* =============================================================== */
/*  FIRST-RUN SETUP -- provider choice + API keys                  */
/* =============================================================== */

@SuppressWarnings("serial")
class SetupWindow extends JFrame {

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JTextField nvidiaField = keyField();
    private final JTextField groqField = keyField();
    private final JPanel nvidiaBlock = new JPanel();
    private final JLabel keysTitle = new JLabel();
    private final JLabel error = new JLabel(" ");
    private final boolean changing;
    private boolean nvidiaMode = false;

    SetupWindow(boolean changing) {
        super("PROTON AI");
        this.changing = changing;
        setUndecorated(true);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setBackground(Theme.BG);
        setSize(600, 486);
        setResizable(false);
        setLocationRelativeTo(null);
        if (changing) setAlwaysOnTop(true);

        nvidiaField.setText(Prefs.getString("nvidiaKey", ""));
        groqField.setText(Prefs.getString("groqKey", ""));

        deck.setOpaque(false);
        deck.add(buildProviderCard(), "provider");
        deck.add(buildKeysCard(), "keys");

        JPanel root = new SetupPanel();
        root.setLayout(new BorderLayout());
        root.add(deck, BorderLayout.CENTER);
        setContentPane(root);

        cards.show(deck, "provider");
        getRootPane().registerKeyboardAction(e -> {
            dispose();
            if (!changing) System.exit(0);
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /* ---------- screens ---------- */

    private JPanel buildProviderCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setOpaque(false);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel kicker = label("SETUP", Font.BOLD, 9.5f, Theme.TEXT3);
        kicker.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = label("Choose your AI engine", Font.BOLD, 24f, Theme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = label("Nemotron for complex work. Groq for everyday chat,", Font.PLAIN, 12f, Theme.TEXT2);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub2 = label("voice and hearing.", Font.PLAIN, 12f, Theme.TEXT2);
        sub2.setAlignmentX(Component.CENTER_ALIGNMENT);

        SetupButton nvidia = new SetupButton("NVIDIA NIM + Groq",
                "Nemotron for complex work, coding, vision and agents. Groq for chat and voice.");
        nvidia.addActionListener(e -> showKeys(true));
        SetupButton groq = new SetupButton("Groq only",
                "Fast chat and voice with Groq. No NVIDIA key needed.");
        groq.addActionListener(e -> showKeys(false));

        box.add(kicker);
        box.add(Box.createVerticalStrut(10));
        box.add(title);
        box.add(Box.createVerticalStrut(8));
        box.add(sub);
        box.add(sub2);
        box.add(Box.createVerticalStrut(30));
        box.add(nvidia);
        box.add(Box.createVerticalStrut(14));
        box.add(groq);
        box.add(Box.createVerticalStrut(18));
        JLabel note = label("You can change this later from Settings.", Font.PLAIN, 10.5f, Theme.TEXT3);
        note.setAlignmentX(Component.CENTER_ALIGNMENT);
        box.add(note);

        card.add(box);
        return card;
    }

    private JPanel buildKeysCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setOpaque(false);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        keysTitle.setFont(Fnt.get(Font.BOLD, 22f));
        keysTitle.setForeground(Theme.TEXT);
        keysTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel sub = label("Keys are stored locally in ~/.proton-ai.properties.", Font.PLAIN, 11f, Theme.TEXT2);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        nvidiaBlock.setOpaque(false);
        nvidiaBlock.setLayout(new BoxLayout(nvidiaBlock, BoxLayout.Y_AXIS));
        nvidiaBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel nl = label("NVIDIA NIM API key", Font.BOLD, 11.5f, Theme.TEXT);
        nl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel nh = label("Starts with nvapi-  \u00B7  used for Nemotron", Font.PLAIN, 10f, Theme.TEXT3);
        nh.setAlignmentX(Component.LEFT_ALIGNMENT);
        nvidiaField.setAlignmentX(Component.LEFT_ALIGNMENT);
        nvidiaBlock.add(nl);
        nvidiaBlock.add(Box.createVerticalStrut(6));
        nvidiaBlock.add(nvidiaField);
        nvidiaBlock.add(Box.createVerticalStrut(4));
        nvidiaBlock.add(nh);
        nvidiaBlock.add(Box.createVerticalStrut(16));

        JLabel gl = label("Groq API key", Font.BOLD, 11.5f, Theme.TEXT);
        gl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel gh = label("Starts with gsk_  \u00B7  used for chat, TTS and STT", Font.PLAIN, 10f, Theme.TEXT3);
        gh.setAlignmentX(Component.LEFT_ALIGNMENT);
        groqField.setAlignmentX(Component.LEFT_ALIGNMENT);

        error.setFont(Fnt.get(Font.PLAIN, 10.5f));
        error.setForeground(new Color(0xFF7A9A));
        error.setAlignmentX(Component.LEFT_ALIGNMENT);

        SetupButton back = new SetupButton("< Back", null, false);
        back.addActionListener(e -> { error.setText(" "); cards.show(deck, "provider"); });
        SetupButton done = new SetupButton(changing ? "Save & restart" : "Done", null, true);
        done.addActionListener(e -> submit());

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(492, 42));
        row.add(back, BorderLayout.WEST);
        row.add(done, BorderLayout.EAST);

        box.add(keysTitle);
        box.add(Box.createVerticalStrut(6));
        box.add(sub);
        box.add(Box.createVerticalStrut(22));
        box.add(nvidiaBlock);
        box.add(gl);
        box.add(Box.createVerticalStrut(6));
        box.add(groqField);
        box.add(Box.createVerticalStrut(4));
        box.add(gh);
        box.add(Box.createVerticalStrut(8));
        box.add(error);
        box.add(Box.createVerticalStrut(14));
        box.add(row);

        card.add(box);
        return card;
    }

    private void showKeys(boolean nvidia) {
        nvidiaMode = nvidia;
        nvidiaBlock.setVisible(nvidia);
        keysTitle.setText(nvidia ? "Connect NVIDIA NIM + Groq" : "Connect Groq");
        error.setText(" ");
        cards.show(deck, "keys");
        revalidate();
        repaint();
        (nvidia ? nvidiaField : groqField).requestFocusInWindow();
    }

    private void submit() {
        String g = groqField.getText() == null ? "" : groqField.getText().trim();
        String n = nvidiaField.getText() == null ? "" : nvidiaField.getText().trim();
        if (g.isEmpty()) {
            error.setText("Paste your Groq API key to continue.");
            return;
        }
        if (nvidiaMode && n.isEmpty()) {
            error.setText("Paste your NVIDIA NIM API key to continue.");
            return;
        }
        Prefs.setString("provider", nvidiaMode ? "nvidia+groq" : "groq");
        Prefs.setString("groqKey", g);
        if (!n.isEmpty()) Prefs.setString("nvidiaKey", n);
        main.provider = nvidiaMode ? main.Provider.NVIDIA_GROQ : main.Provider.GROQ;
        main.groqKey = g;
        main.nvidiaKey = nvidiaMode ? n : "";
        dispose();
        if (changing) main.restartApp();
        else main.launchMain();
    }

    /* ---------- small helpers ---------- */

    private static JTextField keyField() {
        JTextField f = new JTextField();
        f.setOpaque(true);
        f.setBackground(new Color(0x141222));
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.TEXT);
        f.setFont(Fnt.get(Font.PLAIN, 13f));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 34), 1, true),
                new EmptyBorder(11, 13, 11, 13)));
        f.setMaximumSize(new Dimension(492, 46));
        f.setPreferredSize(new Dimension(492, 46));
        return f;
    }

    private static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(Fnt.get(style, size));
        l.setForeground(color);
        return l;
    }

    /** Glassy card button used by both setup screens. */
    @SuppressWarnings("serial")
    static final class SetupButton extends JButton {
        private final String title;
        private final String subtitle;
        private final boolean primary;

        SetupButton(String title, String subtitle) {
            this(title, subtitle, false);
        }

        SetupButton(String title, String subtitle, boolean primary) {
            this.title = title;
            this.subtitle = subtitle;
            this.primary = primary;
            setText("");
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(Component.CENTER_ALIGNMENT);
            if (subtitle != null) {
                setPreferredSize(new Dimension(492, 84));
                setMaximumSize(new Dimension(492, 84));
            } else {
                setPreferredSize(new Dimension(primary ? 132 : 96, 40));
                setMaximumSize(new Dimension(primary ? 132 : 96, 40));
            }
            getAccessibleContext().setAccessibleName(title);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                boolean hover = getModel().isRollover();
                boolean press = getModel().isPressed();
                int arc = subtitle != null ? 18 : h;
                RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc);

                if (primary) {
                    g2.setPaint(new LinearGradientPaint(
                            new Point2D.Float(0, 0), new Point2D.Float(w, h),
                            new float[]{0f, 0.55f, 1f},
                            new Color[]{new Color(0x2B6BFF), new Color(0xA855F7), new Color(0xFF4DD0)}));
                    g2.fill(rr);
                    g2.setColor(new Color(255, 255, 255, 40));
                    g2.draw(rr);
                } else {
                    g2.setColor(new Color(255, 255, 255, hover ? 20 : 10));
                    g2.fill(rr);
                    g2.setColor(new Color(255, 255, 255, hover ? 46 : 26));
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(rr);
                }
                if (press) {
                    g2.setColor(new Color(0, 0, 0, 40));
                    g2.fill(rr);
                }

                if (subtitle != null) {
                    g2.setFont(Fnt.get(Font.BOLD, 14f));
                    g2.setColor(Theme.TEXT);
                    g2.drawString(title, 22, 33);
                    g2.setFont(Fnt.get(Font.PLAIN, 11f));
                    g2.setColor(Theme.TEXT2);
                    String s = subtitle;
                    FontMetrics fm = g2.getFontMetrics();
                    if (fm.stringWidth(s) > w - 44) {
                        while (s.length() > 4 && fm.stringWidth(s + "...") > w - 44) s = s.substring(0, s.length() - 1);
                        s += "...";
                    }
                    g2.drawString(s, 22, 55);
                } else {
                    g2.setFont(Fnt.get(Font.BOLD, 12.5f));
                    g2.setColor(Color.WHITE);
                    FontMetrics fm = g2.getFontMetrics();
                    int tx = (w - fm.stringWidth(title)) / 2;
                    int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                    g2.drawString(title, tx, ty);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** Paints the aurora backdrop and the glass card behind the deck. */
    @SuppressWarnings("serial")
    private final class SetupPanel extends JPanel {
        SetupPanel() {
            setOpaque(true);
            setBackground(Theme.BG);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x0B0920), 0, h, new Color(0x04030A)));
                g2.fillRect(0, 0, w, h);

                g2.setPaint(new RadialGradientPaint(
                        new Point2D.Float(w * 0.22f, h * 0.18f), 280f,
                        new float[]{0f, 1f},
                        new Color[]{Theme.alpha(new Color(0x3D3BFF), 0.18),
                                    new Color(0, 0, 0, 0)}));
                g2.fill(new Ellipse2D.Float(w * 0.22f - 280f, h * 0.18f - 280f, 560f, 560f));
                g2.setPaint(new RadialGradientPaint(
                        new Point2D.Float(w * 0.82f, h * 0.86f), 260f,
                        new float[]{0f, 1f},
                        new Color[]{Theme.alpha(new Color(0xFF2FA8), 0.12),
                                    new Color(0, 0, 0, 0)}));
                g2.fill(new Ellipse2D.Float(w * 0.82f - 260f, h * 0.86f - 260f, 520f, 520f));

                RoundRectangle2D card = new RoundRectangle2D.Float(26.5f, 26.5f, w - 53f, h - 53f, 28, 28);
                g2.setColor(new Color(10, 8, 20, 236));
                g2.fill(card);
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 18),
                        0, h, new Color(255, 255, 255, 3)));
                g2.fill(card);
                g2.setColor(new Color(255, 255, 255, 34));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(card);
            } finally {
                g2.dispose();
            }
        }
    }
}

/* =============================================================== */
/*  SHARED STATE                                                   */
/* =============================================================== */

/** Dedicated operating modes: full workstation vs. hands-free voice coworker. */
enum AppMode { ASSISTANT, VOICE }

final class S {
    volatile float   liveLevel   = 0f;
    volatile float   targetLevel = 0f;
    float            level       = 0f;
    volatile boolean micActive = false;
    volatile boolean micLive   = false;
    volatile boolean micHeard  = false;   // user speech detected in this listen
    volatile boolean speaking  = false;   // assistant is speaking
    volatile AppMode mode = AppMode.ASSISTANT; // strict mode separation (spec 2A)
    volatile boolean continuousVoice = false;  // true only in VOICE mode
    volatile long    micStart  = 0L;
    volatile long    micLastData = 0L;

    volatile boolean thinking    = false;
    float            thinkPhase = 0f;
    double           thinkTime  = 0;

    double noiseT   = 0;
    double rotY     = 0;
    double ringTime = 0;

    boolean composerExpanded = false;
    float   composerExpand   = 0f;

    float  hintAlpha       = 1f;
    float  transcriptAlpha = 0f;
    float  transcriptTtl   = 0f;
    String transcript      = "";

    double simPhase = 0;

    long lastStatusHash = 0;

    // Corner Mini-Orb Companion HUD (PiP task mode) -- shared UI + worker state.
    volatile boolean companionMode  = false; // mini overlay visible, main hidden
    volatile boolean companionDone  = false; // emerald completion flash armed
    volatile long    companionDoneTime = 0L;
    volatile int     companionStep  = 0;     // 1-based active step
    volatile int     companionTotal = 0;
    volatile String  companionLabel = "";    // active action name
    volatile boolean abortTask       = false; // set by mini-orb click / Escape

    // Kinetic window morph (EDT only): 0.0 = fully expanded, 1.0 = fully mini.
    float morphProgress = 0f;
}

/* =============================================================== */
/*  WINDOW CHROME: resize border, title bar, window buttons        */
/* =============================================================== */

@SuppressWarnings("serial")
class ResizePanel extends JPanel {
    static final int B = 4;
    static final int C = 14;
    private final JFrame f;
    private int edge = 0;               // 1 left, 2 right, 4 top, 8 bottom
    private Point startScreen;
    private Rectangle startBounds;
    private boolean resizeEnabled = true;
    private boolean maximized = false;

    ResizePanel(JFrame f) {
        super(new BorderLayout());
        this.f = f;
        setOpaque(true);
        setBackground(Theme.BG);
        setBorder(new EmptyBorder(B, B, B, B));
        MouseAdapter ma = new MouseAdapter() {
            public void mouseMoved(MouseEvent e) {
                if (startScreen != null) return;
                setCursor(Cursor.getPredefinedCursor(cursorFor(hit(e.getPoint()))));
            }
            public void mouseExited(MouseEvent e) {
                if (startScreen == null) setCursor(Cursor.getDefaultCursor());
            }
            public void mousePressed(MouseEvent e) {
                edge = hit(e.getPoint());
                if (edge == 0) return;
                startScreen = e.getLocationOnScreen();
                startBounds = f.getBounds();
            }
            public void mouseDragged(MouseEvent e) {
                if (startScreen == null || edge == 0) return;
                Point cur = e.getLocationOnScreen();
                int dx = cur.x - startScreen.x, dy = cur.y - startScreen.y;
                Rectangle r = new Rectangle(startBounds);
                Dimension min = f.getMinimumSize();
                if ((edge & 1) != 0) {
                    int nw = Math.max(min.width, startBounds.width - dx);
                    r.x = startBounds.x + startBounds.width - nw;
                    r.width = nw;
                }
                if ((edge & 2) != 0) r.width = Math.max(min.width, startBounds.width + dx);
                if ((edge & 4) != 0) {
                    int nh = Math.max(min.height, startBounds.height - dy);
                    r.y = startBounds.y + startBounds.height - nh;
                    r.height = nh;
                }
                if ((edge & 8) != 0) r.height = Math.max(min.height, startBounds.height + dy);
                f.setBounds(r);
            }
            public void mouseReleased(MouseEvent e) { startScreen = null; edge = 0; }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    void setMaximized(boolean m) {
        maximized = m;
        resizeEnabled = !m;
        int b = m ? 0 : B;
        setBorder(new EmptyBorder(b, b, b, b));
        setCursor(Cursor.getDefaultCursor());
        revalidate();
        repaint();
    }

    private int hit(Point p) {
        if (!resizeEnabled) return 0;
        int w = getWidth(), h = getHeight();
        boolean inBorder = p.x < B || p.x >= w - B || p.y < B || p.y >= h - B;
        if (!inBorder) return 0;
        boolean horizStrip = p.y < B || p.y >= h - B;
        boolean vertStrip  = p.x < B || p.x >= w - B;
        int m = 0;
        if (p.x < (horizStrip ? C : B)) m |= 1;
        if (p.x >= w - (horizStrip ? C : B)) m |= 2;
        if (p.y < (vertStrip ? C : B)) m |= 4;
        if (p.y >= h - (vertStrip ? C : B)) m |= 8;
        return m;
    }

    private int cursorFor(int m) {
        switch (m) {
            case 1:  return Cursor.W_RESIZE_CURSOR;
            case 2:  return Cursor.E_RESIZE_CURSOR;
            case 4:  return Cursor.N_RESIZE_CURSOR;
            case 8:  return Cursor.S_RESIZE_CURSOR;
            case 5:  return Cursor.NW_RESIZE_CURSOR;
            case 6:  return Cursor.NE_RESIZE_CURSOR;
            case 9:  return Cursor.SW_RESIZE_CURSOR;
            case 10: return Cursor.SE_RESIZE_CURSOR;
            default: return Cursor.DEFAULT_CURSOR;
        }
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!maximized) {
            g.setColor(new Color(255, 255, 255, 30));
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        }
    }
}

@SuppressWarnings("serial")
class WinButton extends JComponent {
    static final int MIN = 0, MAX = 1, CLOSE = 2;
    private final int kind;
    private final ProtonFrame f;
    private boolean hover = false, down = false;

    WinButton(int kind, ProtonFrame f) {
        this.kind = kind;
        this.f = f;
        setOpaque(false);
        setPreferredSize(new Dimension(46, 34));
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            public void mouseExited(MouseEvent e)  { hover = false; down = false; repaint(); }
            public void mousePressed(MouseEvent e) { down = true; repaint(); }
            public void mouseReleased(MouseEvent e) {
                boolean fire = down && contains(e.getPoint());
                down = false;
                repaint();
                if (!fire) return;
                if (WinButton.this.kind == MIN) WinButton.this.f.minimizeWindow();
                else if (WinButton.this.kind == MAX) WinButton.this.f.toggleMaximize();
                else WinButton.this.f.closeRequest();
            }
        });
    }

    public String getToolTipText() {
        if (kind == MIN) return "Minimize";
        if (kind == MAX) return f.maximized ? "Restore" : "Maximize";
        return "Close";
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        int w = getWidth(), h = getHeight();
        boolean close = kind == CLOSE;
        if (hover) {
            if (close) g2.setColor(down ? new Color(0xB30F1E) : new Color(0xE81123));
            else g2.setColor(new Color(255, 255, 255, down ? 44 : 28));
            g2.fillRect(0, 0, w, h);
        }
        Color ic = (hover && close) ? Color.WHITE : new Color(0xC9CBDA);
        g2.setColor(ic);
        g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        double cx = w / 2.0, cy = h / 2.0;
        if (kind == MIN) {
            g2.draw(new Line2D.Double(cx - 5, cy + 0.5, cx + 5, cy + 0.5));
        } else if (kind == MAX) {
            if (!f.maximized) {
                g2.draw(new Rectangle2D.Double(cx - 5, cy - 5, 10, 10));
            } else {
                Path2D back = new Path2D.Double();
                back.moveTo(cx - 2, cy - 3);
                back.lineTo(cx - 2, cy - 5.5);
                back.lineTo(cx + 5.5, cy - 5.5);
                back.lineTo(cx + 5.5, cy + 2);
                back.lineTo(cx + 3, cy + 2);
                g2.draw(back);
                g2.draw(new Rectangle2D.Double(cx - 5.5, cy - 3, 8, 8));
            }
        } else {
            g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Line2D.Double(cx - 5, cy - 5, cx + 5, cy + 5));
            g2.draw(new Line2D.Double(cx + 5, cy - 5, cx - 5, cy + 5));
        }
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class TitleBar extends JPanel {
    private final ProtonFrame f;
    final WinButton minB, maxB, closeB;
    private Point pressScreen, pressLoc;
    private double pressRatio = 0.5;

    TitleBar(ProtonFrame f) {
        this.f = f;
        setOpaque(false);
        setLayout(null);
        setPreferredSize(new Dimension(100, 34));
        minB   = new WinButton(WinButton.MIN, f);
        maxB   = new WinButton(WinButton.MAX, f);
        closeB = new WinButton(WinButton.CLOSE, f);
        add(minB); add(maxB); add(closeB);

        MouseAdapter ma = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                pressScreen = e.getLocationOnScreen();
                pressLoc = TitleBar.this.f.getLocation();
                pressRatio = (double) e.getX() / Math.max(1, getWidth());
            }
            public void mouseDragged(MouseEvent e) {
                if (pressScreen == null) return;
                Point cur = e.getLocationOnScreen();
                if (TitleBar.this.f.maximized) {
                    if (pressScreen.distance(cur) < 4) return;
                    TitleBar.this.f.restoreForDrag(cur, pressRatio, e.getY());
                    pressLoc = TitleBar.this.f.getLocation();
                    pressScreen = cur;
                    return;
                }
                TitleBar.this.f.setLocation(pressLoc.x + cur.x - pressScreen.x,
                                            pressLoc.y + cur.y - pressScreen.y);
            }
            public void mouseReleased(MouseEvent e) { pressScreen = null; }
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2)
                    TitleBar.this.f.toggleMaximize();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    public void doLayout() {
        int w = getWidth(), h = getHeight(), bw = 46;
        closeB.setBounds(w - bw, 0, bw, h);
        maxB.setBounds(w - 2 * bw, 0, bw, h);
        minB.setBounds(w - 3 * bw, 0, bw, h);
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        // Kinetic morph: peripheral chrome cross-fades as the window compresses.
        float fade = 1f - f.s.morphProgress;
        if (fade <= 0.01f) { g2.dispose(); return; }
        if (fade < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillRect(0, 0, w, h);
        g2.setColor(new Color(255, 255, 255, 14));
        g2.drawLine(0, h - 1, w, h - 1);

        float ly = h / 2f - 9;
        LinearGradientPaint lp = new LinearGradientPaint(
            new Point2D.Float(12, ly), new Point2D.Float(30, ly + 18),
            new float[]{0f, 0.5f, 1f},
            new Color[]{new Color(0x3D7BFF), new Color(0xA855F7), new Color(0xFF4DD0)});
        g2.setPaint(lp);
        g2.fill(new RoundRectangle2D.Float(12, ly, 18, 18, 7, 7));
        g2.setFont(Fnt.get(Font.BOLD, 11f));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(Color.WHITE);
        g2.drawString("P", 12 + (18 - fm.stringWidth("P")) / 2,
                      (int) ly + (18 + fm.getAscent() - fm.getDescent()) / 2);

        g2.setFont(Fnt.get(Font.BOLD, 12f));
        fm = g2.getFontMetrics();
        g2.setColor(new Color(0xC3C5D6));
        g2.drawString("Proton AI", 40, (h + fm.getAscent() - fm.getDescent()) / 2);
        g2.dispose();
    }
}

/* =============================================================== */
/*  PROTON FRAME                                                   */
/* =============================================================== */

@SuppressWarnings("serial")
class ProtonFrame extends JFrame {

    final S s = new S();
    final Brain brain = new Brain(main.groqKey, main.nvidiaKey,
            main.provider == main.Provider.NVIDIA_GROQ);
    volatile boolean siriActive = false; // Siri wake overlay owns audio + visuals
    {
        // Background skills (monitors) speak up through here: pop-up always, voice only when Proton is idle.
        brain.sayAloud = msg -> {
            if (!s.micActive && !s.speaking && !s.thinking && !siriActive) {
                s.speaking = true;
                try { brain.speak(msg); }
                finally { s.speaking = false; }
            }
        };
        brain.alertSpeaker = msg -> {
            MemoryToast.show("Skill alert", msg, 2);
            if (!s.micActive && !s.speaking && !s.thinking && !siriActive) {
                s.speaking = true;
                try { brain.speak(msg); }
                finally { s.speaking = false; }
            }
        };
    }

    private final AmbientPanel ambient;
    private final RailPanel    rail;
    private final TopBar       topBar;
    private final TitleBar     titleBar;
    private final ResizePanel  shell;
    private final StagePanel   stage;
    private final OrbPanel     orb;
    private final StatusPanel  status;
    private final DockPanel    dock;
    private final ComposerPanel composer;
    private final GlassOverlay glass;

    private javax.swing.Timer ticker;
    private long  lastNanos = 0L;
    private MicCapture micThread;

    boolean maximized = false;
    private Rectangle normalBounds;
    private volatile int queryGen = 0;

    // Corner Mini-Orb Companion HUD state (main window handoff).
    private MiniCompanionOverlay companion;
    private Rectangle companionSavedBounds;

    // Settings card + "Hey Proton" wake word + system tray.
    private SettingsPanel settingsPanel;
    private WakeListener wake;
    private WHotkey wHotkey;
    private TrayIcon trayIcon;
    private volatile boolean inTray = false;

    // Siri-style wake activation overlay (never opens the main window).
    private SiriOverlay siri;

    // Live Nemotron reasoning card (upper-right, never read aloud).
    private ThoughtWindow thought;

    ProtonFrame() {
        super("PROTON AI");
        setUndecorated(true);
        setBackground(Theme.BG);
        setMinimumSize(new Dimension(780, 500));
        setSize(1000, 600);
        setLocationRelativeTo(null);
        normalBounds = getBounds();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        glass = new GlassOverlay();
        setGlassPane(glass);
        glass.setVisible(true);

        ambient  = new AmbientPanel();
        titleBar = new TitleBar(this);
        rail     = new RailPanel(s, glass);
        topBar   = new TopBar(s);
        stage    = new StagePanel(s);
        dock     = new DockPanel(s);
        orb      = stage.orb;
        status   = stage.status;
        composer = dock.composer;

        ambient.setLayout(new BorderLayout());
        ambient.add(titleBar, BorderLayout.NORTH);
        ambient.add(rail, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(topBar, BorderLayout.NORTH);
        center.add(stage,  BorderLayout.CENTER);
        center.add(dock,   BorderLayout.SOUTH);
        ambient.add(center, BorderLayout.CENTER);

        shell = new ResizePanel(this);
        shell.add(ambient, BorderLayout.CENTER);
        setContentPane(shell);

        wireEvents();
        wireCompanionHooks();
        wireThoughtHooks();

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { closeRequest(); }
        });

        lastNanos = System.nanoTime();
        ticker = new javax.swing.Timer(16, e -> tick());
        ticker.setCoalesce(true);
        ticker.start();

        if (!brain.hasKey()) {
            s.transcript = "\u201C add your API keys in Settings to get started \u201D";
            s.transcriptTtl = 30f;
        }

        if (Prefs.getBool("wake", false)) SwingUtilities.invokeLater(this::startWake);
    }

    /* ---------- window controls ---------- */

    void minimizeWindow() { setExtendedState(Frame.ICONIFIED); }

    void toggleMaximize() {
        if (maximized) {
            maximized = false;
            shell.setMaximized(false);
            setBounds(normalBounds);
        } else {
            normalBounds = getBounds();
            Rectangle r;
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc != null) {
                Rectangle sb = gc.getBounds();
                Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                r = new Rectangle(sb.x + in.left, sb.y + in.top,
                                  sb.width - in.left - in.right,
                                  sb.height - in.top - in.bottom);
            } else {
                r = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            }
            maximized = true;
            shell.setMaximized(true);
            setBounds(r);
        }
        titleBar.repaint();
        shell.revalidate();
        shell.repaint();
    }

    void restoreForDrag(Point cur, double ratio, int yInBar) {
        maximized = false;
        shell.setMaximized(false);
        Rectangle nb = normalBounds;
        setBounds(cur.x - (int) (nb.width * ratio),
                  cur.y - ResizePanel.B - yInBar,
                  nb.width, nb.height);
        titleBar.repaint();
        shell.revalidate();
    }

    private void wireEvents() {
        JRootPane rp = getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
          .put(KeyStroke.getKeyStroke("ESCAPE"), "esc");
        rp.getActionMap().put("esc", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (settingsPanel != null && settingsPanel.isVisible()) { closeSettings(); return; }
                // Companion HUD: Escape aborts the task and restores immediately.
                if (s.companionMode || s.abortTask) {
                    abortCompanion();
                    if (s.mode == AppMode.VOICE) setAssistantMode();
                    return;
                }
                if (companion != null && companion.isVisible()) {
                    abortCompanion();
                    if (s.mode == AppMode.VOICE) setAssistantMode();
                    return;
                }
                // Voice Mode exit: cancel listening, restore dock, back to Assistant.
                if (s.mode == AppMode.VOICE) { setAssistantMode(); return; }
                if (s.composerExpanded) collapseComposer();
                else closeRequest();
            }
        });
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
          .put(KeyStroke.getKeyStroke('/'), "focusInput");
        rp.getActionMap().put("focusInput", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (s.mode == AppMode.VOICE) return; // voice stage has no text bar
                expandComposer();
                composer.input.requestFocusInWindow();
            }
        });
        // Mini-orb click aborts the running task and morphs back immediately.
        orb.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (s.morphProgress > 0.85f || s.companionMode) abortCompanion();
            }
        });
    }

    /* ---- Strict mode separation: Assistant workstation vs. Voice coworker ---- */

    /** Assistant Mode: full workstation, single-turn mic, dock/composer visible. */
    void setAssistantMode() {
        if (s.mode == AppMode.ASSISTANT && !s.micActive && dock.isVisible()) {
            rail.setModeMarker(0);
            return;
        }
        s.mode = AppMode.ASSISTANT;
        s.continuousVoice = false;
        queryGen++; // invalidate pending STT / brain turns from Voice Mode
        try { brain.stopSpeaking(); } catch (Throwable ignored) {}
        MicCapture t = micThread;
        micThread = null;
        s.micActive = false;
        s.micLive = false;
        s.micHeard = false;
        s.targetLevel = 0f;
        if (t != null) t.stopCapture();
        composer.mic.setActive(false);
        if (!dock.isVisible()) {
            dock.setVisible(true);
            dock.revalidate();
            dock.repaint();
        }
        rail.setModeMarker(0);
        s.transcript = "\u201C assistant mode \u201D";
        s.transcriptTtl = 2f;
    }

    /** Voice Mode: dedicated voice stage, dock hidden, continuous auto-rearm loop. */
    void setVoiceMode() {
        if (s.mode == AppMode.VOICE) {
            if (!s.micActive) startMic(); // re-arm if idle
            rail.setModeMarker(1);
            return;
        }
        s.mode = AppMode.VOICE;
        s.continuousVoice = true;
        collapseComposer();
        if (dock.isVisible()) {
            dock.setVisible(false);
            dock.revalidate();
            dock.repaint();
        }
        rail.setModeMarker(1);
        s.transcript = "\u201C voice mode -- listening \u201D";
        s.transcriptTtl = 3f;
        startMic(); // immediately begins listening upon switching
    }

    /* ---- Corner Mini-Orb Companion HUD lifecycle (PiP task mode) ---- */

    private void wireCompanionHooks() {
        final ProtonFrame self = this;
        brain.companion = new Brain.CompanionHooks() {
            public boolean shouldAccompany(int stepCount) {
                // Reflex (<10ms) already bypassed Brain.process; any LLM plan
                // with real steps gets the corner treatment. During a Siri wake
                // conversation the floating orb owns the stage instead.
                return !siriActive && stepCount > 0;
            }
            public void enter(int totalSteps) { self.enterCompanionMode(totalSteps); }
            public void onStep(int index, int total, String action, String target) {
                s.companionStep = index + 1;
                s.companionTotal = total;
                s.companionLabel = action == null ? "" : action;
            }
            public void onDone() {
                s.companionDone = true;
                s.companionDoneTime = System.currentTimeMillis();
            }
            public void exit(boolean completed) { self.exitCompanionMode(completed); }
            public boolean isAborted() { return s.abortTask; }
        };
    }

    /* ---- Live Nemotron reasoning card (upper-right, silent) ---- */

    private void wireThoughtHooks() {
        thought = new ThoughtWindow();
        brain.thoughtHooks = new Brain.ThoughtHooks() {
            public void begin() { if (thought != null) thought.begin(); }
            public void thought(String delta) { if (thought != null) thought.append(delta); }
            public void end() { if (thought != null) thought.end(); }
        };
    }

    /* ---- Kinetic window morph: expanded <-> corner mini (single window) ---- */
    private javax.swing.Timer morphTimer;
    private final Rectangle morphFrom = new Rectangle();
    private final Rectangle morphTo = new Rectangle();
    private long morphT0;
    private boolean morphCollapsing;
    private boolean chromeHidden = false;
    private boolean chromeEnabledState = true;
    private static final long MORPH_MS = 300; // 280-320ms glide at 60 FPS
    private static final int MINI_SIZE = 150;
    private static final int MINI_PAD = 24;

    /** Cubic ease-in-out for natural deceleration into the dock. */
    static double morphEase(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Thread-safe: dispatches the compress animation to the EDT. */
    void animateToMini() {
        SwingUtilities.invokeLater(this::animateToMiniOnEdt);
    }

    /** Thread-safe: dispatches the expand animation to the EDT. */
    void animateToExpanded() {
        SwingUtilities.invokeLater(this::animateToExpandedOnEdt);
    }

    private void animateToMiniOnEdt() {
        try {
            try { companionSavedBounds = getBounds(); } catch (Throwable ignored) {}
            // Floor the frame minimum so the OS honors the 150px collapsed size.
            try { setMinimumSize(new Dimension(MINI_SIZE, MINI_SIZE)); }
            catch (Throwable ignored) {}
            morphFrom.setBounds(getX(), getY(), getWidth(), getHeight());
            try {
                Rectangle max = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getMaximumWindowBounds();
                morphTo.setBounds(max.x + max.width - MINI_SIZE - MINI_PAD,
                                  max.y + max.height - MINI_SIZE - MINI_PAD,
                                  MINI_SIZE, MINI_SIZE);
            } catch (Throwable t) {
                Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
                morphTo.setBounds(scr.width - MINI_SIZE - MINI_PAD,
                                  scr.height - MINI_SIZE - MINI_PAD - 48,
                                  MINI_SIZE, MINI_SIZE);
            }
            morphCollapsing = true;
            morphT0 = System.currentTimeMillis();
            ensureMorphTimer();
        } catch (Throwable ignored) {}
    }

    private void animateToExpandedOnEdt() {
        try {
            // Reveal chrome immediately so it fades back in during the glide.
            setChromeHidden(false);
            setChromeEnabled(true);
            try { setShape(null); } catch (Throwable ignored) {}
            try { setAlwaysOnTop(false); } catch (Throwable ignored) {}
            try { setFocusableWindowState(true); } catch (Throwable ignored) {}
            if (s.morphProgress <= 0.01f) {
                finishExpand();
                return;
            }
            morphFrom.setBounds(getX(), getY(), getWidth(), getHeight());
            Rectangle r = companionSavedBounds != null ? companionSavedBounds : normalBounds;
            if (r != null) morphTo.setBounds(r.x, r.y, r.width, r.height);
            else morphTo.setBounds(morphFrom);
            morphCollapsing = false;
            morphT0 = System.currentTimeMillis();
            ensureMorphTimer();
        } catch (Throwable ignored) {}
    }

    private void ensureMorphTimer() {
        if (morphTimer == null) {
            morphTimer = new javax.swing.Timer(16, e -> morphTick());
            morphTimer.setCoalesce(true);
            morphTimer.start();
        } else {
            morphTimer.restart();
        }
    }

    /** 60 FPS morph tick (EDT). Zero allocation: primitives + reused rects only. */
    private void morphTick() {
        try {
            long now = System.currentTimeMillis();
            double t = (now - morphT0) / (double) MORPH_MS;
            if (t >= 1) t = 1;
            else if (t < 0) t = 0;
            double e = morphEase(t);
            int cx = (int) Math.round(morphFrom.x + (morphTo.x - morphFrom.x) * e);
            int cy = (int) Math.round(morphFrom.y + (morphTo.y - morphFrom.y) * e);
            int cw = (int) Math.round(morphFrom.width + (morphTo.width - morphFrom.width) * e);
            int ch = (int) Math.round(morphFrom.height + (morphTo.height - morphFrom.height) * e);
            setBounds(cx, cy, cw, ch);
            float p = morphCollapsing ? (float) e : (float) (1 - e);
            s.morphProgress = p;
            if (p > 0.85f) setChromeEnabled(false);
            repaint(); // frame-wide repaint drives the chrome cross-fade
            if (t >= 1) {
                morphTimer.stop();
                if (morphCollapsing) finishCollapse();
                else finishExpand();
            }
        } catch (Throwable ignored) {}
    }

    private void finishCollapse() {
        try {
            setBounds(morphTo.x, morphTo.y, morphTo.width, morphTo.height);
            s.morphProgress = 1f;
            chromeHidden = false; // force re-apply (mode may have flipped mid-glide)
            setChromeHidden(true);
            try { setShape(new Ellipse2D.Double(0, 0, MINI_SIZE, MINI_SIZE)); }
            catch (Throwable ignored) {}
            try { setAlwaysOnTop(true); } catch (Throwable ignored) {}
            // Yield OS focus so Robot keystrokes route to the target application.
            try {
                setFocusableWindowState(false);
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .clearGlobalFocusOwner();
            } catch (Throwable ignored) {}
            repaint();
        } catch (Throwable ignored) {}
    }

    private void finishExpand() {
        try {
            Rectangle r = companionSavedBounds != null ? companionSavedBounds : normalBounds;
            if (r != null) setBounds(r.x, r.y, r.width, r.height);
            try { setMinimumSize(new Dimension(780, 500)); } catch (Throwable ignored) {}
            try { setShape(null); } catch (Throwable ignored) {}
            try { setAlwaysOnTop(false); } catch (Throwable ignored) {}
            try { setFocusableWindowState(true); } catch (Throwable ignored) {}
            s.morphProgress = 0f;
            chromeHidden = true; // force re-apply so voice-mode dock stays hidden
            setChromeHidden(false);
            setChromeEnabled(true);
            setExtendedState(Frame.NORMAL);
            toFront();
            try { requestFocus(); } catch (Throwable ignored) {}
            repaint();
        } catch (Throwable ignored) {}
    }

    private void setChromeHidden(boolean hidden) {
        if (chromeHidden == hidden) return;
        chromeHidden = hidden;
        boolean show = !hidden;
        titleBar.setVisible(show);
        rail.setVisible(show);
        topBar.setVisible(show);
        stage.headline.setVisible(show);
        status.setVisible(show);
        dock.setVisible(show && s.mode != AppMode.VOICE);
    }

    private void setChromeEnabled(boolean on) {
        if (chromeEnabledState == on) return;
        chromeEnabledState = on;
        titleBar.setEnabled(on);
        rail.setEnabled(on);
        topBar.setEnabled(on);
        stage.headline.setEnabled(on);
        status.setEnabled(on);
        dock.setEnabled(on);
    }

    /** Called (any thread) after plan parse, before first Step: morph to corner. */
    void enterCompanionMode(int totalSteps) {
        SwingUtilities.invokeLater(this::closeSettings);
        s.abortTask = false;
        s.companionDone = false;
        s.companionMode = true;
        s.companionStep = 0;
        s.companionTotal = totalSteps;
        s.companionLabel = "Starting...";
        setThinking(true);
        animateToMini();
    }

    /** Called when steps finish and PROTON is ready to speak: morph back. */
    void exitCompanionMode(boolean completed) {
        s.companionMode = false;
        if (!completed) s.companionDone = false;
        setThinking(false);
        animateToExpanded();
        if (completed) {
            // Clear the completion pulse shortly after restore so the orb idles.
            javax.swing.Timer clear = new javax.swing.Timer(800, e -> {
                s.companionDone = false;
            });
            clear.setRepeats(false);
            clear.start();
        } else {
            s.companionDone = false;
        }
    }

    /** Mini-orb click / Escape: abort background execution, morph back now. */
    void abortCompanion() {
        s.abortTask = true;
        queryGen++;
        try { brain.stopSpeaking(); } catch (Throwable ignored) {}
        s.companionMode = false;
        s.companionDone = false;
        setThinking(false);
        animateToExpanded();
    }

    /* ---------- Settings card ---------- */

    void toggleSettings() {
        if (settingsPanel != null && settingsPanel.isVisible()) { closeSettings(); return; }
        if (settingsPanel == null) {
            settingsPanel = new SettingsPanel(Prefs.getBool("wake", false),
                    on -> setWakeEnabled(on), this::closeSettings,
                    NoiseSuppressor.level, lvl -> setNoiseLevel(lvl),
                    () -> {
                        closeSettings();
                        new SetupWindow(true).setVisible(true);
                    });
            getLayeredPane().add(settingsPanel, JLayeredPane.POPUP_LAYER);
        }
        settingsPanel.setWake(Prefs.getBool("wake", false));
        settingsPanel.setNoise(NoiseSuppressor.level);
        IconButton gear = rail.btns[3];
        Point p = SwingUtilities.convertPoint(gear, gear.getWidth() + 14, 0, getLayeredPane());
        Dimension d = settingsPanel.getPreferredSize();
        int y = Math.max(8, Math.min(p.y, getLayeredPane().getHeight() - d.height - 8));
        settingsPanel.setBounds(p.x, y, d.width, d.height);
        settingsPanel.setVisible(true);
        getLayeredPane().repaint();
    }

    /** 0 = off, 1 = normal, 2 = strong; applies instantly to every microphone path and is remembered. */
    void setNoiseLevel(int lvl) {
        lvl = Math.max(0, Math.min(2, lvl));
        NoiseSuppressor.level = lvl;
        Prefs.setInt("noise", lvl);
    }

    void closeSettings() {
        if (settingsPanel != null && settingsPanel.isVisible()) {
            settingsPanel.setVisible(false);
            getLayeredPane().repaint();
        }
        rail.setModeMarker(s.mode == AppMode.VOICE ? 1 : 0);
    }

    /* ---------- Wake word ("Hey Proton") + system tray ---------- */

    private void setWakeEnabled(boolean on) {
        Prefs.setBool("wake", on);
        if (on) {
            startWake();
            s.transcript = "\u201C wake word on -- say Hey Proton \u201D";
        } else {
            stopWake();
            removeTray();
            s.transcript = "\u201C wake word off \u201D";
        }
        s.transcriptTtl = 3.5f;
    }

    private void startWake() {
        ensureTray();
        if (wake == null || !wake.isAlive()) {
            wake = new WakeListener(brain, new WakeListener.Cb() {
                public boolean canListen() {
                    return Prefs.getBool("wake", false) && !s.micActive && !s.speaking
                            && !s.thinking && !s.companionMode && s.mode != AppMode.VOICE
                            && !siriActive;
                }
                public void onWake(String remainder) {
                    SwingUtilities.invokeLater(() -> wakeUp(remainder));
                }
            });
            wake.start();
        }

        // Global "hold W" activation (works while Proton is closed to the tray).
        if (wHotkey == null || !wHotkey.isAlive()) {
            wHotkey = new WHotkey(new WHotkey.Cb() {
                public boolean armed() { return openFromWKeyAllowed(); }
                public void triggered() {
                    SwingUtilities.invokeLater(() -> wakeUp(null));
                }
            });
            wHotkey.start();
        }
    }

    private void stopWake() {
        WakeListener w = wake;
        wake = null;
        if (w != null) w.shutdownListener();
        WHotkey k = wHotkey;
        wHotkey = null;
        if (k != null) k.shutdownHotkey();
    }

    /** The long-press-W shortcut only fires while Proton is in the background. */
    private boolean openFromWKeyAllowed() {
        if (!Prefs.getBool("wake", false)) return false;
        if (siriActive || s.micActive || s.speaking || s.thinking) return false;
        if (s.companionMode) return false;
        try {
            if (isVisible() && (getExtendedState() & Frame.ICONIFIED) == 0) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * "Hey Proton" heard: awaken the Siri-style floating overlay instead of
     * opening the main window. The overlay runs its own mic -> STT -> Brain
     * loop so the animation and AI work independently.
     */
    private void wakeUp(String remainder) {
        if (siriActive) return;
        // Take over cleanly from any main-window mic, speech or pending turn.
        queryGen++;
        try { if (wake != null) wake.releaseMic(); } catch (Throwable ignored) {}
        if (s.micActive) cancelMic(null);
        try { brain.stopSpeaking(); } catch (Throwable ignored) {}
        s.speaking = false;
        s.abortTask = false;
        setThinking(false);
        if (siri == null) siri = new SiriOverlay(this, s, brain);
        siri.activate(remainder);
    }

    /** True while the main window's own ticker is already advancing shared S. */
    boolean isAnimatingForSiri() {
        return !inTray; // tick() only skips its animation work while parked in the tray
    }

    /** The wake overlay finished its dissolve; Proton keeps listening silently. */
    void onSiriFinished() {
        siriActive = false;
        lastNanos = System.nanoTime();
    }

    private void ensureTray() {
        if (trayIcon != null || !SystemTray.isSupported()) return;
        try {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(0x3D7BFF), 32, 32, new Color(0xFF4DD0)));
            g.fill(new RoundRectangle2D.Float(1, 1, 30, 30, 10, 10));
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("P", (32 - fm.stringWidth("P")) / 2, (32 + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();

            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open PROTON");
            open.addActionListener(e -> SwingUtilities.invokeLater(this::showFromTray));
            MenuItem off = new MenuItem("Turn off wake word");
            off.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                setWakeEnabled(false);
                if (settingsPanel != null) settingsPanel.setWake(false);
                showFromTray();
            }));
            MenuItem quit = new MenuItem("Quit");
            quit.addActionListener(e -> SwingUtilities.invokeLater(this::shutdown));
            menu.add(open); menu.add(off); menu.addSeparator(); menu.add(quit);

            TrayIcon ti = new TrayIcon(img, "PROTON AI -- say \"Hey Proton\"", menu);
            ti.setImageAutoSize(true);
            ti.addActionListener(e -> SwingUtilities.invokeLater(this::showFromTray));
            SystemTray.getSystemTray().add(ti);
            trayIcon = ti;
        } catch (Throwable ignored) { trayIcon = null; }
    }

    private void removeTray() {
        TrayIcon ti = trayIcon;
        trayIcon = null;
        if (ti != null) {
            try { SystemTray.getSystemTray().remove(ti); } catch (Throwable ignored) {}
        }
    }

    /** Close button / Escape: with the wake word on, hide to the tray instead of quitting. */
    void closeRequest() {
        if (Prefs.getBool("wake", false) && SystemTray.isSupported()) hideToTray();
        else shutdown();
    }

    private void hideToTray() {
        ensureTray();
        if (trayIcon == null) { shutdown(); return; }
        closeSettings();
        // A live Siri wake conversation owns the mic/speech; just hide the frame.
        if (!siriActive) {
            queryGen++;
            try { brain.stopSpeaking(); } catch (Throwable ignored) {}
            if (s.mode == AppMode.VOICE) setAssistantMode();
            if (s.micActive) cancelMic(null);
            s.speaking = false;
            setThinking(false);
        }
        inTray = true;
        setVisible(false);
    }

    private void showFromTray() {
        inTray = false;
        lastNanos = System.nanoTime();
        try {
            setVisible(true);
            setExtendedState(Frame.NORMAL);
            if (!s.companionMode) {
                setAlwaysOnTop(true);
                toFront();
                requestFocus();
                setAlwaysOnTop(false);
            } else {
                toFront();
            }
        } catch (Throwable ignored) {}
        repaint();
    }

    void shutdown() {
        stopWake();
        removeTray();
        if (ticker != null) ticker.stop();
        if (micThread != null) micThread.stopCapture();
        brain.stopSpeaking();
        try { if (companion != null) companion.disposeOverlay(); } catch (Throwable ignored) {}
        try { if (siri != null) siri.disposeOverlay(); } catch (Throwable ignored) {}
        try { if (thought != null) thought.disposeWindow(); } catch (Throwable ignored) {}
        dispose();
        System.exit(0);
    }

    /** Test-only: dock visibility probe (headless-safe, no side effects). */
    boolean getDockVisibleForTest() { return dock.isVisible(); }

    /** Test-only: rail visibility probe (chrome handoff check). */
    boolean getRailVisibleForTest() { return rail.isVisible(); }

    /** Test-only: full teardown without killing the JVM. */
    void shutdownNoExitForTest() {
        if (ticker != null) ticker.stop();
        if (micThread != null) micThread.stopCapture();
        try { brain.stopSpeaking(); } catch (Throwable ignored) {}
        try { if (companion != null) companion.disposeOverlay(); } catch (Throwable ignored) {}
        try { if (siri != null) siri.disposeOverlay(); } catch (Throwable ignored) {}
        try { if (thought != null) thought.disposeWindow(); } catch (Throwable ignored) {}
        dispose();
    }

    void expandComposer()   { s.composerExpanded = true; composer.setExpanded(true); }
    void collapseComposer() {
        s.composerExpanded = false;
        composer.setExpanded(false);
        composer.input.setText("");
        composer.input.transferFocus();
    }

    private float simLevel(double dt) {
        s.simPhase += dt;
        double a = Math.abs(Math.sin(s.simPhase * 2.1));
        double b = Math.abs(Math.sin(s.simPhase * 5.7 + 1.3));
        double c = Math.abs(Math.sin(s.simPhase * 11.3 + 0.7));
        float v = (float) (
            (0.12 + a * 0.30 + b * 0.12 + c * 0.05) *
            (0.62 + Math.random() * 0.38));
        if (v < 0.08f) v = 0.08f;
        if (v > 1f)    v = 1f;
        return v;
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1e9;
        lastNanos = now;
        if (dt > 0.05) dt = 0.05;
        if (dt < 0)    dt = 0.016;
        if (inTray) return; // window hidden in the tray: skip all animation work

        if (s.micActive) {
            float sim = simLevel(dt);
            if (s.micLive) s.targetLevel = Math.max(s.liveLevel, 0.06f + sim * 0.10f);
            else           s.targetLevel = sim;
        } else if (s.speaking) {
            s.targetLevel = simLevel(dt) * 0.95f;
        } else {
            s.targetLevel = 0f;
        }

        float k = s.targetLevel > s.level ? 0.34f : 0.070f;
        s.level += (s.targetLevel - s.level) * k;
        if (s.level < 0.0005f) s.level = 0f;

        float tp = s.thinking ? 1f : 0f;
        float rate = s.thinking ? 1.9f : 2.6f;
        s.thinkPhase += (tp - s.thinkPhase) * Math.min(1f, (float)(dt * rate));
        if (Math.abs(s.thinkPhase - tp) < 0.002f) s.thinkPhase = tp;
        // Companion bot working animation: accelerate orbital runners.
        double workBoost = s.companionMode ? 2.4 : 1.0;
        if (s.thinking) s.thinkTime += dt * (s.companionMode ? 2.5 : 1.0);

        s.noiseT   += dt * workBoost * (0.80 + s.level * 2.6 + s.thinkPhase * 0.45);
        s.rotY     += dt * workBoost * (0.14 + s.level * 0.62) * (1 - s.thinkPhase * 0.72);
        s.ringTime += dt * workBoost;

        float ce = s.composerExpanded ? 1f : 0f;
        s.composerExpand += (ce - s.composerExpand) * Math.min(1f, (float)(dt * 7.5));
        if (Math.abs(s.composerExpand - ce) < 0.002f) s.composerExpand = ce;

        if (s.transcriptTtl > 0f) {
            s.transcriptTtl -= (float) dt;
            if (s.transcriptTtl < 0f) s.transcriptTtl = 0f;
        }
        float tTarget = s.transcriptTtl > 0f ? 1f : 0f;
        s.transcriptAlpha += (tTarget - s.transcriptAlpha) * Math.min(1f, (float)(dt * 4.5));
        float hTarget = 1f - s.transcriptAlpha;
        s.hintAlpha += (hTarget - s.hintAlpha) * Math.min(1f, (float)(dt * 4.5));

        if (composer.mic.active != s.micActive) composer.mic.setActive(s.micActive);
        // Rail markers strictly reflect operating mode (Assistant vs. Voice),
        // never transient mic level -- managed by setAssistantMode/setVoiceMode.
        int wantMarker = (s.mode == AppMode.VOICE) ? 1 : 0;
        if (rail.getModeMarker() != wantMarker) rail.setModeMarker(wantMarker);

        dock.doLayout();
        composer.doLayout();

        orb.repaint();
        status.repaint();
        dock.repaint();

        long hash = (s.thinking ? 1L : 0L) | (s.micActive ? 2L : 0L)
                  | (s.micLive ? 4L : 0L) | (s.speaking ? 8L : 0L)
                  | (s.micHeard ? (1L << 20) : 0L)
                  | (s.companionMode ? (1L << 21) : 0L) | (s.companionDone ? (1L << 22) : 0L)
                  | ((long)(s.composerExpand * 8) << 4);
        if (hash != s.lastStatusHash) {
            s.lastStatusHash = hash;
            rail.repaint();
            topBar.repaint();
            stage.headline.repaint();
        }
    }

    /* ---------- microphone / voice assistant ---------- */

    void toggleMic() {
        if (s.mode == AppMode.VOICE) {
            // Voice Mode owns the mic loop; manual toggles are no-ops.
            if (!s.micActive) startMic();
            return;
        }
        // Assistant Mode: single-turn utterance, no auto-rearm.
        if (s.micActive) stopMic();      // send what has been said right now
        else startMic();
    }

    private void startMic() {
        // barge-in: silence any reply and invalidate pending ones
        queryGen++;
        brain.stopSpeaking();
        Brain.refreshContextAsync();   // foreground window + open apps are ready by the time you finish speaking
        s.speaking = false;
        if (s.thinking) setThinking(false);

        s.micActive = true;
        s.micLive = false;
        s.micHeard = false;
        s.micStart = System.currentTimeMillis();
        s.micLastData = 0L;
        s.liveLevel = 0f;
        s.targetLevel = 0.65f;

        final MicCapture mc = new MicCapture(s, new MicListener() {
            public void onSpeechEnd(MicCapture src) {
                SwingUtilities.invokeLater(() -> { if (micThread == src) stopMic(); });
            }
            public void onNoSpeech(MicCapture src) {
                SwingUtilities.invokeLater(() -> {
                    if (micThread == src) {
                        cancelMic("\u201C I didn't hear anything \u201D");
                        // Voice Mode loops without manual presses; silence re-arms.
                        if (s.mode == AppMode.VOICE) rearmVoiceAfterPause(250, queryGen);
                    }
                });
            }
            public void onFail(MicCapture src) {
                SwingUtilities.invokeLater(() -> {
                    // Hardware failure: surface the message, do not hot-loop a dead mic.
                    if (micThread == src) cancelMic("\u201C microphone unavailable \u201D");
                });
            }
        });
        micThread = mc;
        mc.start();

        composer.mic.setActive(true);
    }

    /**
     * Voice Mode hands-free loop helper. May be called from any background
     * thread: pauses off-EDT, then re-arms the listener on the EDT only if
     * still in Voice Mode for the same generation.
     */
    private void rearmVoiceAfterPause(long pauseMs, int gen) {
        if (s.mode != AppMode.VOICE || !s.continuousVoice) return;
        new Thread(() -> {
            try { Thread.sleep(pauseMs); } catch (InterruptedException ignored) { return; }
            if (gen != queryGen) return;
            if (s.mode != AppMode.VOICE || !s.continuousVoice || s.micActive) return;
            SwingUtilities.invokeLater(() -> {
                if (gen != queryGen) return;
                if (s.mode != AppMode.VOICE || !s.continuousVoice || s.micActive) return;
                startMic();
            });
        }, "voice-rearm").start();
    }

    private void cancelMic(String msg) {
        s.micActive = false;
        s.micLive = false;
        s.micHeard = false;
        s.targetLevel = 0f;
        composer.mic.setActive(false);
        MicCapture t = micThread;
        micThread = null;
        if (t != null) t.stopCapture();
        if (msg != null) {
            s.transcript = msg;
            s.transcriptTtl = 3.5f;
        }
    }

    private void stopMic() {
        s.micActive = false;
        s.micLive = false;
        s.micHeard = false;
        s.targetLevel = 0f;
        composer.mic.setActive(false);

        final MicCapture t = micThread;
        micThread = null;
        if (t == null) return;

        final int gen = queryGen;

        new Thread(() -> {
            byte[] pcm = t.stopAndGetAudio();
            t.stopCapture();
            if (pcm == null || pcm.length < 6000) {
                SwingUtilities.invokeLater(() -> {
                    if (gen != queryGen) return;
                    s.transcript = "\u201C heard nothing \u201D";
                    s.transcriptTtl = 3f;
                });
                // Assistant single-turn stops here; Voice Mode listens again.
                if (s.mode == AppMode.VOICE) rearmVoiceAfterPause(250, gen);
                return;
            }
            try {
                SwingUtilities.invokeLater(() -> { if (gen == queryGen) setThinking(true); });
                String text = brain.transcribe(pcm);
                if (gen != queryGen) return;
                if (text == null || text.trim().length() < 2) {
                    SwingUtilities.invokeLater(() -> {
                        if (gen != queryGen) return;
                        setThinking(false);
                        s.transcript = "\u201C no speech detected \u201D";
                        s.transcriptTtl = 3f;
                    });
                    if (s.mode == AppMode.VOICE) rearmVoiceAfterPause(250, gen);
                    return;
                }
                final String t2 = text.trim();
                SwingUtilities.invokeLater(() -> { if (gen == queryGen) submitQuery(t2); });
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    if (gen != queryGen) return;
                    s.transcript = "\u201C stt error: " + ex.getMessage() + " \u201D";
                    s.transcriptTtl = 5f;
                    setThinking(false);
                });
                if (s.mode == AppMode.VOICE) rearmVoiceAfterPause(500, gen);
            }
        }, "stt").start();
    }

    void setThinking(boolean on) {
        s.thinking = on;
        if (on) s.thinkTime = 0;
        composer.deep.setActive(on);
        rail.repaint();
        topBar.repaint();
        stage.headline.repaint();
        status.repaint();
    }

    void toggleThinking() { setThinking(!s.thinking); }

    void submitQuery(String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String u = text.trim();
        final int gen = ++queryGen;
        s.transcript = "\u201C" + u + "\u201D";
        s.transcriptTtl = 4.6f;
        setThinking(true);
        Brain.refreshContextAsync();
        brain.earlySpeaker = spoken -> {
            SwingUtilities.invokeLater(() -> {
                if (gen != queryGen) return;
                s.transcript = "\u201C" + spoken + "\u201D";
                s.transcriptTtl = 6.5f;
            });
            s.speaking = true;
            try { brain.speak(spoken); }
            finally { s.speaking = false; }
        };

        new Thread(() -> {
            try {
                String reply = brain.process(u);
                if (reply == null || reply.trim().isEmpty()) reply = "done.";
                final String r = reply.trim();
                if (gen != queryGen) return;
                SwingUtilities.invokeLater(() -> {
                    if (gen != queryGen) return;
                    s.transcript = "\u201C" + r + "\u201D";
                    s.transcriptTtl = 6.5f;
                    setThinking(false);
                });
                if (gen != queryGen) return;
                // SPEAKING phase: orb/status reflect s.speaking via tick().
                // (If Proton already acknowledged out loud while working, only the follow-up is spoken here.)
                String toSay = brain.remainingSpeech(r);
                if (toSay != null && !toSay.trim().isEmpty()) {
                    s.speaking = true;
                    try { brain.speak(toSay); }
                    finally { s.speaking = false; }
                }
                // Voice Mode hands-free loop only (Assistant mic is single-turn).
                // STANDBY (speaking=false) -> 450ms cooldown -> LISTENING (startMic).
                if (gen == queryGen && s.mode == AppMode.VOICE && s.continuousVoice && !s.micActive) {
                    try { Thread.sleep(450); } catch (InterruptedException ignored) {}
                    if (gen == queryGen && s.mode == AppMode.VOICE && s.continuousVoice && !s.micActive) {
                        SwingUtilities.invokeLater(() -> {
                            if (gen == queryGen && s.mode == AppMode.VOICE
                                    && s.continuousVoice && !s.micActive) startMic();
                        });
                    }
                }
            } catch (Throwable ex) {
                s.speaking = false;
                if (gen != queryGen) return;
                final String m = String.valueOf(ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    if (gen != queryGen) return;
                    s.transcript = "\u201C error: " + m + " \u201D";
                    s.transcriptTtl = 6f;
                    setThinking(false);
                });
                // Re-arm even after errors so the Voice Mode loop never dies.
                if (gen == queryGen && s.mode == AppMode.VOICE && s.continuousVoice && !s.micActive) {
                    try { Thread.sleep(450); } catch (InterruptedException ignored) {}
                    if (gen == queryGen && s.mode == AppMode.VOICE && s.continuousVoice && !s.micActive) {
                        SwingUtilities.invokeLater(() -> {
                            if (gen == queryGen && s.mode == AppMode.VOICE
                                    && s.continuousVoice && !s.micActive) startMic();
                        });
                    }
                }
            }
        }, "brain").start();
    }
}

/* =============================================================== */
/*  MIC CAPTURE  (records + voice activity detection)              */
/* =============================================================== */

/* =============================================================== */
/*  SETTINGS STORE + WAKE WORD ("Hey Proton")                      */
/* =============================================================== */

final class Prefs {
    private static final File F = new File(System.getProperty("user.home"), ".proton-ai.properties");
    private static final Properties P = new Properties();
    static {
        try (InputStream in = new FileInputStream(F)) { P.load(in); } catch (Throwable ignored) {}
    }
    static synchronized boolean getBool(String k, boolean d) {
        String v = P.getProperty(k);
        return v == null ? d : "true".equalsIgnoreCase(v.trim());
    }
    static synchronized void setBool(String k, boolean v) {
        P.setProperty(k, v ? "true" : "false");
        try (OutputStream o = new FileOutputStream(F)) { P.store(o, "PROTON AI settings"); }
        catch (Throwable ignored) {}
    }
    static synchronized int getInt(String k, int d) {
        String v = P.getProperty(k);
        try { return v == null ? d : Integer.parseInt(v.trim()); } catch (Exception e) { return d; }
    }
    static synchronized void setInt(String k, int v) {
        P.setProperty(k, Integer.toString(v));
        try (OutputStream o = new FileOutputStream(F)) { P.store(o, "PROTON AI settings"); }
        catch (Throwable ignored) {}
    }
    static synchronized String getString(String k, String d) {
        String v = P.getProperty(k);
        return v == null ? d : v;
    }
    static synchronized void setString(String k, String v) {
        if (v == null) P.remove(k); else P.setProperty(k, v);
        try (OutputStream o = new FileOutputStream(F)) { P.store(o, "PROTON AI settings"); }
        catch (Throwable ignored) {}
    }
}

/* =============================================================== */
/*  GITHUB UPDATER -- checks Hellototech/Proton for new commits,    */
/*  installs the newest main.java and recompiles it.                */
/* =============================================================== */

final class UpdateKit {
    static final String OWNER  = "Hellototech";
    static final String REPO   = "Proton";
    static final String BRANCH = "main";
    static final String COMMITS_API = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/commits?per_page=1";
    static final String ZIP_URL     = "https://codeload.github.com/" + OWNER + "/" + REPO + "/zip/refs/heads/" + BRANCH;
    private static final String UA  = "PROTON-AI-Updater/1.0";

    static final int IDLE = 0, CHECKING = 1, AVAILABLE = 2, UP_TO_DATE = 3,
                     INSTALLING = 4, UPDATED = 5, FAILED = 6;

    static volatile int state = IDLE;
    static volatile String message = "Check GitHub for a newer version of PROTON.";
    static volatile boolean auto = Prefs.getBool("update.auto", true);
    private static volatile String latestSha = "";
    private static volatile Runnable repaintHook;
    private static volatile boolean busy = false;
    private static boolean watching = false;
    private static final Object LOCK = new Object();

    private UpdateKit() {}

    static void setRepaintHook(Runnable r) { repaintHook = r; }

    static String buttonLabel() {
        if (state == CHECKING) return "Checking\u2026";
        if (state == INSTALLING) return "Updating\u2026";
        return "Update";
    }

    static Color statusColor() {
        if (state == UP_TO_DATE || state == UPDATED) return new Color(0x3DDC97);
        if (state == AVAILABLE) return new Color(0xC9A0FF);
        if (state == FAILED) return new Color(0xFFB86B);
        if (state == CHECKING || state == INSTALLING) return new Color(0x9A9CB4);
        return Theme.TEXT3;
    }

    static boolean isBusy() {
        return state == CHECKING || state == INSTALLING;
    }

    /** Slider in Settings: on by default, remembered across launches. */
    static void setAuto(boolean on) {
        auto = on;
        Prefs.setBool("update.auto", on);
        if (on) checkAsync(false);
        fire();
    }

    /** The Update button: install a known update, otherwise check GitHub. */
    static void pressUpdate() {
        if (isBusy()) return;
        if (state == UPDATED) return;                 // installed, waiting for restart
        if (state == AVAILABLE && !latestSha.isEmpty()) startInstallAsync(true);
        else checkAsync(true);
    }

    static void checkAsync(final boolean manual) {
        synchronized (LOCK) {
            if (busy) return;
            busy = true;
        }
        Thread t = new Thread(() -> {
            try { runCheck(manual); }
            catch (Throwable ex) { fail(ex); }
            finally { synchronized (LOCK) { busy = false; } }
        }, "proton-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void runCheck(boolean manual) throws Exception {
        set(CHECKING, "Checking GitHub for new commits\u2026");
        Commit c = fetchLatest();
        String known = Prefs.getString("update.commit", "");
        if (known.isEmpty()) {
            // First ever check: this build is the starting point.
            Prefs.setString("update.commit", c.sha);
            latestSha = c.sha;
            set(UP_TO_DATE, "Up to date.");
            return;
        }
        if (known.equalsIgnoreCase(c.sha)) {
            latestSha = c.sha;
            set(UP_TO_DATE, "Up to date.");
            return;
        }
        latestSha = c.sha;
        if (manual || auto) installBlocking(manual);
        else set(AVAILABLE, "Update available \u00B7 " + shortSha(c.sha));
    }

    private static void startInstallAsync(final boolean restartWhenDone) {
        synchronized (LOCK) {
            if (busy) return;
            busy = true;
        }
        Thread t = new Thread(() -> {
            try { installBlocking(restartWhenDone); }
            catch (Throwable ex) { fail(ex); }
            finally { synchronized (LOCK) { busy = false; } }
        }, "proton-update-install");
        t.setDaemon(true);
        t.start();
    }

    /** Downloads main.java from the latest commit, verifies it compiles, then swaps it in. */
    private static void installBlocking(boolean restartWhenDone) throws Exception {
        String sha = latestSha;
        if (sha.isEmpty()) { set(UP_TO_DATE, "Up to date."); return; }
        File tmp = Files.createTempDirectory("proton-update").toFile();
        try {
            set(INSTALLING, "Downloading update\u2026");
            File src = fetchRemoteSource(tmp);
            File appDir = appDir();
            File classesTarget = classesDir(appDir);
            File target = new File(appDir, "main.java");
            File backup = new File(appDir, "main.java.bak");

            // Compile in an isolated folder first: never touch the app unless the
            // downloaded source builds cleanly.
            set(INSTALLING, "Checking update\u2026");
            File buildDir = new File(tmp, "build");
            compile(src, buildDir);

            set(INSTALLING, "Installing update\u2026");
            if (target.isFile()) copyFile(target, backup);
            try {
                copyFile(src, target);
                // Clear old .class files in app\classes\ when packaged so removed inner classes don't linger
                if (isPackaged() && classesTarget.isDirectory()) {
                    File[] old = classesTarget.listFiles((d, n) -> n.endsWith(".class"));
                    if (old != null) for (File f : old) f.delete();
                }
                copyTree(buildDir, classesTarget);
            } catch (IOException ce) {
                if (backup.isFile()) copyFile(backup, target);
                throw ce;
            }
            Prefs.setString("update.commit", sha);
            String label = shortSha(sha);

            if (restartWhenDone) {
                set(UPDATED, "Updated to " + label + " \u00B7 restarting\u2026");
                MemoryToast.show("PROTON updated", label + " installed \u00B7 restarting", 0);
                try { Thread.sleep(1600); } catch (InterruptedException ignored) {}
                SwingUtilities.invokeLater(main::restartApp);
            } else {
                set(UPDATED, "Updated to " + label + " \u00B7 restart to finish.");
                MemoryToast.show("PROTON updated", label + " installed \u00B7 restart to finish", 0);
            }
        } finally {
            deleteTree(tmp);
        }
    }

    private static Commit fetchLatest() throws IOException {
        String json = httpGet(COMMITS_API);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"sha\"\\s*:\\s*\"([0-9a-fA-F]{40})\"").matcher(json);
        if (!m.find()) throw new IOException("GitHub returned an unexpected answer.");
        Commit c = new Commit();
        c.sha = m.group(1);
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (mm.find()) c.message = unescapeJson(mm.group(1));
        return c;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code == 403 || code == 429)
                throw new IOException("GitHub is busy \u00B7 try again in a minute.");
            if (code >= 400) throw new IOException("GitHub is unreachable right now.");
            return readAll(c.getInputStream());
        } catch (IllegalArgumentException ex) {
            throw new IOException("GitHub is unreachable right now.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void httpDownload(String url, File dest) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(12000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Couldn't download the update.");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Couldn't download the update.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static File fetchRemoteSource(File tmp) throws IOException {
        File zip = new File(tmp, "proton.zip");
        httpDownload(ZIP_URL, zip);
        File outDir = new File(tmp, "source");
        outDir.mkdirs();
        File out = new File(outDir, "main.java");
        String best = null;
        boolean found = false;
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (!name.endsWith("main.java")) continue;
                if (found && best != null
                        && name.split("/").length > best.split("/").length) continue;
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = zin.read(b)) > 0) o.write(b, 0, n);
                }
                best = name;
                found = true;
            }
        }
        if (!found) throw new IOException("The latest commit has no main.java yet.");
        return out;
    }

    private static void compile(File source, File classesDir) throws IOException {
        if (!classesDir.isDirectory() && !classesDir.mkdirs())
            throw new IOException("Couldn't prepare the update folder.");
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc != null) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int rc;
            try {
                rc = jc.run(null, null, err, "-encoding", "UTF-8", "-d",
                        classesDir.getAbsolutePath(), source.getAbsolutePath());
            } catch (Throwable t) {
                rc = -1;
            }
            if (rc != 0) throw new IOException(compileError(
                    new String(err.toByteArray(), StandardCharsets.UTF_8)));
            return;
        }
        File javac = new File(System.getProperty("java.home", ""),
                "bin" + File.separator + "javac");
        if (!javac.isFile()) {
            javac = new File(System.getProperty("java.home", "") + File.separator + ".."
                    + File.separator + "bin" + File.separator + "javac");
        }
        if (!javac.isFile()) throw new IOException("Java compiler not found on this PC.");
        ProcessBuilder pb = new ProcessBuilder(javac.getAbsolutePath(),
                "-encoding", "UTF-8", "-d", classesDir.getAbsolutePath(), source.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String log = readAll(p.getInputStream());
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroy();
                throw new IOException("Compiling the update timed out.");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("The update was interrupted.");
        }
        if (p.exitValue() != 0) throw new IOException(compileError(log));
    }

    private static String compileError(String log) {
        String s = log == null ? "" : log.trim();
        if (s.isEmpty()) return "The new version didn't compile.";
        String pick = s;
        for (String line : s.split("\\R")) {
            if (line.contains("error:")) { pick = line.trim(); break; }
        }
        if (pick.length() > 110) pick = pick.substring(0, 107) + "\u2026";
        return "Update failed \u00B7 " + pick;
    }

        private static boolean isPackaged() {
        return "true".equalsIgnoreCase(System.getProperty("proton.packaged"));
        }

    /** Folder that holds the running main.java (app\ when packaged, project folder from source). */
    private static File appDir() {
        String prop = System.getProperty("proton.appdir", "");
        if (!prop.isEmpty()) {
            File d = new File(prop);
            if (d.isDirectory()) return d;
        }
        File cwd = new File(System.getProperty("user.dir", "."));
        if (new File(cwd, "main.java").isFile()) return cwd;
        try {
            File f = new File(UpdateKit.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (f.isDirectory()) {
                if ("classes".equals(f.getName()) && f.getParentFile() != null
                        && new File(f.getParentFile(), "main.java").isFile()) {
                    return f.getParentFile();
                }
                return f;
            }
            if (f.isFile() && f.getParentFile() != null) return f.getParentFile();
        } catch (Throwable ignored) {}
        return cwd;
    }

    /** Where compiled .class files live: app\classes\ when packaged, appDir\ from source. */
    private static File classesDir(File appDir) {
        if (isPackaged()) {
            File cls = new File(appDir, "classes");
            if (!cls.isDirectory()) cls.mkdirs();
            return cls;
        }
        return appDir;
    }

    private static void copyFile(File from, File to) throws IOException {
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(File from, File to) throws IOException {
        File[] kids = from.listFiles();
        if (kids == null) return;
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Couldn't write the update.");
        for (File k : kids) {
            File dst = new File(to, k.getName());
            if (k.isDirectory()) copyTree(k, dst);
            else if (k.getName().endsWith(".class")) copyFile(k, dst);
        }
    }

    private static void deleteTree(File f) {
        try {
            if (f == null || !f.exists()) return;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
            f.delete();
        } catch (Throwable ignored) {}
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : (sha.length() > 7 ? sha.substring(0, 7) : sha);
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\n", " ").replace("\\t", " ")
                .replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\");
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void set(int st, String msg) {
        state = st;
        message = msg == null ? "" : msg;
        fire();
    }

    private static void fire() {
        Runnable r = repaintHook;
        if (r != null) {
            try { SwingUtilities.invokeLater(r); } catch (Throwable ignored) {}
        }
    }

    private static void fail(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage());
        if (m == null || m.trim().isEmpty() || "null".equals(m))
            m = "Couldn't check GitHub for updates.";
        set(FAILED, m);
    }

    /** Background auto-update watcher; does nothing while the slider is off. */
    static void startAutoWatch() {
        synchronized (LOCK) {
            if (watching) return;
            watching = true;
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
            while (true) {
                try { if (auto) checkAsync(false); } catch (Throwable ignored) {}
                try { Thread.sleep(10L * 60L * 1000L); } catch (InterruptedException e) { return; }
            }
        }, "proton-update-watch");
        t.setDaemon(true);
        t.start();
    }

    private static final class Commit {
        String sha = "";
        String message = "";
    }
}

final class WakeWord {
    private static final Set<String> GREET = new HashSet<>(Arrays.asList(
        "hey", "hay", "hi", "hello", "okay", "ok", "yo", "hei", "heya", "a", "eh"));
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
        "proton", "protons", "proton's", "protein", "protean", "protoin", "protan",
        "protun", "prouton", "protone", "brotan"));

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
    private static boolean near(String w) {
        return NAMES.contains(w) || lev(w, "proton") <= 1;
    }

    /** Returns the words spoken AFTER "hey proton" (possibly ""), or null if no wake phrase. */
    static String match(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ")
                       .replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        String[] w = t.split(" ");
        int lim = Math.min(w.length, 3);
        for (int i = 0; i < lim; i++) {
            if (!GREET.contains(w[i]) || i + 1 >= w.length) continue;
            int used = 0;
            if (near(w[i + 1])) used = 2;
            else if (w[i + 1].equals("pro") && i + 2 < w.length && w[i + 2].equals("ton")) used = 3;
            if (used == 0) continue;
            int from = i + used;
            StringBuilder b = new StringBuilder();
            for (int k = from; k < w.length; k++) { if (b.length() > 0) b.append(' '); b.append(w[k]); }
            return b.toString();
        }
        // bare "proton" / "proton?" on its own
        if (w.length <= 2 && near(w[0])) {
            StringBuilder b = new StringBuilder();
            for (int k = 1; k < w.length; k++) { if (b.length() > 0) b.append(' '); b.append(w[k]); }
            return b.toString();
        }
        return null;
    }
}

/* =============================================================== */
/*  NOISE CANCELLATION -- real-time mic denoiser (pure Java)       */
/* =============================================================== */

/**
 * Streaming noise suppressor for the microphone: 90 Hz high-pass (rumble / AC hum), then an STFT
 * Wiener filter (decision-directed a-priori SNR, per-bin noise tracking, frequency + time smoothing).
 * It removes steady background noise: fans, air-conditioning, hiss, traffic hum, keyboard-room noise.
 * Adds ~32 ms of delay and keeps the sample count identical (in place).
 */
final class NoiseSuppressor {
    /** 0 = off, 1 = normal, 2 = strong. Shared by every microphone path. */
    static volatile int level = 1;
    static { try { level = Math.max(0, Math.min(2, Prefs.getInt("noise", 1))); } catch (Throwable ignored) {} }

    /** Strong mode also asks for a little more energy before a sound counts as speech. */
    static double vadBoost() { return level >= 2 ? 1.6 : 1.0; }

    private final int N, H, BINS;
    private final float[] win, cosT, sinT;
    private final int[] rev;
    private final float[] re, im, noise, prevClean, gain, tmpGain;
    // minimum-statistics noise tracking (smoothed periodogram, sliding minimum over ~3 s)
    private static final int SUBS = 8, SUBLEN = 24;
    private static final float BIAS = 1.9f;
    private final float[] ps, curMin, initAvg;
    private final float[][] subMin;
    private int subCount = 0, subIdx = 0;
    private float[] inBuf;
    private int inLen = 0;
    private final float[] olap;
    private float[] fifo;
    private int fHead = 0, fLen = 0;
    private int frames = 0;
    private static final int INIT_FRAMES = 10;
    // high-pass biquad (direct form I)
    private final float b0, b1, b2, a1, a2;
    private float x1, x2, y1, y2;

    NoiseSuppressor(float sampleRate) {
        N = sampleRate >= 32000f ? 1024 : 512;
        H = N / 2;
        BINS = N / 2 + 1;
        win = new float[N];
        for (int i = 0; i < N; i++) win[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));   // sqrt-Hann
        cosT = new float[N / 2]; sinT = new float[N / 2];
        for (int k = 0; k < N / 2; k++) { cosT[k] = (float) Math.cos(2 * Math.PI * k / N); sinT[k] = (float) Math.sin(2 * Math.PI * k / N); }
        rev = new int[N];
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        re = new float[N]; im = new float[N];
        noise = new float[BINS]; prevClean = new float[BINS]; gain = new float[BINS]; tmpGain = new float[BINS];
        ps = new float[BINS]; curMin = new float[BINS]; initAvg = new float[BINS];
        subMin = new float[SUBS][BINS];
        inBuf = new float[N + 8192];
        olap = new float[N];
        fifo = new float[N * 4 + 8192];
        fLen = N;   // constant N-sample delay: output length always equals input length
        double w0 = 2 * Math.PI * 90.0 / sampleRate, cs = Math.cos(w0), al = Math.sin(w0) / (2 * 0.7071);
        double a0 = 1 + al;
        b0 = (float) ((1 + cs) / 2 / a0); b1 = (float) (-(1 + cs) / a0); b2 = b0;
        a1 = (float) (-2 * cs / a0); a2 = (float) ((1 - al) / a0);
    }

    /** Cleans 16-bit little-endian mono PCM in place. */
    void processInPlace(byte[] buf, int nBytes) {
        int ns = nBytes / 2;
        if (inLen + ns > inBuf.length) inBuf = java.util.Arrays.copyOf(inBuf, inLen + ns + 4096);
        for (int i = 0; i < ns; i++) {
            short v = (short) ((buf[i * 2 + 1] << 8) | (buf[i * 2] & 0xFF));
            float x = v / 32768f;
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            inBuf[inLen++] = y;
        }
        while (inLen >= N) {
            frame();
            System.arraycopy(inBuf, H, inBuf, 0, inLen - H);
            inLen -= H;
        }
        for (int i = 0; i < ns; i++) {
            float y = fLen > 0 ? fifo[fHead++] : 0f;
            if (fLen > 0) fLen--;
            if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
            int o = Math.round(y * 32767f);
            buf[i * 2] = (byte) (o & 0xFF);
            buf[i * 2 + 1] = (byte) ((o >> 8) & 0xFF);
        }
        if (fLen == 0) fHead = 0;
    }

    private void frame() {
        final int lvl = level;
        final float floor = lvl >= 2 ? 0.05f : 0.18f;
        final float over  = lvl >= 2 ? 1.8f  : 1.3f;
        final float alpha = lvl >= 2 ? 0.94f : 0.90f;

        for (int i = 0; i < N; i++) { re[i] = inBuf[i] * win[i]; im[i] = 0f; }
        fft(false);

        boolean init = frames < INIT_FRAMES;
        for (int k = 0; k < BINS; k++) {
            float p = re[k] * re[k] + im[k] * im[k];
            float nz;
            if (init) {
                initAvg[k] = (initAvg[k] * frames + p) / (frames + 1);
                ps[k] = initAvg[k];
                nz = initAvg[k];
                noise[k] = nz;
            } else {
                if (frames == INIT_FRAMES) {                       // hand over from the start-up average
                    curMin[k] = initAvg[k] / BIAS;
                    for (int u = 0; u < SUBS; u++) subMin[u][k] = initAvg[k] / BIAS;
                }
                ps[k] = 0.88f * ps[k] + 0.12f * p;
                if (ps[k] < curMin[k]) curMin[k] = ps[k];
                float m = curMin[k];
                for (int u = 0; u < SUBS; u++) if (subMin[u][k] < m) m = subMin[u][k];
                nz = BIAS * m;
                noise[k] = nz;
            }
            float nn = nz * over + 1e-12f;
            float gamma = p / nn;
            float xi = alpha * (prevClean[k] / nn) + (1f - alpha) * Math.max(gamma - 1f, 0f);
            float g = xi / (1f + xi);
            gain[k] = init ? floor : Math.max(g, floor);
        }
        if (!init && ++subCount >= SUBLEN) {                        // close a sub-window
            subCount = 0;
            for (int k = 0; k < BINS; k++) { subMin[subIdx][k] = curMin[k]; curMin[k] = ps[k]; }
            subIdx = (subIdx + 1) % SUBS;
        }
        frames++;
        // smooth across frequency (kills "musical noise" speckles)
        tmpGain[0] = gain[0]; tmpGain[BINS - 1] = gain[BINS - 1];
        for (int k = 1; k < BINS - 1; k++) tmpGain[k] = 0.25f * gain[k - 1] + 0.5f * gain[k] + 0.25f * gain[k + 1];
        for (int k = 0; k < BINS; k++) {
            float g = tmpGain[k];
            float p = re[k] * re[k] + im[k] * im[k];
            prevClean[k] = g * g * p;
            re[k] *= g; im[k] *= g;
            if (k > 0 && k < N / 2) { re[N - k] *= g; im[N - k] *= g; }
        }
        fft(true);
        for (int i = 0; i < N; i++) olap[i] += re[i] * win[i];
        // first H samples are complete
        if (fHead + fLen + H > fifo.length) {
            if (fHead > 0) { System.arraycopy(fifo, fHead, fifo, 0, fLen); fHead = 0; }
            if (fLen + H > fifo.length) fifo = java.util.Arrays.copyOf(fifo, fLen + H + 8192);
        }
        System.arraycopy(olap, 0, fifo, fHead + fLen, H);
        fLen += H;
        System.arraycopy(olap, H, olap, 0, N - H);
        java.util.Arrays.fill(olap, N - H, N, 0f);
    }

    private void fft(boolean inverse) {
        final int n = N;
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; j++) {
                    float wr = cosT[j * step], wi = inverse ? sinT[j * step] : -sinT[j * step];
                    int a = i + j, b = a + half;
                    float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
        }
        if (inverse) { float s = 1f / n; for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; } }
    }
}

/** Always-on, low-footprint wake listener: VAD-gated short clips -> STT -> "hey proton". */
class WakeListener extends Thread {
    interface Cb {
        boolean canListen();            // false while the main mic / speech / task owns audio
        void onWake(String remainder);  // called off-EDT
    }
    private final Brain brain;
    private final Cb cb;
    private volatile boolean alive = true;
    private volatile TargetDataLine line;
    private volatile float sr = 16000f;
    private volatile NoiseSuppressor ns;

    WakeListener(Brain b, Cb c) {
        brain = b; cb = c;
        setDaemon(true);
        setName("proton-wake");
    }

    void shutdownListener() {
        alive = false;
        closeLine();
        interrupt();
    }

    private void closeLine() {
        TargetDataLine l = line;
        line = null;
        if (l != null) {
            try { l.stop(); } catch (Exception ignored) {}
            try { l.close(); } catch (Exception ignored) {}
        }
    }

    /** Free the microphone immediately (e.g. a global hotkey just woke Siri). */
    void releaseMic() { closeLine(); }

    private boolean openLine() {
        try {
            AudioFormat fmt = new AudioFormat(16000f, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                fmt = new AudioFormat(44100f, 16, 1, true, false);
                info = new DataLine.Info(TargetDataLine.class, fmt);
            }
            if (!AudioSystem.isLineSupported(info)) return false;
            TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
            l.open(fmt, 8192);
            l.start();
            sr = fmt.getSampleRate();
            ns = new NoiseSuppressor(sr);
            line = l;
            return true;
        } catch (Throwable t) { return false; }
    }

    public void run() {
        byte[] buf = new byte[2048];
        ArrayDeque<byte[]> pre = new ArrayDeque<>();
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        boolean inSpeech = false;
        double noise = 0.006, loudMs = 0, quietMs = 0, segMs = 0;
        while (alive) {
            try {
                if (!brain.hasKey() || !cb.canListen()) {
                    if (line != null) closeLine();
                    inSpeech = false; loudMs = 0; quietMs = 0; segMs = 0;
                    pre.clear(); seg.reset();
                    Thread.sleep(300);
                    continue;
                }
                TargetDataLine l = line;
                if (l == null) {
                    if (!openLine()) { Thread.sleep(3000); continue; }
                    l = line;
                    if (l == null) continue;
                }
                int n = l.read(buf, 0, buf.length);
                if (n <= 0) continue;
                NoiseSuppressor nsp = ns;
                if (NoiseSuppressor.level > 0 && nsp != null) nsp.processInPlace(buf, n & ~1);   // noise cancellation
                double sum = 0;
                int count = n / 2;
                for (int i = 0; i < count; i++) {
                    short v = (short) ((buf[i * 2 + 1] << 8) | (buf[i * 2] & 0xFF));
                    double f = v / 32768.0;
                    sum += f * f;
                }
                double rms = Math.sqrt(sum / Math.max(1, count));
                double chunkMs = n * 1000.0 / (sr * 2);
                double thr = Math.max(0.015, Math.min(0.06, noise * 3.0)) * NoiseSuppressor.vadBoost();

                if (!inSpeech) {
                    pre.addLast(Arrays.copyOf(buf, n));
                    while (pre.size() > 8) pre.removeFirst();
                    if (rms > thr) {
                        loudMs += chunkMs;
                        if (loudMs >= 120) {
                            inSpeech = true;
                            seg.reset();
                            for (byte[] c : pre) seg.write(c, 0, c.length);
                            segMs = 0; quietMs = 0;
                        }
                    } else {
                        loudMs = 0;
                        noise = Math.min(0.02, noise * 0.95 + rms * 0.05);
                    }
                } else {
                    seg.write(buf, 0, n);
                    segMs += chunkMs;
                    if (rms > thr * 0.7) quietMs = 0; else quietMs += chunkMs;
                    if (quietMs > 700 || segMs > 5000) {
                        inSpeech = false; loudMs = 0; pre.clear();
                        byte[] pcm = seg.toByteArray();
                        seg.reset();
                        double voiced = segMs - quietMs;
                        segMs = 0; quietMs = 0;
                        if (voiced >= 350 && cb.canListen()) {
                            String text = null;
                            try { text = brain.transcribeWake(Brain.toWav(pcm, (int) sr)); }
                            catch (Throwable ignored) {}
                            try { l.flush(); } catch (Throwable ignored) {}
                            String rem = WakeWord.match(text);
                            if (rem != null) {
                                closeLine();          // free the mic for the main listener
                                cb.onWake(rem);
                                Thread.sleep(2500);   // let the UI take over before re-arming
                            }
                        }
                    }
                }
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable t) {
                closeLine();
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }
        closeLine();
    }
}

/**
 * Global "hold W" activation. AWT cannot see keys while another app has focus,
 * so a small warm PowerShell helper polls GetAsyncKeyState. The helper stays
 * alive while the wake feature is on; the reader only fires when Proton is in
 * the background (tray/minimised) and idle.
 */
final class WHotkey extends Thread {

    interface Cb {
        boolean armed();
        void triggered();
    }

    private static final long HOLD_MS = 800L;

    private final Cb cb;
    private volatile boolean alive = true;
    private volatile Process proc;
    private volatile Thread reader;

    WHotkey(Cb cb) {
        this.cb = cb;
        setDaemon(true);
        setName("proton-whotkey");
    }

    void shutdownHotkey() {
        alive = false;
        killProc();
        interrupt();
    }

    private void killProc() {
        Process p = proc;
        proc = null;
        if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        Thread r = reader;
        reader = null;
        if (r != null && r != Thread.currentThread()) {
            try { r.join(300); } catch (InterruptedException ignored) {}
        }
    }

    public void run() {
        while (alive) {
            try {
                Process p = proc;
                if (p != null && !p.isAlive()) killProc();
                if (proc == null) startHelper();
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable t) {
                killProc();
                try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
            }
        }
        killProc();
    }

    private void startHelper() {
        try {
            String enc = Base64.getEncoder().encodeToString(
                    script().getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile",
                    "-NonInteractive", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            proc = p;
            Thread r = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (alive && proc == p && (line = br.readLine()) != null) {
                        if (line.trim().equals("WAKE") && cb.armed()) cb.triggered();
                    }
                } catch (Throwable ignored) {}
            }, "proton-whotkey-reader");
            r.setDaemon(true);
            reader = r;
            r.start();
        } catch (Throwable t) {
            killProc();
        }
    }

    private static String script() {
        return "$ErrorActionPreference='SilentlyContinue'\n"
            + "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; "
            + "public class ProtonWKey { [DllImport(\"user32.dll\")] public static extern short "
            + "GetAsyncKeyState(int vKey); }'\n"
            + "$parentPid = " + ProcessHandle.current().pid() + "\n"
            + "$tick = 0\n"
            + "$down = ([ProtonWKey]::GetAsyncKeyState(0x57) -band 0x8000) -ne 0\n"
            + "$held = 0\n"
            + "$latched = $down\n"
            + "while ($true) {\n"
            + "  $down = ([ProtonWKey]::GetAsyncKeyState(0x57) -band 0x8000) -ne 0\n"
            + "  if ($down) {\n"
            + "    if (-not $latched) {\n"
            + "      $held += 200\n"
            + "      if ($held -ge " + HOLD_MS + ") {\n"
            + "        [Console]::Out.WriteLine('WAKE')\n"
            + "        [Console]::Out.Flush()\n"
            + "        $latched = $true\n"
            + "      }\n"
            + "    }\n"
            + "  } else {\n"
            + "    $held = 0\n"
            + "    $latched = $false\n"
            + "  }\n"
            + "  $tick++\n"
            + "  if ($tick -ge 25) {\n"
            + "    $tick = 0\n"
            + "    try { [void][System.Diagnostics.Process]::GetProcessById($parentPid) } catch { break }\n"
            + "  }\n"
            + "  Start-Sleep -Milliseconds 200\n"
            + "}\n";
    }
}

/** Small glass settings card opened by the rail's gear icon. */
@SuppressWarnings("serial")
class SettingsPanel extends JComponent {
    private final Runnable onClose;
    private final java.util.function.Consumer<Boolean> onWake;
    private final java.util.function.Consumer<Integer> onNoise;
    private final Runnable onChangeKeys;
    private boolean wakeOn;
    private int noiseLevel;
    private final Rectangle toggleR = new Rectangle();
    private final Rectangle closeR = new Rectangle();
    private final Rectangle keysR = new Rectangle();
    private final Rectangle updateR = new Rectangle();
    private final Rectangle autoR = new Rectangle();
    private final Rectangle[] segR = {new Rectangle(), new Rectangle(), new Rectangle()};

    SettingsPanel(boolean wakeOn, java.util.function.Consumer<Boolean> onWake, Runnable onClose,
                  int noiseLevel, java.util.function.Consumer<Integer> onNoise,
                  Runnable onChangeKeys) {
        this.wakeOn = wakeOn;
        this.onWake = onWake;
        this.onClose = onClose;
        this.noiseLevel = noiseLevel;
        this.onNoise = onNoise;
        this.onChangeKeys = onChangeKeys;
        setOpaque(false);
        setPreferredSize(new Dimension(372, 500));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        UpdateKit.setRepaintHook(this::repaint);
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                Point p = e.getPoint();
                if (closeR.contains(p)) { SettingsPanel.this.onClose.run(); return; }
                if (keysR.contains(p)) { SettingsPanel.this.onChangeKeys.run(); return; }
                if (updateR.contains(p)) { UpdateKit.pressUpdate(); repaint(); return; }
                if (autoR.contains(p) || (p.y > 428 && p.y < 500)) {
                    UpdateKit.setAuto(!UpdateKit.auto);
                    repaint();
                    return;
                }
                for (int i = 0; i < segR.length; i++) {
                    if (segR[i].contains(p)) {
                        SettingsPanel.this.noiseLevel = i;
                        repaint();
                        SettingsPanel.this.onNoise.accept(i);
                        return;
                    }
                }
                if (toggleR.contains(p) || (p.y > 44 && p.y < 140)) {
                    SettingsPanel.this.wakeOn = !SettingsPanel.this.wakeOn;
                    repaint();
                    SettingsPanel.this.onWake.accept(SettingsPanel.this.wakeOn);
                }
            }
        });
    }

    void setWake(boolean on) { wakeOn = on; repaint(); }
    void setNoise(int lvl) { noiseLevel = lvl; repaint(); }

    /** Wraps a status message into at most maxLines lines that fit maxWidth. */
    private static String[] wrap(Graphics2D g2, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String rest = text == null ? "" : text.trim();
        if (rest.isEmpty()) return new String[]{""};
        FontMetrics fm = g2.getFontMetrics();
        while (!rest.isEmpty() && lines.size() < maxLines) {
            if (fm.stringWidth(rest) <= maxWidth) { lines.add(rest); rest = ""; break; }
            int cut = rest.length();
            while (cut > 0 && fm.stringWidth(rest.substring(0, cut) + "\u2026") > maxWidth) cut--;
            int sp = rest.lastIndexOf(' ', Math.max(0, cut));
            if (sp > 0) cut = sp;
            if (cut <= 0) break;
            lines.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty() && !lines.isEmpty()) {
            int last = lines.size() - 1;
            String s = lines.get(last);
            while (!s.isEmpty() && fm.stringWidth(s + "\u2026") > maxWidth)
                s = s.substring(0, s.length() - 1);
            lines.set(last, s.trim() + "\u2026");
        }
        return lines.toArray(new String[0]);
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 18, 18);
        g2.setColor(new Color(12, 10, 22, 248));
        g2.fill(rr);
        g2.setColor(new Color(255, 255, 255, 30));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(rr);

        g2.setFont(Fnt.get(Font.BOLD, 13.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Settings", 20, 29);

        closeR.setBounds(w - 38, 10, 26, 26);
        g2.setColor(new Color(0xA3A5BA));
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = closeR.x + 13, cy = closeR.y + 13;
        g2.draw(new Line2D.Double(cx - 4, cy - 4, cx + 4, cy + 4));
        g2.draw(new Line2D.Double(cx + 4, cy - 4, cx - 4, cy + 4));

        g2.setColor(new Color(255, 255, 255, 16));
        g2.drawLine(16, 42, w - 16, 42);

        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Wake PROTON", 20, 70);
        g2.setFont(Fnt.get(Font.PLAIN, 11f));
        g2.setColor(Theme.TEXT2);
        g2.drawString("Say \u201CHey Proton\u201D to open PROTON, even after you", 20, 92);
        g2.drawString("close the window (keeps listening from the tray).", 20, 108);
        g2.setColor(Theme.TEXT3);
        g2.drawString(wakeOn ? "Listening for \u201CHey Proton\u201D" : "Off", 20, 128);

        int tw = 46, th = 26;
        int tx = w - 20 - tw, ty = 56;
        toggleR.setBounds(tx, ty, tw, th);
        RoundRectangle2D track = new RoundRectangle2D.Float(tx, ty, tw, th, th, th);
        if (wakeOn) {
            g2.setPaint(new LinearGradientPaint(new Point2D.Float(tx, ty), new Point2D.Float(tx + tw, ty),
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(43, 107, 255), new Color(168, 85, 247), new Color(255, 77, 208)}));
        } else {
            g2.setColor(new Color(0x2A2C3D));
        }
        g2.fill(track);
        g2.setColor(new Color(255, 255, 255, wakeOn ? 40 : 22));
        g2.draw(track);
        int kd = th - 6;
        int kx = wakeOn ? tx + tw - kd - 3 : tx + 3;
        g2.setColor(Color.WHITE);
        g2.fill(new Ellipse2D.Double(kx, ty + 3, kd, kd));

        // ---- Noise cancellation (microphone) ----
        g2.setColor(new Color(255, 255, 255, 16));
        g2.drawLine(16, 146, w - 16, 146);
        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Noise cancellation", 20, 174);
        g2.setFont(Fnt.get(Font.PLAIN, 11f));
        g2.setColor(Theme.TEXT2);
        g2.drawString("Filters fans, hum and background noise from the mic.", 20, 194);
        String[] names = {"Off", "Normal", "Strong"};
        int bx = 20, by = 208, bw = w - 40, bh = 28, sw = bw / 3;
        g2.setColor(new Color(0x2A2C3D));
        g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        g2.setColor(new Color(255, 255, 255, 22));
        g2.draw(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        for (int i = 0; i < 3; i++) {
            segR[i].setBounds(bx + i * sw, by, i == 2 ? bw - 2 * sw : sw, bh);
            Rectangle r = segR[i];
            if (i == noiseLevel) {
                RoundRectangle2D pill = new RoundRectangle2D.Float(r.x + 3, r.y + 3, r.width - 6, r.height - 6, bh, bh);
                if (i == 0) g2.setColor(new Color(0x4A4D66));
                else g2.setPaint(new LinearGradientPaint(new Point2D.Float(r.x, r.y), new Point2D.Float(r.x + r.width, r.y),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(43, 107, 255), new Color(168, 85, 247), new Color(255, 77, 208)}));
                g2.fill(pill);
            }
            g2.setFont(Fnt.get(Font.BOLD, 12f));
            g2.setColor(i == noiseLevel ? Color.WHITE : Theme.TEXT2);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(names[i], r.x + (r.width - fm.stringWidth(names[i])) / 2, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
        }

        // ---- API keys ----
        g2.setColor(new Color(255, 255, 255, 16));
        g2.drawLine(16, 250, w - 16, 250);
        keysR.setBounds(16, 256, w - 32, 38);
        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("API keys", 20, 278);
        g2.setFont(Fnt.get(Font.PLAIN, 10.5f));
        g2.setColor(Theme.TEXT3);
        g2.drawString("Change provider or keys", 20, 293);

        int pw = 74, ph = 24, px = w - 20 - pw, py = 264;
        RoundRectangle2D pill = new RoundRectangle2D.Float(px, py, pw, ph, ph, ph);
        g2.setColor(new Color(255, 255, 255, 16));
        g2.fill(pill);
        g2.setColor(new Color(255, 255, 255, 34));
        g2.draw(pill);
        g2.setFont(Fnt.get(Font.BOLD, 10.5f));
        g2.setColor(Theme.TEXT);
        FontMetrics fm2 = g2.getFontMetrics();
        g2.drawString("Change", px + (pw - fm2.stringWidth("Change")) / 2,
                py + (ph + fm2.getAscent() - fm2.getDescent()) / 2);

        // ---- Updates ----
        g2.setColor(new Color(255, 255, 255, 16));
        g2.drawLine(16, 308, w - 16, 308);

        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Updates", 20, 330);

        g2.setFont(Fnt.get(Font.PLAIN, 9.5f));
        g2.setColor(Theme.TEXT3);
        String repo = "github.com/" + UpdateKit.OWNER + "/" + UpdateKit.REPO;
        FontMetrics rf = g2.getFontMetrics();
        g2.drawString(repo, w - 20 - rf.stringWidth(repo), 329);

        updateR.setBounds(20, 342, 140, 34);
        boolean busy = UpdateKit.isBusy();
        RoundRectangle2D updateBtn = new RoundRectangle2D.Float(
                updateR.x, updateR.y, updateR.width, updateR.height, 17, 17);
        if (busy) {
            g2.setColor(new Color(0x3A3550));
            g2.fill(updateBtn);
        } else {
            g2.setPaint(new LinearGradientPaint(
                    new Point2D.Float(updateR.x, updateR.y),
                    new Point2D.Float(updateR.x + updateR.width, updateR.y),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(43, 107, 255), new Color(168, 85, 247),
                                new Color(255, 77, 208)}));
            g2.fill(updateBtn);
        }
        g2.setColor(new Color(255, 255, 255, busy ? 30 : 64));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(updateBtn);
        g2.setFont(Fnt.get(Font.BOLD, 12f));
        g2.setColor(busy ? new Color(0xC3C5D6) : Color.WHITE);
        String updateLabel = UpdateKit.buttonLabel();
        FontMetrics uf = g2.getFontMetrics();
        g2.drawString(updateLabel, updateR.x + (updateR.width - uf.stringWidth(updateLabel)) / 2,
                updateR.y + (updateR.height + uf.getAscent() - uf.getDescent()) / 2);

        g2.setFont(Fnt.get(Font.PLAIN, 10.5f));
        g2.setColor(UpdateKit.statusColor());
        String[] lines = wrap(g2, UpdateKit.message, w - 40, 2);
        int ly = 398;
        for (String line : lines) {
            g2.drawString(line, 20, ly);
            ly += 15;
        }

        g2.setColor(new Color(255, 255, 255, 16));
        g2.drawLine(16, 428, w - 16, 428);

        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Auto update", 20, 452);

        int atw = 46, ath = 26;
        int atx = w - 20 - atw, aty = 434;
        autoR.setBounds(atx, aty, atw, ath);
        RoundRectangle2D autoTrack = new RoundRectangle2D.Float(atx, aty, atw, ath, ath, ath);
        if (UpdateKit.auto) {
            g2.setPaint(new LinearGradientPaint(
                    new Point2D.Float(atx, aty), new Point2D.Float(atx + atw, aty),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(43, 107, 255), new Color(168, 85, 247),
                                new Color(255, 77, 208)}));
        } else {
            g2.setColor(new Color(0x2A2C3D));
        }
        g2.fill(autoTrack);
        g2.setColor(new Color(255, 255, 255, UpdateKit.auto ? 40 : 22));
        g2.draw(autoTrack);
        int akd = ath - 6;
        int akx = UpdateKit.auto ? atx + atw - akd - 3 : atx + 3;
        g2.setColor(Color.WHITE);
        g2.fill(new Ellipse2D.Double(akx, aty + 3, akd, akd));

        g2.setFont(Fnt.get(Font.PLAIN, 10.5f));
        g2.setColor(Theme.TEXT3);
        g2.drawString("Checks GitHub every 10 minutes and installs new", 20, 476);
        g2.drawString("commits automatically. On by default.", 20, 490);
        g2.dispose();
    }
}

interface MicListener {
    void onSpeechEnd(MicCapture src);   // user spoke and then went quiet
    void onNoSpeech(MicCapture src);    // nothing heard for a long time
    void onFail(MicCapture src);        // microphone problem
}

class MicCapture extends Thread {
    static final long SILENCE_MS    = 950;    // quiet time that ends an utterance
    static final long MIN_SPEECH_MS = 160;    // shorter blips are treated as noise
    static final long NO_SPEECH_MS  = 9000;   // give up if nobody talks
    static final long MAX_TOTAL_MS  = 40000;  // hard cap

    private final S s;
    private final MicListener listener;
    private volatile boolean running = true;
    private TargetDataLine line;
    private final ByteArrayOutputStream rec = new ByteArrayOutputStream();
    private volatile AudioFormat fmt;

    private volatile boolean heardSpeech = false;
    private volatile int speechStartByte = 0;
    private volatile double maxRms = 0;

    MicCapture(S s, MicListener listener) {
        this.s = s;
        this.listener = listener;
        setDaemon(true);
        setName("proton-mic");
    }

    private static double thr(double noise) {
        return Math.max(0.012, Math.min(0.06, noise * 3.0)) * NoiseSuppressor.vadBoost();
    }

    public void run() {
        try {
            fmt = new AudioFormat(16000f, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                fmt = new AudioFormat(44100f, 16, 1, true, false);
                info = new DataLine.Info(TargetDataLine.class, fmt);
            }
            if (!AudioSystem.isLineSupported(info)) {
                running = false;
                listener.onFail(this);
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(fmt, 8192);
            line.start();
            s.micLive = true;
            s.micHeard = false;

            final double sr = fmt.getSampleRate();
            final int preRoll = ((int) (sr * 2 * 0.45)) & ~1;
            byte[] buf = new byte[2048];
            final NoiseSuppressor nsp = new NoiseSuppressor((float) sr);   // noise cancellation (see Settings)

            long t0 = System.currentTimeMillis();
            double noise = 0.006, threshold = 0.015;
            double calibMs = 0, calibSum = 0;
            double loudMs = 0;
            int loudStart = 0;
            long speechStartMs = 0, lastVoiceMs = 0;

            while (running) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (NoiseSuppressor.level > 0) nsp.processInPlace(buf, n & ~1);   // cleaned audio feeds BOTH the VAD and Whisper
                int before;
                synchronized (rec) { before = rec.size(); rec.write(buf, 0, n); }

                int count = n / 2;
                double sum = 0;
                for (int i = 0; i < count; i++) {
                    int idx = i * 2;
                    int lo = buf[idx] & 0xFF;
                    int hi = buf[idx + 1];
                    short v = (short) ((hi << 8) | lo);
                    double f = v / 32768.0;
                    sum += f * f;
                }
                double rms = Math.sqrt(sum / Math.max(1, count));
                if (rms > maxRms) maxRms = rms;
                double boosted = Math.max(0, rms - 0.002) * 15.0;
                float lvl = (float) Math.min(1.0, boosted);
                s.liveLevel = lvl;
                if (lvl > 0.03f) s.micLastData = System.currentTimeMillis();

                long now = System.currentTimeMillis();
                double chunkMs = n * 1000.0 / (sr * 2);

                if (calibMs < 300) {
                    // measure ambient noise first
                    calibSum += rms * chunkMs;
                    calibMs += chunkMs;
                    if (calibMs >= 300) {
                        noise = Math.min(0.02, calibSum / calibMs);
                        threshold = thr(noise);
                    }
                } else if (!heardSpeech) {
                    if (rms > threshold) {
                        if (loudMs == 0) loudStart = before;
                        loudMs += chunkMs;
                        if (loudMs >= 120) {
                            heardSpeech = true;
                            s.micHeard = true;
                            speechStartMs = now;
                            lastVoiceMs = now;
                            speechStartByte = Math.max(0, loudStart - preRoll) & ~1;
                        }
                    } else {
                        loudMs = 0;
                        noise = noise * 0.95 + rms * 0.05;
                        threshold = thr(noise);
                    }
                    if (!heardSpeech && now - t0 > NO_SPEECH_MS) {
                        running = false;
                        listener.onNoSpeech(this);
                        break;
                    }
                } else {
                    if (rms > threshold * 0.7) lastVoiceMs = now;
                    long quiet = now - lastVoiceMs;
                    if (quiet > SILENCE_MS) {
                        if (lastVoiceMs - speechStartMs >= MIN_SPEECH_MS) {
                            running = false;
                            listener.onSpeechEnd(this);
                            break;
                        }
                        // just a click / cough -- keep waiting for real speech
                        heardSpeech = false;
                        s.micHeard = false;
                        loudMs = 0;
                    } else if (now - t0 > MAX_TOTAL_MS) {
                        running = false;
                        listener.onSpeechEnd(this);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                running = false;
                listener.onFail(this);
            }
        } finally {
            s.micLive = false;
            if (line != null) {
                try { line.stop();  } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** Stops recording and returns a WAV of the spoken part, or null if nothing was said. */
    byte[] stopAndGetAudio() {
        running = false;
        if (line != null) try { line.stop(); } catch (Exception ignored) {}
        try { join(800); } catch (InterruptedException ignored) {}
        byte[] pcm;
        synchronized (rec) { pcm = rec.toByteArray(); }
        int start = 0;
        if (heardSpeech) start = Math.min(speechStartByte, pcm.length) & ~1;
        else if (maxRms < 0.008) return null;
        if (pcm.length - start < 6400) return null;
        try {
            float sr = fmt != null ? fmt.getSampleRate() : 16000f;
            byte[] cut = Arrays.copyOfRange(pcm, start, pcm.length);
            return Brain.toWav(cut, (int) sr);
        } catch (Throwable t) { return null; }
    }

    void stopCapture() {
        running = false;
        if (line != null) try { line.stop(); } catch (Exception ignored) {}
    }
}

/* =============================================================== */
/*  UI PANELS                                                      */
/* =============================================================== */

@SuppressWarnings("serial")
class AmbientPanel extends JPanel {
    // Phase 2 Module A: static background is pre-rendered once into backBuffer.
    // Rebuilt only when width/height changes; paint is a single drawImage (zero-GC).
    private BufferedImage backBuffer;
    private int bufW = -1, bufH = -1;

    AmbientPanel() { setOpaque(true); setBackground(Theme.BG); }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (backBuffer == null || w != bufW || h != bufH) rebuildBackBuffer(w, h);
        // Single blit -- no per-frame allocation.
        g.drawImage(backBuffer, 0, 0, null);
    }

    private void rebuildBackBuffer(int w, int h) {
        if (backBuffer == null || bufW != w || bufH != h) {
            backBuffer = new BufferedImage(Math.max(1, w), Math.max(1, h),
                    BufferedImage.TYPE_INT_ARGB);
            bufW = w; bufH = h;
        }
        Graphics2D g2 = backBuffer.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            double mn = Math.min(w, h);

            g2.setColor(Theme.BG);
            g2.fillRect(0, 0, w, h);

            blob(g2, w * 0.19, h * 0.20, mn * 0.36, new Color(43, 107, 255, 56));
            blob(g2, w * 0.83, h * 0.22, mn * 0.34, new Color(168, 85, 247, 48));
            blob(g2, w * 0.76, h * 0.88, mn * 0.44, new Color(255, 77, 208, 40));
            blob(g2, w * 0.15, h * 0.88, mn * 0.40, new Color(56, 110, 255, 28));

            int step = 36;
            double mx = w / 2.0, my = h * 0.42;
            double mr = Math.max(w, h) * 0.55;
            for (int y = step / 2; y < h; y += step) {
                for (int x = step / 2; x < w; x += step) {
                    double d = Math.hypot(x - mx, y - my) / mr;
                    if (d >= 1) continue;
                    int a = (int) (30 * (1 - d * d));
                    if (a <= 0) continue;
                    g2.setColor(new Color(255, 255, 255, a));
                    g2.fillRect(x, y, 1, 1);
                }
            }

            double vr = Math.hypot(w, h) * 0.62;
            RadialGradientPaint vg = new RadialGradientPaint(
                new Point2D.Double(mx, h * 0.46), (float) vr,
                new float[]{0f, 0.34f, 1f},
                new Color[]{new Color(0,0,0,0), new Color(0,0,0,0), new Color(0,0,0,205)});
            g2.setPaint(vg);
            g2.fillRect(0, 0, w, h);
        } finally {
            g2.dispose();
        }
    }

    private void blob(Graphics2D g2, double x, double y, double r, Color c) {
        RadialGradientPaint p = new RadialGradientPaint(
            new Point2D.Double(x, y), (float) r,
            new float[]{0f, 1f},
            new Color[]{c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)});
        g2.setPaint(p);
        g2.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
    }
}

final class Icons {
    private static AffineTransform unit(double cx, double cy, double size) {
        double sc = size / 24.0;
        AffineTransform t = new AffineTransform();
        t.translate(cx - 12 * sc, cy - 12 * sc);
        t.scale(sc, sc);
        return t;
    }

    static void mic(Graphics2D g2, double cx, double cy, double size, Color c, float sw) {
        AffineTransform old = g2.getTransform();
        g2.transform(unit(cx, cy, size));
        g2.setColor(c);
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Double(9, 2.6, 6, 11, 6, 6));
        g2.draw(new Arc2D.Double(5.5, 5.0, 13, 13, 180, 180, Arc2D.OPEN));
        g2.draw(new Line2D.Double(12, 18, 12, 21.4));
        g2.setTransform(old);
    }
    static void spark(Graphics2D g2, double cx, double cy, double size, Color c, float sw) {
        AffineTransform old = g2.getTransform();
        g2.transform(unit(cx, cy, size));
        Path2D p = new Path2D.Double();
        p.moveTo(12, 3.4); p.lineTo(14, 8.7); p.lineTo(19.3, 10.7);
        p.lineTo(14, 12.7); p.lineTo(12, 18); p.lineTo(10, 12.7);
        p.lineTo(4.7, 10.7); p.lineTo(10, 8.7); p.closePath();
        g2.setColor(c);
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(p);
        g2.setTransform(old);
    }
    static void clock(Graphics2D g2, double cx, double cy, double size, Color c, float sw) {
        AffineTransform old = g2.getTransform();
        g2.transform(unit(cx, cy, size));
        g2.setColor(c);
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Ellipse2D.Double(3.4, 3.4, 17.2, 17.2));
        g2.draw(new Line2D.Double(12, 7.4, 12, 12));
        g2.draw(new Line2D.Double(12, 12, 15, 13.9));
        g2.setTransform(old);
    }
    static void gear(Graphics2D g2, double cx, double cy, double size, Color c, float sw) {
        AffineTransform old = g2.getTransform();
        g2.transform(unit(cx, cy, size));
        g2.setColor(c);
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Ellipse2D.Double(9, 9, 6, 6));
        double[][] sp = {
            {12,2.8,12,5.1},{12,18.9,12,21.2},{21.2,12,18.9,12},{5.1,12,2.8,12},
            {18.5,5.5,16.9,7.1},{7.1,16.9,5.5,18.5},{18.5,18.5,16.9,16.9},{7.1,7.1,5.5,5.5}
        };
        for (double[] a : sp) g2.draw(new Line2D.Double(a[0], a[1], a[2], a[3]));
        g2.setTransform(old);
    }
    static void panel(Graphics2D g2, double cx, double cy, double size, Color c, float sw) {
        AffineTransform old = g2.getTransform();
        g2.transform(unit(cx, cy, size));
        g2.setColor(c);
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Double(3, 4.5, 18, 15, 7, 7));
        g2.draw(new Line2D.Double(9.5, 4.5, 9.5, 19.5));
        g2.setTransform(old);
    }
    static void arrow(Graphics2D g2, double cx, double cy, double size, Color c, float sw) {
        AffineTransform old = g2.getTransform();
        g2.transform(unit(cx, cy, size));
        g2.setColor(c);
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(5, 12, 18, 12));
        Path2D p = new Path2D.Double();
        p.moveTo(13, 6.5); p.lineTo(18.5, 12); p.lineTo(13, 17.5);
        g2.draw(p);
        g2.setTransform(old);
    }
    static void clip(Graphics2D g2, double cx, double cy, double size, Color c, float sw) {
        AffineTransform old = g2.getTransform();
        g2.transform(unit(cx, cy, size));
        Path2D p = new Path2D.Double();
        p.moveTo(16.5, 7.5); p.lineTo(16.5, 15);
        p.curveTo(16.5, 17.5, 14.5, 19.5, 12, 19.5);
        p.curveTo(9.5, 19.5, 7.5, 17.5, 7.5, 15);
        p.lineTo(7.5, 8.5);
        p.curveTo(7.5, 7, 8.6, 5.9, 10, 5.9);
        p.curveTo(11.4, 5.9, 12.5, 7, 12.5, 8.5);
        p.lineTo(12.5, 15.5);
        p.curveTo(12.5, 16.4, 11.9, 17, 11, 17);
        p.curveTo(10.1, 17, 9.5, 16.4, 9.5, 15.5);
        p.lineTo(9.5, 9);
        g2.setColor(c);
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(p);
        g2.setTransform(old);
    }
}

@SuppressWarnings("serial")
class GlassOverlay extends JPanel {
    private String tip;
    private int tipX, tipY;
    GlassOverlay() { setOpaque(false); setLayout(null); }
    public boolean contains(int x, int y) { return false; }
    void showTip(String text, int x, int y) {
        if (tip != null && tip.equals(text) && x == tipX && y == tipY) return;
        tip = text; tipX = x; tipY = y;
        repaint();
    }
    void hideTip() { if (tip != null) { tip = null; repaint(); } }
    protected void paintComponent(Graphics g) {
        if (tip == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font f = Fnt.get(Font.BOLD, 11.5f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        int padX = 10, padY = 6;
        int tw = fm.stringWidth(tip);
        int th = fm.getHeight();
        int w = tw + padX * 2, h = th + padY * 2;
        int x = tipX, y = tipY - h / 2;
        RoundRectangle2D rr = new RoundRectangle2D.Float(x, y, w, h, 9, 9);
        g2.setColor(new Color(12, 10, 22, 246));
        g2.fill(rr);
        g2.setColor(new Color(255, 255, 255, 24));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(rr);
        g2.setColor(new Color(0xDF, 0xE1, 0xEE));
        g2.drawString(tip, x + padX, y + padY + fm.getAscent());
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class IconButton extends JComponent {
    interface Painter {
        void paint(Graphics2D g2, double cx, double cy, double size, Color c, float sw);
    }
    private final Painter painter;
    private final double  iconSize;
    private final float   strokeW;
    private final boolean hasBg;
    private final boolean railStyle;

    float   alpha  = 1f;
    boolean hover  = false;
    boolean active = false;
    private Runnable onClick;

    IconButton(Painter p, double iconSize, float strokeW, boolean hasBg, boolean railStyle) {
        this.painter = p;
        this.iconSize = iconSize;
        this.strokeW = strokeW;
        this.hasBg = hasBg;
        this.railStyle = railStyle;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
            public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (onClick != null) onClick.run();
            }
        });
    }

    void setActive(boolean a) { if (active == a) return; active = a; repaint(); }
    void setAlpha(float a)    { alpha = Math.max(0f, Math.min(1f, a)); repaint(); }
    void setOnClick(Runnable r) { this.onClick = r; }

    protected void paintComponent(Graphics g) {
        if (alpha < 0.02f) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        int w = getWidth(), h = getHeight();
        float a = alpha;

        if (hasBg) {
            RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 13, 13);
            if (active) {
                LinearGradientPaint lg = new LinearGradientPaint(
                    new Point2D.Float(0, 0), new Point2D.Float(w, h),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{
                        Theme.alpha(new Color(43, 107, 255), a * 0.22),
                        Theme.alpha(new Color(168, 85, 247), a * 0.20),
                        Theme.alpha(new Color(255, 77, 208), a * 0.16)});
                g2.setPaint(lg);
                g2.fill(rr);
                g2.setColor(Theme.alpha(Color.WHITE, a * 0.10));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(rr);
            } else if (hover) {
                g2.setColor(Theme.alpha(Color.WHITE, a * 0.065));
                g2.fill(rr);
            }
        }

        Color icon;
        if (active)     icon = Theme.alpha(new Color(0xC9A0FF), a);
        else if (hover) icon = Theme.alpha(new Color(0xD6D8E6), a);
        else            icon = Theme.alpha(railStyle ? new Color(0x6F7189)
                                                     : new Color(0x7C7E96), a);
        painter.paint(g2, w / 2.0, h / 2.0, iconSize, icon, strokeW);
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class SendButton extends JComponent {
    float   alpha = 0f;
    boolean hover = false;
    private Runnable onClick;

    SendButton(Runnable onClick) {
        this.onClick = onClick;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
            public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (SendButton.this.onClick != null) SendButton.this.onClick.run();
            }
        });
    }

    protected void paintComponent(Graphics g) {
        if (alpha < 0.02f) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        RoundRectangle2D rr = new RoundRectangle2D.Float(0, 0, w, h, h, h);
        LinearGradientPaint lg = new LinearGradientPaint(
            new Point2D.Float(0, 0), new Point2D.Float(w, h),
            new float[]{0f, 0.34f, 0.66f, 1f},
            new Color[]{new Color(43, 107, 255), new Color(124, 91, 255),
                        new Color(184, 85, 247), new Color(255, 77, 208)});
        g2.setPaint(lg);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g2.fill(rr);
        g2.setColor(new Color(255, 255, 255, (int) (46 * Math.max(0f, Math.min(1f, alpha)))));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(rr);

        Font f = Fnt.get(Font.BOLD, 13f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        String label = "Send";
        int tw = fm.stringWidth(label);
        int arrowW = 14, gap = 8;
        int total = tw + gap + arrowW;
        int tx = (w - total) / 2;
        int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
        g2.setColor(Color.WHITE);
        g2.drawString(label, tx, ty);
        Icons.arrow(g2, tx + tw + gap + arrowW / 2.0, h / 2.0, 15, Color.WHITE, 2.2f);
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class RailPanel extends JPanel {
    private final S s;
    private final GlassOverlay glass;
    private static final int W = 74, BTN = 42, GAP = 6;
    final IconButton[] btns = new IconButton[5];
    private final String[] tips = {"Assistant", "Voice", "Clear memory", "Settings", "Collapse"};
    private int activeIndex = 0;

    RailPanel(S s, GlassOverlay glass) {
        this.s = s;
        this.glass = glass;
        setOpaque(false);
        setPreferredSize(new Dimension(W, 100));
        setLayout(null);

        IconButton.Painter[] painters = {
            Icons::spark, Icons::mic, Icons::clock, Icons::gear, Icons::panel
        };

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            final IconButton b = new IconButton(painters[i], 19, 1.6f, true, true);
            b.setActive(i == 0);
            b.setOnClick(() -> {
                Window w = SwingUtilities.getWindowAncestor(RailPanel.this);
                if (!(w instanceof ProtonFrame)) return;
                ProtonFrame f = (ProtonFrame) w;
                if (idx == 4) {
                    f.collapseComposer();
                    return;
                }
                if (idx == 2) {
                    f.brain.clearSession();
                    f.s.transcript = "\u201C session memory cleared \u201D";
                    f.s.transcriptTtl = 3f;
                    return;
                }
                if (idx == 0) {
                    // Assistant Mode: workstation, single-turn mic, dock visible.
                    f.setAssistantMode();
                    return;
                }
                if (idx == 1) {
                    // Voice Mode: voice stage, dock hidden, continuous loop.
                    if (f.s.mode == AppMode.VOICE) {
                        f.toggleMic(); // already hands-free; no-op unless idle
                    } else {
                        f.setVoiceMode();
                    }
                    return;
                }
                if (idx == 3) {
                    // Settings: opens the settings card (wake word toggle).
                    f.toggleSettings();
                    return;
                }
                activeIndex = idx;
                for (int k = 0; k < 4; k++) btns[k].setActive(k == activeIndex);
            });
            b.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    Point p = SwingUtilities.convertPoint(b, b.getWidth() + 10, b.getHeight() / 2, glass);
                    glass.showTip(tips[idx], p.x, p.y);
                }
                public void mouseExited(MouseEvent e) { glass.hideTip(); }
            });
            btns[i] = b;
            add(b);
        }
    }

    /** Mode markers: 0 = Assistant, 1 = Voice. Drives the side indicator bar. */
    void setModeMarker(int idx) {
        if (idx < 0 || idx > 3) return;
        activeIndex = idx;
        for (int k = 0; k < 4; k++) btns[k].setActive(k == activeIndex);
    }

    int getModeMarker() { return activeIndex; }

    public void doLayout() {
        int h = getHeight();
        int x = (W - BTN) / 2;
        int y = 66;
        for (int i = 0; i < 4; i++) { btns[i].setBounds(x, y, BTN, BTN); y += BTN + GAP; }
        btns[4].setBounds(x, h - 16 - BTN, BTN, BTN);
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        // Kinetic morph: peripheral chrome cross-fades as the window compresses.
        float fade = 1f - s.morphProgress;
        if (fade <= 0.01f) { g2.dispose(); return; }
        if (fade < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
        g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 9),
                                      0, h, new Color(255, 255, 255, 2)));
        g2.fillRect(0, 0, w, h);
        g2.setColor(new Color(255, 255, 255, 13));
        g2.drawLine(w - 1, 0, w - 1, h);

        int ls = 38;
        int lx = (w - ls) / 2;
        int ly = 14;
        RoundRectangle2D logo = new RoundRectangle2D.Float(lx, ly, ls, ls, 13, 13);
        LinearGradientPaint lp = new LinearGradientPaint(
            new Point2D.Float(lx, ly), new Point2D.Float(lx + ls, ly + ls),
            new float[]{0f, 0.26f, 0.56f, 0.92f},
            new Color[]{new Color(0xDBE4FF), new Color(0x6D8CFF),
                        new Color(0xA855F7), new Color(0xFF4DD0)});
        g2.setPaint(lp);
        g2.fill(logo);
        g2.setColor(new Color(255, 255, 255, 60));
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(new Arc2D.Double(lx + 3, ly + 3, ls - 6, ls - 6, 40, 100, Arc2D.OPEN));
        g2.setFont(Fnt.get(Font.BOLD, 15f));
        g2.setColor(Color.WHITE);
        FontMetrics fm = g2.getFontMetrics();
        String t = "P";
        g2.drawString(t, lx + (ls - fm.stringWidth(t)) / 2,
                      ly + (ls + fm.getAscent() - fm.getDescent()) / 2);

        if (activeIndex >= 0 && activeIndex < 4) {
            IconButton ab = btns[activeIndex];
            if (ab != null) {
                int ay = ab.getY() + ab.getHeight() / 2 - 10;
                LinearGradientPaint marker = new LinearGradientPaint(
                    new Point2D.Float(0, ay), new Point2D.Float(0, ay + 20),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(43, 107, 255), new Color(168, 85, 247),
                                new Color(255, 77, 208)});
                g2.setPaint(marker);
                g2.fill(new RoundRectangle2D.Float(-1, ay, 3, 20, 3, 3));
            }
        }
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class TopBar extends JPanel {
    private final S s;

    TopBar(S s) {
        this.s = s;
        setOpaque(false);
        setPreferredSize(new Dimension(100, 48));
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int h = getHeight();
        int cy = h / 2 + 2;
        // Kinetic morph: peripheral chrome cross-fades as the window compresses.
        float fade = 1f - s.morphProgress;
        if (fade <= 0.01f) { g2.dispose(); return; }
        if (fade < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));

        int dx = 28;
        Color dotCol;
        if (s.thinking)        dotCol = new Color(0x8CE9FF);
        else if (s.micActive)  dotCol = new Color(0x3DDC97);
        else if (s.speaking)   dotCol = new Color(0xC9A0FF);
        else                   dotCol = Theme.GREY;
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 500.0);
        Color halo;
        if (s.thinking)        halo = new Color(120, 180, 255, (int) (14 + pulse * 12));
        else if (s.micActive)  halo = new Color(126, 224, 168, (int) (14 + pulse * 12));
        else if (s.speaking)   halo = new Color(190, 140, 255, (int) (14 + pulse * 12));
        else                   halo = new Color(67, 69, 90, 20);

        g2.setColor(halo);
        g2.fill(new Ellipse2D.Double(dx - 5, cy - 5, 10, 10));
        g2.setColor(dotCol);
        g2.fill(new Ellipse2D.Double(dx - 3.5, cy - 3.5, 7, 7));

        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(new Color(0xECEEF8));
        FontMetrics fm = g2.getFontMetrics();
        int tx = dx + 16;
        g2.drawString("P R O T O N", tx, cy + fm.getAscent() / 2 - 1);
        tx += fm.stringWidth("P R O T O N") + 10;
        g2.setColor(new Color(255, 255, 255, 32));
        g2.drawLine(tx, cy - 7, tx, cy + 7);
        tx += 11;

        String st = s.thinking ? "Thinking"
                  : s.micActive ? (s.micHeard ? "Hearing you" : "Listening")
                  : s.speaking ? "Speaking"
                  : "Standby";
        Color stCol = s.thinking ? new Color(0x7CC4FF)
                    : s.micActive ? new Color(0x9A9CB4)
                    : s.speaking ? new Color(0xB98BFF)
                    : Theme.TEXT3;
        g2.setFont(Fnt.get(Font.PLAIN, 12.5f));
        g2.setColor(stCol);
        g2.drawString(st, tx, cy + fm.getAscent() / 2 - 1);
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class StagePanel extends JPanel {
    final HeadlinePanel headline;
    final OrbPanel      orb;
    final StatusPanel   status;
    StagePanel(S s) {
        setOpaque(false);
        setLayout(new BorderLayout());
        headline = new HeadlinePanel(s);
        orb      = new OrbPanel(s);
        status   = new StatusPanel(s);
        add(headline, BorderLayout.NORTH);
        add(orb,      BorderLayout.CENTER);
        add(status,   BorderLayout.SOUTH);
    }
}

@SuppressWarnings("serial")
class HeadlinePanel extends JPanel {
    private final S s;
    private static final String S1 = "AI Powered ";
    private static final String S2 = "Voice Control";
    private static final String S3 = " and Instant Assistance";

    HeadlinePanel(S s) {
        this.s = s;
        setOpaque(false);
        setPreferredSize(new Dimension(100, 36));
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        FontRenderContext frc = g2.getFontRenderContext();
        // Kinetic morph: peripheral chrome cross-fades as the window compresses.
        float fade = 1f - s.morphProgress;
        if (fade <= 0.01f) { g2.dispose(); return; }
        if (fade < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));

        float base = 18.5f;
        Font fDim = Fnt.get(Font.PLAIN, base);
        Font fAcc = Fnt.get(Font.BOLD, base);
        TextLayout l1 = new TextLayout(S1, fDim, frc);
        TextLayout l2 = new TextLayout(S2, fAcc, frc);
        TextLayout l3 = new TextLayout(S3, fDim, frc);
        double total = l1.getAdvance() + l2.getAdvance() + l3.getAdvance();
        double avail = getWidth() - 48;
        if (total > avail && avail > 50) {
            float scale = (float)(avail / total);
            base *= scale;
            fDim = Fnt.get(Font.PLAIN, base);
            fAcc = Fnt.get(Font.BOLD, base);
            l1 = new TextLayout(S1, fDim, frc);
            l2 = new TextLayout(S2, fAcc, frc);
            l3 = new TextLayout(S3, fDim, frc);
            total = l1.getAdvance() + l2.getAdvance() + l3.getAdvance();
        }
        double x = (getWidth() - total) / 2.0;
        double y = getHeight() / 2.0 + (l1.getAscent() - l1.getDescent()) / 2.0;

        float tp = s.thinkPhase;
        Color dimC = Theme.mix(Theme.TEXT_DIM, new Color(0x5A7898), tp);
        g2.setColor(dimC);
        g2.fill(l1.getOutline(AffineTransform.getTranslateInstance(x, y)));
        g2.fill(l3.getOutline(AffineTransform.getTranslateInstance(
            x + l1.getAdvance() + l2.getAdvance(), y)));

        Shape accShape = l2.getOutline(AffineTransform.getTranslateInstance(
            x + l1.getAdvance(), y));
        Rectangle2D b = accShape.getBounds2D();
        if (b.getWidth() > 1 && b.getHeight() > 1) {
            Color[] pal = Theme.palette(tp);
            LinearGradientPaint gp = new LinearGradientPaint(
                new Point2D.Double(b.getMinX(), b.getMinY()),
                new Point2D.Double(b.getMaxX(), b.getMaxY()),
                new float[]{0f, 0.34f, 0.62f, 1f},
                new Color[]{pal[0], pal[2], pal[3], pal[4]});
            g2.setPaint(gp);
            g2.fill(accShape);
        }
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class OrbPanel extends JPanel {
    private final S s;

    // Siri wake overlay: 0..1 bloom factor + mini-chrome suppression.
    private float activation = 1f;
    private boolean siriMode = false;

    void setActivation(float a) {
        if (a < 0f) a = 0f; else if (a > 1f) a = 1f;
        activation = a;
    }
    float getActivation() { return activation; }
    void setSiriMode(boolean on) { siriMode = on; }

    // Phase 2 Module A: clamped wireframe stride cuts trig evals ~30%.
    private static final int LATN = 22, LONN = 36, STRIDE = LONN + 1;
    private static final int COUNT = (LATN + 1) * STRIDE;
    private static final int BUCKETS = 8;
    private static final float[] BUCKET_ALPHA = {
        0.045f, 0.070f, 0.105f, 0.150f, 0.210f, 0.300f, 0.425f, 0.605f };

    // Phase 2 Module A: cached paints -- recreated only when R shifts >2px
    // or quantized dynamic params shift (avoids freezing level feedback
    // while eliminating per-frame RadialGradientPaint allocation).
    private RadialGradientPaint bloomPaint, rimPaint, corePaint;
    private LinearGradientPaint wirePaint;
    private double cacheR = -1, cacheCx = -1, cacheCy = -1;
    private float cacheLvl = -1, cacheTp = -1;
    // Reused shapes -- zero per-frame allocation for blits.
    private final Ellipse2D.Double bloomEllipse = new Ellipse2D.Double();
    private final Ellipse2D.Double rimEllipse = new Ellipse2D.Double();
    private final Ellipse2D.Double coreEllipse = new Ellipse2D.Double();
    private final Ellipse2D.Double ringEllipse = new Ellipse2D.Double();
    private final Ellipse2D.Double dotEllipse = new Ellipse2D.Double();
    private final Ellipse2D.Double haloEllipse = new Ellipse2D.Double();
    private final Rectangle2D.Double dustRect = new Rectangle2D.Double();
    private final RoundRectangle2D.Float badgeRect = new RoundRectangle2D.Float();
    private final Arc2D.Double accentArc = new Arc2D.Double();
    private static final Font BADGE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 10);
    private static final BasicStroke BADGE_STROKE = new BasicStroke(1f);
    private static final BasicStroke ACCENT_STROKE = new BasicStroke(2f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private final float[] px = new float[COUNT];
    private final float[] py = new float[COUNT];
    private final float[] pz = new float[COUNT];
    private final Path2D.Float[] buckets = new Path2D.Float[BUCKETS];
    private final Path2D.Float frontOrbit = new Path2D.Float();
    private final Path2D.Float backOrbit  = new Path2D.Float();

    private static final int DUST_N = 320;
    private final double[] dth = new double[DUST_N];
    private final double[] dph = new double[DUST_N];
    private final double[] drr = new double[DUST_N];
    private final double[] dsp = new double[DUST_N];
    private final double[] dsz = new double[DUST_N];
    private final double[] dal = new double[DUST_N];

    private static final double[][] ORBITS = {
        {1.18,  1.15,  0.22,  1.0, 0.54, 1.00},
        {1.36, -0.70,  1.30, -1.7, 0.38, 0.90},
        {1.15,  0.35, -2.05,  2.4, 0.62, 1.20}
    };
    private static final double TILT  = -0.34;
    private static final double PERSP = 5.0;

    OrbPanel(S s) {
        this.s = s;
        setOpaque(false);
        for (int i = 0; i < BUCKETS; i++) buckets[i] = new Path2D.Float();
        Random rnd = new Random(20240817L);
        for (int k = 0; k < DUST_N; k++) {
            double u = rnd.nextDouble() * 2 - 1;
            dth[k] = Math.acos(u);
            dph[k] = rnd.nextDouble() * Math.PI * 2;
            drr[k] = 1.06 + rnd.nextDouble() * 0.46;
            dsp[k] = 0.10 + rnd.nextDouble() * 0.40;
            dsz[k] = 0.60 + rnd.nextDouble() * 1.00;
            dal[k] = 0.10 + rnd.nextDouble() * 0.55;
        }
    }

    private static double surfaceNoise(double theta, double phi, double t) {
        double n = 0;
        n += Math.sin(theta * 3.1 - t * 0.90) * Math.cos(phi * 2.4 + t * 0.55) * 0.54;
        n += Math.sin(phi * 3.7 + t * 1.15) * Math.sin(theta * 2.2 - t * 0.40) * 0.40;
        n += Math.sin(theta * 5.4 + phi * 2.1 - t * 1.50) * 0.25;
        n += Math.cos(phi * 6.2 - theta * 4.1 + t * 1.90) * 0.15;
        n += Math.sin(theta * 8.3 + t * 2.40) * 0.08;
        return n * 0.70;
    }

    private int bucketOf(float z) {
        int b = (int) (((z + 1) * 0.5) * BUCKETS);
        if (b < 0) b = 0;
        else if (b >= BUCKETS) b = BUCKETS - 1;
        return b;
    }

    // Reused strokes -- avoids per-frame BasicStroke allocation.
    private static final BasicStroke RING_STROKE = new BasicStroke(1.2f);
    private static final BasicStroke GLOW_STROKE = new BasicStroke(3.6f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke WIRE_STROKE = new BasicStroke(0.90f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private void ensureCachedPaints(double cx, double cy, double R,
                                    float lvl, float tp) {
        boolean stale = bloomPaint == null || rimPaint == null || corePaint == null || wirePaint == null
                || Math.abs(R - cacheR) > 2.0
                || Math.abs(cx - cacheCx) > 2.0 || Math.abs(cy - cacheCy) > 2.0
                || Math.abs(lvl - cacheLvl) > 0.05f || Math.abs(tp - cacheTp) > 0.05f;
        if (!stale) return;
        double bloomR = R * 2.05;
        bloomPaint = new RadialGradientPaint(
            new Point2D.Double(cx, cy), (float) bloomR,
            new float[]{0f, 0.44f, 1f},
            new Color[]{
                new Color(110, 75, 255, Theme.clamp255(255 * (0.085 + lvl * 0.28 + tp * 0.13))),
                new Color(180, 70, 255, Theme.clamp255(255 * (0.042 + lvl * 0.18 + tp * 0.06))),
                new Color(0, 0, 0, 0)});
        double rimR = R * 1.42;
        rimPaint = new RadialGradientPaint(
            new Point2D.Double(cx, cy), (float) rimR,
            new float[]{0f, 0.5915f, 0.7304f, 0.8611f, 1f},
            new Color[]{
                new Color(0, 0, 0, 0),
                new Color(0, 0, 0, 0),
                new Color(130, 90, 255, Theme.clamp255(255 * (0.085 + lvl * 0.22 + tp * 0.07))),
                new Color(255, 80, 215, Theme.clamp255(255 * (0.042 + lvl * 0.13))),
                new Color(0, 0, 0, 0)});
        corePaint = new RadialGradientPaint(
            new Point2D.Double(cx - R * 0.10, cy - R * 0.12), (float) (R * 1.02),
            new float[]{0f, 0.48f, 1f},
            new Color[]{
                new Color(160, 120, 255, Theme.clamp255(255 * (0.10 + lvl * 0.25 + tp * 0.16))),
                new Color(90, 60, 210, Theme.clamp255(255 * (0.045 + lvl * 0.13 + tp * 0.05))),
                new Color(0, 0, 0, 0)});
        Color[] pal = Theme.palette(tp);
        // Copy palette: Theme.palette allocates a fresh array; LinearGradientPaint
        // holds the reference, so cache the paint and reuse until tp/R shifts.
        wirePaint = new LinearGradientPaint(
            new Point2D.Double(cx - R * 1.12, cy - R * 1.12),
            new Point2D.Double(cx + R * 1.12, cy + R * 1.12),
            Theme.PAL_FR, pal);
        cacheR = R; cacheCx = cx; cacheCy = cy; cacheLvl = lvl; cacheTp = tp;
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int w = getWidth(), h = getHeight();
        if (w < 8 || h < 8) { g2.dispose(); return; }

        // Kinetic morph: the sphere scales naturally with the window -- from
        // ~200px expanded down to ~45px in the 150px corner companion.
        boolean mini = !siriMode && (s.morphProgress > 0.5f || s.companionMode || s.companionDone);
        double cx = w / 2.0, cy = h / 2.0;
        double R = Math.min(w * 0.30, h * 0.40);
        R = Math.max(38, Math.min(R, 262));
        // Siri wake overlay: the sphere blooms out of a single luminous point.
        float act = activation;
        if (act <= 0.004f) { g2.dispose(); return; }
        R *= 0.16 + 0.84 * act;
        if (mini) cy -= 6; // leave room for the step micro-badge pill

        float  rawLvl = s.level;
        // Companion working state stays energetic even while silent.
        float  lvl = (mini && !s.companionDone) ? Math.max(rawLvl, 0.55f) : rawLvl;
        float  tp  = Math.max(0f, Math.min(1f, s.thinkPhase));
        double t   = s.noiseT;
        double rotY = s.rotY;
        boolean micOn = s.micActive && !mini;

        if (micOn && tp < 0.5f) {
            Color rc = Theme.mix(new Color(168, 110, 255), new Color(120, 180, 255), tp);
            Color ringColBase = rc;
            for (int k = 0; k < 3; k++) {
                double ph = ((s.ringTime * 0.38 + k / 3.0) % 1.0);
                double ringR = R * (1.0 + ph * 0.85);
                float a = (float) ((1 - ph) * 0.55) * act;
                if (a < 0.02f) continue;
                g2.setColor(new Color(ringColBase.getRed(), ringColBase.getGreen(),
                        ringColBase.getBlue(), (int)(a * 255)));
                g2.setStroke(RING_STROKE);
                ringEllipse.setFrame(cx - ringR, cy - ringR, ringR * 2, ringR * 2);
                g2.draw(ringEllipse);
            }
        }

        // Cached gradients: rebuilt only on significant R shift (>2px) or
        // quantized level/think change -- zero per-frame RadialGradientPaint churn.
        ensureCachedPaints(cx, cy, R, lvl, tp);
        Composite baseComp = g2.getComposite();
        if (act < 0.999f) g2.setComposite(AlphaComposite.getInstance(
                AlphaComposite.SRC_OVER, act));
        double bloomR = R * 2.05;
        g2.setPaint(bloomPaint);
        bloomEllipse.setFrame(cx - bloomR, cy - bloomR, bloomR * 2, bloomR * 2);
        g2.fill(bloomEllipse);

        double rimR = R * 1.42;
        g2.setPaint(rimPaint);
        rimEllipse.setFrame(cx - rimR, cy - rimR, rimR * 2, rimR * 2);
        g2.fill(rimEllipse);

        g2.setPaint(corePaint);
        coreEllipse.setFrame(cx - R * 1.02, cy - R * 1.02, R * 2.04, R * 2.04);
        g2.fill(coreEllipse);
        g2.setComposite(baseComp);

        double cosY = Math.cos(rotY), sinY = Math.sin(rotY);
        double tiltNow = TILT + Math.sin(t * 0.33) * 0.055;
        double cosT = Math.cos(tiltNow), sinT = Math.sin(tiltNow);

        double amp = 0.06 + lvl * 0.35;
        double breath = tp * 0.05 * Math.sin(t * 2.9 + 0.4);
        double scanY = Math.sin(t * 0.75) * 0.72;
        double scanAmp = tp * 0.10;

        for (int i = 0; i <= LATN; i++) {
            double theta = (i / (double) LATN) * Math.PI;
            double sinTh = Math.sin(theta), cosTh = Math.cos(theta);
            int base = i * STRIDE;
            double scanG = 0;
            if (scanAmp > 0.001) {
                double d = cosTh - scanY;
                scanG = Math.exp(-d * d * 30) * scanAmp;
            }
            double rowBreath = breath + scanG;
            for (int j = 0; j <= LONN; j++) {
                double phi = (j / (double) LONN) * Math.PI * 2;
                double r = 1 + amp * surfaceNoise(theta, phi, t)
                             + Math.sin(t * 0.75) * 0.010 + rowBreath;
                if (lvl > 0.01) {
                    r += lvl * 0.10 * Math.sin(theta * 5.5 - t * 3.4);
                    r += lvl * 0.06 * Math.sin(phi * 6.2 + t * 4.1);
                }
                if (tp > 0.02) r += tp * 0.020 * Math.sin(theta * 7.0 - phi * 2.0 + t * 2.1);
                double x0 = r * sinTh * Math.cos(phi);
                double y0 = r * cosTh;
                double z0 = r * sinTh * Math.sin(phi);
                double x1 =  x0 * cosY - z0 * sinY;
                double z1 =  x0 * sinY + z0 * cosY;
                double y1 =  y0 * cosT - z1 * sinT;
                double z2 =  y0 * sinT + z1 * cosT;
                double f = PERSP / (PERSP - z2);
                int id = base + j;
                px[id] = (float)(cx + x1 * R * f);
                py[id] = (float)(cy + y1 * R * f);
                pz[id] = (float) z2;
            }
        }

        for (int i = 0; i < BUCKETS; i++) buckets[i].reset();
        for (int i = 1; i < LATN; i++) {
            int base = i * STRIDE;
            for (int j = 0; j < LONN; j++) {
                int a = base + j, b = a + 1;
                int k = bucketOf((pz[a] + pz[b]) * 0.5f);
                buckets[k].moveTo(px[a], py[a]);
                buckets[k].lineTo(px[b], py[b]);
            }
        }
        for (int j = 0; j < LONN; j++) {
            for (int i = 0; i < LATN; i++) {
                int a = i * STRIDE + j, b = a + STRIDE;
                int k = bucketOf((pz[a] + pz[b]) * 0.5f);
                buckets[k].moveTo(px[a], py[a]);
                buckets[k].lineTo(px[b], py[b]);
            }
        }

        // Reuse cached wire gradient (rebuilt in ensureCachedPaints).
        LinearGradientPaint lg = wirePaint;
        g2.setPaint(lg);

        Composite oldComp = g2.getComposite();
        float glowA = Math.min(1f, 0.060f + lvl * 0.10f + tp * 0.070f) * act;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, glowA));
        g2.setStroke(GLOW_STROKE);
        g2.draw(buckets[BUCKETS - 1]);
        g2.draw(buckets[BUCKETS - 2]);

        g2.setStroke(WIRE_STROKE);
        for (int b = 0; b < BUCKETS; b++) {
            float a = Math.min(1f, BUCKET_ALPHA[b] * (1f + tp * 0.22f)) * act;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g2.draw(buckets[b]);
        }
        g2.setComposite(oldComp);

        if (tp > 0.008f) {
            g2.setPaint(lg);
            for (int k = 0; k < ORBITS.length; k++) {
                double[] o = ORBITS[k];
                double rr = 1 + (o[0] - 1) * tp;
                double sp = s.thinkTime * o[3];
                drawOrbit(g2, cx, cy, R, rr, o[1], o[2], sp, o[4] * tp * act, (float) o[5], lg);
                drawRunner(g2, cx, cy, R, rr, o[1], o[2],
                           sp + s.thinkTime * 1.9 + k * 2.1, o[4] * tp * 0.95 * act, lg);
            }
        }

        double dustBoost = 1 + lvl * 1.15 + tp * 0.30;
        double dustPull  = 1 - tp * 0.34;
        double dustSpin  = 0.34 + tp * 1.25;

        for (int k = 0; k < DUST_N; k++) {
            double ph = dph[k] + t * dsp[k] * dustSpin;
            double rr = 1 + (drr[k] - 1) * dustPull;
            double sinTh = Math.sin(dth[k]);
            double x0 = rr * sinTh * Math.cos(ph);
            double y0 = rr * Math.cos(dth[k]);
            double z0 = rr * sinTh * Math.sin(ph);
            double x1 =  x0 * cosY - z0 * sinY;
            double z1 =  x0 * sinY + z0 * cosY;
            double y1 =  y0 * cosT - z1 * sinT;
            double z2 =  y0 * sinT + z1 * cosT;
            double f = PERSP / (PERSP - z2);
            double sx = cx + x1 * R * f;
            double sy = cy + y1 * R * f;
            double depth = Math.max(0, (z2 + 1) * 0.5);
            double size  = dsz[k] * (0.7 + f * 0.5) * dustBoost;
            float a = (float) Math.min(1.0,
                dal[k] * (0.18 + depth * 0.82) * (1 + tp * 0.15)) * act;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g2.setPaint(lg);
            dustRect.setRect(sx, sy, size, size);
            g2.fill(dustRect);
        }
        g2.setComposite(oldComp);
        // Mini companion: Theme-synced status chrome (badge + rim accent).
        if (mini) paintCompanionChrome(g2, w, h, cx, cy, R);
        g2.dispose();
    }

    /**
     * Companion-only overlay: completion pulse + bottom step pill + rim
     * accent arc. Strictly Theme tokens: BG #04030A, PAL_A cyan/magenta,
     * violet/cyan core glow. Reuses shapes; primitives only per frame.
     */
    private void paintCompanionChrome(Graphics2D g2, int w, int h,
                                      double cx, double cy, double R) {
        Composite prev = g2.getComposite();
        boolean done = s.companionDone;
        // Completion pulse in signature violet (Theme core-glow family).
        if (done) {
            long age = System.currentTimeMillis() - s.companionDoneTime;
            float fade = age < 700 ? (1f - age / 700f) : 0f;
            if (fade > 0.01f) {
                g2.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, Math.min(1f, fade * 0.45f)));
                g2.setColor(new Color(168, 85, 247));
                int d = (int) (R * 2.6);
                g2.fillOval((int) (cx - d / 2.0), (int) (cy - d / 2.0), d, d);
                g2.setComposite(prev);
            }
        }
        // Pulsating rim accent arc: cyan -> magenta (PAL_A[0] / PAL_A[4]).
        double arcR = R * 1.12;
        double head = 90.0 - (s.ringTime * 140.0 % 360.0);
        accentArc.setArc(cx - arcR, cy - arcR, arcR * 2, arcR * 2, head, 75, Arc2D.OPEN);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
        g2.setPaint(wirePaint);
        g2.setStroke(ACCENT_STROKE);
        g2.draw(accentArc);
        // Indicator dot riding the arc head in magenta.
        double a = Math.toRadians(head + 75);
        double dx = cx + Math.cos(a) * arcR, dy = cy - Math.sin(a) * arcR;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.95f));
        g2.setColor(Theme.PAL_A[4]);
        g2.fillOval((int) (dx - 2.5), (int) (dy - 2.5), 5, 5);
        g2.setComposite(prev);
        String label;
        if (done) {
            label = "\u2713 Done";
        } else if (s.companionTotal > 0) {
            String act = s.companionLabel == null ? "" : s.companionLabel.trim();
            if (act.length() > 14) act = act.substring(0, 14);
            label = "\u26A1 Step " + Math.max(1, s.companionStep)
                    + "/" + s.companionTotal + (act.isEmpty() ? "" : ": " + act);
        } else {
            label = "\u25CF Working...";
        }
        g2.setFont(BADGE_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(label);
        int pw = Math.min(w - 8, tw + 16);
        int ph = 18;
        int px = (w - pw) / 2;
        int py = h - ph - 6;
        badgeRect.setRoundRect(px, py, pw, ph, ph, ph);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.72f));
        g2.setColor(new Color(6, 12, 20, 210));
        g2.fill(badgeRect);
        Color accent = done ? Theme.PAL_A[4] : Theme.PAL_A[0];
        Color ink = done ? Theme.PAL_A[4] : Theme.PAL_B[0];
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f));
        g2.setColor(accent);
        g2.setStroke(BADGE_STROKE);
        g2.draw(badgeRect);
        g2.setColor(ink);
        int tx = px + (pw - tw) / 2;
        int ty = py + (ph + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(label, tx, ty);
        g2.setComposite(prev);
    }

    private void drawOrbit(Graphics2D g2, double cx, double cy, double R,
                           double rMul, double tiltX, double tiltZ,
                           double spin, double alpha, float width,
                           LinearGradientPaint lg) {
        final int N = 76;
        frontOrbit.reset();
        backOrbit.reset();
        double p0x = 0, p0y = 0, p0z = 0;
        for (int i = 0; i <= N; i++) {
            double a  = (i / (double) N) * Math.PI * 2 + spin;
            double x0 = Math.cos(a);
            double y0 = Math.sin(a);
            double y1 = y0 * Math.cos(tiltX);
            double z1 = y0 * Math.sin(tiltX);
            double x1 = x0 * Math.cos(tiltZ) - y1 * Math.sin(tiltZ);
            double y2 = x0 * Math.sin(tiltZ) + y1 * Math.cos(tiltZ);
            double f  = PERSP / (PERSP - z1);
            double sx = cx + x1 * R * f * rMul;
            double sy = cy + y2 * R * f * rMul;
            if (i == 0) { p0x = sx; p0y = sy; p0z = z1; continue; }
            double zm = (z1 + p0z) * 0.5;
            Path2D.Float p = zm > 0 ? frontOrbit : backOrbit;
            p.moveTo(p0x, p0y);
            p.lineTo(sx, sy);
            p0x = sx; p0y = sy; p0z = z1;
        }
        Composite old = g2.getComposite();
        g2.setPaint(lg);
        g2.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                        (float) Math.min(1.0, alpha * 0.30)));
        g2.draw(backOrbit);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                        (float) Math.min(1.0, alpha)));
        g2.draw(frontOrbit);
        g2.setComposite(old);
    }

    private void drawRunner(Graphics2D g2, double cx, double cy, double R,
                            double rMul, double tiltX, double tiltZ,
                            double angle, double alpha, LinearGradientPaint lg) {
        double x0 = Math.cos(angle);
        double y0 = Math.sin(angle);
        double y1 = y0 * Math.cos(tiltX);
        double z1 = y0 * Math.sin(tiltX);
        double x1 = x0 * Math.cos(tiltZ) - y1 * Math.sin(tiltZ);
        double y2 = x0 * Math.sin(tiltZ) + y1 * Math.cos(tiltZ);
        double f  = PERSP / (PERSP - z1);
        double sx = cx + x1 * R * f * rMul;
        double sy = cy + y2 * R * f * rMul;
        boolean front = z1 > 0;
        double a  = alpha * (front ? 1.0 : 0.24);
        double sz = front ? 2.4 : 1.4;
        Composite old = g2.getComposite();
        g2.setPaint(lg);
        if (front) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                            (float) Math.min(1.0, a * 0.28)));
            haloEllipse.setFrame(sx - sz * 3.4, sy - sz * 3.4, sz * 6.8, sz * 6.8);
            g2.fill(haloEllipse);
        }
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                        (float) Math.min(1.0, a)));
        dotEllipse.setFrame(sx - sz, sy - sz, sz * 2, sz * 2);
        g2.fill(dotEllipse);
        g2.setComposite(old);
    }
}

/* =============================================================== */
/*  SIRI-STYLE WAKE ACTIVATION OVERLAY                             */
/* =============================================================== */

/**
 * A transparent, focus-less JWindow that appears above the taskbar when the
 * wake word is heard. It never opens the main Proton window: it blooms out of
 * a single point (0-150 ms), expands organically (150-450 ms), materialises a
 * minimal listening pill (450-760 ms), then runs its own voice conversation
 * and peacefully dissolves back into standby.
 */
final class SiriOverlay {

    private static final int  WIN_W       = 540;
    private static final int  WIN_H       = 380;
    static final int          ORB_SIZE    = 280;
    private static final long ACT_MS      = 760L;
    private static final long DISMISS_MS  = 640L;

    private final ProtonFrame owner;
    private final S s;
    private final Brain brain;
    private final JWindow win;
    private final SiriStage stage;
    private final OrbPanel orb;
    private final javax.swing.Timer ticker;

    private volatile boolean active;
    private volatile boolean activating;
    private volatile boolean dismissing;
    private volatile boolean processing;
    private volatile int turn;
    private long t0;
    private long lastNanos;
    private long dismissT0;
    private double clock;
    private double simPhase;
    private float orbAct;
    private float uiAlpha;
    private float dismissFrom;
    private float dismissFromUi;
    private String status = "Listening...";
    private String pendingQuery;
    private MicCapture mic;

    SiriOverlay(ProtonFrame owner, S s, Brain brain) {
        this.owner = owner;
        this.s = s;
        this.brain = brain;

        win = new JWindow();
        win.setAlwaysOnTop(true);
        win.setFocusableWindowState(false);
        try { win.setAutoRequestFocus(false); } catch (Throwable ignored) {}
        try { win.setBackground(new Color(0, 0, 0, 0)); }
        catch (Throwable t) { win.setBackground(Theme.BG); }
        win.setSize(WIN_W, WIN_H);

        orb = new OrbPanel(s);
        orb.setOpaque(false);
        orb.setSiriMode(true);
        orb.setActivation(0f);
        orb.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { dismiss(); }
        });

        stage = new SiriStage(orb, this);
        win.setContentPane(stage);

        ticker = new javax.swing.Timer(15, e -> tick());
        ticker.setCoalesce(true);
        positionBottomCenter();
    }

    private void positionBottomCenter() {
        try {
            Rectangle max = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int x = max.x + (max.width - WIN_W) / 2;
            int y = max.y + max.height - WIN_H - 16;
            if (x < max.x) x = max.x;
            if (y < max.y) y = max.y;
            win.setLocation(x, y);
        } catch (Throwable t) {
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
            win.setLocation(Math.max(0, (scr.width - WIN_W) / 2),
                    Math.max(0, scr.height - WIN_H - 56));
        }
    }

    void activate(String remainder) {
        if (active) { try { win.toFront(); } catch (Throwable ignored) {} return; }
        active = true;
        activating = true;
        dismissing = false;
        processing = false;
        turn++;
        orbAct = 0f;
        uiAlpha = 0f;
        clock = 0;
        simPhase = 0;
        pendingQuery = (remainder != null && remainder.trim().length() >= 3)
                ? remainder.trim() : null;
        status = pendingQuery != null ? "One moment..." : "Listening...";
        t0 = System.nanoTime();
        lastNanos = t0;

        s.micActive = false;
        s.micLive = false;
        s.micHeard = false;
        s.speaking = false;
        s.thinking = false;
        s.level = 0f;
        s.targetLevel = 0f;
        s.liveLevel = 0f;
        s.thinkPhase = 0f;
        owner.siriActive = true;

        orb.setActivation(0f);
        positionBottomCenter();
        win.setVisible(true);
        try { win.toFront(); } catch (Throwable ignored) {}
        ticker.restart();
        stage.repaint();
        orb.repaint();
    }

    /* ---------- read-only view for the stage ---------- */

    float activation()    { return orbAct; }
    float uiAlpha()       { return uiAlpha; }
    String statusText()   { return status; }
    double clock()        { return clock; }
    float level()         { return s.level; }
    float thinkPhase()    { return s.thinkPhase; }

    /* ---------- 60 FPS animation + state machine ---------- */

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1e9;
        lastNanos = now;
        if (dt < 0) dt = 0.016;
        if (dt > 0.05) dt = 0.05;
        clock += dt;

        // The main window usually stays hidden, so the overlay advances the
        // shared S animation clock. If the window is visible, its ticker owns it.
        if (!owner.isAnimatingForSiri()) advance(dt);

        if (dismissing) {
            double p = Math.min(1.0, (now - dismissT0) / (DISMISS_MS * 1e6));
            float e = (float) easeInOut(p);
            orbAct = dismissFrom * (1f - e);
            uiAlpha = dismissFromUi * (1f - e);
            orb.setActivation(orbAct);
            stage.repaint();
            orb.repaint();
            if (p >= 1.0) finishDismiss();
            return;
        }

        if (activating) {
            double ms = (now - t0) / 1e6;
            float a;
            if (ms < 150) {
                a = 0.06f * (float) easeOut(ms / 150.0);
                uiAlpha = 0f;
            } else if (ms < 450) {
                a = 0.06f + 0.70f * (float) easeInOut((ms - 150) / 300.0);
                uiAlpha = 0.10f * (float) easeInOut((ms - 150) / 300.0);
            } else {
                double q = Math.min(1.0, (ms - 450) / (ACT_MS - 450));
                a = 0.76f + 0.24f * (float) easeInOut(q);
                uiAlpha = 0.10f + 0.90f * (float) easeInOut(q);
            }
            if (a > orbAct) orbAct = a;
            orb.setActivation(orbAct);
            if (mic == null && pendingQuery == null && ms >= 420) beginListening();
            if (ms >= ACT_MS) {
                activating = false;
                orbAct = 1f;
                uiAlpha = 1f;
                orb.setActivation(1f);
                if (pendingQuery != null) {
                    String q = pendingQuery;
                    pendingQuery = null;
                    processText(q);
                }
            }
            stage.repaint();
            orb.repaint();
            return;
        }

        stage.repaint();
        orb.repaint();
    }

    private void advance(double dt) {
        if (s.micActive) {
            float sim = simLevel(dt);
            s.targetLevel = s.micLive ? Math.max(s.liveLevel, 0.06f + sim * 0.10f) : sim;
        } else if (s.speaking) {
            s.targetLevel = simLevel(dt) * 0.95f;
        } else {
            s.targetLevel = 0f;
        }
        float k = s.targetLevel > s.level ? 0.34f : 0.070f;
        s.level += (s.targetLevel - s.level) * k;
        if (s.level < 0.0005f) s.level = 0f;

        float tp = s.thinking ? 1f : 0f;
        float rate = s.thinking ? 1.9f : 2.6f;
        s.thinkPhase += (tp - s.thinkPhase) * Math.min(1f, (float) (dt * rate));
        if (Math.abs(s.thinkPhase - tp) < 0.002f) s.thinkPhase = tp;
        if (s.thinking) s.thinkTime += dt;

        s.noiseT += dt * (0.80 + s.level * 2.6 + s.thinkPhase * 0.45);
        s.rotY += dt * (0.14 + s.level * 0.62) * (1 - s.thinkPhase * 0.72);
        s.ringTime += dt;
    }

    private float simLevel(double dt) {
        simPhase += dt;
        double a = Math.abs(Math.sin(simPhase * 2.1));
        double b = Math.abs(Math.sin(simPhase * 5.7 + 1.3));
        double c = Math.abs(Math.sin(simPhase * 11.3 + 0.7));
        float v = (float) ((0.12 + a * 0.30 + b * 0.12 + c * 0.05)
                * (0.62 + Math.random() * 0.38));
        if (v < 0.08f) v = 0.08f;
        if (v > 1f) v = 1f;
        return v;
    }

    /* ---------- voice loop: listen -> think -> speak -> listen ---------- */

    private void beginListening() {
        if (!active || dismissing || mic != null) return;
        final int g = ++turn;

        s.micActive = true;
        s.micLive = false;
        s.micHeard = false;
        s.micStart = System.currentTimeMillis();
        s.micLastData = 0L;
        s.liveLevel = 0f;
        s.targetLevel = 0.65f;
        s.speaking = false;
        s.thinking = false;
        status = "Listening...";

        final MicCapture mc = new MicCapture(s, new MicListener() {
            public void onSpeechEnd(MicCapture src) {
                SwingUtilities.invokeLater(() -> endListening(src, g));
            }
            public void onNoSpeech(MicCapture src) {
                SwingUtilities.invokeLater(() -> {
                    if (!active || dismissing || g != turn) return;
                    mic = null;
                    s.micActive = false;
                    s.micLive = false;
                    s.micHeard = false;
                    s.targetLevel = 0f;
                    dismiss();
                });
            }
            public void onFail(MicCapture src) {
                SwingUtilities.invokeLater(() -> {
                    if (!active || dismissing || g != turn) return;
                    mic = null;
                    s.micActive = false;
                    s.micLive = false;
                    s.micHeard = false;
                    s.targetLevel = 0f;
                    dismiss();
                });
            }
        });
        mic = mc;
        mc.start();
    }

    private void endListening(final MicCapture src, final int g) {
        if (!active || dismissing || g != turn || src != mic) return;
        mic = null;
        s.micActive = false;
        s.micLive = false;
        s.micHeard = false;
        s.targetLevel = 0f;
        processing = true;
        status = "One moment...";

        new Thread(() -> {
            byte[] wav = null;
            try { wav = src.stopAndGetAudio(); } catch (Throwable ignored) {}
            try { src.stopCapture(); } catch (Throwable ignored) {}
            String text = null;
            if (wav != null && wav.length > 6000) {
                try { text = brain.transcribe(wav); } catch (Throwable ignored) {}
            }
            final String q = text == null ? "" : text.trim();
            SwingUtilities.invokeLater(() -> {
                if (!active || dismissing || g != turn) return;
                if (q.length() < 2) {
                    processing = false;
                    beginListening();
                    return;
                }
                processText(q);
            });
        }, "siri-stt").start();
    }

    private void processText(final String q) {
        if (!active || dismissing) return;
        final int g = ++turn;
        processing = true;
        s.thinking = true;
        s.thinkTime = 0;
        s.speaking = false;
        s.micActive = false;
        status = "Thinking...";
        Brain.refreshContextAsync();

        brain.earlySpeaker = spoken -> {
            if (!active || dismissing) return;
            SwingUtilities.invokeLater(() -> {
                if (active && !dismissing) {
                    s.thinking = false;
                    s.speaking = true;
                    status = "Speaking...";
                }
            });
            try { brain.speak(spoken); }
            finally {
                SwingUtilities.invokeLater(() -> {
                    if (active && !dismissing) {
                        s.speaking = false;
                        if (processing) {
                            s.thinking = true;
                            status = "Thinking...";
                        }
                    }
                });
            }
        };

        new Thread(() -> {
            String reply;
            try {
                reply = brain.process(q);
                if (reply == null || reply.trim().isEmpty()) reply = "done.";
            } catch (Throwable t) {
                reply = "Sorry, I ran into a problem.";
            }
            if (!active || dismissing || g != turn) return;

            String toSay;
            try { toSay = brain.remainingSpeech(reply); }
            catch (Throwable t) { toSay = reply; }
            // null = everything was already spoken as an early acknowledgement.
            final String say = toSay == null ? "" : toSay.trim();

            if (!say.isEmpty()) {
                s.thinking = false;
                s.speaking = true;
                SwingUtilities.invokeLater(() -> {
                    if (active && !dismissing && g == turn) status = "Speaking...";
                });
                try { brain.speak(say); }
                catch (Throwable ignored) {}
                finally { s.speaking = false; }
            }

            SwingUtilities.invokeLater(() -> {
                if (!active || dismissing || g != turn) return;
                s.thinking = false;
                s.speaking = false;
                processing = false;
                if (isFarewell(q)) { dismiss(); return; }
                s.targetLevel = 0f;
                status = "Listening...";
                scheduleListen(g, 420);
            });
        }, "siri-brain").start();
    }

    private void scheduleListen(final int g, long delayMs) {
        new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { return; }
            SwingUtilities.invokeLater(() -> {
                if (!active || dismissing || g != turn) return;
                if (processing || s.speaking || s.thinking) return;
                beginListening();
            });
        }, "siri-rearm").start();
    }

    /* ---------- dismissal ---------- */

    void dismiss() {
        if (!active || dismissing) return;
        dismissing = true;
        dismissT0 = System.nanoTime();
        dismissFrom = Math.max(orbAct, 0.05f);
        dismissFromUi = uiAlpha;
        turn++;

        MicCapture m = mic;
        mic = null;
        if (m != null) { try { m.stopCapture(); } catch (Throwable ignored) {} }

        s.micActive = false;
        s.micLive = false;
        s.micHeard = false;
        s.targetLevel = 0f;
        try { brain.stopSpeaking(); } catch (Throwable ignored) {}
        s.speaking = false;
        s.thinking = false;
        processing = false;
        status = "Standing by";
    }

    private void finishDismiss() {
        ticker.stop();
        try { win.setVisible(false); } catch (Throwable ignored) {}
        active = false;
        dismissing = false;
        activating = false;
        processing = false;
        orbAct = 0f;
        uiAlpha = 0f;
        orb.setActivation(0f);

        s.micActive = false;
        s.micLive = false;
        s.micHeard = false;
        s.speaking = false;
        s.thinking = false;
        s.level = 0f;
        s.targetLevel = 0f;
        s.thinkPhase = 0f;
        brain.earlySpeaker = null;
        owner.onSiriFinished();
    }

    void disposeOverlay() {
        try { ticker.stop(); } catch (Throwable ignored) {}
        MicCapture m = mic;
        mic = null;
        if (m != null) { try { m.stopCapture(); } catch (Throwable ignored) {} }
        try { win.dispose(); } catch (Throwable ignored) {}
        active = false;
        dismissing = false;
        owner.siriActive = false;
    }

    /* ---------- helpers ---------- */

    private static double easeInOut(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    private static double easeOut(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        double u = 1 - t;
        return 1 - u * u * u;
    }

    private static boolean isFarewell(String q) {
        if (q == null) return false;
        String t = q.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ").trim();
        return t.matches("(bye|goodbye|good bye|that s all|thats all|that is all|"
                + "thank you|thanks|no thanks|never mind|nevermind|stop|stop listening|"
                + "go to sleep|sleep now|standby|stand by|you can go|dismiss|close)");
    }
}

@SuppressWarnings("serial")
class SiriStage extends JPanel {

    private final OrbPanel orb;
    private final SiriOverlay owner;

    private final double[] aAng = new double[72];
    private final double[] aRad = new double[72];
    private final double[] aSpd = new double[72];
    private final double[] aSz  = new double[72];
    private final double[] aPh  = new double[72];

    private final Ellipse2D.Double dot = new Ellipse2D.Double();
    private final Ellipse2D.Double glow = new Ellipse2D.Double();
    private final RoundRectangle2D.Float pill = new RoundRectangle2D.Float();
    private final RoundRectangle2D.Float bar = new RoundRectangle2D.Float();
    private final BasicStroke hairline = new BasicStroke(1f);

    SiriStage(OrbPanel orb, SiriOverlay owner) {
        this.orb = orb;
        this.owner = owner;
        setOpaque(false);
        setLayout(null);
        add(orb);
        Random rnd = new Random(0x51B1L);
        for (int i = 0; i < aAng.length; i++) {
            aAng[i] = rnd.nextDouble() * Math.PI * 2;
            aRad[i] = 0.55 + rnd.nextDouble() * 0.85;
            aSpd[i] = 0.25 + rnd.nextDouble() * 0.90;
            aSz[i]  = 0.70 + rnd.nextDouble() * 1.80;
            aPh[i]  = rnd.nextDouble();
        }
    }

    public void doLayout() {
        int w = getWidth(), h = getHeight();
        int size = Math.min(SiriOverlay.ORB_SIZE, Math.min(w - 40, (int) (h * 0.74)));
        if (size < 40) size = 40;
        orb.setBounds((w - size) / 2, 16, size, size);
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int w = getWidth(), h = getHeight();
        if (w < 10 || h < 10) { g2.dispose(); return; }

        float act = owner.activation();
        float ui  = owner.uiAlpha();
        if (act <= 0.003f && ui <= 0.003f) { g2.dispose(); return; }

        Rectangle ob = orb.getBounds();
        double cx = ob.x + ob.width / 2.0;
        double cy = ob.y + ob.height / 2.0;
        double base = Math.min(w, h) * 0.5;
        double t = owner.clock();

        float breathe = (float) (0.5 + 0.5 * Math.sin(t * 1.55));
        Color warm = Theme.mix(new Color(0x6A5BFF), new Color(0xFF5BD6), 0.35f + 0.30f * breathe);
        Color cool = Theme.mix(new Color(0x5AD7FF), new Color(0x8F5BFF),
                0.5f + 0.15f * (float) Math.sin(t * 0.7));

        // Layered ambient bloom: soft, wide, and darker near the edges.
        double gr = base * (0.42 + 0.28 * act);
        RadialGradientPaint rg1 = new RadialGradientPaint(
                new Point2D.Double(cx, cy), (float) gr,
                new float[]{0f, 0.42f, 1f},
                new Color[]{
                        new Color(warm.getRed(), warm.getGreen(), warm.getBlue(),
                                Theme.clamp255(255 * 0.17f * act)),
                        new Color(warm.getRed(), warm.getGreen(), warm.getBlue(),
                                Theme.clamp255(255 * 0.070f * act)),
                        new Color(0, 0, 0, 0)});
        g2.setPaint(rg1);
        glow.setFrame(cx - gr, cy - gr, gr * 2, gr * 2);
        g2.fill(glow);

        double ir = base * (0.22 + 0.26 * act);
        RadialGradientPaint rg2 = new RadialGradientPaint(
                new Point2D.Double(cx, cy), (float) ir,
                new float[]{0f, 0.52f, 1f},
                new Color[]{
                        new Color(cool.getRed(), cool.getGreen(), cool.getBlue(),
                                Theme.clamp255(255 * 0.13f * act)),
                        new Color(cool.getRed(), cool.getGreen(), cool.getBlue(),
                                Theme.clamp255(255 * 0.050f * act)),
                        new Color(0, 0, 0, 0)});
        g2.setPaint(rg2);
        glow.setFrame(cx - ir, cy - ir, ir * 2, ir * 2);
        g2.fill(glow);

        // Awakening particles: emerge from the point, drift outward, then fade.
        Color spark = Theme.mix(cool, Color.WHITE, 0.35f);
        for (int i = 0; i < aAng.length; i++) {
            double life = (t * 0.55 * aSpd[i] + aPh[i]) % 1.0;
            double r = (8 + life * (70 + 130 * act)) * aRad[i];
            double ang = aAng[i] + Math.sin(t * 0.6 + i) * 0.35 + life * 0.8;
            double x = cx + Math.cos(ang) * r;
            double y = cy + Math.sin(ang) * r * 0.62 - life * 24.0;
            float a = (float) ((1 - life) * (0.08 + act * 0.30)
                    * Math.min(1.0, aSz[i]));
            if (a <= 0.01f) continue;
            g2.setColor(new Color(spark.getRed(), spark.getGreen(), spark.getBlue(),
                    Theme.clamp255(255 * a)));
            double sz = aSz[i] * (0.9 + act * 1.4);
            dot.setFrame(x - sz / 2, y - sz / 2, sz, sz);
            g2.fill(dot);
        }

        if (ui > 0.01f) paintStatus(g2, w, h, ui, t);
        g2.dispose();
    }

    private void paintStatus(Graphics2D g2, int w, int h, float ui, double t) {
        String label = owner.statusText();
        Font f = Fnt.get(Font.PLAIN, 13f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(label);
        int ph = 34;
        int pw = tw + 18 + 16 + 30;
        int px = (w - pw) / 2;
        int py = h - ph - 20;

        pill.setRoundRect(px, py, pw, ph, ph, ph);
        g2.setColor(new Color(8, 8, 14, Theme.clamp255(180 * ui)));
        g2.fill(pill);
        g2.setColor(new Color(255, 255, 255, Theme.clamp255(26 * ui)));
        g2.setStroke(hairline);
        g2.draw(pill);

        // Tiny living waveform: the listening indicator.
        int bx = px + 17;
        int midY = py + ph / 2;
        float lvl = owner.level();
        float tp = owner.thinkPhase();
        for (int k = 0; k < 3; k++) {
            double amp;
            if (label.startsWith("Thinking")) {
                amp = 0.45 + 0.28 * Math.sin(t * 2.4 + k * 1.4);
            } else if (label.startsWith("Speaking")) {
                amp = 0.38 + 0.56 * lvl;
            } else if (label.startsWith("Listening")) {
                amp = 0.28 + 0.72 * lvl;
            } else {
                amp = 0.22 + tp * 0.30;
            }
            if (amp < 0.05) amp = 0.05;
            float bh = (float) (4 + Math.abs(Math.sin(t * 4.2 + k * 1.6)) * 11 * amp);
            if (bh > 18) bh = 18;
            bar.setRoundRect(bx + k * 5, midY - bh / 2f, 2.6f, bh, 2.6f, 2.6f);
            float ba = 0.55f + 0.45f * (float) Math.sin(t * 3.1 + k);
            g2.setColor(new Color(158, 150, 255, Theme.clamp255(255 * ba * ui)));
            g2.fill(bar);
        }

        Color ink = Theme.mix(new Color(0xC9C3FF), new Color(0xB8ECFF),
                (float) (0.5 + 0.5 * Math.sin(t * 0.8)));
        g2.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(),
                Theme.clamp255(238 * ui)));
        int tx = bx + 22;
        int ty = py + (ph + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(label, tx, ty);
    }
}

/* =============================================================== */
/*  THOUGHT WINDOW -- live Nemotron reasoning in liquid glass       */
/* =============================================================== */

@SuppressWarnings("serial")
class ThoughtWindow extends JWindow {
    private static final int W = 404;
    private static final int H = 262;

    private final JTextArea area = new JTextArea();
    private final Glass glass = new Glass();
    private final StringBuilder pending = new StringBuilder();
    private final Object lock = new Object();
    private final javax.swing.Timer flush;
    private final javax.swing.Timer pulse;
    private final javax.swing.Timer fade;

    private volatile boolean active;
    private volatile int gen;
    private float alpha = 0f, target = 0f;
    private boolean hideWhenZero;
    private float phase;

    ThoughtWindow() {
        setBackground(new Color(0, 0, 0, 0));
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setAlwaysOnTop(true);
        try { setType(Window.Type.UTILITY); } catch (Throwable ignored) {}
        setSize(W, H);

        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        area.setForeground(new Color(0xDCE1FF));
        area.setFont(Fnt.get(Font.PLAIN, 11.5f));
        area.setBorder(new EmptyBorder(0, 0, 0, 0));

        JScrollPane sp = new JScrollPane(area);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

        glass.setOpaque(false);
        glass.setLayout(new BorderLayout());
        glass.setBorder(new EmptyBorder(46, 18, 16, 18));
        glass.add(sp, BorderLayout.CENTER);
        setContentPane(glass);

        flush = new javax.swing.Timer(60, e -> flushPending());
        flush.setCoalesce(true);
        pulse = new javax.swing.Timer(50, e -> { phase += 0.10f; glass.repaint(); });
        fade = new javax.swing.Timer(16, e -> tickFade());
    }

    void begin() {
        if (SwingUtilities.isEventDispatchThread()) beginEdt();
        else SwingUtilities.invokeLater(this::beginEdt);
    }

    private void beginEdt() {
        gen++;
        active = true;
        synchronized (lock) { pending.setLength(0); }
        area.setText("");
        position();
        if (!isVisible()) alpha = 0f;
        target = 1f;
        hideWhenZero = false;
        setVisible(true);
        toFront();
        flush.start();
        pulse.start();
        if (!fade.isRunning()) fade.start();
    }

    void append(String delta) {
        if (delta == null || delta.isEmpty()) return;
        synchronized (lock) {
            pending.append(delta);
            if (pending.length() > 7000) pending.delete(0, pending.length() - 7000);
        }
    }

    void end() {
        final int g = gen;
        SwingUtilities.invokeLater(() -> {
            flushPending();
            javax.swing.Timer wait = new javax.swing.Timer(450, e -> {
                ((javax.swing.Timer) e.getSource()).stop();
                if (gen != g) return;   // a newer stream already started
                target = 0f;
                hideWhenZero = true;
                if (!fade.isRunning()) fade.start();
            });
            wait.setRepeats(false);
            wait.start();
        });
    }

    void disposeWindow() {
        SwingUtilities.invokeLater(() -> {
            active = false;
            flush.stop();
            pulse.stop();
            fade.stop();
            setVisible(false);
            dispose();
        });
    }

    private void flushPending() {
        if (!active) return;
        String add;
        synchronized (lock) {
            if (pending.length() == 0) return;
            add = pending.toString();
            pending.setLength(0);
        }
        String text = area.getText() + add;
        if (text.length() > 6000) text = text.substring(text.length() - 6000);
        area.setText(text);
        area.setCaretPosition(text.length());
    }

    private void tickFade() {
        if (alpha < target) alpha = Math.min(target, alpha + 0.14f);
        else if (alpha > target) alpha = Math.max(target, alpha - 0.14f);
        try { setOpacity(Math.max(0.01f, alpha)); } catch (Throwable ignored) {}
        glass.repaint();
        if (Math.abs(alpha - target) < 0.01f) {
            fade.stop();
            if (target == 0f && hideWhenZero) {
                active = false;
                hideWhenZero = false;
                flush.stop();
                pulse.stop();
                setVisible(false);
            }
        }
    }

    private void position() {
        try {
            Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            setLocation(b.x + b.width - getWidth() - 22, b.y + 22);
        } catch (Throwable ignored) {}
    }

    /** Paints the liquid-glass card and its live THINKING header. */
    private final class Glass extends JPanel {
        Glass() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                float a = Math.max(0f, Math.min(1f, alpha));
                RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 22, 22);
                g2.setColor(new Color(9, 8, 20, (int) (238 * a)));
                g2.fill(rr);
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, (int) (24 * a)),
                        0, h, new Color(255, 255, 255, (int) (4 * a))));
                g2.fill(rr);
                g2.setColor(new Color(255, 255, 255, (int) (40 * a)));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(rr);

                float pulseAmt = 0.55f + 0.45f * (float) Math.sin(phase);
                g2.setColor(new Color(120, 180, 255, (int) (a * 70 * pulseAmt)));
                g2.fill(new Ellipse2D.Float(14, 16, 16, 16));
                g2.setColor(new Color(150, 205, 255, (int) (a * 255)));
                g2.fill(new Ellipse2D.Float(19, 21, 6, 6));

                g2.setFont(Fnt.get(Font.BOLD, 9.5f));
                g2.setColor(new Color(0x9A9CB4));
                g2.drawString("THINKING", 36, 28);
                String tag = "NEMOTRON";
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(new Color(0x6F7390));
                g2.drawString(tag, w - 18 - fm.stringWidth(tag), 28);

                g2.setColor(new Color(255, 255, 255, (int) (22 * a)));
                g2.drawLine(18, 38, w - 18, 38);
            } finally {
                g2.dispose();
            }
        }
    }
}

/* =============================================================== */
/*  PROTON IDE -- PROTON's own code editor.                        */
/*  The user can read and copy the code but never edit it: only    */
/*  PROTON writes here. Follow-up edits are shown as a live diff,  */
/*  removed lines in red with "(-)", added lines in green with "+" */
/* =============================================================== */

@SuppressWarnings("serial")
class ProtonIDE extends JFrame {

    private static final Color BG     = new Color(0x0B0A14);
    private static final Color BAR    = new Color(0x14121F);
    private static final Color LINE   = new Color(0x2A2740);
    private static final Color TEXT   = new Color(0xD9DEF7);
    private static final Color MUTED  = new Color(0x7E829C);
    private static final Color ACCENT = new Color(0x7FB4FF);
    private static final Color ADD_FG = new Color(0x8FE3A0);
    private static final Color DEL_FG = new Color(0xFF9BA1);
    private static final Color ADD_BG = new Color(60, 200, 110, 48);
    private static final Color DEL_BG = new Color(235, 70, 85, 48);
    static final Color OK  = new Color(0x7CE3A0);
    static final Color ERR = new Color(0xFF8F96);

    static final String MONO;
    static {
        String pick = Font.MONOSPACED;
        try {
            List<String> avail = Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String c : new String[]{"Cascadia Mono", "Cascadia Code", "Consolas",
                    "JetBrains Mono", "Fira Code", "Courier New"}) {
                if (avail.contains(c)) { pick = c; break; }
            }
        } catch (Throwable ignored) {}
        MONO = pick;
    }

    private final CodePane code = new CodePane();
    private final JLabel fileLbl = new JLabel("untitled");
    private final JLabel metaLbl = new JLabel("TEXT");
    private final JLabel statusLbl = new JLabel("READY");
    private final JScrollPane scroll;
    private javax.swing.Timer reveal;
    private String[] revealLines;
    private int revealAt;
    private volatile String fullCode = "";

    ProtonIDE() {
        super("PROTON IDE");
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setBackground(BG);
        setSize(860, 640);
        setMinimumSize(new Dimension(520, 340));
        setAlwaysOnTop(true);
        try { setFocusableWindowState(false); } catch (Throwable ignored) {}
        try { setAutoRequestFocus(false); } catch (Throwable ignored) {}

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createLineBorder(LINE, 1));

        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setBackground(BAR);
        head.setBorder(new EmptyBorder(9, 12, 9, 8));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        JLabel dot = new JLabel("\u25CF");
        dot.setForeground(ACCENT);
        dot.setFont(Fnt.get(Font.PLAIN, 13f));
        JLabel name = new JLabel("PROTON IDE");
        name.setForeground(TEXT);
        name.setFont(Fnt.get(Font.BOLD, 12f));
        fileLbl.setForeground(ACCENT);
        fileLbl.setFont(Fnt.get(Font.PLAIN, 12f));
        metaLbl.setForeground(MUTED);
        metaLbl.setFont(Fnt.get(Font.BOLD, 9.5f));
        left.add(dot);
        left.add(name);
        left.add(Box.createHorizontalStrut(6));
        left.add(fileLbl);
        left.add(metaLbl);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        statusLbl.setForeground(MUTED);
        statusLbl.setFont(Fnt.get(Font.BOLD, 9.5f));
        JLabel lock = new JLabel("\uD83D\uDD12 READ-ONLY \u00B7 PROTON ONLY");
        lock.setForeground(MUTED);
        lock.setFont(Fnt.get(Font.PLAIN, 9.5f));
        JLabel close = new JLabel("\u2715");
        close.setForeground(MUTED);
        close.setFont(Fnt.get(Font.PLAIN, 14f));
        close.setBorder(new EmptyBorder(0, 6, 0, 6));
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { setVisible(false); }
            public void mouseEntered(MouseEvent e) { close.setForeground(TEXT); }
            public void mouseExited(MouseEvent e)  { close.setForeground(MUTED); }
        });
        right.add(statusLbl);
        right.add(lock);
        right.add(close);

        head.add(left, BorderLayout.WEST);
        head.add(right, BorderLayout.EAST);
        final Point drag = new Point();
        head.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { drag.setLocation(e.getPoint()); }
        });
        head.addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                setLocation(p.x - drag.x, p.y - drag.y);
            }
        });

        code.setEditable(false);
        code.setOpaque(false);
        code.setBackground(BG);
        code.setForeground(TEXT);
        code.setFont(new Font(MONO, Font.PLAIN, 12));
        code.setBorder(new EmptyBorder(10, 12, 12, 12));
        code.setCaretColor(BG);
        code.setSelectionColor(new Color(0x30406A));
        code.setSelectedTextColor(TEXT);

        scroll = new JScrollPane(code);
        scroll.setBorder(null);
        scroll.setOpaque(true);
        scroll.getViewport().setBackground(BG);
        scroll.setBackground(BG);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        JLabel foot = new JLabel("  PROTON writes \u00B7 the user reads");
        foot.setForeground(MUTED);
        foot.setFont(Fnt.get(Font.PLAIN, 9.5f));
        foot.setBorder(new EmptyBorder(6, 9, 6, 9));
        foot.setOpaque(true);
        foot.setBackground(BAR);

        root.add(head, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        setContentPane(root);
    }

    /* ---------------- public API used by Brain ---------------- */

    void begin(String file, String lang, boolean keepCode) {
        edt(() -> {
            if (file != null && !file.isEmpty()) fileLbl.setText(file);
            if (lang != null && !lang.isEmpty()) metaLbl.setText(lang.toUpperCase(Locale.ROOT));
            if (!keepCode) {
                stopReveal();
                clearDoc();
                fullCode = "";
            }
            status("THINKING", ACCENT);
            showWindow();
        });
    }

    void writeCode(String text, boolean animate) {
        final String t = norm(text);
        edt(() -> {
            stopReveal();
            fullCode = t;
            clearDoc();
            String[] lines = splitLines(t);
            if (animate && lines.length > 0) {
                revealLines = lines;
                revealAt = 0;
                status("WRITING", ACCENT);
                reveal = new javax.swing.Timer(16, e -> {
                    int step = Math.max(1, revealLines.length / 120);
                    for (int i = 0; i < step && revealAt < revealLines.length; i++) {
                        appendLine("  ", revealLines[revealAt], TEXT);
                        revealAt++;
                    }
                    if (revealAt >= revealLines.length) {
                        stopReveal();
                        status("READY", MUTED);
                        code.setCaretPosition(0);
                    } else {
                        code.setCaretPosition(code.getDocument().getLength());
                    }
                });
                reveal.start();
            } else {
                for (String line : lines) appendLine("  ", line, TEXT);
                status("READY", MUTED);
                code.setCaretPosition(0);
            }
            showWindow();
        });
    }

    /** Renders the change from oldCode to newCode with red/green full-line diff bars. */
    void applyDiff(String oldCode, String newCode) {
        final String nu = norm(newCode);
        final String old = norm(oldCode);
        edt(() -> {
            stopReveal();
            fullCode = nu;
            List<DiffOp> ops = diffLines(splitLines(old), splitLines(nu));
            code.addRows.clear();
            code.delRows.clear();
            clearDoc();
            int row = 0;
            for (DiffOp op : ops) {
                Color fg = op.kind == '+' ? ADD_FG : op.kind == '-' ? DEL_FG : TEXT;
                String pre = op.kind == '+' ? "+ " : op.kind == '-' ? "- " : "  ";
                appendLine(pre, op.text, fg);
                if (op.kind == '+') code.addRows.add(row);
                else if (op.kind == '-') code.delRows.add(row);
                row++;
            }
            status("DIFF \u00B7 +" + code.addRows.size() + " \u00B7 -" + code.delRows.size(), ACCENT);
            code.setCaretPosition(0);
            code.repaint();
            showWindow();
        });
    }

    void status(String text, Color c) {
        edt(() -> { statusLbl.setText(text); statusLbl.setForeground(c); });
    }

    String getCode() { return fullCode; }

    void disposeWindow() {
        edt(() -> {
            stopReveal();
            setVisible(false);
            dispose();
        });
    }

    /* ---------------- internals ---------------- */

    private void showWindow() {
        if (!isVisible()) position();
        setVisible(true);
        toFront();
    }

    private void position() {
        try {
            Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int x = b.x + (b.width - getWidth()) / 2;
            int y = b.y + (b.height - getHeight()) / 2 - 20;
            setLocation(Math.max(b.x + 12, x), Math.max(b.y + 12, y));
        } catch (Throwable ignored) {}
    }

    private void clearDoc() {
        try { code.getStyledDocument().remove(0, code.getStyledDocument().getLength()); }
        catch (Throwable ignored) {}
        code.addRows.clear();
        code.delRows.clear();
        code.repaint();
    }

    private void appendLine(String prefix, String line, Color fg) {
        SimpleAttributeSet at = new SimpleAttributeSet();
        StyleConstants.setFontFamily(at, MONO);
        StyleConstants.setFontSize(at, 12);
        StyleConstants.setForeground(at, fg);
        try {
            code.getStyledDocument().insertString(
                    code.getStyledDocument().getLength(), prefix + line + "\n", at);
        } catch (Throwable ignored) {}
    }

    private void stopReveal() {
        if (reveal != null) { reveal.stop(); reveal = null; }
    }

    private static void edt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String[] splitLines(String s) {
        String[] p = norm(s).split("\n", -1);
        if (p.length > 0 && p[p.length - 1].isEmpty()) {
            String[] q = new String[p.length - 1];
            System.arraycopy(p, 0, q, 0, q.length);
            return q;
        }
        return p;
    }

    static final class DiffOp {
        final char kind;
        final String text;
        DiffOp(char k, String t) { kind = k; text = t; }
    }

    /** Classic LCS line diff; falls back to replace-all for very large files. */
    static List<DiffOp> diffLines(String[] a, String[] b) {
        List<DiffOp> ops = new ArrayList<>();
        int n = a.length, m = b.length;
        if ((long) n * m > 4_000_000L) {
            for (String s : a) ops.add(new DiffOp('-', s));
            for (String s : b) ops.add(new DiffOp('+', s));
            return ops;
        }
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) { ops.add(new DiffOp(' ', a[i])); i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1]) { ops.add(new DiffOp('-', a[i])); i++; }
            else { ops.add(new DiffOp('+', b[j])); j++; }
        }
        while (i < n) ops.add(new DiffOp('-', a[i++]));
        while (j < m) ops.add(new DiffOp('+', b[j++]));
        return ops;
    }

    /** Read-only code pane that paints full-width translucent diff rows. */
    private final class CodePane extends JTextPane {
        final Set<Integer> addRows = new HashSet<>();
        final Set<Integer> delRows = new HashSet<>();

        CodePane() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                for (int row : delRows) shade(g2, row, DEL_BG, DEL_FG);
                for (int row : addRows) shade(g2, row, ADD_BG, ADD_FG);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        private void shade(Graphics2D g2, int row, Color fill, Color bar) {
            try {
                javax.swing.text.Element root = getDocument().getDefaultRootElement();
                if (row < 0 || row >= root.getElementCount()) return;
                int off = root.getElement(row).getStartOffset();
                Rectangle2D r = modelToView2D(off);
                if (r == null) return;
                int y = (int) Math.floor(r.getY());
                int h = Math.max(1, (int) Math.ceil(r.getHeight()));
                g2.setColor(fill);
                g2.fillRect(0, y, getWidth(), h);
                g2.setColor(Theme.alpha(bar, 0.55));
                g2.fillRect(0, y, 3, h);
            } catch (Throwable ignored) {}
        }
    }
}

/* =============================================================== */
/*  CORNER MINI-ORB COMPANION HUD (Picture-in-Picture Task Mode)   */
/* =============================================================== */

@SuppressWarnings("serial")
class MiniCompanionOverlay extends JWindow {
    static final int SIZE = 150;

    private final S s;
    private final OrbPanel miniOrb;
    private final javax.swing.Timer miniTicker;
    private final Runnable onAbort;

    MiniCompanionOverlay(Window owner, S s, Runnable onAbort) {
        super(owner);
        this.s = s;
        this.onAbort = onAbort;
        setAlwaysOnTop(true);
        // Never steal keyboard focus from the app PROTON is driving.
        setFocusableWindowState(false);
        setBackground(new Color(0, 0, 0, 0));
        setSize(SIZE, SIZE);
        setLayout(new BorderLayout());

        miniOrb = new OrbPanel(s);
        miniOrb.setOpaque(false);
        add(miniOrb, BorderLayout.CENTER);

        MouseAdapter abort = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                Runnable r = MiniCompanionOverlay.this.onAbort;
                if (r != null) r.run();
            }
        };
        addMouseListener(abort);
        miniOrb.addMouseListener(abort);

        miniTicker = new javax.swing.Timer(16, e -> {
            if (isVisible()) miniOrb.repaint();
        });
        miniTicker.setCoalesce(true);
        miniTicker.start();

        positionBottomRight();
    }

    void positionBottomRight() {
        try {
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
            int pad = 24;
            int x = scr.width - SIZE - pad;
            int y = scr.height - SIZE - pad - 48; // clamped above Windows taskbar
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            setLocation(x, y);
        } catch (Throwable ignored) {}
    }

    void showOverlay() {
        positionBottomRight();
        setVisible(true);
        miniOrb.repaint();
    }

    void hideOverlay() {
        setVisible(false);
    }

    void disposeOverlay() {
        try { miniTicker.stop(); } catch (Throwable ignored) {}
        try { dispose(); } catch (Throwable ignored) {}
    }
}

@SuppressWarnings("serial")
class StatusPanel extends JPanel {
    private final S s;
    private static final int N = 46;
    private final float[] scales = new float[N];

    StatusPanel(S s) {
        this.s = s;
        setOpaque(false);
        setPreferredSize(new Dimension(100, 68));
        for (int i = 0; i < N; i++) scales[i] = 0.06f;
    }

    private void updateBars() {
        float lvl = s.level;
        float tp  = s.thinkPhase;
        double tt = s.thinkTime;
        float mid = (N - 1) / 2f;
        for (int i = 0; i < N; i++) {
            float d = Math.abs(i - mid) / mid;
            float c = 1 - d * d;
            float goal;
            if (tp > 0.15f) {
                double travel = 0.5 + 0.5 * Math.sin(tt * 2.4 - d * 3.2);
                goal = (float)(0.14 + tp * 0.42 * (0.35 + c * 0.75) * (0.55 + travel * 0.45));
            } else if (lvl > 0.004f) {
                double wob = 0.55 + 0.45 * Math.sin(s.noiseT * 4.4 + i * 0.72) *
                                         Math.cos(s.noiseT * 2.1 - i * 0.31);
                goal = (float)(0.06 + lvl * (0.22 + c * 0.88) * (0.48 + wob * 0.52));
                if (goal > 1f) goal = 1f;
            } else {
                goal = 0.055f;
            }
            scales[i] += (goal - scales[i]) * 0.26f;
        }
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        updateBars();
        int w = getWidth();
        float tp = s.thinkPhase;
        // Kinetic morph: peripheral chrome cross-fades as the window compresses.
        float fade = 1f - s.morphProgress;
        if (fade <= 0.01f) { g2.dispose(); return; }
        if (fade < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));

        int barW = 3, gap = 3;
        int totalW = N * barW + (N - 1) * gap;
        int startX = (w - totalW) / 2;
        int waveH = 30;
        int midY = waveH / 2;
        float maxH = waveH * 0.94f;

        Color cTop = Theme.mix(new Color(0xFF8CE0), new Color(0x8CE9FF), tp);
        Color cMid = Theme.mix(new Color(0xA855F7), new Color(0x5AA7FF), tp);
        Color cBot = Theme.mix(new Color(0x2B6BFF), new Color(0x3D6BFF), tp);

        for (int i = 0; i < N; i++) {
            float sc = scales[i];
            float bh = Math.max(2.5f, maxH * sc);
            float x = startX + i * (barW + gap);
            float y = midY - bh / 2f;
            LinearGradientPaint bg = new LinearGradientPaint(
                new Point2D.Float(0, y), new Point2D.Float(0, y + bh),
                new float[]{0f, 0.46f, 1f},
                new Color[]{cTop, cMid, cBot});
            g2.setPaint(bg);
            g2.fill(new RoundRectangle2D.Float(x, y, barW, bh, barW, barW));
        }

        String hintText;
        Color hintCol;
        if (tp > 0.4f) {
            hintText = "Deep reasoning \u2014 analysing context and options";
            hintCol  = new Color(0x7CC4FF);
        } else if (s.micActive) {
            if (!s.micLive)       hintText = "Starting microphone\u2026";
            else if (s.micHeard)  hintText = "Hearing you \u2014 pause and I'll respond";
            else                  hintText = "Listening \u2014 go ahead, I'll respond when you pause";
            hintCol = new Color(0xB98BFF);
        } else if (s.speaking) {
            hintText = "Speaking \u2014 tap the microphone to interrupt";
            hintCol  = new Color(0xB98BFF);
        } else {
            hintText = "Tap the microphone and speak, or press / to type";
            hintCol  = Theme.TEXT3;
        }
        Font hf = Fnt.get(Font.PLAIN, 12.5f);
        g2.setFont(hf);
        FontMetrics hfm = g2.getFontMetrics();
        int hy = waveH + 12 + hfm.getAscent();
        if (s.hintAlpha > 0.01f) {
            g2.setColor(Theme.alpha(hintCol, s.hintAlpha));
            g2.drawString(hintText, (w - hfm.stringWidth(hintText)) / 2, hy);
        }

        if (s.transcriptAlpha > 0.01f && s.transcript != null && !s.transcript.isEmpty()) {
            Font tf = Fnt.get(Font.ITALIC, 12.5f);
            g2.setFont(tf);
            FontMetrics tfm = g2.getFontMetrics();
            int maxW = Math.min(w - 40, 720);
            String txt = s.transcript;
            if (tfm.stringWidth(txt) > maxW) {
                while (txt.length() > 4 && tfm.stringWidth(txt + "\u2026") > maxW)
                    txt = txt.substring(0, txt.length() - 1);
                txt = txt + "\u2026";
            }
            g2.setColor(Theme.alpha(new Color(0x8F8FAE), s.transcriptAlpha));
            g2.drawString(txt, (w - tfm.stringWidth(txt)) / 2,
                          waveH + 12 + tfm.getAscent());
        }
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class DockPanel extends JPanel {
    private final S s;
    final ComposerPanel composer;
    private static final int COMPOSER_H = 56, TOP_PAD = 14;

    DockPanel(S s) {
        this.s = s;
        setOpaque(false);
        setLayout(null);
        setPreferredSize(new Dimension(100, 112));
        composer = new ComposerPanel(s);
        add(composer);
    }

    public void doLayout() {
        int w = getWidth();
        float e = s.composerExpand;
        int targetW = Math.round(340 + (770 - 340) * e);
        targetW = Math.min(targetW, w - 48);
        if (targetW < 200) targetW = Math.min(200, w - 20);
        int cx = (w - targetW) / 2;
        composer.setBounds(cx, TOP_PAD, targetW, COMPOSER_H);
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Kinetic morph: peripheral chrome cross-fades as the window compresses.
        float fade = 1f - s.morphProgress;
        if (fade <= 0.01f) { g2.dispose(); return; }
        if (fade < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
        Rectangle b = composer.getBounds();
        int mx = b.x, my = b.y + b.height + 10, mw = b.width;
        g2.setColor(new Color(255, 255, 255, 14));
        g2.fill(new RoundRectangle2D.Float(mx, my, mw, 2, 2, 2));
        float mv = Math.min(1f, s.level * 1.5f + s.thinkPhase * 0.42f);
        if (mv > 0.004f) {
            int fw = Math.max(1, (int)(mw * mv));
            Color c1 = Theme.mix(new Color(0x2B6BFF), new Color(0x8CE9FF), s.thinkPhase);
            Color c2 = Theme.mix(new Color(0xFF4DD0), new Color(0x5AA7FF), s.thinkPhase);
            LinearGradientPaint lg = new LinearGradientPaint(
                new Point2D.Float(mx, 0), new Point2D.Float(mx + mw, 0),
                new float[]{0f, 1f}, new Color[]{c1, c2});
            g2.setPaint(lg);
            g2.fill(new RoundRectangle2D.Float(mx, my, fw, 2, 2, 2));
        }

        String[] parts = {"Click the mic to talk", "Press / to type", "Esc to close"};
        Font f = Fnt.get(Font.PLAIN, 11.5f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        int gap = 16;
        int totalT = gap * (parts.length - 1);
        for (String p : parts) totalT += fm.stringWidth(p);
        int tx = (getWidth() - totalT) / 2;
        int ty = my + 22;
        g2.setColor(new Color(0x4E5068));
        for (String p : parts) {
            g2.drawString(p, tx, ty);
            tx += fm.stringWidth(p) + gap;
        }
        g2.dispose();
    }
}

@SuppressWarnings("serial")
class ComposerPanel extends JPanel {
    private final S s;
    final JTextField input;
    final IconButton attach;
    final IconButton deep;
    final IconButton mic;
    final SendButton send;

    ComposerPanel(S s) {
        this.s = s;
        setOpaque(false);
        setLayout(null);

        input = new JTextField();
        input.setOpaque(false);
        input.setBorder(null);
        input.setForeground(Theme.TEXT);
        input.setCaretColor(new Color(0xC9A0FF));
        input.setFont(Fnt.get(Font.PLAIN, 14f));
        input.setSelectionColor(new Color(168, 85, 247, 90));
        input.setSelectedTextColor(Color.WHITE);
        add(input);

        input.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) { setExpanded(true); }
            public void focusLost(FocusEvent e) {
                if (!input.getText().isEmpty()) return;
                javax.swing.Timer t = new javax.swing.Timer(180, ev -> {
                    if (!input.hasFocus() && input.getText().isEmpty()) setExpanded(false);
                });
                t.setRepeats(false);
                t.start();
            }
        });
        input.addActionListener(e -> submitAndClose());

        attach = new IconButton(Icons::clip,  16, 1.7f, true, false);
        deep   = new IconButton(Icons::spark, 16, 1.7f, true, false);
        mic    = new IconButton(Icons::mic,   17, 1.8f, true, false);
        send   = new SendButton(this::submitAndClose);
        attach.setAlpha(0f);
        deep.setAlpha(0f);
        send.alpha = 0f;

        attach.setOnClick(() -> input.requestFocusInWindow());
        deep.setOnClick(() -> {
            Window w = SwingUtilities.getWindowAncestor(ComposerPanel.this);
            if (w instanceof ProtonFrame) ((ProtonFrame) w).toggleThinking();
        });
        mic.setOnClick(() -> {
            Window w = SwingUtilities.getWindowAncestor(ComposerPanel.this);
            if (w instanceof ProtonFrame) ((ProtonFrame) w).toggleMic();
        });

        add(attach); add(deep); add(mic); add(send);

        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (e.getSource() == ComposerPanel.this) input.requestFocusInWindow();
            }
        });
    }

    void setExpanded(boolean on) {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w instanceof ProtonFrame) ((ProtonFrame) w).s.composerExpanded = on;
    }

    private void submitAndClose() {
        String v = input.getText();
        if (v == null || v.trim().isEmpty()) return;
        input.setText("");
        Window w = SwingUtilities.getWindowAncestor(ComposerPanel.this);
        if (w instanceof ProtonFrame) {
            ((ProtonFrame) w).submitQuery(v);
            ((ProtonFrame) w).collapseComposer();
        }
    }

    public void doLayout() {
        int w = getWidth(), h = getHeight();
        if (w < 10 || h < 10) return;
        float e = s.composerExpand;
        float aLead = e;
        attach.setAlpha(aLead);
        deep.setAlpha(aLead);
        send.alpha = e;
        boolean showLead = aLead > 0.02f;
        attach.setVisible(showLead);
        deep.setVisible(showLead);

        int padL = 9, padR = 8;
        int leadW = Math.round(78 * e);
        int sendW = Math.round(84 * e);
        int micD = 40;
        int rightW = micD + (sendW > 0 ? 6 + sendW : 0);
        int inputX = padL + leadW + (leadW > 0 ? 4 : 6);
        int inputW = w - padR - rightW - inputX;
        if (inputW < 40) inputW = 40;
        int inputH = 26;
        input.setBounds(inputX, (h - inputH) / 2, inputW, inputH);

        int by = (h - 38) / 2;
        attach.setBounds(padL, by, 38, 38);
        deep.setBounds(padL + 40, by, 38, 38);

        int micX = w - padR - rightW;
        mic.setBounds(micX, (h - micD) / 2, micD, micD);
        if (sendW > 0) send.setBounds(w - padR - sendW, (h - 36) / 2, sendW, 36);
        else           send.setBounds(w - padR, (h - 36) / 2, 0, 36);
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        float e = s.composerExpand;
        float tp = s.thinkPhase;
        // Kinetic morph: peripheral chrome cross-fades as the window compresses.
        float fade = 1f - s.morphProgress;
        if (fade <= 0.01f) { g2.dispose(); return; }
        if (fade < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));

        if (e > 0.05f || tp > 0.05f) {
            int gxA = (int)(e * 26 + tp * 22);
            if (gxA > 0) {
                Color gc = Theme.mix(new Color(168, 85, 247), new Color(90, 167, 255), tp);
                g2.setColor(new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), gxA));
                g2.setStroke(new BasicStroke(3f));
                g2.draw(new RoundRectangle2D.Float(-2.5f, -2.5f, w + 5, h + 5, h + 5, h + 5));
            }
        }

        RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h, h);
        int bgA = (int)(13 + 5 * e + 4 * tp);
        g2.setColor(new Color(255, 255, 255, bgA));
        g2.fill(rr);

        Color b1 = new Color(255, 255, 255, 24);
        Color b2 = new Color(168, 85, 247, 92);
        Color b3 = new Color(90, 167, 255, 108);
        Color bMid = Theme.mix(b1, b2, e);
        Color bCol = Theme.mix(bMid, b3, tp);
        g2.setColor(bCol);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(rr);

        g2.setColor(new Color(255, 255, 255, 18));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new Arc2D.Double(6, 1.5, w - 12, h - 3, 20, 140, Arc2D.OPEN));
        g2.dispose();
    }
}

/* =============================================================== */
/*  UI AUTOMATION -- exact on-screen element positions (Windows)   */
/* =============================================================== */

/**
 * Reads the real accessibility tree of the foreground window (PowerPoint, Word, Chrome, Settings,
 * desktop icons ...). Gives exact names and rectangles, so Proton does not have to guess pixels.
 * Java Swing apps (BlueJ) expose almost nothing; those fall back to vision automatically.
 */
final class UiaScanner {
    static final Set<String> INTERACTIVE = new HashSet<>(Arrays.asList(
        "Button", "MenuItem", "TabItem", "ListItem", "Hyperlink", "CheckBox", "RadioButton", "ComboBox",
        "SplitButton", "Edit", "Document", "DataItem", "TreeItem", "MenuBar", "Menu"));

    static final class El {
        int idx; String type = "", name = ""; Rectangle r = new Rectangle();
        int cx() { return r.x + r.width / 2; }
        int cy() { return r.y + r.height / 2; }
    }
    static final class Scan {
        String proc = "", title = ""; Rectangle win; List<El> els = new ArrayList<>();
        long at = System.currentTimeMillis();
        ProtonMemory.Fg toFg() {
            ProtonMemory.Fg f = new ProtonMemory.Fg();
            f.proc = proc; f.title = title; f.rect = win;
            return f;
        }
    }
    static final class Cand { El e; double score; }

    private static volatile Scan cache;
    private static final Map<String, Long> poorUntil = new HashMap<>();

    static boolean isPoor(String key) {
        if (key == null) return false;
        synchronized (poorUntil) {
            Long t = poorUntil.get(key);
            return t != null && t > System.currentTimeMillis();
        }
    }
    static void markPoor(String key) {
        if (key == null || key.isEmpty()) return;
        synchronized (poorUntil) { poorUntil.put(key, System.currentTimeMillis() + 600000L); }
    }
    static void invalidate() { cache = null; }

    static synchronized Scan scan(long maxAgeMs) {
        if (!Brain.isWin()) return null;
        Scan c = cache;
        if (c != null && System.currentTimeMillis() - c.at <= maxAgeMs) return c;
        Scan sc = run();
        cache = sc;
        return sc;
    }

    private static Scan run() {
        try {
            String script = String.join("\n",
                "$ErrorActionPreference='SilentlyContinue'",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
                "Add-Type -AssemblyName UIAutomationClient",
                "Add-Type -AssemblyName UIAutomationTypes",
                "Add-Type -AssemblyName System.Windows.Forms",
                "Add-Type @\"",
                "using System;",
                "using System.Runtime.InteropServices;",
                "public class UFG {",
                "  [DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();",
                "  [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
                "}",
                "\"@",
                "$AE = [System.Windows.Automation.AutomationElement]",
                "$scr = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds",
                "Write-Output (\"SCR|\" + $scr.Width + \"|\" + $scr.Height)",
                "$h = [UFG]::GetForegroundWindow()",
                "$procId = [uint32]0",
                "[void][UFG]::GetWindowThreadProcessId($h, [ref]$procId)",
                "$pn = (Get-Process -Id $procId).ProcessName",
                "$root = $AE::FromHandle($h)",
                "if ($root -eq $null) { exit }",
                "$rr = $root.Current.BoundingRectangle",
                "Write-Output (\"WIN|\" + $pn + \"|\" + ($root.Current.Name -replace '[\\r\\n|]',' ') + \"|\" + [int]$rr.Left + \"|\" + [int]$rr.Top + \"|\" + [int]$rr.Right + \"|\" + [int]$rr.Bottom)",
                "$cr = New-Object System.Windows.Automation.CacheRequest",
                "$cr.Add($AE::NameProperty)",
                "$cr.Add($AE::BoundingRectangleProperty)",
                "$cr.Add($AE::ControlTypeProperty)",
                "$cr.TreeScope = [System.Windows.Automation.TreeScope]::Element",
                "$cond = New-Object System.Windows.Automation.PropertyCondition($AE::IsOffscreenProperty, $false)",
                "$scope = $cr.Activate()",
                "$all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)",
                "$n = 0",
                "foreach ($e in $all) {",
                "  $c = $e.Cached",
                "  $nm = $c.Name",
                "  if ([string]::IsNullOrWhiteSpace($nm)) { continue }",
                "  $b = $c.BoundingRectangle",
                "  if ($b.IsEmpty -or $b.Width -lt 4 -or $b.Height -lt 4) { continue }",
                "  $t = $c.ControlType.ProgrammaticName.Replace('ControlType.','')",
                "  Write-Output (\"EL|\" + $t + \"|\" + ($nm -replace '[\\r\\n|]',' ') + \"|\" + [int]$b.Left + \"|\" + [int]$b.Top + \"|\" + [int]$b.Right + \"|\" + [int]$b.Bottom)",
                "  $n++",
                "  if ($n -ge 800) { break }",
                "}",
                "$scope.Dispose()");
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "-");
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) { os.write(script.getBytes(StandardCharsets.UTF_8)); }
            Thread wd = new Thread(() -> {
                try { if (!p.waitFor(18, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (Exception ignored) {}
            });
            wd.setDaemon(true);
            wd.start();
            String out = Brain.readStr(p.getInputStream());

            double scale = 1.0;
            Scan s = new Scan();
            boolean gotWin = false;
            for (String line : out.split("\\r?\\n")) {
                try {
                    if (line.startsWith("SCR|")) {
                        String[] a = line.split("\\|");
                        double pw = Double.parseDouble(a[1].trim());
                        if (pw > 100) {
                            Rectangle jb = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                            scale = jb.width / pw;
                            if (Math.abs(scale - 1.0) < 0.03) scale = 1.0;
                        }
                    } else if (line.startsWith("WIN|")) {
                        String[] a = line.split("\\|", 7);
                        s.proc = a[1].trim();
                        s.title = a[2].trim();
                        s.win = rect(a, 3, scale);
                        gotWin = true;
                    } else if (line.startsWith("EL|")) {
                        String[] a = line.split("\\|", 7);
                        El e = new El();
                        e.type = a[1].trim();
                        e.name = a[2].trim();
                        e.r = rect(a, 3, scale);
                        e.idx = s.els.size();
                        s.els.add(e);
                    }
                } catch (Throwable ignored) {}
            }
            if (!gotWin) return null;
            if (s.win == null || s.win.width < 120 || s.win.height < 40) s.win = null;
            if (s.els.size() < 8) markPoor(ProtonMemory.keyFor(s.proc, s.title));
            return s;
        } catch (Throwable t) { return null; }
    }

    private static Rectangle rect(String[] a, int at, double scale) {
        int l = (int) Math.round(Integer.parseInt(a[at].trim()) * scale);
        int t = (int) Math.round(Integer.parseInt(a[at + 1].trim()) * scale);
        int r = (int) Math.round(Integer.parseInt(a[at + 2].trim()) * scale);
        int b = (int) Math.round(Integer.parseInt(a[at + 3].trim()) * scale);
        return new Rectangle(l, t, Math.max(0, r - l), Math.max(0, b - t));
    }

    /* ------------------------------ matching ------------------------------ */

    static String norm(String s) {
        return (s == null ? "" : s).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    static Set<String> toks(String n) {
        Set<String> out = new HashSet<>();
        for (String t : n.split(" ")) if (t.length() >= 2 && !ProtonMemory.STOP.contains(t)) out.add(t);
        return out;
    }

    /** The words that actually name the target: the quoted text, else the description minus context words. */
    static String core(String desc) {
        if (desc == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("['\"]([^'\"]{2,60})['\"]").matcher(desc);
        if (m.find()) return norm(m.group(1));
        String d = desc.toLowerCase(Locale.ROOT);
        d = d.split("\\s+(?:in|on|at|inside|within|from|near|under|above|below|of)\\s+(?:the|a|an|my|this)\\b", 2)[0];
        d = d.replaceAll("^(?:the|a|an)\\s+", "");
        d = d.replaceAll("(?:\\s+(?:button|icon|tab|menu|item|option|link|field|tile|card|template|thumbnail|entry|checkbox|label|box))+$", "");
        return norm(d);
    }

    static List<Cand> rank(Scan s, String desc, List<int[]> avoid) {
        List<Cand> out = new ArrayList<>();
        String q = core(desc);
        if (s == null || q.isEmpty()) return out;
        Set<String> qt = toks(q);
        String dl = desc.toLowerCase(Locale.ROOT);
        for (El e : s.els) {
            if (avoid != null) {
                boolean skip = false;
                for (int[] a : avoid) if (Math.hypot(e.cx() - a[0], e.cy() - a[1]) < 24) { skip = true; break; }
                if (skip) continue;
            }
            String n = norm(e.name);
            if (n.isEmpty()) continue;
            double sc = 0;
            if (n.equals(q)) sc = 1.0;
            else if (q.length() >= 3 && n.contains(q)) sc = 0.70 + 0.25 * q.length() / (double) n.length();
            else if (n.length() >= 3 && q.contains(n)) sc = 0.62 + 0.25 * n.length() / (double) q.length();
            else {
                Set<String> nt = toks(n);
                if (!qt.isEmpty() && !nt.isEmpty()) {
                    Set<String> in = new HashSet<>(qt); in.retainAll(nt);
                    double a = in.size() / (double) qt.size(), b = in.size() / (double) nt.size();
                    if (a >= 0.5) sc = 0.30 + 0.35 * a * b + 0.15 * a;
                }
            }
            if (sc <= 0) continue;
            if (INTERACTIVE.contains(e.type)) sc += 0.03;
            if (dl.contains("menu") && e.type.equals("MenuItem")) sc += 0.03;
            if (dl.contains("tab") && e.type.equals("TabItem")) sc += 0.03;
            if (dl.contains("button") && e.type.equals("Button")) sc += 0.03;
            Cand c = new Cand(); c.e = e; c.score = Math.min(1.0, sc);
            out.add(c);
        }
        out.sort((x, y) -> Double.compare(y.score, x.score));
        return out;
    }

    /** A candidate that is clearly the one (strong name match, no look-alike elsewhere). */
    static Cand confident(List<Cand> c) {
        if (c.isEmpty()) return null;
        Cand t = c.get(0);
        if (t.score < 0.85) return null;
        for (int i = 1; i < c.size(); i++) {
            Cand o = c.get(i);
            if (o.score < t.score - 0.06) break;
            if (Math.hypot(o.e.cx() - t.e.cx(), o.e.cy() - t.e.cy()) > 60) return null;
        }
        return t;
    }
}

/* =============================================================== */
/*  USER FACTS -- what Proton remembers about YOU (~/.proton/user.json) */
/* =============================================================== */

final class UserFacts {
    static final int MAX = 40;

    private static File file() { return new File(ProtonMemory.DIR, "user.json"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        File f = file();
        Map<String, Object> m = f.isFile() ? ProtonMemory.readMap(f) : null;
        if (m == null) m = new LinkedHashMap<>();
        if (!(m.get("facts") instanceof List)) m.put("facts", new ArrayList<Object>());
        if (m.get("name") == null) m.put("name", "");
        return m;
    }

    private static void save(Map<String, Object> m) {
        try { ProtonMemory.ensureDir(); ProtonMemory.save(file(), m); } catch (Throwable ignored) {}
    }

    static synchronized String name() {
        if (!file().isFile()) return "";
        Object o = load().get("name");
        return o == null ? "" : o.toString().trim();
    }

    static synchronized void setName(String n) {
        Map<String, Object> m = load();
        m.put("name", n == null ? "" : n.trim());
        save(m);
    }

    @SuppressWarnings("unchecked")
    static synchronized List<String> facts() {
        List<String> out = new ArrayList<>();
        if (!file().isFile()) return out;
        for (Object o : (List<Object>) load().get("facts")) if (o != null) out.add(o.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    static synchronized void addFact(String f) {
        if (f == null || f.trim().isEmpty()) return;
        Map<String, Object> m = load();
        List<Object> l = (List<Object>) m.get("facts");
        String t = f.trim();
        for (Object o : l) if (o != null && o.toString().equalsIgnoreCase(t)) return;
        l.add(t);
        while (l.size() > MAX) l.remove(0);
        save(m);
    }

    /** "everything" wipes it all; otherwise removes facts that mention the phrase. Returns how many went. */
    @SuppressWarnings("unchecked")
    static synchronized int forget(String what) {
        if (!file().isFile()) return 0;
        Map<String, Object> m = load();
        List<Object> l = (List<Object>) m.get("facts");
        int before = l.size();
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT).trim();
        if (w.matches("everything|all|it all|everything about me|all of it")) {
            l.clear();
            m.put("name", "");
            before += 1;
        } else {
            l.removeIf(o -> o != null && o.toString().toLowerCase(Locale.ROOT).contains(w));
        }
        save(m);
        return before - l.size();
    }

    static synchronized String promptBlock() {
        if (!file().isFile()) return "";
        String n = name();
        List<String> f = facts();
        if (n.isEmpty() && f.isEmpty()) return "";
        StringBuilder b = new StringBuilder("- USER PROFILE (told to you by the user; first person here means the user)");
        if (!n.isEmpty()) b.append(": name is ").append(n);
        b.append('\n');
        for (String x : f) b.append("  * ").append(x.length() > 200 ? x.substring(0, 200) : x).append('\n');
        return b.toString();
    }
}

/* =============================================================== */
/*  APPROVAL WINDOW -- red / black "ATTENTION" gate for risky skills */
/* =============================================================== */

final class ApprovalDialog {
    private static final Map<String, ApprovalDialog> OPEN = new HashMap<>();
    private JDialog dlg;
    private javax.swing.Timer pulse;

    /** Opens (or re-focuses) the approval window for one skill. Safe from any thread. */
    static void show(final String name, final String title, final String desc, final List<String> reasons, final String code,
                     final Runnable onApprove, final Runnable onDeny) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                ApprovalDialog old = OPEN.get(name);
                if (old != null && old.dlg != null && old.dlg.isShowing()) { old.dlg.toFront(); return; }
                ApprovalDialog d = new ApprovalDialog();
                d.build(name, title, desc, reasons, code, onApprove, onDeny);
                OPEN.put(name, d);
            } catch (Throwable ignored) {}
        });
    }

    /** Closes the window (for example when the skill was approved by voice). */
    static void close(final String name) {
        SwingUtilities.invokeLater(() -> {
            ApprovalDialog d = OPEN.remove(name);
            if (d != null) d.dispose();
        });
    }

    private void dispose() {
        if (pulse != null) pulse.stop();
        if (dlg != null) dlg.dispose();
    }

    private void build(final String name, String title, String desc, List<String> reasons, String code,
                       final Runnable onApprove, final Runnable onDeny) {
        boolean translucent = false;
        try {
            translucent = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (Throwable ignored) {}
        dlg = new JDialog((Frame) null);
        dlg.setUndecorated(true);
        dlg.setAlwaysOnTop(true);
        dlg.setModalityType(Dialog.ModalityType.MODELESS);
        if (translucent) { try { dlg.setBackground(new Color(0, 0, 0, 0)); } catch (Throwable ignored) { translucent = false; } }
        final Panel p = new Panel(title, desc, reasons, code, translucent);
        dlg.setContentPane(p);
        dlg.pack();
        Rectangle sb = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        dlg.setLocation(sb.x + (sb.width - dlg.getWidth()) / 2, sb.y + (sb.height - dlg.getHeight()) / 3);
        final boolean[] fired = {false};
        p.onApprove = () -> { if (fired[0]) return; fired[0] = true; OPEN.remove(name); dispose(); if (onApprove != null) onApprove.run(); };
        p.onDeny    = () -> { if (fired[0]) return; fired[0] = true; OPEN.remove(name); dispose(); if (onDeny != null) onDeny.run(); };
        p.onClose   = () -> { OPEN.remove(name); dispose(); };      // dismissed: the skill stays pending
        p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        p.getActionMap().put("close", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { p.onClose.run(); } });
        pulse = new javax.swing.Timer(40, e -> { p.phase += 0.09f; p.repaint(); });
        dlg.setVisible(true);
        pulse.start();
        try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
        dlg.toFront();
    }

    @SuppressWarnings("serial")
    static final class Panel extends JComponent {
        static final Color BLACK = new Color(0x0A0A0A), RED = new Color(0xFF1E2D), DEEP = new Color(0x7A0A12), TXT = new Color(0xF2F2F2), DIM = new Color(0x9A9A9A);
        final String title, desc;
        final List<String> reasons;
        final List<String> codeLines = new ArrayList<>();
        final boolean translucent;
        volatile float phase = 0f;
        Runnable onApprove, onDeny, onClose;
        private final Rectangle approveR = new Rectangle(), denyR = new Rectangle(), closeR = new Rectangle();
        private int hover = 0;                 // 1 approve, 2 deny, 3 close
        private int W = 468, H = 300;
        private Point drag;

        Panel(String title, String desc, List<String> reasons, String code, boolean translucent) {
            this.title = title == null ? "Skill" : title;
            this.desc = desc == null ? "" : desc;
            this.reasons = reasons == null || reasons.isEmpty() ? Arrays.asList("Change files or settings on your PC") : reasons;
            this.translucent = translucent;
            for (String ln : (code == null ? "" : code).replace("\r", "").split("\n")) {
                String t = ln.trim();
                if (t.isEmpty() || t.startsWith("[Console]::OutputEncoding") || t.startsWith("$ProgressPreference")) continue;
                codeLines.add(ln.length() > 62 ? ln.substring(0, 61) + "\u2026" : ln);
                if (codeLines.size() >= 7) break;
            }
            int n = Math.min(5, this.reasons.size());
            H = 218 + n * 22 + (codeLines.isEmpty() ? 0 : 36 + codeLines.size() * 15) + 58;
            setPreferredSize(new Dimension(W, H));
            setOpaque(false);
            setFocusable(true);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int h = approveR.contains(e.getPoint()) ? 1 : denyR.contains(e.getPoint()) ? 2 : closeR.contains(e.getPoint()) ? 3 : 0;
                    if (h != hover) { hover = h; setCursor(Cursor.getPredefinedCursor(h == 0 ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR)); repaint(); }
                }
                @Override public void mouseExited(MouseEvent e) { hover = 0; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    Point pt = e.getPoint();
                    if (approveR.contains(pt)) { if (onApprove != null) onApprove.run(); }
                    else if (denyR.contains(pt)) { if (onDeny != null) onDeny.run(); }
                    else if (closeR.contains(pt)) { if (onClose != null) onClose.run(); }
                    else drag = e.getPoint();
                }
                @Override public void mouseReleased(MouseEvent e) { drag = null; }
                @Override public void mouseDragged(MouseEvent e) {
                    Window w = SwingUtilities.getWindowAncestor(Panel.this);
                    if (drag != null && w != null) {
                        Point o = w.getLocation();
                        w.setLocation(o.x + e.getX() - drag.x, o.y + e.getY() - drag.y);
                    }
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private static String fit(String t, FontMetrics fm, int maxW) {
            if (fm.stringWidth(t) <= maxW) return t;
            while (t.length() > 1 && fm.stringWidth(t + "\u2026") > maxW) t = t.substring(0, t.length() - 1);
            return t + "\u2026";
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            float pulse = 0.5f + 0.5f * (float) Math.sin(phase);
            if (!translucent) { g.setColor(BLACK); g.fillRect(0, 0, w, h); }
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(2, 2, w - 4, h - 4, 18, 18);
            g.setColor(BLACK);
            g.fill(shape);
            // red glow behind the border, breathing
            for (int i = 6; i >= 1; i--) {
                g.setColor(new Color(255, 30, 45, (int) ((12 + 22 * pulse) / i)));
                g.setStroke(new BasicStroke(i * 2.2f));
                g.draw(shape);
            }
            Shape oldClip = g.getClip();
            g.clip(shape);
            // hazard stripes
            g.setColor(new Color(0x140607));
            g.fillRect(0, 0, w, 12);
            g.setColor(RED);
            for (int x = -20; x < w + 20; x += 24) {
                Path2D.Float st = new Path2D.Float();
                st.moveTo(x, 12); st.lineTo(x + 12, 12); st.lineTo(x + 24, 0); st.lineTo(x + 12, 0); st.closePath();
                g.fill(st);
            }
            g.setClip(oldClip);
            g.setStroke(new BasicStroke(1.8f));
            g.setColor(new Color(255, 30, 45, (int) (170 + 85 * pulse)));
            g.draw(shape);

            // warning triangle
            int tx = 26, ty = 30;
            Path2D.Float tri = new Path2D.Float();
            tri.moveTo(tx + 24, ty); tri.lineTo(tx + 48, ty + 42); tri.lineTo(tx, ty + 42); tri.closePath();
            g.setColor(new Color(255, 30, 45, (int) (200 + 55 * pulse)));
            g.fill(tri);
            g.setColor(BLACK);
            g.setFont(Fnt.get(Font.BOLD, 28f));
            g.drawString("!", tx + 20, ty + 37);

            // headline
            g.setFont(Fnt.get(Font.BOLD, 28f));
            g.setColor(RED);
            g.drawString("ATTENTION", 92, 56);
            Font small = Fnt.get(Font.BOLD, 11f);
            Map<java.awt.font.TextAttribute, Object> at = new HashMap<>();
            at.put(java.awt.font.TextAttribute.TRACKING, 0.14);
            g.setFont(small.deriveFont(at));
            g.setColor(TXT);
            g.drawString("A NEW SKILL IS ASKING FOR PERMISSION", 93, 76);

            // close x
            closeR.setBounds(w - 40, 20, 24, 24);
            g.setColor(hover == 3 ? RED : DIM);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(w - 34, 26, w - 22, 38); g.drawLine(w - 22, 26, w - 34, 38);

            g.setColor(new Color(255, 30, 45, 90));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(24, 100, w - 24, 100);

            // skill name + description
            int y = 130;
            g.setFont(Fnt.get(Font.BOLD, 18f));
            g.setColor(Color.WHITE);
            g.drawString(fit(title, g.getFontMetrics(), w - 48), 26, y);
            g.setFont(Fnt.get(Font.PLAIN, 12f));
            g.setColor(DIM);
            y += 20;
            g.drawString(fit(desc, g.getFontMetrics(), w - 48), 26, y);

            // what it can do
            y += 32;
            g.setFont(small.deriveFont(at));
            g.setColor(RED);
            g.drawString("THIS SKILL CAN", 26, y);
            g.setFont(Fnt.get(Font.PLAIN, 13.5f));
            int n = Math.min(5, reasons.size());
            for (int i = 0; i < n; i++) {
                y += 22;
                g.setColor(RED);
                g.fillRect(28, y - 9, 8, 8);
                g.setColor(TXT);
                g.drawString(fit(reasons.get(i), g.getFontMetrics(), w - 80), 46, y);
            }

            // script preview (risky lines in red)
            if (!codeLines.isEmpty()) {
                y += 20;
                g.setFont(small.deriveFont(at));
                g.setColor(DIM);
                g.drawString("SCRIPT PREVIEW", 26, y);
                int bh = codeLines.size() * 15 + 12;
                RoundRectangle2D.Float box = new RoundRectangle2D.Float(24, y + 8, w - 48, bh, 8, 8);
                g.setColor(new Color(0x141414));
                g.fill(box);
                g.setColor(new Color(0x3A1014));
                g.draw(box);
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                int ly = y + 8 + 17;
                for (String ln : codeLines) {
                    boolean risky = SkillStore.CAUTION_RX.matcher(ln).find() || SkillStore.BLOCK_RX.matcher(ln).find();
                    g.setColor(risky ? new Color(0xFF5560) : new Color(0xB8B8B8));
                    g.drawString(fit(ln, g.getFontMetrics(), w - 70), 32, ly);
                    ly += 15;
                }
                y += 8 + bh;
            }

            // buttons
            int by = h - 66, bw = (w - 48 - 14) / 2, bh2 = 42;
            denyR.setBounds(24, by, bw, bh2);
            approveR.setBounds(24 + bw + 14, by, bw, bh2);
            RoundRectangle2D.Float dr = new RoundRectangle2D.Float(denyR.x, denyR.y, denyR.width, denyR.height, 10, 10);
            g.setColor(hover == 2 ? new Color(0x2A1013) : new Color(0x151515));
            g.fill(dr);
            g.setColor(hover == 2 ? RED : new Color(0x5A1A20));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(dr);
            g.setFont(Fnt.get(Font.BOLD, 14f));
            g.setColor(TXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString("DENY", denyR.x + (denyR.width - fm.stringWidth("DENY")) / 2, denyR.y + 26);

            RoundRectangle2D.Float ar = new RoundRectangle2D.Float(approveR.x, approveR.y, approveR.width, approveR.height, 10, 10);
            g.setPaint(new GradientPaint(0, approveR.y, hover == 1 ? new Color(0xFF4150) : RED, 0, approveR.y + approveR.height, DEEP));
            g.fill(ar);
            g.setColor(new Color(255, 255, 255, hover == 1 ? 120 : 40));
            g.draw(ar);
            g.setColor(Color.WHITE);
            g.drawString("APPROVE", approveR.x + (approveR.width - fm.stringWidth("APPROVE")) / 2, approveR.y + 26);

            g.setFont(Fnt.get(Font.PLAIN, 10.5f));
            g.setColor(DIM);
            String hint = "Or say \u201Capprove\u201D. Only approve skills you trust.";
            g.drawString(hint, (w - g.getFontMetrics().stringWidth(hint)) / 2, h - 12);
            g.dispose();
        }
    }
}

/* =============================================================== */
/*  SKILLS -- abilities Proton writes for itself (~/.proton/skills) */
/* =============================================================== */

final class SkillStore {
    static File dir() { return new File(ProtonMemory.DIR, "skills"); }

    static String str(Object o) { return o == null ? "" : o.toString().trim(); }
    static boolean truthy(Object o) { return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(str(o)); }

    static String clean(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (n.length() > 40) n = n.substring(0, 40).replaceAll("_+$", "");
        return n;
    }

    static String ext(String lang) { return lang.equals("powershell") ? ".ps1" : lang.equals("python") ? ".py" : ".sh"; }
    static File specFile(String name) { return new File(dir(), name + ".json"); }
    static File scriptFile(Map<String, Object> spec) { return new File(dir(), clean(str(spec.get("name"))) + ext(str(spec.get("language")))); }

    static synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        File[] fs = dir().isDirectory() ? dir().listFiles((d, n) -> n.endsWith(".json")) : null;
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) { Map<String, Object> m = ProtonMemory.readMap(f); if (m != null && !str(m.get("name")).isEmpty()) out.add(m); }
        return out;
    }

    static synchronized Map<String, Object> get(String name) {
        File f = specFile(clean(name));
        return f.isFile() ? ProtonMemory.readMap(f) : null;
    }

    static synchronized void save(Map<String, Object> spec, String code) throws IOException {
        ProtonMemory.ensureDir();
        if (!dir().isDirectory()) dir().mkdirs();
        String lang = str(spec.get("language"));
        String body = code;
        if (lang.equals("powershell"))
            body = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n$ProgressPreference = 'SilentlyContinue'\n" + code;
        byte[] raw = body.replace("\r\n", "\n").replace("\n", lang.equals("powershell") ? "\r\n" : "\n").getBytes(StandardCharsets.UTF_8);
        if (lang.equals("powershell")) {      // BOM so Windows PowerShell 5.1 reads UTF-8 correctly
            byte[] b = new byte[raw.length + 3];
            b[0] = (byte) 0xEF; b[1] = (byte) 0xBB; b[2] = (byte) 0xBF;
            System.arraycopy(raw, 0, b, 3, raw.length);
            raw = b;
        }
        java.nio.file.Files.write(scriptFile(spec).toPath(), raw);
        ProtonMemory.save(specFile(clean(str(spec.get("name")))), spec);
    }

    static synchronized String code(Map<String, Object> spec) {
        try {
            String t = new String(java.nio.file.Files.readAllBytes(scriptFile(spec).toPath()), StandardCharsets.UTF_8);
            if (t.startsWith("\uFEFF")) t = t.substring(1);
            return t.replace("\r\n", "\n");
        } catch (Throwable e) { return ""; }
    }

    static synchronized boolean delete(String name) {
        Map<String, Object> sp = get(name);
        if (sp == null) return false;
        scriptFile(sp).delete();
        return specFile(clean(name)).delete();
    }

    @SuppressWarnings("unchecked")
    static synchronized void update(String name, java.util.function.Consumer<Map<String, Object>> fn) {
        Map<String, Object> sp = get(name);
        if (sp == null) return;
        fn.accept(sp);
        try { ProtonMemory.save(specFile(clean(name)), sp); } catch (Throwable ignored) {}
    }

    static void touch(String name) {
        update(name, m -> {
            m.put("runs", (long) ProtonMemory.n(m.get("runs"), 0) + 1);
            m.put("lastRun", ProtonMemory.now());
        });
    }

    /** Best skill for a spoken sentence (trigger phrases), or null. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> match(String text) {
        String n = UiaScanner.norm(text);
        if (n.isEmpty()) return null;
        Set<String> tt = UiaScanner.toks(n);
        Map<String, Object> best = null; double bs = 0;
        for (Map<String, Object> sp : list()) {
            Object to = sp.get("triggers");
            if (!(to instanceof List)) continue;
            for (Object t : (List<Object>) to) {
                String tn = UiaScanner.norm(str(t));
                if (tn.length() < 4) continue;
                double sc = 0;
                if (n.contains(tn)) sc = 1.0 + 0.1 * tn.length() / n.length();
                else {
                    Set<String> kt = UiaScanner.toks(tn);
                    if (kt.size() >= 2) {
                        Set<String> in = new HashSet<>(kt); in.retainAll(tt);
                        double cont = in.size() / (double) kt.size();
                        if (cont >= 0.8 && tt.size() <= kt.size() + 4) sc = 0.85 * cont;
                    }
                }
                if (sc > bs) { bs = sc; best = sp; }
            }
        }
        return bs >= 0.8 ? best : null;
    }

    /** Words in the sentence that name an installed skill. */
    static Map<String, Object> byWords(String text) {
        Set<String> tt = UiaScanner.toks(UiaScanner.norm(text));
        Map<String, Object> best = null; double bs = 0;
        for (Map<String, Object> sp : list()) {
            Set<String> st = UiaScanner.toks(UiaScanner.norm(str(sp.get("name")).replace('_', ' ') + " " + str(sp.get("title"))));
            st.remove("skill");
            if (st.isEmpty()) continue;
            Set<String> in = new HashSet<>(st); in.retainAll(tt);
            double sc = in.size() / (double) st.size();
            if (sc > bs) { bs = sc; best = sp; }
        }
        return bs >= 0.5 ? best : null;
    }

    static String promptLine() {
        List<Map<String, Object>> l = list();
        if (l.isEmpty()) return "";
        StringBuilder b = new StringBuilder("- INSTALLED SKILLS (emit action skill with the name to run one):\n");
        int n = 0;
        for (Map<String, Object> sp : l) {
            if (n++ >= 12) break;
            String d = str(sp.get("description"));
            if (d.length() > 90) d = d.substring(0, 90);
            b.append("  * ").append(str(sp.get("name"))).append(" = ").append(str(sp.get("title"))).append(": ").append(d).append('\n');
        }
        return b.toString();
    }

    /* ----------------------------- safety ----------------------------- */

    static final java.util.regex.Pattern BLOCK_RX = java.util.regex.Pattern.compile(
        "(?i)format-volume|\\bformat\\s+[a-z]:|diskpart|bcdedit|cipher\\s+/w|vssadmin\\s+delete|reg(?:\\.exe)?\\s+delete|rd\\s+/s|rmdir\\s+/s|\\bdel\\s+/[fsq]"
      + "|rm\\s+-rf\\s+[/~]|mkfs|dd\\s+if=|invoke-expression|\\biex\\b|downloadstring|-encodedcommand|\\s-enc\\s|frombase64string|certutil[^\\n]*-urlcache"
      + "|set-mppreference|disable-windowsdefender|add-mppreference|mimikatz|lsass|net\\s+user[^\\n]*/add|new-localuser|add-localgroupmember"
      + "|sc(?:\\.exe)?\\s+(?:create|delete)|new-service|remove-item[^\\n]*(?:system32|\\\\windows\\\\|program files)|netsh\\s+advfirewall[^\\n]*(?:off|disable)"
      + "|keylog|getasynckeystate|set-executionpolicy|start-process[^\\n]*-verb\\s+runas|eval\\(|exec\\(|base64\\.b64decode");
    static final java.util.regex.Pattern CAUTION_RX = java.util.regex.Pattern.compile(
        "(?i)remove-item|\\bdel\\b|\\berase\\b|set-content|add-content|out-file|new-item|move-item|copy-item|rename-item|set-itemproperty|new-itemproperty"
      + "|stop-process|\\bkill\\b|taskkill|stop-service|restart-service|start-service|start-process|invoke-webrequest|invoke-restmethod|\\bcurl\\b|\\bwget\\b"
      + "|\\bnet\\s+|netsh|schtasks|reg(?:\\.exe)?\\s+add|\\bsetx\\b|>>|stop-computer|restart-computer|shutdown|send-mailmessage|clear-recyclebin|set-service"
      + "|disable-|enable-|install-|uninstall-|os\\.remove|shutil\\.rmtree|subprocess|os\\.system|requests\\.|urllib|socket\\.|rm\\s+-");

    /** What a skill that needs approval is able to do, in plain words (for the approval window). */
    static List<String> riskReasons(String code) {
        List<String> out = new ArrayList<>();
        String[][] cats = {
            {"Create, change or delete files on your PC", "(?i)remove-item|\\bdel\\b|\\berase\\b|set-content|add-content|out-file|new-item|move-item|copy-item|rename-item|>>|os\\.remove|shutil\\.rmtree|rm\\s+-"},
            {"Start or stop programs and services", "(?i)stop-process|\\bkill\\b|taskkill|stop-service|restart-service|start-service|start-process|subprocess|os\\.system"},
            {"Connect to the internet", "(?i)invoke-webrequest|invoke-restmethod|\\bcurl\\b|\\bwget\\b|requests\\.|urllib|socket\\."},
            {"Change system or network settings", "(?i)set-itemproperty|new-itemproperty|reg(?:\\.exe)?\\s+add|\\bsetx\\b|netsh|\\bnet\\s+|schtasks|set-service|disable-|enable-|install-|uninstall-"},
            {"Shut down or restart your PC", "(?i)stop-computer|restart-computer|shutdown"},
            {"Send e-mail", "(?i)send-mailmessage"},
            {"Empty the recycle bin", "(?i)clear-recyclebin"}
        };
        for (String[] c : cats) if (java.util.regex.Pattern.compile(c[1]).matcher(code).find()) out.add(c[0]);
        if (out.isEmpty()) out.add("Change files or settings on your PC");
        return out;
    }

    /** {level, reason}: level is "safe", "caution" (needs the user's approval) or "block". */
    static String[] assess(String code) {
        java.util.regex.Matcher b = BLOCK_RX.matcher(code);
        if (b.find()) return new String[]{"block", b.group().trim()};
        java.util.regex.Matcher c = CAUTION_RX.matcher(code);
        if (c.find()) return new String[]{"caution", c.group().trim()};
        return new String[]{"safe", ""};
    }
}

/* =============================================================== */
/*  APP MEMORY -- ~/.proton/<app>.json + click screenshots         */
/* =============================================================== */

/**
 * Remembers every GUI Proton has learned, the way a person does: the first time an app is
 * operated Proton records what it clicked (where, in window-relative terms), keeps a marked
 * screenshot and a short description of the layout; next time it checks the remembered
 * spot first. Files live in  <user home>/.proton  (created only if it does not exist yet).
 */
final class ProtonMemory {
    static final File DIR = new File(System.getProperty("user.home"), ".proton");
    static final Object LOCK = new Object();
    static final int MAX_ELEMENTS = 80, MAX_SHOTS = 40;
    static final Set<String> seenThisRun = new HashSet<>();
    static final Set<String> recalledShown = new HashSet<>();

    static final class Fg { String proc = "", title = ""; Rectangle rect; }
    static final class Learned { boolean newApp, newElement, moved; }

    /** Creates ~/.proton only when it is missing. */
    static File ensureDir() {
        if (!DIR.isDirectory()) DIR.mkdirs();
        return DIR;
    }

    static String now() { return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()); }

    static String slug(String s, int max) {
        String t = (s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (t.length() > max) t = t.substring(0, max).replaceAll("-+$", "");
        return t.isEmpty() ? "x" : t;
    }

    static double n(Object o, double d) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return o == null ? d : Double.parseDouble(o.toString()); } catch (Exception e) { return d; }
    }
    static double r4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /* ------------------------- foreground window ------------------------- */

    static Fg foreground() {
        if (!Brain.isWin()) return null;
        try {
            String script = String.join("\n",
                "$ErrorActionPreference='SilentlyContinue'",
                "Add-Type @\"",
                "using System;",
                "using System.Text;",
                "using System.Runtime.InteropServices;",
                "public class PFG {",
                "  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }",
                "  [DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();",
                "  [DllImport(\"user32.dll\")] public static extern bool GetWindowRect(IntPtr h, out RECT r);",
                "  [DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);",
                "  [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
                "}",
                "\"@",
                "$h = [PFG]::GetForegroundWindow()",
                "$r = New-Object PFG+RECT",
                "[void][PFG]::GetWindowRect($h, [ref]$r)",
                "$sb = New-Object System.Text.StringBuilder 512",
                "[void][PFG]::GetWindowText($h, $sb, 512)",
                "$procId = [uint32]0",
                "[void][PFG]::GetWindowThreadProcessId($h, [ref]$procId)",
                "$p = Get-Process -Id $procId",
                "Write-Output (\"PFG|\" + $p.ProcessName + \"|\" + $r.L + \"|\" + $r.T + \"|\" + $r.R + \"|\" + $r.B + \"|\" + $sb.ToString())");
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "-");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (OutputStream os = p.getOutputStream()) { os.write(script.getBytes(StandardCharsets.UTF_8)); }
            if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            String out = Brain.readStr(p.getInputStream());
            for (String line : out.split("\\r?\\n")) {
                if (!line.startsWith("PFG|")) continue;
                String[] a = line.split("\\|", 7);
                if (a.length < 6) continue;
                Fg f = new Fg();
                f.proc = a[1].trim();
                f.title = a.length >= 7 ? a[6].trim() : "";
                int l = Integer.parseInt(a[2].trim()), t = Integer.parseInt(a[3].trim());
                int r = Integer.parseInt(a[4].trim()), b = Integer.parseInt(a[5].trim());
                if (r - l >= 120 && b - t >= 40) {
                    Rectangle rc = new Rectangle(l, t, r - l, b - t);
                    Rectangle vb = new Rectangle(0, 0, 0, 0);
                    for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                        vb = vb.union(gd.getDefaultConfiguration().getBounds());
                    if (rc.width <= vb.width * 1.25 && rc.height <= vb.height * 1.25) f.rect = rc;
                }
                return f;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Stable app identity from the foreground window (process name; title for generic hosts). */
    static String keyFor(String proc, String title) {
        String p = proc == null ? "" : proc.toLowerCase(Locale.ROOT).trim();
        String t = title == null ? "" : title.trim();
        if (p.equals("explorer") && t.equals("Program Manager")) return "desktop";
        if (p.isEmpty() || p.equals("java") || p.equals("javaw") || p.equals("applicationframehost")) {
            String first = t.split("\\s[-\\u2013\\u2014]\\s|:")[0].trim();
            return first.isEmpty() ? "" : slug(first, 40);
        }
        return slug(p, 40);
    }

    /* ------------------------------ json io ------------------------------ */

    @SuppressWarnings("unchecked")
    static Map<String, Object> readMap(File f) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Object o = Brain.jparse(s);
            return (o instanceof Map) ? (Map<String, Object>) o : null;
        } catch (Throwable t) { return null; }
    }

    static void save(File f, Map<String, Object> m) throws IOException {
        StringBuilder b = new StringBuilder();
        w(b, m, 0);
        b.append('\n');
        File tmp = new File(f.getPath() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static void pad(StringBuilder b, int n) { for (int i = 0; i < n; i++) b.append("  "); }

    static void w(StringBuilder b, Object o, int ind) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(b, ind + 1);
                b.append(Brain.js(String.valueOf(e.getKey()))).append(": ");
                w(b, e.getValue(), ind + 1);
                b.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(b, ind); b.append('}');
        } else if (o instanceof List) {
            List<?> l = (List<?>) o;
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                pad(b, ind + 1);
                w(b, l.get(i), ind + 1);
                b.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            pad(b, ind); b.append(']');
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(String.format(Locale.ROOT, "%.4f", d));
        } else if (o instanceof Boolean) {
            b.append(o.toString());
        } else {
            b.append(Brain.js(o.toString()));
        }
    }

    /* ----------------------------- matching ------------------------------ */

    static final Set<String> STOP = new HashSet<>(Arrays.asList(
        "the", "a", "an", "of", "in", "on", "to", "at", "and", "or", "is", "that", "this", "with", "for", "it", "its"));

    static Set<String> tokens(String d) {
        Set<String> out = new HashSet<>();
        for (String t : (d == null ? "" : d).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
            if (t.length() >= 2 && !STOP.contains(t)) out.add(t);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> matchElem(List<Object> els, String desc) {
        if (els == null) return null;
        Set<String> a = tokens(desc);
        if (a.isEmpty()) return null;
        Map<String, Object> best = null; double bs = 0;
        for (Object o : els) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> e = (Map<String, Object>) o;
            Set<String> b = tokens(String.valueOf(e.get("desc")));
            if (b.isEmpty()) continue;
            Set<String> inter = new HashSet<>(a); inter.retainAll(b);
            Set<String> uni = new HashSet<>(a); uni.addAll(b);
            double sc = inter.size() / (double) uni.size();
            if (sc > bs) { bs = sc; best = e; }
        }
        return bs >= 0.6 ? best : null;
    }

    /** Remembered element for this app, or null. Never creates the folder. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> recall(String key, String desc) {
        File f = new File(DIR, key + ".json");
        if (!f.isFile()) return null;
        synchronized (LOCK) {
            Map<String, Object> m = readMap(f);
            if (m == null) return null;
            return matchElem((List<Object>) m.get("elements"), desc);
        }
    }

    static int recallScroll(String key, String desc) {
        Map<String, Object> e = recall(key, desc);
        return e == null ? 0 : (int) Math.round(n(e.get("scrollNotches"), 0));
    }

    static int[] expected(Map<String, Object> e, Rectangle win) {
        if (e == null || win == null) return null;
        double rx = n(e.get("relX"), -1), ry = n(e.get("relY"), -1);
        if (rx < 0 || ry < 0 || rx > 1.2 || ry > 1.2) return null;
        return new int[]{(int) Math.round(win.x + rx * win.width), (int) Math.round(win.y + ry * win.height)};
    }

    /** What Proton already knows about this app (layout + notes), for the vision prompt. */
    @SuppressWarnings("unchecked")
    static String hint(String key) {
        if (key == null) return null;
        File f = new File(DIR, key + ".json");
        if (!f.isFile()) return null;
        synchronized (LOCK) {
            Map<String, Object> m = readMap(f);
            if (m == null) return null;
            StringBuilder b = new StringBuilder();
            String lay = String.valueOf(m.get("layout") == null ? "" : m.get("layout")).trim();
            if (!lay.isEmpty()) b.append("Layout of this app: ").append(lay.length() > 450 ? lay.substring(0, 450) : lay).append(' ');
            Object no = m.get("notes");
            if (no instanceof List) {
                List<Object> l = (List<Object>) no;
                for (int i = Math.max(0, l.size() - 3); i < l.size(); i++) b.append("Note: ").append(l.get(i)).append(' ');
            }
            String r = b.toString().trim();
            return r.isEmpty() ? null : "Known from earlier use of this app: " + r;
        }
    }

    static boolean firstRecallThisRun(String key) {
        synchronized (LOCK) { return recalledShown.add(key); }
    }

    /* ------------------------------ updates ------------------------------ */

    static void setField(String key, String name, Object val) {
        File f = new File(DIR, key + ".json");
        if (!f.isFile()) return;
        synchronized (LOCK) {
            Map<String, Object> m = readMap(f);
            if (m == null) return;
            m.put(name, val);
            try { save(f, m); } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    static void addNote(String key, String text) {
        if (key == null || text == null || text.isEmpty()) return;
        File f = new File(DIR, key + ".json");
        if (!f.isFile()) return;
        synchronized (LOCK) {
            Map<String, Object> m = readMap(f);
            if (m == null) return;
            List<Object> l = (List<Object>) m.get("notes");
            if (l == null) { l = new ArrayList<>(); m.put("notes", l); }
            if (l.contains(text)) return;
            l.add(text);
            while (l.size() > 30) l.remove(0);
            try { save(f, m); } catch (Throwable ignored) {}
        }
    }

    /** Stores one operated GUI element (and its marked screenshot) in <key>.json. */
    @SuppressWarnings("unchecked")
    static Learned record(String key, String display, String title, String action, String desc,
                          Rectangle win, int sx, int sy, boolean recalled, int scrollNet, BufferedImage marked) {
        synchronized (LOCK) {
            ensureDir();
            Learned L = new Learned();
            File f = new File(DIR, key + ".json");
            Map<String, Object> m = f.isFile() ? readMap(f) : null;
            if (m == null) {
                if (f.isFile()) {
                    try { java.nio.file.Files.move(f.toPath(), new File(DIR, key + ".json.bad").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Throwable ignored) {}
                }
                m = new LinkedHashMap<>();
                m.put("app", key);
                m.put("displayName", display);
                m.put("firstSeen", now());
                m.put("lastUsed", now());
                m.put("visits", 0L);
                m.put("windowTitles", new ArrayList<Object>());
                m.put("layout", "");
                m.put("notes", new ArrayList<Object>());
                m.put("elements", new ArrayList<Object>());
                L.newApp = true;
            }
            m.put("displayName", display);
            if (seenThisRun.add(key)) m.put("visits", (long) n(m.get("visits"), 0) + 1);
            m.put("lastUsed", now());

            List<Object> titles = (List<Object>) m.get("windowTitles");
            if (titles == null) { titles = new ArrayList<>(); m.put("windowTitles", titles); }
            if (title != null && !title.isEmpty() && !titles.contains(title) && titles.size() < 8) titles.add(title);

            List<Object> els = (List<Object>) m.get("elements");
            if (els == null) { els = new ArrayList<>(); m.put("elements", els); }

            double rx = (sx - win.x) / (double) Math.max(1, win.width);
            double ry = (sy - win.y) / (double) Math.max(1, win.height);
            String shot = saveShot(key, desc, marked);
            Map<String, Object> el = matchElem(els, desc);
            if (el == null) {
                el = new LinkedHashMap<>();
                el.put("desc", desc);
                el.put("action", action);
                el.put("relX", r4(rx));
                el.put("relY", r4(ry));
                el.put("winW", (long) win.width);
                el.put("winH", (long) win.height);
                el.put("scrollNotches", (long) scrollNet);
                el.put("uses", 1L);
                el.put("recalls", recalled ? 1L : 0L);
                el.put("firstSeen", now());
                el.put("lastUsed", now());
                el.put("shot", shot);
                els.add(el);
                L.newElement = true;
                while (els.size() > MAX_ELEMENTS) {
                    int worst = 0; double wu = Double.MAX_VALUE;
                    for (int i = 0; i < els.size() - 1; i++) {
                        double u = n(((Map<String, Object>) els.get(i)).get("uses"), 1);
                        if (u < wu) { wu = u; worst = i; }
                    }
                    els.remove(worst);
                }
            } else {
                double dx = Math.abs(rx - n(el.get("relX"), rx)) * win.width;
                double dy = Math.abs(ry - n(el.get("relY"), ry)) * win.height;
                if (dx > 40 || dy > 40) L.moved = true;
                el.put("action", action);
                el.put("relX", r4(rx));
                el.put("relY", r4(ry));
                el.put("winW", (long) win.width);
                el.put("winH", (long) win.height);
                el.put("scrollNotches", (long) scrollNet);
                el.put("uses", (long) n(el.get("uses"), 0) + 1);
                if (recalled) el.put("recalls", (long) n(el.get("recalls"), 0) + 1);
                el.put("lastUsed", now());
                if (!shot.isEmpty()) el.put("shot", shot);
            }
            try { save(f, m); } catch (Throwable t) { return null; }
            return L;
        }
    }

    /* ----------------------------- screenshots ---------------------------- */

    static BufferedImage fit(BufferedImage src, int maxW) {
        int w = src.getWidth(), h = src.getHeight();
        int nw = Math.min(w, maxW);
        int nh = Math.max(1, (int) Math.round(h * (nw / (double) w)));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** Copy of the screenshot with a ring + crosshair + label on the spot Proton operated. */
    static BufferedImage annotate(BufferedImage src, int x, int y, String label) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(6f));
        g.setColor(new Color(0, 0, 0, 170));
        g.drawOval(x - 24, y - 24, 48, 48);
        g.setStroke(new BasicStroke(3f));
        g.setColor(Theme.PAL_A[4]);
        g.drawOval(x - 24, y - 24, 48, 48);
        g.drawLine(x - 36, y, x - 14, y); g.drawLine(x + 14, y, x + 36, y);
        g.drawLine(x, y - 36, x, y - 14); g.drawLine(x, y + 14, x, y + 36);
        String t = label == null ? "" : label.trim();
        if (t.length() > 70) t = t.substring(0, 69) + "\u2026";
        Font f = Fnt.get(Font.BOLD, 15f);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(t) + 20, th = fm.getHeight() + 8;
        int bx = Math.max(6, Math.min(src.getWidth() - tw - 6, x + 30));
        int by = Math.max(6, Math.min(src.getHeight() - th - 6, y - 34 - th));
        g.setColor(new Color(8, 8, 20, 215));
        g.fillRoundRect(bx, by, tw, th, 12, 12);
        g.setColor(Theme.PAL_A[4]);
        g.drawRoundRect(bx, by, tw, th, 12, 12);
        g.setColor(Color.WHITE);
        g.drawString(t, bx + 10, by + 4 + fm.getAscent());
        g.dispose();
        return out;
    }

    static String saveShot(String key, String desc, BufferedImage img) {
        if (img == null) return "";
        try {
            File d = new File(new File(DIR, "shots"), key);
            if (!d.isDirectory()) d.mkdirs();
            String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                        + "-" + slug(desc, 28) + ".jpg";
            File f = new File(d, name);
            ImageIO.write(fit(img, 1400), "jpg", f);
            File[] all = d.listFiles((dir, nm) -> nm.endsWith(".jpg"));
            if (all != null && all.length > MAX_SHOTS) {
                Arrays.sort(all, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < all.length - MAX_SHOTS; i++) all[i].delete();
            }
            return "shots/" + key + "/" + name;
        } catch (Throwable t) { return ""; }
    }
}

/* =============================================================== */
/*  MEMORY TOAST -- "memory updated" pop-up, stays a few seconds   */
/* =============================================================== */

final class MemoryToast {
    static final int HOLD_MS = 3400, FADE_MS = 380;
    private static JWindow win;
    private static ToastPanel panel;
    private static javax.swing.Timer tick;
    private static long shownAt;
    private static volatile boolean visible = false;
    private static boolean suspended = false;
    private static long suspendedAt = 0L;
    private static boolean translucent = false;

    /** kind 0 = learned / updated, kind 1 = recalled from memory. Safe from any thread. */
    static void show(final String head, final String sub, final int kind) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                if (win == null) {
                    win = new JWindow();
                    win.setAlwaysOnTop(true);
                    win.setFocusableWindowState(false);
                    try { win.setAutoRequestFocus(false); } catch (Throwable ignored) {}
                    try {
                        translucent = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
                        if (translucent) win.setBackground(new Color(0, 0, 0, 0));
                    } catch (Throwable ignored) { translucent = false; }
                    panel = new ToastPanel();
                    win.setContentPane(panel);
                }
                panel.set(head, sub, kind, translucent);
                Dimension d = panel.getPreferredSize();
                win.setSize(d);
                Rectangle sb = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                win.setLocation(sb.x + (sb.width - d.width) / 2, sb.y + 26);
                try { win.setOpacity(1f); } catch (Throwable ignored) {}
                shownAt = System.currentTimeMillis();
                suspended = false;
                visible = true;
                if (!win.isVisible()) win.setVisible(true);
                panel.repaint();
                if (tick == null) tick = new javax.swing.Timer(30, e -> step());
                if (!tick.isRunning()) tick.start();
            } catch (Throwable ignored) {}
        });
    }

    private static void step() {
        if (suspended) return;
        long el = System.currentTimeMillis() - shownAt;
        if (el >= HOLD_MS + FADE_MS) { hideImmediate(); return; }
        float a = el <= HOLD_MS ? 1f : 1f - (float) (el - HOLD_MS) / FADE_MS;
        try { if (win != null) win.setOpacity(Math.max(0.05f, Math.min(1f, a))); } catch (Throwable ignored) {}
        if (panel != null) {
            panel.progress = el >= HOLD_MS ? 0f : 1f - (float) el / HOLD_MS;
            panel.repaint();
        }
    }

    private static void hideImmediate() {
        if (tick != null) tick.stop();
        if (win != null) win.setVisible(false);
        visible = false;
        suspended = false;
    }

    /** Hides the toast just while a screenshot is taken so it never ends up in what vision sees. */
    static boolean suspend() {
        if (!visible) return false;
        try {
            Runnable r = () -> {
                if (win != null && win.isVisible()) {
                    win.setVisible(false);
                    suspended = true;
                    suspendedAt = System.currentTimeMillis();
                }
            };
            if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
            if (suspended) { Thread.sleep(110); return true; }
        } catch (Throwable ignored) {}
        return false;
    }

    static void resume() {
        SwingUtilities.invokeLater(() -> {
            if (suspended && win != null) {
                shownAt += System.currentTimeMillis() - suspendedAt;
                suspended = false;
                win.setVisible(true);
            }
        });
    }

    @SuppressWarnings("serial")
    static final class ToastPanel extends JComponent {
        String head = "", sub = "";
        int kind = 0;
        boolean translucent = false;
        volatile float progress = 1f;

        ToastPanel() { setOpaque(false); }

        void set(String h, String s, int k, boolean tr) {
            head = h == null ? "" : h; sub = s == null ? "" : s; kind = k; translucent = tr;
            progress = 1f;
        }

        private static String fit(String t, FontMetrics fm, int maxW) {
            if (fm.stringWidth(t) <= maxW) return t;
            while (t.length() > 1 && fm.stringWidth(t + "\u2026") > maxW) t = t.substring(0, t.length() - 1);
            return t + "\u2026";
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics hf = getFontMetrics(Fnt.get(Font.BOLD, 14f));
            FontMetrics sf = getFontMetrics(Fnt.get(Font.PLAIN, 12f));
            int w = Math.max(hf.stringWidth(head), Math.min(380, sf.stringWidth(sub)));
            return new Dimension(Math.max(250, 62 + w + 26), 62);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color c1 = kind == 2 ? new Color(0x22D3EE) : kind == 1 ? new Color(0x34D399) : Theme.PAL_A[2];
            Color c2 = kind == 2 ? new Color(0x2B6BFF) : kind == 1 ? Theme.PAL_B[0] : Theme.PAL_A[4];
            if (!translucent) { g.setColor(Theme.BG); g.fillRect(0, 0, w, h); }
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 22, 22);
            g.setPaint(new GradientPaint(0, 0, new Color(12, 11, 26, 244), 0, h, new Color(23, 20, 51, 244)));
            g.fill(shape);
            g.setPaint(new GradientPaint(0, 0, Theme.alpha(c1, 0.22), w, 0, Theme.alpha(c2, 0.05)));
            g.fill(shape);
            g.setStroke(new BasicStroke(1.4f));
            g.setPaint(new GradientPaint(0, 0, Theme.alpha(c1, 0.95), w, h, Theme.alpha(c2, 0.95)));
            g.draw(shape);

            // memory orb with three linked nodes
            int ox = 16, oy = (h - 32) / 2;
            g.setPaint(new GradientPaint(ox, oy, c1, ox + 32, oy + 32, c2));
            g.fillOval(ox, oy, 32, 32);
            g.setColor(new Color(255, 255, 255, 235));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (kind == 2) {   // lightning bolt = a new skill
                g.fillPolygon(new int[]{ox + 18, ox + 9, ox + 15, ox + 13, ox + 23, ox + 17},
                              new int[]{oy + 6, oy + 18, oy + 18, oy + 27, oy + 14, oy + 14}, 6);
            } else {
                int ax = ox + 9, ay = oy + 20, bx = ox + 16, by = oy + 9, cx = ox + 23, cy = oy + 20;
                g.drawLine(ax, ay, bx, by); g.drawLine(bx, by, cx, cy); g.drawLine(ax, ay, cx, cy);
                g.fillOval(ax - 3, ay - 3, 6, 6); g.fillOval(bx - 3, by - 3, 6, 6); g.fillOval(cx - 3, cy - 3, 6, 6);
            }

            int tx = 62, maxW = w - tx - 18;
            g.setFont(Fnt.get(Font.BOLD, 14f));
            g.setColor(Theme.TEXT);
            g.drawString(fit(head, g.getFontMetrics(), maxW), tx, 27);
            g.setFont(Fnt.get(Font.PLAIN, 12f));
            g.setColor(Theme.TEXT2);
            g.drawString(fit(sub, g.getFontMetrics(), maxW), tx, 45);

            // time-left line
            g.setColor(Theme.alpha(c2, 0.18));
            g.fillRoundRect(tx, h - 9, maxW, 2, 2, 2);
            g.setColor(Theme.alpha(c2, 0.9));
            g.fillRoundRect(tx, h - 9, Math.max(0, (int) (maxW * progress)), 2, 2, 2);
            g.dispose();
        }
    }
}

/* =============================================================== */
/*  BRAIN -- chat + STT + TTS + actions + vision + memory          */
/* =============================================================== */

class Brain {

    static final String BASE       = "https://api.groq.com/openai/v1";
    static final String CHAT_MODEL = "openai/gpt-oss-120b";
    static final String STT_MODEL  = "whisper-large-v3-turbo";
    static final String VISION_MODEL = "qwen/qwen3.8-27b";
    static final String TTS_MODEL  = "canopylabs/orpheus-v1-english";
    static final String TTS_VOICE  = "troy";   // male voice
    static final int    TTS_MAX_CHARS = 190;   // Orpheus accepts <= 200 chars per request

    static class ChatMsg { final String role, content; ChatMsg(String r, String c) { role = r; content = c; } }
    static class Step { String action = ""; String target = ""; long waitMs = 0; }
    static class Plan { String speech = ""; List<Step> steps = new ArrayList<>(); }

    final String key;          // Groq key (chat fallback, STT, TTS, vision fallback)
    final String nvidiaKey;    // NVIDIA NIM key (may be empty)
    final boolean nvidia;      // true when NVIDIA + Groq was chosen
    final List<ChatMsg> history = new ArrayList<>();
    static final int HISTORY_MAX = 20;
    final Object planLock = new Object();

    volatile String sLastApp = "", sLastWindow = "", sLastTyped = "", sLastTopic = "";

    // Phase 2 Module C: fast foreground-window resolver with 500ms TTL.
    private static volatile String fgCacheTitle = "";
    private static volatile long fgCacheTime = 0L;
    private static final long FG_TTL_MS = 500L;

    /** Fast active-window title resolver (Windows User32 via PowerShell). Cached 500ms. */
    static String getForegroundTitle() {
        long now = System.currentTimeMillis();
        if (now - fgCacheTime < FG_TTL_MS) return fgCacheTitle;
        String title = "";
        if (isWin()) {
            try {
                String script =
                    "$ErrorActionPreference='SilentlyContinue'\n" +
                    "Add-Type @\"\n" +
                    "using System;\nusing System.Runtime.InteropServices;\n" +
                    "public class FGWin { [DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow(); }\n" +
                    "\"@\n" +
                    "$hwnd = [FGWin]::GetForegroundWindow()\n" +
                    "$p = Get-Process | Where-Object { $_.MainWindowHandle -eq $hwnd } | Select-Object -First 1\n" +
                    "if ($p) { $p.MainWindowTitle }";
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile",
                        "-ExecutionPolicy", "Bypass", "-Command", "-");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (OutputStream os = p.getOutputStream()) {
                    os.write(script.getBytes(StandardCharsets.UTF_8));
                }
                boolean done = p.waitFor(2, TimeUnit.SECONDS);
                String out = "";
                if (done) {
                    try (InputStream in = p.getInputStream()) {
                        out = readStr(in);
                    }
                } else {
                    p.destroyForcibly();
                }
                if (out != null) title = out.trim();
            } catch (Throwable ignored) {
                title = "";
            }
        }
        fgCacheTitle = title == null ? "" : title;
        fgCacheTime = System.currentTimeMillis();
        return fgCacheTitle;
    }

    String ttsModel, ttsVoice;
    boolean ttsDisabled, ttsLogged;

    // speech playback control (barge-in)
    volatile boolean speechCancelled = false;
    volatile Clip activeClip;
    volatile SourceDataLine activeLine;
    volatile Process speechProc;

    List<String> visionCands;
    Set<String> visionDead = new LinkedHashSet<>();
    volatile boolean nvidiaVisionDead = false;
    String visionPicked;

    /* ---- Corner Mini-Orb Companion HUD hooks (wired by ProtonFrame) ---- */
    interface CompanionHooks {
        boolean shouldAccompany(int stepCount);
        void enter(int totalSteps);
        void onStep(int index, int total, String action, String target);
        void onDone();
        void exit(boolean completed);
        boolean isAborted();
    }
    static class CompanionAbort extends Exception {
        private static final long serialVersionUID = 1L;
        CompanionAbort() { super("aborted"); }
    }
    volatile CompanionHooks companion;

    /* ---- Live "thinking" stream hooks (wired by ProtonFrame) ---- */
    interface ThoughtHooks {
        void begin();
        void thought(String delta);
        void end();
    }
    volatile ThoughtHooks thoughtHooks;

    /* ---- PROTON's own coding IDE (read-only for the user) ---- */
    volatile ProtonIDE codeIde;
    volatile String ideFile = "";
    volatile String ideLang = "";
    volatile String ideCode = "";
    volatile boolean ideActive = false;

    // Dock timing: keep the mini-orb docked long enough for the target app to finish.
    static final long MIN_DOCK_MS    = 5000;  // minimum time docked for an LLM action plan
    static final long DONE_LINGER_MS = 1800;  // extra hold after the last step finishes
    volatile boolean dockActive   = false;    // companion currently docked (either path)
    volatile boolean reflexDocked = false;    // docked on demand by a reflex action
    volatile long    reflexLinger = 0L;       // how long to stay docked after a reflex action
    volatile boolean skipLinger   = false;    // vision path: expand right away once the answer is ready
    volatile String  lastScreenDesc = "";     // last thing the vision model saw (for follow-ups)
    volatile long    lastScreenTime = 0L;

    private static boolean needsDock(String a) {
        switch (a) {
            case "openapp": case "openurl": case "search": case "youtube": case "type":
            case "press": case "hotkey": case "closeapp": case "focus": case "click":
            case "doubleclick": case "rightclick": case "scroll": case "findclick":
            case "findmove": case "move": case "moverel":
            case "finddoubleclick": case "bluej": case "gui":
                return true;
            default: return false;
        }
    }

    /** Dock to the corner orb before ANY desktop-touching action (even a tiny type),
     *  so keyboard focus stays with the app being driven. */
    private void ensureDocked(String action, String target) {
        CompanionHooks h = companion;
        if (h == null || dockActive) return;
        dockActive = true;
        reflexDocked = true;
        try {
            h.enter(1);
            try { h.onStep(0, 1, action, target); } catch (Throwable ignored) {}
            Thread.sleep(320); // let the glide settle so focus is released to the target app
        } catch (Throwable ignored) {}
        long linger;
        switch (action == null ? "" : action) {
            case "openurl": case "search": case "youtube": linger = 4500; break;
            case "openapp": linger = 3000; break;
            case "type": linger = 1800; break;
            case "closeapp": linger = 1200; break;
            default: linger = 1500;
        }
        if (linger > reflexLinger) reflexLinger = linger;
    }

    /** Holds the dock after a reflex action, then glides back to the full window. */
    private void finishReflexDock() {
        if (!reflexDocked) return;
        CompanionHooks h = companion;
        boolean aborted = false;
        try {
            if (h != null) { try { h.onDone(); } catch (Throwable ignored) {} }
            sleepAbortable(skipLinger ? 250 : Math.max(reflexLinger, DONE_LINGER_MS));
        } catch (CompanionAbort a) {
            aborted = true;
        } finally {
            skipLinger = false;
            reflexDocked = false;
            dockActive = false;
            reflexLinger = 0L;
            try { if (h != null) h.exit(!aborted); } catch (Throwable ignored) {}
            try { Thread.sleep(320); } catch (InterruptedException ignored) {}
        }
    }

    /** Abort-aware sleep: waits in slices so a mini-orb click aborts promptly. */
    private void sleepAbortable(long ms) throws CompanionAbort {
        if (ms <= 0) return;
        long deadline = System.currentTimeMillis() + Math.min(ms, 60000);
        long remaining = Math.min(ms, 60000);
        while (remaining > 0) {
            CompanionHooks h = companion;
            if (h != null && h.isAborted()) throw new CompanionAbort();
            long slice = Math.min(100, remaining);
            try { Thread.sleep(slice); } catch (InterruptedException ie) { throw new CompanionAbort(); }
            remaining = deadline - System.currentTimeMillis();
        }
    }

    Brain(String gk, String nk, boolean nv) {
        this.key = (gk == null) ? "" : gk.trim();
        this.nvidiaKey = (nk == null) ? "" : nk.trim();
        this.nvidia = nv && !this.nvidiaKey.isEmpty();
        warmUp(); startSkillRuntime();
    }
    boolean hasKey() { return !key.isEmpty() && !key.startsWith("PASTE"); }
    boolean hasNvidia() { return nvidia; }
    void log(String m) { System.out.println("[proton] " + m); }

    /* ============================================================
     * NVIDIA NIM (OpenAI-compatible) -- Nemotron reasoning engine
     * ============================================================ */

    HttpURLConnection nconn(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                URI.create(main.NVIDIA_BASE + path).toURL().openConnection();
        c.setRequestProperty("Authorization", "Bearer " + nvidiaKey);
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        return c;
    }

    String nvidiaPost(String path, String json) throws Exception {
        HttpURLConnection c = nconn(path);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        OutputStream o = c.getOutputStream();
        try { o.write(json.getBytes(StandardCharsets.UTF_8)); } finally { o.close(); }
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String body = readStr(in);
        if (code >= 400) throw new Exception("nvidia " + code + ": " + shortStr(body));
        return body;
    }

    static boolean isModelMissing(Throwable t) {
        String m = String.valueOf(t == null ? "" : t.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("404") || m.contains("model_not_found") || m.contains("does not exist")
                || m.contains("not found") || m.contains("unknown model");
    }

    /** Non-streaming Nemotron call with automatic fallback to the smaller model. */
    String nvidiaComplete(String model, String msgsJson, int maxTokens, double temp) throws Exception {
        String use = model;
        for (int attempt = 0; attempt < 2; attempt++) {
            String body = "{\"model\":" + js(use) + ",\"messages\":[" + msgsJson + "],"
                        + "\"max_tokens\":" + maxTokens + ",\"temperature\":" + temp + "}";
            try {
                return nvidiaPost("/chat/completions", body);
            } catch (Exception ex) {
                if (attempt == 0 && !main.NVIDIA_FALLBACK.equals(use) && isModelMissing(ex)) {
                    log("nvidia model " + use + " unavailable, trying " + main.NVIDIA_FALLBACK);
                    use = main.NVIDIA_FALLBACK;
                    continue;
                }
                throw ex;
            }
        }
        throw new Exception("nvidia call failed");
    }

    /** Streaming Nemotron call: reasoning deltas go to the thought window, content is returned. */
    String nvidiaStream(String model, String msgsJson, int maxTokens, double temp) throws Exception {
        ThoughtHooks hooks = thoughtHooks;
        if (hooks != null) try { hooks.begin(); } catch (Throwable ignored) {}
        try {
            String use = model;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return nvidiaStreamOnce(use, msgsJson, maxTokens, temp, hooks);
                } catch (Exception ex) {
                    if (attempt == 0 && !main.NVIDIA_FALLBACK.equals(use) && isModelMissing(ex)) {
                        log("nvidia stream model " + use + " unavailable, trying " + main.NVIDIA_FALLBACK);
                        use = main.NVIDIA_FALLBACK;
                        continue;
                    }
                    throw ex;
                }
            }
            throw new Exception("nvidia stream failed");
        } finally {
            if (hooks != null) try { hooks.end(); } catch (Throwable ignored) {}
        }
    }

    private String nvidiaStreamOnce(String model, String msgsJson, int maxTokens, double temp,
                                    ThoughtHooks hooks) throws Exception {
        HttpURLConnection c = nconn("/chat/completions");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        String body = "{\"model\":" + js(model) + ",\"messages\":[" + msgsJson + "],"
                    + "\"stream\":true,\"max_tokens\":" + maxTokens + ",\"temperature\":" + temp + "}";
        OutputStream o = c.getOutputStream();
        try { o.write(body.getBytes(StandardCharsets.UTF_8)); } finally { o.close(); }
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new Exception("nvidia " + code + ": " + shortStr(readStr(c.getErrorStream())));
        }
        StringBuilder answer = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                try {
                    Object root = jparse(data);
                    Object r = dig(root, "choices", 0, "delta", "reasoning_content");
                    if (r == null) r = dig(root, "choices", 0, "delta", "reasoning");
                    if (r != null && !r.toString().isEmpty() && hooks != null) {
                        try { hooks.thought(r.toString()); } catch (Throwable ignored) {}
                    }
                    Object ct = dig(root, "choices", 0, "delta", "content");
                    if (ct != null) answer.append(ct.toString());
                } catch (Throwable ignored) {}
            }
        }
        return answer.toString();
    }

    static final String NEMOTRON_PERSONA =
        "You are PROTON, a JARVIS-style AI companion living on the user's PC. Your reply is SPOKEN ALOUD.\n" +
        "don't use emoji and markdown say as a person says in normal except in coding you can use numbers emoji and special character as you want\n" +
        "you are an excellent agentic coder and especially built for agentic works\n" +
        "for coding, complex question think as much as you can , give your full effort and thinking to max level to reach user's satisfaction and desire\n" +
        "Never say 'as an AI' or 'I'm just a language model\n" +
        "If the user is really asking you to DO something on the computer (open, click, type, write into an app, play, close, change a setting, search the web), reply with exactly [[ACTION]] and nothing else.\n" +
        "- Answer in plain spoken English. For normal conversation keep it to 1 to 4 sentences; for explanations, coding and agentic work, take the space you need.\n" +
        "- Be correct and concrete. Never invent facts, quotes or statistics. When unsure, say so briefly.\n";

    static final java.util.regex.Pattern COMPLEX_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(code|coding|program|programming|script|function|class|method|debug|compile|refactor|algorithm|"
      + "complexity|architecture|implement|api|database|sql|regex|optimi[sz]e|prove|derive|analy[sz]e|"
      + "step by step|agent|agentic|automate|automation|workflow|research|compare|trade-?offs?|pros and cons|"
      + "math|physics|essay|report|thesis|strategy|vision|screen|screenshot|look at|see my|read my)\\b");

    /** True when the request deserves the full Nemotron reasoning model. */
    boolean isComplexTask(String t) {
        if (!nvidia || t == null) return false;
        String n = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (n.length() < 3) return false;
        // "write a poem/script/email" is content creation, not a desktop action.
        boolean contentWrite = n.matches("^(?:please )?(?:hey |ok |okay )?(?:proton )?write\\b.*");
        if (!contentWrite && DESKTOP_CMD_RX.matcher(n).find()) return true;
        if (HARD_RX.matcher(n).find()) return true;
        if (COMPLEX_RX.matcher(n).find()) return true;
        return n.split("\\s+").length >= 30;
    }

    /* ============================================================
     * PROTON IDE: write code in PROTON's own window, then mirror it
     * into the editor the user already has open (VS Code, PyCharm...).
     * ============================================================ */

    static final java.util.regex.Pattern CODE_EXT_RX = java.util.regex.Pattern.compile(
        "(?i)\\b[\\w\\-]+\\.(?:html?|css|scss|sass|js|jsx|mjs|cjs|ts|tsx|py|java|c|h|cpp|hpp|cs|go|rs|rb|php|json|ya?ml|xml|sql|sh|bash|ps1|md|txt)\\b");

    static final java.util.regex.Pattern CODE_TASK_RX = java.util.regex.Pattern.compile(
        "(?i)(?:^|[.!?]\\s*)"
      + "(?:please\\s+|hey\\s+|ok\\s+|okay\\s+|proton[,\\s]+)?"
      + "(?:can you\\s+|could you\\s+|would you\\s+|i want you to\\s+|i need you to\\s+|help me\\s+)?"
      + "(?:write|create|make|build|generate|develop|design|code|implement|program|rewrite|refactor|fix|debug|edit|modify|update|improve|style|restyle)"
      + "\\b[^.!?]{0,140}?"
      + "\\b(?:code|program|script|app|application|website|web\\s?page|webpage|html|css|javascript|js|python|java|react|node\\.?js|api|function|class|component|game|form|calculator|page|interface|ui|dashboard|layout|frontend|backend|stylesheet)\\b");

    static final java.util.regex.Pattern CODE_EDIT_RX = java.util.regex.Pattern.compile(
        "(?i)^(?:please\\s+|hey\\s+|ok\\s+|okay\\s+|proton[,\\s]+)?"
      + "(?:now\\s+|also\\s+|then\\s+|next\\s+|and\\s+)?"
      + "(?:add|change|update|remove|delete|replace|make|set|move|rename|fix|style|restyle|tweak|adjust|edit|modify|improve|refactor|rewrite|increase|decrease|put|insert|append|prepend|turn|convert|center|align|hide|show|animate|resize|round|color|colou?r)\\b");

    static final java.util.regex.Pattern CONTENT_ONLY_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(poem|poetry|essay|story|email|letter|song|lyrics|blog|article|summary|report|thesis|joke|speech|resume|cv|cover letter)\\b");

    static final java.util.regex.Pattern APP_ONLY_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(vs ?code|visual studio code|vscodium|pycharm|intellij|eclipse|notepad\\+\\+|sublime|atom|cursor|webstorm)\\b");

    static final java.util.regex.Pattern CODE_ARTIFACT_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(html|css|javascript|js|python|java|function|class|component|website|web ?page|program|script|app|application|page|form|game|calculator|dashboard|stylesheet|react|api)\\b");

    static final java.util.regex.Pattern WRITE_CODE_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(?:write|create|generate|build|make|produce|develop)\\s+(?:me\\s+)?(?:some\\s+|a\\s+|the\\s+)?(?:code|program|script|app|website|page|function|class|component)\\b");

    static final java.util.regex.Pattern SYS_ACTION_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(volume|mute|unmute|brightness|screenshot|screen shot|lock|shutdown|shut down|restart|reboot|sleep|hibernate|wifi|wi-fi|bluetooth|battery|clipboard)\\b");

    boolean isCodeWriteTask(String t) {
        if (t == null) return false;
        String n = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (n.length() < 4) return false;
        boolean ext = CODE_EXT_RX.matcher(n).find();
        boolean task = CODE_TASK_RX.matcher(n).find();
        if (!task && !ext) return false;
        if (CONTENT_ONLY_RX.matcher(n).find() && !ext
                && !CODE_ARTIFACT_RX.matcher(n).find()) return false;
        if (!ideActive && !ext
                && n.matches("^(?:please )?(?:open|launch|start|focus|close|quit|type|press|click|search|play)\\b.*"))
            return false;
        // "make VS Code dark" is a settings task, not a coding task.
        if (!ideActive && !ext && APP_ONLY_RX.matcher(n).find()
                && !CODE_ARTIFACT_RX.matcher(n).find()
                && !WRITE_CODE_RX.matcher(n).find())
            return false;
        return true;
    }

    boolean isCodeFollowUp(String t) {
        if (!ideActive || t == null) return false;
        String n = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (n.length() < 3) return false;
        if (CONTENT_ONLY_RX.matcher(n).find()
                && !CODE_EXT_RX.matcher(n).find()
                && !CODE_ARTIFACT_RX.matcher(n).find()) return false;
        if (n.matches("^(?:please )?(?:open|launch|start|focus|close|quit)\\b.*")) return false;
        if (SYS_ACTION_RX.matcher(n).find() && !CODE_ARTIFACT_RX.matcher(n).find()) return false;
        return CODE_EDIT_RX.matcher(n).find();
    }

    ProtonIDE ide() {
        ProtonIDE w = codeIde;
        if (w != null) return w;
        synchronized (this) {
            if (codeIde == null) {
                final ProtonIDE[] box = new ProtonIDE[1];
                try {
                    Runnable r = () -> box[0] = new ProtonIDE();
                    if (SwingUtilities.isEventDispatchThread()) r.run();
                    else SwingUtilities.invokeAndWait(r);
                } catch (Throwable t) {
                    log("ide window failed: " + shortStr(String.valueOf(t.getMessage())));
                    return null;
                }
                codeIde = box[0];
            }
            return codeIde;
        }
    }

    static final String IDE_SYSTEM =
        "You are PROTON IDE, the coding engine inside PROTON, a JARVIS-style assistant on the user's PC.\n" +
        "You write complete, runnable, production-quality code. Think deeply, then answer in EXACTLY this format:\n" +
        "FILENAME: <single file name with extension, no folders>\n" +
        "LANGUAGE: <html|css|javascript|typescript|python|java|c|cpp|csharp|json|sql|bash|powershell|text>\n" +
        "===CODE===\n" +
        "<the complete file>\n" +
        "===END===\n" +
        "RULES\n" +
        "- Output nothing before FILENAME and nothing after ===END===.\n" +
        "- Return the COMPLETE file every time. Never return a diff, a fragment or 'unchanged' placeholders.\n" +
        "- No markdown fences inside the answer.\n" +
        "- One file per answer. Use inline CSS/JS inside the HTML file when one file is enough.\n" +
        "- Keep all existing behaviour and change only what the user asked for.\n" +
        "- The code is shown in PROTON's read-only IDE and then copied into the user's editor.\n";

    String ideMessages(String request, boolean editing) {
        String sys = IDE_SYSTEM + "\nCONTEXT\n- NOW: " + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)) + "\n";
        StringBuilder b = new StringBuilder();
        b.append("{\"role\":\"system\",\"content\":").append(js(sys)).append("}");
        if (editing) {
            String u = "FILE: " + ideFile + "\nLANGUAGE: " + ideLang
                    + "\nCURRENT CODE:\n===CODE===\n" + ideCode + "\n===END===\n\n"
                    + "REQUEST: " + request + "\n\n"
                    + "Edit the current file to satisfy the request. Return the complete updated file in the required format.";
            b.append(",{\"role\":\"user\",\"content\":").append(js(u)).append("}");
        } else {
            String u = "REQUEST: " + request + "\n\nWrite the complete file in the required format.";
            b.append(",{\"role\":\"user\",\"content\":").append(js(u)).append("}");
        }
        return b.toString();
    }

    String tryCodeTask(String userText) {
        boolean editing = ideActive && isCodeFollowUp(userText);
        boolean fresh = !editing && isCodeWriteTask(userText);
        if (!editing && !fresh) return null;

        ProtonIDE ide = ide();
        if (fresh) {
            ideFile = guessFileName(userText);
            ideLang = guessLang(ideFile, userText);
            ideCode = "";
        }
        if (ide != null) ide.begin(ideFile, ideLang, editing);
        startEarlySpeech(editing
                ? pick("On it. Editing the code now.", "Right away. Updating the file.", "Sure. Changing the code now.")
                : pick("On it. Opening the PROTON IDE.", "Right away. I'll write that in my IDE.", "Certainly. Opening my code editor."));

        try {
            String msgs = ideMessages(userText, editing);
            String raw = null;
            if (nvidia) {
                try {
                    raw = nvidiaStream(main.NVIDIA_MODEL, msgs, 9000, 0.25);
                } catch (Throwable t) {
                    log("ide nemotron failed, falling back to groq: " + shortStr(String.valueOf(t.getMessage())));
                }
            }
            if (raw == null || raw.trim().isEmpty()) {
                String body = postFast(msgs, "high", 7000, 0.25);
                Object content = dig(jparse(body), "choices", 0, "message", "content");
                if (content != null) raw = content.toString();
            }
            if (raw == null || raw.trim().isEmpty())
                return "I couldn't reach the coding model. Please try again.";

            CodeFile cf = parseCodeFile(raw, userText, editing);
            if (cf == null || cf.code.trim().isEmpty())
                return "The model didn't return usable code. Try asking again, a little more specifically.";
            sanitize(cf);
            if (ide != null) ide.begin(cf.name, cf.lang, true);

            String old = ideCode;
            if (ide != null) {
                if (editing && !old.isEmpty()) ide.applyDiff(old, cf.code);
                else ide.writeCode(cf.code, true);
            }

            ideCode = cf.code;
            ideFile = cf.name;
            ideLang = cf.lang;
            ideActive = true;

            int lines = ProtonIDE.splitLines(cf.code).length;
            String saved = saveProjectFile(cf.code, cf.name);
            String editor = syncCodeToUserEditor(cf.code, cf.name, cf.lang, !editing);
            String where = "clipboard".equals(editor)
                    ? (saved != null ? "your clipboard and saved it as " + cf.name : "your clipboard")
                    : (editor == null ? "your editor" : editor);
            String reply = (editing ? "Updated " : "Done. I wrote ") + cf.name
                    + " in the PROTON IDE" + (editing ? "" : " with " + lines + " lines")
                    + " and copied it into " + where + ".";
            if (ide != null) ide.status((editing ? "UPDATED \u00B7 " : "DONE \u00B7 ") + shortStr(where), ProtonIDE.OK);
            log("ide task complete: " + cf.name + " -> " + where);
            return reply;
        } catch (Throwable t) {
            log("ide task failed: " + shortStr(String.valueOf(t.getMessage())));
            if (ide != null) ide.status("FAILED", ProtonIDE.ERR);
            return "Sorry, I couldn't finish that code. " + shortStr(String.valueOf(t.getMessage()));
        }
    }

    /* ---------------- code file parsing ---------------- */

    static final class CodeFile {
        String name = "", lang = "", code = "";
    }

    CodeFile parseCodeFile(String raw, String request, boolean editing) {
        String txt = ProtonIDE.norm(raw).trim();
        txt = txt.replaceAll("(?s)<think>.*?</think>", "").trim();
        CodeFile cf = new CodeFile();

        java.util.regex.Matcher fm = java.util.regex.Pattern.compile(
                "(?im)^\\s*FILENAME\\s*:\\s*([^\\n]+)\\s*$").matcher(txt);
        if (fm.find()) cf.name = cleanName(fm.group(1));
        java.util.regex.Matcher lm = java.util.regex.Pattern.compile(
                "(?im)^\\s*LANGUAGE\\s*:\\s*([^\\n]+)\\s*$").matcher(txt);
        if (lm.find()) cf.lang = cleanLang(lm.group(1));

        String code = null;
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                "(?is)===CODE===\\s*(.*?)\\s*(?:===END===|$)").matcher(txt);
        if (cm.find()) {
            code = cm.group(1);
        } else {
            java.util.regex.Matcher f = java.util.regex.Pattern.compile(
                    "(?is)```([a-zA-Z0-9+#.\\-]*)[ \\t]*\\n(.*?)```").matcher(txt);
            String best = null, bl = "";
            while (f.find()) {
                String c = f.group(2);
                if (best == null || c.length() > best.length()) {
                    best = c;
                    bl = f.group(1) == null ? "" : f.group(1);
                }
            }
            if (best != null) {
                code = best;
                if (cf.lang.isEmpty() && !bl.trim().isEmpty()) cf.lang = cleanLang(bl);
            } else {
                code = stripPreamble(txt);
            }
        }
        if (code == null) return null;

        code = code.replaceAll("(?s)^```[a-zA-Z0-9+#.\\-]*[ \\t]*\\n", "")
                   .replaceAll("(?s)\\n?```\\s*$", "").trim();
        cf.code = code;

        if (cf.name.isEmpty()) {
            String req = request == null ? "" : request;
            java.util.regex.Matcher em = CODE_EXT_RX.matcher(req);
            if (em.find()) cf.name = cleanName(em.group());
        }
        if (cf.name.isEmpty()) cf.name = editing && !ideFile.isEmpty() ? ideFile : guessFileName(request);
        if (cf.lang.isEmpty()) cf.lang = langForName(cf.name);
        if (cf.lang.isEmpty()) cf.lang = guessLang(cf.name, request);
        return cf;
    }

    void sanitize(CodeFile cf) {
        if (cf == null) return;
        cf.name = cleanName(cf.name);
        if (cf.name.isEmpty()) cf.name = fileNameFromLang(cf.lang);
        if (!cf.name.contains(".")) {
            String ext = extForLang(cf.lang);
            if (!ext.isEmpty()) cf.name += "." + ext;
        }
        if (cf.lang.isEmpty()) cf.lang = langForName(cf.name);
        if (cf.lang.isEmpty()) cf.lang = "text";
    }

    static String stripPreamble(String txt) {
        if (txt == null) return "";
        String[] marks = {"<!DOCTYPE", "<!doctype", "<html", "#include", "#import",
                "import ", "from ", "def ", "public class", "function ", "const ", "let ",
                "class ", "using ", "package ", "<?php"};
        int best = -1;
        for (String m : marks) {
            int i = txt.indexOf(m);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        return best > 0 ? txt.substring(best) : txt;
    }

    static String cleanName(String s) {
        if (s == null) return "";
        String n = s.trim().replaceAll("^[\"'`*]+|[\"'`*]+$", "");
        n = n.replace('\\', '/').replace(' ', '-');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[^A-Za-z0-9._\\-]", "").trim();
        if (n.length() > 80) n = n.substring(0, 80);
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return "";
        return n;
    }

    static String cleanLang(String s) {
        if (s == null) return "";
        String l = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#.\\-]", "");
        switch (l) {
            case "js": case "node": case "nodejs": return "javascript";
            case "ts": return "typescript";
            case "py": return "python";
            case "c++": return "cpp";
            case "c#": return "csharp";
            case "htm": return "html";
            case "sh": case "shell": return "bash";
            case "yml": return "yaml";
            case "ps": case "ps1": return "powershell";
            case "md": return "markdown";
            case "plaintext": case "plain": return "text";
            default: return l;
        }
    }

    static String extForLang(String lang) {
        switch (lang == null ? "" : lang) {
            case "html": return "html";
            case "css": return "css";
            case "scss": return "scss";
            case "javascript": return "js";
            case "typescript": return "ts";
            case "python": return "py";
            case "java": return "java";
            case "c": return "c";
            case "cpp": return "cpp";
            case "csharp": return "cs";
            case "go": return "go";
            case "rust": return "rs";
            case "ruby": return "rb";
            case "php": return "php";
            case "json": return "json";
            case "yaml": return "yml";
            case "xml": return "xml";
            case "sql": return "sql";
            case "bash": return "sh";
            case "powershell": return "ps1";
            case "markdown": return "md";
            default: return "txt";
        }
    }

    static String langForName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String e = dot >= 0 ? n.substring(dot + 1) : "";
        switch (e) {
            case "html": case "htm": return "html";
            case "css": return "css";
            case "scss": return "scss";
            case "js": case "mjs": case "cjs": return "javascript";
            case "jsx": return "jsx";
            case "ts": return "typescript";
            case "tsx": return "tsx";
            case "py": return "python";
            case "java": return "java";
            case "c": return "c";
            case "cpp": case "cc": case "hpp": return "cpp";
            case "cs": return "csharp";
            case "go": return "go";
            case "rs": return "rust";
            case "rb": return "ruby";
            case "php": return "php";
            case "json": return "json";
            case "yml": case "yaml": return "yaml";
            case "xml": return "xml";
            case "sql": return "sql";
            case "sh": return "bash";
            case "ps1": return "powershell";
            case "md": return "markdown";
            default: return "";
        }
    }

    static String fileNameFromLang(String lang) {
        switch (lang == null ? "" : lang) {
            case "html": return "index.html";
            case "css": return "styles.css";
            case "scss": return "styles.scss";
            case "javascript": return "script.js";
            case "jsx": return "App.jsx";
            case "typescript": return "main.ts";
            case "tsx": return "App.tsx";
            case "python": return "main.py";
            case "java": return "Main.java";
            case "c": return "main.c";
            case "cpp": return "main.cpp";
            case "csharp": return "Program.cs";
            case "go": return "main.go";
            case "rust": return "main.rs";
            case "ruby": return "main.rb";
            case "php": return "index.php";
            case "json": return "data.json";
            case "yaml": return "config.yml";
            case "xml": return "data.xml";
            case "sql": return "query.sql";
            case "bash": return "script.sh";
            case "powershell": return "script.ps1";
            case "markdown": return "README.md";
            default: return "code.txt";
        }
    }

    static boolean hasWord(String n, String w) {
        return java.util.regex.Pattern.compile("(?i)\\b" + java.util.regex.Pattern.quote(w) + "\\b")
                .matcher(n).find();
    }

    static String guessFileName(String req) {
        String raw = req == null ? "" : req;
        String n = raw.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)\\b(?:named|called|save as|filename|file name|file)\\s+[\"'`]?"
              + "([\\w\\-]+\\.(?:html?|css|js|jsx|ts|tsx|py|java|c|cpp|cs|go|rs|rb|php|json|yml|yaml|xml|sql|sh|ps1|md))")
                .matcher(raw);
        if (m.find()) return cleanName(m.group(1));

        if (hasWord(n, "html") || hasWord(n, "webpage") || hasWord(n, "website")) return "index.html";
        if (hasWord(n, "css") || hasWord(n, "stylesheet")) return "styles.css";
        if (hasWord(n, "javascript") || hasWord(n, "js")) return "script.js";
        if (hasWord(n, "typescript") || hasWord(n, "ts")) return "main.ts";
        if (hasWord(n, "python") || hasWord(n, "py")) return "main.py";
        if (hasWord(n, "java") && !hasWord(n, "javascript")) return "Main.java";
        if (hasWord(n, "c++") || hasWord(n, "cpp")) return "main.cpp";
        if (hasWord(n, "c#") || hasWord(n, "csharp")) return "Program.cs";
        if (hasWord(n, "react")) return "App.jsx";
        if (hasWord(n, "node")) return "server.js";
        if (hasWord(n, "sql") || hasWord(n, "database")) return "query.sql";
        if (hasWord(n, "powershell") || hasWord(n, "ps1")) return "script.ps1";
        if (hasWord(n, "bash") || hasWord(n, "shell")) return "script.sh";
        return "code.txt";
    }

    static String guessLang(String name, String req) {
        String byName = langForName(name);
        if (!byName.isEmpty()) return byName;
        String n = req == null ? "" : req.toLowerCase(Locale.ROOT);
        if (hasWord(n, "html") || hasWord(n, "webpage") || hasWord(n, "website")) return "html";
        if (hasWord(n, "css") || hasWord(n, "stylesheet")) return "css";
        if (hasWord(n, "javascript") || hasWord(n, "js")) return "javascript";
        if (hasWord(n, "typescript") || hasWord(n, "ts")) return "typescript";
        if (hasWord(n, "python") || hasWord(n, "py")) return "python";
        if (hasWord(n, "java") && !hasWord(n, "javascript")) return "java";
        if (hasWord(n, "c++") || hasWord(n, "cpp")) return "cpp";
        if (hasWord(n, "c#") || hasWord(n, "csharp")) return "csharp";
        if (hasWord(n, "react")) return "jsx";
        if (hasWord(n, "node")) return "javascript";
        if (hasWord(n, "sql") || hasWord(n, "database")) return "sql";
        if (hasWord(n, "powershell") || hasWord(n, "ps1")) return "powershell";
        if (hasWord(n, "bash") || hasWord(n, "shell")) return "bash";
        return "text";
    }

    /* ---------------- save + copy into the user's editor ---------------- */

    String saveProjectFile(String code, String name) {
        try {
            File dir = new File(System.getProperty("user.home"), "ProtonProjects");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File f = new File(dir, name);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(code);
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            log("could not save project file: " + shortStr(String.valueOf(t.getMessage())));
            return null;
        }
    }

    static final String[] USER_EDITORS = {
        "Visual Studio Code", "VSCodium", "Cursor", "PyCharm", "IntelliJ IDEA",
        "WebStorm", "Sublime Text", "Notepad++", "Atom", "Eclipse", "NetBeans",
        "Kate", "gedit", "Notepad"
    };

    String findOpenUserEditor() {
        for (String e : USER_EDITORS) {
            try {
                String title = findOpenWindow(e);
                if (title != null && !title.toLowerCase(Locale.ROOT).contains("proton")) return title;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Opens the saved file in whichever editor is running (or launches one). */
    boolean openFileInEditor(String editorTitle, File f) {
        String t = editorTitle == null ? "" : editorTitle.toLowerCase(Locale.ROOT);
        String cmd = null;
        if (t.contains("visual studio code") || t.contains("vscodium") || t.contains("cursor")) cmd = "code";
        else if (t.contains("pycharm")) cmd = "pycharm";
        else if (t.contains("webstorm")) cmd = "webstorm";
        else if (t.contains("intellij")) cmd = "idea";
        else if (t.contains("sublime")) cmd = "subl";
        else if (t.contains("notepad++")) cmd = "notepad++";
        else if (t.contains("atom")) cmd = "atom";
        else if (t.contains("notepad")) cmd = "notepad";
        if (cmd == null || f == null || !f.isFile()) return false;
        try {
            Process p;
            if (isWin()) p = new ProcessBuilder("cmd", "/c", cmd, f.getAbsolutePath())
                    .redirectErrorStream(true).start();
            else p = new ProcessBuilder(cmd, f.getAbsolutePath()).redirectErrorStream(true).start();
            p.waitFor(3, TimeUnit.SECONDS);
            return p.isAlive() || p.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    String syncCodeToUserEditor(String code, String name, String lang, boolean fresh) {
        File f = new File(new File(System.getProperty("user.home"), "ProtonProjects"), name);
        String editor = findOpenUserEditor();
        boolean openedFile = false;

        if (editor != null) {
            // Always focus the actual file, so an unrelated tab is never wiped.
            openedFile = openFileInEditor(editor, f);
            if (openedFile) {
                try { Thread.sleep(fresh ? 700 : 400); } catch (InterruptedException ignored) {}
                editor = findOpenUserEditor();
            }
        } else {
            try {
                openedFile = openFileInEditor("Visual Studio Code", f);
                if (!openedFile) actOpenApp("visual studio code", "writing " + name);
                for (int i = 0; i < 24 && editor == null; i++) {
                    Thread.sleep(250);
                    editor = findOpenUserEditor();
                }
            } catch (Throwable ignored) {}
        }

        if (editor == null) {
            setClipboard(code);
            return "clipboard";
        }
        try {
            actFocus(editor);
            Thread.sleep(500);
            if (fresh && !openedFile && ctrlNMeansNewFile(editor)) {
                // Best effort: a new document so existing code is not destroyed.
                actHotkey("ctrl+n");
                Thread.sleep(300);
            }
            actHotkey("ctrl+a");
            Thread.sleep(150);
            paste(code);
            Thread.sleep(200);
            return editor;
        } catch (Throwable t) {
            log("editor paste failed: " + shortStr(String.valueOf(t.getMessage())));
            setClipboard(code);
            return "clipboard";
        }
    }

    static boolean ctrlNMeansNewFile(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return t.contains("visual studio code") || t.contains("vscodium") || t.contains("cursor")
                || t.contains("sublime") || t.contains("notepad") || t.contains("atom");
    }

    static void setClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (Throwable ignored) {}
    }

    /** Keep code intact: strip a wrapping fence and  thinking blocks, but no markdown squashing. */
    static String cleanComplex(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end > 0) s = s.substring(0, end);
            s = s.trim();
        }
        return s;
    }

    String process(String userText) throws Exception {
        synchronized (planLock) {
            log("user: " + userText);
            remember("user", userText);
            earlyThread = null; earlyText = null; followUp = null;
            stepFailures.clear(); healable = false; planHasGui = false;

            // Instant answers: time, date, greetings, exact arithmetic, what Proton knows about you (0 ms, no network).
            try {
                String quick = tryInstant(userText);
                if (quick != null) { remember("assistant", quick); return quick; }
            } catch (Throwable t) { log("instant failed: " + shortStr(String.valueOf(t.getMessage()))); }

            // Skills: build new abilities, manage them, or trigger one by voice.
            try {
                String sk = trySkills(userText);
                if (sk != null) { remember("assistant", sk); return closeWith(sk); }
            } catch (Throwable t) { log("skills failed: " + shortStr(String.valueOf(t.getMessage()))); }

            // BlueJ: vision-driven mouse workflow (project -> class -> code -> compile).
            if (isBlueJTask(userText)) {
                String b;
                startEarlySpeech(pick("On it. Setting up BlueJ.", "Right away. Opening BlueJ.", "Certainly. BlueJ, coming up."));
                try { b = runAction("bluej", userText, userText); }
                finally { finishReflexDock(); }
                if (b == null || b.startsWith("failed:"))
                    b = "Sorry, I got stuck while working in BlueJ. " + (b == null ? "" : b);
                remember("assistant", b);
                return closeWith(b);
            }

            // Coding: PROTON writes in its own read-only IDE, then mirrors the
            // file into the user's editor (VS Code, PyCharm, ...). Follow-up
            // requests edit the existing code and show a red/green line diff.
            if (isCodeWriteTask(userText) || isCodeFollowUp(userText)) {
                String c = null;
                try { c = tryCodeTask(userText); }
                catch (Throwable t) { log("code task failed: " + shortStr(String.valueOf(t.getMessage()))); }
                if (c != null && !c.isEmpty()) { remember("assistant", c); return closeWith(c); }
            }

            // Live web: Gemini-style grounded answers (search -> read -> speak).
            if (isWebQuestion(userText)) {
                String wa = webAnswer(userText);
                remember("assistant", wa);
                return wa;
            }

            // Phase 2 Module D: 0-2ms local reflex interception (no network).
            // Reflex bypasses corner mode entirely to avoid flickering.
            String reflex;
            try { reflex = tryReflex(userText); }
            finally { finishReflexDock(); }
            if (reflex != null) {
                reflex = jarvisReflex(reflex);
                remember("assistant", reflex);
                return reflex;
            }

            // Vision questions: Qwen looks first, then the reply model answers from what Qwen saw.
            if (isVisionQuestion(userText)) {
                String v;
                try { v = visionAnswer(userText); }
                finally { finishReflexDock(); }
                remember("assistant", v);
                return v;
            }

            // Questions, complex work and agentic requests get the thinking model first.
            // If Nemotron replies [[ACTION]], converse() returns null and we fall through
            // to chat(), the existing JSON action-plan protocol (two-step fallback).
            if (isChatLike(userText) || isComplexTask(userText)) {
                String c = null;
                try { c = converse(userText); } catch (Throwable t) { log("converse failed: " + shortStr(String.valueOf(t.getMessage()))); }
                if ("[[WEB]]".equals(c)) {
                    String wa = webAnswer(userText);
                    remember("assistant", wa);
                    return wa;
                }
                if (c != null && !c.isEmpty()) { remember("assistant", c); return c; }
            }

            String raw = chat(userText);
            Plan p = parsePlan(raw);
            if (p == null) {   // the model answered in plain words instead of JSON: just say it
                String t = cleanSpoken(raw);
                if (t.length() > 1) { p = new Plan(); p.speech = t; }
            }
            if (p == null) {
                remember("assistant", "(could not parse plan)");
                return "Sorry, I didn't quite get that. Could you say it again?";
            }
            normalizePlan(p);
            if (!p.steps.isEmpty()) {
                boolean onlyWeb = true;
                for (Step ws : p.steps) if (!"websearch".equals(ws.action)) { onlyWeb = false; break; }
                if (onlyWeb) {
                    String wq = p.steps.get(0).target;
                    String wa = webAnswer(wq == null || wq.trim().isEmpty() ? userText : wq);
                    remember("assistant", wa);
                    return wa;
                }
            }
            if (p.speech == null || p.speech.trim().isEmpty())
                p.speech = p.steps.isEmpty() ? "okay."
                         : (p.steps.get(0).action + " " + p.steps.get(0).target).trim();
            log("speech: " + p.speech);
            for (Step gs : p.steps) if ("gui".equals(gs.action)) planHasGui = true;
            if (!p.steps.isEmpty()) startEarlySpeech(p.speech);   // talk now, work while talking

            CompanionHooks hooks = companion;
            // Universal mini-orb trigger: every non-empty action plan docks
            // the main window to the corner HUD during execution.
            boolean accompany = hooks != null && p.steps != null && !p.steps.isEmpty()
                    && hooks.shouldAccompany(p.steps.size());
            if (!accompany) {
                for (Step s : p.steps) {
                    log("step: " + s.action + " " + s.target);
                    String res;
                    if ("writecode".equals(s.action) || "writetext".equals(s.action)) {
                        res = doWrite(s, "writecode".equals(s.action));
                    } else {
                        res = runAction(s.action, s.target, userText);
                    }
                    if ("gui".equals(s.action) && res != null) p.speech = guiSpeech(res, p.speech);
                    noteFailure(s, res);
                    if (("skill".equals(s.action) || "newskill".equals(s.action)) && res != null) p.speech = res;
                    if (res != null && !res.isEmpty()) log("  -> " + res);
                    if (s.waitMs > 0) Thread.sleep(s.waitMs);
                }
                return finishPlan(p, userText);
            }

            // Companion morph path: glide to the corner HUD, guarantee teardown.
            hooks.enter(p.steps.size());
            dockActive = true;
            final long dockT0 = System.currentTimeMillis();
            boolean completed = false;
            try {
                // Let the 300ms morph curve settle before step 1 touches the desktop.
                try { Thread.sleep(320); } catch (InterruptedException ie) { throw new CompanionAbort(); }
                int total = p.steps.size();
                for (int i = 0; i < total; i++) {
                    Step s = p.steps.get(i);
                    if (hooks.isAborted()) throw new CompanionAbort();
                    try { hooks.onStep(i, total, s.action, s.target); } catch (Throwable ignored) {}
                    log("step: " + s.action + " " + s.target);
                    String res;
                    if ("writecode".equals(s.action) || "writetext".equals(s.action)) {
                        res = doWrite(s, "writecode".equals(s.action));
                    } else {
                        res = runAction(s.action, s.target, userText);
                    }
                    if ("gui".equals(s.action) && res != null) p.speech = guiSpeech(res, p.speech);
                    noteFailure(s, res);
                    if (("skill".equals(s.action) || "newskill".equals(s.action)) && res != null) p.speech = res;
                    if (res != null && !res.isEmpty()) log("  -> " + res);
                    if (s.waitMs > 0) sleepAbortable(s.waitMs);
                    if (hooks.isAborted()) throw new CompanionAbort();
                }
                completed = true;
                // Theme-synced completion pulse while the mini-orb is still docked.
                try { hooks.onDone(); } catch (Throwable ignored) {}
                // Stay docked long enough for the app/page to finish (min dock + linger).
                long dockLeft = MIN_DOCK_MS - (System.currentTimeMillis() - dockT0);
                sleepAbortable(Math.max(DONE_LINGER_MS, dockLeft));
                return finishPlan(p, userText);
            } catch (CompanionAbort a) {
                remember("assistant", "Aborted.");
                return "Aborted.";
            } finally {
                dockActive = false;
                try { hooks.exit(completed); } catch (Throwable ignored) {}
                // Let the ~300ms expand glide settle before assistant speech begins.
                try { Thread.sleep(320); } catch (InterruptedException ignored) {}
            }
        }
    }

    /* ============================================================
     * Phase 2 Module D: Fast Reflex Router (0-2ms local interception)
     * Deterministic tasks bypass the LLM entirely. Returns non-null
     * speech acknowledgment if handled, else null to fall through.
     * ============================================================ */
    String tryReflex(String userText) {
        if (userText == null) return null;
        String low = userText.trim().toLowerCase(Locale.ROOT);
        if (low.isEmpty()) return null;
        // Light normalization: strip leading/trailing politeness + punctuation.
        String norm = low.replaceAll("\\s+", " ").trim();
        if (norm.startsWith("please ")) norm = norm.substring(7).trim();
        if (norm.endsWith(" please")) norm = norm.substring(0, norm.length() - 7).trim();
        norm = norm.replaceAll("[.!?]+$", "").trim();
        if (norm.isEmpty()) return null;
        try {
            // ---- Volume ----
            if (norm.contains("unmute")) {
                String r = runAction("volume", "unmute", userText);
                return "\u26A1 Reflex: " + r;
            }
            if (norm.contains("volume up") || norm.contains("turn volume up")
                    || norm.contains("increase volume") || norm.contains("raise volume")
                    || norm.equals("volume up") || norm.equals("turn it up")) {
                String r = runAction("volume", "up", userText);
                return "\u26A1 Reflex: " + r;
            }
            if (norm.contains("volume down") || norm.contains("turn volume down")
                    || norm.contains("decrease volume") || norm.contains("lower volume")
                    || norm.equals("volume down") || norm.equals("turn it down")) {
                String r = runAction("volume", "down", userText);
                return "\u26A1 Reflex: " + r;
            }
            if (norm.matches(".*\\bmute\\b.*") && !norm.contains("unmute")) {
                String r = runAction("volume", "mute", userText);
                return "\u26A1 Reflex: " + r;
            }
            // ---- Screenshot ----
            if (norm.contains("screenshot") || norm.contains("capture screen")
                    || norm.contains("capture my screen") || norm.contains("take a screen")) {
                String r = runAction("screenshot", "", userText);
                return "\u26A1 Reflex: " + r;
            }
            // ---- Lock (guard against "unlock") ----
            if (norm.matches(".*\\block\\b.*") && !norm.contains("unlock")) {
                if (norm.equals("lock") || norm.contains("lock pc") || norm.contains("lock screen")
                        || norm.contains("lock computer") || norm.contains("lock my")
                        || norm.length() < 20) {
                    String r = runAction("lock", "", userText);
                    return "\u26A1 Reflex: " + r;
                }
            }
            // ---- Clipboard ----
            if (norm.contains("read clipboard") || norm.contains("what is on my clipboard")
                    || norm.contains("what's on my clipboard") || norm.contains("whats on my clipboard")
                    || norm.contains("show clipboard") || norm.contains("clipboard contents")
                    || norm.equals("clipboard")) {
                String r = runAction("clipread", "", userText);
                return "\u26A1 Reflex: " + r;
            }
            // ---- Which apps are open ----
            if (norm.equals("list apps") || norm.equals("list open apps")
                    || norm.matches(".*\\b(what'?s|what is) open\\b.*")
                    || norm.matches(".*\\b(what|which|list|show)\\b.*\\b(apps|applications|windows|programs)\\b.*\\b(open|running)\\b.*")) {
                String r = runAction("listapps", "", userText);
                return "\u26A1 Reflex: " + r;
            }
            // ---- Close app: close/quit <app> ----
            if (norm.matches("^(close|quit|exit|kill)\\s+.+") && !norm.contains(" and ")) {
                String t = norm.replaceFirst("^(close|quit|exit|kill)\\s+", "")
                        .replaceAll("\\s+for me$", "").replaceFirst("^(the|my)\\s+", "").trim();
                if (t.matches("^(it|this|that|this window|this app|current window|the current window)$"))
                    t = sLastWindow == null ? "" : sLastWindow;
                if (!t.isEmpty() && !t.contains("proton") && !t.equals("all")) {
                    String r = runAction("closeapp", t, userText);
                    return "\u26A1 Reflex: " + r;
                }
            }
            // ---- Deterministic web: search/google/youtube/bare URL ----
            String web = tryReflexWeb(norm);
            if (web != null) return web;
            // ---- Deterministic app launch: open/launch <app> (exact, score>=1200) ----
            String app = tryReflexApp(norm);
            if (app != null) return app;
            // ---- Dictation continuity: say/type/tell it -> type into foreground ----
            String dict = tryReflexDictation(norm);
            if (dict != null) return dict;
        } catch (Throwable t) {
            log("reflex failed: " + t.getMessage());
            return null;
        }
        return null;
    }

    private String tryReflexWeb(String norm) {
        // google/search
        if (norm.matches("^(search|google)( for)?\\s+.+")) {
            String q = norm.replaceFirst("^(search|google)( for)?\\s+", "").trim();
            if (!q.isEmpty() && !q.contains(" and ")) {
                String r = runAction("search", q, norm);
                return "\u26A1 Reflex: " + r;
            }
        }
        if (norm.matches("^youtube\\s+.+")) {
            String q = norm.replaceFirst("^youtube\\s+", "").trim();
            if (!q.isEmpty()) {
                String r = runAction("youtube", q, norm);
                return "\u26A1 Reflex: " + r;
            }
        }
        // Explicit URL: http(s)://..., www...., or bare domain.
        String trimmed = norm.trim();
        // Strip leading open/launch/go to prefix for URL detection.
        String cand = trimmed.replaceFirst("^(open|launch|go to|visit)\\s+", "").trim();
        if (isExplicitUrl(cand)) {
            String r = runAction("openurl", cand, norm);
            return "\u26A1 Reflex: " + r;
        }
        if (isExplicitUrl(trimmed)) {
            String r = runAction("openurl", trimmed, norm);
            return "\u26A1 Reflex: " + r;
        }
        return null;
    }

    private static boolean isExplicitUrl(String s) {
        if (s == null || s.isEmpty() || s.contains(" ")) return false;
        String l = s.toLowerCase(Locale.ROOT);
        if (l.startsWith("http://") || l.startsWith("https://") || l.startsWith("www.")) return true;
        // bare domain like youtube.com, gemini.google.com
        if (l.matches("^[a-z0-9\\-]+(\\.[a-z0-9\\-]+)+(/\\S*)?$")
                && l.matches(".*\\.(com|org|net|io|ai|dev|gov|edu|co|in|app|gg|xyz)(/.*)?$")) return true;
        return false;
    }

    /** Maps famous browser apps with no local install to direct URLs (offline-safe). */
    private static String wellKnownWebApp(String target) {
        if (target == null) return null;
        String t = target.toLowerCase(Locale.ROOT).trim();
        // Strip trailing site words: "gemini website" -> "gemini".
        t = t.replaceAll("\\s+(website|web app|app|site)$", "").trim();
        switch (t) {
            case "gemini": return "https://gemini.google.com";
            case "chatgpt": case "chat gpt": case "chat gpt 4": return "https://chatgpt.com";
            case "claude": return "https://claude.ai";
            case "copilot": return "https://copilot.microsoft.com";
            case "youtube": return "https://www.youtube.com";
            case "gmail": return "https://mail.google.com";
            case "drive": case "google drive": return "https://drive.google.com";
            case "docs": case "google docs": return "https://docs.google.com";
            default: return null;
        }
    }

    private String tryReflexApp(String norm) {
        String target = null;
        if (norm.matches("^(open|launch|start)\\s+.+")) {
            target = norm.replaceFirst("^(open|launch|start)\\s+", "").trim();
        }
        if (target == null || target.isEmpty()) return null;
        // Multi-step ("open X and ...") goes to the LLM planner.
        if (target.contains(" and ")) return null;
        // URL-like targets are handled by the web branch.
        if (isExplicitUrl(target)) return null;
        // Strip trailing "for me" politeness.
        target = target.replaceAll("\\s+for me$", "").trim();
        if (target.isEmpty()) return null;
        // Well-known browser web apps (Gemini/ChatGPT/...) have no local AppIndex
        // entry -- open them directly so "open gemini" works offline in <10ms.
        String webApp = wellKnownWebApp(target);
        if (webApp != null) {
            String r = runAction("openurl", webApp, norm);
            sLastApp = target;
            String wt = webTitleForUrl(webApp);
            sLastWindow = wt != null ? wt : "Chrome";
            log("reflex webapp: " + target + " -> " + webApp);
            return "\u26A1 Reflex: " + r;
        }
        // Already running? Focus it instead of launching a second copy.
        String alreadyOpen = focusIfOpen(target);
        if (alreadyOpen != null) return "\u26A1 Reflex: " + alreadyOpen;
        target = fixAppAlias(target);
        Match m = AppIndex.bestMatch(target);
        if (m != null && m.app != null && m.score >= 1200) {
            String launchRes = AppIndex.launch(m.app);
            // Mirror actOpenApp memory bookkeeping for follow-up continuity.
            sLastApp = m.app.name;
            String g = sLastApp.toLowerCase(Locale.ROOT);
            if (g.contains("code")) sLastWindow = "Visual Studio Code";
            else if (g.contains("chrome")) sLastWindow = "Chrome";
            else if (g.contains("notepad")) sLastWindow = "Notepad";
            else if (g.contains("edge")) sLastWindow = "Edge";
            else sLastWindow = sLastApp;
            log("reflex app: " + target + " -> " + m.app.name + " (" + m.score + ")");
            return "\u26A1 Reflex: " + launchRes;
        }
        return null;
    }

    private String tryReflexDictation(String norm) {
        String dictate = null;
        if (norm.matches("^say\\s+.+")) dictate = norm.replaceFirst("^say\\s+", "").trim();
        else if (norm.matches("^type\\s+.+")) dictate = norm.replaceFirst("^type\\s+", "").trim();
        else if (norm.matches("^tell (it|him|her|them)\\s+.+"))
            dictate = norm.replaceFirst("^tell (it|him|her|them)\\s+", "").trim();
        else return null;
        if (dictate.isEmpty()) return null;
        // Strip "to say"/"that" preambles: "tell it to say hello" -> "hello".
        dictate = dictate.replaceFirst("^(to say|to type|that)\\s+", "").trim();
        // Strip surrounding quotes.
        if (dictate.length() >= 2 && ((dictate.startsWith("\"") && dictate.endsWith("\""))
                || (dictate.startsWith("'") && dictate.endsWith("'"))))
            dictate = dictate.substring(1, dictate.length() - 1).trim();
        if (dictate.isEmpty()) return null;
        // Fast foreground peek (no blocking PowerShell): cached title or session memory.
        String fg = "";
        try {
            long age = System.currentTimeMillis() - fgCacheTime;
            if (age < FG_TTL_MS && fgCacheTitle != null) fg = fgCacheTitle;
            else if (sLastWindow != null) fg = sLastWindow;
        } catch (Throwable ignored) {}
        String fgl = fg == null ? "" : fg.toLowerCase(Locale.ROOT);
        boolean chatLike = fgl.contains("gemini") || fgl.contains("chatgpt") || fgl.contains("chat gpt")
                || fgl.contains("claude") || fgl.contains("chrome") || fgl.contains("edge")
                || fgl.contains("firefox") || fgl.contains("browser") || fgl.contains("notepad")
                || fgl.contains("code") || fgl.contains("vscode") || fgl.contains("word")
                || fgl.contains("docs") || fgl.contains("editor");
        // If we have evidence of a chat/editor foreground (or session memory of one),
        // type directly; otherwise let the LLM decide (speak vs type).
        if (!chatLike) {
            // Fall back to session memory: if last window was chat-like, still dictate.
            String sl = sLastWindow == null ? "" : sLastWindow.toLowerCase(Locale.ROOT);
            String sa = sLastApp == null ? "" : sLastApp.toLowerCase(Locale.ROOT);
            boolean memChat = sl.contains("gemini") || sl.contains("chrome") || sl.contains("notepad")
                    || sl.contains("code") || sa.contains("chrome") || sa.contains("gemini")
                    || sa.contains("notepad") || sa.contains("edge");
            if (!memChat) return null;
        }
        String r = runAction("type", dictate, norm);
        return "\u26A1 Reflex: " + r + " \"" + dictate + "\"";
    }

    void clearSession() {
        synchronized (history) {
            history.clear();
            sLastApp = sLastWindow = sLastTyped = sLastTopic = "";
        }
    }

    /** STT tuned for the short wake phrase (English, biased towards "Hey Proton"). */
    String transcribeWake(byte[] wav) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("model", STT_MODEL);
        f.put("response_format", "json");
        f.put("language", "en");
        f.put("prompt", "Hey Proton.");
        String body = postMultipart("/audio/transcriptions", wav, "audio.wav", f);
        Object root = jparse(body);
        Object text = dig(root, "text");
        return text == null ? "" : text.toString();
    }

    String transcribe(byte[] wav) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("model", STT_MODEL);
        f.put("response_format", "json");
        String body = postMultipart("/audio/transcriptions", wav, "audio.wav", f);
        Object root = jparse(body);
        Object text = dig(root, "text");
        return text == null ? "" : text.toString();
    }

    /** Immediately stops whatever the assistant is saying. */
    void stopSpeaking() {
        speechCancelled = true;
        Clip c = activeClip;
        if (c != null) { try { c.stop(); } catch (Throwable ignored) {} }
        SourceDataLine sl = activeLine;
        if (sl != null) { try { sl.stop(); sl.flush(); } catch (Throwable ignored) {} }
        Process p = speechProc;
        if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
    }

    void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        speechCancelled = false;
        boolean played = false;
        if (!ttsDisabled) {
            String[][] cands = {
                {TTS_MODEL, TTS_VOICE},
                {"playai-tts", "Fritz-PlayAI"},
                {"playai-tts", "Arista-PlayAI"},
                {"playai-tts", "Celeste-PlayAI"},
                {"playai-tts", "Cheyenne-PlayAI"},
                {"playai-tts", "Thunder-PlayAI"},
            };
            if (ttsModel != null) {
                try { played = speakWith(text, ttsModel, ttsVoice); }
                catch (Throwable t) { log("tts " + ttsModel + " failed: " + t); ttsModel = null; }
            }
            if (!played && !speechCancelled) {
                for (String[] c : cands) {
                    if (speechCancelled) break;
                    try {
                        if (speakWith(text, c[0], c[1])) {
                            ttsModel = c[0]; ttsVoice = c[1];
                            played = true;
                            break;
                        }
                    } catch (Throwable t) { log("tts " + c[0] + "/" + c[1] + " failed: " + t); }
                }
            }
            if (!played && !ttsLogged && !speechCancelled) {
                ttsLogged = true;
                ttsDisabled = true;
                log("groq tts unavailable - using system voice");
            }
        }
        if (!played && !speechCancelled) speakNative(text);
    }

    /** Splits text into <= max-char pieces on sentence/clause/word boundaries. */
    static List<String> ttsChunks(String text, int max) {
        List<String> out = new ArrayList<>();
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) { if (!t.isEmpty()) out.add(t); return out; }
        StringBuilder cur = new StringBuilder();
        for (String sent : t.split("(?<=[.!?;:])\\s+")) {
            while (sent.length() > max) {
                int cut = sent.lastIndexOf(", ", max);
                if (cut < max / 3) cut = sent.lastIndexOf(' ', max);
                if (cut < 1) cut = max;
                String head = sent.substring(0, Math.min(sent.length(), cut + 1)).trim();
                sent = sent.substring(Math.min(sent.length(), cut + 1)).trim();
                if (cur.length() > 0 && cur.length() + 1 + head.length() > max) { out.add(cur.toString()); cur.setLength(0); }
                if (cur.length() > 0) cur.append(' ');
                cur.append(head);
                out.add(cur.toString()); cur.setLength(0);
            }
            if (sent.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + 1 + sent.length() > max) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(sent);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Splits the first chunk after its first sentence/clause so audio starts as early as possible. */
    static List<String> shortFirst(List<String> parts) {
        String f = parts.get(0);
        if (f.length() <= 110) return parts;
        int cut = -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[.!?;:]\\s").matcher(f);
        while (m.find()) { if (m.end() >= 24 && m.end() <= 150) { cut = m.end(); break; } }
        if (cut < 0) {
            java.util.regex.Matcher c = java.util.regex.Pattern.compile(",\\s").matcher(f);
            while (c.find()) { if (c.end() >= 40 && c.end() <= 130) { cut = c.end(); break; } }
        }
        if (cut < 0 || cut >= f.length() - 8) return parts;
        List<String> out = new ArrayList<>();
        out.add(f.substring(0, cut).trim());
        out.add(f.substring(cut).trim());
        for (int i = 1; i < parts.size(); i++) out.add(parts.get(i));
        return out;
    }

    /** Synthesizes + plays with one model/voice. Long text is chunked; the next chunk is
     *  fetched while the current one plays. Returns true once playback started/finished. */
    boolean speakWith(String text, String model, String voice) throws Exception {
        List<String> parts = model.contains("orpheus")
                ? ttsChunks(text, TTS_MAX_CHARS) : java.util.Collections.singletonList(text);
        if (parts.isEmpty()) return true;
        if (parts.size() > 1 || parts.get(0).length() > 110) parts = shortFirst(parts);
        java.util.concurrent.CompletableFuture<byte[]> next = fetchTts(parts.get(0), model, voice);
        boolean started = false;
        for (int i = 0; i < parts.size(); i++) {
            if (speechCancelled) return true;
            byte[] wav;
            try { wav = next.get(40, TimeUnit.SECONDS); }
            catch (Throwable t) {
                if (!started) throw (t.getCause() instanceof Exception ? (Exception) t.getCause() : new Exception(t));
                return true;
            }
            next = (i + 1 < parts.size()) ? fetchTts(parts.get(i + 1), model, voice) : null;
            playWav(wav);
            started = true;
            if (next == null) break;
        }
        return true;
    }

    private java.util.concurrent.CompletableFuture<byte[]> fetchTts(String t, String model, String voice) {
        java.util.concurrent.CompletableFuture<byte[]> f = new java.util.concurrent.CompletableFuture<>();
        Thread th = new Thread(() -> {
            try { f.complete(synthesize(t, model, voice)); }
            catch (Throwable ex) { f.completeExceptionally(ex); }
        }, "tts-fetch");
        th.setDaemon(true);
        th.start();
        return f;
    }

    void remember(String role, String content) {
        if (content == null) return;
        String c = content.trim();
        if (c.isEmpty()) return;
        synchronized (history) {
            history.add(new ChatMsg(role, c));
            while (history.size() > HISTORY_MAX) history.remove(0);
        }
    }

    String sessionSnapshot() {
        StringBuilder b = new StringBuilder();
        b.append("- NOW: ").append(java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH))).append('\n');
        b.append(UserFacts.promptBlock());
        b.append(SkillStore.promptLine());
        if (!sLastApp.isEmpty())    b.append("- last app opened: ").append(sLastApp).append('\n');
        if (!sLastWindow.isEmpty()) b.append("- last window focused: ").append(sLastWindow).append('\n');
        if (!sLastTopic.isEmpty())  b.append("- last content written about: ").append(sLastTopic).append('\n');
        if (!sLastTyped.isEmpty()) {
            String t = sLastTyped.length() > 240 ? sLastTyped.substring(0, 240) + "..." : sLastTyped;
            b.append("- last text written (start): ").append(t.replace('\n', ' ')).append('\n');
        }
        // Phase 2 Module C: ground follow-ups in the live OS foreground window.
        try {
            String fg = getForegroundTitleFast();
            if (fg != null && !fg.trim().isEmpty()) {
                String clean = fg.trim().replace('"', '\'');
                if (clean.length() > 160) clean = clean.substring(0, 160);
                b.append("- current active foreground window: \"").append(clean).append("\"\n");
            }
        } catch (Throwable ignored) {}
        if (lastScreenDesc != null && !lastScreenDesc.isEmpty()
                && System.currentTimeMillis() - lastScreenTime < 600000L) {
            String d = lastScreenDesc.replace('\n', ' ');
            if (d.length() > 700) d = d.substring(0, 700) + "...";
            b.append("- LAST SCREEN SEEN (by vision, ")
             .append((System.currentTimeMillis() - lastScreenTime) / 1000).append("s ago): ").append(d).append('\n');
        }
        // Awareness: what is actually open right now.
        try {
            List<String> wins = openWindowsFast();
            if (!wins.isEmpty()) {
                StringBuilder w = new StringBuilder();
                int n = 0;
                for (String e : wins) {
                    if (n++ >= 20) break;
                    String c = e.replace('"', '\'');
                    if (c.length() > 80) c = c.substring(0, 80);
                    if (w.length() > 0) w.append(" ; ");
                    w.append(c);
                }
                b.append("- OPEN WINDOWS right now (process|title): ").append(w).append('\n');
            }
        } catch (Throwable ignored) {}
        if (b.length() == 0) return "(no prior session state)\n";
        return b.toString();
    }

    /* ============================================================
     * SPEED: never block a command on PowerShell
     * ============================================================ */
    private static final java.util.concurrent.atomic.AtomicBoolean ctxRefreshing = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Refreshes the foreground title + window list in the background (cheap to call often). */
    static void refreshContextAsync() {
        if (!ctxRefreshing.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try { fgCacheTime = 0L; getForegroundTitle(); openWindows(true); }
            catch (Throwable ignored) {}
            finally { ctxRefreshing.set(false); }
        }, "proton-ctx");
        t.setDaemon(true);
        t.start();
    }

    static String getForegroundTitleFast() {
        if (fgCacheTime == 0L) return getForegroundTitle();                       // very first call only
        if (System.currentTimeMillis() - fgCacheTime > FG_TTL_MS) refreshContextAsync();
        return fgCacheTitle;
    }

    static List<String> openWindowsFast() {
        if (winCacheTime == 0L) return openWindows(true);                         // very first call only
        if (System.currentTimeMillis() - winCacheTime > 2500) refreshContextAsync();
        return winCache;
    }

    /** Opens the TLS connection and primes the caches while the user is still looking at the window. */
    void warmUp() {
        if (key.isEmpty()) return;
        Thread t = new Thread(() -> {
            try { fgCacheTime = 0L; getForegroundTitle(); openWindows(true); } catch (Throwable ignored) {}
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(BASE + "/models").toURL().openConnection();
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setConnectTimeout(5000); c.setReadTimeout(5000);
                readStr(c.getInputStream());
            } catch (Throwable ignored) {}
            if (nvidia) {
                try {
                    HttpURLConnection c = nconn("/models");
                    c.setConnectTimeout(5000); c.setReadTimeout(5000);
                    readStr(c.getInputStream());
                } catch (Throwable ignored) {}
            }
        }, "proton-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** Fast structured call: low reasoning effort + token cap; falls back to a plain request if the API rejects the extras. */
    String postFast(String msgsJson, String effort, int maxTokens, double temp) throws Exception {
        String fast = "{\"model\":" + js(CHAT_MODEL) + ",\"messages\":[" + msgsJson + "],"
                    + "\"reasoning_effort\":" + js(effort) + ",\"include_reasoning\":false,"
                    + "\"max_tokens\":" + maxTokens + ",\"temperature\":" + temp + "}";
        try { return postJson("/chat/completions", fast); }
        catch (Exception ex) {
            String m = String.valueOf(ex.getMessage());
            if (m.contains("429") || m.contains("401") || m.contains("403")) throw ex;
            log("fast call rejected (" + shortStr(m) + ") - retrying plain");
            return postJson("/chat/completions", "{\"model\":" + js(CHAT_MODEL) + ",\"messages\":[" + msgsJson + "]}");
        }
    }

    /* ============================================================
     * SKILL FORGE: Proton writes, tests, repairs, saves and runs its own new abilities
     * ============================================================ */
    volatile java.util.function.Consumer<String> alertSpeaker;
    volatile java.util.function.Consumer<String> sayAloud;     // speak (no pop-up) when Proton is idle
    final java.util.concurrent.ScheduledExecutorService skillPool = java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "proton-skill"); t.setDaemon(true); return t; });
    final Map<String, java.util.concurrent.ScheduledFuture<?>> monitors = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, String> skillStatus = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, Long> skillStatusAt = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, Long> alertSeen = new java.util.concurrent.ConcurrentHashMap<>();
    volatile String pendingSkill = "";

    static final java.util.regex.Pattern SKILL_CREATE_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(?:develop|create|build|make|generate|design|write|add|code|invent|craft|program|forge|install)\\b[^.?!]{0,50}\\b(?:skills?|abilit(?:y|ies)|capabilit(?:y|ies))\\b"
      + "|\\bnew (?:skill|ability|capability)\\b|\\b(?:teach|train|upgrade|improve) yourself\\b|\\bgive yourself (?:a |the )?(?:new )?(?:skill|ability)\\b"
      + "|\\bgain (?:a |the )?(?:new )?(?:skill|ability)\\b");

    static final String FORGE_PROMPT =
        "You are the SKILL FORGE of PROTON, a JARVIS-style PC assistant. The user wants a NEW ability. Write ONE complete, self-contained script that implements it.\n" +
        "Target platform: {OS}. The script runs non-interactively (no prompts, no admin rights) and must finish within 20 seconds.\n\n" +
        "OUTPUT FORMAT (exactly this, nothing else, no markdown fences):\n" +
        "NAME: short_snake_case_name\n" +
        "TITLE: Human Readable Title\n" +
        "DESCRIPTION: one sentence\n" +
        "TRIGGERS: phrase one | phrase two | ... (6 to 10 natural things the user might say later to use it; lowercase; at least two words each)\n" +
        "MODE: oneshot or monitor\n" +
        "INTERVAL: seconds between checks (monitor only; 10 to 300; default 30)\n" +
        "LANGUAGE: {LANG}\n" +
        "===CODE===\n" +
        "<the script>\n" +
        "===END===\n\n" +
        "RULES\n" +
        "- MODE oneshot (answers on demand): print the answer the user wants as short plain sentences or lines.\n" +
        "- MODE monitor (anything about watching, monitoring, tracking, keeping an eye, alerting): print exactly ONE line starting with 'STATUS: ' that sums up the current state as a natural spoken sentence " +
        "(for example 'STATUS: CPU 23 percent, memory 61 percent, disk C 74 percent full, battery 88 percent.') and one line 'ALERT: <spoken sentence>' for EACH problem right now. " +
        "Use the thresholds from the request; otherwise sensible defaults (CPU above 90 percent, memory above 90 percent, any disk above 90 percent full, battery below 15 percent while unplugged, unusually high temperature).\n" +
        "- READ-ONLY by default: never delete, modify, kill, install, download, upload or change anything unless the user explicitly asked for exactly that action.\n" +
        "- Output with Write-Output only (PowerShell) or print (python). No colours, no tables, no progress bars, no Write-Host.\n" +
        "- Wrap every probe in try/catch and use -ErrorAction SilentlyContinue so one missing sensor never breaks the script. Built-in cmdlets / standard library only, no modules, no internet unless the user asked for it.\n" +
        "- PowerShell 5.1 facts: use Get-CimInstance (never Get-WmiObject). CPU: (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average. " +
        "Memory: Get-CimInstance Win32_OperatingSystem (TotalVisibleMemorySize and FreePhysicalMemory are in KB). Disks: Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3'. " +
        "Battery: Get-CimInstance Win32_Battery (EstimatedChargeRemaining; BatteryStatus 1 means discharging). Uptime: (Get-Date) - (Get-CimInstance Win32_OperatingSystem).LastBootUpTime. " +
        "Top processes: Get-Process | Sort-Object CPU -Descending | Select-Object -First 3. Network: Get-NetAdapter, Test-Connection -Count 1 -Quiet.\n" +
        "- For a system monitor include CPU, memory, disks, battery (if present), uptime and the top process; keep the STATUS sentence short.\n";

    /** One call to the thinking model. Overridable for tests. */
    String forgeCall(String msgsJson) throws Exception {
        String body;
        if (nvidia) {
            try {
                body = nvidiaComplete(main.NVIDIA_MODEL, msgsJson, 4500, 0.3);
            } catch (Throwable t) {
                log("nemotron forge failed, falling back to groq: " + shortStr(String.valueOf(t.getMessage())));
                body = postFast(msgsJson, "high", 4500, 0.3);
            }
        } else {
            body = postFast(msgsJson, "high", 4500, 0.3);
        }
        Object content = dig(jparse(body), "choices", 0, "message", "content");
        if (content == null || content.toString().trim().isEmpty()) throw new Exception("the model returned nothing");
        return content.toString();
    }

    String forgeMsgs(String request, String prevCode, String error, String output) {
        String lang = isWin() ? "powershell" : "python (or sh for trivial tasks)";
        String os = isWin() ? "Windows (PowerShell 5.1)" : isMac() ? "macOS (python3 or sh)" : "Linux (python3 or sh)";
        String sys = FORGE_PROMPT.replace("{OS}", os).replace("{LANG}", lang);
        StringBuilder u = new StringBuilder("Skill request: ").append(request).append('\n');
        if (prevCode != null && !prevCode.isEmpty()) {
            u.append("\nYour previous script:\n").append(prevCode).append("\n\nIt failed or produced no usable output.\nERROR OUTPUT:\n")
             .append(error == null || error.isEmpty() ? "(none)" : (error.length() > 1500 ? error.substring(0, 1500) : error))
             .append("\nSTANDARD OUTPUT:\n").append(output == null || output.isEmpty() ? "(empty)" : (output.length() > 800 ? output.substring(0, 800) : output))
             .append("\n\nFix it and return the COMPLETE corrected answer in the same output format.");
        } else if (error != null && !error.isEmpty()) {
            u.append('\n').append(error);
        }
        return "{\"role\":\"system\",\"content\":" + js(sys) + "},{\"role\":\"user\",\"content\":" + js(u.toString()) + "}";
    }

    static String field(String head, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?im)^\\s*" + key + "\\s*:\\s*(.+)$").matcher(head);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Parses the forge format into a spec map ("_code" holds the script). */
    Map<String, Object> parseForge(String raw) {
        if (raw == null) return null;
        String txt = raw.replace("\r", "");
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("(?is)===CODE===\\s*(.*?)\\s*(?:===END===|$)").matcher(txt);
        if (!cm.find()) return null;
        String code = cm.group(1).trim().replaceAll("(?s)^```[a-zA-Z0-9]*\\s*", "").replaceAll("(?s)\\s*```\\s*$", "").trim();
        if (code.length() < 20) return null;
        String head = txt.substring(0, cm.start());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", field(head, "NAME"));
        m.put("title", field(head, "TITLE"));
        m.put("description", field(head, "DESCRIPTION"));
        List<Object> trig = new ArrayList<>();
        for (String t : field(head, "TRIGGERS").split("\\|")) {
            String x = t.trim().toLowerCase(Locale.ROOT).replaceAll("^[\"'`]+|[\"'`]+$", "");
            if (x.length() >= 4) trig.add(x);
        }
        m.put("triggers", trig);
        m.put("mode", field(head, "MODE").toLowerCase(Locale.ROOT));
        m.put("interval", field(head, "INTERVAL"));
        m.put("language", field(head, "LANGUAGE").toLowerCase(Locale.ROOT));
        m.put("_code", code);
        return m;
    }

    @SuppressWarnings("unchecked")
    void normalizeSpec(Map<String, Object> sp, String request, String code) {
        String title = SkillStore.str(sp.get("title"));
        String name = SkillStore.clean(SkillStore.str(sp.get("name")));
        if (name.isEmpty()) name = SkillStore.clean(title);
        if (name.length() < 3) name = "skill_" + Integer.toHexString(request.hashCode() & 0xffff);
        if (title.isEmpty()) title = capWords(name.replace('_', ' '));
        sp.put("name", name);
        sp.put("title", title);
        if (SkillStore.str(sp.get("description")).isEmpty()) sp.put("description", title);
        String mode = SkillStore.str(sp.get("mode"));
        if (!mode.equals("monitor") && !mode.equals("oneshot")) mode = code.contains("STATUS:") ? "monitor" : "oneshot";
        sp.put("mode", mode);
        int iv = 30;
        try { iv = Integer.parseInt(SkillStore.str(sp.get("interval")).replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
        sp.put("interval", (long) Math.max(10, Math.min(300, iv)));
        String lang = SkillStore.str(sp.get("language"));
        if (isWin()) lang = "powershell";
        else if (!lang.equals("python") && !lang.equals("sh")) lang = lang.contains("sh") ? "sh" : "python";
        sp.put("language", lang);
        List<Object> trig = (List<Object>) sp.get("triggers");
        if (trig == null) { trig = new ArrayList<>(); sp.put("triggers", trig); }
        if (trig.size() < 2) { trig.add(title.toLowerCase(Locale.ROOT)); trig.add(name.replace('_', ' ')); }
        sp.put("request", request);
    }

    static final class RunRes { int exit = -1; String out = "", err = ""; boolean timedOut; }

    static Thread pump(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) if (sb.length() < 40000) sb.append(line).append('\n');
            } catch (Throwable ignored) {}
        }, "proton-skill-io");
        t.setDaemon(true);
        t.start();
        return t;
    }

    RunRes runScript(Map<String, Object> spec, int timeoutSec) {
        RunRes r = new RunRes();
        try {
            File sf = SkillStore.scriptFile(spec);
            String lang = SkillStore.str(spec.get("language"));
            List<String> cmd = new ArrayList<>();
            if (lang.equals("powershell"))
                cmd.addAll(Arrays.asList("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", sf.getAbsolutePath()));
            else if (lang.equals("python")) cmd.addAll(Arrays.asList(isWin() ? "python" : "python3", sf.getAbsolutePath()));
            else cmd.addAll(Arrays.asList("sh", sf.getAbsolutePath()));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(SkillStore.dir());
            final Process p = pb.start();
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}
            StringBuilder so = new StringBuilder(), se = new StringBuilder();
            Thread t1 = pump(p.getInputStream(), so), t2 = pump(p.getErrorStream(), se);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { r.timedOut = true; p.destroyForcibly(); }
            t1.join(1500); t2.join(1500);
            r.exit = r.timedOut ? -1 : p.exitValue();
            r.out = so.toString().trim();
            r.err = se.toString().trim();
        } catch (Throwable ex) { r.err = String.valueOf(ex.getMessage()); }
        return r;
    }

    static boolean scriptFailed(RunRes r, String mode) {
        if (r.timedOut || r.exit != 0) return true;
        if (r.err.contains("CategoryInfo") || r.err.contains("ParserError") || r.err.contains("FullyQualifiedErrorId")
                || r.err.contains("Traceback") || r.err.contains("SyntaxError")) return true;
        if (r.out.isEmpty()) return true;
        return mode.equals("monitor") && !r.out.contains("STATUS:");
    }

    static String describe(RunRes r) {
        if (r.timedOut) return "the script took longer than 25 seconds";
        String e = r.err.isEmpty() ? (r.out.isEmpty() ? "it printed nothing" : "unexpected output") : r.err;
        return e.length() > 400 ? e.substring(0, 400) : e;
    }

    /** Spoken form of a script's output (short outputs as-is, long ones summarised). */
    String speakable(String request, String out) {
        String flat = cleanSpoken(out.replaceAll("(?m)^\\s*(STATUS|ALERT):\\s*", "").replace('\n', ' '));
        if (flat.length() <= 260) return flat;
        try {
            String sys = "You are PROTON. A script just ran for the user's request. Turn its output into a short spoken answer: 1 to 3 sentences, plain words, "
                       + "say numbers naturally, no markdown, mention only what matters.";
            String msgs = "{\"role\":\"system\",\"content\":" + js(sys) + "},{\"role\":\"user\",\"content\":"
                        + js("Request: " + request + "\n\nOUTPUT:\n" + (out.length() > 6000 ? out.substring(0, 6000) : out)) + "}";
            Object c = dig(jparse(postFast(msgs, "low", 350, 0.3)), "choices", 0, "message", "content");
            if (c != null && !c.toString().trim().isEmpty()) return cleanSpoken(c.toString());
        } catch (Throwable t) { log("skill summary failed: " + shortStr(String.valueOf(t.getMessage()))); }
        return flat.substring(0, 300);
    }

    /** STATUS sentence plus any ALERT sentences. */
    static String statusLine(String out) {
        String status = ""; StringBuilder alerts = new StringBuilder();
        for (String line : out.split("\\r?\\n")) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "STATUS:", 0, 7)) status = t.substring(7).trim();
            else if (t.regionMatches(true, 0, "ALERT:", 0, 6)) alerts.append(' ').append(t.substring(6).trim());
        }
        String r = status;
        if (alerts.length() > 0) r = (r + " Warning:" + alerts).trim();
        return cleanSpoken(r);
    }

    /** Builds, tests (and repairs) a brand-new skill from the user's request. Returns what Proton says. */
    String createSkill(String request) throws Exception {
        log("skill forge: " + request);
        String raw = forgeCall(forgeMsgs(request, null, null, null));
        String code = "", lastErr = "", name = "";
        boolean saved = false, updated = false;
        Map<String, Object> sp = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            sp = parseForge(raw);
            if (sp == null) {
                raw = forgeCall(forgeMsgs(request, null, "Your last answer did not follow the required output format. Use the exact format.", null));
                continue;
            }
            code = (String) sp.remove("_code");
            normalizeSpec(sp, request, code);
            name = SkillStore.str(sp.get("name"));
            String[] risk = SkillStore.assess(code);
            if (risk[0].equals("block")) {
                if (saved) SkillStore.delete(name);
                log("skill blocked: " + risk[1]);
                return "I won't build that one. It would need something dangerous to your system, like " + shortStr(risk[1].replaceAll("[^A-Za-z0-9 \\-]", " ")) + ".";
            }
            Map<String, Object> old = SkillStore.get(name);
            updated = old != null;
            sp.put("created", old != null ? SkillStore.str(old.get("created")) : ProtonMemory.now());
            sp.put("version", old != null ? (long) ProtonMemory.n(old.get("version"), 1) + 1 : 1L);
            sp.put("runs", 0L);
            sp.put("risk", risk[0]);
            sp.put("riskReason", risk[1]);
            if (risk[0].equals("caution")) {       // never run unreviewed code that can change things
                sp.put("approved", false); sp.put("enabled", false);
                SkillStore.save(sp, code);
                pendingSkill = name;
                MemoryToast.show("Skill generated", SkillStore.str(sp.get("title")) + " \u00B7 waiting for your approval", 2);
                openApprovalFor(sp, code);
                return "I built " + SkillStore.str(sp.get("title")) + ", but it can change things or reach the internet, so I haven't run it. "
                     + "Attention: I've opened an approval window. Click approve to enable it, or deny to discard it.";
            }
            sp.put("approved", true); sp.put("enabled", true);
            SkillStore.save(sp, code);
            saved = true;
            RunRes rr = runScript(sp, 25);
            String mode = SkillStore.str(sp.get("mode"));
            if (!scriptFailed(rr, mode)) {
                SkillStore.touch(name);
                String title = SkillStore.str(sp.get("title"));
                MemoryToast.show(updated ? "Skill updated" : "Skill generated", title + " \u00B7 ready to use", 2);
                if (mode.equals("monitor")) {
                    String st = statusLine(rr.out);
                    skillStatus.put(name, st); skillStatusAt.put(name, System.currentTimeMillis());
                    startMonitor(SkillStore.get(name));
                    return "Skill ready: " + title + ". I'm watching it in the background and I'll speak up if anything looks wrong. Right now: " + st;
                }
                return "Skill ready: " + title + ". " + speakable(request, rr.out);
            }
            lastErr = describe(rr);
            log("skill test failed (attempt " + (attempt + 1) + "): " + lastErr);
            if (attempt < 2) raw = forgeCall(forgeMsgs(request, code, rr.err.isEmpty() ? lastErr : rr.err, rr.out));
        }
        if (saved && !name.isEmpty() && !updated) SkillStore.delete(name);
        return "I tried three times, but I couldn't get that skill to run cleanly: " + shortStr(lastErr.replace('\n', ' ')) + ".";
    }

    String createSkillReply(String request) {
        try { return createSkill(request); }
        catch (Throwable t) {
            log("skill forge error: " + t);
            return "I couldn't build that skill right now. " + shortStr(String.valueOf(t.getMessage()));
        }
    }

    /* ---------- monitors ---------- */
    void startMonitor(final Map<String, Object> spec) {
        if (spec == null) return;
        final String name = SkillStore.str(spec.get("name"));
        stopMonitor(name);
        long iv = Math.max(10, Math.min(600, (long) ProtonMemory.n(spec.get("interval"), 30)));
        monitors.put(name, skillPool.scheduleWithFixedDelay(() -> monitorTick(name), iv, iv, TimeUnit.SECONDS));
        log("monitor started: " + name + " every " + iv + "s");
    }

    void stopMonitor(String name) {
        java.util.concurrent.ScheduledFuture<?> f = monitors.remove(name);
        if (f != null) f.cancel(false);
    }

    void monitorTick(String name) {
        try {
            Map<String, Object> sp = SkillStore.get(name);
            if (sp == null || !SkillStore.truthy(sp.get("enabled")) || !SkillStore.truthy(sp.get("approved"))) { stopMonitor(name); return; }
            RunRes rr = runScript(sp, 25);
            SkillStore.touch(name);
            long now = System.currentTimeMillis();
            String status = "";
            List<String> alerts = new ArrayList<>();
            for (String line : rr.out.split("\\r?\\n")) {
                String t = line.trim();
                if (t.regionMatches(true, 0, "STATUS:", 0, 7)) status = t.substring(7).trim();
                else if (t.regionMatches(true, 0, "ALERT:", 0, 6)) alerts.add(t.substring(6).trim());
            }
            if (!status.isEmpty()) { skillStatus.put(name, cleanSpoken(status)); skillStatusAt.put(name, now); }
            for (String a : alerts) {
                String k = name + "|" + a.replaceAll("[0-9.]+", "#");
                Long seen = alertSeen.get(k);
                if (seen != null && now - seen < 15 * 60 * 1000L) continue;
                alertSeen.put(k, now);
                java.util.function.Consumer<String> sp2 = alertSpeaker;
                if (sp2 != null) sp2.accept(cleanSpoken(a));
            }
        } catch (Throwable t) { log("monitor " + name + " failed: " + shortStr(String.valueOf(t.getMessage()))); }
    }

    void startSkillRuntime() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2500);
                for (Map<String, Object> sp : SkillStore.list())
                    if ("monitor".equals(SkillStore.str(sp.get("mode"))) && SkillStore.truthy(sp.get("enabled")) && SkillStore.truthy(sp.get("approved")))
                        startMonitor(sp);
            } catch (Throwable ignored) {}
        }, "proton-skill-boot");
        t.setDaemon(true);
        t.start();
    }

    /* ---------- using skills ---------- */

    /** Runs an installed skill for a spoken request; repairs it once if it broke. */
    String runSkill(Map<String, Object> sp, String userText) {
        String name = SkillStore.str(sp.get("name")), title = SkillStore.str(sp.get("title"));
        if (!SkillStore.truthy(sp.get("approved"))) {
            openApprovalFor(sp, null);
            return title + " is waiting for your approval. I've opened the approval window.";
        }
        String mode = SkillStore.str(sp.get("mode"));
        long iv = (long) ProtonMemory.n(sp.get("interval"), 30);
        if (mode.equals("monitor") && monitors.containsKey(name)) {
            Long at = skillStatusAt.get(name);
            String st = skillStatus.get(name);
            if (st != null && at != null && System.currentTimeMillis() - at < (iv * 2 + 5) * 1000L) return st;
        }
        RunRes rr = runScript(sp, 25);
        if (scriptFailed(rr, mode)) {
            String healed = healSkill(sp, userText, rr);
            if (healed != null) return healed;
            return "The " + title + " skill hit an error: " + shortStr(describe(rr).replace('\n', ' ')) + ".";
        }
        SkillStore.touch(name);
        if (mode.equals("monitor")) {
            String st = statusLine(rr.out);
            skillStatus.put(name, st); skillStatusAt.put(name, System.currentTimeMillis());
            return monitors.containsKey(name) ? st : st + " Monitoring is currently paused.";
        }
        return speakable(userText, rr.out);
    }

    /** A saved skill broke (an update, a missing sensor...): rewrite it once and run it again. */
    String healSkill(Map<String, Object> sp, String userText, RunRes failed) {
        try {
            String name = SkillStore.str(sp.get("name"));
            String request = SkillStore.str(sp.get("request"));
            String raw = forgeCall(forgeMsgs(request.isEmpty() ? userText : request, SkillStore.code(sp),
                                             failed.err.isEmpty() ? describe(failed) : failed.err, failed.out));
            Map<String, Object> n = parseForge(raw);
            if (n == null) return null;
            String code = (String) n.remove("_code");
            String[] risk = SkillStore.assess(code);
            if (!risk[0].equals("safe")) return null;
            normalizeSpec(n, request.isEmpty() ? userText : request, code);
            n.put("name", name);
            n.put("created", SkillStore.str(sp.get("created")));
            n.put("version", (long) ProtonMemory.n(sp.get("version"), 1) + 1);
            n.put("runs", sp.get("runs") == null ? 0L : sp.get("runs"));
            n.put("risk", "safe"); n.put("riskReason", "");
            n.put("approved", true); n.put("enabled", sp.get("enabled") == null ? Boolean.TRUE : sp.get("enabled"));
            SkillStore.save(n, code);
            RunRes rr = runScript(n, 25);
            if (scriptFailed(rr, SkillStore.str(n.get("mode")))) return null;
            MemoryToast.show("Skill updated", SkillStore.str(n.get("title")) + " \u00B7 repaired itself", 2);
            if (SkillStore.str(n.get("mode")).equals("monitor")) {
                String st = statusLine(rr.out);
                skillStatus.put(name, st); skillStatusAt.put(name, System.currentTimeMillis());
                if (SkillStore.truthy(n.get("enabled"))) startMonitor(SkillStore.get(name));
                return "I repaired that skill. " + st;
            }
            return "I repaired that skill. " + speakable(userText, rr.out);
        } catch (Throwable t) { log("heal failed: " + shortStr(String.valueOf(t.getMessage()))); return null; }
    }

    /** Opens the red / black approval window for a skill that is waiting for permission. */
    void openApprovalFor(final Map<String, Object> spec, String codeOrNull) {
        final String name = SkillStore.str(spec.get("name")), title = SkillStore.str(spec.get("title"));
        String code = codeOrNull != null ? codeOrNull : SkillStore.code(spec);
        pendingSkill = name;
        ApprovalDialog.show(name, title, SkillStore.str(spec.get("description")), SkillStore.riskReasons(code), code,
            () -> new Thread(() -> {
                Map<String, Object> fresh = SkillStore.get(name);
                String r = fresh == null ? "That skill no longer exists." : approveSkill(fresh);
                java.util.function.Consumer<String> sp2 = sayAloud;
                if (sp2 != null) sp2.accept(r);
            }, "proton-approve").start(),
            () -> new Thread(() -> {
                stopMonitor(name);
                SkillStore.delete(name);
                if (name.equals(pendingSkill)) pendingSkill = "";
                MemoryToast.show("Skill discarded", title + " \u00B7 denied and removed", 2);
                java.util.function.Consumer<String> sp2 = sayAloud;
                if (sp2 != null) sp2.accept("Understood. I've discarded that skill.");
            }, "proton-deny").start());
    }

    String approveSkill(Map<String, Object> sp) {
        String name = SkillStore.str(sp.get("name")), title = SkillStore.str(sp.get("title"));
        ApprovalDialog.close(name);
        SkillStore.update(name, m -> { m.put("approved", true); m.put("enabled", true); });
        Map<String, Object> fresh = SkillStore.get(name);
        if (name.equals(pendingSkill)) pendingSkill = "";
        MemoryToast.show("Skill approved", title + " \u00B7 enabled", 2);
        String r = fresh == null ? "" : runSkill(fresh, title);
        if (fresh != null && "monitor".equals(SkillStore.str(fresh.get("mode")))) startMonitor(fresh);
        return "Approved. " + title + " is enabled. " + r;
    }

    static final java.util.regex.Pattern APPROVE_RX = java.util.regex.Pattern.compile(
        "^(?:yes |ok |okay |please |sure |go ahead and )*(?:approve|allow|authori[sz]e|enable|activate)(?: the)?(?: skill)?(?: (.+))?$");

    /** Everything about skills: building them, managing them, and triggering them by voice. Null = not a skill request. */
    String trySkills(String userText) {
        if (userText == null) return null;
        String raw = userText.trim();
        String n = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        if (n.length() < 4) return null;
        List<Map<String, Object>> all = SkillStore.list();

        // ---- management ----
        if (n.matches(".*\\b(?:what|which|list|show)\\b.*\\b(?:skills|abilities)\\b.*") && !SKILL_CREATE_RX.matcher(raw).find()) {
            if (all.isEmpty()) return "I haven't developed any extra skills yet. Ask me to build one, for example a skill that monitors your system.";
            StringBuilder b = new StringBuilder("I have " + all.size() + (all.size() == 1 ? " skill: " : " skills: "));
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) b.append(i == all.size() - 1 ? " and " : ", ");
                b.append(SkillStore.str(all.get(i).get("title")));
            }
            return b.append('.').toString();
        }
        if (!all.isEmpty() && n.matches(".*\\b(?:delete|remove|uninstall|erase|get rid of)\\b.*\\bskills?\\b.*")) {
            Map<String, Object> t = SkillStore.byWords(n.replaceAll("\\b(?:delete|remove|uninstall|erase|get rid of)\\b", " "));
            if (t == null) return null;
            String name = SkillStore.str(t.get("name"));
            stopMonitor(name);
            SkillStore.delete(name);
            return "Done. The " + SkillStore.str(t.get("title")) + " skill has been removed.";
        }
        boolean anyMonitor = false;
        for (Map<String, Object> sp : all) if ("monitor".equals(SkillStore.str(sp.get("mode")))) anyMonitor = true;
        if (anyMonitor && n.matches("^(?:please )?(?:stop|pause|disable|cancel|turn off|end)\\b.*\\b(?:monitoring|monitor|watching)\\b.*")) {
            Map<String, Object> t = SkillStore.byWords(n);
            int c = 0;
            for (Map<String, Object> sp : all) {
                if (!"monitor".equals(SkillStore.str(sp.get("mode")))) continue;
                if (t != null && !SkillStore.str(t.get("name")).equals(SkillStore.str(sp.get("name")))) continue;
                String nm = SkillStore.str(sp.get("name"));
                stopMonitor(nm);
                SkillStore.update(nm, m -> m.put("enabled", false));
                c++;
            }
            return c > 0 ? "Monitoring paused. Say start monitoring whenever you want it back." : null;
        }
        if (anyMonitor && n.matches("^(?:please )?(?:start|resume|restart|turn on|enable)\\b.*\\b(?:monitoring|monitor|watching)\\b.*")) {
            Map<String, Object> t = SkillStore.byWords(n);
            int c = 0;
            for (Map<String, Object> sp : all) {
                if (!"monitor".equals(SkillStore.str(sp.get("mode"))) || !SkillStore.truthy(sp.get("approved"))) continue;
                if (t != null && !SkillStore.str(t.get("name")).equals(SkillStore.str(sp.get("name")))) continue;
                String nm = SkillStore.str(sp.get("name"));
                SkillStore.update(nm, m -> m.put("enabled", true));
                startMonitor(SkillStore.get(nm));
                c++;
            }
            return c > 0 ? "Monitoring resumed." : null;
        }
        java.util.regex.Matcher am = APPROVE_RX.matcher(n);
        if (am.find() && !all.isEmpty()) {
            String arg = am.group(1) == null ? "" : am.group(1).trim();
            Map<String, Object> t = arg.isEmpty() ? null : SkillStore.byWords(arg);
            if (t == null && (arg.isEmpty() || arg.matches("it|that|this|the skill|skill|that skill|this skill")) && !pendingSkill.isEmpty())
                t = SkillStore.get(pendingSkill);
            if (t != null && !SkillStore.truthy(t.get("approved"))) {
                startEarlySpeech(pick("Approved. Running it now.", "Understood. Enabling it."));
                return approveSkill(t);
            }
        }

        // ---- build a new skill ----
        if (SKILL_CREATE_RX.matcher(raw).find()) {
            startEarlySpeech(pick("Understood. Developing that skill now.", "Give me a moment. I'll build that ability.", "On it. Forging a new skill."));
            return createSkillReply(raw);
        }

        // ---- use an installed skill ----
        if (!all.isEmpty()) {
            Map<String, Object> m = SkillStore.match(raw);
            if (m != null) return runSkill(m, raw);
        }
        return null;
    }

    String runSkillByName(String target) {
        Map<String, Object> sp = SkillStore.get(target == null ? "" : target);
        if (sp == null) sp = SkillStore.byWords(target == null ? "" : target);
        return sp == null ? "I don't have a skill called " + target + "." : runSkill(sp, target);
    }

    /* ============================================================
     * SPEAK THE ACKNOWLEDGEMENT NOW, DO THE WORK WHILE TALKING
     * ============================================================ */
    volatile java.util.function.Consumer<String> earlySpeaker;
    private volatile Thread earlyThread;
    private volatile String earlyText;
    private volatile String followUp;

    void startEarlySpeech(final String text) {
        java.util.function.Consumer<String> sp = earlySpeaker;
        if (sp == null || text == null || text.trim().isEmpty()) return;
        earlyText = text.trim();
        Thread t = new Thread(() -> { try { sp.accept(text.trim()); } catch (Throwable ignored) {} }, "proton-early-speech");
        t.setDaemon(true);
        earlyThread = t;
        t.start();
    }

    /** The UI calls this once process() returned: what is still left to say aloud (null = nothing). */
    String remainingSpeech(String reply) {
        Thread t = earlyThread;
        if (t == null) return reply;
        try { t.join(40000); } catch (InterruptedException ignored) {}
        String fu = followUp;
        earlyThread = null; earlyText = null; followUp = null;
        return fu;
    }

    /** Remembers what still has to be said after the early acknowledgement. */
    String closeWith(String reply) {
        String et = earlyText;
        if (et != null && reply != null) {
            String fu = reply.startsWith(et) ? reply.substring(et.length()).trim() : reply;
            followUp = fu.isEmpty() ? null : fu;
        }
        return reply;
    }

    /* ============================================================
     * JARVIS LAYER: instant answers, personality, memory of the user
     * ============================================================ */
    static String pick(String... a) { return a[java.util.concurrent.ThreadLocalRandom.current().nextInt(a.length)]; }

    static String secondPerson(String s) {
        String t = " " + s + " ";
        t = t.replaceAll("(?i)\\bi am\\b", "you are").replaceAll("(?i)\\bi'm\\b", "you're").replaceAll("(?i)\\bi've\\b", "you've")
             .replaceAll("(?i)\\bmyself\\b", "yourself").replaceAll("(?i)\\bmy\\b", "your").replaceAll("(?i)\\bme\\b", "you")
             .replaceAll("(?i)\\bi\\b", "you");
        t = t.trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static String capWords(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    static final java.util.regex.Pattern NAME_RX = java.util.regex.Pattern.compile(
        "(?i)^(?:please |ok |okay )?(?:proton[, ]+)?(?:my name is|my name's|call me|you can call me|i am called|i'm called)\\s+([\\p{L}][\\p{L}'\\- ]{0,24}?)\\s*[.!]?$");
    static final java.util.regex.Pattern REMEMBER_RX = java.util.regex.Pattern.compile(
        "(?i)^(?:please |ok |okay )?(?:proton[, ]+)?remember (?:that )?(.{3,200}?)\\s*[.!]?$");
    static final java.util.regex.Pattern FORGET_RX = java.util.regex.Pattern.compile(
        "(?i)^(?:please |ok |okay )?(?:proton[, ]+)?forget (?:that |about |what i said about )?(.{2,100}?)\\s*[.!]?$");
    static final Set<String> NOT_NAMES = new HashSet<>(Arrays.asList(
        "back", "later", "maybe", "tomorrow", "tonight", "when", "if", "please", "now", "soon", "again", "anything", "whatever"));

    /** Zero-network answers. Returns null when the request needs the real brain. */
    String tryInstant(String userText) {
        if (userText == null) return null;
        String raw = userText.trim().replaceAll("\\s+", " ");
        if (raw.isEmpty()) return null;
        String n = raw.toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "").trim()
                      .replaceFirst("^(?:please |ok |okay )+", "").replaceFirst("^proton[, ]+(?=\\S)", "").trim();
        if (n.isEmpty()) return null;
        String who = UserFacts.name();
        String call = who.isEmpty() ? "" : ", " + who;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.util.regex.Matcher m;

        // ---- things about the user ----
        m = NAME_RX.matcher(raw);
        if (m.find()) {
            String nm = m.group(1).trim();
            boolean filler = false;
            for (String w : nm.toLowerCase(Locale.ROOT).split("\\s+")) if (NOT_NAMES.contains(w)) filler = true;
            if (!filler && nm.split("\\s+").length <= 3) {
                nm = capWords(nm);
                UserFacts.setName(nm);
                return pick("Pleasure to meet you, " + nm + ". I'll remember that.",
                            "Understood, " + nm + ". I'll call you that from now on.");
            }
        }
        if (n.matches("what('s| is) my name|who am i|do you know my name|what do you call me")) {
            return who.isEmpty() ? "I don't know your name yet. Just say, my name is, and then your name."
                                 : "You're " + who + ".";
        }
        m = REMEMBER_RX.matcher(raw);
        if (m.find()) {
            UserFacts.addFact(m.group(1).trim());
            return pick("Noted. I'll remember that" + call + ".", "Got it. That's stored.", "Understood. I won't forget.");
        }
        m = FORGET_RX.matcher(raw);
        if (m.find() && !m.group(1).trim().toLowerCase(Locale.ROOT).matches("it|that|this|about it|about that|everything i said|the last thing")) {
            int gone = UserFacts.forget(m.group(1));
            return gone > 0 ? "Done. It's forgotten." : "I don't have anything stored like that.";
        }
        if (n.matches("what do you (know|remember) about me|what have i told you|what do you remember( about me)?")) {
            List<String> f = UserFacts.facts();
            if (who.isEmpty() && f.isEmpty()) return "Nothing yet. Tell me about yourself and say remember that, and I'll keep it.";
            StringBuilder b = new StringBuilder();
            if (!who.isEmpty()) b.append("Your name is ").append(who).append(". ");
            for (int i = 0; i < Math.min(5, f.size()); i++) b.append(secondPerson(f.get(i).replaceAll("[.!]+$", ""))).append(". ");
            return b.toString().trim();
        }

        // ---- time and date ----
        if (n.length() < 48 && n.matches(".*\\b(what time is it|what'?s the time|what is the time|current time|tell me the time|time right now|time now)\\b.*"))
            return "It's " + now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) + ".";
        if (n.length() < 48 && n.matches(".*\\b(what'?s (the )?date|what is (the )?date|today'?s date|what day is it|what is today|what'?s today|which day is it|current date)\\b.*"))
            return "Today is " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)) + ".";

        // ---- small talk (instant) ----
        if (n.matches("(hello|hi|hey|yo|hey there|hello there|hi there|proton|hey proton|hello proton|hi proton)")) {
            int h = now.getHour();
            String part = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
            return pick(part + call + ". How may I help?", "At your service" + call + ".", "Hello" + call + ". What do you need?");
        }
        m = java.util.regex.Pattern.compile("good (morning|afternoon|evening|night)( proton)?").matcher(n);
        if (m.matches()) {
            if (m.group(1).equals("night")) return "Good night" + call + ". Rest well.";
            return "Good " + m.group(1) + call + ". " + pick("What can I do for you?", "Ready when you are.", "How may I help?");
        }
        if (n.matches("how are you( doing)?( today)?|how'?s it going|how are things|how do you feel"))
            return pick("All systems running smoothly" + call + ". What can I do for you?",
                        "Running at full capacity and ready to help.", "Sharp as ever. What do you need?");
        if (n.matches("(thanks|thank you|thank you so much|thanks a lot|cheers|much appreciated|thank you proton|thanks proton)"))
            return pick("Always a pleasure" + call + ".", "Anytime.", "Happy to help.");
        if (n.matches("who are you|what are you|what is your name|what'?s your name|introduce yourself|tell me about yourself"))
            return "I'm Proton, your personal AI. I can operate your PC, see your screen, search the live web and answer almost anything. Just ask.";
        if (n.matches("what can you do|what are your (abilities|capabilities|features)|what do you do"))
            return "I can open and operate apps with the mouse and keyboard, write code and documents, build presentations, look at your screen, search the live web, control your volume and windows, and answer questions. Try asking me to create a presentation.";

        // ---- exact arithmetic ----
        String mathAns = tryMath(n);
        if (mathAns != null) return mathAns;
        return null;
    }

    /* ---------- arithmetic ---------- */
    static String tryMath(String n) {
        String s = n.replaceFirst("^(?:what is|what's|whats|calculate|compute|solve|how much is|work out|evaluate|tell me|what does|what do)\\s+(?:the\\s+)?", "").trim();
        s = s.replaceFirst("\\s+(?:equal|equals|is)$", "");
        s = s.replaceAll("(\\d),(\\d{3})", "$1$2");
        s = s.replaceAll("square root of ([0-9.]+)", "sqrt($1)");
        s = s.replaceAll("([0-9.]+)\\s*(?:%|percent)\\s+of\\s+([0-9.]+)", "($1/100*$2)");
        s = s.replaceAll("([0-9.]+) squared", "($1^2)").replaceAll("([0-9.]+) cubed", "($1^3)");
        s = s.replaceAll("\\bplus\\b", "+").replaceAll("\\bminus\\b", "-")
             .replaceAll("\\b(?:times|multiplied by|into)\\b", "*").replaceAll("(?<=[0-9)])\\s*x\\s*(?=[0-9(])", "*")
             .replaceAll("\\b(?:divided by|over)\\b", "/").replaceAll("\\bto the power (?:of )?", "^")
             .replaceAll("\\bmod(?:ulo)?\\b", "%").replace("\u00D7", "*").replace("\u00F7", "/");
        String c = s.replaceAll("\\s+", "");
        if (c.length() < 3 || !c.matches("(?:sqrt|[0-9.+\\-*/^%()])+")) return null;
        if (!c.matches(".*[0-9].*") || !c.matches(".*(?:[+\\-*/^%]|sqrt).*")) return null;
        Double v = evalMath(c);
        if (v == null) return null;
        String out = fmtNum(v);
        return pick("It comes to " + out + ".", "That's " + out + ".", "The answer is " + out + ".");
    }

    static String fmtNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) return String.valueOf((long) Math.rint(v));
        return new java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static Double evalMath(String s) {
        try {
            MathP p = new MathP(s);
            double v = p.expr();
            if (p.pos != p.s.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (RuntimeException e) { return null; }
    }

    static final class MathP {
        final String s; int pos = 0;
        MathP(String in) { s = in; }
        double expr() {
            double v = term();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') { pos++; v += term(); } else if (c == '-') { pos++; v -= term(); } else break;
            }
            return v;
        }
        double term() {
            double v = pow();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') { pos++; v *= pow(); }
                else if (c == '/') { pos++; double d = pow(); if (d == 0) throw new ArithmeticException(); v /= d; }
                else if (c == '%') { pos++; double d = pow(); if (d == 0) throw new ArithmeticException(); v %= d; }
                else break;
            }
            return v;
        }
        double pow() {
            double b = unary();
            if (pos < s.length() && s.charAt(pos) == '^') { pos++; return Math.pow(b, pow()); }
            return b;
        }
        double unary() {
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -unary(); }
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; return unary(); }
            return atom();
        }
        double atom() {
            if (s.startsWith("sqrt", pos)) {
                pos += 4;
                double v = atom();
                if (v < 0) throw new ArithmeticException();
                return Math.sqrt(v);
            }
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = expr();
                if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalStateException();
                pos++;
                return v;
            }
            int st = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (st == pos) throw new IllegalStateException();
            return Double.parseDouble(s.substring(st, pos));
        }
    }

    /* ---------- conversation (real answers, not 35-word JSON) ---------- */
    static final java.util.regex.Pattern CHAT_RX = java.util.regex.Pattern.compile(
        "^(?:please )?(?:hey |ok |okay )?(?:proton[, ]+)?(?:what|what's|whats|who|who's|why|how|when|where|which|explain|tell me|describe|define|compare|"
      + "difference between|calculate|solve|translate|summari[sz]e|teach me|give me (?:a|an|some|the)|can you (?:explain|tell|help me understand)|"
      + "could you (?:explain|tell)|is it|is there|are there|are you|do you|does it|did |should i|recommend|suggest|what do you think|"
      + "joke|riddle|motivate|brainstorm|write (?:me )?(?:a|an) (?:poem|story|joke|haiku|limerick)|i want to (?:know|understand|learn)|i wonder|"
      + "help me understand|why is|why does|what would|imagine)\\b");

    boolean isChatLike(String t) {
        if (t == null) return false;
        String n = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (n.length() < 4) return false;
        if (DESKTOP_CMD_RX.matcher(n).find()) return false;
        return CHAT_RX.matcher(n).find();
    }

    static final String PERSONA =
        "You are PROTON, a JARVIS-style AI companion living on the user's PC. Your reply is SPOKEN ALOUD.\n" +
        "- Answer directly and correctly FIRST, with substance: real facts, clear reasoning, concrete numbers and examples.\n" +
        "- Voice: calm, sharp, quietly witty, loyal. Never robotic. Never say 'as an AI' or 'I'm just a language model'.\n" +
        "- Length: 1 to 4 sentences (under about 70 words) for simple questions; up to about 160 words when asked to explain, compare, teach or tell a story.\n" +
        "- Plain spoken English only: no markdown, no lists, no emojis, no URLs. Say numbers and symbols the way a person speaks.\n" +
        "- If you are not sure, say so in a few words instead of guessing. Never invent facts, quotes or statistics.\n" +
        "- You may end with one short, relevant offer or follow-up when it is genuinely useful, but not every time.\n" +
        "- If the user is really asking you to DO something on the computer (open, click, type, write into an app, play, close, change a setting, search the web), reply with exactly [[ACTION]] and nothing else.\n";

    static final java.util.regex.Pattern HARD_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(prove|proof|derive|derivation|analy[sz]e|analysis|optimi[sz]e|algorithm|complexity|big o|step by step|puzzle|riddle|paradox|"
      + "logic|logical|probability|statistics|equation|integral|derivative|matrix|theorem|calculus|debug|design a|architecture|"
      + "trade-?offs?|pros and cons|deduce|infer|estimate|forecast)\\b");

    /** How hard should the model think? Hard problems get full reasoning; quick facts stay fast. */
    static String effortFor(String t) {
        if (t == null) return "medium";
        int words = t.trim().split("\\s+").length;
        if (HARD_RX.matcher(t).find() || words >= 28) return "high";
        return (words <= 7 && t.trim().toLowerCase(Locale.ROOT).matches("^(?:what|what's|who|who's|when|where|which|define)\\b.*")) ? "low" : "medium";
    }

    /** Thoughtful answer path for questions and conversation. Returns null when it is really a PC command. */
    String converse(String userText) throws Exception {
        boolean useNvidia = nvidia && isComplexTask(userText);
        String sys = (useNvidia ? NEMOTRON_PERSONA : PERSONA) + "\nCONTEXT\n- NOW: " + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)) + "\n"
                + UserFacts.promptBlock();
        StringBuilder msgs = new StringBuilder();
        msgs.append("{\"role\":\"system\",\"content\":").append(js(sys)).append("}");
        synchronized (history) {
            for (ChatMsg m : history) {
                if (m.role.equals("user") && m.content.equals(userText)) continue;   // the current turn is appended below
                msgs.append(",{\"role\":\"").append(m.role).append("\",\"content\":").append(js(m.content)).append("}");
            }
        }
        msgs.append(",{\"role\":\"user\",\"content\":").append(js(userText)).append("}");
        String out;
        if (useNvidia) {
            String effort = effortFor(userText);
            try {
                out = cleanComplex(nvidiaStream(main.NVIDIA_MODEL, msgs.toString(),
                        effort.equals("high") ? 4096 : 2600, 0.6));
            } catch (Throwable t) {
                log("nemotron failed, falling back to groq: " + shortStr(String.valueOf(t.getMessage())));
                String body = postFast(msgs.toString(), effort, effort.equals("high") ? 2200 : 900,
                        effort.equals("high") ? 0.4 : 0.6);
                Object content = dig(jparse(body), "choices", 0, "message", "content");
                if (content == null) return null;
                out = cleanSpoken(content.toString());
            }
        } else {
            String effort = effortFor(userText);
            String body = postFast(msgs.toString(), effort, effort.equals("high") ? 2200 : 900, effort.equals("high") ? 0.4 : 0.6);
            Object content = dig(jparse(body), "choices", 0, "message", "content");
            if (content == null) return null;
            out = cleanSpoken(content.toString());
        }
        if (out.contains("[[WEB]]")) return "[[WEB]]";
        if (out.isEmpty() || out.contains("[[ACTION]]")) return null;
        return out;
    }

    /** Reflex results are log-style ("opening Notepad"); turn them into something a person would say. */
    static String jarvisReflex(String r) {
        if (r == null) return null;
        String t = r.replaceFirst("^\\W*Reflex:\\s*", "").trim();
        if (t.isEmpty()) return "Done.";
        String l = t.toLowerCase(Locale.ROOT);
        if (l.startsWith("failed")) {
            String d = t.replaceFirst("(?i)^failed:?\\s*", "").trim();
            return "I couldn't do that" + (d.isEmpty() ? "." : ": " + shortStr(d).replaceAll("[.]+$", "") + ".");
        }
        if (l.startsWith("already open")) return "That's already open, so I've brought it to the front.";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^opening\\s+(.+?)[.\\s]*$").matcher(t);
        if (m.find()) return pick("Right away. ", "On it. ", "Certainly. ", "") + "Opening " + m.group(1) + ".";
        if (t.matches("(?s)^[a-z ]+ \".*\"$")) return pick("Typed it.", "Done.", "There you go.");
        String out = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        if (!out.matches("(?s).*[.!?\"]$")) out += ".";
        return out;
    }

    /* ---------- failures: be honest and try to fix them ---------- */
    final List<String> stepFailures = new ArrayList<>();
    volatile boolean healable = false, planHasGui = false, inHeal = false;

    void noteFailure(Step st, String res) {
        if (res == null || !res.startsWith("failed")) return;
        stepFailures.add(st.action + " " + st.target + ": " + res.replaceFirst("(?i)^failed:?\\s*", ""));
        String a = st.action == null ? "" : st.action;
        if (a.equals("findclick") || a.equals("finddoubleclick") || a.equals("findmove")) healable = true;
    }

    String finishPlan(Plan p, String userText) {
        try {
            if (healable && !planHasGui && !inHeal && isWin() && !stepFailures.isEmpty()) {
                inHeal = true;
                try {
                    log("self-heal: a mouse step failed -> handing over to the GUI agent");
                    String r = doGui("Complete this request on screen: " + userText
                                     + ". An earlier attempt failed at: " + stepFailures.get(0));
                    p.speech = guiSpeech(r, p.speech);
                    if (r != null && r.startsWith("done:")) stepFailures.clear();
                } catch (Throwable t) { log("self-heal failed: " + shortStr(String.valueOf(t.getMessage()))); }
                finally { inHeal = false; }
            }
        } catch (Throwable ignored) {}
        String reply = p.speech == null ? "" : p.speech;
        if (!stepFailures.isEmpty()) {
            String f = shortStr(stepFailures.get(0).replaceFirst("^[a-z]+ ", ""));
            reply = (reply + " One thing didn't go through: " + f.replaceAll("[.]+$", "") + ".").trim();
        }
        remember("assistant", reply);
        return closeWith(reply);
    }

    String chat(String userText) throws Exception {
        String sys = SYSTEM_PROMPT + sessionSnapshot() + "\n==========================================\n";
        StringBuilder msgs = new StringBuilder();
        msgs.append("{\"role\":\"system\",\"content\":").append(js(sys)).append("}");
        synchronized (history) {
            for (ChatMsg m : history)
                msgs.append(",{\"role\":\"").append(m.role)
                    .append("\",\"content\":").append(js(m.content)).append("}");
        }
        msgs.append(",{\"role\":\"user\",\"content\":").append(js(userText)).append("}");
        String content;
        int words = userText == null ? 0 : userText.trim().split("\\s+").length;
        if (nvidia && isComplexTask(userText)) {
            try {
                content = nvidiaStream(main.NVIDIA_MODEL, msgs.toString(), 4096, 0.3);
            } catch (Throwable t) {
                log("nemotron action planner failed, falling back to groq: " + shortStr(String.valueOf(t.getMessage())));
                String raw = postFast(msgs.toString(), words >= 22 ? "medium" : "low",
                        words >= 22 ? 1400 : 900, 0.3);
                Object c = dig(jparse(raw), "choices", 0, "message", "content");
                content = c == null ? null : c.toString();
            }
        } else {
            String raw = postFast(msgs.toString(), words >= 22 ? "medium" : "low", words >= 22 ? 1400 : 900, 0.3);
            Object c = dig(jparse(raw), "choices", 0, "message", "content");
            content = c == null ? null : c.toString();
        }
        if (content == null || content.trim().isEmpty()) throw new Exception("empty model reply");
        return content;
    }

    static final String SYSTEM_PROMPT =
        "You are PROTON, a persistent voice assistant that controls the user's PC with\n" +
        "keyboard, mouse, vision (screenshot analysis) and window management.\n" +
        "Your reply is spoken aloud to the user, so talk naturally and conversationally.\n" +
        "You REMEMBER the current session. Follow-up commands refer to the previous turn.\n" +
        "Reply with ONLY one JSON object. No markdown. No explanation.\n\n" +
        "PERSONALITY (JARVIS)\n" +
        "- Calm, sharp, quietly witty and loyal. Competent first, charming second. Never robotic, never say 'as an AI'.\n" +
        "- Acknowledge actions in 3-12 natural words and vary them ('Right away.', 'On it.', 'Consider it done.', 'Opening that now.').\n" +
        "- If the user's name is in the session block you may use it now and then.\n" +
        "- If a request is ambiguous, pick the most sensible reading, act, and say what you assumed in one clause.\n" +
        "- Choose the shortest RELIABLE plan: one gui step for multi-click app tasks, hotkeys when they are dependable, no pointless extra steps.\n" +
        "- For questions and chat give a real, correct, helpful answer in speech (up to about 80 words) with steps []. Be honest when unsure.\n\n" +
        "Preferred format:\n" +
        "{\"speech\":\"<short reply>\",\"steps\":[{\"action\":\"...\",\"target\":\"...\",\"wait\":2000}, ...]}\n\n" +
        "SESSION MEMORY RULES (VERY IMPORTANT)\n" +
        "- The CURRENT SESSION block below tells you the last app opened, last window focused,\n" +
        "  and what content was last written.\n" +
        "- If the user says 'add more lines', 'continue', 'more', 'expand', 'keep going',\n" +
        "  'append', 'now write about X':\n" +
        "    * DO NOT open a new app. DO NOT press ctrl+n. DO NOT reset the document.\n" +
        "    * Emit {action:'focus', target:'<last window from session>'}\n" +
        "      then {action:'writetext', target:'<clear description of the addition, matching topic>'}\n" +
        "- Only open a new app if the user explicitly names one.\n" +
        "- If the user asks a question or just chats, answer it in speech and use steps [].\n\n" +
        "DICTATION PRIORITY (VERY IMPORTANT)\n" +
        "- If the current foreground window is a chat interface, browser web app, or document editor\n" +
        "  (e.g., Gemini, ChatGPT, Claude, Notepad, VS Code) and the user gives a follow-up utterance\n" +
        "  like 'say <X>', 'type <X>', 'tell it <X>', or 'write <X>', treat it as DICTATION:\n" +
        "  use `type` or `writetext` into the active window rather than responding conversationally in `speech`.\n" +
        "- Example: foreground is \"Gemini - Google Chrome\" and user says \"say hello\"\n" +
        "  -> {\"speech\":\"Typing it.\",\"steps\":[{\"action\":\"type\",\"target\":\"hello\"}]}\n\n" +
        "ACTIONS\n" +
        "openapp    target = app name (any installed app).\n" +
        "openurl    target = full url\n" +
        "search     target = google search query\n" +
        "youtube    target = youtube search query\n" +
        "websearch  target = the user's question; searches the LIVE web and answers aloud (news, scores, prices, weather, recent facts)\n" +
        "bluej      target = the whole request, e.g. write a java program for factorial; opens BlueJ, makes the project and class with the mouse, writes and compiles the code\n" +
        "finddoubleclick target = describe UI element -> vision -> double click\n" +
        "gui        target = the COMPLETE goal inside the open app; Proton then looks at the screen, clicks, types and verifies step by step until it is done\n" +
        "skill      target = the NAME of an INSTALLED SKILL (listed in the session block) that fits the request; Proton runs it and speaks the result\n" +
        "newskill   target = the user's full request when they want a NEW ability, monitor or automation you do not have yet; Proton writes, tests and installs it\n" +
        "type       target = EXACT text typed/pasted into focused window\n" +
        "writecode  target = \"<lang>: <task>\"  -- NEW code buffer\n" +
        "writetext  target = description of prose to write; APPENDS\n" +
        "press      target = one key: enter, esc, tab, space, up, down, left, right, f5\n" +
        "hotkey     target = combo like \"ctrl+c\", \"alt+f4\"\n" +
        "focus      target = substring of a window title\n" +
        "findclick  target = describe UI element -> vision -> click\n" +
        "findmove   target = describe UI element -> move cursor\n" +
        "move       target = \"x,y\" or \"right 200\"\n" +
        "moverel    target = \"right 200\", \"upright 400\"\n" +
        "click      target = \"left\"|\"right\"|\"middle\"\n" +
        "closeapp   target = app/window name to CLOSE (e.g. notepad, chrome, chatgpt, gemini)\n" +
        "listapps   (no target) lists the windows that are open\n" +
        "doubleclick, rightclick, scroll, volume, screenshot, lock, shell, wait, clipread, reset, chat\n\n" +
        "OPEN WINDOWS AWARENESS (VERY IMPORTANT)\n" +
        "- The CURRENT SESSION block lists OPEN WINDOWS. Use it to know what is running.\n" +
        "- If the app or site the user wants is already in OPEN WINDOWS, do NOT open it again: use focus.\n" +
        "- For websites/web apps (ChatGPT, Gemini, Claude, YouTube, Gmail) use exactly ONE openurl step.\n" +
        "  NEVER add openapp chrome/edge/browser before openurl -- that opens two browsers.\n" +
        "- To close something the user names, use closeapp with its name (a browser web app closes only its tab).\n" +
        "- 'type X' / 'say X' into a chat site means paste X only; press enter only if the user says send/submit/ask.\n\n" +
        "LIVE WEB AND MOUSE (VERY IMPORTANT)\n" +
        "- For news, sports scores, weather, prices, releases, who currently holds a job, or anything recent that may have changed:\n" +
        "  emit ONE step websearch with the question as target. Do not guess from memory. Timeless questions: answer in speech with steps [].\n" +
        "- Any BlueJ request (BlueJ may be misheard as bluj or blue j): emit ONE step bluej with the whole request as target.\n" +
        "- If the user asks you to gain, build, learn or develop a new skill / ability / monitor, emit ONE step newskill with their full request.\n" +
        "- If an INSTALLED SKILL fits what the user wants, emit ONE step skill with its name instead of improvising.\n" +
        "- For ANY task that needs several clicks inside an app (create a presentation / document / spreadsheet, change a setting,\n" +
        "  fill a form, export, install ...): emit openapp (only if the app is not already open) and then ONE step gui whose\n" +
        "  target is the full goal in plain words. NEVER guess long chains of findclick steps for this.\n" +
        "- To move to or click an app or icon, use findmove / findclick with a short description such as WPS Office icon.\n" +
        "  The cursor glides like a human hand, and findclick scrolls the window by itself if the element is off-screen.\n\n" +
        "RULES\n" +
        "- speech: about 35 words max when you are doing something; up to 80 words when you are answering a question.\n" +
        "- Between opening an app and next step add wait 2500-4000 on the open step.\n" +
        "- Output ONLY the JSON.\n\n" +
        "EXAMPLE 1 -- \"open chrome and search cats\"\n" +
        "{\"speech\":\"Opening Chrome and searching for cats.\",\"steps\":[\n" +
        "{\"action\":\"openapp\",\"target\":\"chrome\",\"wait\":3000},\n" +
        "{\"action\":\"search\",\"target\":\"cats\"}]}\n\n" +
        "EXAMPLE 2 -- \"open notepad and write an essay on AI\"\n" +
        "{\"speech\":\"Opening Notepad and writing an essay about AI.\",\"steps\":[\n" +
        "{\"action\":\"openapp\",\"target\":\"notepad\",\"wait\":2500},\n" +
        "{\"action\":\"writetext\",\"target\":\"a five paragraph essay on the history and future of artificial intelligence\"}]}\n\n" +
        "EXAMPLE 3 -- follow-up \"add some more lines\"\n" +
        "{\"speech\":\"Adding more lines.\",\"steps\":[\n" +
        "{\"action\":\"focus\",\"target\":\"Notepad\",\"wait\":500},\n" +
        "{\"action\":\"writetext\",\"target\":\"three more paragraphs continuing the essay about AI, same tone\"}]}\n\n" +
        "EXAMPLE 4 -- \"open vs code and write a python fibonacci function\"\n" +
        "{\"speech\":\"Opening VS Code.\",\"steps\":[\n" +
        "{\"action\":\"openapp\",\"target\":\"vs code\",\"wait\":3500},\n" +
        "{\"action\":\"writecode\",\"target\":\"python: the n-th fibonacci number\"}]}\n\n" +
        "EXAMPLE 5 -- \"move mouse right 200 then click\"\n" +
        "{\"speech\":\"Moving the cursor.\",\"steps\":[\n" +
        "{\"action\":\"moverel\",\"target\":\"right 200\"},\n" +
        "{\"action\":\"click\",\"target\":\"left\"}]}\n\n" +
        "EXAMPLE 6 -- \"what is a blockchain\"\n" +
        "{\"speech\":\"A blockchain is a distributed, tamper-resistant ledger.\",\"steps\":[]}\n\n" +
        "EXAMPLE 7 -- \"open chatgpt and type hello\"\n" +
        "{\"speech\":\"Opening ChatGPT and typing hello.\",\"steps\":[\n" +
        "{\"action\":\"openurl\",\"target\":\"https://chatgpt.com\",\"wait\":6000},\n" +
        "{\"action\":\"type\",\"target\":\"hello\"}]}\n\n" +
        "EXAMPLE 8 -- \"close notepad\"\n" +
        "{\"speech\":\"Closing Notepad.\",\"steps\":[\n" +
        "{\"action\":\"closeapp\",\"target\":\"notepad\"}]}\n\n" +
        "EXAMPLE 9 -- \"what is the latest news about ISRO\"\n" +
        "{\"speech\":\"Let me check.\",\"steps\":[{\"action\":\"websearch\",\"target\":\"latest news about ISRO\"}]}\n\n" +
        "EXAMPLE 10 -- \"open bluj and write a code for factorial\"\n" +
        "{\"speech\":\"Opening BlueJ.\",\"steps\":[{\"action\":\"bluej\",\"target\":\"write a java program for factorial\"}]}\n\n" +
        "EXAMPLE 11 -- \"move my mouse cursor to wps office\"\n" +
        "{\"speech\":\"Moving the cursor.\",\"steps\":[{\"action\":\"findmove\",\"target\":\"WPS Office icon\"}]}\n\n" +
        "EXAMPLE 12 -- \"open powerpoint and create a presentation on solar energy\"\n" +
        "{\"speech\":\"Opening PowerPoint and building your presentation.\",\"steps\":[{\"action\":\"openapp\",\"target\":\"PowerPoint\",\"wait\":3000},{\"action\":\"gui\",\"target\":\"create a new blank presentation about solar energy with a title slide and four content slides\"}]}\n\n" +
        "============ CURRENT SESSION ============\n";

    String doWrite(Step s, boolean codeMode) throws Exception {
        String content;
        if (codeMode) {
            content = gen("code", s.target);
        } else {
            String prev = sLastTyped == null ? "" : sLastTyped;
            if (prev.length() > 1500) prev = prev.substring(prev.length() - 1500);
            String spec = s.target;
            if (!prev.isEmpty())
                spec += "\n\n--- PREVIOUS TEXT (continue from here, same style and voice) ---\n" + prev;
            content = gen("text", spec);
        }
        if (!sLastWindow.isEmpty()) {
            try { actFocus(sLastWindow); } catch (Throwable ignored) {}
            Thread.sleep(450);
        }
        if (codeMode) {
            actHotkey("ctrl+n");
            Thread.sleep(500);
        } else {
            Robot kb = new Robot();
            kb.keyPress(KeyEvent.VK_END);   kb.keyRelease(KeyEvent.VK_END);
            Thread.sleep(60);
            kb.keyPress(KeyEvent.VK_ENTER); kb.keyRelease(KeyEvent.VK_ENTER);
            Thread.sleep(80);
        }
        paste(content);
        sLastTyped = content;
        sLastTopic = s.target;
        return codeMode
                ? "wrote " + content.split("\n", -1).length + " lines of code"
                : "wrote " + content.split("\n", -1).length + " lines of text";
    }

    String gen(String kind, String spec) throws Exception {
        String sys = "code".equals(kind)
            ? "You are a code writer. Output ONLY source code. No fences. No prose."
            : "You are a prose writer. Output ONLY the requested text. No fences. Match prior tone if given.";
        String user = "Write " + kind + " for: " + spec + "\nReply with only the raw content.";
        String msgs = "{\"role\":\"system\",\"content\":" + js(sys) + "},{"
                + "\"role\":\"user\",\"content\":" + js(user) + "}";
        String out;
        if (nvidia) {
            try {
                if ("code".equals(kind)) {
                    out = cleanComplex(nvidiaStream(main.NVIDIA_MODEL, msgs, 4096, 0.3));
                } else {
                    String body = nvidiaComplete(main.NVIDIA_MODEL, msgs, 3000, 0.5);
                    Object content = dig(jparse(body), "choices", 0, "message", "content");
                    if (content == null) throw new Exception("empty reply");
                    out = content.toString().trim();
                }
            } catch (Throwable t) {
                log("nemotron gen failed, falling back to groq: " + shortStr(String.valueOf(t.getMessage())));
                out = groqGen(msgs);
            }
        } else {
            out = groqGen(msgs);
        }
        if (out.startsWith("```")) {
            int nl = out.indexOf('\n');
            if (nl > 0) out = out.substring(nl + 1);
            int end = out.lastIndexOf("```");
            if (end > 0) out = out.substring(0, end);
            out = out.trim();
        }
        return out;
    }

    String groqGen(String msgs) throws Exception {
        String body = postJson("/chat/completions",
                "{\"model\":" + js(CHAT_MODEL) + ",\"messages\":[" + msgs + "]}");
        Object content = dig(jparse(body), "choices", 0, "message", "content");
        if (content == null) throw new Exception("empty reply");
        return content.toString().trim();
    }

    /* ============================================================
     * WEB SEARCH -- Gemini-style: search live, read, answer aloud
     * ============================================================ */
    static final java.util.regex.Pattern WEB_EXPLICIT_RX = java.util.regex.Pattern.compile(
        "\\b(search|look|find|check|browse)\\s+(it\\s+|this\\s+|that\\s+)?(up\\s+)?(on\\s+|in\\s+)?(the\\s+)?(web|internet|online)\\b"
      + "|\\bweb search\\b|\\bsearch online\\b|\\blook ?up\\b|\\bfind out\\b|\\bfrom the (web|internet)\\b");
    static final java.util.regex.Pattern WEB_FRESH_RX = java.util.regex.Pattern.compile(
        "\\b(latest|news|headlines?|breaking|today'?s?|tonight|yesterday|tomorrow|currently|current|right now|"
      + "this (week|month|year|weekend)|recent(ly)?|scores?|weather|forecast|temperature|stock|share price|"
      + "price of|exchange rate|who won|who is the|who'?s the|release date|trending|standings|fixtures?|"
      + "what happened|what'?s happening|what'?s new|when (is|does|did|will)|how much (is|does|are)|"
      + "box office|election|ipl|world cup|20(2[4-9]|3\\d))\\b");
    static final java.util.regex.Pattern WEB_QUESTION_RX = java.util.regex.Pattern.compile(
        "^(please )?(hey |ok |okay )?(proton )?(what|what'?s|whats|who|who'?s|when|where|which|how|is|are|does|did|will|can you tell|tell me|give me|any|show me)\\b");
    static final java.util.regex.Pattern DESKTOP_CMD_RX = java.util.regex.Pattern.compile(
        "^(please )?(hey |ok |okay )?(proton )?(open|close|quit|launch|start|click|double click|right click|type|press|play|go to|write|take a screenshot|screenshot|save|move|scroll|focus|say|volume|mute|unmute|lock|youtube)\\b");

    static boolean isWebQuestion(String text) {
        if (text == null) return false;
        String n = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (n.length() < 6) return false;
        if (BLUEJ_RX.matcher(n).find()) return false;
        if (DESKTOP_CMD_RX.matcher(n).find()) return false;
        if (WEB_EXPLICIT_RX.matcher(n).find()) return true;
        if (isVisionQuestion(n)) return false;
        return WEB_QUESTION_RX.matcher(n).find() && WEB_FRESH_RX.matcher(n).find();
    }

    static String cleanWebQuery(String t) {
        String q = t.trim();
        q = q.replaceFirst("(?i)^(please\\s+)?(hey\\s+|ok\\s+|okay\\s+)?(proton[,\\s]+)?", "");
        q = q.replaceFirst("(?i)^(search|look|find|check|browse)\\s+(it\\s+)?(up\\s+)?(on\\s+|in\\s+)?(the\\s+)?(web|internet|online)\\s*(for|about)?\\s*", "");
        q = q.replaceFirst("(?i)^(web search|search online|look ?up|find out)\\s*(for|about)?\\s*", "");
        q = q.trim();
        return q.isEmpty() ? t.trim() : q;
    }

    static final String WEB_SYS =
        "You are PROTON, a voice assistant with live web search. Search the web, then answer the question " +
        "with the freshest facts you find. Reply in plain spoken English: at most 3 short sentences, " +
        "under 70 words, no markdown, no bullet points, no URLs, no citation markers. " +
        "Name the source briefly, for example 'according to BBC'. If sources disagree or you are unsure, say so. ";

    final Set<String> compoundDead = new HashSet<>();

    /** Gemini-style answer: Groq's built-in web-search models first, then DuckDuckGo + page reading. */
    String webAnswer(String question) {
        String q = cleanWebQuery(question == null ? "" : question);
        String today = new java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date());
        log("web search: " + q);
        for (String m : new String[]{"groq/compound-mini", "groq/compound"}) {
            if (compoundDead.contains(m)) continue;
            try {
                String a = cleanSpoken(webModelAnswer(m, q, today));
                if (!a.isEmpty()) return a;
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                log("web model " + m + " failed: " + shortStr(msg));
                if (msg.contains("404") || msg.contains("model_not_found") || msg.contains("does not exist")
                        || msg.contains("403") || msg.contains("permission")) compoundDead.add(m);
            }
        }
        try {
            String a = cleanSpoken(scrapedAnswer(q, today));
            if (!a.isEmpty()) return a;
        } catch (Throwable t) { log("web scrape failed: " + shortStr(String.valueOf(t.getMessage()))); }
        return "Sorry, I couldn't reach the web right now.";
    }

    String webModelAnswer(String model, String q, String today) throws Exception {
        String sys = WEB_SYS + "Today is " + today + ".";
        String json = "{\"model\":" + js(model) + ",\"messages\":[{\"role\":\"system\",\"content\":" + js(sys)
                + "},{\"role\":\"user\",\"content\":" + js(q) + "}]}";
        Object content = dig(jparse(postJson("/chat/completions", json)), "choices", 0, "message", "content");
        if (content == null) throw new Exception("empty web reply");
        return content.toString();
    }

    String scrapedAnswer(String q, String today) throws Exception {
        List<String[]> hits = ddgSearch(q, 6);
        if (hits.isEmpty()) throw new Exception("no search results");
        StringBuilder ctx = new StringBuilder();
        int n = 0;
        for (String[] h : hits) {
            n++;
            ctx.append('[').append(n).append("] ").append(h[0]).append(" (").append(hostOf(h[1])).append(")\n");
            if (!h[2].isEmpty()) ctx.append(h[2]).append('\n');
            if (n <= 3 && !h[1].toLowerCase(Locale.ROOT).matches(".*(youtube\\.com|youtu\\.be|\\.pdf).*")) {
                String page = fetchPageText(h[1], 2200);
                if (!page.isEmpty()) ctx.append(page).append('\n');
            }
            ctx.append('\n');
        }
        String ctxS = ctx.length() > 12000 ? ctx.substring(0, 12000) : ctx.toString();
        String sys = WEB_SYS + "Today is " + today + ". Use ONLY the search results below.";
        String user = "Question: " + q + "\n\nSEARCH RESULTS:\n" + ctxS;
        String json = "{\"model\":" + js(CHAT_MODEL) + ",\"messages\":[{\"role\":\"system\",\"content\":" + js(sys)
                + "},{\"role\":\"user\",\"content\":" + js(user) + "}]}";
        Object content = dig(jparse(postJson("/chat/completions", json)), "choices", 0, "message", "content");
        if (content == null) throw new Exception("empty answer");
        return content.toString();
    }

    static final String WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** Returns {title, url, snippet} rows from DuckDuckGo's HTML endpoint. */
    List<String[]> ddgSearch(String q, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                URI.create("https://html.duckduckgo.com/html/?q=" + enc(q)).toURL().openConnection();
        c.setRequestProperty("User-Agent", WEB_UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setConnectTimeout(8000); c.setReadTimeout(10000);
        String html = readStr(c.getInputStream());
        java.util.regex.Pattern pa = java.util.regex.Pattern.compile(
                "<a\\s[^>]*class=\"result__a\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern ph = java.util.regex.Pattern.compile("href=\"([^\"]+)\"");
        java.util.regex.Pattern ps = java.util.regex.Pattern.compile(
                "class=\"result__snippet\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL);
        List<int[]> spans = new ArrayList<>();
        java.util.regex.Matcher ma = pa.matcher(html);
        while (ma.find()) spans.add(new int[]{ma.start(), ma.end()});
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < spans.size() && out.size() < max; i++) {
            String tag = html.substring(spans.get(i)[0], spans.get(i)[1]);
            int gt = tag.indexOf('>');
            java.util.regex.Matcher mh = ph.matcher(gt > 0 ? tag.substring(0, gt) : tag);
            if (!mh.find()) continue;
            String url = ddgTarget(mh.group(1));
            if (url.contains("duckduckgo.com/y.js") || !url.startsWith("http")) continue;
            String title = stripHtml(gt > 0 ? tag.substring(gt + 1) : tag);
            int regionEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : html.length();
            String snippet = "";
            java.util.regex.Matcher sm = ps.matcher(html.substring(spans.get(i)[1], regionEnd));
            if (sm.find()) snippet = stripHtml(sm.group(1));
            out.add(new String[]{title, url, snippet});
        }
        return out;
    }

    static String ddgTarget(String href) {
        String h = href.replace("&amp;", "&");
        int i = h.indexOf("uddg=");
        if (i >= 0) {
            String v = h.substring(i + 5);
            int e = v.indexOf('&');
            if (e >= 0) v = v.substring(0, e);
            try { return java.net.URLDecoder.decode(v, "UTF-8"); } catch (Exception ex) { return v; }
        }
        if (h.startsWith("//")) return "https:" + h;
        return h;
    }

    static String hostOf(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? "" : h.replaceFirst("^www\\.", ""); }
        catch (Exception e) { return ""; }
    }

    static String stripHtml(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
             .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">");
        return s.replaceAll("\\s+", " ").trim();
    }

    String fetchPageText(String url, int max) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestProperty("User-Agent", WEB_UA);
            c.setConnectTimeout(5000); c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() >= 400) return "";
            String ct = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
            if (!ct.contains("text/html") && !ct.contains("text/plain")) return "";
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0 && b.size() < 400000) b.write(buf, 0, n);
            in.close();
            String t = stripHtml(new String(b.toByteArray(), StandardCharsets.UTF_8));
            return t.length() > max ? t.substring(0, max) : t;
        } catch (Throwable t) { return ""; }
    }

    static String cleanSpoken(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?s)<think>.*?</think>", "");
        s = s.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "$1");
        s = s.replaceAll("https?://\\S+", "");
        s = s.replaceAll("\\x{3010}[^\\x{3011}]*\\x{3011}", "");
        s = s.replaceAll("[*#`>]+", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    /* ============================================================
     * BLUEJ -- its UI needs real mouse work: menus, toolbar, dialogs
     * ============================================================ */
    static final java.util.regex.Pattern BLUEJ_RX =
        java.util.regex.Pattern.compile("\\b(blue\\s?j(ay)?|blu\\s?j)\\b");

    static boolean isBlueJTask(String text) {
        if (text == null) return false;
        String n = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!BLUEJ_RX.matcher(n).find()) return false;
        if (n.matches("^(please )?(close|quit|exit|kill)\\b.*")) return false;
        return n.matches(".*\\b(write|code|program|create|make|class|java|generate|build|type)\\b.*");
    }

    /** Spoken/typed variants ("bluj", "blue j") -> the real app name. */
    static String fixAppAlias(String t) {
        if (t == null) return null;
        String n = t.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+(app|application|ide)$", "");
        return BLUEJ_RX.matcher(n).matches() ? "BlueJ" : t;
    }

    String doBlueJ(String task) throws Exception {
        // 1) Ask the writer model for ONE complete, BlueJ-friendly class.
        String spec = "Request: " + task + "\n"
            + "Write ONE complete, compilable Java class for BlueJ. No package statement. "
            + "Use a descriptive public class name. Add a main method with sample output when it makes sense. "
            + "Ignore any mention of BlueJ or of opening apps -- output only the class source.";
        String code = gen("code", spec);
        code = code.replaceAll("(?m)^\\s*package\\s+[\\w.]+\\s*;\\s*\\r?\\n?", "").trim();
        String cls = "MyProgram";
        java.util.regex.Matcher cm = java.util.regex.Pattern
            .compile("public\\s+(?:final\\s+|abstract\\s+)?(?:class|interface|enum)\\s+([A-Za-z_]\\w*)").matcher(code);
        if (cm.find()) cls = cm.group(1);
        else {
            cm = java.util.regex.Pattern.compile("(?:class|interface|enum)\\s+([A-Za-z_]\\w*)").matcher(code);
            if (cm.find()) cls = cm.group(1);
        }

        // 2) Open (or focus) BlueJ and wait for its window.
        abortCheck();
        String win = findOpenWindow("bluej");
        if (win == null) {
            actOpenApp("BlueJ", task);
            long dl = System.currentTimeMillis() + 45000;
            while (win == null && System.currentTimeMillis() < dl) {
                abortCheck();
                Thread.sleep(900);
                win = findOpenWindow("bluej");
            }
            if (win == null) return "I couldn't get BlueJ to open. Is it installed?";
            sleepAbortable(3500);   // let the UI finish drawing
        }
        try { actFocus(win); } catch (Throwable ignored) {}
        sLastApp = "BlueJ"; sLastWindow = "BlueJ";
        Thread.sleep(900);

        // 3) No project open yet -> Project > New Project...
        String title = findOpenWindow("bluej");
        boolean hasProject = title != null && title.matches("(?is).*bluej\\s*[:\\u2014\\u2013-].*");
        if (!hasProject) {
            String proj = cls + "Project";
            actFindClick("the 'Project' menu in the menu bar at the top of the BlueJ window");
            Thread.sleep(700);
            actFindClick("the 'New Project...' item in the open Project drop-down menu");
            Thread.sleep(1800);
            actHotkey("ctrl+a");
            paste(proj);
            Thread.sleep(400);
            actPress("enter");
            long dl = System.currentTimeMillis() + 12000;
            String pw = null;
            while (pw == null && System.currentTimeMillis() < dl) {
                abortCheck();
                Thread.sleep(700);
                pw = findOpenWindow(proj);
            }
            if (pw == null) {   // dialog still open? press its Create button
                try { actFindClick("the 'Create' button in the dialog"); } catch (Throwable ignored) {}
                Thread.sleep(2500);
            }
        }

        // 4) New class via the left toolbar button.
        abortCheck();
        actFindClick("the 'New Class...' button in the left-hand toolbar of the BlueJ project window");
        Thread.sleep(1500);
        actHotkey("ctrl+a");
        paste(cls);
        Thread.sleep(400);
        actPress("enter");
        Thread.sleep(2000);

        // 5) Double-click the new class box to open its editor.
        abortCheck();
        actFindDoubleClick("the new class box labelled '" + cls + "' in the BlueJ project diagram");
        Thread.sleep(2800);

        // 6) Replace the template with the generated code, save, compile.
        try { actFindClick("the source-code text area of the open class editor window"); } catch (Throwable ignored) {}
        Thread.sleep(300);
        actHotkey("ctrl+a");
        Thread.sleep(150);
        paste(code);
        Thread.sleep(700);
        actHotkey("ctrl+s");
        Thread.sleep(500);
        actHotkey("ctrl+k");
        sLastTyped = code; sLastTopic = task;
        try {
            String nk = lastMemKey;
            if (nk != null && !nk.isEmpty()) {
                ProtonMemory.addNote(nk, "Ctrl+S saves the open class and Ctrl+K compiles it.");
                ProtonMemory.addNote(nk, "To start a program: Project > New Project..., then the New Class... button in the left toolbar, then double-click the class box to open its editor.");
            }
        } catch (Throwable ignored) {}
        return "Done. I opened BlueJ, created the class " + cls + ", wrote the code and compiled it.";
    }

    String runAction(String action, String target, String original) {
        try {
            if (action == null) action = "";
            if (needsDock(action)) ensureDocked(action, target);
            switch (action) {
                case "openapp":     return actOpenApp(target, original);
                case "closeapp":    return actCloseApp(target);
                case "listapps":    return actListApps();
                case "openurl":     return actOpenUrl(target);
                case "search":      return actOpenUrl("https://www.google.com/search?q=" + enc(target));
                case "youtube":     return actOpenUrl("https://www.youtube.com/results?search_query=" + enc(target));
                case "type":        return actType(target);
                case "press":       return actPress(target);
                case "hotkey":      return actHotkey(target);
                case "volume":      return actVolume(target);
                case "screenshot":  return actScreenshot();
                case "lock":        return actLock();
                case "shell":       return actShell(target);
                case "wait":        return actWait(target);
                case "focus":       return actFocusMem(target);
                case "move":        return actMove(target);
                case "moverel":     return actMoveRel(target);
                case "mousepos":    return actMousePos();
                case "click":       return actClick(target);
                case "doubleclick": return actDoubleClick(target);
                case "rightclick":  return actRightClick(target);
                case "scroll":      return actScroll(target);
                case "clipread":    return actClipRead();
                case "findclick":   return actFindClick(target);
                case "findmove":    return actFindMove(target);
                case "finddoubleclick": return actFindDoubleClick(target);
                case "websearch":   return webAnswer(target);
                case "bluej":       return doBlueJ(target);
                case "gui":         return doGui(target);
                case "skill":       return runSkillByName(target);
                case "newskill":    return createSkillReply(target);
                case "reset":       clearSession(); return "session cleared";
                default:            return null;
            }
        } catch (Throwable e) { return "failed: " + e.getMessage(); }
    }

    String actOpenApp(String t, String sentence) {
        t = fixAppAlias(t);
        String already = focusIfOpen(t);
        if (already != null) return already;
        String r = AppIndex.open(t, sentence);
        if (r != null && r.startsWith("opening")) {
            waitAppReady(t);
            sLastApp = t == null ? "" : t;
            String g = sLastApp.toLowerCase(Locale.ROOT);
            if (g.contains("code"))         sLastWindow = "Visual Studio Code";
            else if (g.contains("chrome"))  sLastWindow = "Chrome";
            else if (g.contains("notepad")) sLastWindow = "Notepad";
            else if (g.contains("edge"))    sLastWindow = "Edge";
            else                            sLastWindow = sLastApp;
        }
        return r;
    }

    String actFocusMem(String title) throws Exception {
        String r = actFocus(title);
        if (r != null && r.startsWith("focused")) sLastWindow = title;
        return r;
    }

    String actType(String text) throws Exception {
        if (text == null || text.isEmpty()) return "nothing to type";
        paste(text);
        sLastTyped = text;
        sLastTopic = text.length() > 80 ? text.substring(0, 80) + "..." : text;
        return "typed";
    }

    void paste(String text) throws Exception {
        Toolkit.getDefaultToolkit().getSystemClipboard()
               .setContents(new StringSelection(text), null);
        Thread.sleep(180);
        Robot r = new Robot();
        r.keyPress(KeyEvent.VK_CONTROL); Thread.sleep(40);
        r.keyPress(KeyEvent.VK_V);
        r.keyRelease(KeyEvent.VK_V);     Thread.sleep(40);
        r.keyRelease(KeyEvent.VK_CONTROL);
    }

    String actWait(String ms) throws Exception {
        long n; try { n = Long.parseLong(ms.trim()); } catch (Exception e) { n = 1000; }
        if (n < 0) n = 0;
        if (n > 60000) n = 60000;
        Thread.sleep(n);
        return "waited " + n + "ms";
    }

    String actOpenUrl(String url) throws Exception {
        String u = (url == null ? "" : url.trim());
        if (u.isEmpty()) return "no url";
        String wk = wellKnownWebApp(u);
        if (wk != null) u = wk;
        if (!u.startsWith("http") && !u.startsWith("file:")) u = "https://" + u;
        // Already open in a browser tab? Focus it -- never spawn a second window/tab.
        String webTitle = webTitleForUrl(u);
        if (webTitle != null) {
            try {
                String title = findOpenWindow(webTitle);
                if (title != null) {
                    String f = actFocus(title);
                    if (f != null && f.startsWith("focused")) {
                        sLastApp = webTitle; sLastWindow = webTitle;
                        return "already open - focused " + webTitle;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(new URI(u));
        } else if (isWin()) {
            Runtime.getRuntime().exec(new String[]{"cmd","/c","start","",u});
        } else if (isMac()) {
            Runtime.getRuntime().exec(new String[]{"open", u});
        } else {
            Runtime.getRuntime().exec(new String[]{"xdg-open", u});
        }
        if (webTitle != null) { sLastApp = webTitle; sLastWindow = webTitle; }
        else if (sLastWindow.isEmpty()) { sLastApp = "browser"; sLastWindow = "Chrome"; }
        return "opened " + u;
    }

    String actPress(String key) throws Exception {
        int c = keyCode(key);
        if (c == 0) return "unknown key: " + key;
        Robot r = new Robot();
        r.keyPress(c); Thread.sleep(45); r.keyRelease(c);
        return "pressed " + key;
    }

    String actHotkey(String combo) throws Exception {
        if (combo == null || combo.isEmpty()) return "no combo";
        String[] parts = combo.toLowerCase().split("\\+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            codes[i] = keyCode(parts[i].trim());
            if (codes[i] == 0) return "unknown key: " + parts[i];
        }
        Robot r = new Robot();
        for (int c : codes) r.keyPress(c);
        Thread.sleep(70);
        for (int i = codes.length - 1; i >= 0; i--) r.keyRelease(codes[i]);
        return "hotkey " + combo;
    }

    int keyCode(String name) {
        if (name == null) return 0;
        String n = name.toLowerCase().trim();
        switch (n) {
            case "enter": case "return":   return KeyEvent.VK_ENTER;
            case "esc": case "escape":     return KeyEvent.VK_ESCAPE;
            case "tab":                    return KeyEvent.VK_TAB;
            case "space":                  return KeyEvent.VK_SPACE;
            case "backspace": case "back": return KeyEvent.VK_BACK_SPACE;
            case "delete": case "del":     return KeyEvent.VK_DELETE;
            case "up": return KeyEvent.VK_UP;     case "down": return KeyEvent.VK_DOWN;
            case "left": return KeyEvent.VK_LEFT; case "right": return KeyEvent.VK_RIGHT;
            case "home": return KeyEvent.VK_HOME; case "end": return KeyEvent.VK_END;
            case "pageup": return KeyEvent.VK_PAGE_UP; case "pagedown": return KeyEvent.VK_PAGE_DOWN;
            case "ctrl": case "control":   return KeyEvent.VK_CONTROL;
            case "shift":                  return KeyEvent.VK_SHIFT;
            case "alt":                    return KeyEvent.VK_ALT;
            case "cmd": case "meta": case "win": case "super": return KeyEvent.VK_WINDOWS;
            case "f1": return KeyEvent.VK_F1;   case "f2": return KeyEvent.VK_F2;
            case "f3": return KeyEvent.VK_F3;   case "f4": return KeyEvent.VK_F4;
            case "f5": return KeyEvent.VK_F5;   case "f6": return KeyEvent.VK_F6;
            case "f7": return KeyEvent.VK_F7;   case "f8": return KeyEvent.VK_F8;
            case "f9": return KeyEvent.VK_F9;   case "f10": return KeyEvent.VK_F10;
            case "f11": return KeyEvent.VK_F11; case "f12": return KeyEvent.VK_F12;
            default: break;
        }
        if (n.length() == 1) {
            char c = n.charAt(0);
            if (c >= 'a' && c <= 'z') return KeyEvent.VK_A + (c - 'a');
            if (c >= '0' && c <= '9') return KeyEvent.VK_0 + (c - '0');
        }
        return 0;
    }

    static final int VK_VOLUME_MUTE = 0xAD, VK_VOLUME_DOWN = 0xAE, VK_VOLUME_UP = 0xAF;

    String actVolume(String dir) throws Exception {
        String d = (dir == null ? "" : dir.toLowerCase().trim());
        int c; int t = 5;
        if (d.equals("up") || d.equals("raise"))          c = VK_VOLUME_UP;
        else if (d.equals("down") || d.equals("lower"))   c = VK_VOLUME_DOWN;
        else if (d.equals("mute") || d.equals("unmute"))  { c = VK_VOLUME_MUTE; t = 1; }
        else return "unknown volume: " + d;
        Robot r = new Robot();
        for (int i = 0; i < t; i++) { r.keyPress(c); r.keyRelease(c); Thread.sleep(45); }
        return "volume " + d;
    }

    String actScreenshot() throws Exception {
        Rectangle vb = virtualBounds();
        BufferedImage img = new Robot().createScreenCapture(vb);
        File out = new File(System.getProperty("user.home"),
                            "proton_shot_" + System.currentTimeMillis() + ".png");
        ImageIO.write(img, "png", out);
        return "screenshot: " + out.getName();
    }

    String actLock() throws Exception {
        if (isWin())      Runtime.getRuntime().exec(new String[]{"rundll32", "user32.dll,LockWorkStation"});
        else if (isMac()) Runtime.getRuntime().exec(new String[]{"pmset","displaysleepnow"});
        else              Runtime.getRuntime().exec(new String[]{"loginctl","lock-session"});
        return "screen locked";
    }

    String actShell(String cmd) throws Exception {
        if (cmd == null || cmd.trim().isEmpty()) return "no command";
        ProcessBuilder pb = isWin()
                ? new ProcessBuilder("cmd","/c",cmd)
                : new ProcessBuilder("/bin/sh","-c",cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readStr(p.getInputStream()).trim();
        p.waitFor();
        if (out.length() > 400) out = out.substring(0, 400) + "...";
        return out.isEmpty() ? "done" : out;
    }

    static Rectangle virtualBounds() {
        Rectangle r = null;
        try {
            for (GraphicsDevice gd :
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = gd.getDefaultConfiguration().getBounds();
                r = (r == null) ? b : r.union(b);
            }
        } catch (Throwable ignored) {}
        if (r == null) r = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        return r;
    }

    int[] parseXY(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("[,\\sx]+");
        if (p.length < 2) return null;
        try { return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())}; }
        catch (Exception e) { return null; }
    }

    int[] parseDir(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        String[] w = t.split(" ");
        String dir = w[0];
        if ((dir.equals("mouse") || dir.equals("cursor") || dir.equals("the")
             || dir.equals("move") || dir.equals("nudge") || dir.equals("shift"))
            && w.length >= 2) dir = w[1];
        int amt = 100;
        for (String x : w) {
            try { amt = Integer.parseInt(x); break; } catch (Exception ignored) {}
        }
        switch (dir) {
            case "right": return new int[]{amt, 0};
            case "left":  return new int[]{-amt, 0};
            case "up":    return new int[]{0, -amt};
            case "down":  return new int[]{0, amt};
            case "upright": case "up-right": case "topright": case "top-right": return new int[]{amt, -amt};
            case "upleft": case "up-left": case "topleft": case "top-left": return new int[]{-amt, -amt};
            case "downright": case "down-right": case "bottomright": case "bottom-right": return new int[]{amt, amt};
            case "downleft": case "down-left": case "bottomleft": case "bottom-left": return new int[]{-amt, amt};
            default: return null;
        }
    }

    /* ============================================================
     * HUMAN-LIKE MOUSE: curved path, ease-in / ease-out, hand tremor
     * ============================================================ */
    final java.util.Random humanRnd = new java.util.Random();

    void abortCheck() throws CompanionAbort {
        CompanionHooks h = companion;
        if (h != null && h.isAborted()) throw new CompanionAbort();
    }

    /** Glides the real cursor to (tx,ty) along a slightly curved, eased path (about 0.6s - 2.8s). */
    void humanMove(int tx, int ty) throws Exception {
        Robot r = new Robot();
        Rectangle vb = virtualBounds();
        tx = Math.max(vb.x, Math.min(vb.x + vb.width  - 1, tx));
        ty = Math.max(vb.y, Math.min(vb.y + vb.height - 1, ty));
        Point p0;
        try { p0 = MouseInfo.getPointerInfo().getLocation(); }
        catch (Throwable t) { r.mouseMove(tx, ty); return; }
        double x0 = p0.x, y0 = p0.y;
        double dx = tx - x0, dy = ty - y0;
        double dist = Math.hypot(dx, dy);
        if (dist < 3) { r.mouseMove(tx, ty); return; }

        // Fitts-style duration: longer travel = longer glide.
        double dur = 600 + 380 * (Math.log(1 + dist / 60.0) / Math.log(2));
        dur = Math.min(2800, dur) * (0.9 + humanRnd.nextDouble() * 0.25);

        double nx = -dy / dist, ny = dx / dist;                       // unit normal to the path
        double bend = (humanRnd.nextDouble() - 0.5) * Math.min(dist * 0.30, 160);
        double c1x = x0 + dx * 0.30 + nx * bend,        c1y = y0 + dy * 0.30 + ny * bend;
        double c2x = x0 + dx * 0.72 + nx * bend * 0.55, c2y = y0 + dy * 0.72 + ny * bend * 0.55;

        long t0 = System.nanoTime();
        while (true) {
            abortCheck();
            double el = (System.nanoTime() - t0) / 1e6;
            double t = Math.min(1.0, el / dur);
            double e = t * t * t * (t * (t * 6 - 15) + 10);           // slow start, slow landing
            double u = 1 - e;
            double x = u * u * u * x0 + 3 * u * u * e * c1x + 3 * u * e * e * c2x + e * e * e * tx;
            double y = u * u * u * y0 + 3 * u * u * e * c1y + 3 * u * e * e * c2y + e * e * e * ty;
            if (t < 0.85) {                                           // tiny hand tremor, none while landing
                x += (humanRnd.nextDouble() - 0.5) * 1.2;
                y += (humanRnd.nextDouble() - 0.5) * 1.2;
            }
            r.mouseMove((int) Math.round(x), (int) Math.round(y));
            if (t >= 1.0) break;
            Thread.sleep(7);
        }
        r.mouseMove(tx, ty);
        Thread.sleep(90 + humanRnd.nextInt(110));                     // settle like a real hand
    }

    /** One wheel notch at a time with uneven timing (positive = down). */
    void smoothScroll(int notches) throws Exception {
        Robot r = new Robot();
        int dir = notches < 0 ? -1 : 1;
        for (int i = 0; i < Math.abs(notches); i++) {
            abortCheck();
            r.mouseWheel(dir);
            Thread.sleep(70 + humanRnd.nextInt(80));
        }
    }

    String actMove(String t) throws Exception {
        if (t == null || t.trim().isEmpty()) return "no target";
        int[] a = parseXY(t);
        if (a != null) {
            Rectangle vb = virtualBounds();
            int nx = Math.max(vb.x, Math.min(vb.x + vb.width  - 1, a[0]));
            int ny = Math.max(vb.y, Math.min(vb.y + vb.height - 1, a[1]));
            humanMove(nx, ny);
            return "moved to " + nx + "," + ny;
        }
        int[] d = parseDir(t);
        if (d != null) return doMoveRel(d[0], d[1], t);
        return "invalid move: " + t;
    }

    String actMoveRel(String t) throws Exception {
        if (t == null || t.trim().isEmpty()) return "no direction";
        int[] d = parseDir(t);
        if (d == null) return "unknown direction: " + t;
        return doMoveRel(d[0], d[1], t);
    }

    String doMoveRel(int dx, int dy, String label) throws Exception {
        Point p;
        try { p = MouseInfo.getPointerInfo().getLocation(); }
        catch (Throwable t) { return "cannot read cursor"; }
        Rectangle vb = virtualBounds();
        int nx = Math.max(vb.x, Math.min(vb.x + vb.width  - 1, p.x + dx));
        int ny = Math.max(vb.y, Math.min(vb.y + vb.height - 1, p.y + dy));
        humanMove(nx, ny);
        return "moved " + label + "  " + p.x + "," + p.y + " -> " + nx + "," + ny;
    }

    String actMousePos() {
        try { Point p = MouseInfo.getPointerInfo().getLocation();
              return "cursor at " + p.x + "," + p.y;
        } catch (Throwable t) { return "cursor unknown"; }
    }

    String actClick(String which) throws Exception {
        String w = (which == null ? "left" : which.toLowerCase().trim());
        Robot r = new Robot();
        if (w.startsWith("right")) {
            r.mousePress(InputEvent.BUTTON3_DOWN_MASK); Thread.sleep(60);
            r.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
            return "right-clicked";
        }
        if (w.startsWith("mid")) {
            r.mousePress(InputEvent.BUTTON2_DOWN_MASK); Thread.sleep(60);
            r.mouseRelease(InputEvent.BUTTON2_DOWN_MASK);
            return "middle-clicked";
        }
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(60);
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        return "clicked";
    }

    String actDoubleClick(String which) throws Exception {
        Robot r = new Robot();
        for (int i = 0; i < 2; i++) {
            r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(40);
            r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            if (i == 0) Thread.sleep(80);
        }
        return "double-clicked";
    }

    String actRightClick(String which) throws Exception {
        Robot r = new Robot();
        r.mousePress(InputEvent.BUTTON3_DOWN_MASK); Thread.sleep(60);
        r.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        return "right-clicked";
    }

    String actScroll(String amount) throws Exception {
        String sc = amount == null ? "" : amount.toLowerCase(Locale.ROOT).trim();
        int a;
        try { a = Integer.parseInt(sc); }
        catch (Exception ex) {
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile("-?\\d+").matcher(sc);
            boolean has = nm.find();
            int n = has ? Integer.parseInt(nm.group()) : 3;
            if (sc.contains("down")) a = Math.abs(n);
            else if (sc.contains("up")) a = -Math.abs(n);
            else a = has ? n : -3;
        }
        if (a > 30) a = 30;
        if (a < -30) a = -30;
        smoothScroll(a);
        return "scrolled " + a;
    }

    String actClipRead() {
        try {
            Object o = Toolkit.getDefaultToolkit().getSystemClipboard()
                              .getData(DataFlavor.stringFlavor);
            if (o == null) return "clipboard empty";
            String t = o.toString();
            if (t.length() > 300) t = t.substring(0, 300) + "...";
            return "clipboard: " + t.replace('\n', ' ');
        } catch (Throwable e) { return "clipboard empty"; }
    }

    /* ============================================================
     * Window awareness, close-app, plan normalization
     * ============================================================ */
    private static volatile List<String> winCache = new ArrayList<>();
    private static volatile long winCacheTime = 0L;

    /** Visible top-level windows as "process|title" (cached 2.5s unless forced). */
    static List<String> openWindows(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - winCacheTime < 2500) return winCache;
        List<String> out = new ArrayList<>();
        try {
            Process p;
            if (isWin()) {
                p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                    "$ErrorActionPreference='SilentlyContinue'; Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle } | ForEach-Object { $_.ProcessName + '|' + $_.MainWindowTitle }")
                    .redirectErrorStream(true).start();
            } else if (isMac()) {
                p = new ProcessBuilder("osascript", "-e",
                    "tell application \"System Events\" to get name of (processes where background only is false)")
                    .redirectErrorStream(true).start();
            } else {
                p = new ProcessBuilder("wmctrl", "-l").redirectErrorStream(true).start();
            }
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}
            String raw = "";
            if (p.waitFor(5, TimeUnit.SECONDS)) raw = readStr(p.getInputStream());
            else p.destroyForcibly();
            if (isWin()) {
                for (String line : raw.split("\\r?\\n")) {
                    line = line.trim();
                    int bar = line.indexOf('|');
                    if (bar <= 0) continue;
                    String proc = line.substring(0, bar).toLowerCase(Locale.ROOT);
                    if (proc.equals("java") || proc.equals("javaw")) continue;
                    out.add(line);
                }
            } else if (isMac()) {
                for (String n : raw.trim().split(",\\s*")) if (!n.trim().isEmpty()) out.add(n.trim());
            } else {
                for (String line : raw.split("\\r?\\n")) {
                    String[] parts = line.trim().split("\\s+", 4);
                    if (parts.length == 4 && !parts[3].trim().isEmpty()) out.add(parts[3].trim());
                }
            }
        } catch (Throwable ignored) {}
        winCache = out;
        winCacheTime = System.currentTimeMillis();
        return out;
    }

    /** Names that identify an app: process-name equality or (long) title substring. */
    static List<String> appNeedles(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) return out;
        String n = name.toLowerCase(Locale.ROOT).trim()
                .replaceAll("\\s+(app|application|browser|website|web app|site)$", "");
        String c = n.replace(" ", "");
        if (c.isEmpty()) return out;
        switch (c) {
            case "vscode": case "visualstudiocode": case "code": case "vscodium":
                out.add("code"); out.add("visualstudiocode"); break;
            case "chrome": case "googlechrome":
                out.add("chrome"); break;
            case "edge": case "microsoftedge": case "msedge":
                out.add("msedge"); out.add("microsoftedge"); break;
            case "calc": case "calculator":
                out.add("calculator"); out.add("calculatorapp"); break;
            case "chat gpt": case "chatgpt":
                out.add("chatgpt"); break;
            case "bluj": case "bluej": case "bluejay":
                out.add("bluej"); break;
            default:
                out.add(c);
        }
        return out;
    }

    /** Returns the title of an open window matching the app/site name, else null. */
    static String findOpenWindow(String name) {
        List<String> needles = appNeedles(name);
        if (needles.isEmpty()) return null;
        for (String e : openWindows(true)) {
            int bar = e.indexOf('|');
            String proc = (bar > 0 ? e.substring(0, bar) : "").toLowerCase(Locale.ROOT).replace(" ", "");
            String title = bar >= 0 ? e.substring(bar + 1) : e;
            String tl = title.toLowerCase(Locale.ROOT).replace(" ", "");
            for (String n : needles) {
                if (n.length() < 3) continue;
                if (!proc.isEmpty() && proc.equals(n)) return title;
                if (n.length() >= 5 && tl.contains(n)) return title;
            }
        }
        return null;
    }

    /** If the app is already running, bring it to front instead of launching another. */
    String focusIfOpen(String name) {
        try {
            String title = findOpenWindow(name);
            if (title == null) return null;
            String r = actFocus(title);
            if (r != null && r.startsWith("focused")) {
                sLastApp = name == null ? "" : name;
                String g = sLastApp.toLowerCase(Locale.ROOT);
                if (g.contains("code"))         sLastWindow = "Visual Studio Code";
                else if (g.contains("chrome"))  sLastWindow = "Chrome";
                else if (g.contains("notepad")) sLastWindow = "Notepad";
                else if (g.contains("edge"))    sLastWindow = "Edge";
                else                            sLastWindow = title;
                return "already open - focused " + title;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Site title shown in the browser tab for well-known web-app root URLs. */
    static String webTitleForUrl(String u) {
        if (u == null) return null;
        String k = u.toLowerCase(Locale.ROOT).replaceAll("^https?://(www\\.)?", "").replaceAll("/+$", "");
        switch (k) {
            case "chatgpt.com": case "chat.openai.com": return "ChatGPT";
            case "gemini.google.com": case "gemini.google.com/app": return "Gemini";
            case "claude.ai": return "Claude";
            case "copilot.microsoft.com": return "Copilot";
            case "youtube.com": return "YouTube";
            case "mail.google.com": return "Gmail";
            case "drive.google.com": return "Google Drive";
            case "docs.google.com": return "Google Docs";
            default: return null;
        }
    }

    String actListApps() {
        List<String> w = openWindows(true);
        if (w.isEmpty()) return "no open windows detected";
        StringBuilder b = new StringBuilder("open: ");
        Set<String> seen = new LinkedHashSet<>();
        for (String e : w) {
            int bar = e.indexOf('|');
            String proc = bar > 0 ? e.substring(0, bar) : e;
            if (seen.add(proc.toLowerCase(Locale.ROOT))) {
                if (seen.size() > 1) b.append(", ");
                b.append(proc);
                if (seen.size() >= 12) break;
            }
        }
        return b.toString();
    }

    String actCloseApp(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) return "no app name";
        String t = name.trim();
        if (t.toLowerCase(Locale.ROOT).contains("proton")) return "I will not close myself";
        // Web apps (ChatGPT, Gemini...) live in a browser tab: close just that tab.
        if (wellKnownWebApp(t) != null) {
            String title = findOpenWindow(t);
            if (title != null) {
                String f = actFocus(title);
                if (f != null && f.startsWith("focused")) {
                    Thread.sleep(350);
                    actHotkey("ctrl+w");
                    return "closed tab: " + t;
                }
            }
        }
        List<String> needles = appNeedles(t);
        if (needles.isEmpty()) return "no app name";
        if (isWin()) {
            StringBuilder arr = new StringBuilder();
            for (String n : needles) {
                if (arr.length() > 0) arr.append(',');
                arr.append('\'').append(n.replace("'", "''")).append('\'');
            }
            String script =
                "$ErrorActionPreference='SilentlyContinue'\n" +
                "$needles = @(" + arr + ")\n" +
                "$skip = @('java','javaw')\n" +
                "$procs = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and ($skip -notcontains $_.ProcessName.ToLower()) } | Where-Object {\n" +
                "  $pn = $_.ProcessName.ToLower(); $tt = $_.MainWindowTitle.ToLower().Replace(' ','')\n" +
                "  $hit = $false\n" +
                "  foreach ($n in $needles) { if ($pn -eq $n -or ($n.Length -ge 5 -and $tt.Contains($n))) { $hit = $true } }\n" +
                "  $hit }\n" +
                "if (-not $procs) { exit 1 }\n" +
                "$procs | ForEach-Object { $_.ProcessName; $null = $_.CloseMainWindow() }\n" +
                "Start-Sleep -Milliseconds 600\n" +
                "exit 0";
            Process p = new ProcessBuilder("powershell", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-Command", "-")
                    .redirectErrorStream(true).start();
            OutputStream o = p.getOutputStream();
            o.write(script.getBytes(StandardCharsets.UTF_8));
            o.close();
            boolean done = p.waitFor(8, TimeUnit.SECONDS);
            String out = "";
            if (done) out = readStr(p.getInputStream()).replace('\r', ' ').replace('\n', ' ').trim();
            else p.destroyForcibly();
            if (done && p.exitValue() == 0) return "closed: " + out;
            return "not open: " + t;
        }
        if (isMac()) {
            String esc = t.replace("\\", "\\\\").replace("\"", "\\\"");
            Process p = new ProcessBuilder("osascript", "-e",
                    "tell application \"" + esc + "\" to quit").start();
            p.waitFor(6, TimeUnit.SECONDS);
            return "quit requested: " + t;
        }
        try { new ProcessBuilder("wmctrl", "-c", t).start().waitFor(6, TimeUnit.SECONDS); }
        catch (Throwable ignored) {}
        return "close requested: " + t;
    }

    private static boolean isBrowserName(String t) {
        if (t == null) return false;
        String c = t.toLowerCase(Locale.ROOT).replace(" ", "");
        return c.equals("chrome") || c.equals("googlechrome") || c.equals("edge")
            || c.equals("microsoftedge") || c.equals("msedge") || c.equals("firefox")
            || c.equals("brave") || c.equals("opera") || c.equals("browser")
            || c.equals("webbrowser") || c.equals("internetbrowser");
    }

    /** Cleans an LLM plan: one browser only, no duplicate opens, realistic settle waits. */
    Plan normalizePlan(Plan p) {
        if (p == null || p.steps == null || p.steps.isEmpty()) return p;
        List<Step> in = p.steps, out = new ArrayList<>();
        Set<String> opened = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            Step st = in.get(i);
            String act = st.action == null ? "" : st.action;
            String tgt = st.target == null ? "" : st.target.trim();
            if ("openapp".equals(act)) {
                String web = wellKnownWebApp(tgt);
                if (web != null) { st.action = "openurl"; st.target = web; act = "openurl"; tgt = web; }
                else if (isBrowserName(tgt)) {
                    boolean urlLater = false;
                    for (int j = i + 1; j < in.size(); j++) {
                        String a = in.get(j).action;
                        if ("openurl".equals(a) || "search".equals(a) || "youtube".equals(a)) { urlLater = true; break; }
                    }
                    if (urlLater) continue; // the URL step opens the browser itself
                }
            } else if ("openurl".equals(act)) {
                String web = wellKnownWebApp(tgt);
                if (web != null) { st.target = web; tgt = web; }
            }
            if ("openurl".equals(act) || "openapp".equals(act)) {
                String key = tgt.toLowerCase(Locale.ROOT).replaceAll("^https?://(www\\.)?", "").replaceAll("/+$", "");
                if (!opened.add(key)) continue; // same thing opened twice in one plan
            }
            out.add(st);
        }
        for (int i = 0; i < out.size(); i++) {
            Step st = out.get(i);
            boolean more = i < out.size() - 1;
            String tl = st.target == null ? "" : st.target.toLowerCase(Locale.ROOT);
            long min = 0;
            switch (st.action == null ? "" : st.action) {
                case "openurl":
                    min = more ? (tl.contains("chatgpt") || tl.contains("gemini") || tl.contains("claude") ? 6500 : 5000) : 2500;
                    break;
                case "search": case "youtube": min = more ? 4000 : 2500; break;
                case "openapp": min = more ? 3500 : 2000; break;
                case "focus":   min = more ? 500 : 0; break;
                case "closeapp": min = 1200; break;
                case "type": case "press": case "hotkey": case "click": min = more ? 300 : 0; break;
                default: break;
            }
            if (st.waitMs < min) st.waitMs = min;
        }
        p.steps = out;
        return p;
    }

    String actFocus(String title) throws Exception {
        if (title == null || title.trim().isEmpty()) return "no window";
        String t = title.trim().replace("`", "");
        if (isWin()) {
            String script =
                "$ErrorActionPreference='SilentlyContinue'\n" +
                "$sig = @\"\n" +
                "using System;\nusing System.Runtime.InteropServices;\n" +
                "public class U32 {\n" +
                "  [DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr h);\n" +
                "  [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h, int n);\n" +
                "  [DllImport(\"user32.dll\")] public static extern bool IsIconic(IntPtr h);\n" +
                "}\n" +
                "\"@\n" +
                "Add-Type -TypeDefinition $sig -ErrorAction SilentlyContinue\n" +
                "$needle = '" + t.replace("'", "''") + "'\n" +
                "$procs = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like ('*' + $needle + '*') }\n" +
                "if (-not $procs) { exit 1 }\n" +
                "$p = $procs | Select-Object -First 1\n" +
                "if ([U32]::IsIconic($p.MainWindowHandle)) { [U32]::ShowWindow($p.MainWindowHandle, 9) | Out-Null }\n" +
                "[U32]::SetForegroundWindow($p.MainWindowHandle) | Out-Null\n" +
                "Start-Sleep -Milliseconds 200\n" +
                "exit 0";
            Process p = new ProcessBuilder("powershell","-NoProfile",
                    "-ExecutionPolicy","Bypass","-Command","-")
                    .redirectErrorStream(true).start();
            OutputStream o = p.getOutputStream();
            o.write(script.getBytes(StandardCharsets.UTF_8));
            o.close();
            p.waitFor(6, TimeUnit.SECONDS);
            return p.exitValue() == 0 ? "focused: " + t : "not found: " + t;
        }
        if (isMac()) {
            String esc = t.replace("\\", "\\\\").replace("\"", "\\\"");
            String script =
                "tell application \"System Events\"\n" +
                "  set names to name of every process whose background only is false\n" +
                "  repeat with n in names\n" +
                "    if n contains \"" + esc + "\" then\n" +
                "      set frontmost of process n to true\n      return\n    end if\n  end repeat\n" +
                "end tell";
            Process p = new ProcessBuilder("osascript","-e",script).start();
            p.waitFor(6, TimeUnit.SECONDS);
            return "focus requested: " + t;
        }
        try { new ProcessBuilder("wmctrl","-a",t).start().waitFor(6, TimeUnit.SECONDS); }
        catch (Throwable ignored) {}
        return "focus requested: " + t;
    }

    /* ============================================================
     * VISION QUESTIONS: Qwen looks -> reply model answers -> TTS
     * ============================================================ */
    private static final java.util.regex.Pattern VISION_RX = java.util.regex.Pattern.compile(
        "\\b(what|whats|what's)\\b.{0,30}\\b(on|in|at)\\b.{0,14}\\b(screen|display|monitor|desktop)\\b"
        + "|\\bwhat (do|can|could|did) you see\\b"
        + "|\\b(do|can|could) you (see|view)\\b"
        + "|\\bwhat am i (looking|seeing)\\b"
        + "|\\b(look|looks|looking|see|view) (at )?(my |the |this |that )?(screen|display|monitor|desktop)\\b"
        + "|\\blook at (this|that|it)\\b"
        + "|\\b(describe|explain|analy[sz]e|summari[sz]e|read|check|scan|review|identify|translate|solve)\\b.{0,24}\\b(screen|display|monitor|desktop|this page|this window|what you see)\\b"
        + "|\\bwhat does (it|this|that) say\\b"
        + "|\\bwhat('?s| is) (this|that) (on|in) (the |my )?(screen|window|page)\\b");

    static boolean isVisionQuestion(String text) {
        if (text == null) return false;
        String n = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (n.length() < 6) return false;
        // real desktop commands are handled by the planner, not by vision
        if (n.matches("^(please )?(open|close|quit|launch|start|click|double click|right click|type|press|search|play|go to|write|take a screenshot|screenshot|save).*"))
            return false;
        return VISION_RX.matcher(n).find();
    }

    /** Screenshot of the whole desktop as a JPEG data URI (scaled to maxW wide). */
    String screenDataUri(int maxW) throws Exception {
        Rectangle vb = virtualBounds();
        BufferedImage full = new Robot().createScreenCapture(vb);
        BufferedImage rgb = new BufferedImage(
                Math.min(full.getWidth(), maxW),
                Math.max(1, (int) Math.round(full.getHeight()
                        * (double) Math.min(full.getWidth(), maxW) / full.getWidth())),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(full, 0, 0, rgb.getWidth(), rgb.getHeight(), null);
        g.dispose();
        ByteArrayOutputStream jb = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", jb);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jb.toByteArray());
    }

    /** Step 1: the vision model (Nemotron VL first, then Qwen) describes the current screen for this question. */
    String describeScreen(String userText) throws Exception {
        String uri = screenDataUri(1600);
        String prompt =
            "You are the eyes of a voice assistant. This is a screenshot of the user's computer screen right now.\n" +
            "The user asked: \"" + userText + "\"\n" +
            "Describe what you see clearly and accurately: the main app or window, the key content, and any " +
            "readable text that matters for the question. Be factual and do not guess. Max 250 words.";
        if (nvidia && !nvidiaVisionDead) {
            try {
                String json = "{\"model\":" + js(main.NVIDIA_MODEL) + ",\"messages\":[{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"text\",\"text\":" + js(prompt) + "},"
                    + "{\"type\":\"image_url\",\"image_url\":{\"url\":" + js(uri) + "}}]}],"
                    + "\"temperature\":0.2,\"max_tokens\":2048}";
                String body = nvidiaPost("/chat/completions", json);
                Object content = dig(jparse(body), "choices", 0, "message", "content");
                String d = content == null ? "" : cleanComplex(content.toString());
                if (!d.isEmpty()) {
                    log("vision (nemotron): " + shortStr(d));
                    return d;
                }
            } catch (Throwable t) {
                if (isModelMissing(t)) nvidiaVisionDead = true;
                log("nemotron vision failed: " + shortStr(String.valueOf(t.getMessage())));
            }
        }
        List<String> cands = liveCands();
        if (cands.isEmpty()) throw new Exception("no vision model available on this key");
        StringBuilder errs = new StringBuilder();
        for (String vm : cands) {
            try {
                String json = "{\"model\":" + js(vm) + ",\"messages\":[{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"text\",\"text\":" + js(prompt) + "},"
                    + "{\"type\":\"image_url\",\"image_url\":{\"url\":" + js(uri) + "}}]}],"
                    + "\"temperature\":0.2,\"max_tokens\":2048}";
                String body = postJson("/chat/completions", json);
                Object content = dig(jparse(body), "choices", 0, "message", "content");
                String d = content == null ? "" : content.toString()
                        .replaceAll("(?s)<think>.*?</think>", "").trim();
                if (d.isEmpty()) { errs.append(vm).append(":empty;"); continue; }
                if (visionPicked == null) { visionPicked = vm; }
                log("vision (" + vm + "): " + shortStr(d));
                return d;
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                if (msg.contains("404") || msg.contains("does not exist") || msg.contains("model_not_found"))
                    visionDead.add(vm);
                errs.append(vm).append(':').append(shortStr(msg)).append(';');
                log("vision " + vm + " failed: " + shortStr(msg));
            }
        }
        throw new Exception(errs.toString());
    }

    /** Step 2: the reply model answers using only what the vision model described. */
    String replyFromVision(String userText, String desc) throws Exception {
        String sys = nvidia
            ? NEMOTRON_PERSONA + "\nWHAT YOU SAW:\n" + desc
            : "You are PROTON, a voice assistant. You just looked at the user's screen. What you saw is written below.\n" +
              "Answer the user's request using it, in first person (\"I can see...\"). Never mention a vision model, " +
              "a description or an image. Your reply is spoken aloud: plain natural English, max 70 words unless the " +
              "user asked to read or list something, no markdown, no emojis, no bullet points.\n\n" +
              "WHAT YOU SAW:\n" + desc;
        StringBuilder msgs = new StringBuilder();
        msgs.append("{\"role\":\"system\",\"content\":").append(js(sys)).append("}");
        synchronized (history) {
            for (ChatMsg m : history)
                msgs.append(",{\"role\":\"").append(m.role)
                    .append("\",\"content\":").append(js(m.content)).append("}");
        }
        if (nvidia) {
            try {
                String out = cleanComplex(nvidiaStream(main.NVIDIA_MODEL, msgs.toString(), 2600, 0.6));
                if (out.isEmpty()) throw new Exception("empty reply");
                return out;
            } catch (Throwable t) {
                log("nemotron vision reply failed, falling back to groq: " + shortStr(String.valueOf(t.getMessage())));
            }
        }
        String json = "{\"model\":" + js(CHAT_MODEL) + ",\"messages\":[" + msgs + "]}";
        Object content = dig(jparse(postJson("/chat/completions", json)), "choices", 0, "message", "content");
        if (content == null || content.toString().trim().isEmpty()) throw new Exception("empty reply");
        return content.toString().trim();
    }

    String visionAnswer(String userText) {
        String desc;
        try {
            ensureDocked("screenshot", "");   // shrink so PROTON itself isn't in the screenshot
            skipLinger = true;
            Thread.sleep(380);
            desc = describeScreen(userText);
        } catch (Throwable t) {
            log("vision error: " + t);
            return "Sorry, I couldn't look at your screen right now.";
        }
        lastScreenDesc = desc;
        lastScreenTime = System.currentTimeMillis();
        try {
            return replyFromVision(userText, desc);
        } catch (Throwable t) {
            log("vision reply error: " + t);
            return desc.length() > 400 ? desc.substring(0, 400) : desc;
        }
    }

    List<String> visionCands() {
        if (visionCands != null) return visionCands;
        synchronized (Brain.class) {
            if (visionCands != null) return visionCands;
            List<String> l = new ArrayList<>();
            l.add(VISION_MODEL);
            l.add("meta-llama/llama-4-scout-17b-16e-instruct");
            l.add("llama-3.2-11b-vision-preview");
            l.add("llama-3.2-90b-vision-preview");
            try {
                HttpURLConnection c = (HttpURLConnection)
                        URI.create(BASE + "/models").toURL().openConnection();
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    Object root = jparse(readStr(c.getInputStream()));
                    Object data = dig(root, "data");
                    if (data instanceof List) for (Object o : (List<?>) data) {
                        if (!(o instanceof Map)) continue;
                        Object idO = ((Map<?, ?>) o).get("id");
                        if (idO == null) continue;
                        String id = idO.toString();
                        String low = id.toLowerCase(Locale.ROOT);
                        if ((low.contains("vision") || low.contains("llama-4")
                             || low.contains("scout") || low.contains("maverick"))
                            && !l.contains(id)) l.add(id);
                    }
                }
            } catch (Throwable ignored) {}
            visionCands = l;
            return l;
        }
    }

    List<String> liveCands() {
        List<String> src = visionCands();
        List<String> out = new ArrayList<>();
        if (visionPicked != null && !visionDead.contains(visionPicked)) out.add(visionPicked);
        for (String m : src) if (!out.contains(m) && !visionDead.contains(m)) out.add(m);
        return out;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = c.createGraphics(); g.drawImage(src, 0, 0, null); g.dispose();
        return c;
    }

    static double num(Object o) { return Double.parseDouble(o.toString()); }

    /** Asks one vision model for the element's centre, in the pixel space of img. Null = not found. */
    double[] askLocate(String vm, BufferedImage img, String desc, String hint) throws Exception {
        BufferedImage rgb = toRgb(img);
        ByteArrayOutputStream jb = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", jb);
        String uri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jb.toByteArray());
        String prompt =
            "You are a pixel-accurate UI element locator.\n" +
            "Image size: " + rgb.getWidth() + " x " + rgb.getHeight() + " pixels (origin top-left, x to the right, y downward).\n" +
            "Find: \"" + desc + "\".\n" +
            (hint == null ? "" : hint + "\n") +
            "Locate the clickable element ITSELF. For a desktop, taskbar or Start-menu icon use the icon picture, " +
            "NOT the text caption underneath it. For a button, menu item or tab use the shape that holds its label.\n" +
            "Reply with ONLY JSON: {\"found\":true,\"box\":[x1,y1,x2,y2]} where the box tightly encloses that element " +
            "in image pixels, or {\"found\":false}.";
        String json = "{\"model\":" + js(vm) + ",\"messages\":[{\"role\":\"user\",\"content\":["
            + "{\"type\":\"text\",\"text\":" + js(prompt) + "},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":" + js(uri) + "}}]}],"
            + "\"temperature\":0.1,\"max_tokens\":2048}";
        String body = postJson("/chat/completions", json);
        Object content = dig(jparse(body), "choices", 0, "message", "content");
        if (content == null) throw new Exception("empty");
        String j = extractJson(content.toString());
        if (j == null) throw new Exception("no json");
        Object o = jparse(j);
        if (!(o instanceof Map)) throw new Exception("bad json");
        Map<?, ?> m = (Map<?, ?>) o;
        Object found = m.get("found");
        if (found instanceof Boolean && !((Boolean) found)) return null;
        Object bx = m.get("box");
        if (bx instanceof List && ((List<?>) bx).size() >= 4) {
            List<?> b = (List<?>) bx;
            double x1 = num(b.get(0)), y1 = num(b.get(1)), x2 = num(b.get(2)), y2 = num(b.get(3));
            return new double[]{(x1 + x2) / 2.0, (y1 + y2) / 2.0};
        }
        Object xo = m.get("x"), yo = m.get("y");
        if (xo == null || yo == null) throw new Exception("no coords");
        return new double[]{num(xo), num(yo)};
    }

    /** Pass 2: zoom into a 2x crop around the first guess and re-measure, so the click lands on the element's true centre. */
    int[] refineLocate(String vm, BufferedImage full, int px, int py, String desc) throws Exception {
        int cw = Math.min(560, full.getWidth()), ch = Math.min(360, full.getHeight());
        int x0 = Math.max(0, Math.min(full.getWidth()  - cw, px - cw / 2));
        int y0 = Math.max(0, Math.min(full.getHeight() - ch, py - ch / 2));
        BufferedImage crop = full.getSubimage(x0, y0, cw, ch);
        BufferedImage up = new BufferedImage(cw * 2, ch * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = up.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(crop, 0, 0, cw * 2, ch * 2, null); g.dispose();
        double[] c = askLocate(vm, up, desc, "This is a zoomed-in crop of the screen; the target is near the middle.");
        if (c == null) return null;
        int rx = x0 + (int) Math.round(c[0] / 2.0);
        int ry = y0 + (int) Math.round(c[1] / 2.0);
        if (Math.hypot(rx - px, ry - py) > 220) return null;   // disagrees wildly -> keep the first reading
        return new int[]{rx, ry};
    }

    /* ---- app memory state (see ProtonMemory) ---- */
    volatile ProtonMemory.Fg curFg;
    volatile String memKey;
    volatile BufferedImage lastShot;
    volatile Rectangle lastShotVb;
    volatile boolean lastRecalled;
    volatile int lastScrollNet;
    volatile String lastMemKey = "";
    volatile long lastMemKeyTime = 0L;

    /** Which app is being operated right now (null = Proton itself / unknown: do not learn). */
    String resolveMemKey(ProtonMemory.Fg fg) {
        String k = "";
        if (fg != null) {
            String tl = fg.title == null ? "" : fg.title.toLowerCase(Locale.ROOT);
            if (tl.contains("proton ai")) return null;
            k = ProtonMemory.keyFor(fg.proc, fg.title);
        }
        long now = System.currentTimeMillis();
        if (k.isEmpty()) {
            if (!lastMemKey.isEmpty() && now - lastMemKeyTime < 90000) k = lastMemKey;
            else if (sLastApp != null && !sLastApp.trim().isEmpty())
                k = ProtonMemory.slug(fixAppAlias(sLastApp.trim()), 40);
        }
        if (k.isEmpty() || k.equals("x")) return null;
        lastMemKey = k; lastMemKeyTime = now;
        return k;
    }

    String memDisplay(String key) {
        String a = sLastApp == null ? "" : fixAppAlias(sLastApp.trim());
        if (!a.isEmpty() && ProtonMemory.slug(a, 40).equals(key)) return a;
        if ("desktop".equals(key)) return "Windows Desktop";
        String b = key.replace('-', ' ');
        return b.isEmpty() ? key : Character.toUpperCase(b.charAt(0)) + b.substring(1);
    }

    static String shortLabel(String desc) {
        if (desc == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([^']{2,40})'").matcher(desc);
        if (m.find()) return m.group(1);
        String t = desc.trim().replaceFirst("(?i)^the\\s+", "");
        return t.length() > 40 ? t.substring(0, 39) + "\u2026" : t;
    }

    int[] findOnScreen(String desc) throws Exception {
        if (desc == null || desc.trim().isEmpty()) throw new Exception("no description");
        List<String> cands = liveCands();
        if (cands.isEmpty()) throw new Exception("vision: no models available on this key");

        boolean toastWas = MemoryToast.suspend();
        Rectangle vb = virtualBounds();
        BufferedImage full;
        try { full = new Robot().createScreenCapture(vb); }
        finally { if (toastWas) MemoryToast.resume(); }
        lastShot = full; lastShotVb = vb; lastRecalled = false;

        // 0) The app's own accessibility tree: exact names and rectangles (Office, browsers, Settings, desktop icons ...).
        int[] viaUia = uiaLocate(desc);
        if (viaUia != null) return viaUia;

        // 1) Do I already know this app? Check the remembered spot first (one quick zoomed look).
        String mk = memKey;
        if (mk != null) {
            try {
                Map<String, Object> el = ProtonMemory.recall(mk, desc);
                if (el != null) {
                    ProtonMemory.Fg fg = curFg;
                    Rectangle win = (fg != null && fg.rect != null) ? fg.rect : vb;
                    int[] ex = ProtonMemory.expected(el, win);
                    boolean avoided = false;
                    if (ex != null) for (int[] av : avoidPts) if (Math.hypot(ex[0] - av[0], ex[1] - av[1]) < 30) avoided = true;
                    if (ex != null && !avoided && vb.contains(ex[0], ex[1])) {
                        for (String vm : cands) {
                            try {
                                int[] ref = refineLocate(vm, full, ex[0] - vb.x, ex[1] - vb.y, desc);
                                if (ref != null) {
                                    lastRecalled = true;
                                    log("memory hit: \"" + shortLabel(desc) + "\" in " + mk);
                                    return new int[]{ref[0] + vb.x, ref[1] + vb.y};
                                }
                                break;   // not at the remembered spot -> look at the whole screen
                            } catch (Throwable t) { /* try next model */ }
                        }
                    }
                }
            } catch (Throwable t) { log("memory recall skipped: " + shortStr(String.valueOf(t.getMessage()))); }
        }

        // 2) Full-screen look (what a person does on a new GUI).
        int maxW = 1400; BufferedImage send = full;
        if (full.getWidth() > maxW) {
            int nw = maxW;
            int nh = Math.max(1, (int) Math.round(full.getHeight() * (double) maxW / full.getWidth()));
            BufferedImage tmp = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = tmp.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(full, 0, 0, nw, nh, null); g.dispose();
            send = tmp;
        }
        double sx = (double) full.getWidth()  / send.getWidth();
        double sy = (double) full.getHeight() / send.getHeight();
        String appHint = null;
        try { appHint = ProtonMemory.hint(mk); } catch (Throwable ignored) {}
        if (!avoidPts.isEmpty()) {
            StringBuilder av = new StringBuilder("Clicking these spots did NOTHING, so the target is somewhere else: ");
            for (int[] a : avoidPts)
                av.append('(').append(Math.round((a[0] - vb.x) / sx)).append(',').append(Math.round((a[1] - vb.y) / sy)).append(") ");
            appHint = (appHint == null ? "" : appHint + " ") + av.toString().trim();
        }

        StringBuilder errs = new StringBuilder();
        for (String vm : cands) {
            try {
                double[] c = askLocate(vm, send, desc, appHint);
                if (c == null) { errs.append(vm).append(":not found;"); continue; }
                int px = (int) Math.round(c[0] * sx);
                int py = (int) Math.round(c[1] * sy);
                try {
                    int[] ref = refineLocate(vm, full, px, py, desc);
                    if (ref != null) { px = ref[0]; py = ref[1]; }
                } catch (Throwable rt) { log("refine skipped: " + shortStr(String.valueOf(rt.getMessage()))); }
                if (visionPicked == null) { visionPicked = vm; log("vision picked: " + vm); }
                return new int[]{px + vb.x, py + vb.y};
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                if (msg.contains("404") || msg.contains("does not exist")
                                    || msg.contains("model_not_found"))
                    visionDead.add(vm);
                errs.append(vm).append(':').append(shortStr(msg)).append(';');
            }
        }
        throw new Exception("vision failed: " + errs);
    }

    /* ============================================================
     * SEEING THE SCREEN: change / settle detection
     * ============================================================ */
    static List<Rectangle> ownWindowRects() {
        List<Rectangle> out = new ArrayList<>();
        try {
            for (Window w : Window.getWindows()) if (w.isShowing()) out.add(w.getBounds());
        } catch (Throwable ignored) {}
        return out;
    }

    /** Luma thumbnail (box-averaged). Proton's own windows are blanked so they never count as change. */
    static int[] thumb(BufferedImage full, Rectangle vb, int tw, int th) {
        int w = full.getWidth(), h = full.getHeight();
        int[] px = full.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            int y0 = ty * h / th, y1 = Math.max(y0 + 1, (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
                int x0 = tx * w / tw, x1 = Math.max(x0 + 1, (tx + 1) * w / tw);
                long sum = 0; int cnt = 0;
                for (int y = y0; y < y1; y += 2) {
                    int row = y * w;
                    for (int x = x0; x < x1; x += 2) {
                        int p = px[row + x];
                        sum += (((p >> 16) & 255) * 3 + ((p >> 8) & 255) * 6 + (p & 255)) / 10;
                        cnt++;
                    }
                }
                out[ty * tw + tx] = cnt == 0 ? 0 : (int) (sum / cnt);
            }
        }
        for (Rectangle r : ownWindowRects()) {
            int x0 = Math.max(0, (r.x - vb.x - 2) * tw / w), x1 = Math.min(tw, (r.x - vb.x + r.width + 2) * tw / w + 1);
            int y0 = Math.max(0, (r.y - vb.y - 2) * th / h), y1 = Math.min(th, (r.y - vb.y + r.height + 2) * th / h + 1);
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) out[y * tw + x] = 0;
        }
        return out;
    }

    static int thumbDiff(int[] a, int[] b, int thr) {
        if (a == null || b == null || a.length != b.length) return Integer.MAX_VALUE;
        int c = 0;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > thr) c++;
        return c;
    }

    int[] snapThumb(int tw, int th) throws Exception {
        Rectangle vb = virtualBounds();
        return thumb(new Robot().createScreenCapture(vb), vb, tw, th);
    }

    /** True once the screen stops changing (page / window finished loading). */
    boolean waitSettled(long maxMs, long minMs) throws Exception {
        long t0 = System.currentTimeMillis();
        int[] prev = snapThumb(240, 135);
        int calm = 0;
        while (System.currentTimeMillis() - t0 < maxMs) {
            abortCheck();
            Thread.sleep(300);
            int[] cur = snapThumb(240, 135);
            int d = thumbDiff(prev, cur, 14);
            prev = cur;
            if (d <= 150) calm++; else calm = 0;
            if (calm >= 2 && System.currentTimeMillis() - t0 >= minMs) return true;
        }
        return false;
    }

    /** Waits up to maxMs for ANY visible change versus `before`. */
    boolean waitChange(int[] before, long maxMs) throws Exception {
        long t0 = System.currentTimeMillis();
        while (true) {
            abortCheck();
            Thread.sleep(220);
            if (thumbDiff(before, snapThumb(480, 270), 14) > 2) return true;
            if (System.currentTimeMillis() - t0 >= maxMs) return false;
        }
    }

    /** After launching an app: wait for its window, then for its splash / start screen to settle. */
    void waitAppReady(String name) {
        try {
            long t0 = System.currentTimeMillis();
            boolean found = false;
            while (System.currentTimeMillis() - t0 < 18000) {
                abortCheck();
                if (findOpenWindow(name) != null) { found = true; break; }
                Thread.sleep(500);
            }
            Thread.sleep(found ? 1200 : 2200);
            waitSettled(found ? 15000 : 6000, 700);
        } catch (Throwable ignored) {}
    }

    void mouseClick(int kind) throws Exception {   // 1 = single, 2 = double, 3 = right
        Robot r = new Robot();
        int btn = kind == 3 ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        int n = kind == 2 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            r.mousePress(btn); Thread.sleep(50 + humanRnd.nextInt(40));
            r.mouseRelease(btn);
            if (i + 1 < n) Thread.sleep(90 + humanRnd.nextInt(60));
        }
    }

    /* ============================================================
     * UI AUTOMATION LOOKUP
     * ============================================================ */
    volatile UiaScanner.Scan uiaScanForLocate;
    final List<int[]> avoidPts = new ArrayList<>();
    static final java.util.regex.Pattern FOCUSLIKE_RX = java.util.regex.Pattern.compile(
        "(?i)\\b(text area|text field|textbox|text box|input|search (bar|box)|editor|edit area|address bar|canvas|document|page|slide|cell|code area|source)\\b");

    int[] uiaLocate(String desc) {
        try {
            UiaScanner.Scan sc = uiaScanForLocate;
            if (sc == null || sc.els.size() < 3) return null;
            List<UiaScanner.Cand> cs = UiaScanner.rank(sc, desc, avoidPts);
            UiaScanner.Cand c = UiaScanner.confident(cs);
            UiaScanner.El el = c == null ? null : c.e;
            if (el == null) {
                List<UiaScanner.Cand> sh = new ArrayList<>();
                for (UiaScanner.Cand x : cs) if (x.score >= 0.35 && sh.size() < 25) sh.add(x);
                if (!sh.isEmpty()) el = uiaPick(desc, sc, sh);
            }
            if (el == null) return null;
            log("uia: \"" + shortLabel(desc) + "\" -> [" + el.type + "] " + el.name);
            return new int[]{el.cx(), el.cy()};
        } catch (Throwable t) { return null; }
    }

    /** Several look-alikes: let the text model choose using the description's context. */
    UiaScanner.El uiaPick(String desc, UiaScanner.Scan sc, List<UiaScanner.Cand> cands) throws Exception {
        Rectangle w = sc.win != null ? sc.win : virtualBounds();
        StringBuilder b = new StringBuilder();
        for (UiaScanner.Cand c : cands) {
            int px = (int) Math.round(100.0 * (c.e.cx() - w.x) / Math.max(1, w.width));
            int py = (int) Math.round(100.0 * (c.e.cy() - w.y) / Math.max(1, w.height));
            String nm = c.e.name.length() > 70 ? c.e.name.substring(0, 70) : c.e.name;
            b.append('[').append(c.e.idx).append("] ").append(c.e.type).append(" \"").append(nm)
             .append("\" at (").append(px).append("%,").append(py).append("%)\n");
        }
        String user = "Description of the target: " + desc + "\nWindow: " + sc.title + "\n\nElements (number, type, name, "
            + "position as % from the window's top-left):\n" + b
            + "\nWhich element is the target? Reply ONLY JSON {\"i\": <number>} or {\"i\": -1} if none is clearly the one.";
        Object content = dig(jparse(postFast("{\"role\":\"user\",\"content\":" + js(user) + "}", "low", 200, 0.0)),
                             "choices", 0, "message", "content");
        if (content == null) return null;
        String j = extractJson(content.toString());
        if (j == null) return null;
        Object o = jparse(j);
        if (!(o instanceof Map)) return null;
        Object iv = ((Map<?, ?>) o).get("i");
        if (iv == null) return null;
        int i = (int) Math.round(num(iv));
        for (UiaScanner.Cand c : cands) if (c.e.idx == i) return c.e;
        return null;
    }

    /* ============================================================
     * GUI AGENT: look -> decide -> act -> verify, repeat
     * ============================================================ */
    static String guiSpeech(String res, String orig) {
        if (res.startsWith("done:")) {
            String t = res.substring(5).trim();
            return t.isEmpty() ? orig : t;
        }
        if (res.startsWith("failed:")) {
            String t = res.substring(7).trim();
            if (t.length() > 160) t = t.substring(0, 160);
            return "Sorry, I couldn't finish that. " + t;
        }
        return orig;
    }

    static String sv(Object o) { return o == null ? "" : o.toString().trim(); }
    static int iv(Object o, int d) {
        try { return o == null ? d : (int) Math.round(num(o)); } catch (Exception e) { return d; }
    }

    String listElements(UiaScanner.Scan sc) {
        if (sc == null) return "";
        Rectangle w = sc.win != null ? sc.win : virtualBounds();
        StringBuilder b = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int[] cap = {140, 50};
        for (int pass = 0; pass < 2; pass++) {
            int n = 0;
            for (UiaScanner.El e : sc.els) {
                boolean inter = UiaScanner.INTERACTIVE.contains(e.type);
                if ((pass == 0) != inter) continue;
                if (n >= cap[pass]) break;
                String nm = e.name.length() > 60 ? e.name.substring(0, 60) : e.name;
                if (!seen.add(e.type + "|" + nm + "|" + (e.r.x / 20) + "|" + (e.r.y / 20))) continue;
                int px = (int) Math.round(100.0 * (e.cx() - w.x) / Math.max(1, w.width));
                int py = (int) Math.round(100.0 * (e.cy() - w.y) / Math.max(1, w.height));
                b.append('[').append(e.idx).append("] ").append(e.type).append(" \"").append(nm)
                 .append("\" (").append(px).append(',').append(py).append(")\n");
                n++;
            }
        }
        return b.toString();
    }

    String visionChat(String vm, BufferedImage img, String prompt, int maxTokens) throws Exception {
        BufferedImage rgb = toRgb(ProtonMemory.fit(img, 1400));
        ByteArrayOutputStream jb = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", jb);
        String uri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jb.toByteArray());
        String json = "{\"model\":" + js(vm) + ",\"messages\":[{\"role\":\"user\",\"content\":["
            + "{\"type\":\"text\",\"text\":" + js(prompt) + "},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":" + js(uri) + "}}]}],"
            + "\"temperature\":0.2,\"max_tokens\":" + maxTokens + "}";
        Object content = dig(jparse(postJson("/chat/completions", json)), "choices", 0, "message", "content");
        if (content == null) throw new Exception("empty");
        return content.toString();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> decideNext(String goal, List<String> hist, UiaScanner.Scan sc, BufferedImage full) throws Exception {
        List<String> cands = liveCands();
        if (cands.isEmpty()) throw new Exception("no vision model available");
        StringBuilder p = new StringBuilder();
        p.append("You are the hands of a desktop assistant that operates apps with the mouse and keyboard.\n");
        p.append("GOAL: ").append(goal).append('\n');
        if (sc != null && !sc.title.isEmpty()) p.append("Foreground window: ").append(sc.title).append('\n');
        if (!hist.isEmpty()) {
            p.append("Actions already done (oldest first):\n");
            for (int i = Math.max(0, hist.size() - 8); i < hist.size(); i++) p.append(hist.get(i)).append('\n');
        }
        String els = listElements(sc);
        if (!els.isEmpty()) p.append("Clickable elements of this window: [number] Type \"name\" (x%,y% inside the window)\n").append(els);
        else p.append("No element list is available: describe targets instead of giving an index.\n");
        p.append("\nChoose exactly ONE next action. Reply with ONLY this JSON:\n");
        p.append("{\"action\":\"click|doubleclick|rightclick|type|hotkey|press|scroll|wait|done|fail\",");
        p.append("\"index\":-1,\"target\":\"\",\"text\":\"\",\"keys\":\"\",\"amount\":0,\"why\":\"\",\"say\":\"\"}\n");
        p.append("- click / doubleclick / rightclick: put the element's [number] in index when it is listed (best, it is exact). ");
        p.append("If it is not listed, leave index -1 and describe it in target (e.g. \"the Blank Presentation tile\").\n");
        p.append("- type: text = exactly what to enter at the current keyboard focus. Write real, complete content yourself. ");
        p.append("Click the right field first when focus is unclear.\n");
        p.append("- hotkey: keys like ctrl+n, ctrl+s, alt+f4.   press: one key like enter, tab, esc.\n");
        p.append("- scroll: amount > 0 scrolls down, < 0 up (notches).   wait: the app is still loading.\n");
        p.append("- done: ONLY when the screenshot shows the whole goal is finished; say = one short spoken sentence.\n");
        p.append("- fail: only when it is truly impossible; why = the reason.\n");
        p.append("- Start screens, template galleries and dialogs must be passed first (for example choose the blank option).\n");
        p.append("- NEVER repeat an action that had NO visible change; pick a different element or approach.\n");
        p.append("- Do the goal fully (for example create all requested slides) before saying done.");
        Exception last = null;
        for (String vm : cands) {
            try {
                String raw = visionChat(vm, full, p.toString(), 700);
                String j = extractJson(raw);
                if (j == null) throw new Exception("no json");
                Object o = jparse(j);
                if (!(o instanceof Map)) throw new Exception("bad json");
                if (visionPicked == null) visionPicked = vm;
                return (Map<String, Object>) o;
            } catch (Exception ex) {
                last = ex;
                String msg = String.valueOf(ex.getMessage());
                if (msg.contains("404") || msg.contains("does not exist") || msg.contains("model_not_found")) visionDead.add(vm);
            }
        }
        throw last != null ? last : new Exception("no decision");
    }

    String doGui(String goal) throws Exception {
        if (goal == null || goal.trim().isEmpty()) return "failed: no goal";
        log("gui goal: " + goal);
        List<String> hist = new ArrayList<>();
        String prevSig = ""; int same = 0;
        long t0 = System.currentTimeMillis();
        try { if (sLastApp != null && !sLastApp.isEmpty()) focusIfOpen(sLastApp); } catch (Throwable ignored) {}
        waitSettled(6000, 300);

        for (int step = 1; step <= 22; step++) {
            abortCheck();
            if (System.currentTimeMillis() - t0 > 300000) return "failed: that is taking too long";

            UiaScanner.Scan sc = null;
            try { sc = UiaScanner.scan(0); } catch (Throwable ignored) {}
            if (sc != null && sc.title.toLowerCase(Locale.ROOT).contains("proton ai")) {   // Proton itself got focus
                try { if (sLastApp != null && !sLastApp.isEmpty()) focusIfOpen(sLastApp); } catch (Throwable ignored) {}
                waitSettled(3000, 300);
                continue;
            }
            boolean tw = MemoryToast.suspend();
            Rectangle vb = virtualBounds();
            BufferedImage full;
            try { full = new Robot().createScreenCapture(vb); }
            finally { if (tw) MemoryToast.resume(); }
            int[] before = thumb(full, vb, 480, 270);

            curFg = sc != null ? sc.toFg() : null;
            memKey = resolveMemKey(curFg);
            lastShot = full; lastShotVb = vb; lastRecalled = false; lastScrollNet = 0;

            Map<String, Object> d = decideNext(goal, hist, sc, full);
            String act = sv(d.get("action")).toLowerCase(Locale.ROOT);
            int idx = iv(d.get("index"), -1);
            String target = sv(d.get("target")), text = d.get("text") == null ? "" : d.get("text").toString();
            String keys = sv(d.get("keys")).replace(" ", ""), why = sv(d.get("why")), say = sv(d.get("say"));
            log("gui step " + step + ": " + act + (idx >= 0 ? " [" + idx + "]" : "") + (target.isEmpty() ? "" : " \"" + target + "\"")
                + (text.isEmpty() ? "" : " text(" + text.length() + ")") + (keys.isEmpty() ? "" : " " + keys)
                + (why.isEmpty() ? "" : "  // " + why));

            if (act.equals("done")) return "done: " + (say.isEmpty() ? "All done." : say);
            if (act.equals("fail")) return "failed: " + (why.isEmpty() ? "I could not do that." : why);

            String sig = act + "|" + idx + "|" + target + "|" + keys + "|" + (text.length() > 40 ? text.substring(0, 40) : text);
            same = sig.equals(prevSig) ? same + 1 : 0;
            prevSig = sig;
            if (same >= 2) return "failed: I kept repeating the same action without progress";

            String label;
            UiaScanner.El el = (sc != null && idx >= 0 && idx < sc.els.size()) ? sc.els.get(idx) : null;
            try {
                switch (act) {
                    case "click": case "doubleclick": case "rightclick": {
                        int kind = act.equals("doubleclick") ? 2 : act.equals("rightclick") ? 3 : 1;
                        if (el != null) {
                            label = act + " [" + el.type + "] \"" + el.name + "\"";
                            humanMove(el.cx(), el.cy());
                            Thread.sleep(150 + humanRnd.nextInt(120));
                            mouseClick(kind);
                            learnFromLocate(act, "the '" + el.name + "' " + el.type.toLowerCase(Locale.ROOT),
                                            new int[]{el.cx(), el.cy()});
                        } else if (!target.isEmpty()) {
                            label = act + " \"" + target + "\"";
                            String r = kind == 2 ? actFindDoubleClick(target) : kind == 3 ? actFindRightClick(target) : actFindClick(target);
                            if (r != null && r.startsWith("failed")) label += " (" + r + ")";
                        } else { hist.add((hist.size() + 1) + ". " + act + " without a target -> ignored"); continue; }
                        break;
                    }
                    case "type":
                        if (text.isEmpty()) { hist.add((hist.size() + 1) + ". type with no text -> ignored"); continue; }
                        actType(text);
                        label = "type " + text.length() + " characters";
                        break;
                    case "hotkey":
                        if (keys.isEmpty()) { hist.add((hist.size() + 1) + ". hotkey with no keys -> ignored"); continue; }
                        actHotkey(keys);
                        label = "hotkey " + keys;
                        break;
                    case "press":
                        if (keys.isEmpty()) keys = target.replace(" ", "");
                        actPress(keys);
                        label = "press " + keys;
                        break;
                    case "scroll": {
                        Rectangle w = (sc != null && sc.win != null) ? sc.win : vb;
                        humanMove(w.x + w.width / 2, w.y + w.height / 2);
                        int amt = Math.max(-25, Math.min(25, iv(d.get("amount"), 5)));
                        smoothScroll(amt);
                        label = "scroll " + amt;
                        break;
                    }
                    case "wait":
                        sleepAbortable(1500);
                        label = "wait";
                        break;
                    default:
                        hist.add((hist.size() + 1) + ". unknown action \"" + act + "\" -> ignored");
                        continue;
                }
            } catch (CompanionAbort ca) { throw ca; }
            catch (Exception ex) {
                hist.add((hist.size() + 1) + ". " + act + " failed: " + shortStr(String.valueOf(ex.getMessage())));
                continue;
            }

            waitSettled(7000, 350);
            boolean changed = thumbDiff(before, snapThumb(480, 270), 14) > 2;
            hist.add((hist.size() + 1) + ". " + label + (changed ? " -> screen changed" : " -> NO visible change"));
            UiaScanner.invalidate();
        }
        return "failed: I ran out of steps before finishing";
    }

    /** Vision lookup that also scrolls the window when the element is off-screen. */
    int[] locateWithScroll(String desc) throws Exception {
        boolean uiaPoor = lastMemKey != null && !lastMemKey.isEmpty()
                && System.currentTimeMillis() - lastMemKeyTime < 90000 && UiaScanner.isPoor(lastMemKey);
        UiaScanner.Scan usc = null;
        if (!uiaPoor) { try { usc = UiaScanner.scan(0); } catch (Throwable ignored) {} }
        uiaScanForLocate = usc;
        if (usc != null) curFg = usc.toFg();
        else { try { curFg = ProtonMemory.foreground(); } catch (Throwable t) { curFg = null; } }
        memKey = resolveMemKey(curFg);
        lastScrollNet = 0;
        int h = 0;
        try { if (memKey != null) h = ProtonMemory.recallScroll(memKey, desc); } catch (Throwable ignored) {}
        if (h == 0) h = 5;
        h = Math.max(-25, Math.min(25, h));
        Exception first = null;
        int[] scrollPlan = {0, h, -2 * h};   // none, then where it was last time (default: down), then the other way
        boolean positioned = false;
        for (int i = 0; i < scrollPlan.length; i++) {
            abortCheck();
            if (scrollPlan[i] != 0) {
                if (!positioned) {
                    Rectangle vb = virtualBounds();
                    humanMove(vb.x + vb.width / 2, vb.y + vb.height / 2);   // the wheel acts under the cursor
                    positioned = true;
                }
                smoothScroll(scrollPlan[i]);
                lastScrollNet += scrollPlan[i];
                Thread.sleep(600);
                if (!uiaPoor) { UiaScanner.invalidate(); try { uiaScanForLocate = UiaScanner.scan(0); } catch (Throwable ignored) {} }
            }
            try { return findOnScreen(desc); }
            catch (Exception ex) {
                if (first == null) first = ex;
                if (!String.valueOf(ex.getMessage()).contains("not found")) break;   // network/model error: scrolling won't help
            }
        }
        throw first != null ? first : new Exception("not found");
    }

    BufferedImage cropToWindow(BufferedImage full, Rectangle vb, Rectangle win) {
        if (win == null || vb == null) return full;
        Rectangle r = new Rectangle(win.x - vb.x, win.y - vb.y, win.width, win.height)
                .intersection(new Rectangle(0, 0, full.getWidth(), full.getHeight()));
        if (r.width < 200 || r.height < 150) return full;
        return full.getSubimage(r.x, r.y, r.width, r.height);
    }

    /** Remember what was just operated: JSON entry, marked screenshot, layout on first visit, pop-up. */
    void learnFromLocate(String action, String desc, int[] xy) {
        try {
            String key = memKey;
            BufferedImage clean = lastShot;
            Rectangle vb = lastShotVb;
            if (key == null || clean == null || vb == null) return;
            ProtonMemory.Fg fg = curFg;
            Rectangle win = (fg != null && fg.rect != null) ? fg.rect : vb;
            BufferedImage marked = ProtonMemory.annotate(clean, xy[0] - vb.x, xy[1] - vb.y, desc);
            String display = memDisplay(key);
            ProtonMemory.Learned L = ProtonMemory.record(key, display, fg == null ? "" : fg.title, action, desc,
                    win, xy[0], xy[1], lastRecalled, lastScrollNet, marked);
            if (L == null) return;
            String lbl = shortLabel(desc);
            if (L.newApp) {
                describeLayoutAsync(key, display, cropToWindow(clean, vb, win));
                MemoryToast.show("New app learned", display + " \u00B7 first look saved \u00B7 " + lbl, 0);
            } else if (L.newElement) {
                MemoryToast.show("Memory updated", display + " \u00B7 learned: " + lbl, 0);
            } else if (L.moved) {
                MemoryToast.show("Memory updated", display + " \u00B7 layout changed: " + lbl, 0);
            } else if (lastRecalled && ProtonMemory.firstRecallThisRun(key)) {
                MemoryToast.show("Recalled from memory", display + " \u00B7 I already know this app", 1);
            }
            log("memory: " + key + (L.newApp ? " [new app]" : "") + (L.newElement ? " [new element]" : "")
                    + (L.moved ? " [moved]" : "") + (lastRecalled ? " [recalled]" : "") + " -> " + lbl);
        } catch (Throwable t) { log("memory skipped: " + shortStr(String.valueOf(t.getMessage()))); }
    }

    /** First visit: look at the whole window once and store a short description of its layout. */
    void describeLayoutAsync(final String key, final String display, final BufferedImage img) {
        final List<String> cands = new ArrayList<>(liveCands());
        Thread t = new Thread(() -> {
            try {
                BufferedImage rgb = toRgb(ProtonMemory.fit(img, 1200));
                ByteArrayOutputStream jb = new ByteArrayOutputStream();
                ImageIO.write(rgb, "jpg", jb);
                String uri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jb.toByteArray());
                String prompt = "This is a screenshot of the application window \"" + display + "\". "
                    + "In under 70 words describe its layout for an assistant that drives it with the mouse: "
                    + "menu bar items, toolbar buttons and WHERE they are (left / right / top / bottom), and the main panels. "
                    + "Plain text only, no markdown.";
                for (String vm : cands) {
                    try {
                        String json = "{\"model\":" + js(vm) + ",\"messages\":[{\"role\":\"user\",\"content\":["
                            + "{\"type\":\"text\",\"text\":" + js(prompt) + "},"
                            + "{\"type\":\"image_url\",\"image_url\":{\"url\":" + js(uri) + "}}]}],"
                            + "\"temperature\":0.2,\"max_tokens\":1024}";
                        Object content = dig(jparse(postJson("/chat/completions", json)), "choices", 0, "message", "content");
                        if (content == null) continue;
                        String text = cleanSpoken(content.toString());
                        if (text.length() < 20) continue;
                        if (text.length() > 700) text = text.substring(0, 700);
                        ProtonMemory.setField(key, "layout", text);
                        MemoryToast.show("Memory updated", display + " \u00B7 UI layout learned", 0);
                        return;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }, "proton-mem-layout");
        t.setDaemon(true);
        t.start();
    }

    String actFindClick(String desc) throws Exception {
        if (desc == null || desc.trim().isEmpty()) return "no description";
        avoidPts.clear();
        boolean focusLike = FOCUSLIKE_RX.matcher(desc).find();
        int[] xy = locateWithScroll(desc);
        humanMove(xy[0], xy[1]);
        Thread.sleep(160 + humanRnd.nextInt(140));
        boolean changed = false;
        int tries = 0;
        while (true) {
            tries++;
            int[] before = snapThumb(480, 270);
            mouseClick(1);
            changed = waitChange(before, tries == 1 ? 1600 : 1200);
            if (changed || focusLike || tries >= 3) break;
            log("click on \"" + shortLabel(desc) + "\" had no visible effect (try " + tries + ")");
            if (tries == 1) {
                Thread.sleep(450);   // same spot again: the first click may only have activated the window
            } else {
                avoidPts.add(new int[]{xy[0], xy[1]});
                UiaScanner.invalidate();
                try {
                    xy = locateWithScroll(desc);
                    humanMove(xy[0], xy[1]);
                    Thread.sleep(180);
                } catch (Exception ex) { break; }
            }
        }
        if (changed || focusLike) {
            learnFromLocate("click", desc, xy);
            if (changed) { try { waitSettled(7000, 250); } catch (CompanionAbort ca) { throw ca; } catch (Exception ignored) {} }
        }
        return "clicked \"" + desc + "\" at " + xy[0] + "," + xy[1] + (changed || focusLike ? "" : " (no visible effect)");
    }

    String actFindDoubleClick(String desc) throws Exception {
        if (desc == null || desc.trim().isEmpty()) return "no description";
        avoidPts.clear();
        int[] xy = locateWithScroll(desc);
        humanMove(xy[0], xy[1]);
        Thread.sleep(160 + humanRnd.nextInt(140));
        int[] before = snapThumb(480, 270);
        mouseClick(2);
        boolean changed = waitChange(before, 1800);
        learnFromLocate("doubleclick", desc, xy);
        if (changed) { try { waitSettled(7000, 250); } catch (CompanionAbort ca) { throw ca; } catch (Exception ignored) {} }
        return "double-clicked \"" + desc + "\" at " + xy[0] + "," + xy[1];
    }

    String actFindRightClick(String desc) throws Exception {
        if (desc == null || desc.trim().isEmpty()) return "no description";
        avoidPts.clear();
        int[] xy = locateWithScroll(desc);
        humanMove(xy[0], xy[1]);
        Thread.sleep(160 + humanRnd.nextInt(140));
        int[] before = snapThumb(480, 270);
        mouseClick(3);
        boolean changed = waitChange(before, 1500);
        learnFromLocate("rightclick", desc, xy);
        return "right-clicked \"" + desc + "\" at " + xy[0] + "," + xy[1] + (changed ? "" : " (no visible effect)");
    }

    String actFindMove(String desc) throws Exception {
        if (desc == null || desc.trim().isEmpty()) return "no description";
        avoidPts.clear();
        int[] xy = locateWithScroll(desc);
        humanMove(xy[0], xy[1]);
        learnFromLocate("move", desc, xy);
        return "moved to \"" + desc + "\" at " + xy[0] + "," + xy[1];
    }

    byte[] synthesize(String text, String model, String voice) throws Exception {
        String json = "{\"model\":" + js(model) + ",\"voice\":" + js(voice)
                    + ",\"input\":" + js(text) + ",\"response_format\":\"wav\"}";
        return postJsonBytes("/audio/speech", json);
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    /** Plays a WAV by parsing the header ourselves (handles streamed/unknown data sizes and
     *  float PCM, which Java's AudioSystem/Clip rejects). Returns false if not parseable. */
    private boolean playWavRaw(byte[] w) throws Exception {
        if (w.length < 44 || w[0] != 'R' || w[1] != 'I' || w[2] != 'F' || w[3] != 'F') return false;
        int fmtTag = 1, ch = 1, bits = 16; long rate = 24000; int dataStart = -1; long dataLen = 0;
        int off = 12;
        while (off + 8 <= w.length) {
            String id = new String(w, off, 4, StandardCharsets.US_ASCII);
            long size = le32(w, off + 4);
            if (id.equals("fmt ") && off + 8 + 16 <= w.length) {
                fmtTag = le16(w, off + 8);
                ch     = le16(w, off + 10);
                rate   = le32(w, off + 12);
                bits   = le16(w, off + 22);
                if (fmtTag == 0xFFFE && size >= 26 && off + 8 + 26 <= w.length) fmtTag = le16(w, off + 32);
            } else if (id.equals("data")) {
                dataStart = off + 8;
                long avail = w.length - dataStart;
                dataLen = (size == 0 || size > avail) ? avail : size;
                break;
            }
            off += 8 + (int) Math.min(size, Integer.MAX_VALUE - 16) + (int) (size & 1);
        }
        if (dataStart < 0 || dataLen <= 0 || ch < 1 || ch > 2) return false;

        byte[] pcm; int outBits;
        if (fmtTag == 3 && bits == 32) {                       // 32-bit float -> 16-bit PCM
            int n = (int) (dataLen / 4);
            pcm = new byte[n * 2]; outBits = 16;
            for (int i = 0; i < n; i++) {
                int bi = dataStart + i * 4;
                float f = Float.intBitsToFloat((w[bi] & 0xFF) | ((w[bi + 1] & 0xFF) << 8)
                        | ((w[bi + 2] & 0xFF) << 16) | ((w[bi + 3] & 0xFF) << 24));
                int v = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
                pcm[i * 2] = (byte) v; pcm[i * 2 + 1] = (byte) (v >> 8);
            }
        } else if (fmtTag == 1 && (bits == 16 || bits == 8)) {
            pcm = Arrays.copyOfRange(w, dataStart, dataStart + (int) dataLen); outBits = bits;
        } else return false;

        int frame = ch * (outBits / 8);
        int usable = pcm.length - (pcm.length % frame);
        AudioFormat af = new AudioFormat(outBits == 8 ? AudioFormat.Encoding.PCM_UNSIGNED
                : AudioFormat.Encoding.PCM_SIGNED, (float) rate, outBits, ch, frame, (float) rate, false);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, af));
        line.open(af);
        activeLine = line;
        try {
            line.start();
            int pos = 0;
            while (pos < usable && !speechCancelled) {
                int n = Math.min(4096 - (4096 % frame), usable - pos);
                line.write(pcm, pos, n);
                pos += n;
            }
            if (!speechCancelled) line.drain();
        } finally {
            try { line.stop(); } catch (Throwable ignored) {}
            try { line.close(); } catch (Throwable ignored) {}
            activeLine = null;
        }
        return true;
    }

    void playWav(byte[] wav) throws Exception {
        if (speechCancelled) return;
        if (playWavRaw(wav)) return;
        AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
        final Clip clip = AudioSystem.getClip();
        clip.open(in);
        activeClip = clip;
        final Object lock = new Object();
        final boolean[] done = {false};
        clip.addLineListener(ev -> {
            if (ev.getType() == LineEvent.Type.STOP) {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        try {
            if (!speechCancelled) {
                clip.start();
                long deadline = System.currentTimeMillis() + 30000;
                synchronized (lock) {
                    while (!done[0] && !speechCancelled
                           && System.currentTimeMillis() < deadline) lock.wait(200);
                }
            }
        } finally {
            try { clip.stop(); } catch (Throwable ignored) {}
            try { clip.close(); } catch (Throwable ignored) {}
            try { in.close(); } catch (Throwable ignored) {}
            activeClip = null;
        }
    }

    void speakNative(String text) {
        try {
            if (speechCancelled) return;
            if (isWin()) {
                String ps = "Add-Type -AssemblyName System.Speech; "
                          + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                          + "$t = [Console]::In.ReadToEnd(); $s.Speak($t);";
                Process p = new ProcessBuilder("powershell","-NoProfile","-Command",ps)
                        .redirectErrorStream(true).start();
                speechProc = p;
                OutputStream o = p.getOutputStream();
                o.write(text.getBytes(StandardCharsets.UTF_8)); o.close();
                p.waitFor(45, TimeUnit.SECONDS);
            } else if (isMac()) {
                Process p = new ProcessBuilder("say", text).start();
                speechProc = p;
                p.waitFor(45, TimeUnit.SECONDS);
            } else {
                for (String[] c : new String[][]{{"spd-say", text},{"espeak", text}}) {
                    try {
                        Process p = new ProcessBuilder(c).start();
                        speechProc = p;
                        p.waitFor(45, TimeUnit.SECONDS);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        } finally {
            speechProc = null;
        }
    }

    static class App {
        final String name, command, kind;
        App(String n, String c, String k) { name = n; command = c; kind = k; }
        public String toString() { return name; }
    }
    static class Match { App app; int score; Match(App a, int s) { app = a; score = s; } }

    static class AppIndex {
        static final List<App> apps = new ArrayList<>();
        static volatile boolean scanned = false;

        static void scan() {
            List<App> tmp = new ArrayList<>();
            try {
                if (isWin())      scanWin(tmp);
                else if (isMac()) scanMac(tmp);
                else              scanLinux(tmp);
            } catch (Throwable ignored) {}
            Map<String, App> uniq = new LinkedHashMap<>();
            for (App a : tmp) {
                String k = norm(a.name);
                if (k.isEmpty() || uniq.containsKey(k)) continue;
                uniq.put(k, a);
            }
            List<App> sorted = new ArrayList<>(uniq.values());
            sorted.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
            synchronized (apps) { apps.clear(); apps.addAll(sorted); }
            scanned = true;
            System.out.println("[proton] app index ready: " + sorted.size());
        }

        static void scanWin(List<App> out) {
            try {
                ProcessBuilder pb = new ProcessBuilder("powershell","-NoProfile",
                        "-ExecutionPolicy","Bypass","-Command",
                        "$ErrorActionPreference='SilentlyContinue'; " +
                        "Get-StartApps | ForEach-Object { $_.Name + [char]9 + $_.AppID }");
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    long dl = System.currentTimeMillis() + 12000;
                    while ((line = r.readLine()) != null && System.currentTimeMillis() < dl) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        String n = line.substring(0, tab).trim();
                        String id = line.substring(tab + 1).trim();
                        if (!n.isEmpty() && !id.isEmpty()) out.add(new App(n, id, "win-appid"));
                    }
                }
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (Throwable ignored) {}
            String pd = System.getenv("ProgramData");
            String ad = System.getenv("APPDATA");
            if (pd != null) {
                File r = new File(pd, "Microsoft\\Windows\\Start Menu\\Programs");
                if (r.isDirectory()) walkLnk(r, out);
            }
            if (ad != null) {
                File r = new File(ad, "Microsoft\\Windows\\Start Menu\\Programs");
                if (r.isDirectory()) walkLnk(r, out);
            }
        }

        static void walkLnk(File dir, List<App> out) {
            File[] fs = dir.listFiles(); if (fs == null) return;
            for (File f : fs) {
                if (f.isDirectory()) walkLnk(f, out);
                else if (f.getName().toLowerCase().endsWith(".lnk")) {
                    String n = f.getName().substring(0, f.getName().length() - 4).trim();
                    if (!n.isEmpty()) out.add(new App(n, f.getAbsolutePath(), "win-lnk"));
                }
            }
        }

        static void scanMac(List<App> out) {
            File[] roots = { new File("/Applications"), new File("/System/Applications"),
                             new File(System.getProperty("user.home"), "Applications") };
            for (File r : roots) {
                File[] fs = r.listFiles(); if (fs == null) continue;
                for (File f : fs) if (f.isDirectory() && f.getName().endsWith(".app")) {
                    String n = f.getName().substring(0, f.getName().length() - 4);
                    out.add(new App(n, n, "mac-app"));
                }
            }
        }

        static void scanLinux(List<App> out) {
            File[] roots = { new File("/usr/share/applications"),
                             new File("/usr/local/share/applications"),
                             new File(System.getProperty("user.home"), ".local/share/applications") };
            for (File r : roots) {
                File[] fs = r.listFiles(); if (fs == null) continue;
                for (File f : fs) {
                    if (!f.getName().endsWith(".desktop")) continue;
                    String name = null, exec = null;
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                        String l; boolean in = false;
                        while ((l = br.readLine()) != null) {
                            if (l.startsWith("[")) { in = l.trim().equals("[Desktop Entry]"); continue; }
                            if (!in) continue;
                            int eq = l.indexOf('='); if (eq < 0) continue;
                            String k = l.substring(0, eq).trim();
                            String v = l.substring(eq + 1).trim();
                            if (k.equals("Name") && name == null) name = v;
                            else if (k.equals("Exec") && exec == null) exec = v;
                        }
                    } catch (Throwable ignored) {}
                    if (name == null || exec == null) continue;
                    exec = exec.replaceAll("%[a-zA-Z]", "").trim();
                    if (!name.isEmpty() && !exec.isEmpty()) out.add(new App(name, exec, "linux-desktop"));
                }
            }
        }

        static String norm(String s) {
            if (s == null) return "";
            s = s.toLowerCase(Locale.ROOT);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c)) b.append(c); else b.append(' ');
            }
            return b.toString().replaceAll("\\s+", " ").trim();
        }

        static final Map<String, String> EXPAND = new HashMap<>();
        static {
            EXPAND.put("vs code", "visual studio code");
            EXPAND.put("vscode", "visual studio code");
            EXPAND.put("chrome", "google chrome");
            EXPAND.put("edge", "microsoft edge");
            EXPAND.put("word", "microsoft word");
            EXPAND.put("excel", "microsoft excel");
            EXPAND.put("calc", "calculator");
            EXPAND.put("terminal", "command prompt");
            EXPAND.put("cmd", "command prompt");
            EXPAND.put("chatgpt", "chatgpt");
        }

        static int score(String q, String name) {
            String a = norm(q), b = norm(name);
            if (a.isEmpty() || b.isEmpty()) return 0;
            String ae = EXPAND.containsKey(a) ? EXPAND.get(a) : a;
            if (a.equals(b)) return 2000;
            if (ae.equals(b)) return 1900;
            if (a.length() >= 3 && b.contains(a)) return 1200 + a.length() * 3;
            if (ae.length() >= 3 && b.contains(ae)) return 1250 + ae.length() * 3;
            if (b.length() >= 4 && a.contains(b)) return 1000 + b.length() * 2;
            Set<String> ta = new HashSet<>(Arrays.asList(a.split(" ")));
            Set<String> tb = new HashSet<>(Arrays.asList(b.split(" ")));
            int common = 0; for (String t : ta) if (tb.contains(t)) common++;
            if (common > 0) return Math.max(1, 500 + common * 130
                    - Math.min(200, Math.abs(a.length() - b.length()) * 4));
            int d = lev(a, b), m = Math.max(a.length(), b.length());
            if (m >= 4 && d <= Math.max(2, m / 5)) return Math.max(50, 200 - d * 15);
            return 0;
        }

        static int lev(String a, String b) {
            int n = a.length(), m = b.length();
            if (n == 0) return m; if (m == 0) return n;
            int[] prev = new int[m + 1], cur = new int[m + 1];
            for (int j = 0; j <= m; j++) prev[j] = j;
            for (int i = 1; i <= n; i++) {
                cur[0] = i;
                for (int j = 1; j <= m; j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                int[] tmp = prev; prev = cur; cur = tmp;
            }
            return prev[m];
        }

        static Match bestMatch(String q) {
            Match m = new Match(null, 0);
            synchronized (apps) {
                for (App a : apps) {
                    int s = score(q, a.name);
                    if (s > m.score) { m.app = a; m.score = s; }
                }
            }
            return m;
        }

        static String launch(App a) {
            try {
                switch (a.kind) {
                    case "win-lnk":
                        Runtime.getRuntime().exec(new String[]{"cmd","/c","start","",a.command});
                        return "opening " + a.name;
                    case "win-appid":
                        Runtime.getRuntime().exec(new String[]{"explorer.exe","shell:AppsFolder\\" + a.command});
                        return "opening " + a.name;
                    case "mac-app":
                        Runtime.getRuntime().exec(new String[]{"open","-a",a.command});
                        return "opening " + a.name;
                    case "linux-desktop":
                        new ProcessBuilder("sh","-c",a.command + " >/dev/null 2>&1 &").start();
                        return "opening " + a.name;
                }
            } catch (Throwable t) { return "failed: " + t.getMessage(); }
            return "cannot launch";
        }

        static String open(String phrase, String sentence) {
            if (phrase == null || phrase.trim().isEmpty()) return "no app name";
            phrase = phrase.trim();
            long dl = System.currentTimeMillis() + 8000;
            while (!scanned && System.currentTimeMillis() < dl) {
                try { Thread.sleep(80); } catch (InterruptedException ie) { break; }
            }
            Match m = bestMatch(phrase);
            if (m.app != null && m.score >= 700) return launch(m.app);
            try {
                if (isWin()) Runtime.getRuntime().exec(new String[]{"cmd","/c","start","",phrase});
                else if (isMac()) Runtime.getRuntime().exec(new String[]{"open","-a",phrase});
                else new ProcessBuilder("sh","-c",phrase + " >/dev/null 2>&1 &").start();
                return "opening " + phrase;
            } catch (Throwable t) { return "could not open \"" + phrase + "\""; }
        }
    }

    HttpURLConnection conn(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(BASE + path).toURL().openConnection();
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        return c;
    }

    String postJson(String path, String json) throws Exception {
        HttpURLConnection c = conn(path);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        OutputStream o = c.getOutputStream();
        try { o.write(json.getBytes(StandardCharsets.UTF_8)); } finally { o.close(); }
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String body = readStr(in);
        if (code >= 400) throw new Exception("groq " + code + ": " + shortStr(body));
        return body;
    }

    byte[] postJsonBytes(String path, String json) throws Exception {
        HttpURLConnection c = conn(path);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        OutputStream o = c.getOutputStream();
        try { o.write(json.getBytes(StandardCharsets.UTF_8)); } finally { o.close(); }
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("groq " + code + ": "
                + shortStr(readStr(c.getErrorStream())));
        return readBytes(c.getInputStream());
    }

    String postMultipart(String path, byte[] file, String name,
                         Map<String, String> fields) throws Exception {
        String bd = "----ProtonBoundary" + System.currentTimeMillis();
        HttpURLConnection c = conn(path);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bd);
        OutputStream o = c.getOutputStream();
        try {
            o.write(("--" + bd + "\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                     + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            o.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            o.write(file);
            o.write("\r\n".getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> e : fields.entrySet()) {
                o.write(("--" + bd + "\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(("Content-Disposition: form-data; name=\"" + e.getKey()
                         + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                o.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            o.write(("--" + bd + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } finally { o.close(); }
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String body = readStr(in);
        if (code >= 400) throw new Exception("groq " + code + ": " + shortStr(body));
        return body;
    }

    static String readStr(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static String shortStr(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    static final String UESC = "\\" + "u";

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(UESC).append(String.format("%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static Object jparse(String s) { return new JP(s).parse(); }

    static class JP {
        final String s; int i;
        JP(String str) { s = str; }
        Object parse() { skip(); return value(); }
        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            skip(); char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { i += 4; return Boolean.TRUE; }
            if (c == 'f') { i += 5; return Boolean.FALSE; }
            if (c == 'n') { i += 4; return null; }
            return number();
        }
        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; skip();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip(); String k = string(); skip(); i++; skip();
                m.put(k, value()); skip();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }
        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; skip();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                skip(); l.add(value()); skip();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }
        String string() {
            StringBuilder b = new StringBuilder();
            skip(); i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case '/': b.append('/');  break;
                        case '\\': b.append('\\'); break;
                        case '"': b.append('"');  break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }
        Double number() {
            skip(); int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c) || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') i++;
                else break;
            }
            return Double.valueOf(s.substring(st, i));
        }
    }

    @SuppressWarnings("unchecked")
    static Object dig(Object o, Object... path) {
        for (Object k : path) {
            if (o == null) return null;
            if (k instanceof String) {
                if (!(o instanceof Map)) return null;
                o = ((Map<String, Object>) o).get(k);
            } else {
                if (!(o instanceof List)) return null;
                List<Object> l = (List<Object>) o;
                int idx = ((Integer) k).intValue();
                o = (idx >= 0 && idx < l.size()) ? l.get(idx) : null;
            }
        }
        return o;
    }

    static String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0; boolean inStr = false, esc = false;
        for (int j = start; j < text.length(); j++) {
            char c = text.charAt(j);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) return text.substring(start, j + 1); }
            }
        }
        return null;
    }

    Plan parsePlan(String raw) {
        String j = extractJson(raw);
        if (j == null) return null;
        try {
            Object o = jparse(j);
            if (!(o instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) o;
            Plan p = new Plan();
            if (m.get("speech") != null) p.speech = m.get("speech").toString();
            Object st = m.get("steps");
            if (st instanceof List) {
                for (Object so : (List<?>) st) {
                    if (!(so instanceof Map)) continue;
                    Map<?, ?> sm = (Map<?, ?>) so;
                    Step s = new Step();
                    if (sm.get("action") != null) s.action = sm.get("action").toString().toLowerCase().trim();
                    if (sm.get("target") != null) s.target = sm.get("target").toString();
                    Object w = sm.get("wait");
                    if (w instanceof Number) s.waitMs = ((Number) w).longValue();
                    if (!s.action.isEmpty()) p.steps.add(s);
                }
                if (!p.steps.isEmpty() || !p.speech.isEmpty()) return p;
            }
            Step s = new Step();
            if (m.get("action") != null) s.action = m.get("action").toString().toLowerCase().trim();
            if (m.get("target") != null) s.target = m.get("target").toString();
            if (!s.action.isEmpty()) p.steps.add(s);
            return p;
        } catch (Exception e) { return null; }
    }

    static boolean isWin() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    static boolean isMac() { return System.getProperty("os.name").toLowerCase().contains("mac"); }

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    static byte[] toWav(byte[] pcm, int sampleRate) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int dataLen = pcm.length;
        b.write("RIFF".getBytes());
        le32(b, 36 + dataLen);
        b.write("WAVE".getBytes());
        b.write("fmt ".getBytes());
        le32(b, 16); le16(b, 1); le16(b, 1);
        le32(b, sampleRate); le32(b, sampleRate * 2);
        le16(b, 2); le16(b, 16);
        b.write("data".getBytes());
        le32(b, dataLen);
        b.write(pcm);
        return b.toByteArray();
    }
    static void le16(OutputStream o, int v) throws IOException { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    static void le32(OutputStream o, int v) throws IOException {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    /* Kick off app scan once */
    static { new Thread(AppIndex::scan, "app-scan").start(); }
}