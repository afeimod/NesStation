/*
   HQ4X — pixel-art upscale post-processing shader (compact HQ-family port, 4x)

   NesStation adaptation notes:
   - Backs the global "HQ4X" filter choice (hq4x / hq4x_dot / hq4x_scanline /
     hq4x_tv map here through mmjShaderForGlobalFilter).
   - Same analytic kernel as HQ2X.glsl (see its notes) but tuned for a 4x
     output per source pixel: the sub-pixel offset spans -2.0 .. +2.0 so the
     directional ramps saturate earlier and the four centre-most fragments
     stay nearly pure centre color (sharp core), while outer fragments lean
     further towards blendable neighbours (smooth staircase) — the visual
     signature of 4x hqx upscaling.
   - Corner weights use a squared falloff so only the true corner fragments
     take meaningful diagonal color (hqx corner rule).

   Based on the HQ4X algorithm derived from HQ2X by Maxim Stepin (2003,
   public domain).
*/

//! mag_filter = nearest
//! min_filter = nearest

const float HQ_SCALE  = 4.0;                 // 4x output per source pixel
const float3 HQ_YUV_W = float3(0.299, 0.587, 0.114);
const float HQ_THRESHOLD = 30.0 / 255.0;     // hqx-style same-color gate

float hq_luma(float3 c)
{
    return dot(c, HQ_YUV_W);
}

float hq_diff(float3 a, float3 b)
{
    float3 dc3 = abs(a - b);
    float chroma = dc3.r + dc3.g + dc3.b;
    float luma = abs(hq_luma(a) - hq_luma(b));
    return chroma * 0.25 + luma * 2.0;
}

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

    // Sub-pixel offset inside the source pixel, scaled to the 4x output
    // block: -2.0 .. +2.0.
    float2 op = (fp - 0.5) * HQ_SCALE;

    float sB = hq_same(E, B);
    float sD = hq_same(E, D);
    float sF = hq_same(E, F);
    float sH = hq_same(E, H);
    float sA = hq_same(E, A);
    float sC = hq_same(E, C);
    float sG = hq_same(E, G);
    float sI = hq_same(E, I);

    // Orthogonal ramps: saturate at the outer half of the block
    // (|op| >= 1.0 gets full neighbour weight).
    float wB = sB * clamp(-op.y, 0.0, 1.0);
    float wD = sD * clamp(-op.x, 0.0, 1.0);
    float wF = sF * clamp( op.x, 0.0, 1.0);
    float wH = sH * clamp( op.y, 0.0, 1.0);

    // Corner rule: squared product keeps diagonals to the corner-most
    // fragments only (visual match of hqx 4x corner behaviour).
    float2 q = clamp(op * 0.5 + 0.5, 0.0, 1.0) - 0.5;   // -0.5..0.5
    float2 qa = float2(-q.x, -q.y);
    float wA = sA * clamp(4.0 * qa.x * qa.y, 0.0, 1.0);
    float wC = sC * clamp(4.0 *  q.x * qa.y, 0.0, 1.0);
    float wG = sG * clamp(4.0 * qa.x *  q.y, 0.0, 1.0);
    float wI = sI * clamp(4.0 *  q.x *  q.y, 0.0, 1.0);

    // Centre core weight: boosted so the 2x2 centre of the 4x4 block
    // stays essentially pure centre color (sharp flat areas).
    float wE = 2.0;

    float sum = wE + wB + wD + wF + wH + wA + wC + wG + wI;
    float3 res = (E  * wE +
                  B  * wB + D  * wD + F  * wF + H  * wH +
                  A  * wA + C  * wC + G  * wG + I  * wI) / sum;

    SetOutput(float4(res, 1.0));
}
