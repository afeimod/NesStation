package javax.microedition.lcdui.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * CPU-based bitmap filter for J2ME games (non-GL rendering modes).
 *
 * <p>Uses the <b>exact same algorithms</b> as the reference GLSL shaders:
 * <ul>
 *   <li>2xBR / 4xBR: Hyllian's 5xBR v3.5a with weighted RGB luminance
 *       edge detection, 21-pixel sampling, interpolation restriction, and
 *       line-inequality edge positioning (matches the GLSL shader in
 *       {@link J2meFilterShaders}).</li>
 *   <li>HQ4x: guest(r)'s 4xGLSLHqFilter weighted interpolation.</li>
 *   <li>Dot: Themaister's LCD dot effect with {@code exp(-gamma * delta * bloom)}.</li>
 *   <li>Scanline: sine-modulated brightness.</li>
 *   <li>CRT: RGB phosphor mask + scanlines + vignette.</li>
 * </ul>
 *
 * <p>Filter modes (must match {@code J2meFilterShaders}):
 * <pre>
 *   0 = None
 *   1 = Scanline
 *   2 = CRT
 *   3 = Dot
 *   4 = 2xBR
 *   5 = 4xBR
 *   6 = 2xBR + Dot
 *   7 = 4xBR + Dot
 *   8 = HQ4x
 *   9 = HQ4x + Dot
 *  10 = TV      (仿电视机：CPU 端 drawTvFrame 桶形弧面 + applyTvMask)
 *  11 = 2xBR + Scanline
 *  12 = 4xBR + Scanline
 *  13 = HQ4x + Scanline
 *  14 = 2xBR + TV
 *  15 = 4xBR + TV
 *  16 = HQ4x + TV
 * </pre>
 * 追加式编号（新模式只加在尾部）保证旧存档里的滤镜序号不漂移。
 */
public final class J2meBitmapFilter {

    public static final int MODE_NONE      = 0;
    public static final int MODE_SCANLINE  = 1;
    public static final int MODE_CRT       = 2;
    public static final int MODE_DOT       = 3;
    public static final int MODE_2XBR      = 4;
    public static final int MODE_4XBR      = 5;
    public static final int MODE_2XBR_DOT  = 6;
    public static final int MODE_4XBR_DOT  = 7;
    public static final int MODE_HQ4X      = 8;
    public static final int MODE_HQ4X_DOT  = 9;
    // —— 仿电视机 + 组合滤镜（追加尾部，不改旧编号）——
    public static final int MODE_TV            = 10;
    public static final int MODE_2XBR_SCANLINE = 11;
    public static final int MODE_4XBR_SCANLINE = 12;
    public static final int MODE_HQ4X_SCANLINE = 13;
    public static final int MODE_2XBR_TV       = 14;
    public static final int MODE_4XBR_TV       = 15;
    public static final int MODE_HQ4X_TV       = 16;

    // Legacy aliases for backward compatibility
    public static final int MODE_XBR     = MODE_2XBR;
    public static final int MODE_XBR_DOT = MODE_2XBR_DOT;

    private static final Paint sNearestPaint;
    private static final Paint sSmoothPaint;
    private static final Paint sMaskPaint = new Paint();

    static {
        sNearestPaint = new Paint();
        sNearestPaint.setFilterBitmap(false);
        sSmoothPaint = new Paint();
        sSmoothPaint.setFilterBitmap(true);
    }

    // ─── Cached filtered bitmap ──────────────────────────────────────────
    private static Bitmap sFilteredBitmap;
    private static int sCachedSrcW = -1;
    private static int sCachedSrcH = -1;
    private static int sCachedMode = -1;

    private J2meBitmapFilter() {}

    // ─── Mode classification ─────────────────────────────────────────────

    public static boolean isFilteredMode(int mode) {
        return mode != MODE_NONE;
    }

    public static boolean isPixelProcessingMode(int mode) {
        return mode == MODE_2XBR || mode == MODE_4XBR ||
               mode == MODE_2XBR_DOT || mode == MODE_4XBR_DOT ||
               mode == MODE_HQ4X || mode == MODE_HQ4X_DOT ||
               mode == MODE_2XBR_SCANLINE || mode == MODE_4XBR_SCANLINE ||
               mode == MODE_HQ4X_SCANLINE || mode == MODE_2XBR_TV ||
               mode == MODE_4XBR_TV || mode == MODE_HQ4X_TV;
    }

    /** 放大类组合滤镜中带 TV 外观的（需要弧面网格绘制 + TV 遮罩叠加）。 */
    public static boolean isTvComboMode(int mode) {
        return mode == MODE_2XBR_TV || mode == MODE_4XBR_TV || mode == MODE_HQ4X_TV;
    }

    public static int getScaleFactor(int mode) {
        if (mode == MODE_2XBR || mode == MODE_2XBR_DOT ||
            mode == MODE_2XBR_SCANLINE || mode == MODE_2XBR_TV) return 2;
        if (mode == MODE_4XBR || mode == MODE_4XBR_DOT ||
            mode == MODE_HQ4X || mode == MODE_HQ4X_DOT ||
            mode == MODE_4XBR_SCANLINE || mode == MODE_HQ4X_SCANLINE ||
            mode == MODE_4XBR_TV || mode == MODE_HQ4X_TV) return 4;
        return 1;
    }

    public static boolean isMaskMode(int mode) {
        return mode == MODE_SCANLINE || mode == MODE_CRT || mode == MODE_DOT ||
               mode == MODE_TV;
    }

    // ─── Public API: applyFilter (returns upscaled filtered bitmap) ──────

    public static Bitmap applyFilter(Bitmap src, int mode) {
        return applyFilter(src, src.getWidth(), src.getHeight(), mode);
    }

