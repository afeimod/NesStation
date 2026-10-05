/*
   4xBR (xBR Level 3) — stronger pixel-art upscale post-processing shader

   Ported to the MMJ / Citra post-processing format from Hyllian's xBR-lv3
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
   - Level-3 detects longer edges than level-2 (xBR.glsl): rounder, smoother
     curves. Exposed as the MMJ counterpart of the global "4XBR" filter.
   - mat4x3/transpose() of the original replaced by explicit dot products
     (GLSL ES 1.00 has no mat4x3 constructor from vec3 rows).
   - corner_type = 3 variant (canonical default).
*/

//! mag_filter = nearest
//! min_filter = nearest

const float XBR_Y_WEIGHT        = 48.0;
const float XBR_EQ_THRESHOLD    = 10.0;
const float XBR_EQ_THRESHOLD2   = 2.0;
const float XBR_LV2_COEFFICIENT = 2.0;

const float3 Y = float3(0.299, 0.587, 0.114);
const float4 delta = float4(0.4, 0.4, 0.4, 0.4);

float4 df(float4 A, float4 B)
{
    return float4(abs(A - B));
}

float c_df(float3 c1, float3 c2)
{
    float3 d = abs(c1 - c2);
    return d.r + d.g + d.b;
}

float4 eq(float4 A, float4 B)
{
    return step(df(A, B), float4(XBR_EQ_THRESHOLD));
}

float4 eq2(float4 A, float4 B)
{
    return step(df(A, B), float4(XBR_EQ_THRESHOLD2));
}

// 精确不等（notEqual）→ 0/1 浮点（对应原 bvec4 逻辑的浮点化）。
float4 diff4(float4 A, float4 B)
{
    return float4(notEqual(A, B));
}

// 阈值不等（not eq）→ 0/1 浮点。
float4 neq4(float4 A, float4 B)
{
    return float4(1.0, 1.0, 1.0, 1.0) - eq(A, B);
}

float4 wd(float4 a, float4 b, float4 c, float4 d, float4 e, float4 f, float4 g, float4 h)
{
    return (df(a, b) + df(a, c) + df(d, e) + df(d, f) + 4.0 * df(g, h));
}

