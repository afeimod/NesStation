package javax.microedition.lcdui.graphics;

/**
 * GLSL vertex + fragment shader sources for J2ME video filters.
 *
 * <p>Filter modes (must match {@code J2meBitmapFilter} constants):
 * <pre>
 *   0 = None         (passthrough)
 *   1 = Scanline      (scanlines-emu)
 *   2 = CRT           (crt, u_time removed)
 *   3 = Dot           (LCD dot effect, full version)
 *   4 = 2xBR          (Hyllian 2xBR)
 *   5 = 4xBR          (Hyllian 4xBR)
 *   6 = 2xBR + Dot
 *   7 = 4xBR + Dot
 *   8 = HQ4x          (4xGLSLHqFilter)
 *   9 = HQ4x + Dot
 *  10 = TV            (仿电视机：桶形弧面 + 四角圆角 + 暗角 + 扫描线 + 玻璃高光)
 *  11 = 2xBR + Scanline
 *  12 = 4xBR + Scanline
 *  13 = HQ4x + Scanline
 *  14 = 2xBR + TV     (单 pass 真弯曲采样)
 *  15 = 4xBR + TV
 *  16 = HQ4x + TV
 * </pre>
 * 追加式编号（新模式只能加在尾部）保证旧存档里的滤镜序号不漂移。
 *
 * <p>XBR shaders implement Hyllian's 5xBR v3.5a algorithm with weighted RGB luminance
 * edge detection, 21-pixel sampling, interpolation restriction, and line-inequality
 * edge positioning. Scale-independent (2xBR and 4xBR share the same fragment shader).
 */
public final class J2meFilterShaders {

    // ─── Mode constants ──────────────────────────────────────────────────────
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

    // ─── Default passthrough shaders (mode 0) ────────────────────────────────

    /** Passthrough vertex shader — uses vec2 a_position (consistent with all filter shaders). */
    public static final String VERTEX_SHADER =
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "}\n";