    /**
     * Apply the specified filter to the {@code srcW × srcH} active area of
     * the source bitmap. If the active area is smaller than the bitmap's
     * physical size, a cropped view is used to avoid processing stale pixels
     * beyond the active area (which would cause ghost images).
     */
    public static Bitmap applyFilter(Bitmap src, int srcW, int srcH, int mode) {
        // If the active area is smaller than the full bitmap, extract a
        // sub-bitmap view to avoid processing stale edge pixels.
        Bitmap work = src;
        if (srcW < src.getWidth() || srcH < src.getHeight()) {
            work = Bitmap.createBitmap(src, 0, 0, srcW, srcH);
        }

        int sw = work.getWidth();
        int sh = work.getHeight();

        if (sFilteredBitmap != null && (sCachedSrcW != sw || sCachedSrcH != sh || sCachedMode != mode)) {
            sFilteredBitmap.recycle();
            sFilteredBitmap = null;
        }
        sCachedSrcW = sw;
        sCachedSrcH = sh;
        sCachedMode = mode;

        switch (mode) {
            case MODE_2XBR:
                return xbrUpscale(work, 2);
            case MODE_4XBR:
                return xbrUpscale(work, 4);
            case MODE_2XBR_DOT:
                return applyDotMask(xbrUpscale(work, 2));
            case MODE_4XBR_DOT:
                return applyDotMask(xbrUpscale(work, 4));
            case MODE_HQ4X:
                return hq4xUpscale(work);
            case MODE_HQ4X_DOT:
                return applyDotMask(hq4xUpscale(work));
            case MODE_2XBR_SCANLINE:
                return applyScanlineMask(xbrUpscale(work, 2), 2);
            case MODE_4XBR_SCANLINE:
                return applyScanlineMask(xbrUpscale(work, 4), 4);
            case MODE_HQ4X_SCANLINE:
                return applyScanlineMask(hq4xUpscale(work), 4);
            case MODE_2XBR_TV:
                return xbrUpscale(work, 2);      // TV 外观在 drawFiltered 里画
            case MODE_4XBR_TV:
                return xbrUpscale(work, 4);
            case MODE_HQ4X_TV:
                return hq4xUpscale(work);
            default:
                return work;
        }
    }

    // ─── Public API: drawFiltered (non-GL canvas rendering) ──────────────

    public static void drawFiltered(Bitmap srcBitmap, Canvas dstCanvas,
                                    RectF dstRect, int mode) {
        drawFiltered(srcBitmap, srcBitmap.getWidth(), srcBitmap.getHeight(),
                dstCanvas, dstRect, mode);
    }