void main()
{
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

    float4 b = float4(dot(B, Y), dot(D, Y), dot(H, Y), dot(F, Y)) * XBR_Y_WEIGHT;
    float4 c = float4(dot(C, Y), dot(A, Y), dot(G, Y), dot(I, Y)) * XBR_Y_WEIGHT;
    float4 e = float4(dot(E, Y)) * XBR_Y_WEIGHT;
    float4 d = b.yzwx;
    float4 f = b.wxyz;
    float4 g = c.zwxy;
    float4 h = b.zwxy;
    float4 i = c.wxyz;

    float4 i4 = float4(dot(I4, Y), dot(C1, Y), dot(A0, Y), dot(G5, Y)) * XBR_Y_WEIGHT;
    float4 i5 = float4(dot(I5, Y), dot(C4, Y), dot(A1, Y), dot(G0, Y)) * XBR_Y_WEIGHT;
    float4 h5 = float4(dot(H5, Y), dot(F4, Y), dot(B1, Y), dot(D0, Y)) * XBR_Y_WEIGHT;
    float4 f4 = h5.yzwx;

    float4 c1 = i4.yzwx;
    float4 g0 = i5.wxyz;
    float4 b1 = h5.zwxy;
    float4 d0 = h5.wxyz;

    float4 Ao = float4( 1.0, -1.0, -1.0,  1.0 );
    float4 Bo = float4( 1.0,  1.0, -1.0, -1.0 );
    float4 Co = float4( 1.5,  0.5, -0.5,  0.5 );
    float4 Ax = float4( 1.0, -1.0, -1.0,  1.0 );
    float4 Bx = float4( 0.5,  2.0, -0.5, -2.0 );
    float4 Cx = float4( 1.0,  1.0, -0.5,  0.0 );
    float4 Ay = float4( 1.0, -1.0, -1.0,  1.0 );
    float4 By = float4( 2.0,  0.5, -2.0, -0.5 );
    float4 Cy = float4( 2.0,  0.0, -1.0,  0.5 );

    float4 Az = float4( 6.0, -2.0, -6.0,  2.0 );
    float4 Bz = float4( 2.0,  6.0, -2.0, -6.0 );
    float4 Cz = float4( 5.0,  3.0, -3.0, -1.0 );
    float4 Aw = float4( 2.0, -6.0, -2.0,  6.0 );
    float4 Bw = float4( 6.0,  2.0, -6.0, -2.0 );
    float4 Cw = float4( 5.0, -1.0, -3.0,  3.0 );

    float4 fx       = (Ao * fp.y + Bo * fp.x);
    float4 fx_left  = (Ax * fp.y + Bx * fp.x);
    float4 fx_up    = (Ay * fp.y + By * fp.x);
    float4 fx3_left = (Az * fp.y + Bz * fp.x);
    float4 fx3_up   = (Aw * fp.y + Bw * fp.x);

    // corner_type = 3 (canonical default)
    float4 irlv1 = (diff4(e, f) * diff4(e, h)) *
                   (neq4(f, b) * neq4(f, c) + neq4(h, d) * neq4(h, g) +
                    eq(e, i) * (neq4(f, f4) * neq4(f, i4) + neq4(h, h5) * neq4(h, i5)) +
                    eq(e, g) + eq(e, c));

    float4 irlv2l = diff4(e, g) * diff4(d, g);
    float4 irlv2u = diff4(e, c) * diff4(b, c);
    float4 irlv3l = eq2(g, g0) * (float4(1.0) - eq2(d0, g0));
    float4 irlv3u = eq2(c, c1) * (float4(1.0) - eq2(b1, c1));

    float4 fx45 = clamp((fx       + delta - Co) / (2.0 * delta), 0.0, 1.0);
    float4 fx30 = clamp((fx_left  + delta - Cx) / (2.0 * delta), 0.0, 1.0);
    float4 fx60 = clamp((fx_up    + delta - Cy) / (2.0 * delta), 0.0, 1.0);
    float4 fx15 = clamp((fx3_left + delta - Cz) / (2.0 * delta), 0.0, 1.0);
    float4 fx75 = clamp((fx3_up   + delta - Cw) / (2.0 * delta), 0.0, 1.0);

    float4 wd1 = wd(e, c, g, i, h5, f4, h, f);
    float4 wd2 = wd(h, d, i5, f, i4, b, e, i);

    float4 edr   = step(wd1, wd2) * step(float4(0.5), irlv1);
    float4 edr_l = step(XBR_LV2_COEFFICIENT * df(f, g), df(h, c)) * irlv2l * edr;
    float4 edr_u = step(XBR_LV2_COEFFICIENT * df(h, c), df(f, g)) * irlv2u * edr;
    float4 edr3_l = irlv3l * edr;
    float4 edr3_u = irlv3u * edr;

    float4 nc45 = edr    * fx45;
    float4 nc30 = edr    * edr_l * fx30;
    float4 nc60 = edr    * edr_u * fx60;
    float4 nc15 = edr    * edr_l * edr3_l * fx15;
    float4 nc75 = edr    * edr_u * edr3_u * fx75;

    float4 px = step(df(e, f), df(e, h));

    float4 nc = float4(
        (nc75.x > 0.0 || nc15.x > 0.0 || nc30.x > 0.0 || nc60.x > 0.0 || nc45.x > 0.0) ? 1.0 : 0.0,
        (nc75.y > 0.0 || nc15.y > 0.0 || nc30.y > 0.0 || nc60.y > 0.0 || nc45.y > 0.0) ? 1.0 : 0.0,
        (nc75.z > 0.0 || nc15.z > 0.0 || nc30.z > 0.0 || nc60.z > 0.0 || nc45.z > 0.0) ? 1.0 : 0.0,
        (nc75.w > 0.0 || nc15.w > 0.0 || nc30.w > 0.0 || nc60.w > 0.0 || nc45.w > 0.0) ? 1.0 : 0.0);

    float4 final45 = nc45;
    float4 final30 = nc30;
    float4 final60 = nc60;
    float4 final15 = nc15;
    float4 final75 = nc75;

    float4 maximo = max(max(max(final15, final75), max(final30, final60)), final45);

    float3 pix1 = E;
    float blend1 = 0.0;
    if      (nc.x > 0.0) { pix1 = (px.x > 0.5) ? F : H; blend1 = maximo.x; }
    else if (nc.y > 0.0) { pix1 = (px.y > 0.5) ? B : F; blend1 = maximo.y; }
    else if (nc.z > 0.0) { pix1 = (px.z > 0.5) ? D : B; blend1 = maximo.z; }
    else if (nc.w > 0.0) { pix1 = (px.w > 0.5) ? H : D; blend1 = maximo.w; }

    float3 pix2 = E;
    float blend2 = 0.0;
    if      (nc.w > 0.0) { pix2 = (px.w > 0.5) ? H : D; blend2 = maximo.w; }
    else if (nc.z > 0.0) { pix2 = (px.z > 0.5) ? D : B; blend2 = maximo.z; }
    else if (nc.y > 0.0) { pix2 = (px.y > 0.5) ? B : F; blend2 = maximo.y; }
    else if (nc.x > 0.0) { pix2 = (px.x > 0.5) ? F : H; blend2 = maximo.x; }

    float3 res1 = mix(E, pix1, blend1);
    float3 res2 = mix(E, pix2, blend2);
    float3 res = mix(res1, res2, step(c_df(E, res1), c_df(E, res2)));

    SetOutput(float4(res, 1.0));
}
