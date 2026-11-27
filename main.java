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
    static volatile String googleKey = "";
    static volatile String openrouterKey = "";

    /** How long the chrome-less splash stays on screen (spec: 8-10 s). */
    static final int SPLASH_MS = 9000;

    public static void main(String[] args) {
        System.setProperty("swing.aatext", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");

        EventQueue.invokeLater(() -> {
            // 1) Floating 460x440 obsidian liquid-glass splash, centered on screen.
            final LoadingWindow loading = new LoadingWindow(SPLASH_MS);
            loading.setVisible(true);
            loading.toFront();
            loading.start();

            // Run real boot tasks in the background and report stages into the splash telemetry log.
            Thread boot = new Thread(() -> {
                try {
                    loading.pushStage("Decoding brand assets & fonts");
                    BrandLogo.getBase();
                    String fam = Fnt.FAM;
                    if (fam == null) loading.pushStage("Initializing fallback font");

                    Thread.sleep(900);
                    loading.pushStage("Loading local BYOK configuration");
                    loadSavedConfig();
                    UserFacts.name();

                    Thread.sleep(1100);
                    loading.pushStage("Audio DSP & Noise Suppressor ready");
                    int nl = Prefs.getInt("noise", 1);
                    if (nl >= 0 && nl <= 2) NoiseSuppressor.level = nl;

                    Thread.sleep(1200);
                    loading.pushStage("Syncing memory and skills");
                    SkillStore.list();
                    Brain.refreshContextAsync();

                    Thread.sleep(1000);
                    loading.pushStage("System ready");
                } catch (Throwable ignored) {}
            }, "proton-boot");
            boot.setDaemon(true);
            boot.start();

            // 2) After the splash: first-run setup, or straight into the app.
            javax.swing.Timer timer = new javax.swing.Timer(SPLASH_MS, e -> {
                loading.stopAnim();
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

    static String cleanKey(String k) {
        if (k == null) return "";
        String t = k.trim();
        return t.startsWith("PASTE") ? "" : t;
    }

    static void loadSavedConfig() {
        googleKey = cleanKey(Prefs.getString("googleKey", ""));
        openrouterKey = cleanKey(Prefs.getString("openrouterKey", ""));
    }

    static boolean hasSavedConfig() {
        return !googleKey.isEmpty() || !openrouterKey.isEmpty();
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
/*  BRAND LOGO -- embedded 192x192 circular transparent PNG        */
/* =============================================================== */

final class BrandLogo {
    private static final String B64_A = "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";
    private static final String B64_B = "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";
    static final String BRAND_LOGO_B64 = String.join("", B64_A, B64_B);

    private static volatile BufferedImage baseImg;
    private static final Map<Integer, BufferedImage> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private BrandLogo() {}

    static BufferedImage getBase() {
        BufferedImage img = baseImg;
        if (img != null) return img;
        synchronized (BrandLogo.class) {
            if (baseImg == null) {
                try {
                    byte[] raw = Base64.getDecoder().decode(BRAND_LOGO_B64);
                    baseImg = ImageIO.read(new ByteArrayInputStream(raw));
                } catch (Throwable t) {
                    baseImg = new BufferedImage(192, 192, BufferedImage.TYPE_INT_ARGB);
                }
            }
            return baseImg;
        }
    }

    static BufferedImage getScaled(int size) {
        int s = Math.max(8, size);
        BufferedImage hit = cache.get(s);
        if (hit != null) return hit;
        BufferedImage src = getBase();
        BufferedImage cur = src;
        // Progressive downscale for crisp anti-aliased small icons
        if (s <= 48 && cur.getWidth() >= 96) {
            cur = scaleOnce(cur, 96);
        }
        BufferedImage out = scaleOnce(cur, s);
        cache.put(s, out);
        return out;
    }

    private static BufferedImage scaleOnce(BufferedImage src, int s) {
        BufferedImage dst = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = dst.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(src, 0, 0, s, s, null);
        } finally {
            g2.dispose();
        }
        return dst;
    }

    static List<Image> getWindowIcons() {
        int[] sizes = {16, 24, 32, 64, 128};
        List<Image> icons = new ArrayList<>(sizes.length);
        for (int sz : sizes) icons.add(getScaled(sz));
        return icons;
    }
}

/* =============================================================== */
/*  LOADING WINDOW -- floating obsidian liquid-glass boot splash   */
/* =============================================================== */

@SuppressWarnings("serial")
class LoadingWindow extends JWindow {

    private static final int W = 460;
    private static final int H = 440;

    private final int             durationMs;
    private final SplashPanel     splash;
    private final long            t0 = System.nanoTime();
    private javax.swing.Timer     anim;
    private final List<String>    stages = new ArrayList<>();

    LoadingWindow(int durationMs) {
        super();
        this.durationMs = Math.max(1500, durationMs);
        stages.add("Initializing obsidian UI & graphics");

        try { setBackground(new Color(0, 0, 0, 0)); } catch (Throwable ignored) {}
        try { setIconImages(BrandLogo.getWindowIcons()); } catch (Throwable ignored) {}
        setAlwaysOnTop(true);

        splash = new SplashPanel();
        setContentPane(splash);

        setSize(W, H);
        setLocationRelativeTo(null);
    }

    /** Start the 60 FPS paint loop (call once, on the EDT). */
    void start() {
        if (anim != null) return;
        anim = new javax.swing.Timer(16, e -> splash.repaint());
        anim.setCoalesce(true);
        anim.start();
    }

    void stopAnim() {
        if (anim != null) { anim.stop(); anim = null; }
    }

    /** Appends a real boot telemetry stage (thread-safe). */
    void pushStage(String label) {
        if (label == null || label.trim().isEmpty()) return;
        String clean = label.trim();
        synchronized (stages) {
            if (!stages.isEmpty() && stages.get(stages.size() - 1).equals(clean)) return;
            stages.add(clean);
        }
        SwingUtilities.invokeLater(splash::repaint);
    }

    private float elapsedMs() {
        return (System.nanoTime() - t0) / 1_000_000f;
    }

    /* ------------------------------------------------------------ */

    private final class SplashPanel extends JPanel {

        SplashPanel() {
            setOpaque(false);
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

                RoundRectangle2D shell = new RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, 24f, 24f);
                Shape prevClip = g2.getClip();
                g2.setClip(shell);
                paintBackdrop(g2, w, h, ms);

                Composite base = g2.getComposite();
                if (in < 1f) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, in));
                }

                paintHeroOrb(g2, w / 2f, 94f, 54f, ms);
                paintWordmark(g2, w);
                String activeStage = paintTelemetry(g2, w, ms);
                paintProgress(g2, w, t, activeStage);

                g2.setComposite(base);
                // Soft top specular highlight inside rounded card
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 22),
                        0, 64, new Color(255, 255, 255, 0)));
                g2.fillRect(0, 0, w, 64);
                g2.setClip(prevClip);

                // Subtle 1px glass border
                g2.setColor(new Color(255, 255, 255, 31));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(shell);
            } finally {
                g2.dispose();
            }
        }

        /* ---- deep obsidian liquid-glass backdrop ---- */
        private void paintBackdrop(Graphics2D g2, int w, int h, float ms) {
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(w * 0.5f, h * 0.38f), Math.max(w, h) * 0.72f,
                    new float[]{0f, 0.58f, 1f},
                    new Color[]{new Color(0x0A0818), new Color(0x060510), new Color(0x04030A)}));
            g2.fillRect(0, 0, w, h);

            float drift = (float) Math.sin(ms * 0.00055) * 20f;
            float cx = w / 2f + drift;
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(cx, 96f), 205f,
                    new float[]{0f, 0.55f, 1f},
                    new Color[]{Theme.alpha(new Color(0x38BDF8), 0.14),
                                Theme.alpha(new Color(0xA855F7), 0.06),
                                new Color(0, 0, 0, 0)}));
            g2.fill(new Ellipse2D.Float(cx - 205f, 96f - 205f, 410f, 410f));

            float drift2 = (float) Math.cos(ms * 0.00042) * 22f;
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(w * 0.82f + drift2, h * 0.86f), 190f,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{Theme.alpha(new Color(0xEC4899), 0.09),
                                Theme.alpha(new Color(0x6366F1), 0.04),
                                new Color(0, 0, 0, 0)}));
            g2.fill(new Ellipse2D.Float(w * 0.82f + drift2 - 190f, h * 0.86f - 190f, 380f, 380f));
        }

        /* ---- BrandLogo 108x108 hero + breathing glow + 2 rotating orbital arcs ---- */
        private void paintHeroOrb(Graphics2D g2, float cx, float cy, float r, float ms) {
            float breath = 0.85f + 0.15f * (float) Math.sin(ms * 0.0026f);
            float glowR = (r + 30f) * breath;
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(cx, cy), glowR,
                    new float[]{0f, 0.55f, 1f},
                    new Color[]{Theme.alpha(new Color(0x38BDF8), 0.24 * breath),
                                Theme.alpha(new Color(0xA855F7), 0.11 * breath),
                                new Color(0, 0, 0, 0)}));
            g2.fill(new Ellipse2D.Float(cx - glowR, cy - glowR, glowR * 2f, glowR * 2f));

            // 2 smooth-rotating orbital light arcs around the 108x108 orb
            AffineTransform old = g2.getTransform();
            float r1 = r + 10f;
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setPaint(new LinearGradientPaint(
                    cx - r1, cy - r1, cx + r1, cy + r1,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{Theme.alpha(new Color(0x38BDF8), 0f),
                                Theme.alpha(new Color(0x38BDF8), 0.90f),
                                Theme.alpha(new Color(0xA855F7), 0.15f)}));
            g2.rotate(Math.toRadians(ms * 0.055f), cx, cy);
            g2.draw(new Arc2D.Float(cx - r1, cy - r1, r1 * 2f, r1 * 2f, 20f, 225f, Arc2D.OPEN));
            g2.setTransform(old);

            float r2 = r + 16f;
            g2.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setPaint(new LinearGradientPaint(
                    cx - r2, cy - r2, cx + r2, cy + r2,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{Theme.alpha(new Color(0xEC4899), 0.05f),
                                Theme.alpha(new Color(0xA855F7), 0.72f),
                                Theme.alpha(new Color(0x38BDF8), 0f)}));
            g2.rotate(Math.toRadians(-ms * 0.038f), cx, cy);
            g2.draw(new Arc2D.Float(cx - r2, cy - r2, r2 * 2f, r2 * 2f, 150f, 195f, Arc2D.OPEN));
            g2.setTransform(old);

            // Subtle orbiting spark dot
            double ang = ms * 0.0014;
            float px = cx + (float) Math.cos(ang) * r2;
            float py = cy + (float) Math.sin(ang) * r2;
            g2.setColor(new Color(0xBAE6FD));
            g2.fill(new Ellipse2D.Float(px - 2f, py - 2f, 4f, 4f));

            // 108x108 circular BrandLogo
            BufferedImage logo = BrandLogo.getScaled(108);
            g2.drawImage(logo, Math.round(cx - 54f), Math.round(cy - 54f), 108, 108, null);
        }

        /* ---- PROTON wordmark + AI WORKSTATION · WINDOWS pill ---- */
        private void paintWordmark(Graphics2D g2, int w) {
            String word = "PROTON";
            float sp = 7.0f;
            Font f = Fnt.get(Font.BOLD, 25f);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();

            float total = 0f;
            for (int i = 0; i < word.length(); i++) {
                total += fm.charWidth(word.charAt(i));
                if (i < word.length() - 1) total += sp;
            }
            float x = (w - total) / 2f;
            float baseY = 198f;
            g2.setPaint(new LinearGradientPaint(
                    x, baseY, x + total, baseY,
                    new float[]{0f, 0.52f, 1f},
                    new Color[]{new Color(0xF8FAFC), new Color(0x38BDF8), new Color(0xA855F7)}));
            for (int i = 0; i < word.length(); i++) {
                g2.drawString(String.valueOf(word.charAt(i)), x, baseY);
                x += fm.charWidth(word.charAt(i)) + sp;
            }

            String sub = "AI WORKSTATION \u00B7 WINDOWS";
            g2.setFont(Fnt.get(Font.BOLD, 9f));
            FontMetrics sfm = g2.getFontMetrics();
            float stw = 0f;
            float ssp = 1.8f;
            for (int i = 0; i < sub.length(); i++) {
                stw += sfm.charWidth(sub.charAt(i));
                if (i < sub.length() - 1) stw += ssp;
            }
            float pw = stw + 24f, ph = 20f;
            float px = (w - pw) / 2f, py = 211f;
            RoundRectangle2D pill = new RoundRectangle2D.Float(px, py, pw, ph, ph, ph);
            g2.setColor(new Color(255, 255, 255, 11));
            g2.fill(pill);
            g2.setColor(new Color(255, 255, 255, 24));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(pill);

            g2.setColor(new Color(0xA5B4CF));
            float sx = (w - stw) / 2f;
            float sy = py + (ph + sfm.getAscent() - sfm.getDescent()) / 2f;
            for (int i = 0; i < sub.length(); i++) {
                g2.drawString(String.valueOf(sub.charAt(i)), sx, sy);
                sx += sfm.charWidth(sub.charAt(i)) + ssp;
            }
        }

        /* ---- live boot telemetry card (last 3 real startup stages) ---- */
        private String paintTelemetry(Graphics2D g2, int w, float ms) {
            float x = 36f, y = 248f, cw = w - 72f, ch = 98f;
            RoundRectangle2D card = new RoundRectangle2D.Float(x, y, cw, ch, 12f, 12f);
            g2.setColor(new Color(255, 255, 255, 9));
            g2.fill(card);
            g2.setColor(new Color(255, 255, 255, 22));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(card);

            List<String> snap;
            synchronized (stages) {
                snap = new ArrayList<>(stages);
            }
            int n = snap.size();
            int start = Math.max(0, n - 3);
            String active = n > 0 ? snap.get(n - 1) : "Booting";

            float rowY = y + 27f;
            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 11);
            for (int i = start; i < n; i++) {
                boolean isLast = (i == n - 1);
                boolean isReady = isLast && "System ready".equalsIgnoreCase(snap.get(i));
                if (!isLast || isReady) {
                    // Completed stage: crisp vector emerald checkmark + text
                    g2.setColor(new Color(0x34D399));
                    g2.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D.Float chk = new Path2D.Float();
                    chk.moveTo(x + 14.5f, rowY - 3.5f);
                    chk.lineTo(x + 17.5f, rowY - 0.5f);
                    chk.lineTo(x + 23.0f, rowY - 6.8f);
                    g2.draw(chk);
                    g2.setFont(mono);
                    g2.setColor(isReady ? new Color(0xA7F3D0) : Theme.TEXT2);
                    g2.drawString(fitText(g2, snap.get(i), cw - 48f), x + 34f, rowY);
                } else {
                    // Active stage: pulsing cyan/violet dot + bright text
                    float p = 0.55f + 0.45f * (float) Math.sin(ms * 0.008f);
                    g2.setColor(Theme.alpha(new Color(0x38BDF8), 0.35 * p));
                    g2.fill(new Ellipse2D.Float(x + 12f, rowY - 9f, 12f, 12f));
                    g2.setColor(Theme.mix(new Color(0x38BDF8), new Color(0xA855F7), p * 0.5f));
                    g2.fill(new Ellipse2D.Float(x + 15f, rowY - 6f, 6f, 6f));
                    g2.setFont(mono);
                    g2.setColor(Theme.TEXT);
                    g2.drawString(fitText(g2, snap.get(i) + "\u2026", cw - 48f), x + 34f, rowY);
                }
                rowY += 25f;
            }
            return active;
        }

        private String fitText(Graphics2D g2, String s, float maxW) {
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(s) <= maxW) return s;
            String t = s;
            while (t.length() > 3 && fm.stringWidth(t + "\u2026") > maxW) t = t.substring(0, t.length() - 1);
            return t + "\u2026";
        }

        /* ---- sleek 4px progress track + stage name + tabular % ---- */
        private void paintProgress(Graphics2D g2, int w, float t, String stageName) {
            float x = 36f, barW = w - 72f, barH = 4f, y = 392f;
            float e = t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;
            int pct = Math.min(100, Math.max(0, Math.round(e * 100f)));

            g2.setFont(Fnt.get(Font.PLAIN, 11f));
            g2.setColor(Theme.TEXT2);
            g2.drawString(fitText(g2, stageName, barW - 48f), x, 380f);

            String pctStr = pct + "%";
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
            g2.setColor(new Color(0xCBD5E1));
            FontMetrics pfm = g2.getFontMetrics();
            g2.drawString(pctStr, x + barW - pfm.stringWidth(pctStr), 380f);

            g2.setColor(new Color(255, 255, 255, 20));
            g2.fill(new RoundRectangle2D.Float(x, y, barW, barH, barH, barH));

            float fw = Math.max(barH, barW * e);
            g2.setPaint(new LinearGradientPaint(
                    x, y, x + barW, y,
                    new float[]{0f, 0.35f, 0.70f, 1f},
                    new Color[]{new Color(0x38BDF8), new Color(0x6366F1),
                                new Color(0xA855F7), new Color(0xEC4899)}));
            g2.fill(new RoundRectangle2D.Float(x, y, fw, barH, barH, barH));

            float sheen = x + ((System.nanoTime() / 1_000_000L) % 1400L) / 1400f * barW;
            if (sheen < x + fw) {
                g2.setPaint(new LinearGradientPaint(
                        Math.max(x, sheen - 34f), y, Math.min(x + fw, sheen + 34f), y,
                        new float[]{0f, 0.5f, 1f},
                        new Color[]{new Color(255, 255, 255, 0),
                                    new Color(255, 255, 255, 130),
                                    new Color(255, 255, 255, 0)}));
                g2.fill(new RoundRectangle2D.Float(x, y, fw, barH, barH, barH));
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
/*  FIRST-RUN SETUP -- independent free-tier BYOK setup            */
/* =============================================================== */

@SuppressWarnings("serial")
class SetupWindow extends JFrame {

    private static final String GOOGLE_KEY_URL     = "https://aistudio.google.com/apikey";
    private static final String OPENROUTER_KEY_URL = "https://openrouter.ai/keys";

    private final KeyCard googleCard;
    private final KeyCard openrouterCard;
    private final JLabel error = new JLabel(" ");
    private final SetupButton launchBtn;
    private final boolean changing;

    SetupWindow(boolean changing) {
        super("PROTON AI");
        this.changing = changing;
        setUndecorated(true);
        setDefaultCloseOperation(changing ? DISPOSE_ON_CLOSE : EXIT_ON_CLOSE);
        setBackground(Theme.BG);
        setSize(620, 540);
        setResizable(false);
        setLocationRelativeTo(null);
        try { setIconImages(BrandLogo.getWindowIcons()); } catch (Throwable ignored) {}
        if (changing) setAlwaysOnTop(true);

        googleCard = new KeyCard(
                "Google AI Studio (Optional)",
                "Gemini 3.8 Flash \u00B7 Voice STT \u00B7 Vision",
                "AIza...",
                "Get free key at aistudio.google.com \u2192",
                GOOGLE_KEY_URL,
                Prefs.getString("googleKey", ""),
                this::testGoogleKey);

        openrouterCard = new KeyCard(
                "OpenRouter (Optional)",
                "Nemotron 3.5 Super VL \u00B7 Fish Audio TTS",
                "sk-or-...",
                "Get free key at openrouter.ai/keys \u2192",
                OPENROUTER_KEY_URL,
                Prefs.getString("openrouterKey", ""),
                this::testOpenRouterKey);

        launchBtn = new SetupButton(changing ? "Save & Restart" : "Launch PROTON", true);
        launchBtn.addActionListener(e -> submit());

        JPanel root = new SetupPanel();
        root.setLayout(new GridBagLayout());
        root.add(buildContent());
        setContentPane(root);

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { syncValidity(true); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { syncValidity(true); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { syncValidity(true); }
        };
        googleCard.field.getDocument().addDocumentListener(dl);
        openrouterCard.field.getDocument().addDocumentListener(dl);

        KeyListener enterKey = new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) submit();
            }
        };
        googleCard.field.addKeyListener(enterKey);
        openrouterCard.field.addKeyListener(enterKey);

        syncValidity(false);

        getRootPane().registerKeyboardAction(e -> {
            dispose();
            if (!changing) System.exit(0);
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private JPanel buildContent() {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setPreferredSize(new Dimension(520, 464));
        box.setMaximumSize(new Dimension(520, 464));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(520, 56));
        JComponent logoBadge = new JComponent() {
            { setPreferredSize(new Dimension(46, 46)); setMinimumSize(new Dimension(46, 46)); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new RadialGradientPaint(new Point2D.Float(23, 23), 23f,
                        new float[]{0f, 1f},
                        new Color[]{Theme.alpha(new Color(0x38BDF8), 0.25), new Color(0, 0, 0, 0)}));
                g2.fill(new Ellipse2D.Float(0, 0, 46, 46));
                g2.drawImage(BrandLogo.getScaled(40), 3, 3, 40, 40, null);
                g2.dispose();
            }
        };
        JPanel hdrText = new JPanel();
        hdrText.setOpaque(false);
        hdrText.setLayout(new BoxLayout(hdrText, BoxLayout.Y_AXIS));
        JLabel kicker = label("FREE BYOK SETUP", Font.BOLD, 9.5f, new Color(0x8D95C9));
        kicker.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = label("Connect your AI keys", Font.BOLD, 22f, Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        hdrText.add(kicker);
        hdrText.add(Box.createVerticalStrut(2));
        hdrText.add(title);
        header.add(logoBadge, BorderLayout.WEST);
        header.add(hdrText, BorderLayout.CENTER);

        JLabel sub = label("Add one or both free API keys \u2014 stored locally in ~/.proton-ai.properties.",
                Font.PLAIN, 11.5f, Theme.TEXT2);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        error.setFont(Fnt.get(Font.PLAIN, 10.5f));
        error.setForeground(new Color(0xFF7A9A));
        error.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setMaximumSize(new Dimension(520, 42));
        if (changing) {
            SetupButton cancel = new SetupButton("Cancel", false);
            cancel.addActionListener(e -> dispose());
            actions.add(cancel, BorderLayout.WEST);
        }
        actions.add(launchBtn, BorderLayout.EAST);

        box.add(header);
        box.add(Box.createVerticalStrut(6));
        box.add(sub);
        box.add(Box.createVerticalStrut(16));
        box.add(googleCard);
        box.add(Box.createVerticalStrut(12));
        box.add(openrouterCard);
        box.add(Box.createVerticalStrut(10));
        box.add(error);
        box.add(Box.createVerticalStrut(8));
        box.add(actions);
        return box;
    }

    private void syncValidity(boolean showEmptyErr) {
        String g = googleCard.getKey();
        String o = openrouterCard.getKey();
        boolean hasAny = !g.isEmpty() || !o.isEmpty();
        launchBtn.setEnabled(hasAny);
        if (hasAny) {
            error.setText(" ");
        } else if (showEmptyErr) {
            error.setText("Add at least one free API key (Google or OpenRouter) to continue.");
        }
        launchBtn.repaint();
    }

    private void submit() {
        String g = main.cleanKey(googleCard.getKey());
        String o = main.cleanKey(openrouterCard.getKey());
        if (g.isEmpty() && o.isEmpty()) {
            error.setText("Add at least one free API key (Google or OpenRouter) to continue.");
            launchBtn.setEnabled(false);
            return;
        }
        Prefs.setString("googleKey", g.isEmpty() ? null : g);
        Prefs.setString("openrouterKey", o.isEmpty() ? null : o);
        main.googleKey = g;
        main.openrouterKey = o;
        dispose();
        if (changing) main.restartApp();
        else main.launchMain();
    }

    private String[] testGoogleKey(String key) {
        try {
            String url = Brain.GOOGLE_BASE + "/models?key=" + URLEncoder.encode(key, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            if (code == 200) {
                try (InputStream in = c.getInputStream()) { Brain.readStr(in); }
                return new String[]{"ok", "\u2713 Valid"};
            }
            String err = "";
            try (InputStream in = c.getErrorStream()) { err = Brain.readStr(in); } catch (Throwable ignored) {}
            return new String[]{"err", "Invalid (" + code + ")" + (err.isEmpty() ? "" : "")};
        } catch (Throwable t) {
            return new String[]{"err", "Connection failed"};
        }
    }

    private String[] testOpenRouterKey(String key) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    URI.create(Brain.OPENROUTER_BASE + "/auth/key").toURL().openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("HTTP-Referer", Brain.OR_REFERER);
            c.setRequestProperty("X-Title", Brain.OR_TITLE);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            if (code == 200) {
                try (InputStream in = c.getInputStream()) { Brain.readStr(in); }
                return new String[]{"ok", "\u2713 Valid"};
            }
            return new String[]{"err", "Invalid (" + code + ")"};
        } catch (Throwable t) {
            return new String[]{"err", "Connection failed"};
        }
    }

    private static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(Fnt.get(style, size));
        l.setForeground(color);
        return l;
    }

    private static void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Throwable ignored) {}
    }

    /** Provider card with capability badge, masked key field, eye toggle, Test button, and link. */
    @SuppressWarnings("serial")
    private static final class KeyCard extends JPanel {
        final KeyPasswordField field;
        private final JLabel status = new JLabel(" ");
        private final SmallButton testBtn = new SmallButton("Test");
        private final EyeButton eyeBtn;

        KeyCard(String title, String badge, String placeholder, String linkText,
                String url, String initial, java.util.function.Function<String, String[]> tester) {
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(520, 128));
            setPreferredSize(new Dimension(520, 128));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(13, 16, 11, 16));

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setOpaque(false);
            top.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.setMaximumSize(new Dimension(488, 22));
            JLabel tl = label(title, Font.BOLD, 12.5f, Theme.TEXT);
            BadgeLabel bl = new BadgeLabel(badge);
            top.add(tl, BorderLayout.WEST);
            top.add(bl, BorderLayout.EAST);

            field = new KeyPasswordField(placeholder);
            field.setText(initial == null ? "" : initial);
            eyeBtn = new EyeButton(field);

            JPanel fieldWrap = new JPanel(new BorderLayout(0, 0));
            fieldWrap.setOpaque(true);
            fieldWrap.setBackground(new Color(0x141222));
            fieldWrap.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 34), 1, true));
            fieldWrap.add(field, BorderLayout.CENTER);
            fieldWrap.add(eyeBtn, BorderLayout.EAST);

            JPanel inputRow = new JPanel(new BorderLayout(8, 0));
            inputRow.setOpaque(false);
            inputRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            inputRow.setMaximumSize(new Dimension(488, 38));
            inputRow.setPreferredSize(new Dimension(488, 38));
            inputRow.add(fieldWrap, BorderLayout.CENTER);
            inputRow.add(testBtn, BorderLayout.EAST);

            JPanel foot = new JPanel(new BorderLayout(8, 0));
            foot.setOpaque(false);
            foot.setAlignmentX(Component.LEFT_ALIGNMENT);
            foot.setMaximumSize(new Dimension(488, 18));
            JLabel link = label(linkText, Font.PLAIN, 10.5f, Theme.TEXT3);
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { link.setForeground(new Color(0xB8C2FF)); }
                @Override public void mouseExited(MouseEvent e) { link.setForeground(Theme.TEXT3); }
                @Override public void mouseClicked(MouseEvent e) { openUrl(url); }
            });
            status.setFont(Fnt.get(Font.BOLD, 10.5f));
            status.setHorizontalAlignment(SwingConstants.RIGHT);
            foot.add(link, BorderLayout.WEST);
            foot.add(status, BorderLayout.EAST);

            testBtn.addActionListener(e -> {
                String k = getKey();
                if (k.isEmpty()) {
                    status.setForeground(new Color(0xFF7A9A));
                    status.setText("Enter key first");
                    return;
                }
                testBtn.setEnabled(false);
                status.setForeground(Theme.TEXT2);
                status.setText("Testing\u2026");
                Thread th = new Thread(() -> {
                    String[] res = tester.apply(k);
                    SwingUtilities.invokeLater(() -> {
                        testBtn.setEnabled(true);
                        boolean ok = "ok".equals(res[0]);
                        status.setForeground(ok ? new Color(0x3DDC97) : new Color(0xFF7A9A));
                        status.setText(res[1]);
                    });
                }, "proton-key-test");
                th.setDaemon(true);
                th.start();
            });

            add(top);
            add(Box.createVerticalStrut(9));
            add(inputRow);
            add(Box.createVerticalStrut(8));
            add(foot);
        }

        String getKey() {
            char[] pw = field.getPassword();
            return pw == null ? "" : new String(pw).trim();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 16, 16);
                g2.setColor(new Color(255, 255, 255, 9));
                g2.fill(rr);
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 10),
                        0, h, new Color(255, 255, 255, 2)));
                g2.fill(rr);
                g2.setColor(new Color(255, 255, 255, 28));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(rr);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    @SuppressWarnings("serial")
    private static final class BadgeLabel extends JLabel {
        BadgeLabel(String text) {
            super(text);
            setFont(Fnt.get(Font.PLAIN, 9.5f));
            setForeground(new Color(0xC6CBFF));
            setBorder(new EmptyBorder(2, 9, 2, 9));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, h, h);
                g2.setColor(new Color(109, 107, 255, 34));
                g2.fill(rr);
                g2.setColor(new Color(168, 140, 255, 65));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(rr);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    @SuppressWarnings("serial")
    private static final class KeyPasswordField extends JPasswordField {
        private final String placeholder;
        KeyPasswordField(String placeholder) {
            this.placeholder = placeholder;
            setOpaque(false);
            setEchoChar('\u2022');
            setForeground(Theme.TEXT);
            setCaretColor(Theme.TEXT);
            setFont(Fnt.get(Font.PLAIN, 12.5f));
            setBorder(new EmptyBorder(8, 12, 8, 6));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getPassword().length == 0) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(getFont());
                    g2.setColor(Theme.TEXT3);
                    FontMetrics fm = g2.getFontMetrics();
                    int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                    g2.drawString(placeholder, 12, y);
                } finally {
                    g2.dispose();
                }
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class EyeButton extends JButton {
        private boolean shown = false;
        EyeButton(KeyPasswordField target) {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(36, 36));
            setToolTipText("Show/Hide key");
            addActionListener(e -> {
                shown = !shown;
                target.setEchoChar(shown ? (char) 0 : '\u2022');
                repaint();
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                float cx = w / 2f, cy = h / 2f;
                boolean hover = getModel().isRollover();
                g2.setColor(shown ? new Color(0xC6CBFF) : (hover ? Theme.TEXT : Theme.TEXT2));
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D eye = new Path2D.Float();
                eye.moveTo(cx - 7.5f, cy);
                eye.quadTo(cx, cy - 5.5f, cx + 7.5f, cy);
                eye.quadTo(cx, cy + 5.5f, cx - 7.5f, cy);
                g2.draw(eye);
                g2.fill(new Ellipse2D.Float(cx - 2.2f, cy - 2.2f, 4.4f, 4.4f));
                if (!shown) {
                    g2.draw(new Line2D.Float(cx - 6.5f, cy + 5.5f, cx + 6.5f, cy - 5.5f));
                }
            } finally {
                g2.dispose();
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class SmallButton extends JButton {
        SmallButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(62, 38));
            setMinimumSize(new Dimension(62, 38));
            setMaximumSize(new Dimension(62, 38));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                boolean hover = isEnabled() && getModel().isRollover();
                RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 10, 10);
                g2.setColor(new Color(255, 255, 255, hover ? 22 : 12));
                g2.fill(rr);
                g2.setColor(new Color(255, 255, 255, hover ? 56 : 32));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(rr);
                g2.setFont(Fnt.get(Font.BOLD, 11.5f));
                g2.setColor(isEnabled() ? Theme.TEXT : Theme.TEXT3);
                FontMetrics fm = g2.getFontMetrics();
                String t = getText();
                g2.drawString(t, (w - fm.stringWidth(t)) / 2,
                        (h + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Primary / secondary pill button. */
    @SuppressWarnings("serial")
    static final class SetupButton extends JButton {
        private final String title;
        private final boolean primary;

        SetupButton(String title, boolean primary) {
            this.title = title;
            this.primary = primary;
            setText("");
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            int w = primary ? 160 : 96;
            setPreferredSize(new Dimension(w, 40));
            setMaximumSize(new Dimension(w, 40));
            getAccessibleContext().setAccessibleName(title);
        }

        @Override
        public void setEnabled(boolean b) {
            super.setEnabled(b);
            setCursor(Cursor.getPredefinedCursor(b ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                boolean en = isEnabled();
                boolean hover = en && getModel().isRollover();
                boolean press = en && getModel().isPressed();
                RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, h, h);

                if (primary && en) {
                    g2.setPaint(new LinearGradientPaint(
                            new Point2D.Float(0, 0), new Point2D.Float(w, h),
                            new float[]{0f, 0.5f, 1f},
                            new Color[]{new Color(0x3D7BFF), new Color(0xA855F7), new Color(0xFF4DD0)}));
                    g2.fill(rr);
                    g2.setColor(new Color(255, 255, 255, hover ? 72 : 42));
                    g2.draw(rr);
                } else {
                    g2.setColor(new Color(255, 255, 255, hover ? 20 : 10));
                    g2.fill(rr);
                    g2.setColor(new Color(255, 255, 255, hover ? 46 : 24));
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(rr);
                }
                if (press) {
                    g2.setColor(new Color(0, 0, 0, 40));
                    g2.fill(rr);
                }
                g2.setFont(Fnt.get(Font.BOLD, 12.5f));
                g2.setColor(en ? Color.WHITE : Theme.TEXT3);
                FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(title)) / 2;
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(title, tx, ty);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Paints the aurora backdrop, liquid-glass card, and supports window dragging. */
    @SuppressWarnings("serial")
    private final class SetupPanel extends JPanel {
        private Point dragStart;
        SetupPanel() {
            setOpaque(true);
            setBackground(Theme.BG);
            MouseAdapter dragger = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { dragStart = e.getPoint(); }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    Point p = SetupWindow.this.getLocation();
                    SetupWindow.this.setLocation(p.x + e.getX() - dragStart.x,
                                                 p.y + e.getY() - dragStart.y);
                }
            };
            addMouseListener(dragger);
            addMouseMotionListener(dragger);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x0B0920), 0, h, Theme.BG));
                g2.fillRect(0, 0, w, h);

                g2.setPaint(new RadialGradientPaint(
                        new Point2D.Float(w * 0.20f, h * 0.16f), 290f,
                        new float[]{0f, 1f},
                        new Color[]{Theme.alpha(new Color(0x3D7BFF), 0.20), new Color(0, 0, 0, 0)}));
                g2.fill(new Ellipse2D.Float(w * 0.20f - 290f, h * 0.16f - 290f, 580f, 580f));

                g2.setPaint(new RadialGradientPaint(
                        new Point2D.Float(w * 0.84f, h * 0.86f), 270f,
                        new float[]{0f, 1f},
                        new Color[]{Theme.alpha(new Color(0xFF4DD0), 0.14), new Color(0, 0, 0, 0)}));
                g2.fill(new Ellipse2D.Float(w * 0.84f - 270f, h * 0.86f - 270f, 540f, 540f));

                RoundRectangle2D card = new RoundRectangle2D.Float(22.5f, 22.5f, w - 45f, h - 45f, 26, 26);
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

        int ly = (h - 20) / 2;
        g2.drawImage(BrandLogo.getScaled(20), 12, ly, 20, 20, null);

        g2.setFont(Fnt.get(Font.BOLD, 12f));
        FontMetrics fm = g2.getFontMetrics();
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
    final Brain brain = new Brain(main.googleKey, main.openrouterKey);
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

    ProtonFrame() {
        super("PROTON AI");
        setUndecorated(true);
        setBackground(Theme.BG);
        try { setIconImages(BrandLogo.getWindowIcons()); } catch (Throwable ignored) {}
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
        int maxH = Math.max(300, getLayeredPane().getHeight() - 16);
        int h = Math.min(d.height, maxH);
        int y = Math.max(8, Math.min(p.y, getLayeredPane().getHeight() - h - 8));
        settingsPanel.setBounds(p.x, y, d.width, h);
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
            BufferedImage img = BrandLogo.getScaled(32);

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
        try { brain.warmWinTts.shutdown(); } catch (Throwable ignored) {}
        try { if (companion != null) companion.disposeOverlay(); } catch (Throwable ignored) {}
        try { if (siri != null) siri.disposeOverlay(); } catch (Throwable ignored) {}
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
        try { brain.warmWinTts.shutdown(); } catch (Throwable ignored) {}
        try { if (companion != null) companion.disposeOverlay(); } catch (Throwable ignored) {}
        try { if (siri != null) siri.disposeOverlay(); } catch (Throwable ignored) {}
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

        if (!brain.hasGoogle()) {
            s.transcript = "\u201C add a free Google key in Settings for voice input \u201D";
            s.transcriptTtl = 6f;
            return;
        }

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
                if (!brain.hasGoogle()) {
                    SwingUtilities.invokeLater(() -> {
                        if (gen != queryGen) return;
                        setThinking(false);
                        s.transcript = "\u201C add a free Google key in Settings for voice input \u201D";
                        s.transcriptTtl = 6f;
                    });
                    return;
                }
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
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                brain.speak(spoken, () -> {
                    if (gen == queryGen && !done.get()) s.speaking = true;
                    SwingUtilities.invokeLater(() -> {
                        if (gen != queryGen || done.get()) return;
                        s.speaking = true;
                        s.transcript = "\u201C" + spoken + "\u201D";
                        s.transcriptTtl = 6.5f;
                    });
                });
            } finally {
                done.set(true);
                s.speaking = false;
            }
        };

        new Thread(() -> {
            try {
                String reply = brain.process(u);
                if (reply == null || reply.trim().isEmpty()) reply = "done.";
                final String r = reply.trim();
                if (gen != queryGen) return;
                // SPEAKING phase: orb/status reflect s.speaking via tick().
                // (If Proton already acknowledged out loud while working, only the follow-up is spoken here.)
                String toSay = brain.remainingSpeech(r);
                if (toSay == null || toSay.trim().isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        if (gen != queryGen) return;
                        s.transcript = "\u201C" + r + "\u201D";
                        s.transcriptTtl = 6.5f;
                        setThinking(false);
                    });
                } else {
                    final java.util.concurrent.atomic.AtomicBoolean speechDone = new java.util.concurrent.atomic.AtomicBoolean(false);
                    try {
                        brain.speak(toSay, () -> {
                            if (gen == queryGen && !speechDone.get()) s.speaking = true;
                            SwingUtilities.invokeLater(() -> {
                                if (gen != queryGen) return;
                                if (!speechDone.get()) s.speaking = true;
                                s.transcript = "\u201C" + r + "\u201D";
                                s.transcriptTtl = 6.5f;
                                setThinking(false);
                            });
                        });
                    } finally {
                        speechDone.set(true);
                        s.speaking = false;
                    }
                    if (gen == queryGen) {
                        SwingUtilities.invokeLater(() -> {
                            if (gen != queryGen) return;
                            if (s.thinking) setThinking(false);
                            s.speaking = false;
                        });
                    }
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
                if (!brain.hasGoogle() || !cb.canListen()) {
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

/**
 * Warm persistent Windows SAPI TTS worker (hidden PowerShell + System.Speech).
 * Eliminates cold powershell.exe startup latency on local TTS fallback and
 * supports instant SpeakAsyncCancelAll() barge-in.
 */
final class WarmWinTts {
    private volatile boolean alive = true;
    private volatile Process proc;
    private volatile BufferedWriter stdinWriter;
    private volatile Thread reader;

    private final Object procLock = new Object();
    private final Object speechLock = new Object();
    private final Object waitLock = new Object();

    private volatile boolean startedSignal = false;
    private volatile boolean doneSignal = false;
    private volatile boolean cancelled = false;

    void warmUpAsync() {
        if (!Brain.isWin() || !alive) return;
        Thread t = new Thread(this::ensureStarted, "proton-win-tts-warm");
        t.setDaemon(true);
        t.start();
    }

    boolean speak(String text, Runnable onAudioStart) {
        if (!Brain.isWin() || !alive || text == null || text.trim().isEmpty()) return false;
        synchronized (speechLock) {
            if (!alive) return false;
            if (!ensureStarted()) return false;
            synchronized (waitLock) {
                cancelled = false;
                startedSignal = false;
                doneSignal = false;
            }
            String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            if (!sendLine("SAY:" + b64)) {
                killProc();
                if (!ensureStarted() || !sendLine("SAY:" + b64)) return false;
            }

            long startDeadline = System.currentTimeMillis() + 10000L;
            synchronized (waitLock) {
                while (!startedSignal && !doneSignal && !cancelled && alive) {
                    Process p = proc;
                    if (p == null || !p.isAlive()) break;
                    long rem = startDeadline - System.currentTimeMillis();
                    if (rem <= 0) break;
                    try { waitLock.wait(Math.min(100L, rem)); }
                    catch (InterruptedException ie) { cancelled = true; break; }
                }
            }
            if (cancelled || !alive) return true;
            if (onAudioStart != null) {
                try { onAudioStart.run(); } catch (Throwable ignored) {}
            }
            long doneDeadline = System.currentTimeMillis() + 120000L;
            synchronized (waitLock) {
                while (!doneSignal && !cancelled && alive) {
                    Process p = proc;
                    if (p == null || !p.isAlive()) break;
                    long rem = doneDeadline - System.currentTimeMillis();
                    if (rem <= 0) break;
                    try { waitLock.wait(Math.min(200L, rem)); }
                    catch (InterruptedException ie) { cancelled = true; break; }
                }
            }
            return startedSignal || doneSignal || cancelled;
        }
    }

    void stop() {
        synchronized (waitLock) {
            cancelled = true;
            waitLock.notifyAll();
        }
        sendLine("STOP");
    }

    void shutdown() {
        alive = false;
        synchronized (waitLock) {
            cancelled = true;
            waitLock.notifyAll();
        }
        sendLine("EXIT");
        Process p = proc;
        if (p != null) {
            try { p.waitFor(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }
        killProc();
    }

    private boolean ensureStarted() {
        synchronized (procLock) {
            if (!alive) return false;
            Process cur = proc;
            if (cur != null && cur.isAlive() && stdinWriter != null) return true;
            killProc();
            try {
                String enc = Base64.getEncoder().encodeToString(
                        script().getBytes(StandardCharsets.UTF_16LE));
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile",
                        "-NonInteractive", "-WindowStyle", "Hidden",
                        "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc);
                pb.redirectErrorStream(true);
                final Process p = pb.start();
                final BufferedWriter bw = new BufferedWriter(
                        new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
                proc = p;
                stdinWriter = bw;
                Thread r = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (alive && proc == p && (line = br.readLine()) != null) {
                            String cmd = line.trim();
                            if ("START".equals(cmd)) {
                                synchronized (waitLock) {
                                    startedSignal = true;
                                    waitLock.notifyAll();
                                }
                            } else if ("DONE".equals(cmd)) {
                                synchronized (waitLock) {
                                    doneSignal = true;
                                    waitLock.notifyAll();
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        synchronized (waitLock) {
                            if (proc == p) proc = null;
                            waitLock.notifyAll();
                        }
                    }
                }, "proton-win-tts-reader");
                r.setDaemon(true);
                reader = r;
                r.start();
                return true;
            } catch (Throwable t) {
                killProc();
                return false;
            }
        }
    }

    private boolean sendLine(String cmd) {
        BufferedWriter bw = stdinWriter;
        Process p = proc;
        if (bw == null || p == null || !p.isAlive()) return false;
        try {
            synchronized (bw) {
                bw.write(cmd);
                bw.newLine();
                bw.flush();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void killProc() {
        BufferedWriter bw = stdinWriter;
        stdinWriter = null;
        if (bw != null) { try { bw.close(); } catch (Throwable ignored) {} }
        Process p = proc;
        proc = null;
        if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        Thread r = reader;
        reader = null;
        if (r != null && r != Thread.currentThread()) {
            try { r.join(250); } catch (InterruptedException ignored) {}
        }
    }

    private static String script() {
        return "$ErrorActionPreference='SilentlyContinue'\n"
            + "Add-Type -AssemblyName System.Speech\n"
            + "Add-Type -ReferencedAssemblies System.Speech -TypeDefinition @\"\n"
            + "using System;\n"
            + "using System.Speech.Synthesis;\n"
            + "using System.Text;\n"
            + "public class ProtonWinTts {\n"
            + "    public static void Run() {\n"
            + "        Console.InputEncoding = Encoding.UTF8;\n"
            + "        Console.OutputEncoding = Encoding.UTF8;\n"
            + "        using (var synth = new SpeechSynthesizer()) {\n"
            + "            try { synth.SelectVoiceByHints(VoiceGender.Male, VoiceAge.Adult); } catch {}\n"
            + "            synth.Volume = 100;\n"
            + "            synth.Rate = 1;\n"
            + "            var stateLock = new object();\n"
            + "            var outLock = new object();\n"
            + "            Prompt currentPrompt = null;\n"
            + "            Prompt cancelledPrompt = null;\n"
            + "            synth.SpeakCompleted += (s, e) => {\n"
            + "                lock (stateLock) {\n"
            + "                    if (e.Prompt == cancelledPrompt) return;\n"
            + "                    if (e.Prompt == currentPrompt) currentPrompt = null;\n"
            + "                }\n"
            + "                lock (outLock) {\n"
            + "                    Console.WriteLine(\"DONE\");\n"
            + "                    Console.Out.Flush();\n"
            + "                }\n"
            + "            };\n"
            + "            string line;\n"
            + "            while ((line = Console.In.ReadLine()) != null) {\n"
            + "                if (line.StartsWith(\"SAY:\")) {\n"
            + "                    lock (stateLock) {\n"
            + "                        cancelledPrompt = currentPrompt;\n"
            + "                        currentPrompt = null;\n"
            + "                    }\n"
            + "                    synth.SpeakAsyncCancelAll();\n"
            + "                    string text = Encoding.UTF8.GetString(Convert.FromBase64String(line.Substring(4)));\n"
            + "                    lock (outLock) {\n"
            + "                        Console.WriteLine(\"START\");\n"
            + "                        Console.Out.Flush();\n"
            + "                    }\n"
            + "                    lock (stateLock) {\n"
            + "                        currentPrompt = synth.SpeakAsync(text);\n"
            + "                    }\n"
            + "                } else if (line == \"STOP\") {\n"
            + "                    synth.SpeakAsyncCancelAll();\n"
            + "                } else if (line == \"EXIT\") {\n"
            + "                    lock (stateLock) {\n"
            + "                        cancelledPrompt = currentPrompt;\n"
            + "                        currentPrompt = null;\n"
            + "                    }\n"
            + "                    synth.SpeakAsyncCancelAll();\n"
            + "                    break;\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "}\n"
            + "\"@\n"
            + "[ProtonWinTts]::Run()\n";
    }
}

/** Frosted obsidian liquid-glass settings overlay opened by the rail's gear icon. */
@SuppressWarnings("serial")
class SettingsPanel extends JComponent {
    private static final int PANEL_W = 396;
    private static final int CONTENT_H = 536;

    private final Runnable onClose;
    private final java.util.function.Consumer<Boolean> onWake;
    private final java.util.function.Consumer<Integer> onNoise;
    private final Runnable onChangeKeys;
    private boolean wakeOn;
    private int noiseLevel;
    private float wakeAnim;
    private float autoAnim;
    private javax.swing.Timer toggleTimer;
    private int scrollY = 0;

    private boolean hoverClose = false, hoverChange = false, hoverUpdate = false;
    private boolean hoverWake = false, hoverAuto = false;
    private int hoverSeg = -1;

    private final Rectangle toggleR = new Rectangle();
    private final Rectangle wakeRowR = new Rectangle();
    private final Rectangle closeR = new Rectangle();
    private final Rectangle keysR = new Rectangle();
    private final Rectangle updateR = new Rectangle();
    private final Rectangle autoR = new Rectangle();
    private final Rectangle autoRowR = new Rectangle();
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
        this.wakeAnim = wakeOn ? 1f : 0f;
        this.autoAnim = UpdateKit.auto ? 1f : 0f;
        setOpaque(false);
        setPreferredSize(new Dimension(PANEL_W, CONTENT_H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        UpdateKit.setRepaintHook(this::repaint);

        toggleTimer = new javax.swing.Timer(16, e -> {
            float wt = SettingsPanel.this.wakeOn ? 1f : 0f;
            float at = UpdateKit.auto ? 1f : 0f;
            wakeAnim += (wt - wakeAnim) * 0.28f;
            autoAnim += (at - autoAnim) * 0.28f;
            boolean doneW = Math.abs(wt - wakeAnim) < 0.01f;
            boolean doneA = Math.abs(at - autoAnim) < 0.01f;
            if (doneW) wakeAnim = wt;
            if (doneA) autoAnim = at;
            repaint();
            if (doneW && doneA) toggleTimer.stop();
        });
        toggleTimer.setCoalesce(true);

        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                Point p = e.getPoint();
                if (closeR.contains(p)) { SettingsPanel.this.onClose.run(); return; }
                if (p.y < 58) return;
                if (keysR.contains(p)) { SettingsPanel.this.onChangeKeys.run(); return; }
                if (updateR.contains(p)) { UpdateKit.pressUpdate(); repaint(); return; }
                if (autoR.contains(p) || autoRowR.contains(p)) {
                    UpdateKit.setAuto(!UpdateKit.auto);
                    toggleTimer.start();
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
                if (toggleR.contains(p) || wakeRowR.contains(p)) {
                    SettingsPanel.this.wakeOn = !SettingsPanel.this.wakeOn;
                    toggleTimer.start();
                    repaint();
                    SettingsPanel.this.onWake.accept(SettingsPanel.this.wakeOn);
                }
            }
            public void mouseExited(MouseEvent e) {
                if (hoverClose || hoverChange || hoverUpdate || hoverWake || hoverAuto || hoverSeg != -1) {
                    hoverClose = hoverChange = hoverUpdate = hoverWake = hoverAuto = false;
                    hoverSeg = -1;
                    repaint();
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                Point p = e.getPoint();
                boolean hc = closeR.contains(p);
                boolean hch = p.y >= 58 && keysR.contains(p);
                boolean hu = p.y >= 58 && updateR.contains(p);
                boolean hw = p.y >= 58 && (toggleR.contains(p) || wakeRowR.contains(p));
                boolean ha = p.y >= 58 && (autoR.contains(p) || autoRowR.contains(p));
                int hs = -1;
                if (p.y >= 58) {
                    for (int i = 0; i < segR.length; i++) {
                        if (segR[i].contains(p)) { hs = i; break; }
                    }
                }
                if (hc != hoverClose || hch != hoverChange || hu != hoverUpdate
                        || hw != hoverWake || ha != hoverAuto || hs != hoverSeg) {
                    hoverClose = hc; hoverChange = hch; hoverUpdate = hu;
                    hoverWake = hw; hoverAuto = ha; hoverSeg = hs;
                    repaint();
                }
            }
        });

        addMouseWheelListener(e -> {
            int maxScroll = Math.max(0, CONTENT_H - getHeight());
            if (maxScroll <= 0) { scrollY = 0; return; }
            scrollY = Math.max(0, Math.min(maxScroll, scrollY + e.getUnitsToScroll() * 14));
            repaint();
        });
    }

    void setWake(boolean on) { wakeOn = on; wakeAnim = on ? 1f : 0f; repaint(); }
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

    private static void drawSubCard(Graphics2D g2, int x, int y, int w, int h) {
        RoundRectangle2D card = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, 14f, 14f);
        g2.setColor(new Color(255, 255, 255, 8));
        g2.fill(card);
        g2.setColor(new Color(255, 255, 255, 18));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(card);
    }

    private static void drawPillToggle(Graphics2D g2, int x, int y, float anim, boolean hover) {
        int tw = 44, th = 24;
        RoundRectangle2D track = new RoundRectangle2D.Float(x, y, tw, th, th, th);
        if (anim > 0.05f) {
            Color offCol = new Color(0x26283B);
            Color c1 = Theme.mix(offCol, new Color(0x38BDF8), anim);
            Color c2 = Theme.mix(offCol, new Color(0x8B5CF6), anim);
            g2.setPaint(new LinearGradientPaint(new Point2D.Float(x, y), new Point2D.Float(x + tw, y),
                    new float[]{0f, 1f}, new Color[]{c1, c2}));
        } else {
            g2.setColor(new Color(0x26283B));
        }
        g2.fill(track);
        g2.setColor(new Color(255, 255, 255, hover ? 64 : (anim > 0.5f ? 44 : 24)));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(track);
        float kd = th - 6f;
        float kx = x + 3f + (tw - kd - 6f) * anim;
        g2.setColor(Color.WHITE);
        g2.fill(new Ellipse2D.Double(kx, y + 3f, kd, kd));
    }

    private static int drawStatusChip(Graphics2D g2, int x, int y, String label, boolean active, Color accent) {
        g2.setFont(Fnt.get(Font.BOLD, 9.5f));
        FontMetrics fm = g2.getFontMetrics();
        int dot = active ? 8 : 0;
        int cw = fm.stringWidth(label) + 16 + dot;
        int ch = 18;
        RoundRectangle2D chip = new RoundRectangle2D.Float(x, y, cw, ch, ch, ch);
        if (active) {
            g2.setColor(Theme.alpha(accent, 0.14));
            g2.fill(chip);
            g2.setColor(Theme.alpha(accent, 0.42));
        } else {
            g2.setColor(new Color(255, 255, 255, 10));
            g2.fill(chip);
            g2.setColor(new Color(255, 255, 255, 26));
        }
        g2.setStroke(new BasicStroke(1f));
        g2.draw(chip);
        int tx = x + 8;
        if (active) {
            g2.setColor(accent);
            g2.fill(new Ellipse2D.Double(x + 7, y + 6.5, 5, 5));
            tx += 8;
        }
        g2.setColor(active ? Theme.mix(accent, Color.WHITE, 0.35f) : Theme.TEXT3);
        g2.drawString(label, tx, y + (ch + fm.getAscent() - fm.getDescent()) / 2);
        return cw;
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int w = getWidth(), h = getHeight();
        int maxScroll = Math.max(0, CONTENT_H - h);
        if (scrollY > maxScroll) scrollY = maxScroll;

        RoundRectangle2D rr = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 20, 20);
        g2.setColor(new Color(9, 8, 18, 242));
        g2.fill(rr);
        g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 14),
                0, 90, new Color(255, 255, 255, 0)));
        g2.fill(rr);
        g2.setColor(new Color(255, 255, 255, 28));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(rr);

        // ---- Header ----
        g2.drawImage(BrandLogo.getScaled(22), 20, 17, 22, 22, null);
        g2.setFont(Fnt.get(Font.BOLD, 14.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Settings", 50, 33);
        FontMetrics sfm = g2.getFontMetrics();
        int tagX = 50 + sfm.stringWidth("Settings") + 8;
        g2.setFont(Fnt.get(Font.BOLD, 9.5f));
        g2.setColor(Theme.TEXT3);
        g2.drawString("PROTON AI", tagX, 32);

        closeR.setBounds(w - 20 - 28, 14, 28, 28);
        if (hoverClose) {
            g2.setColor(new Color(255, 255, 255, 16));
            g2.fill(new Ellipse2D.Double(closeR.x, closeR.y, closeR.width, closeR.height));
        }
        g2.setColor(hoverClose ? Theme.TEXT : new Color(0xA3A5BA));
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = closeR.x + 14, cy = closeR.y + 14;
        g2.draw(new Line2D.Double(cx - 4, cy - 4, cx + 4, cy + 4));
        g2.draw(new Line2D.Double(cx + 4, cy - 4, cx - 4, cy + 4));

        // ---- Scrollable Glass Sub-Cards Area ----
        Shape prevClip = g2.getClip();
        g2.setClip(new RoundRectangle2D.Float(1, 56, w - 2, h - 57, 18, 18));
        int yOff = -scrollY;
        int cardX = 20, cardW = w - 40;

        // ---- Card 1: VOICE & AUDIO ----
        int c1y = 60 + yOff, c1h = 186;
        drawSubCard(g2, cardX, c1y, cardW, c1h);
        g2.setFont(Fnt.get(Font.BOLD, 9.5f));
        g2.setColor(new Color(0x7B819E));
        g2.drawString("VOICE & AUDIO", cardX + 16, c1y + 21);

        wakeRowR.setBounds(cardX + 8, c1y + 28, cardW - 16, 56);
        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Wake PROTON", cardX + 16, c1y + 46);
        int wTitleEnd = cardX + 16 + g2.getFontMetrics().stringWidth("Wake PROTON") + 8;
        drawStatusChip(g2, wTitleEnd, c1y + 32,
                wakeOn ? "Listening" : "Off", wakeOn, new Color(0x34D399));

        g2.setFont(Fnt.get(Font.PLAIN, 10.5f));
        g2.setColor(Theme.TEXT2);
        g2.drawString("Say \u201CHey Proton\u201D \u2014 listens from tray", cardX + 16, c1y + 66);

        int tx = cardX + cardW - 16 - 44, ty = c1y + 39;
        toggleR.setBounds(tx, ty, 44, 24);
        drawPillToggle(g2, tx, ty, wakeAnim, hoverWake);

        // Internal divider
        g2.setColor(new Color(255, 255, 255, 14));
        g2.drawLine(cardX + 16, c1y + 84, cardX + cardW - 16, c1y + 84);

        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Noise cancellation", cardX + 16, c1y + 108);
        g2.setFont(Fnt.get(Font.PLAIN, 10.5f));
        g2.setColor(Theme.TEXT2);
        g2.drawString("Filters fans, hum & background noise", cardX + 16, c1y + 126);

        String[] names = {"Off", "Normal", "Strong"};
        int bx = cardX + 16, by = c1y + 140, bw = cardW - 32, bh = 30, sw = bw / 3;
        g2.setColor(new Color(255, 255, 255, 10));
        g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        g2.setColor(new Color(255, 255, 255, 20));
        g2.draw(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        for (int i = 0; i < 3; i++) {
            segR[i].setBounds(bx + i * sw, by, i == 2 ? bw - 2 * sw : sw, bh);
            Rectangle r = segR[i];
            if (i == noiseLevel) {
                RoundRectangle2D pill = new RoundRectangle2D.Float(r.x + 3, r.y + 3, r.width - 6, r.height - 6, bh, bh);
                if (i == 0) {
                    g2.setColor(new Color(0x474B66));
                } else {
                    g2.setPaint(new LinearGradientPaint(
                            new Point2D.Float(r.x, r.y), new Point2D.Float(r.x + r.width, r.y),
                            new float[]{0f, 0.5f, 1f},
                            new Color[]{new Color(0x3B82F6), new Color(0xA855F7), new Color(0xEC4899)}));
                }
                g2.fill(pill);
            } else if (i == hoverSeg) {
                RoundRectangle2D pill = new RoundRectangle2D.Float(r.x + 3, r.y + 3, r.width - 6, r.height - 6, bh, bh);
                g2.setColor(new Color(255, 255, 255, 12));
                g2.fill(pill);
            }
            g2.setFont(Fnt.get(Font.BOLD, 11.5f));
            g2.setColor(i == noiseLevel ? Color.WHITE : (i == hoverSeg ? Theme.TEXT : Theme.TEXT2));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(names[i], r.x + (r.width - fm.stringWidth(names[i])) / 2,
                    r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
        }

        // ---- Card 2: AI PROVIDERS (BYOK) ----
        int c2y = c1y + c1h + 12, c2h = 80;
        drawSubCard(g2, cardX, c2y, cardW, c2h);
        g2.setFont(Fnt.get(Font.BOLD, 9.5f));
        g2.setColor(new Color(0x7B819E));
        g2.drawString("AI PROVIDERS (BYOK)", cardX + 16, c2y + 21);

        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("API keys", cardX + 16, c2y + 42);

        int chipX = cardX + 16;
        int chipY = c2y + 51;
        boolean hasG = !main.googleKey.isEmpty();
        boolean hasO = !main.openrouterKey.isEmpty();
        if (hasG) {
            chipX += drawStatusChip(g2, chipX, chipY, "Google", true, new Color(0x34D399)) + 6;
        }
        if (hasO) {
            drawStatusChip(g2, chipX, chipY, "OpenRouter", true, new Color(0x38BDF8));
        }
        if (!hasG && !hasO) {
            drawStatusChip(g2, chipX, chipY, "No keys set", false, Theme.TEXT3);
        }

        int pw = 76, ph = 26, px = cardX + cardW - 16 - pw, py = c2y + 34;
        keysR.setBounds(px - 6, c2y + 24, pw + 12, 46);
        RoundRectangle2D chBtn = new RoundRectangle2D.Float(px, py, pw, ph, ph, ph);
        g2.setColor(new Color(255, 255, 255, hoverChange ? 30 : 18));
        g2.fill(chBtn);
        g2.setColor(hoverChange ? Theme.alpha(new Color(0x38BDF8), 0.55) : new Color(255, 255, 255, 36));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(chBtn);
        g2.setFont(Fnt.get(Font.BOLD, 11f));
        g2.setColor(Theme.TEXT);
        FontMetrics fm2 = g2.getFontMetrics();
        g2.drawString("Change", px + (pw - fm2.stringWidth("Change")) / 2,
                py + (ph + fm2.getAscent() - fm2.getDescent()) / 2);

        // ---- Card 3: SYSTEM & UPDATES ----
        int c3y = c2y + c2h + 12, c3h = 164;
        drawSubCard(g2, cardX, c3y, cardW, c3h);
        g2.setFont(Fnt.get(Font.BOLD, 9.5f));
        g2.setColor(new Color(0x7B819E));
        g2.drawString("SYSTEM & UPDATES", cardX + 16, c3y + 21);

        String repo = UpdateKit.OWNER + "/" + UpdateKit.REPO;
        g2.setFont(Fnt.get(Font.PLAIN, 9.5f));
        g2.setColor(Theme.TEXT3);
        FontMetrics rf = g2.getFontMetrics();
        g2.drawString(repo, cardX + cardW - 16 - rf.stringWidth(repo), c3y + 21);

        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Updates", cardX + 16, c3y + 45);

        int ubw = 114, ubh = 28;
        int ubx = cardX + cardW - 16 - ubw, uby = c3y + 34;
        updateR.setBounds(ubx, uby, ubw, ubh);
        boolean busy = UpdateKit.isBusy();
        RoundRectangle2D updateBtn = new RoundRectangle2D.Float(ubx, uby, ubw, ubh, ubh, ubh);
        if (busy) {
            g2.setColor(new Color(0x3A3550));
            g2.fill(updateBtn);
        } else {
            g2.setPaint(new LinearGradientPaint(
                    new Point2D.Float(ubx, uby), new Point2D.Float(ubx + ubw, uby),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(0x38BDF8), new Color(0x8B5CF6), new Color(0xEC4899)}));
            g2.fill(updateBtn);
        }
        g2.setColor(new Color(255, 255, 255, busy ? 30 : (hoverUpdate ? 120 : 58)));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(updateBtn);
        String updateLabel = UpdateKit.state == UpdateKit.IDLE ? "Check Updates" : UpdateKit.buttonLabel();
        g2.setFont(Fnt.get(Font.BOLD, 10.5f));
        g2.setColor(busy ? new Color(0xC3C5D6) : Color.WHITE);
        FontMetrics uf = g2.getFontMetrics();
        g2.drawString(updateLabel, ubx + (ubw - uf.stringWidth(updateLabel)) / 2,
                uby + (ubh + uf.getAscent() - uf.getDescent()) / 2);

        String stText = UpdateKit.state == UpdateKit.IDLE
                ? ("Ready \u00B7 " + UpdateKit.BRANCH)
                : UpdateKit.message;
        Color stColor = UpdateKit.state == UpdateKit.IDLE ? Theme.TEXT2 : UpdateKit.statusColor();
        g2.setFont(Fnt.get(Font.PLAIN, 10.5f));
        g2.setColor(stColor);
        String[] lines = wrap(g2, stText, cardW - 32 - ubw - 10, 2);
        int ly = c3y + 64;
        for (String line : lines) {
            g2.drawString(line, cardX + 16, ly);
            ly += 14;
        }

        // Internal divider
        g2.setColor(new Color(255, 255, 255, 14));
        g2.drawLine(cardX + 16, c3y + 98, cardX + cardW - 16, c3y + 98);

        autoRowR.setBounds(cardX + 8, c3y + 102, cardW - 16, 54);
        g2.setFont(Fnt.get(Font.BOLD, 12.5f));
        g2.setColor(Theme.TEXT);
        g2.drawString("Auto update", cardX + 16, c3y + 123);
        g2.setFont(Fnt.get(Font.PLAIN, 10.5f));
        g2.setColor(Theme.TEXT2);
        g2.drawString("Checks GitHub every 10 minutes", cardX + 16, c3y + 142);

        int atx = cardX + cardW - 16 - 44, aty = c3y + 116;
        autoR.setBounds(atx, aty, 44, 24);
        drawPillToggle(g2, atx, aty, autoAnim, hoverAuto);

        g2.setClip(prevClip);
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
    private boolean brandHover = false;
    private final Rectangle brandTileR = new Rectangle(17, 13, 40, 40);

    RailPanel(S s, GlassOverlay glass) {
        this.s = s;
        this.glass = glass;
        setOpaque(false);
        setPreferredSize(new Dimension(W, 100));
        setLayout(null);
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                boolean h = brandTileR.contains(e.getPoint());
                if (h != brandHover) { brandHover = h; repaint(); }
            }
        });
        addMouseListener(new MouseAdapter() {
            public void mouseExited(MouseEvent e) {
                if (brandHover) { brandHover = false; repaint(); }
            }
        });

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

        int ls = 40;
        int lx = (w - ls) / 2;
        int ly = 13;
        brandTileR.setBounds(lx, ly, ls, ls);
        if (brandHover) {
            float gcx = lx + ls / 2f, gcy = ly + ls / 2f;
            g2.setPaint(new RadialGradientPaint(new Point2D.Float(gcx, gcy), 26f,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{Theme.alpha(new Color(0x38BDF8), 0.24),
                                Theme.alpha(new Color(0xA855F7), 0.12),
                                new Color(0, 0, 0, 0)}));
            g2.fill(new Ellipse2D.Float(gcx - 26f, gcy - 26f, 52f, 52f));
        }
        RoundRectangle2D logoTile = new RoundRectangle2D.Float(lx + 0.5f, ly + 0.5f, ls - 1f, ls - 1f, 13, 13);
        g2.setColor(new Color(255, 255, 255, brandHover ? 22 : 13));
        g2.fill(logoTile);
        g2.setColor(brandHover ? Theme.alpha(new Color(0x38BDF8), 0.45) : new Color(255, 255, 255, 26));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(logoTile);
        g2.drawImage(BrandLogo.getScaled(32), lx + (ls - 32) / 2, ly + (ls - 32) / 2, 32, 32, null);

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
            if (!brain.hasGoogle()) {
                SwingUtilities.invokeLater(() -> {
                    if (!active || dismissing || g != turn) return;
                    processing = false;
                    status = "Add a free Google key in Settings";
                });
                return;
            }
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
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                brain.speak(spoken, () -> {
                    if (!done.get()) s.speaking = true;
                    SwingUtilities.invokeLater(() -> {
                        if (active && !dismissing && !done.get()) {
                            s.thinking = false;
                            s.speaking = true;
                            status = "Speaking...";
                        }
                    });
                });
            } finally {
                done.set(true);
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
                final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
                try {
                    brain.speak(say, () -> {
                        if (!done.get()) s.speaking = true;
                        SwingUtilities.invokeLater(() -> {
                            if (active && !dismissing && g == turn && !done.get()) {
                                s.thinking = false;
                                s.speaking = true;
                                status = "Speaking...";
                            }
                        });
                    });
                } catch (Throwable ignored) {}
                finally {
                    done.set(true);
                    s.speaking = false;
                }
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

    static final String GOOGLE_BASE     = "https://generativelanguage.googleapis.com/v1beta";
    static final String GEMINI_PRO      = "gemini-3.8-flash";
    static final String GEMINI_FAST     = "gemini-3.5-flash-lite";
    static final String GEMINI_STT      = "gemini-3.5-transcribe";
    static final String GEMINI_TTS      = "gemini-3.8-flash-lite-tts";

    static final String OPENROUTER_BASE = "https://openrouter.ai/api/v1";
    static final String OR_MODEL        = "nvidia/nemotron-3.5-super-vl:free";
    static final String OR_TTS_MODEL    = "fish-audio/s2.1-pro-free:free";
    static final String DEFAULT_OPENROUTER_VOICE = "536d3a5e000945adb7038665781a4aca";
    static final String DEFAULT_GEMINI_VOICE     = "Puck";
    static final String OR_REFERER      = "https://tryprotonai.pages.dev";
    static final String OR_TITLE        = "PROTON AI";

    static String openrouterVoice() {
        String v = Prefs.getString("openrouterVoice", "").trim();
        return v.isEmpty() ? DEFAULT_OPENROUTER_VOICE : v;
    }

    static final class Target {
        final boolean google;
        final String model;
        Target(boolean google, String model) { this.google = google; this.model = model; }
        @Override public String toString() { return (google ? "google:" : "openrouter:") + model; }
    }

    static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        final String provider;
        final int status;
        ApiException(String provider, int status, String body) {
            super(provider + " " + status + ": " + shortStr(body));
            this.provider = provider;
            this.status = status;
        }
        boolean isRetryable() { return status == 429 || status == 502 || status == 503; }
    }

    static class ChatMsg { final String role, content; ChatMsg(String r, String c) { role = r; content = c; } }
    static class Step { String action = ""; String target = ""; long waitMs = 0; }
    static class Plan { String speech = ""; List<Step> steps = new ArrayList<>(); }

    final String googleKey;
    final String openrouterKey;
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

    // speech playback control (barge-in) & TTS circuit breakers
    static final byte[] TTS_EOF = new byte[0];
    volatile boolean speechCancelled = false;
    volatile long openrouterTtsBlockedUntil = 0L;
    volatile long geminiTtsBlockedUntil = 0L;
    volatile Clip activeClip;
    volatile SourceDataLine activeLine;
    volatile Process speechProc;
    final WarmWinTts warmWinTts = new WarmWinTts();
    final List<TtsStream> activeTtsStreams = java.util.Collections.synchronizedList(new ArrayList<>());

    static final class TtsStream {
        final java.util.concurrent.BlockingQueue<byte[]> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        volatile AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 24000f, 16, 1, 2, 24000f, false);
        volatile Throwable error;
        volatile boolean ready = false;
        volatile boolean closed = false;
        volatile boolean hasAudio = false;
        volatile HttpURLConnection conn;
        final Object readyLock = new Object();

        void signalReady() {
            ready = true;
            synchronized (readyLock) { readyLock.notifyAll(); }
        }

        void abort() {
            closed = true;
            queue.clear();
            queue.offer(TTS_EOF);
            HttpURLConnection c = conn;
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
            signalReady();
        }
    }

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

    Brain(String gk, String ork) {
        this.googleKey = main.cleanKey(gk);
        this.openrouterKey = main.cleanKey(ork);
        warmUp(); startSkillRuntime();
    }
    boolean hasGoogle() { return !googleKey.isEmpty(); }
    boolean hasOpenRouter() { return !openrouterKey.isEmpty(); }
    boolean hasKey() { return hasGoogle() || hasOpenRouter(); }
    void log(String m) { System.out.println("[proton] " + m); }

    /* ============================================================
     * OPENROUTER (OpenAI-compatible) + GOOGLE AI STUDIO (Gemini)
     * ============================================================ */

    HttpURLConnection orConn(String path, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                URI.create(OPENROUTER_BASE + path).toURL().openConnection();
        c.setRequestProperty("Authorization", "Bearer " + openrouterKey);
        c.setRequestProperty("HTTP-Referer", OR_REFERER);
        c.setRequestProperty("X-Title", OR_TITLE);
        c.setConnectTimeout(20000);
        c.setReadTimeout(readTimeoutMs);
        return c;
    }

    String orPostJson(String path, String json, int readTimeoutMs) throws Exception {
        HttpURLConnection c = orConn(path, readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String body = readStr(in);
        if (code >= 400) throw new ApiException("openrouter", code, body);
        return body;
    }

    byte[] orPostBytes(String path, String json, int readTimeoutMs) throws Exception {
        HttpURLConnection c = orConn(path, readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new ApiException("openrouter", code, readStr(c.getErrorStream()));
        }
        return readBytes(c.getInputStream());
    }

    String orComplete(String model, String msgsJson, int maxTokens, double temp) throws Exception {
        String body = "{\"model\":" + js(model) + ",\"messages\":[" + msgsJson + "],"
                    + "\"max_tokens\":" + maxTokens + ",\"temperature\":" + temp + "}";
        String res = orPostJson("/chat/completions", body, 120000);
        Object content = dig(jparse(res), "choices", 0, "message", "content");
        if (content == null || content.toString().trim().isEmpty())
            throw new Exception("openrouter returned empty content");
        return content.toString();
    }

    String orStream(String model, String msgsJson, int maxTokens, double temp,
                    boolean unusedShowThoughts) throws Exception {
        HttpURLConnection c = orConn("/chat/completions", 180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        String body = "{\"model\":" + js(model) + ",\"messages\":[" + msgsJson + "],"
                    + "\"stream\":true,\"max_tokens\":" + maxTokens + ",\"temperature\":" + temp + "}";
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new ApiException("openrouter", code, readStr(c.getErrorStream()));
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
                    Object ct = dig(root, "choices", 0, "delta", "content");
                    if (ct != null) answer.append(ct.toString());
                } catch (Throwable ignored) {}
            }
        }
        return answer.toString();
    }

    String googlePostRaw(String model, String method, String jsonBody, int readTimeoutMs) throws Exception {
        String url = GOOGLE_BASE + "/models/" + enc(model) + ":" + method + "?key=" + enc(googleKey);
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream o = c.getOutputStream()) {
            o.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String body = readStr(in);
        if (code >= 400) throw new ApiException("google", code, body);
        return body;
    }

    @SuppressWarnings("unchecked")
    static String toGeminiBody(String msgsJson, int maxTokens, double temp,
                               boolean includeThoughts, String toolsJson) {
        StringBuilder sys = new StringBuilder();
        List<String> roles = new ArrayList<>();
        List<StringBuilder> parts = new ArrayList<>();
        try {
            Object parsed = jparse("[" + msgsJson + "]");
            if (parsed instanceof List) {
                for (Object item : (List<?>) parsed) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) item;
                    String role = m.get("role") == null ? "user" : m.get("role").toString();
                    Object co = m.get("content");
                    String content = co == null ? "" : co.toString();
                    if ("system".equals(role)) {
                        if (!content.isEmpty()) {
                            if (sys.length() > 0) sys.append("\n\n");
                            sys.append(content);
                        }
                        continue;
                    }
                    String gRole = "assistant".equals(role) ? "model" : "user";
                    if (!roles.isEmpty() && roles.get(roles.size() - 1).equals(gRole)) {
                        parts.get(parts.size() - 1).append(",{\"text\":").append(js(content)).append("}");
                    } else {
                        roles.add(gRole);
                        parts.add(new StringBuilder("{\"text\":").append(js(content)).append("}"));
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (roles.isEmpty()) {
            roles.add("user");
            parts.add(new StringBuilder("{\"text\":\" \"}"));
        }
        StringBuilder out = new StringBuilder("{");
        if (sys.length() > 0) {
            out.append("\"systemInstruction\":{\"parts\":[{\"text\":").append(js(sys.toString())).append("}]},");
        }
        out.append("\"contents\":[");
        for (int i = 0; i < roles.size(); i++) {
            if (i > 0) out.append(',');
            out.append("{\"role\":").append(js(roles.get(i))).append(",\"parts\":[").append(parts.get(i)).append("]}");
        }
        out.append("],\"generationConfig\":{\"temperature\":").append(temp)
           .append(",\"maxOutputTokens\":").append(maxTokens);
        if (includeThoughts) {
            out.append(",\"thinkingConfig\":{\"includeThoughts\":true}");
        }
        out.append("}");
        if (toolsJson != null && !toolsJson.isEmpty()) {
            out.append(",\"tools\":").append(toolsJson);
        }
        out.append("}");
        return out.toString();
    }

    static String extractGeminiText(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        Object root = jparse(body);
        Object parts = dig(root, "candidates", 0, "content", "parts");
        if (!(parts instanceof List)) return "";
        StringBuilder out = new StringBuilder();
        for (Object po : (List<?>) parts) {
            if (!(po instanceof Map)) continue;
            Map<?, ?> pm = (Map<?, ?>) po;
            if (Boolean.TRUE.equals(pm.get("thought"))) continue;
            Object t = pm.get("text");
            if (t != null) out.append(t.toString());
        }
        return out.toString();
    }

    String googleComplete(String model, String msgsJson, int maxTokens, double temp) throws Exception {
        String body = toGeminiBody(msgsJson, maxTokens, temp, false, null);
        String res = googlePostRaw(model, "generateContent", body, 120000);
        String txt = extractGeminiText(res);
        if (txt.trim().isEmpty()) throw new Exception("google returned empty content");
        return txt;
    }

    String googleStream(String model, String msgsJson, int maxTokens, double temp,
                        boolean unusedShowThoughts) throws Exception {
        return googleStreamOnce(model, msgsJson, maxTokens, temp);
    }

    private String googleStreamOnce(String model, String msgsJson, int maxTokens, double temp) throws Exception {
        String url = GOOGLE_BASE + "/models/" + enc(model)
                   + ":streamGenerateContent?alt=sse&key=" + enc(googleKey);
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        String body = toGeminiBody(msgsJson, maxTokens, temp, false, null);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new ApiException("google", code, readStr(c.getErrorStream()));
        }
        StringBuilder answer = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    Object root = jparse(data);
                    Object parts = dig(root, "candidates", 0, "content", "parts");
                    if (parts instanceof List) {
                        for (Object po : (List<?>) parts) {
                            if (!(po instanceof Map)) continue;
                            Map<?, ?> pm = (Map<?, ?>) po;
                            if (Boolean.TRUE.equals(pm.get("thought"))) continue;
                            Object t = pm.get("text");
                            if (t != null && !t.toString().isEmpty()) {
                                answer.append(t.toString());
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return answer.toString();
    }

    List<Target> fastTargets() {
        List<Target> list = new ArrayList<>();
        if (hasGoogle()) list.add(new Target(true, GEMINI_FAST));
        if (hasOpenRouter()) list.add(new Target(false, OR_MODEL));
        if (hasGoogle()) list.add(new Target(true, GEMINI_PRO));
        return list;
    }

    List<Target> deepTargets() {
        List<Target> list = new ArrayList<>();
        if (hasOpenRouter()) list.add(new Target(false, OR_MODEL));
        if (hasGoogle()) list.add(new Target(true, GEMINI_PRO));
        if (hasGoogle()) list.add(new Target(true, GEMINI_FAST));
        return list;
    }

    List<Target> visionCandidates() {
        List<Target> list = new ArrayList<>();
        if (hasGoogle()) list.add(new Target(true, GEMINI_PRO));
        if (hasOpenRouter()) list.add(new Target(false, OR_MODEL));
        if (hasGoogle()) list.add(new Target(true, GEMINI_FAST));
        return list;
    }

    String completeFast(String msgsJson, int maxTokens, double temp) throws Exception {
        List<Target> targets = fastTargets();
        if (targets.isEmpty()) throw new Exception("No API key configured.");
        Exception last = null;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            for (int retry = 0; retry < (targets.size() == 1 ? 2 : 1); retry++) {
                try {
                    return t.google
                            ? googleComplete(t.model, msgsJson, maxTokens, temp)
                            : orComplete(t.model, msgsJson, maxTokens, temp);
                } catch (Exception ex) {
                    last = ex;
                    log("fast " + t + " failed: " + shortStr(String.valueOf(ex.getMessage())));
                    if (targets.size() == 1 && retry == 0 && ex instanceof ApiException && ((ApiException) ex).isRetryable()) {
                        try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    break;
                }
            }
        }
        throw last != null ? last : new Exception("fast chat failed");
    }

    String completeDeep(String msgsJson, int maxTokens, double temp) throws Exception {
        List<Target> targets = deepTargets();
        if (targets.isEmpty()) throw new Exception("No API key configured.");
        Exception last = null;
        for (Target t : targets) {
            for (int retry = 0; retry < (targets.size() == 1 ? 2 : 1); retry++) {
                try {
                    return t.google
                            ? googleComplete(t.model, msgsJson, maxTokens, temp)
                            : orComplete(t.model, msgsJson, maxTokens, temp);
                } catch (Exception ex) {
                    last = ex;
                    log("deep " + t + " failed: " + shortStr(String.valueOf(ex.getMessage())));
                    if (targets.size() == 1 && retry == 0 && ex instanceof ApiException && ((ApiException) ex).isRetryable()) {
                        try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    break;
                }
            }
        }
        throw last != null ? last : new Exception("deep completion failed");
    }

    String streamDeep(String msgsJson, int maxTokens, double temp) throws Exception {
        List<Target> targets = deepTargets();
        if (targets.isEmpty()) throw new Exception("No API key configured.");
        Exception last = null;
        for (Target t : targets) {
            for (int retry = 0; retry < (targets.size() == 1 ? 2 : 1); retry++) {
                try {
                    String out = t.google
                            ? googleStream(t.model, msgsJson, maxTokens, temp, !GEMINI_FAST.equals(t.model))
                            : orStream(t.model, msgsJson, maxTokens, temp, true);
                    if (out != null && !out.trim().isEmpty()) return out;
                    throw new Exception(t + " returned empty stream");
                } catch (Exception ex) {
                    last = ex;
                    log("stream " + t + " failed: " + shortStr(String.valueOf(ex.getMessage())));
                    if (targets.size() == 1 && retry == 0 && ex instanceof ApiException && ((ApiException) ex).isRetryable()) {
                        try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    break;
                }
            }
        }
        throw last != null ? last : new Exception("deep stream failed");
    }

    static final String REASONING_PERSONA =
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

    /** True when the request deserves the full deep reasoning model. */
    boolean isComplexTask(String t) {
        if (t == null) return false;
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
            String raw = streamDeep(msgs, 9000, 0.25);
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
        if (!hasGoogle()) return "";
        return googleTranscribeWithFallback(wav);
    }

    String transcribe(byte[] wav) throws Exception {
        if (!hasGoogle()) {
            throw new IllegalStateException("add a free Google key in Settings for voice input");
        }
        return googleTranscribeWithFallback(wav);
    }

    private String googleTranscribeWithFallback(byte[] wav) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(wav);
        String prompt = "Transcribe this audio verbatim. Return only the spoken words.";
        String json = "{\"contents\":[{\"parts\":[{\"text\":" + js(prompt) + "},"
                    + "{\"inline_data\":{\"mime_type\":\"audio/wav\",\"data\":" + js(b64) + "}}]}],"
                    + "\"generationConfig\":{\"temperature\":0.0,\"maxOutputTokens\":512}}";
        String[] models = {GEMINI_STT, GEMINI_FAST};
        Exception last = null;
        for (int i = 0; i < models.length; i++) {
            String m = models[i];
            for (int retry = 0; retry < (i == models.length - 1 ? 2 : 1); retry++) {
                try {
                    String res = googlePostRaw(m, "generateContent", json, 60000);
                    return extractGeminiText(res).trim();
                } catch (Exception ex) {
                    last = ex;
                    log("stt " + m + " failed: " + shortStr(String.valueOf(ex.getMessage())));
                    if (i == models.length - 1 && retry == 0 && ex instanceof ApiException
                            && ((ApiException) ex).isRetryable()) {
                        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    break;
                }
            }
        }
        throw last != null ? last : new Exception("speech transcription failed");
    }

    /** Immediately stops whatever the assistant is saying. */
    void stopSpeaking() {
        speechCancelled = true;
        synchronized (activeTtsStreams) {
            for (TtsStream st : activeTtsStreams) {
                try { st.abort(); } catch (Throwable ignored) {}
            }
            activeTtsStreams.clear();
        }
        try { warmWinTts.stop(); } catch (Throwable ignored) {}
        Clip c = activeClip;
        if (c != null) { try { c.stop(); c.close(); } catch (Throwable ignored) {} }
        SourceDataLine sl = activeLine;
        if (sl != null) { try { sl.stop(); sl.flush(); sl.close(); } catch (Throwable ignored) {} }
        Process p = speechProc;
        if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
    }

    static boolean isOpenRouterTtsRateLimited(Throwable t) {
        if (t instanceof ApiException) {
            int s = ((ApiException) t).status;
            if (s == 429 || s == 502 || s == 503) return true;
        }
        String m = String.valueOf(t == null ? "" : t.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("429") || m.contains("502") || m.contains("503");
    }

    static boolean isGeminiTtsRateLimited(Throwable t) {
        if (t instanceof ApiException && ((ApiException) t).status == 429) return true;
        String m = String.valueOf(t == null ? "" : t.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("429") || m.contains("resource_exhausted");
    }

    void speak(String text) {
        speak(text, null);
    }

    void speak(String text, Runnable onAudioStart) {
        if (text == null || text.trim().isEmpty()) {
            if (onAudioStart != null) try { onAudioStart.run(); } catch (Throwable ignored) {}
            return;
        }
        speechCancelled = false;
        final boolean[] fired = {false};
        Runnable fireOnce = () -> {
            if (!fired[0]) {
                fired[0] = true;
                if (onAudioStart != null) {
                    try { onAudioStart.run(); } catch (Throwable ignored) {}
                }
            }
        };
        boolean played = false;
        long now = System.currentTimeMillis();
        if (hasOpenRouter() && !speechCancelled && now >= openrouterTtsBlockedUntil) {
            try {
                played = speakWith(text, false, fireOnce);
            } catch (Throwable t) {
                if (isOpenRouterTtsRateLimited(t)) {
                    openrouterTtsBlockedUntil = System.currentTimeMillis() + 5 * 60_000L;
                    log("openrouter tts rate-limited (" + shortStr(String.valueOf(t.getMessage())) + ") - cooling down 5m");
                } else {
                    log("openrouter tts failed: " + shortStr(String.valueOf(t.getMessage())));
                }
            }
        }
        now = System.currentTimeMillis();
        if (!played && hasGoogle() && !speechCancelled && now >= geminiTtsBlockedUntil) {
            try {
                played = speakWith(text, true, fireOnce);
            } catch (Throwable t) {
                if (isGeminiTtsRateLimited(t)) {
                    String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
                    long cd = (m.contains("daily") || m.contains("quota")) ? 60 * 60_000L : 30 * 60_000L;
                    geminiTtsBlockedUntil = System.currentTimeMillis() + cd;
                    log("google tts rate-limited (" + shortStr(String.valueOf(t.getMessage()))
                            + ") - cooling down " + (cd / 60_000L) + "m");
                } else {
                    log("google tts failed: " + shortStr(String.valueOf(t.getMessage())));
                }
            }
        }
        if (!played && !speechCancelled) {
            if (isWin()) {
                played = warmWinTts.speak(text, fireOnce);
            }
            if (!played && !speechCancelled) {
                fireOnce.run();
                speakNative(text);
            }
        }
        if (!speechCancelled) fireOnce.run();
    }

    /** Streams the full reply in a single HTTP request directly into SourceDataLine. */
    boolean speakWith(String text, boolean useGoogle, Runnable onAudioStart) throws Exception {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return true;
        TtsStream st = startTtsStream(clean, useGoogle);
        SourceDataLine[] lineHolder = new SourceDataLine[1];
        AudioFormat[] lineFmt = new AudioFormat[1];
        boolean started = false;
        try {
            if (speechCancelled) return false;
            awaitStreamReady(st, 25000);
            if (speechCancelled) return false;
            if (st.error != null && !st.hasAudio) {
                Throwable err = st.error;
                throw (err instanceof Exception ? (Exception) err : new Exception(err));
            }
            started = playTtsStream(st, lineHolder, lineFmt, onAudioStart);
        } finally {
            st.abort();
            activeTtsStreams.remove(st);
            SourceDataLine line = lineHolder[0];
            if (line != null) {
                if (!speechCancelled) { try { line.drain(); } catch (Throwable ignored) {} }
                try { line.stop(); } catch (Throwable ignored) {}
                try { line.flush(); } catch (Throwable ignored) {}
                try { line.close(); } catch (Throwable ignored) {}
                if (activeLine == line) activeLine = null;
            }
        }
        return started;
    }

    private TtsStream startTtsStream(String text, boolean useGoogle) {
        TtsStream st = new TtsStream();
        activeTtsStreams.add(st);
        Thread th = new Thread(() -> {
            try {
                if (useGoogle) produceGoogleTtsStream(text, st);
                else produceOpenRouterTtsStream(text, st);
            } catch (Throwable ex) {
                st.error = ex;
            } finally {
                st.queue.offer(TTS_EOF);
                st.signalReady();
            }
        }, "tts-stream");
        th.setDaemon(true);
        th.start();
        return st;
    }

    private void awaitStreamReady(TtsStream st, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (st.readyLock) {
            while (!st.ready && !st.closed && !speechCancelled) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) {
                    if (st.error == null) st.error = new Exception("tts stream timed out");
                    break;
                }
                try { st.readyLock.wait(Math.min(200, rem)); }
                catch (InterruptedException ie) { break; }
            }
        }
    }

    private boolean playTtsStream(TtsStream st, SourceDataLine[] lineHolder,
                                  AudioFormat[] lineFmt, Runnable onAudioStart) throws Exception {
        AudioFormat af = st.format;
        int frame = Math.max(1, af.getFrameSize());
        // ~150 ms startup buffer so playback begins fast without underrun stutter
        int targetBuf = Math.max(frame, ((int) (af.getSampleRate() * frame * 0.150)) / frame * frame);
        ByteArrayOutputStream prebuf = new ByteArrayOutputStream(targetBuf + 4096);
        boolean eofReached = false;
        while (!speechCancelled && !st.closed && prebuf.size() < targetBuf) {
            byte[] chunk = st.queue.poll(25, TimeUnit.SECONDS);
            if (chunk == null) break;
            if (chunk == TTS_EOF) { eofReached = true; break; }
            prebuf.write(chunk, 0, chunk.length);
        }
        if (speechCancelled || st.closed) return false;
        byte[] initial = prebuf.toByteArray();
        if (initial.length == 0) {
            if (st.error != null) {
                Throwable err = st.error;
                throw (err instanceof Exception ? (Exception) err : new Exception(err));
            }
            return false;
        }

        SourceDataLine line = lineHolder[0];
        if (line == null || lineFmt[0] == null || !af.matches(lineFmt[0])) {
            if (line != null) {
                if (!speechCancelled) { try { line.drain(); } catch (Throwable ignored) {} }
                try { line.stop(); line.flush(); line.close(); } catch (Throwable ignored) {}
            }
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, af));
            line.open(af, Math.max(16384, targetBuf * 2));
            line.start();
            lineHolder[0] = line;
            lineFmt[0] = af;
            activeLine = line;
        }

        byte[] carry = new byte[frame];
        int carryLen = 0;

        int aligned = (initial.length / frame) * frame;
        if (aligned > 0) {
            line.write(initial, 0, aligned);
            if (onAudioStart != null) onAudioStart.run();
        }
        int left = initial.length - aligned;
        if (left > 0) {
            System.arraycopy(initial, aligned, carry, 0, left);
            carryLen = left;
        }

        if (!eofReached) {
            while (!speechCancelled && !st.closed) {
                byte[] chunk = st.queue.poll(25, TimeUnit.SECONDS);
                if (chunk == null || chunk == TTS_EOF) break;
                if (chunk.length == 0) continue;
                if (carryLen > 0) {
                    byte[] merged = new byte[carryLen + chunk.length];
                    System.arraycopy(carry, 0, merged, 0, carryLen);
                    System.arraycopy(chunk, 0, merged, carryLen, chunk.length);
                    chunk = merged;
                    carryLen = 0;
                }
                int wlen = (chunk.length / frame) * frame;
                if (wlen > 0) {
                    line.write(chunk, 0, wlen);
                    if (onAudioStart != null) onAudioStart.run();
                }
                int rem = chunk.length - wlen;
                if (rem > 0) {
                    System.arraycopy(chunk, wlen, carry, 0, rem);
                    carryLen = rem;
                }
            }
        }
        return true;
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

    /** Opens the TLS connection, pre-warms local Windows TTS, and primes caches. */
    void warmUp() {
        warmWinTts.warmUpAsync();
        if (!hasKey()) return;
        Thread t = new Thread(() -> {
            try { fgCacheTime = 0L; getForegroundTitle(); openWindows(true); } catch (Throwable ignored) {}
            if (hasGoogle()) {
                try {
                    String u = GOOGLE_BASE + "/models?key=" + enc(googleKey);
                    HttpURLConnection c = (HttpURLConnection) URI.create(u).toURL().openConnection();
                    c.setConnectTimeout(5000); c.setReadTimeout(5000);
                    readStr(c.getInputStream());
                } catch (Throwable ignored) {}
            }
            if (hasOpenRouter()) {
                try {
                    HttpURLConnection c = orConn("/auth/key", 5000);
                    c.setConnectTimeout(5000);
                    readStr(c.getInputStream());
                } catch (Throwable ignored) {}
            }
        }, "proton-warmup");
        t.setDaemon(true);
        t.start();
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
        String content = completeDeep(msgsJson, 4500, 0.3);
        if (content == null || content.trim().isEmpty()) throw new Exception("the model returned nothing");
        return cleanComplex(content);
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
            String c = completeFast(msgs, 350, 0.3);
            if (c != null && !c.trim().isEmpty()) return cleanSpoken(c);
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
        boolean complex = isComplexTask(userText);
        String sys = (complex ? REASONING_PERSONA : PERSONA) + "\nCONTEXT\n- NOW: " + java.time.LocalDateTime.now().format(
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
        String effort = effortFor(userText);
        String out;
        if (complex) {
            out = cleanComplex(streamDeep(msgs.toString(), effort.equals("high") ? 4096 : 2600, 0.6));
        } else {
            String raw = completeFast(msgs.toString(), effort.equals("high") ? 2200 : 900,
                    effort.equals("high") ? 0.4 : 0.6);
            if (raw == null) return null;
            out = cleanSpoken(raw);
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
        if (isComplexTask(userText)) {
            content = streamDeep(msgs.toString(), 4096, 0.3);
        } else {
            content = completeFast(msgs.toString(), words >= 22 ? 1400 : 900, 0.3);
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
        String out = "code".equals(kind)
                ? cleanComplex(streamDeep(msgs, 4096, 0.3))
                : completeFast(msgs, 3000, 0.5).trim();
        if (out.startsWith("```")) {
            int nl = out.indexOf('\n');
            if (nl > 0) out = out.substring(nl + 1);
            int end = out.lastIndexOf("```");
            if (end > 0) out = out.substring(0, end);
            out = out.trim();
        }
        return out;
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

    /** Gemini Google Search grounding first when Google key is available, then DuckDuckGo + page reading. */
    String webAnswer(String question) {
        String q = cleanWebQuery(question == null ? "" : question);
        String today = new java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date());
        log("web search: " + q);
        if (hasGoogle()) {
            try {
                String sys = WEB_SYS + "Today is " + today + ".";
                String msgs = "{\"role\":\"system\",\"content\":" + js(sys)
                            + "},{\"role\":\"user\",\"content\":" + js(q) + "}";
                String body = toGeminiBody(msgs, 512, 0.3, false, "[{\"google_search\":{}}]");
                String res = googlePostRaw(GEMINI_FAST, "generateContent", body, 60000);
                String a = cleanSpoken(extractGeminiText(res));
                if (!a.isEmpty()) return a;
            } catch (Throwable t) {
                log("google web search failed, falling back to duckduckgo: "
                        + shortStr(String.valueOf(t.getMessage())));
            }
        }
        try {
            String a = cleanSpoken(scrapedAnswer(q, today));
            if (!a.isEmpty()) return a;
        } catch (Throwable t) { log("web scrape failed: " + shortStr(String.valueOf(t.getMessage()))); }
        return "Sorry, I couldn't reach the web right now.";
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
        String msgs = "{\"role\":\"system\",\"content\":" + js(sys)
                    + "},{\"role\":\"user\",\"content\":" + js(user) + "}";
        String content = completeFast(msgs, 512, 0.3);
        if (content == null || content.trim().isEmpty()) throw new Exception("empty answer");
        return content;
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

    /** Screenshot of the whole desktop as an RGB BufferedImage (scaled to maxW wide). */
    BufferedImage screenImage(int maxW) throws Exception {
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
        return rgb;
    }

    /** Multimodal vision call across Google AI Studio (inline_data) and OpenRouter (image_url). */
    String visionCall(Target vm, BufferedImage img, String prompt, int maxTokens, double temp) throws Exception {
        BufferedImage rgb = toRgb(img);
        ByteArrayOutputStream jb = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", jb);
        String b64 = Base64.getEncoder().encodeToString(jb.toByteArray());
        if (vm.google) {
            String json = "{\"contents\":[{\"parts\":["
                        + "{\"text\":" + js(prompt) + "},"
                        + "{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":" + js(b64) + "}}]}],"
                        + "\"generationConfig\":{\"temperature\":" + temp + ",\"maxOutputTokens\":" + maxTokens + "}}";
            String body = googlePostRaw(vm.model, "generateContent", json, 120000);
            String d = extractGeminiText(body).trim();
            if (d.isEmpty()) throw new Exception("google vision returned empty");
            return d;
        } else {
            String uri = "data:image/jpeg;base64," + b64;
            String json = "{\"model\":" + js(vm.model) + ",\"messages\":[{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"text\",\"text\":" + js(prompt) + "},"
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":" + js(uri) + "}}]}],"
                        + "\"temperature\":" + temp + ",\"max_tokens\":" + maxTokens + "}";
            String body = orPostJson("/chat/completions", json, 120000);
            Object content = dig(jparse(body), "choices", 0, "message", "content");
            String d = content == null ? "" : cleanComplex(content.toString());
            if (d.isEmpty()) throw new Exception("openrouter vision returned empty");
            return d;
        }
    }

    /** Step 1: the vision model describes the current screen for this question. */
    String describeScreen(String userText) throws Exception {
        BufferedImage img = screenImage(1600);
        String prompt =
            "You are the eyes of a voice assistant. This is a screenshot of the user's computer screen right now.\n" +
            "The user asked: \"" + userText + "\"\n" +
            "Describe what you see clearly and accurately: the main app or window, the key content, and any " +
            "readable text that matters for the question. Be factual and do not guess. Max 250 words.";
        List<Target> cands = visionCandidates();
        if (cands.isEmpty()) throw new Exception("no vision model available on this key");
        StringBuilder errs = new StringBuilder();
        for (Target vm : cands) {
            try {
                String d = visionCall(vm, img, prompt, 2048, 0.2);
                if (!d.isEmpty()) {
                    log("vision (" + vm + "): " + shortStr(d));
                    return d;
                }
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
                errs.append(vm).append(':').append(shortStr(msg)).append(';');
                log("vision " + vm + " failed: " + shortStr(msg));
            }
        }
        throw new Exception(errs.toString());
    }

    /** Step 2: the reply model answers using only what the vision model described. */
    String replyFromVision(String userText, String desc) throws Exception {
        boolean complex = isComplexTask(userText);
        String sys = complex
            ? REASONING_PERSONA + "\nWHAT YOU SAW:\n" + desc
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
        String out = complex
                ? cleanComplex(streamDeep(msgs.toString(), 2600, 0.6))
                : cleanSpoken(completeFast(msgs.toString(), 900, 0.6));
        if (out.isEmpty()) throw new Exception("empty reply");
        return out;
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

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = c.createGraphics(); g.drawImage(src, 0, 0, null); g.dispose();
        return c;
    }

    static double num(Object o) { return Double.parseDouble(o.toString()); }

    /** Asks one vision model for the element's centre, in the pixel space of img. Null = not found. */
    double[] askLocate(Target vm, BufferedImage img, String desc, String hint) throws Exception {
        BufferedImage rgb = toRgb(img);
        String prompt =
            "You are a pixel-accurate UI element locator.\n" +
            "Image size: " + rgb.getWidth() + " x " + rgb.getHeight() + " pixels (origin top-left, x to the right, y downward).\n" +
            "Find: \"" + desc + "\".\n" +
            (hint == null ? "" : hint + "\n") +
            "Locate the clickable element ITSELF. For a desktop, taskbar or Start-menu icon use the icon picture, " +
            "NOT the text caption underneath it. For a button, menu item or tab use the shape that holds its label.\n" +
            "Reply with ONLY JSON: {\"found\":true,\"box\":[x1,y1,x2,y2]} where the box tightly encloses that element " +
            "in image pixels, or {\"found\":false}.";
        String content = visionCall(vm, rgb, prompt, 2048, 0.1);
        String j = extractJson(content);
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
    int[] refineLocate(Target vm, BufferedImage full, int px, int py, String desc) throws Exception {
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
        List<Target> cands = visionCandidates();
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
                        for (Target vm : cands) {
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
        for (Target vm : cands) {
            try {
                double[] c = askLocate(vm, send, desc, appHint);
                if (c == null) { errs.append(vm).append(":not found;"); continue; }
                int px = (int) Math.round(c[0] * sx);
                int py = (int) Math.round(c[1] * sy);
                try {
                    int[] ref = refineLocate(vm, full, px, py, desc);
                    if (ref != null) { px = ref[0]; py = ref[1]; }
                } catch (Throwable rt) { log("refine skipped: " + shortStr(String.valueOf(rt.getMessage()))); }
                return new int[]{px + vb.x, py + vb.y};
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage());
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
        String content = completeFast("{\"role\":\"user\",\"content\":" + js(user) + "}", 200, 0.0);
        if (content == null) return null;
        String j = extractJson(content);
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

    String visionChat(Target vm, BufferedImage img, String prompt, int maxTokens) throws Exception {
        return visionCall(vm, ProtonMemory.fit(img, 1400), prompt, maxTokens, 0.2);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> decideNext(String goal, List<String> hist, UiaScanner.Scan sc, BufferedImage full) throws Exception {
        List<Target> cands = visionCandidates();
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
        for (Target vm : cands) {
            try {
                String raw = visionChat(vm, full, p.toString(), 700);
                String j = extractJson(raw);
                if (j == null) throw new Exception("no json");
                Object o = jparse(j);
                if (!(o instanceof Map)) throw new Exception("bad json");
                return (Map<String, Object>) o;
            } catch (Exception ex) {
                last = ex;
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
        final List<Target> cands = new ArrayList<>(visionCandidates());
        Thread t = new Thread(() -> {
            try {
                BufferedImage fit = ProtonMemory.fit(img, 1200);
                String prompt = "This is a screenshot of the application window \"" + display + "\". "
                    + "In under 70 words describe its layout for an assistant that drives it with the mouse: "
                    + "menu bar items, toolbar buttons and WHERE they are (left / right / top / bottom), and the main panels. "
                    + "Plain text only, no markdown.";
                for (Target vm : cands) {
                    try {
                        String content = visionCall(vm, fit, prompt, 1024, 0.2);
                        String text = cleanSpoken(content);
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

    byte[] synthesizeOpenRouter(String text) throws Exception {
        String json = "{\"model\":" + js(OR_TTS_MODEL) + ",\"input\":" + js(text)
                    + ",\"response_format\":\"wav\"}";
        byte[] audio = orPostBytes("/audio/speech", json, 60000);
        if (audio == null || audio.length < 44 || audio[0] == '{' || audio[0] == '[') {
            String preview = audio == null ? "null" : shortStr(new String(audio, StandardCharsets.UTF_8));
            throw new Exception("openrouter tts invalid audio: " + preview);
        }
        return audio;
    }

    private static byte[] float32ToPcm16(byte[] src, int len) {
        int usable = len - (len % 4);
        int samples = usable / 4;
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int bi = i * 4;
            float f = Float.intBitsToFloat((src[bi] & 0xFF) | ((src[bi + 1] & 0xFF) << 8)
                    | ((src[bi + 2] & 0xFF) << 16) | ((src[bi + 3] & 0xFF) << 24));
            int v = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
            out[i * 2] = (byte) v;
            out[i * 2 + 1] = (byte) (v >> 8);
        }
        return out;
    }

    void produceOpenRouterTtsStream(String text, TtsStream st) throws Exception {
        String voice = openrouterVoice();
        String withInst = "{\"model\":" + js(OR_TTS_MODEL)
                        + ",\"voice\":" + js(voice)
                        + ",\"instructions\":" + js("Clear, calm, natural desktop assistant.")
                        + ",\"input\":" + js(text)
                        + ",\"response_format\":\"pcm\"}";
        String plain = "{\"model\":" + js(OR_TTS_MODEL)
                     + ",\"voice\":" + js(voice)
                     + ",\"input\":" + js(text)
                     + ",\"response_format\":\"pcm\"}";
        HttpURLConnection c = null;
        int code = 0;
        String payload = withInst;
        for (int attempt = 0; attempt < 2; attempt++) {
            c = orConn("/audio/speech", 60000);
            st.conn = c;
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            code = c.getResponseCode();
            if (code == 400 && attempt == 0) {
                try { readStr(c.getErrorStream()); } catch (Throwable ignored) {}
                payload = plain;
                continue;
            }
            break;
        }
        if (code >= 400) {
            throw new ApiException("openrouter", code, readStr(c.getErrorStream()));
        }

        String contentType = c.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
            throw new Exception("openrouter tts returned json: " + shortStr(readStr(c.getInputStream())));
        }

        float sampleRate = 24000.0f;
        int channels = 1;
        if (contentType != null) {
            java.util.regex.Matcher rm = java.util.regex.Pattern
                    .compile("(?i)(?:rate|sample_rate)\\s*=\\s*(\\d+(?:\\.\\d+)?)").matcher(contentType);
            if (rm.find()) {
                try {
                    float parsed = Float.parseFloat(rm.group(1));
                    if (parsed > 0f) sampleRate = parsed;
                } catch (NumberFormatException ignored) {}
            }
            java.util.regex.Matcher cm = java.util.regex.Pattern
                    .compile("(?i)channels\\s*=\\s*(\\d+)").matcher(contentType);
            if (cm.find()) {
                try {
                    int parsed = Integer.parseInt(cm.group(1));
                    if (parsed >= 1 && parsed <= 2) channels = parsed;
                } catch (NumberFormatException ignored) {}
            }
        }
        st.format = new AudioFormat(sampleRate, 16, channels, true, false);

        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while (!st.closed && !speechCancelled && (n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                st.hasAudio = true;
                st.queue.offer(Arrays.copyOf(buf, n));
                if (!st.ready) st.signalReady();
            }
        }
    }

    @SuppressWarnings("unchecked")
    void produceGoogleTtsStream(String text, TtsStream st) throws Exception {
        String withVoice = "{\"contents\":[{\"parts\":[{\"text\":" + js(text) + "}]}],"
                         + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],"
                         + "\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":"
                         + js(DEFAULT_GEMINI_VOICE) + "}}}}}";
        String plain = "{\"contents\":[{\"parts\":[{\"text\":" + js(text) + "}]}],"
                     + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"]}}";
        String url = GOOGLE_BASE + "/models/" + enc(GEMINI_TTS)
                   + ":streamGenerateContent?alt=sse&key=" + enc(googleKey);
        String body = withVoice;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            st.conn = c;
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 400 && attempt == 0) {
                try { readStr(c.getErrorStream()); } catch (Throwable ignored) {}
                body = plain;
                continue;
            }
            if (code == 404) {
                try { readStr(c.getErrorStream()); } catch (Throwable ignored) {}
                break; // fall through to non-streaming generateContent
            }
            if (code >= 400) {
                throw new ApiException("google", code, readStr(c.getErrorStream()));
            }
            boolean parsedFirstFormat = false;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!st.closed && !speechCancelled && (line = br.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith(":")) continue;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    try {
                        Object parts = dig(jparse(data), "candidates", 0, "content", "parts");
                        if (!(parts instanceof List)) continue;
                        for (Object po : (List<?>) parts) {
                            if (!(po instanceof Map)) continue;
                            Map<String, Object> pm = (Map<String, Object>) po;
                            Object inl = pm.get("inlineData");
                            if (inl == null) inl = pm.get("inline_data");
                            if (!(inl instanceof Map)) continue;
                            Map<String, Object> im = (Map<String, Object>) inl;
                            Object dataObj = im.get("data");
                            if (dataObj == null) continue;
                            String b64 = dataObj.toString().replaceAll("\\s+", "");
                            if (b64.isEmpty()) continue;
                            byte[] raw = Base64.getDecoder().decode(b64);
                            if (raw.length == 0) continue;
                            if (!parsedFirstFormat) {
                                parsedFirstFormat = true;
                                if (raw.length >= 44 && raw[0] == 'R' && raw[1] == 'I'
                                        && raw[2] == 'F' && raw[3] == 'F') {
                                    byte[] stripped = stripWavToPcm(raw, st);
                                    raw = stripped;
                                } else {
                                    int rate = 24000;
                                    Object mt = im.get("mimeType");
                                    if (mt == null) mt = im.get("mime_type");
                                    if (mt != null) {
                                        java.util.regex.Matcher rm = java.util.regex.Pattern
                                                .compile("(?i)rate=(\\d+)").matcher(mt.toString());
                                        if (rm.find()) {
                                            try { rate = Integer.parseInt(rm.group(1)); } catch (NumberFormatException ignored) {}
                                        }
                                    }
                                    st.format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                                            (float) rate, 16, 1, 2, (float) rate, false);
                                }
                            }
                            if (raw.length > 0) {
                                st.hasAudio = true;
                                st.queue.offer(raw);
                                if (!st.ready) st.signalReady();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (st.hasAudio) return;
            break;
        }
        // Fallback to non-streaming generateContent if SSE endpoint returned 404 or empty audio
        if (!st.closed && !speechCancelled) {
            byte[] wav = synthesizeGoogle(text);
            byte[] pcm = stripWavToPcm(wav, st);
            if (pcm.length > 0) {
                st.hasAudio = true;
                st.queue.offer(pcm);
                if (!st.ready) st.signalReady();
            }
        }
    }

    private static byte[] stripWavToPcm(byte[] w, TtsStream st) {
        if (w.length < 44 || w[0] != 'R' || w[1] != 'I' || w[2] != 'F' || w[3] != 'F') return w;
        int fmtTag = 1, ch = 1, bits = 16; long rate = 24000; int dataStart = -1; long dataLen = 0;
        int off = 12;
        while (off + 8 <= w.length) {
            String id = new String(w, off, 4, StandardCharsets.US_ASCII);
            long size = le32(w, off + 4);
            if ("fmt ".equals(id) && off + 8 + 16 <= w.length) {
                fmtTag = le16(w, off + 8);
                ch     = le16(w, off + 10);
                rate   = le32(w, off + 12);
                bits   = le16(w, off + 22);
                if (fmtTag == 0xFFFE && size >= 26 && off + 8 + 26 <= w.length) fmtTag = le16(w, off + 32);
            } else if ("data".equals(id)) {
                dataStart = off + 8;
                long avail = w.length - dataStart;
                dataLen = (size == 0 || size > avail) ? avail : size;
                break;
            }
            off += 8 + (int) Math.min(size, Integer.MAX_VALUE - 16) + (int) (size & 1);
        }
        if (dataStart < 0 || dataLen <= 0 || ch < 1 || ch > 2) return new byte[0];
        if (fmtTag == 3 && bits == 32) {
            byte[] slice = Arrays.copyOfRange(w, dataStart, dataStart + (int) dataLen);
            st.format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, (float) rate, 16, ch, ch * 2, (float) rate, false);
            return float32ToPcm16(slice, slice.length);
        }
        int frame = ch * (bits / 8);
        st.format = new AudioFormat(bits == 8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED,
                (float) rate, bits, ch, frame, (float) rate, false);
        return Arrays.copyOfRange(w, dataStart, dataStart + (int) dataLen);
    }

    @SuppressWarnings("unchecked")
    byte[] synthesizeGoogle(String text) throws Exception {
        String withVoice = "{\"contents\":[{\"parts\":[{\"text\":" + js(text) + "}]}],"
                         + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],"
                         + "\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":"
                         + js(DEFAULT_GEMINI_VOICE) + "}}}}}";
        String plain = "{\"contents\":[{\"parts\":[{\"text\":" + js(text) + "}]}],"
                     + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"]}}";
        String res;
        try {
            res = googlePostRaw(GEMINI_TTS, "generateContent", withVoice, 60000);
        } catch (ApiException ex) {
            if (ex.status == 400) {
                res = googlePostRaw(GEMINI_TTS, "generateContent", plain, 60000);
            } else {
                throw ex;
            }
        }
        Object parts = dig(jparse(res), "candidates", 0, "content", "parts");
        if (!(parts instanceof List)) throw new Exception("google tts returned no audio parts");
        for (Object po : (List<?>) parts) {
            if (!(po instanceof Map)) continue;
            Map<String, Object> pm = (Map<String, Object>) po;
            Object inl = pm.get("inlineData");
            if (inl == null) inl = pm.get("inline_data");
            if (!(inl instanceof Map)) continue;
            Map<String, Object> im = (Map<String, Object>) inl;
            Object dataObj = im.get("data");
            if (dataObj == null) continue;
            String b64 = dataObj.toString().replaceAll("\\s+", "");
            if (b64.isEmpty()) continue;
            byte[] raw = Base64.getDecoder().decode(b64);
            if (raw.length >= 12 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F') {
                return raw;
            }
            int rate = 24000;
            Object mt = im.get("mimeType");
            if (mt == null) mt = im.get("mime_type");
            if (mt != null) {
                java.util.regex.Matcher rm = java.util.regex.Pattern
                        .compile("(?i)rate=(\\d+)").matcher(mt.toString());
                if (rm.find()) {
                    try { rate = Integer.parseInt(rm.group(1)); } catch (NumberFormatException ignored) {}
                }
            }
            return toWav(raw, rate);
        }
        throw new Exception("google tts returned no inline audio");
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