    /**
     * Draw the source bitmap with the specified filter, using only the
     * {@code srcW × srcH} active area (which may be smaller than the
     * bitmap's physical size when the Image was resized via setSize).
     */
    public static void drawFiltered(Bitmap srcBitmap, int srcW, int srcH,
                                    Canvas dstCanvas, RectF dstRect, int mode) {
        if (mode == MODE_NONE) {
            dstCanvas.drawBitmap(srcBitmap,
                    new Rect(0, 0, srcW, srcH),
                    dstRect, sNearestPaint);
            return;
        }

        if (isPixelProcessingMode(mode)) {
            Bitmap filtered = applyFilter(srcBitmap, srcW, srcH, mode);
            if (filtered != null && filtered != srcBitmap) {
                if (isTvComboMode(mode)) {
                    // 放大 + 仿电视机：弧面网格绘制 + 圆角裁剪 + TV 遮罩叠加
                    drawTvFrame(filtered, dstCanvas, dstRect);
                    applyTvMask(dstCanvas, dstRect);
                } else {
                    dstCanvas.drawBitmap(filtered,
                            new Rect(0, 0, filtered.getWidth(), filtered.getHeight()),
                            dstRect, sNearestPaint);
                }
            } else {
                dstCanvas.drawBitmap(srcBitmap,
                        new Rect(0, 0, srcW, srcH),
                        dstRect, sNearestPaint);
            }
        } else if (isMaskMode(mode)) {
            dstCanvas.drawBitmap(srcBitmap,
                    new Rect(0, 0, srcW, srcH),
                    dstRect, sNearestPaint);
            if (mode == MODE_TV) {
                // 仿电视机：先画弧面（带圆角裁剪），再叠扫描线/暗角/高光
                drawTvFrame(srcBitmap, srcW, srcH, dstCanvas, dstRect);
                applyTvMask(dstCanvas, dstRect);
            } else {
                applyCanvasMask(dstCanvas, dstRect, mode);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  XBR Algorithm (Hyllian's 5xBR v3.5a — reference implementation)
    //  Matches the GLSL FRAGMENT_2XBR / FRAGMENT_4XBR shaders exactly.
    //  Uses weighted luminance, 21-pixel sampling, interpolation restriction,
    //  and line-inequality edge positioning. Scale-independent.
    // ════════════════════════════════════════════════════════════════════

    /**
     * {@code reduce()} from the reference shader — packs RGB into a single
     * scalar for exact equality comparison.
     * <p>Uses {@code R*65536 + G*256 + B} (standard 24-bit packing) which is
     * collision-free for 8-bit channels. The GLSL reference uses
     * {@code vec3(65536.0, 255.0, 1.0)} with normalized [0,1] colors where
     * 255 > 1 holds; for integer 0-255 values we use 256 to ensure uniqueness
     * (255 is not > 255, but 256 is).
     */
    private static long reduceColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (long) r * 65536 + (long) g * 256 + b;
    }

    /**
     * 2xBR / 4xBR upscaling — Hyllian's 5xBR v3.5a reference algorithm.
     *
     * <p>Matches the GLSL FRAGMENT_2XBR / FRAGMENT_4XBR shaders exactly.
     * Uses weighted RGB luminance edge detection with 21-pixel sampling,
     * interpolation restriction (lv1, lv2_left, lv2_up), and line-inequality
     * edge positioning. Scale-independent — the same algorithm runs for both
     * 2xBR and 4xBR; the only difference is the fractional position {@code fp}
     * which changes the line-inequality results per sub-pixel.
     *
     * <p>For each output pixel:
     * <ol>
     *   <li>Compute source texel (tx,ty) and fractional position fp within it</li>
     *   <li>Sample 21-pixel neighborhood (3×3 core + 12 extended)</li>
     *   <li>Compute weighted luminance vectors b,c,d,e,f,g,h,i,i4,i5,h5,f4</li>
     *   <li>Evaluate line inequations fx, fx_left, fx_up</li>
     *   <li>Evaluate interpolation restrictions and edge detection rules</li>
     *   <li>Select output: nc[0]?(px[0]?F:H) : nc[1]?(px[1]?B:F)
     *                       : nc[2]?(px[2]?D:B) : nc[3]?(px[3]?H:D) : E</li>
     * </ol>
     */
    private static Bitmap xbrUpscale(Bitmap src, int scale) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw = sw * scale;
        int dh = sh * scale;

        int[] sp = new int[sw * sh];
        src.getPixels(sp, 0, sw, 0, 0, sw, sh);
        int[] dp = new int[dw * dh];

        // Hyllian's 5xBR v3.5a constants
        final float COEF = 2.0f;
        // rgbw = (16.163, 23.351, 8.4772) — weighted luminance for edge detection
        final float RGBW_R = 16.163f;
        final float RGBW_G = 23.351f;
        final float RGBW_B = 8.4772f;

        // Line inequation constants (Ao,Bo,Co / Ax,Bx,Cx / Ay,By,Cy)
        final float AO0 =  1.0f, AO1 = -1.0f, AO2 = -1.0f, AO3 =  1.0f;
        final float BO0 =  1.0f, BO1 =  1.0f, BO2 = -1.0f, BO3 = -1.0f;
        final float CO0 =  1.5f, CO1 =  0.5f, CO2 = -0.5f, CO3 =  0.5f;
        final float AX0 =  1.0f, AX1 = -1.0f, AX2 = -1.0f, AX3 =  1.0f;
        final float BX0 =  0.5f, BX1 =  2.0f, BX2 = -0.5f, BX3 = -2.0f;
        final float CX0 =  1.0f, CX1 =  1.0f, CX2 = -0.5f, CX3 =  0.0f;
        final float AY0 =  1.0f, AY1 = -1.0f, AY2 = -1.0f, AY3 =  1.0f;
        final float BY0 =  2.0f, BY1 =  0.5f, BY2 = -2.0f, BY3 = -0.5f;
        final float CY0 =  2.0f, CY1 =  0.0f, CY2 = -1.0f, CY3 =  0.5f;

        for (int oy = 0; oy < dh; oy++) {
            for (int ox = 0; ox < dw; ox++) {
                // Source texel coordinates
                int tx = ox / scale;
                int ty = oy / scale;

                // Fractional position within source texel (pixel-center sampled
                // to match GPU rasterisation: 0.25/0.75 for 2x, 0.125/0.375/0.625/0.875 for 4x)
                float fpx = ((float) (ox - tx * scale) + 0.5f) / scale;
                float fpy = ((float) (oy - ty * scale) + 0.5f) / scale;

                // ── Sample 21-pixel neighborhood ─────────────────────────────
                // Core 3×3
                int pA = getPixelSafe(sp, sw, sh, tx - 1, ty - 1);
                int pB = getPixelSafe(sp, sw, sh, tx,     ty - 1);
                int pC = getPixelSafe(sp, sw, sh, tx + 1, ty - 1);
                int pD = getPixelSafe(sp, sw, sh, tx - 1, ty);
                int pE = getPixelSafe(sp, sw, sh, tx,     ty);
                int pF = getPixelSafe(sp, sw, sh, tx + 1, ty);
                int pG = getPixelSafe(sp, sw, sh, tx - 1, ty + 1);
                int pH = getPixelSafe(sp, sw, sh, tx,     ty + 1);
                int pI = getPixelSafe(sp, sw, sh, tx + 1, ty + 1);
                // Extended neighborhood (12 pixels beyond 3×3)
                int pA1 = getPixelSafe(sp, sw, sh, tx - 1, ty - 2);
                int pC1 = getPixelSafe(sp, sw, sh, tx + 1, ty - 2);
                int pA0 = getPixelSafe(sp, sw, sh, tx - 2, ty - 1);
                int pG0 = getPixelSafe(sp, sw, sh, tx - 2, ty + 1);
                int pC4 = getPixelSafe(sp, sw, sh, tx + 2, ty - 1);
                int pI4 = getPixelSafe(sp, sw, sh, tx + 2, ty + 1);
                int pG5 = getPixelSafe(sp, sw, sh, tx - 1, ty + 2);
                int pI5 = getPixelSafe(sp, sw, sh, tx + 1, ty + 2);
                int pB1 = getPixelSafe(sp, sw, sh, tx,     ty - 2);
                int pD0 = getPixelSafe(sp, sw, sh, tx - 2, ty);
                int pH5 = getPixelSafe(sp, sw, sh, tx,     ty + 2);
                int pF4 = getPixelSafe(sp, sw, sh, tx + 2, ty);

                // ── Weighted luminance ───────────────────────────────────────
                float lumB  = lum(pB,  RGBW_R, RGBW_G, RGBW_B);
                float lumD  = lum(pD,  RGBW_R, RGBW_G, RGBW_B);
                float lumF  = lum(pF,  RGBW_R, RGBW_G, RGBW_B);
                float lumH  = lum(pH,  RGBW_R, RGBW_G, RGBW_B);
                float lumA  = lum(pA,  RGBW_R, RGBW_G, RGBW_B);
                float lumC  = lum(pC,  RGBW_R, RGBW_G, RGBW_B);
                float lumG  = lum(pG,  RGBW_R, RGBW_G, RGBW_B);
                float lumI  = lum(pI,  RGBW_R, RGBW_G, RGBW_B);
                float lumE  = lum(pE,  RGBW_R, RGBW_G, RGBW_B);
                float lumI4 = lum(pI4, RGBW_R, RGBW_G, RGBW_B);
                float lumC1 = lum(pC1, RGBW_R, RGBW_G, RGBW_B);
                float lumA0 = lum(pA0, RGBW_R, RGBW_G, RGBW_B);
                float lumG5 = lum(pG5, RGBW_R, RGBW_G, RGBW_B);
                float lumI5 = lum(pI5, RGBW_R, RGBW_G, RGBW_B);
                float lumC4 = lum(pC4, RGBW_R, RGBW_G, RGBW_B);
                float lumA1 = lum(pA1, RGBW_R, RGBW_G, RGBW_B);
                float lumG0 = lum(pG0, RGBW_R, RGBW_G, RGBW_B);
                float lumH5 = lum(pH5, RGBW_R, RGBW_G, RGBW_B);
                float lumF4 = lum(pF4, RGBW_R, RGBW_G, RGBW_B);
                float lumB1 = lum(pB1, RGBW_R, RGBW_G, RGBW_B);
                float lumD0 = lum(pD0, RGBW_R, RGBW_G, RGBW_B);

                // ── Build vec4 luminance vectors (float[4] stands in for GLSL vec4) ──
                // b  = (lum(B), lum(D), lum(H), lum(F))
                float b0 = lumB, b1 = lumD, b2 = lumH, b3 = lumF;
                // c  = (lum(C), lum(A), lum(G), lum(I))
                float c0 = lumC, c1 = lumA, c2 = lumG, c3 = lumI;
                // d  = (b.y, b.z, b.w, b.x) = (b1, b2, b3, b0)
                // e  = (lum(E), lum(E), lum(E), lum(E))
                // f  = (b.w, b.x, b.y, b.z) = (b3, b0, b1, b2)
                // g  = (c.z, c.w, c.x, c.y) = (c2, c3, c0, c1)
                // h  = (b.z, b.w, b.x, b.y) = (b2, b3, b0, b1)
                // i  = (c.w, c.x, c.y, c.z) = (c3, c0, c1, c2)
                // i4 = (lum(I4), lum(C1), lum(A0), lum(G5))
                // i5 = (lum(I5), lum(C4), lum(A1), lum(G0))
                // h5 = (lum(H5), lum(F4), lum(B1), lum(D0))
                // f4 = (h5.y, h5.z, h5.w, h5.x)

                // ── Line inequations (boolean[4] stands in for GLSL bvec4) ───
                boolean fx0      = AO0*fpy + BO0*fpx > CO0;
                boolean fx1      = AO1*fpy + BO1*fpx > CO1;
                boolean fx2      = AO2*fpy + BO2*fpx > CO2;
                boolean fx3      = AO3*fpy + BO3*fpx > CO3;
                boolean fxL0     = AX0*fpy + BX0*fpx > CX0;
                boolean fxL1     = AX1*fpy + BX1*fpx > CX1;
                boolean fxL2     = AX2*fpy + BX2*fpx > CX2;
                boolean fxL3     = AX3*fpy + BX3*fpx > CX3;
                boolean fxU0     = AY0*fpy + BY0*fpx > CY0;
                boolean fxU1     = AY1*fpy + BY1*fpx > CY1;
                boolean fxU2     = AY2*fpy + BY2*fpx > CY2;
                boolean fxU3     = AY3*fpy + BY3*fpx > CY3;

                // ── Interpolation restrictions ──────────────────────────────
                // interp_restriction_lv1: (e!=f) && (e!=h)
                // e[k]=lumE, f[k]=b3/b0/b1/b2, h[k]=b2/b3/b0/b1
                boolean ilv1_0 = (lumE != b3) && (lumE != b2);
                boolean ilv1_1 = (lumE != b0) && (lumE != b3);
                boolean ilv1_2 = (lumE != b1) && (lumE != b0);
                boolean ilv1_3 = (lumE != b2) && (lumE != b1);

                // interp_restriction_lv2_left: (e!=g) && (d!=g)
                // d[k]=b1/b2/b3/b0, g[k]=c2/c3/c0/c1
                boolean ilv2L_0 = (lumE != c2) && (b1 != c2);
                boolean ilv2L_1 = (lumE != c3) && (b2 != c3);
                boolean ilv2L_2 = (lumE != c0) && (b3 != c0);
                boolean ilv2L_3 = (lumE != c1) && (b0 != c1);

                // interp_restriction_lv2_up: (e!=c) && (b!=c)
                boolean ilv2U_0 = (lumE != c0) && (b0 != c0);
                boolean ilv2U_1 = (lumE != c1) && (b1 != c1);
                boolean ilv2U_2 = (lumE != c2) && (b2 != c2);
                boolean ilv2U_3 = (lumE != c3) && (b3 != c3);

                // ── Edge detection rules ────────────────────────────────────
                // weighted_distance(a,b,c,d,e,f,g,h) = |a-b|+|c-d|+|e-f|+|g-h|
                // edr: interp_lv1 && (wd(e,c,g,i,h5,f4,h,f) < wd(h,d,i5,f,i4,b,e,i))

                // k=0: e=lumE, c=c0, g=c2, i=c3, h5=lumH5, f4=lumF4, h=b2, f=b3
                //      h=b2, d=b1, i5=lumI5, f=b3, i4=lumI4, b=b0, e=lumE, i=c3
                float wd1_0 = Math.abs(lumE-c0) + Math.abs(c2-c3) + Math.abs(lumH5-lumF4) + Math.abs(b2-b3);
                float wd2_0 = Math.abs(b2-b1) + Math.abs(lumI5-b3) + Math.abs(lumI4-b0) + Math.abs(lumE-c3);
                boolean edr0 = ilv1_0 && (wd1_0 < wd2_0);

                // k=1: e=lumE, c=c1, g=c3, i=c0, h5=lumF4, f4=lumB1, h=b3, f=b0
                //      h=b3, d=b2, i5=lumC4, f=b0, i4=lumC1, b=b1, e=lumE, i=c0
                float wd1_1 = Math.abs(lumE-c1) + Math.abs(c3-c0) + Math.abs(lumF4-lumB1) + Math.abs(b3-b0);
                float wd2_1 = Math.abs(b3-b2) + Math.abs(lumC4-b0) + Math.abs(lumC1-b1) + Math.abs(lumE-c0);
                boolean edr1 = ilv1_1 && (wd1_1 < wd2_1);

                // k=2: e=lumE, c=c2, g=c0, i=c1, h5=lumB1, f4=lumD0, h=b0, f=b1
                //      h=b0, d=b3, i5=lumA1, f=b1, i4=lumA0, b=b2, e=lumE, i=c1
                float wd1_2 = Math.abs(lumE-c2) + Math.abs(c0-c1) + Math.abs(lumB1-lumD0) + Math.abs(b0-b1);
                float wd2_2 = Math.abs(b0-b3) + Math.abs(lumA1-b1) + Math.abs(lumA0-b2) + Math.abs(lumE-c1);
                boolean edr2 = ilv1_2 && (wd1_2 < wd2_2);

                // k=3: e=lumE, c=c3, g=c1, i=c2, h5=lumD0, f4=lumH5, h=b1, f=b2
                //      h=b1, d=b0, i5=lumG0, f=b2, i4=lumG5, b=b3, e=lumE, i=c2
                float wd1_3 = Math.abs(lumE-c3) + Math.abs(c1-c2) + Math.abs(lumD0-lumH5) + Math.abs(b1-b2);
                float wd2_3 = Math.abs(b1-b0) + Math.abs(lumG0-b2) + Math.abs(lumG5-b3) + Math.abs(lumE-c2);
                boolean edr3 = ilv1_3 && (wd1_3 < wd2_3);

                // edr_left: interp_lv2_left && (coef*|f-g| <= |h-c|)
                // k=0: f=b3, g=c2, h=b2, c=c0
                boolean edrL0 = ilv2L_0 && (COEF * Math.abs(b3-c2) <= Math.abs(b2-c0));
                // k=1: f=b0, g=c3, h=b3, c=c1
                boolean edrL1 = ilv2L_1 && (COEF * Math.abs(b0-c3) <= Math.abs(b3-c1));
                // k=2: f=b1, g=c0, h=b0, c=c2
                boolean edrL2 = ilv2L_2 && (COEF * Math.abs(b1-c0) <= Math.abs(b0-c2));
                // k=3: f=b2, g=c1, h=b1, c=c3
                boolean edrL3 = ilv2L_3 && (COEF * Math.abs(b2-c1) <= Math.abs(b1-c3));

                // edr_up: interp_lv2_up && (|f-g| >= coef*|h-c|)
                boolean edrU0 = ilv2U_0 && (Math.abs(b3-c2) >= COEF * Math.abs(b2-c0));
                boolean edrU1 = ilv2U_1 && (Math.abs(b0-c3) >= COEF * Math.abs(b3-c1));
                boolean edrU2 = ilv2U_2 && (Math.abs(b1-c0) >= COEF * Math.abs(b0-c2));
                boolean edrU3 = ilv2U_3 && (Math.abs(b2-c1) >= COEF * Math.abs(b1-c3));

                // ── New color flags: nc[k] = edr[k] && (fx[k] || (edrL[k]&&fxL[k]) || (edrU[k]&&fxU[k])) ──
                boolean nc0 = edr0 && (fx0  || (edrL0 && fxL0) || (edrU0 && fxU0));
                boolean nc1 = edr1 && (fx1  || (edrL1 && fxL1) || (edrU1 && fxU1));
                boolean nc2 = edr2 && (fx2  || (edrL2 && fxL2) || (edrU2 && fxU2));
                boolean nc3 = edr3 && (fx3  || (edrL3 && fxL3) || (edrU3 && fxU3));

                // ── Pixel selection: px[k] = |e-f| <= |e-h| ─────────────────
                boolean px0 = Math.abs(lumE - b3) <= Math.abs(lumE - b2);
                boolean px1 = Math.abs(lumE - b0) <= Math.abs(lumE - b3);
                boolean px2 = Math.abs(lumE - b1) <= Math.abs(lumE - b0);
                boolean px3 = Math.abs(lumE - b2) <= Math.abs(lumE - b1);

                // ── Final color selection ────────────────────────────────────
                // nc[0] ? (px[0] ? F : H) : nc[1] ? (px[1] ? B : F)
                //     : nc[2] ? (px[2] ? D : B) : nc[3] ? (px[3] ? H : D) : E
                int result;
                if (nc0) {
                    result = px0 ? pF : pH;
                } else if (nc1) {
                    result = px1 ? pB : pF;
                } else if (nc2) {
                    result = px2 ? pD : pB;
                } else if (nc3) {
                    result = px3 ? pH : pD;
                } else {
                    result = pE;
                }

                // ── Anti-color-bleeding clamp ──────────────────────────────
                // When XBR selects a neighbor color (F/H/B/D) that differs
                // significantly from the center pixel E in any single channel,
                // it can produce isolated color dots (red/purple/yellow) at
                // hard edges — "color bleeding". This post-step clamps the
                // per-channel deviation from E to a maximum of BLEED_LIMIT,
                // preventing isolated bright dots while preserving the edge
                // smoothing effect.
                if (result != pE) {
                    final int BLEED_LIMIT = 80; // max per-channel deviation from E
                    int eR = (pE >> 16) & 0xFF;
                    int eG = (pE >> 8) & 0xFF;
                    int eB = pE & 0xFF;
                    int rR = (result >> 16) & 0xFF;
                    int rG = (result >> 8) & 0xFF;
                    int rB = result & 0xFF;
                    int dR = rR - eR;
                    int dG = rG - eG;
                    int dB = rB - eB;
                    if (dR > BLEED_LIMIT) rR = eR + BLEED_LIMIT;
                    else if (dR < -BLEED_LIMIT) rR = eR - BLEED_LIMIT;
                    if (dG > BLEED_LIMIT) rG = eG + BLEED_LIMIT;
                    else if (dG < -BLEED_LIMIT) rG = eG - BLEED_LIMIT;
                    if (dB > BLEED_LIMIT) rB = eB + BLEED_LIMIT;
                    else if (dB < -BLEED_LIMIT) rB = eB - BLEED_LIMIT;
                    result = (result & 0xFF000000) | (rR << 16) | (rG << 8) | rB;
                }

                dp[oy * dw + ox] = result;
            }
        }

        Bitmap result = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        result.setPixels(dp, 0, dw, 0, 0, dw, dh);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  HQ4x Algorithm (guest(r)'s 4xGLSLHqFilter)
    //  Weighted interpolation using 13 texels
    // ════════════════════════════════════════════════════════════════════

    /**
     * HQ4x upscaling. Based on the 4xGLSLHqFilter shader by guest(r).
     *
     * <p>Samples 13 texels: center (c), 4 inner (i1-i4), 4 outer (o1-o4),
     * 4 side (s1-s4). Computes edge weights and applies weighted interpolation.
     * Each source pixel produces a 4×4 block with the same filtered color.
     */
    private static Bitmap hq4xUpscale(Bitmap src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw = sw * 4;
        int dh = sh * 4;

        int[] sp = new int[sw * sh];
        src.getPixels(sp, 0, sw, 0, 0, sw, sh);
        int[] dp = new int[dw * dh];

        // Constants from the reference shader
        final float mx = 1.00f;       // start smoothing wt.
        final float k = -1.10f;       // wt. decrease factor
        final float max_w = 0.75f;    // max filter weight
        final float min_w = 0.03f;    // min filter weight
        final float lum_add = 0.33f;  // effects smoothing

        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                // Center
                int c = getPixelSafe(sp, sw, sh, x, y);

                // Inner neighbors (half-texel offsets)
                int i1 = getPixelSafe(sp, sw, sh, x,     y - 1); // top
                int i2 = getPixelSafe(sp, sw, sh, x + 1, y);     // right
                int i3 = getPixelSafe(sp, sw, sh, x,     y + 1); // bottom
                int i4 = getPixelSafe(sp, sw, sh, x - 1, y);     // left

                // Outer neighbors (full-texel diagonal offsets)
                int o1 = getPixelSafe(sp, sw, sh, x - 1, y - 1); // TL
                int o2 = getPixelSafe(sp, sw, sh, x + 1, y - 1); // TR
                int o3 = getPixelSafe(sp, sw, sh, x + 1, y + 1); // BR
                int o4 = getPixelSafe(sp, sw, sh, x - 1, y + 1); // BL

                // Side neighbors (quarter-texel offsets)
                int s1 = getPixelSafe(sp, sw, sh, x,     y - 2); // far top
                int s2 = getPixelSafe(sp, sw, sh, x + 2, y);     // far right
                int s3 = getPixelSafe(sp, sw, sh, x,     y + 2); // far bottom
                int s4 = getPixelSafe(sp, sw, sh, x - 2, y);     // far left

                // Convert to float vectors for computation
                float[] cv = toVec3(c);
                float[] i1v = toVec3(i1), i2v = toVec3(i2);
                float[] i3v = toVec3(i3), i4v = toVec3(i4);
                float[] o1v = toVec3(o1), o2v = toVec3(o2);
                float[] o3v = toVec3(o3), o4v = toVec3(o4);
                float[] s1v = toVec3(s1), s2v = toVec3(s2);
                float[] s3v = toVec3(s3), s4v = toVec3(s4);

                // ko = dot(abs(outer - center), dt)
                float ko1 = dotAbs(o1v, cv);
                float ko2 = dotAbs(o2v, cv);
                float ko3 = dotAbs(o3v, cv);
                float ko4 = dotAbs(o4v, cv);

                // k = min(dot(abs(inner_i - inner_j), dt), max(ko_a, ko_b))
                float sd1 = dotAbs(sub(i1v, i3v));
                float sd2 = dotAbs(sub(i2v, i4v));

                float k1 = Math.min(sd2, Math.max(ko1, ko3));
                float k2 = Math.min(sd1, Math.max(ko2, ko4));
                float k3 = Math.min(sd2, Math.max(ko3, ko1));
                float k4 = Math.min(sd1, Math.max(ko4, ko2));

                // First pass: smooth center
                float w1 = (ko3 < ko1) ? k1 * ko3 / Math.max(ko1, 0.001f) : k1;
                float w2 = (ko4 < ko2) ? k2 * ko4 / Math.max(ko2, 0.001f) : k2;
                float w3 = (ko1 < ko3) ? k3 * ko1 / Math.max(ko3, 0.001f) : k3;
                float w4 = (ko2 < ko4) ? k4 * ko2 / Math.max(ko4, 0.001f) : k4;

                float wsum = w1 + w2 + w3 + w4 + 0.001f;
                cv[0] = (w1 * o1v[0] + w2 * o2v[0] + w3 * o3v[0] + w4 * o4v[0] + 0.001f * cv[0]) / wsum;
                cv[1] = (w1 * o1v[1] + w2 * o2v[1] + w3 * o3v[1] + w4 * o4v[1] + 0.001f * cv[1]) / wsum;
                cv[2] = (w1 * o1v[2] + w2 * o2v[2] + w3 * o3v[2] + w4 * o4v[2] + 0.001f * cv[2]) / wsum;

                // Second pass: weighted interpolation
                float lc = cv[0] + cv[1] + cv[2] + 0.2f;

                w1 = clamp(k * dotAbs(sub(i1v, cv)) / (0.125f * (i1v[0] + i1v[1] + i1v[2]) + lum_add) + mx, min_w, max_w);
                w2 = clamp(k * dotAbs(sub(i2v, cv)) / (0.125f * (i2v[0] + i2v[1] + i2v[2]) + lum_add) + mx, min_w, max_w);
                w3 = clamp(k * dotAbs(sub(i3v, cv)) / (0.125f * (i3v[0] + i3v[1] + i3v[2]) + lum_add) + mx, min_w, max_w);
                w4 = clamp(k * dotAbs(sub(i4v, cv)) / (0.125f * (i4v[0] + i4v[1] + i4v[2]) + lum_add) + mx, min_w, max_w);

                float w5 = clamp(k * dotAbs(sub(s1v, cv)) / (0.125f * (s1v[0] + s1v[1] + s1v[2]) + lum_add) + mx, min_w, max_w);
                float w6 = clamp(k * dotAbs(sub(s2v, cv)) / (0.125f * (s2v[0] + s2v[1] + s2v[2]) + lum_add) + mx, min_w, max_w);
                float w7 = clamp(k * dotAbs(sub(s3v, cv)) / (0.125f * (s3v[0] + s3v[1] + s3v[2]) + lum_add) + mx, min_w, max_w);
                float w8 = clamp(k * dotAbs(sub(s4v, cv)) / (0.125f * (s4v[0] + s4v[1] + s4v[2]) + lum_add) + mx, min_w, max_w);

                float tw = 2.0f * (w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8) + 1.0f;
                float r = (w1 * (i1v[0] + i3v[0]) + w2 * (i2v[0] + i4v[0]) +
                           w5 * (s1v[0] + s3v[0]) + w6 * (s2v[0] + s4v[0]) + cv[0]) / tw;
                float g = (w1 * (i1v[1] + i3v[1]) + w2 * (i2v[1] + i4v[1]) +
                           w5 * (s1v[1] + s3v[1]) + w6 * (s2v[1] + s4v[1]) + cv[1]) / tw;
                float b = (w1 * (i1v[2] + i3v[2]) + w2 * (i2v[2] + i4v[2]) +
                           w5 * (s1v[2] + s3v[2]) + w6 * (s2v[2] + s4v[2]) + cv[2]) / tw;

                int result = clampRGB(r, g, b);

                // Fill 4x4 block
                for (int sy = 0; sy < 4; sy++) {
                    int rowStart = (y * 4 + sy) * dw + x * 4;
                    for (int sx = 0; sx < 4; sx++) {
                        dp[rowStart + sx] = result;
                    }
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        result.setPixels(dp, 0, dw, 0, 0, dw, dh);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Dot Mask (Themaister's dot shader)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Applies the dot mask to an upscaled bitmap (for +dot modes).
     * Uses the reference dot shader's lookup function:
     * {@code color * exp(-gamma * delta * color_bloom(color))}
     * with 9-tap sampling and {@code mix(1.1 * mid, color, 0.65)}.
     */
    private static Bitmap applyDotMask(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        final float gamma = 2.4f;
        final float shine = 0.05f;
        final float blend = 0.65f;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // pixel_no = position in source-resolution space
                float px = x;
                float py = y;

                // 9-tap sampling
                float[] mid = lookup(px, py, 0, 0, pixels, w, h, gamma, shine);
                float[] sum = new float[3];

                addLookup(sum, px, py, -1, -1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  0, -1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  1, -1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py, -1,  0, pixels, w, h, gamma, shine);
                addLookup(sum, mid);
                addLookup(sum, px, py,  1,  0, pixels, w, h, gamma, shine);
                addLookup(sum, px, py, -1,  1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  0,  1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  1,  1, pixels, w, h, gamma, shine);

                // mix(1.1 * mid_color, color, blend) — 1.1 instead of 1.2 to avoid over-bright
                float r = mix(1.1f * mid[0], sum[0], blend);
                float g = mix(1.1f * mid[1], sum[1], blend);
                float b = mix(1.1f * mid[2], sum[2], blend);

                int idx = y * w + x;
                int a = (pixels[idx] >> 24) & 0xFF;
                pixels[idx] = (a << 24) | clampRGB(r, g, b);
            }
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static float[] lookup(float px, float py, float ox, float oy,
                                  int[] pixels, int w, int h,
                                  float gamma, float shine) {
        int sx = clampInt((int)(px + ox), 0, w - 1);
        int sy = clampInt((int)(py + oy), 0, h - 1);
        int color = pixels[sy * w + sx];
        float[] rgb = toVec3(color);

        float deltaX = (float)(px - Math.floor(px)) - (ox + 0.5f);
        float deltaY = (float)(py - Math.floor(py)) - (oy + 0.5f);
        float delta = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        float bright = rgb[0] * 0.30f + rgb[1] * 0.59f + rgb[2] * 0.11f;
        float bloom = mix(1.0f + shine, 1.0f - shine, bright);

        float factor = (float) Math.exp(-gamma * delta * bloom);
        rgb[0] *= factor;
        rgb[1] *= factor;
        rgb[2] *= factor;
        return rgb;
    }

    private static void addLookup(float[] sum, float px, float py, float ox, float oy,
                                  int[] pixels, int w, int h,
                                  float gamma, float shine) {
        float[] v = lookup(px, py, ox, oy, pixels, w, h, gamma, shine);
        sum[0] += v[0];
        sum[1] += v[1];
        sum[2] += v[2];
    }

    private static void addLookup(float[] sum, float[] v) {
        sum[0] += v[0];
        sum[1] += v[1];
        sum[2] += v[2];
    }

    // ════════════════════════════════════════════════════════════════════
    //  Scanline overlay for upscaled bitmaps (xBR/HQ4x + Scanline combos)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 放大后位图的扫描线叠加：每个源像素行（放大后占 {@code scale} 物理行）
     * 的最后一行压暗 —— 与 GL 路径「每源行一条扫描线」的观感一致。
     * 就地修改并返回原 bitmap（调用方持有缓存，无需复制）。
     */
    private static Bitmap applyScanlineMask(Bitmap bmp, int scale) {
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w <= 0 || h <= 0 || scale <= 1) return bmp;
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            final float dim = 0.62f;   // 扫描线暗行亮度系数
            for (int y = 0; y < h; y++) {
                // 源行内位置：放大后每 scale 行构成一个源像素行
                int inRow = y % scale;
                if (inRow != scale - 1) continue;   // 只压暗每组最后一行
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    int c = pixels[idx];
                    int r = (int)(((c >> 16) & 0xFF) * dim);
                    int g = (int)(((c >> 8) & 0xFF) * dim);
                    int b = (int)((c & 0xFF) * dim);
                    pixels[idx] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        } catch (Throwable ignored) {
            // 扫描线叠加失败不影响游戏画面 —— 忽略
        }
        return bmp;
    }

    // ════════════════════════════════════════════════════════════════════
    //  TV appearance (仿电视机) — CPU 路径
    //  drawTvFrame：20×20 网格桶形弧面绘制（矩形裁剪，全出血无黑边）；
    //  applyTvMask：扫描线 + 暗角 + 玻璃高光。
    //  异常时全部退化保护（普通绘制），绝不因滤镜崩溃。
    // ════════════════════════════════════════════════════════════════════

    /** 桶形弯曲强度（与 GLSL nsCurve 的 5.4/3.6 参数观感一致）。 */
    private static final float TV_CURVATURE = 0.07f;

    /** 绘制整张位图（放大后的）到弧面。 */
    private static void drawTvFrame(Bitmap bmp, Canvas canvas, RectF rect) {
        drawTvFrame(bmp, bmp.getWidth(), bmp.getHeight(), canvas, rect);
    }

    /**
     * 只取活动区域 srcW×srcH 绘制到弧面（Image.setSize 缩小位图时避免
     * 把陈旧边缘像素画出来）。用 20×20 drawBitmapMesh 网格做桶形变形，
     * 四周向外鼓；矩形裁剪保证全出血（无大黑边、不遮挡扫描线遮罩）。
     */
    private static void drawTvFrame(Bitmap bmp, int srcW, int srcH,
                                    Canvas canvas, RectF rect) {
        try {
            if (bmp == null || bmp.isRecycled() || rect.width() <= 0 || rect.height() <= 0) return;
            Bitmap work = bmp;
            if (srcW < bmp.getWidth() || srcH < bmp.getHeight()) {
                try {
                    work = Bitmap.createBitmap(bmp, 0, 0, srcW, srcH);
                } catch (Throwable ignored) {
                    work = bmp;   // 裁剪失败退回整图
                }
            }

            // 矩形裁剪（★ 不再圆角裁剪/不画黑边框 —— 大黑边会遮挡扫描线遮罩）
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRect(rect, android.graphics.Path.Direction.CW);

            canvas.save();
            canvas.clipPath(clip);

            // 20×20 网格桶形变形：均匀源网格 → 桶形目标位置（中心不动、边缘外鼓）
            final int N = 20;
            final int cols = N + 1, rows = N + 1;
            float[] verts = new float[cols * rows * 2];
            float cx = rect.centerX(), cy = rect.centerY();
            float halfW = rect.width() * 0.5f, halfH = rect.height() * 0.5f;
            for (int j = 0; j < rows; j++) {
                // v ∈ [-1, 1]
                float v = (j / (float) N) * 2f - 1f;
                for (int i = 0; i < cols; i++) {
                    float u = (i / (float) N) * 2f - 1f;
                    // 桶形：|uv|² 加权外推，k = TV_CURVATURE
                    float r2 = u * u + v * v;
                    float bu = u * (1f + TV_CURVATURE * r2);
                    float bv = v * (1f + TV_CURVATURE * r2);
                    int idx = (j * cols + i) * 2;
                    verts[idx]     = cx + bu * halfW;
                    verts[idx + 1] = cy + bv * halfH;
                }
            }
            android.graphics.Paint meshPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmapMesh(work, N, N, verts, 0, null, 0, meshPaint);
            canvas.restore();

            // ★ 不再绘制 70% 黑暗边框 —— 黑边会遮挡扫描线遮罩（用户要求）

            if (work != bmp) work.recycle();
        } catch (Throwable ignored) {
            // 滤镜绘制任何异常都退化为普通绘制 —— 游戏画面优先
            try {
                canvas.drawBitmap(bmp, new Rect(0, 0, srcW, srcH), rect, sNearestPaint);
            } catch (Throwable ignored2) { }
        }
    }

    /**
     * TV 遮罩叠加（画在弧面画面之上）：水平扫描线 + 径向暗角 +
     * 顶部玻璃高光弧带。全部用 RectF 内的渐变/线条，无位图分配。
     */
    private static void applyTvMask(Canvas canvas, RectF rect) {
        try {
            float w = rect.width();
            float h = rect.height();
            if (w <= 0 || h <= 0) return;

            // 1) 扫描线：每 3px 一条 35% 黑线
            sMaskPaint.setAntiAlias(false);
            sMaskPaint.setColor(0x59000000);   // 35% 黑
            float lineStep = Math.max(3f, h / 240f);
            for (float y = rect.top + lineStep; y < rect.bottom; y += lineStep) {
                canvas.drawLine(rect.left, y, rect.right, y, sMaskPaint);
            }

            // 2) 暗角：径向渐变（中心透明 → 边缘 55% 黑）
            android.graphics.RadialGradient vg = new android.graphics.RadialGradient(
                    rect.centerX(), rect.centerY(),
                    (float) (Math.max(w, h) * 0.72),
                    new int[]{0x00000000, 0x00000000, 0x8C000000},
                    new float[]{0f, 0.55f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            sMaskPaint.setShader(vg);
            sMaskPaint.setAntiAlias(true);
            canvas.drawRect(rect, sMaskPaint);
            sMaskPaint.setShader(null);

            // 3) 玻璃高光：屏幕上部一道微弱白色弧形反光（立体感）
            android.graphics.LinearGradient gloss = new android.graphics.LinearGradient(
                    0, rect.top + h * 0.04f, 0, rect.top + h * 0.38f,
                    new int[]{0x00000000, 0x14FFFFFF, 0x00000000},
                    new float[]{0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            sMaskPaint.setShader(gloss);
            canvas.drawRect(rect.left, rect.top + h * 0.04f,
                    rect.right, rect.top + h * 0.38f, sMaskPaint);
            sMaskPaint.setShader(null);
            sMaskPaint.setAntiAlias(false);
        } catch (Throwable ignored) {
            // 遮罩失败不影响画面
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Canvas Mask Overlay (scanline/CRT/dot for non-GL mode)
    // ════════════════════════════════════════════════════════════════════

    private static void applyCanvasMask(Canvas canvas, RectF rect, int mode) {
        sMaskPaint.setAntiAlias(false);
        switch (mode) {
            case MODE_SCANLINE:
                drawScanlineMask(canvas, rect);
                break;
            case MODE_CRT:
                drawCrtMask(canvas, rect);
                break;
            case MODE_DOT:
                drawDotMask(canvas, rect);
                break;
        }
    }

    private static void drawScanlineMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float right = rect.right;
        float top = rect.top;
        float bottom = rect.bottom;
        float height = bottom - top;

        for (float y = top; y < bottom; y += 1.0f) {
            float t = (y - top) / height;
            float sine = (float) Math.sin(t * Math.PI * 2.0 * height / 4.0f);
            float brightness = 0.95f + 0.15f * sine;
            brightness = Math.max(0.0f, Math.min(1.0f, brightness));
            int alpha = (int) ((1.0f - brightness) * 200);
            sMaskPaint.setColor((alpha << 24));
            canvas.drawRect(left, y, right, y + 1, sMaskPaint);
        }
    }

    private static void drawCrtMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;

        // RGB phosphor stripes
        sMaskPaint.setColor(0x22FF0000);
        for (float x = left; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }
        sMaskPaint.setColor(0x2200FF00);
        for (float x = left + 1; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }
        sMaskPaint.setColor(0x220000FF);
        for (float x = left + 2; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }

        // Scanlines
        sMaskPaint.setColor(0x50000000);
        for (float y = top; y < bottom; y += 3) {
            canvas.drawRect(left, y, right, Math.min(y + 1, bottom), sMaskPaint);
        }

        // Vignette
        float cx = (left + right) / 2;
        float cy = (top + bottom) / 2;
        float maxDist = (float) Math.sqrt(
                (right - cx) * (right - cx) + (bottom - cy) * (bottom - cy));
        sMaskPaint.setColor(0x30000000);
        for (float r = maxDist * 0.6f; r < maxDist; r += 2) {
            int alpha = (int) (80 * (r - maxDist * 0.6f) / (maxDist * 0.4f));
            sMaskPaint.setColor((alpha << 24));
            canvas.drawCircle(cx, cy, r, sMaskPaint);
        }
    }

    private static void drawDotMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;

        final float gamma = 2.4f;
        final float shine = 0.05f;

        for (float y = top; y < bottom; y += 1.0f) {
            for (float x = left; x < right; x += 1.0f) {
                float dx = (float)(x - Math.floor(x)) - 0.5f;
                float dy = (float)(y - Math.floor(y)) - 0.5f;
                float delta = (float) Math.sqrt(dx * dx + dy * dy);
                float factor = (float) Math.exp(-gamma * delta * (1.0f - shine));
                int alpha = (int) ((1.0f - factor) * 120);
                if (alpha > 0) {
                    sMaskPaint.setColor((alpha << 24));
                    canvas.drawPoint(x, y, sMaskPaint);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Utility methods
    // ════════════════════════════════════════════════════════════════════

    private static int getPixelSafe(int[] pixels, int w, int h, int x, int y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= w) x = w - 1;
        if (y >= h) y = h - 1;
        return pixels[y * w + x];
    }

    private static float[] toVec3(int color) {
        return new float[] {
            ((color >> 16) & 0xFF) / 255.0f,
            ((color >> 8) & 0xFF) / 255.0f,
            (color & 0xFF) / 255.0f
        };
    }

    private static float dotAbs(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    private static float dotAbs(float[] v) {
        return Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2]);
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float mix(float a, float b, float t) {
        return a * (1.0f - t) + b * t;
    }

    private static int clampRGB(float r, float g, float b) {
        int ri = clampInt(Math.round(r * 255), 0, 255);
        int gi = clampInt(Math.round(g * 255), 0, 255);
        int bi = clampInt(Math.round(b * 255), 0, 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int blendColor(int c1, int c2, float ratio) {
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int r = Math.round(r1 * (1 - ratio) + r2 * ratio);
        int g = Math.round(g1 * (1 - ratio) + g2 * ratio);
        int b = Math.round(b1 * (1 - ratio) + b2 * ratio);
        int a = (c1 >> 24) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Weighted luminance for XBR edge detection: {@code dot(color_rgb, rgbw)}.
     * Uses integer 0-255 RGB channels directly (the rgbw weights are scaled
     * appropriately so the result is a single scalar for comparison).
     */
    private static float lum(int color, float rw, float gw, float bw) {
        float r = (color >> 16) & 0xFF;
        float g = (color >> 8) & 0xFF;
        float b = color & 0xFF;
        return r * rw + g * gw + b * bw;
    }

    public static void release() {
        if (sFilteredBitmap != null) {
            sFilteredBitmap.recycle();
            sFilteredBitmap = null;
        }
        sCachedSrcW = -1;
        sCachedSrcH = -1;
        sCachedMode = -1;
    }
}
