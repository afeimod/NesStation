/*
   xBR (Level 2) — pixel-art upscale post-processing shader

   Ported to the MMJ / Citra post-processing format from Hyllian's xBR-lv2
   (libretro glsl-shaders). Original copyright:

   Copyright (C) 2011-2015 Hyllian - sergiogdb@gmail.com

   Permission is hereby granted, free of charge, to any person obtaining a copy
   of this software and associated documentation files (the "Software"), to deal
   in the Software without restriction, including without limitation the rights
   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
   copies of the Software, and to permit persons to whom the Software is
   furnished to do so, subject to the following conditions:

   The above copyright notice and this permission notice shall be included in
   all copies or substantial portions of the Software.

   NesStation adaptation notes:
   - Runs at output (window) resolution sampling the emulated screen texture,
     exactly like the other MMJ shaders (SEDI / spline36 / FXAA ...), so it
     works at any window scale.
   - Neighbor varyings t1..t7 of the original two-pass layout are computed
     in-fragment from GetCoordinates() + GetInvResolution().
   - CORNER_C + SMOOTH_TIPS variants, XBR_SCALE = 3 (canonical preset).
*/

//! mag_filter = nearest
//! min_filter = nearest

const float XBR_Y_WEIGHT       = 48.0;
const float XBR_EQ_THRESHOLD   = 15.0;
const float XBR_LV2_COEFFICIENT = 2.0;
const float XBR_SCALE          = 3.0;

const float3 rgbw = float3(14.352, 28.176, 5.472);   // = 48 * (0.299, 0.587, 0.114)

float4 delta   = float4(1.0 / XBR_SCALE, 1.0 / XBR_SCALE, 1.0 / XBR_SCALE, 1.0 / XBR_SCALE);
float4 delta_l = float4(0.5 / XBR_SCALE, 1.0 / XBR_SCALE, 0.5 / XBR_SCALE, 1.0 / XBR_SCALE);
float4 delta_u = delta_l.yxwz;

const float4 Ao = float4( 1.0, -1.0, -1.0,  1.0 );
const float4 Bo = float4( 1.0,  1.0, -1.0, -1.0 );
const float4 Co = float4( 1.5,  0.5, -0.5,  0.5 );
const float4 Ax = float4( 1.0, -1.0, -1.0,  1.0 );
const float4 Bx = float4( 0.5,  2.0, -0.5, -2.0 );
const float4 Cx = float4( 1.0,  1.0, -0.5,  0.0 );
const float4 Ay = float4( 1.0, -1.0, -1.0,  1.0 );
const float4 By = float4( 2.0,  0.5, -2.0, -0.5 );
const float4 Cy = float4( 2.0,  0.0, -1.0,  0.5 );
const float4 Ci = float4(0.25, 0.25, 0.25, 0.25);

// Difference between vector components.
float4 df(float4 A, float4 B)
{
    return float4(abs(A - B));
}

// Compare two vectors and return their components are different.
float4 diff(float4 A, float4 B)
{
    return float4(notEqual(A, B));
}

// Determine if two vector components are equal based on a threshold.
float4 eq(float4 A, float4 B)
{
    return (step(df(A, B), float4(XBR_EQ_THRESHOLD)));
}

// Determine if two vector components are NOT equal based on a threshold.
float4 neq(float4 A, float4 B)
{
    return (float4(1.0, 1.0, 1.0, 1.0) - eq(A, B));
}

float c_df(float3 c1, float3 c2)
{
    float3 d = abs(c1 - c2);
    return d.r + d.g + d.b;
}

float4 wd(float4 a, float4 b, float4 c, float4 d, float4 e, float4 f, float4 g, float4 h)
{
    return (df(a, b) + df(a, c) + df(d, e) + df(d, f) + 4.0 * df(g, h));
}

