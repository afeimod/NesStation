/*
   HQ2X — pixel-art upscale post-processing shader (compact HQ-family port)

   NesStation adaptation notes:
   - Ported to the MMJ / Citra post-processing format to back the global
     "HQ2X" filter choice (hq2x / hq2x_dot / hq2x_scanline / hq2x_tv map
     here through mmjShaderForGlobalFilter).
   - Classic hqx.c uses a 256-entry rule table + several fixed blend ratios
     (3:1, 2:1:1, corner 1:1). This port keeps the same visual signature —
     YUV-weighted same-color gating, edge-aware blending, flat areas stay
     perfectly sharp — with a compact analytic kernel instead of the giant
     branch table: each output fragment falls inside one source pixel
     quadrant; orthogonal / diagonal neighbours are blended in with weights
     that ramp linearly as the fragment approaches them, gated by the hqx
     same-color test. Result: hard edges stay hard, stairs along diagonal
     edges get the characteristic hqx 2:1:1-style smoothing.
   - Runs at output (window) resolution sampling the emulated screen
     texture (same as the other MMJ shaders), works at any window scale.

   Based on the HQ2X algorithm by Maxim Stepin (2003, public domain).
*/

//! mag_filter = nearest
//! min_filter = nearest

const float HQ_SCALE  = 2.0;                 // 2x output per source pixel
const float3 HQ_YUV_W = float3(0.299, 0.587, 0.114);
const float HQ_THRESHOLD = 30.0 / 255.0;     // hqx-style same-color gate

float hq_luma(float3 c)
{
    return dot(c, HQ_YUV_W);
}

// hqx-style perceptual difference: chroma distance + luma distance.
// Below HQ_THRESHOLD the two colors are considered "the same"
// (blendable); above it they belong to different sides of an edge
// (never blended -> edge stays sharp).
float hq_diff(float3 a, float3 b)
{
    float3 dc3 = abs(a - b);
    float chroma = dc3.r + dc3.g + dc3.b;
    float luma = abs(hq_luma(a) - hq_luma(b));
    return chroma * 0.25 + luma * 2.0;
}

// Soft same-color gate (0 = different, 1 = same, smooth transition).
float hq_same(float3 a, float3 b)
{
    return 1.0 - smoothstep(HQ_THRESHOLD * 0.5, HQ_THRESHOLD, hq_diff(a, b));
}

void main()
{
    float2 dc = GetCoordinates();
    float2 ps = GetInvResolution();
    float2 fp = fract(dc * GetResolution());

    // 3x3 neighbourhood (E = current source pixel centre).
    float3 B = SampleLocation(dc + float2(      0.0, -ps.y)).rgb;   // north
    float3 D = SampleLocation(dc + float2(-ps.x,  0.0)).rgb;        // west
    float3 E = SampleLocation(dc).rgb;                              // centre
    float3 F = SampleLocation(dc + float2( ps.x,  0.0)).rgb;        // east
    float3 H = SampleLocation(dc + float2(      0.0,  ps.y)).rgb;   // south
    float3 A = SampleLocation(dc + float2(-ps.x, -ps.y)).rgb;       // north-west
    float3 C = SampleLocation(dc + float2( ps.x, -ps.y)).rgb;       // north-east
    float3 G = SampleLocation(dc + float2(-ps.x,  ps.y)).rgb;       // south-west
    float3 I = SampleLocation(dc + float2( ps.x,  ps.y)).rgb;       // south-east

    // Sub-pixel offset inside the source pixel, scaled to the output
    // quadrant: -1.0 .. +1.0 for a 2x upscale.
    float2 op = (fp - 0.5) * HQ_SCALE;

    // Same-color gates against the centre.
    float sB = hq_same(E, B);
    float sD = hq_same(E, D);
    float sF = hq_same(E, F);
    float sH = hq_same(E, H);
    float sA = hq_same(E, A);
    float sC = hq_same(E, C);
    float sG = hq_same(E, G);
    float sI = hq_same(E, I);

    // Directional ramp weights: the closer this fragment sits towards a
    // blendable neighbour, the more of that neighbour is mixed in.
    // (classic hqx 3:1 / 2:1:1 ratios emerge from normalising these ramps)
    float wB = sB * clamp(-op.y,            0.0, 1.0);
    float wD = sD * clamp(-op.x,            0.0, 1.0);
    float wF = sF * clamp( op.x,            0.0, 1.0);
    float wH = sH * clamp( op.y,            0.0, 1.0);
    float wA = sA * clamp(-op.x * -op.y * 2.0, 0.0, 1.0);
    float wC = sC * clamp( op.x * -op.y * 2.0, 0.0, 1.0);
    float wG = sG * clamp(-op.x *  op.y * 2.0, 0.0, 1.0);
    float wI = sI * clamp( op.x *  op.y * 2.0, 0.0, 1.0);

    // Centre always carries the base weight -> never a zero denominator,
    // flat areas (all neighbours same color) are bit-exact pass-through.
    float wE = 1.0;

    float sum = wE + wB + wD + wF + wH + wA + wC + wG + wI;
    float3 res = (E  * wE +
                  B  * wB + D  * wD + F  * wF + H  * wH +
                  A  * wA + C  * wC + G  * wG + I  * wI) / sum;

    SetOutput(float4(res, 1.0));
}