    /** Plain passthrough fragment shader (no filter). */
    public static final String FRAGMENT_NONE =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sampler0, v_texcoord0);\n" +
            "}\n";

    // ─── Vertex shaders ──────────────────────────────────────────────────────

    /** Scanline vertex shader. */
    public static final String VERTEX_SCANLINE =
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec2 omega;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "    omega = vec2(3.1415 / u_pixelDelta.x / u_texelDelta.x * u_texelDelta.x, 2.0 * 3.1415 / u_texelDelta.y);\n" +
            "}\n";

    /** CRT vertex shader. */
    public static final String VERTEX_CRT =
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "}\n";

    /** Dot vertex shader. Pre-computes the 3x3 neighborhood texcoords. */
    public static final String VERTEX_DOT =
            "uniform vec2 u_texelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec4 v_texcoord1;\n" +
            "varying vec4 v_texcoord2;\n" +
            "varying vec4 v_texcoord3;\n" +
            "varying vec4 v_texcoord4;\n" +
            "varying vec2 v_texcoord5;\n" +
            "varying vec2 v_texcoord6;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "\n" +
            "    float dx = u_texelDelta.x;\n" +
            "    float dy = u_texelDelta.y;\n" +
            "\n" +
            "    v_texcoord1 = vec4(v_texcoord0 + vec2(-dx, -dy), v_texcoord0 + vec2(0.0, -dy));\n" +
            "    v_texcoord2 = vec4(v_texcoord0 + vec2(dx, -dy), v_texcoord0 + vec2(-dx, 0.0));\n" +
            "    v_texcoord3 = vec4(v_texcoord0 + vec2(dx, 0.0), v_texcoord0 + vec2(-dx, dy));\n" +
            "    v_texcoord4 = vec4(v_texcoord0 + vec2(0.0, dy), v_texcoord0 + vec2(dx, dy));\n" +
            "    v_texcoord5 = v_texcoord0;\n" +
            "    v_texcoord6 = v_texcoord0 * (1.0 / u_texelDelta.xy);\n" +
            "}\n";

    /**
     * 2xBR vertex shader — passthrough for Hyllian's 5xBR v3.5a.
     * Fragment shader computes all texture coordinates internally
     * via u_texelDelta and u_pixelDelta uniforms.
     */
    public static final String VERTEX_2XBR =
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_tc0;\n" +
            "\n" +
            "void main() {\n" +
            "    v_tc0 = a_texcoord0;\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "}\n";

    /** 4xBR vertex shader — identical to 2xBR. */
    public static final String VERTEX_4XBR = VERTEX_2XBR;

    /**
     * HQ4x vertex shader (hq4x.vsh) — Uses INDIVIDUAL vec4 varyings
     * (v_tc0..v_tc6) instead of array varying vec4 v_texcoord0[7].
     */
    public static final String VERTEX_HQ4X =
            "uniform vec2 u_texelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec4 v_tc0;\n" +
            "varying vec4 v_tc1;\n" +
            "varying vec4 v_tc2;\n" +
            "varying vec4 v_tc3;\n" +
            "varying vec4 v_tc4;\n" +
            "varying vec4 v_tc5;\n" +
            "varying vec4 v_tc6;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec2 dg1 = 0.5 * u_texelDelta;\n" +
            "    vec2 dg2 = vec2(-dg1.x, dg1.y);\n" +
            "    vec2 sd1 = dg1 * 0.5;\n" +
            "    vec2 sd2 = dg2 * 0.5;\n" +
            "    vec2 ddx = vec2(dg1.x, 0.0);\n" +
            "    vec2 ddy = vec2(0.0, dg1.y);\n" +
            "\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_tc0.xy = a_texcoord0;\n" +
            "    v_tc1.xy = a_texcoord0 - sd1;\n" +
            "    v_tc2.xy = a_texcoord0 - sd2;\n" +
            "    v_tc3.xy = a_texcoord0 + sd1;\n" +
            "    v_tc4.xy = a_texcoord0 + sd2;\n" +
            "    v_tc5.xy = a_texcoord0 - dg1;\n" +
            "    v_tc6.xy = a_texcoord0 + dg1;\n" +
            "    v_tc5.zw = a_texcoord0 - dg2;\n" +
            "    v_tc6.zw = a_texcoord0 + dg2;\n" +
            "    v_tc1.zw = a_texcoord0 - ddy;\n" +
            "    v_tc2.zw = a_texcoord0 + ddx;\n" +
            "    v_tc3.zw = a_texcoord0 + ddy;\n" +
            "    v_tc4.zw = a_texcoord0 - ddx;\n" +
            "}\n";

    // ─── Fragment shaders ────────────────────────────────────────────────────

    /** Scanline fragment shader. */
    public static final String FRAGMENT_SCANLINE =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec2 omega;\n" +
            "\n" +
            "const float base_brightness = 0.95;\n" +
            "const vec2 sine_comp = vec2(0.05, 0.15);\n" +
            "\n" +
            "void main () {\n" +
            "    vec4 c11 = texture2D(sampler0, v_texcoord0);\n" +
            "    vec4 scanline = c11 * (base_brightness + dot(sine_comp * sin(v_texcoord0 * omega), vec2(1.0)));\n" +
            "    gl_FragColor = clamp(scanline, 0.0, 1.0);\n" +
            "}\n";

    /** CRT fragment shader. */
    public static final String FRAGMENT_CRT =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    int vPos = int( v_texcoord0.y * 272.0 );\n" +
            "    float line_intensity = mod( float(vPos), 2.0 );\n" +
            "    float off = line_intensity * 0.0005;\n" +
            "    vec2 shift = vec2( off, 0 );\n" +
            "    vec2 colorShift = vec2( 0.001, 0 );\n" +
            "    float r = texture2D( sampler0, v_texcoord0 + colorShift + shift ).x;\n" +
            "    float g = texture2D( sampler0, v_texcoord0 - colorShift + shift ).y;\n" +
            "    float b = texture2D( sampler0, v_texcoord0 ).z;\n" +
            "    vec4 c = vec4( r, g * 0.99, b, 1.0 ) * clamp( line_intensity, 0.85, 1.0 );\n" +
            "    float rollbar = sin( v_texcoord0.y * 4.0 );\n" +
            "    gl_FragColor.rgba = c + (rollbar * 0.02);\n" +
            "}\n";

    /** Dot fragment shader. */
    public static final String FRAGMENT_DOT =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "#define gamma 2.4\n" +
            "#define shine 0.05\n" +
            "#define blend 0.65\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec4 v_texcoord1;\n" +
            "varying vec4 v_texcoord2;\n" +
            "varying vec4 v_texcoord3;\n" +
            "varying vec4 v_texcoord4;\n" +
            "varying vec2 v_texcoord5;\n" +
            "varying vec2 v_texcoord6;\n" +
            "\n" +
            "float dist(vec2 coord, vec2 source)\n" +
            "{\n" +
            "    vec2 delta = coord - source;\n" +
            "    return sqrt(dot(delta, delta));\n" +
            "}\n" +
            "\n" +
            "float color_bloom(vec3 color)\n" +
            "{\n" +
            "    const vec3 gray_coeff = vec3(0.30, 0.59, 0.11);\n" +
            "    float bright = dot(color, gray_coeff);\n" +
            "    return mix(1.0 + shine, 1.0 - shine, bright);\n" +
            "}\n" +
            "\n" +
            "vec3 lookup(vec2 pixel_no, float offset_x, float offset_y, vec3 color)\n" +
            "{\n" +
            "    vec2 offset = vec2(offset_x, offset_y);\n" +
            "    float delta = dist(fract(pixel_no), offset + vec2(0.5, 0.5));\n" +
            "    return color * exp(-gamma * delta * color_bloom(color));\n" +
            "}\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 mid_color = lookup(v_texcoord6, 0.0, 0.0, texture2D(sampler0, v_texcoord5).rgb);\n" +
            "    vec3 color = vec3(0.0, 0.0, 0.0);\n" +
            "    color += lookup(v_texcoord6, -1.0, -1.0, texture2D(sampler0, v_texcoord1.xy).rgb);\n" +
            "    color += lookup(v_texcoord6,  0.0, -1.0, texture2D(sampler0, v_texcoord1.zw).rgb);\n" +
            "    color += lookup(v_texcoord6,  1.0, -1.0, texture2D(sampler0, v_texcoord2.xy).rgb);\n" +
            "    color += lookup(v_texcoord6, -1.0,  0.0, texture2D(sampler0, v_texcoord2.zw).rgb);\n" +
            "    color += mid_color;\n" +
            "    color += lookup(v_texcoord6,  1.0,  0.0, texture2D(sampler0, v_texcoord3.xy).rgb);\n" +
            "    color += lookup(v_texcoord6, -1.0,  1.0, texture2D(sampler0, v_texcoord3.zw).rgb);\n" +
            "    color += lookup(v_texcoord6,  0.0,  1.0, texture2D(sampler0, v_texcoord4.xy).rgb);\n" +
            "    color += lookup(v_texcoord6,  1.0,  1.0, texture2D(sampler0, v_texcoord4.zw).rgb);\n" +
            "    vec3 out_color = mix(1.1 * mid_color, color, blend);\n" +
            "    out_color = clamp(out_color, 0.0, 1.0);\n" +
            "    gl_FragColor = vec4(out_color, 1.0);\n" +
            "}\n";

    /**
     * 2xBR fragment shader — Hyllian's 5xBR v3.5a algorithm.
     * Uses weighted RGB luminance for edge detection, samples 21 pixels
     * (3x3 core + 12 extended), and implements interpolation restriction
     * with line-inequality edge positioning. Skips processing when upscale
     * ratio is below 1.6x (falls back to nearest-neighbour).
     */
    public static final String FRAGMENT_2XBR =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "vec3 sampleTC(vec2 tc) { return texture2D(sampler0, clamp(tc, 0.5 * u_texelDelta, vec2(1.0) - 0.5 * u_texelDelta)).xyz; }\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = sampleTC(TexCoord_0 -dx -dy);\n" +
            "        vec3 B  = sampleTC(TexCoord_0     -dy);\n" +
            "        vec3 C  = sampleTC(TexCoord_0 +dx -dy);\n" +
            "        vec3 D  = sampleTC(TexCoord_0 -dx    );\n" +
            "        vec3 E  = sampleTC(TexCoord_0         );\n" +
            "        vec3 F  = sampleTC(TexCoord_0 +dx    );\n" +
            "        vec3 G  = sampleTC(TexCoord_0 -dx +dy);\n" +
            "        vec3 H  = sampleTC(TexCoord_0     +dy);\n" +
            "        vec3 I  = sampleTC(TexCoord_0 +dx +dy);\n" +
            "        vec3 A1 = sampleTC(TexCoord_0     -dx -y2);\n" +
            "        vec3 C1 = sampleTC(TexCoord_0     +dx -y2);\n" +
            "        vec3 A0 = sampleTC(TexCoord_0 -x2     -dy);\n" +
            "        vec3 G0 = sampleTC(TexCoord_0 -x2     +dy);\n" +
            "        vec3 C4 = sampleTC(TexCoord_0 +x2     -dy);\n" +
            "        vec3 I4 = sampleTC(TexCoord_0 +x2     +dy);\n" +
            "        vec3 G5 = sampleTC(TexCoord_0     -dx +y2);\n" +
            "        vec3 I5 = sampleTC(TexCoord_0     +dx +y2);\n" +
            "        vec3 B1 = sampleTC(TexCoord_0         -y2);\n" +
            "        vec3 D0 = sampleTC(TexCoord_0 -x2        );\n" +
            "        vec3 H5 = sampleTC(TexCoord_0         +y2);\n" +
            "        vec3 F4 = sampleTC(TexCoord_0 +x2        );\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp: limit per-channel deviation from E\n" +
            "        // to prevent isolated color dots (red/purple/yellow) at hard edges.\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** 4xBR fragment shader — same Hyllian 5xBR v3.5a algorithm as 2xBR (scale-independent). */
    public static final String FRAGMENT_4XBR =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "vec3 sampleTC(vec2 tc) { return texture2D(sampler0, clamp(tc, 0.5 * u_texelDelta, vec2(1.0) - 0.5 * u_texelDelta)).xyz; }\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = sampleTC(TexCoord_0 -dx -dy);\n" +
            "        vec3 B  = sampleTC(TexCoord_0     -dy);\n" +
            "        vec3 C  = sampleTC(TexCoord_0 +dx -dy);\n" +
            "        vec3 D  = sampleTC(TexCoord_0 -dx    );\n" +
            "        vec3 E  = sampleTC(TexCoord_0         );\n" +
            "        vec3 F  = sampleTC(TexCoord_0 +dx    );\n" +
            "        vec3 G  = sampleTC(TexCoord_0 -dx +dy);\n" +
            "        vec3 H  = sampleTC(TexCoord_0     +dy);\n" +
            "        vec3 I  = sampleTC(TexCoord_0 +dx +dy);\n" +
            "        vec3 A1 = sampleTC(TexCoord_0     -dx -y2);\n" +
            "        vec3 C1 = sampleTC(TexCoord_0     +dx -y2);\n" +
            "        vec3 A0 = sampleTC(TexCoord_0 -x2     -dy);\n" +
            "        vec3 G0 = sampleTC(TexCoord_0 -x2     +dy);\n" +
            "        vec3 C4 = sampleTC(TexCoord_0 +x2     -dy);\n" +
            "        vec3 I4 = sampleTC(TexCoord_0 +x2     +dy);\n" +
            "        vec3 G5 = sampleTC(TexCoord_0     -dx +y2);\n" +
            "        vec3 I5 = sampleTC(TexCoord_0     +dx +y2);\n" +
            "        vec3 B1 = sampleTC(TexCoord_0         -y2);\n" +
            "        vec3 D0 = sampleTC(TexCoord_0 -x2        );\n" +
            "        vec3 H5 = sampleTC(TexCoord_0         +y2);\n" +
            "        vec3 F4 = sampleTC(TexCoord_0 +x2        );\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp: limit per-channel deviation from E\n" +
            "        // to prevent isolated color dots (red/purple/yellow) at hard edges.\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** HQ4x fragment shader — individual vec4 varyings (v_tc0..v_tc6). */
    public static final String FRAGMENT_HQ4X =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec4 v_tc0;\n" +
            "varying vec4 v_tc1;\n" +
            "varying vec4 v_tc2;\n" +
            "varying vec4 v_tc3;\n" +
            "varying vec4 v_tc4;\n" +
            "varying vec4 v_tc5;\n" +
            "varying vec4 v_tc6;\n" +
            "vec3 sampleTC(vec2 tc) { return texture2D(sampler0, clamp(tc, vec2(0.001), vec2(0.999))).xyz; }\n" +
            "\n" +
            "const float mx = 1.00;\n" +
            "const float k = -1.10;\n" +
            "const float max_w = 0.75;\n" +
            "const float min_w = 0.03;\n" +
            "const float lum_add = 0.33;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 c  = sampleTC(v_tc0.xy);\n" +
            "    vec3 i1 = sampleTC(v_tc1.xy);\n" +
            "    vec3 i2 = sampleTC(v_tc2.xy);\n" +
            "    vec3 i3 = sampleTC(v_tc3.xy);\n" +
            "    vec3 i4 = sampleTC(v_tc4.xy);\n" +
            "    vec3 o1 = sampleTC(v_tc5.xy);\n" +
            "    vec3 o3 = sampleTC(v_tc6.xy);\n" +
            "    vec3 o2 = sampleTC(v_tc5.zw);\n" +
            "    vec3 o4 = sampleTC(v_tc6.zw);\n" +
            "    vec3 s1 = sampleTC(v_tc1.zw);\n" +
            "    vec3 s2 = sampleTC(v_tc2.zw);\n" +
            "    vec3 s3 = sampleTC(v_tc3.zw);\n" +
            "    vec3 s4 = sampleTC(v_tc4.zw);\n" +
            "    vec3 dt = vec3(1.0, 1.0, 1.0);\n" +
            "\n" +
            "    float ko1 = dot(abs(o1-c), dt);\n" +
            "    float ko2 = dot(abs(o2-c), dt);\n" +
            "    float ko3 = dot(abs(o3-c), dt);\n" +
            "    float ko4 = dot(abs(o4-c), dt);\n" +
            "\n" +
            "    float k1 = min(dot(abs(i1-i3), dt), max(ko1, ko3));\n" +
            "    float k2 = min(dot(abs(i2-i4), dt), max(ko2, ko4));\n" +
            "\n" +
            "    float w1 = k2; if (ko3 < ko1) w1 *= ko3/ko1;\n" +
            "    float w2 = k1; if (ko4 < ko2) w2 *= ko4/ko2;\n" +
            "    float w3 = k2; if (ko1 < ko3) w3 *= ko1/ko3;\n" +
            "    float w4 = k1; if (ko2 < ko4) w4 *= ko2/ko4;\n" +
            "\n" +
            "    c = (w1*o1 + w2*o2 + w3*o3 + w4*o4 + 0.001*c) / (w1+w2+w3+w4+0.001);\n" +
            "\n" +
            "    w1 = k*dot(abs(i1-c)+abs(i3-c), dt) / (0.125*dot(i1+i3, dt) + lum_add);\n" +
            "    w2 = k*dot(abs(i2-c)+abs(i4-c), dt) / (0.125*dot(i2+i4, dt) + lum_add);\n" +
            "    w3 = k*dot(abs(s1-c)+abs(s3-c), dt) / (0.125*dot(s1+s3, dt) + lum_add);\n" +
            "    w4 = k*dot(abs(s2-c)+abs(s4-c), dt) / (0.125*dot(s2+s4, dt) + lum_add);\n" +
            "\n" +
            "    w1 = clamp(w1 + mx, min_w, max_w);\n" +
            "    w2 = clamp(w2 + mx, min_w, max_w);\n" +
            "    w3 = clamp(w3 + mx, min_w, max_w);\n" +
            "    w4 = clamp(w4 + mx, min_w, max_w);\n" +
            "\n" +
            "    vec3 result = (w1*(i1+i3) + w2*(i2+i4) + w3*(s1+s3) + w4*(s2+s4) + c) / (2.0*(w1+w2+w3+w4) + 1.0);\n" +
            "    gl_FragColor = vec4(result, 1.0);\n" +
            "}\n";

    /** 2xBR + Dot fragment shader — 5xBR v3.5a with LCD dot-mask post-processing. */
    public static final String FRAGMENT_2XBR_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "vec3 sampleTC(vec2 tc) { return texture2D(sampler0, clamp(tc, 0.5 * u_texelDelta, vec2(1.0) - 0.5 * u_texelDelta)).xyz; }\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = sampleTC(TexCoord_0 -dx -dy);\n" +
            "        vec3 B  = sampleTC(TexCoord_0     -dy);\n" +
            "        vec3 C  = sampleTC(TexCoord_0 +dx -dy);\n" +
            "        vec3 D  = sampleTC(TexCoord_0 -dx    );\n" +
            "        vec3 E  = sampleTC(TexCoord_0         );\n" +
            "        vec3 F  = sampleTC(TexCoord_0 +dx    );\n" +
            "        vec3 G  = sampleTC(TexCoord_0 -dx +dy);\n" +
            "        vec3 H  = sampleTC(TexCoord_0     +dy);\n" +
            "        vec3 I  = sampleTC(TexCoord_0 +dx +dy);\n" +
            "        vec3 A1 = sampleTC(TexCoord_0     -dx -y2);\n" +
            "        vec3 C1 = sampleTC(TexCoord_0     +dx -y2);\n" +
            "        vec3 A0 = sampleTC(TexCoord_0 -x2     -dy);\n" +
            "        vec3 G0 = sampleTC(TexCoord_0 -x2     +dy);\n" +
            "        vec3 C4 = sampleTC(TexCoord_0 +x2     -dy);\n" +
            "        vec3 I4 = sampleTC(TexCoord_0 +x2     +dy);\n" +
            "        vec3 G5 = sampleTC(TexCoord_0     -dx +y2);\n" +
            "        vec3 I5 = sampleTC(TexCoord_0     +dx +y2);\n" +
            "        vec3 B1 = sampleTC(TexCoord_0         -y2);\n" +
            "        vec3 D0 = sampleTC(TexCoord_0 -x2        );\n" +
            "        vec3 H5 = sampleTC(TexCoord_0         +y2);\n" +
            "        vec3 F4 = sampleTC(TexCoord_0 +x2        );\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    vec2 pixel_no = v_tc0 / u_texelDelta;\n" +
            "    vec2 fp_dot = fract(pixel_no);\n" +
            "    float delta = length(fp_dot - vec2(0.5));\n" +
            "    float bright = dot(res, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    res = mix(1.1 * res, res * dotMask, 0.65);\n" +
            "    res = clamp(res, 0.0, 1.0);\n" +
            "\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** 4xBR + Dot fragment shader — 5xBR v3.5a with LCD dot-mask post-processing. */
    public static final String FRAGMENT_4XBR_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "vec3 sampleTC(vec2 tc) { return texture2D(sampler0, clamp(tc, 0.5 * u_texelDelta, vec2(1.0) - 0.5 * u_texelDelta)).xyz; }\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = sampleTC(TexCoord_0 -dx -dy);\n" +
            "        vec3 B  = sampleTC(TexCoord_0     -dy);\n" +
            "        vec3 C  = sampleTC(TexCoord_0 +dx -dy);\n" +
            "        vec3 D  = sampleTC(TexCoord_0 -dx    );\n" +
            "        vec3 E  = sampleTC(TexCoord_0         );\n" +
            "        vec3 F  = sampleTC(TexCoord_0 +dx    );\n" +
            "        vec3 G  = sampleTC(TexCoord_0 -dx +dy);\n" +
            "        vec3 H  = sampleTC(TexCoord_0     +dy);\n" +
            "        vec3 I  = sampleTC(TexCoord_0 +dx +dy);\n" +
            "        vec3 A1 = sampleTC(TexCoord_0     -dx -y2);\n" +
            "        vec3 C1 = sampleTC(TexCoord_0     +dx -y2);\n" +
            "        vec3 A0 = sampleTC(TexCoord_0 -x2     -dy);\n" +
            "        vec3 G0 = sampleTC(TexCoord_0 -x2     +dy);\n" +
            "        vec3 C4 = sampleTC(TexCoord_0 +x2     -dy);\n" +
            "        vec3 I4 = sampleTC(TexCoord_0 +x2     +dy);\n" +
            "        vec3 G5 = sampleTC(TexCoord_0     -dx +y2);\n" +
            "        vec3 I5 = sampleTC(TexCoord_0     +dx +y2);\n" +
            "        vec3 B1 = sampleTC(TexCoord_0         -y2);\n" +
            "        vec3 D0 = sampleTC(TexCoord_0 -x2        );\n" +
            "        vec3 H5 = sampleTC(TexCoord_0         +y2);\n" +
            "        vec3 F4 = sampleTC(TexCoord_0 +x2        );\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    vec2 pixel_no = v_tc0 / u_texelDelta;\n" +
            "    vec2 fp_dot = fract(pixel_no);\n" +
            "    float delta = length(fp_dot - vec2(0.5));\n" +
            "    float bright = dot(res, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    res = mix(1.1 * res, res * dotMask, 0.65);\n" +
            "    res = clamp(res, 0.0, 1.0);\n" +
            "\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** HQ4x + Dot: HQ4x followed by a dot-mask post-processing pass. */
    public static final String FRAGMENT_HQ4X_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "varying vec4 v_tc0;\n" +
            "varying vec4 v_tc1;\n" +
            "varying vec4 v_tc2;\n" +
            "varying vec4 v_tc3;\n" +
            "varying vec4 v_tc4;\n" +
            "varying vec4 v_tc5;\n" +
            "varying vec4 v_tc6;\n" +
            "vec3 sampleTC(vec2 tc) { return texture2D(sampler0, clamp(tc, vec2(0.001), vec2(0.999))).xyz; }\n" +
            "\n" +
            "const float mx = 1.00;\n" +
            "const float k = -1.10;\n" +
            "const float max_w = 0.75;\n" +
            "const float min_w = 0.03;\n" +
            "const float lum_add = 0.33;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 c  = sampleTC(v_tc0.xy);\n" +
            "    vec3 i1 = sampleTC(v_tc1.xy);\n" +
            "    vec3 i2 = sampleTC(v_tc2.xy);\n" +
            "    vec3 i3 = sampleTC(v_tc3.xy);\n" +
            "    vec3 i4 = sampleTC(v_tc4.xy);\n" +
            "    vec3 o1 = sampleTC(v_tc5.xy);\n" +
            "    vec3 o3 = sampleTC(v_tc6.xy);\n" +
            "    vec3 o2 = sampleTC(v_tc5.zw);\n" +
            "    vec3 o4 = sampleTC(v_tc6.zw);\n" +
            "    vec3 s1 = sampleTC(v_tc1.zw);\n" +
            "    vec3 s2 = sampleTC(v_tc2.zw);\n" +
            "    vec3 s3 = sampleTC(v_tc3.zw);\n" +
            "    vec3 s4 = sampleTC(v_tc4.zw);\n" +
            "    vec3 dt = vec3(1.0, 1.0, 1.0);\n" +
            "\n" +
            "    float ko1 = dot(abs(o1-c), dt);\n" +
            "    float ko2 = dot(abs(o2-c), dt);\n" +
            "    float ko3 = dot(abs(o3-c), dt);\n" +
            "    float ko4 = dot(abs(o4-c), dt);\n" +
            "\n" +
            "    float k1 = min(dot(abs(i1-i3), dt), max(ko1, ko3));\n" +
            "    float k2 = min(dot(abs(i2-i4), dt), max(ko2, ko4));\n" +
            "\n" +
            "    float w1 = k2; if (ko3 < ko1) w1 *= ko3/ko1;\n" +
            "    float w2 = k1; if (ko4 < ko2) w2 *= ko4/ko2;\n" +
            "    float w3 = k2; if (ko1 < ko3) w3 *= ko1/ko3;\n" +
            "    float w4 = k1; if (ko2 < ko4) w4 *= ko2/ko4;\n" +
            "\n" +
            "    c = (w1*o1 + w2*o2 + w3*o3 + w4*o4 + 0.001*c) / (w1+w2+w3+w4+0.001);\n" +
            "\n" +
            "    w1 = k*dot(abs(i1-c)+abs(i3-c), dt) / (0.125*dot(i1+i3, dt) + lum_add);\n" +
            "    w2 = k*dot(abs(i2-c)+abs(i4-c), dt) / (0.125*dot(i2+i4, dt) + lum_add);\n" +
            "    w3 = k*dot(abs(s1-c)+abs(s3-c), dt) / (0.125*dot(s1+s3, dt) + lum_add);\n" +
            "    w4 = k*dot(abs(s2-c)+abs(s4-c), dt) / (0.125*dot(s2+s4, dt) + lum_add);\n" +
            "\n" +
            "    w1 = clamp(w1 + mx, min_w, max_w);\n" +
            "    w2 = clamp(w2 + mx, min_w, max_w);\n" +
            "    w3 = clamp(w3 + mx, min_w, max_w);\n" +
            "    w4 = clamp(w4 + mx, min_w, max_w);\n" +
            "\n" +
            "    vec3 result = (w1*(i1+i3) + w2*(i2+i4) + w3*(s1+s3) + w4*(s2+s4) + c) / (2.0*(w1+w2+w3+w4) + 1.0);\n" +
            "\n" +
            "    vec2 pixel_no = v_tc0.xy / u_texelDelta;\n" +
            "    vec2 fp = fract(pixel_no);\n" +
            "    float delta = length(fp - vec2(0.5));\n" +
            "    float bright = dot(result, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    result = mix(1.1 * result, result * dotMask, 0.65);\n" +
            "    result = clamp(result, 0.0, 1.0);\n" +
            "\n" +
            "    gl_FragColor = vec4(result, 1.0);\n" +
            "}\n";

    // ─── TV appearance (仿电视机) GLSL helper library ─────────────────────────
    //
    // 共享给 FRAGMENT_TV 与 XBR/HQ4X+TV 组合滤镜的函数库：桶形弯曲、四角圆弧、
    // 暗角、扫描线。不含 uniform 声明（由各 shader 自己声明，避免重复）。

    /** TV 外观共享函数库（插入各 shader 的 main 之前）。 */
    private static final String TV_GLSL_HELPERS =
            "// ===== NesStation TV appearance helpers (curved glass / rounded corners / vignette / scanlines) =====\n" +
            "// 桶形弯曲 —— 模拟 CRT 弧面玻璃：屏幕边缘向外鼓\n" +
            "// ★ 全出血预缩放（除以 vec2(1.0298, 1.0727)）：弯曲后的采样坐标恰好\n" +
            "//   在屏幕四角落在纹理四角 (1,1)、边中点不超过边界 —— 画面铺满\n" +
            "//   整屏、无采样越界黑边（黑边会遮挡扫描线遮罩）。\n" +
            "vec2 nsCurve(vec2 tc) {\n" +
            "    vec2 c = tc * 2.0 - 1.0;\n" +
            "    c /= vec2(1.0298, 1.0727);\n" +
            "    vec2 off = abs(c.yx) / vec2(5.4, 3.6);\n" +
            "    c = c + c * off * off;\n" +
            "    return c * 0.5 + 0.5;\n" +
            "}\n" +
            "// 四角玻璃暗影 —— 圆角矩形 SDF：四角弧外仅轻微压暗（不再填黑，\n" +
            "// 大黑边会遮挡遮罩），玻璃圆角观感由暗影 + 暗角共同营造\n" +
            "// ★ 灰边收窄：SDF 内缩 0.085 → 0.035，边缘暗带约 6.5% → 3.5%\n" +
            "//   全幅宽度，与 NdsFilterPatterns.createTvCornerMask 同参数\n" +
            "float nsCornerMask(vec2 tc) {\n" +
            "    vec2 p = abs(tc * 2.0 - 1.0);\n" +
            "    vec2 corner = vec2(0.965, 0.955) - 0.035;\n" +
            "    float dist = length(max(p - corner, vec2(0.0))) - 0.035;\n" +
            "    return 1.0 - 0.40 * smoothstep(-0.008, 0.016, dist);\n" +
            "}\n" +
            "// 暗角 —— CRT 玻璃边缘自然压暗\n" +
            "// ★ 灰边收窄：旧实现 dot(p*0.72,p*0.72) 二次曲线从中心向外渐变，\n" +
            "//   整幅画面都被压暗；改为只在外缘 15%（半幅）内渐变到 32% 暗，\n" +
            "//   画面中心完全不受影响，与画布路径的窄带暗角观感一致\n" +
            "float nsVignette(vec2 tc) {\n" +
            "    vec2 p = abs(tc * 2.0 - 1.0);\n" +
            "    float m = max(p.x, p.y);\n" +
            "    return 1.0 - 0.32 * smoothstep(0.85, 1.0, m);\n" +
            "}\n" +
            "// 扫描线 —— 按源分辨率行数明暗相间；传入弯曲后坐标时扫描线随弧面弯曲\n" +
            "float nsScanlines(vec2 tc) {\n" +
            "    float rows = 1.0 / max(u_texelDelta.y, 0.0001);\n" +
            "    float f = fract(tc.y * rows);\n" +
            "    return 0.72 + 0.28 * exp(-6.0 * f);\n" +
            "}\n" +
            // 立体凸起：屏幕边缘内侧压暗模拟玻璃向内弯折的反光衰减
            "float nsBevel(vec2 tc) {\n" +
            "    vec2 e = min(tc, 1.0 - tc);\n" +
            "    return mix(0.75, 1.0, smoothstep(0.0, 0.02, min(e.x, e.y)));\n" +
            "}\n";

    /**
     * 仿电视机滤镜（mode 10）—— 四角圆弧 + 立体凸起弧面屏幕 + 扫描线 + 暗角 + 玻璃高光。
     * 顶点着色器用 VERTEX_CRT（passthrough，v_texcoord0）。
     */
    public static final String FRAGMENT_TV =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            TV_GLSL_HELPERS +
            "\n" +
            "void main() {\n" +
            "    float corner = nsCornerMask(v_texcoord0);\n" +
            "    // 桶形弯曲采样：屏幕外缘鼓出 —— 真正的弧面透视，而非平面拉伸。\n" +
            "    // nsCurve 内置全出血预缩放，弯曲后坐标恒在 [0,1] 内；clamp 仅作\n" +
            "    // 浮点误差防护，不再产生任何黑边。\n" +
            "    vec2 cuv = clamp(nsCurve(v_texcoord0), 0.0, 1.0);\n" +
            "    vec3 res = texture2D(sampler0, cuv).xyz;\n" +
            "    // 立体凸起：屏幕四周边缘内侧压暗（玻璃向内弯折的反光衰减）\n" +
            "    res *= nsBevel(v_texcoord0);\n" +
            "    // 扫描线随弧面弯曲（用弯曲后坐标）\n" +
            "    res *= nsScanlines(cuv);\n" +
            "    // 暗角 + 四角玻璃暗影（轻微压暗，无黑边）\n" +
            "    res *= nsVignette(v_texcoord0);\n" +
            "    res *= corner;\n" +
            "    // 玻璃高光：屏幕上部一道微弱的弧形反光，立体感的关键\n" +
            "    float gloss = 0.055 * smoothstep(0.42, 0.02, abs(v_texcoord0.y - 0.20));\n" +
            "    res += gloss;\n" +
            "    res = clamp(res, vec3(0.0), vec3(1.0));\n" +
            "    gl_FragColor = vec4(res, 1.0);\n" +
            "}\n";

    // ─── XBR / HQ4X 与扫描线 / 仿电视机的组合滤镜（运行时拼接，复用模板）─────
    //
    // 6 个组合滤镜不从模板整段复制（6×90 行漂移隐患），而是在类加载时用
    // Java 字符串拼接复用 FRAGMENT_2XBR / FRAGMENT_4XBR / FRAGMENT_HQ4X：
    //  - +Scanline：只在模板尾部输出前叠加扫描线；
    //  - +TV：在 main 开头把采样坐标换成桶形弯曲坐标（单 pass 真弯曲采样，
    //    放大算法在弯曲后的 texel 网格上运行），尾部叠加圆角/暗角/弯曲扫描线。

    /** XBR 模板尾部输出锚点（main 内唯一）。 */
    private static final String XBR_TAIL_ANCHOR = "    gl_FragColor.rgb = res;\n";

    /** HQ4X 模板尾部输出锚点（main 内唯一）。 */
    private static final String HQ4X_TAIL_ANCHOR = "    gl_FragColor = vec4(result, 1.0);\n";

    /** XBR main 入口锚点。 */
    private static final String XBR_MAIN_ANCHOR = "void main() {\n";

    /** HQ4X main 入口锚点。 */
    private static final String HQ4X_MAIN_ANCHOR = "void main()\n{\n";

    /** 拼接 +Scanline 变体：尾部输出前叠加扫描线（不弯曲，走直线）。 */
    private static String makeXbrScanline(String base) {
        String tail =
                "    res *= nsScanlines(v_tc0);\n" +
                "    res = clamp(res, vec3(0.0), vec3(1.0));\n";
        return injectXbrTail(base, tail);
    }

    /** 拼接 XBR+TV 变体：弯曲采样 + 圆角 + 暗角 + 随弧面弯曲的扫描线。 */
    private static String makeXbrTv(String base) {
        int idx = base.indexOf(XBR_MAIN_ANCHOR);
        if (idx < 0) return base;   // 模板变了 → 退化为原滤镜（防御性，不崩）
        String header = base.substring(0, idx);
        String body = base.substring(idx);
        // main 内部所有 v_tc0 引用改为弯曲后坐标 v_tc0c（varying 声明在 header，不受影响）
        body = body.replace("v_tc0", "v_tc0c");
        // main 开头计算弯曲坐标
        body = body.replace(XBR_MAIN_ANCHOR,
                XBR_MAIN_ANCHOR + "    vec2 v_tc0c = nsCurve(v_tc0);\n");
        // 尾部叠加 TV 外观（圆角/暗角用屏幕原始坐标，扫描线用弯曲坐标）
        String tail =
                "    res *= nsCornerMask(v_tc0);\n" +
                "    res *= nsVignette(v_tc0);\n" +
                "    res *= nsScanlines(v_tc0c);\n" +
                "    res = clamp(res, vec3(0.0), vec3(1.0));\n";
        body = body.replace(XBR_TAIL_ANCHOR, tail + XBR_TAIL_ANCHOR);
        return header + TV_GLSL_HELPERS + body;
    }

    /** 拼接 HQ4X+Scanline 变体。 */
    private static String makeHq4xScanline(String base) {
        String tail =
                "    result *= nsScanlines(v_tc0.xy);\n" +
                "    result = clamp(result, vec3(0.0), vec3(1.0));\n";
        return injectHq4xTail(base, tail);
    }

    /** 拼接 HQ4X+TV 变体：弯曲采样 + TV 外观。 */
    private static String makeHq4xTv(String base) {
        int idx = base.indexOf(HQ4X_MAIN_ANCHOR);
        if (idx < 0) return base;
        String header = base.substring(0, idx);
        String body = base.substring(idx);
        // HQ4X fragment 没有 u_texelDelta 声明（vertex 有）—— helpers 需要它
        body = body.replace(HQ4X_MAIN_ANCHOR,
                HQ4X_MAIN_ANCHOR + "    vec2 hqC = nsCurve(v_tc0.xy);\n");
        body = body.replace("sampleTC(v_tc0.xy)", "sampleTC(hqC)");
        String tail =
                "    result *= nsCornerMask(v_tc0.xy);\n" +
                "    result *= nsVignette(v_tc0.xy);\n" +
                "    result *= nsScanlines(hqC);\n" +
                "    result = clamp(result, vec3(0.0), vec3(1.0));\n";
        body = body.replace(HQ4X_TAIL_ANCHOR, tail + HQ4X_TAIL_ANCHOR);
        // header 里补 u_texelDelta uniform（TV helpers 的 nsScanlines 用）
        String uniforms = "uniform mediump vec2 u_texelDelta;\n";
        return header + uniforms + TV_GLSL_HELPERS + body;
    }

    /** XBR 系尾部注入（helpers 必须插在 main 之前 —— GLSL 先声明后使用）。 */
    private static String injectXbrTail(String base, String tail) {
        if (!base.contains(XBR_TAIL_ANCHOR)) return base;
        String injected = base.replace(XBR_TAIL_ANCHOR, tail + XBR_TAIL_ANCHOR);
        int m = injected.indexOf(XBR_MAIN_ANCHOR);
        if (m < 0) return injected;
        return injected.substring(0, m) + TV_GLSL_HELPERS + injected.substring(m);
    }

    /** HQ4X 系尾部注入（补 u_texelDelta 声明，校验锚点）。 */
    private static String injectHq4xTail(String base, String tail) {
        if (!base.contains(HQ4X_TAIL_ANCHOR)) return base;
        String injected = base.replace(HQ4X_TAIL_ANCHOR, tail + HQ4X_TAIL_ANCHOR);
        // helpers 的 nsScanlines 需要 u_texelDelta —— HQ4X fragment 未声明，补在 precision 之后
        int p = injected.indexOf("varying vec4 v_tc0;");
        if (p >= 0) {
            injected = injected.substring(0, p)
                    + "uniform mediump vec2 u_texelDelta;\n"
                    + injected.substring(p);
        }
        // helpers 函数库插在 main 之前
        int m = injected.indexOf(HQ4X_MAIN_ANCHOR);
        if (m < 0) return injected;
        return injected.substring(0, m) + TV_GLSL_HELPERS + injected.substring(m);
    }

    /** 2xBR + Scanline（mode 11）。 */
    public static final String FRAGMENT_2XBR_SCANLINE = makeXbrScanline(FRAGMENT_2XBR);

    /** 4xBR + Scanline（mode 12）。 */
    public static final String FRAGMENT_4XBR_SCANLINE = makeXbrScanline(FRAGMENT_4XBR);

    /** HQ4x + Scanline（mode 13）。 */
    public static final String FRAGMENT_HQ4X_SCANLINE = makeHq4xScanline(FRAGMENT_HQ4X);

    /** 2xBR + TV（mode 14）—— 单 pass 真弯曲采样。 */
    public static final String FRAGMENT_2XBR_TV = makeXbrTv(FRAGMENT_2XBR);

    /** 4xBR + TV（mode 15）。 */
    public static final String FRAGMENT_4XBR_TV = makeXbrTv(FRAGMENT_4XBR);

    /** HQ4x + TV（mode 16）。 */
    public static final String FRAGMENT_HQ4X_TV = makeHq4xTv(FRAGMENT_HQ4X);

    // ─── Public API ──────────────────────────────────────────────────────────

    public static String[] getShader(int mode) {
        return new String[] { getVertexShader(mode), getFragmentShader(mode) };
    }

    public static String getVertexShader(int mode) {
        switch (mode) {
            case MODE_SCANLINE: return VERTEX_SCANLINE;
            case MODE_CRT:      return VERTEX_CRT;
            case MODE_DOT:      return VERTEX_DOT;
            case MODE_TV:       return VERTEX_CRT;   // passthrough，varying v_texcoord0
            case MODE_2XBR:
            case MODE_2XBR_DOT:
            case MODE_2XBR_SCANLINE:
            case MODE_2XBR_TV:  return VERTEX_2XBR;
            case MODE_4XBR:
            case MODE_4XBR_DOT:
            case MODE_4XBR_SCANLINE:
            case MODE_4XBR_TV:  return VERTEX_4XBR;
            case MODE_HQ4X:
            case MODE_HQ4X_DOT:
            case MODE_HQ4X_SCANLINE:
            case MODE_HQ4X_TV:  return VERTEX_HQ4X;
            case MODE_NONE:
            default:            return VERTEX_SHADER;
        }
    }

    public static String getFragmentShader(int mode) {
        switch (mode) {
            case MODE_SCANLINE:       return FRAGMENT_SCANLINE;
            case MODE_CRT:            return FRAGMENT_CRT;
            case MODE_DOT:            return FRAGMENT_DOT;
            case MODE_TV:             return FRAGMENT_TV;
            case MODE_2XBR:           return FRAGMENT_2XBR;
            case MODE_4XBR:           return FRAGMENT_4XBR;
            case MODE_2XBR_DOT:       return FRAGMENT_2XBR_DOT;
            case MODE_4XBR_DOT:       return FRAGMENT_4XBR_DOT;
            case MODE_HQ4X:           return FRAGMENT_HQ4X;
            case MODE_HQ4X_DOT:       return FRAGMENT_HQ4X_DOT;
            case MODE_2XBR_SCANLINE:  return FRAGMENT_2XBR_SCANLINE;
            case MODE_4XBR_SCANLINE:  return FRAGMENT_4XBR_SCANLINE;
            case MODE_HQ4X_SCANLINE:  return FRAGMENT_HQ4X_SCANLINE;
            case MODE_2XBR_TV:        return FRAGMENT_2XBR_TV;
            case MODE_4XBR_TV:        return FRAGMENT_4XBR_TV;
            case MODE_HQ4X_TV:        return FRAGMENT_HQ4X_TV;
            case MODE_NONE:
            default:                  return FRAGMENT_NONE;
        }
    }

    public static boolean isCustomVertexShader(int mode) {
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

    public static boolean isMaskMode(int mode) {
        // TV（mode 10）是纯遮罩类：GL 端弯曲采样，CPU 端叠加外观
        return mode == MODE_SCANLINE || mode == MODE_CRT || mode == MODE_DOT ||
               mode == MODE_TV;
    }

    public static boolean usesNearestFiltering(int mode) {
        return isPixelProcessingMode(mode);
    }

    private J2meFilterShaders() {}
}