void main()
{
    float4 edri, edr, edr_l, edr_u, px;   // px = pixel, edr = edge detection rule
    float4 irlv0, irlv1, irlv2l, irlv2u;
    float4 fx, fx_l, fx_u;                // inequations of straight lines.

    float2 dc = GetCoordinates();
    float2 ps = GetInvResolution();
    float fp_x = fract(dc.x * GetResolution().x);
    float fp_y = fract(dc.y * GetResolution().y);
    float2 fp = float2(fp_x, fp_y);

    float dx = ps.x;
    float dy = ps.y;

    float4 t1 = dc.xxxy + float4( -dx, 0.0,  dx, -2.0 * dy);   // A1 B1 C1
    float4 t2 = dc.xxxy + float4( -dx, 0.0,  dx,     -dy);     //  A  B  C
    float4 t3 = dc.xxxy + float4( -dx, 0.0,  dx,      0.0);    //  D  E  F
    float4 t4 = dc.xxxy + float4( -dx, 0.0,  dx,      dy);     //  G  H  I
    float4 t5 = dc.xxxy + float4( -dx, 0.0,  dx,  2.0 * dy);   // G5 H5 I5
    float4 t6 = dc.xyyy + float4(-2.0 * dx, -dy, 0.0,  dy);    // A0 D0 G0
    float4 t7 = dc.xyyy + float4( 2.0 * dx, -dy, 0.0,  dy);    // C4 F4 I4

    float3 A1 = SampleLocation(t1.xw).xyz;
    float3 B1 = SampleLocation(t1.yw).xyz;
    float3 C1 = SampleLocation(t1.zw).xyz;
    float3 A  = SampleLocation(t2.xw).xyz;
    float3 B  = SampleLocation(t2.yw).xyz;
    float3 C  = SampleLocation(t2.zw).xyz;
    float3 D  = SampleLocation(t3.xw).xyz;
    float3 E  = SampleLocation(t3.yw).xyz;
    float3 F  = SampleLocation(t3.zw).xyz;
    float3 G  = SampleLocation(t4.xw).xyz;
    float3 H  = SampleLocation(t4.yw).xyz;
    float3 I  = SampleLocation(t4.zw).xyz;
    float3 G5 = SampleLocation(t5.xw).xyz;
    float3 H5 = SampleLocation(t5.yw).xyz;
    float3 I5 = SampleLocation(t5.zw).xyz;
    float3 A0 = SampleLocation(t6.xy).xyz;
    float3 D0 = SampleLocation(t6.xz).xyz;
    float3 G0 = SampleLocation(t6.xw).xyz;
    float3 C4 = SampleLocation(t7.xy).xyz;
    float3 F4 = SampleLocation(t7.xz).xyz;
    float3 I4 = SampleLocation(t7.xw).xyz;

    float4 b = float4(dot(B, rgbw), dot(D, rgbw), dot(H, rgbw), dot(F, rgbw));
    float4 c = float4(dot(C, rgbw), dot(A, rgbw), dot(G, rgbw), dot(I, rgbw));
    float4 d = b.yzwx;
    float4 e = float4(dot(E, rgbw));
    float4 f = b.wxyz;
    float4 g = c.zwxy;
    float4 h = b.zwxy;
    float4 i = c.wxyz;

    float4 i4 = float4(dot(I4, rgbw), dot(C1, rgbw), dot(A0, rgbw), dot(G5, rgbw));
    float4 i5 = float4(dot(I5, rgbw), dot(C4, rgbw), dot(A1, rgbw), dot(G0, rgbw));
    float4 h5 = float4(dot(H5, rgbw), dot(F4, rgbw), dot(B1, rgbw), dot(D0, rgbw));
    float4 f4 = h5.yzwx;

    // These inequations define the line below which interpolation occurs.
    fx   = (Ao * fp.y + Bo * fp.x);
    fx_l = (Ax * fp.y + Bx * fp.x);
    fx_u = (Ay * fp.y + By * fp.x);

    irlv0 = diff(e, f) * diff(e, h);

    // CORNER_C variant
    irlv1 = (irlv0 * (neq(f, b) * neq(f, c) + neq(h, d) * neq(h, g) +
                      eq(e, i) * (neq(f, f4) * neq(f, i4) + neq(h, h5) * neq(h, i5)) +
                      eq(e, g) + eq(e, c)));

    irlv2l = diff(e, g) * diff(d, g);
    irlv2u = diff(e, c) * diff(b, c);

    float4 fx45i = clamp((fx   + delta   - Co - Ci) / (2.0 * delta  ), 0.0, 1.0);
    float4 fx45  = clamp((fx   + delta   - Co     ) / (2.0 * delta  ), 0.0, 1.0);
    float4 fx30  = clamp((fx_l + delta_l - Cx     ) / (2.0 * delta_l), 0.0, 1.0);
    float4 fx60  = clamp((fx_u + delta_u - Cy     ) / (2.0 * delta_u), 0.0, 1.0);

    float4 wd1 = wd(e, c, g, i, h5, f4, h, f);
    float4 wd2 = wd(h, d, i5, f, i4, b, e, i);

    edri  = step(wd1, wd2) * irlv0;
    edr   = step(wd1 + float4(0.1, 0.1, 0.1, 0.1), wd2) * step(float4(0.5, 0.5, 0.5, 0.5), irlv1);
    edr_l = step(XBR_LV2_COEFFICIENT * df(f, g), df(h, c)) * irlv2l * edr;
    edr_u = step(XBR_LV2_COEFFICIENT * df(h, c), df(f, g)) * irlv2u * edr;

    fx45  = edr   * fx45;
    fx30  = edr_l * fx30;
    fx60  = edr_u * fx60;
    fx45i = edri  * fx45i;

    px = step(df(e, f), df(e, h));

    // SMOOTH_TIPS variant
    float4 maximos = max(max(fx30, fx60), max(fx45, fx45i));

    float3 res1 = E;
    res1 = mix(res1, mix(H, F, px.x), maximos.x);
    res1 = mix(res1, mix(B, D, px.z), maximos.z);

    float3 res2 = E;
    res2 = mix(res2, mix(F, B, px.y), maximos.y);
    res2 = mix(res2, mix(D, H, px.w), maximos.w);

    float3 res = mix(res1, res2, step(c_df(E, res1), c_df(E, res2)));

    SetOutput(float4(res, 1.0));
}
