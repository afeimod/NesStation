/*
 *  Copyright 2020 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.mascotcapsule.micro3d.v3;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

class Utils {

        static final float TO_FLOAT = 2.4414062E-04f;
        static final float TO_RADIANS = (float) (Math.PI / 2048.0);
        static final float[] IDENTITY_AFFINE = {
                        // 0     1     2     3
                        // 0     4     8    12
                        1.0f, 0.0f, 0.0f, 0.0f,
                        // 4     5     6     7
                        // 1     5     9    13
                        0.0f, 1.0f, 0.0f, 0.0f,
                        // 8     9    10    11
                        // 2     6    10    14
                        0.0f, 0.0f, 1.0f, 0.0f
        };

        static void parallelScale(float[] pm, int x, int y, FigureLayout layout, float vw, float vh) {
                float w = vw * (4096.0f / layout.scaleX);
                float h = vh * (4096.0f / layout.scaleY);

                float sx = 2.0f / w;
                float sy = 2.0f / h;
                float sz = 1.0f / 65536.0f;
                float tx = 2.0f * (layout.centerX + x) / vw - 1.0f;
                float ty = 2.0f * (layout.centerY + y) / vh - 1.0f;
                float tz = 0.0f;

                pm[ 0] =   sx; pm[ 4] = 0.0f; pm[ 8] = 0.0f; pm[12] =   tx;
                pm[ 1] = 0.0f; pm[ 5] =   sy; pm[ 9] = 0.0f; pm[13] =   ty;
                pm[ 2] = 0.0f; pm[ 6] = 0.0f; pm[10] =   sz; pm[14] =   tz;
                pm[ 3] = 0.0f; pm[ 7] = 0.0f; pm[11] = 0.0f; pm[15] = 1.0f;
        }

        static void parallelWH(float[] pm, int x, int y, FigureLayout layout, float vw, float vh) {
                float w = layout.parallelWidth == 0 ? 400.0f * 4.0f : layout.parallelWidth;
                float h = layout.parallelHeight == 0 ? w * (vh / vw) : layout.parallelHeight;

                float sx = 2.0f / w;
                float sy = 2.0f / h;
                float sz = 1.0f / 65536.0f;
                float tx = 2.0f * (layout.centerX + x) / vw - 1.0f;
                float ty = 2.0f * (layout.centerY + y) / vh - 1.0f;
                float tz = 0.0f;

                pm[0] =   sx; pm[4] = 0.0f; pm[ 8] = 0.0f; pm[12] =   tx;
                pm[1] = 0.0f; pm[5] =   sy; pm[ 9] = 0.0f; pm[13] =   ty;
                pm[2] = 0.0f; pm[6] = 0.0f; pm[10] =   sz; pm[14] =   tz;
                pm[3] = 0.0f; pm[7] = 0.0f; pm[11] = 0.0f; pm[15] = 1.0f;
        }

        static void perspectiveFov(float[] pm, int x, int y, FigureLayout layout, float vw, float vh) {
                float near = layout.near;
                float far = layout.far;
                float rd = 1.0f / (near - far);
                float sx = 1.0f / (float) Math.tan(layout.angle * TO_FLOAT * Math.PI);
                float sy = sx * (vw / vh);
                float sz = -(far + near) * rd;
                float tx = 2.0f * (layout.centerX + x) / vw - 1.0f;
                float ty = 2.0f * (layout.centerY + y) / vh - 1.0f;
                float tz = 2.0f * far * near * rd;

                pm[0] =   sx; pm[4] = 0.0f; pm[ 8] =   tx; pm[12] = 0.0f;
                pm[1] = 0.0f; pm[5] =   sy; pm[ 9] =   ty; pm[13] = 0.0f;
                pm[2] = 0.0f; pm[6] = 0.0f; pm[10] =   sz; pm[14] =   tz;
                pm[3] = 0.0f; pm[7] = 0.0f; pm[11] = 1.0f; pm[15] = 0.0f;
        }

        static void perspectiveWH(float[] pm, int x, int y, FigureLayout layout, float vw, float vh) {
                float zFar = layout.far;
                float zNear = layout.near;
                float width = layout.perspectiveWidth == 0 ? vw : layout.perspectiveWidth * TO_FLOAT;
                float height = layout.perspectiveHeight == 0 ? vh : layout.perspectiveHeight * TO_FLOAT;

                float rd = 1.0f / (zNear - zFar);
                float sx = 2.0f * zNear / width;
                float sy = 2.0f * zNear / height;
                float sz = -(zNear + zFar) * rd;
                float tx = 2.0f * (layout.centerX + x) / vw - 1.0f;
                float ty = 2.0f * (layout.centerY + y) / vh - 1.0f;
                float tz = 2.0f * zFar * zNear * rd;

                pm[0] =   sx; pm[4] = 0.0f; pm[ 8] =   tx; pm[12] = 0.0f;
                pm[1] = 0.0f; pm[5] =   sy; pm[ 9] =   ty; pm[13] = 0.0f;
                pm[2] = 0.0f; pm[6] = 0.0f; pm[10] =   sz; pm[14] =   tz;
                pm[3] = 0.0f; pm[7] = 0.0f; pm[11] = 1.0f; pm[15] = 0.0f;
        }

        static void getSpriteVertex(float[] quad, float[] center, int angle, float halfW, float halfH) {
                float r = angle * TO_RADIANS;
                float sin = (float) Math.sin(r);
                float cos = (float) Math.cos(r);
                float x = center[0];
                float y = center[1];
                float z = center[2];
                float w = center[3];
                quad[0] = -halfW * cos + halfH * -sin + x;
                quad[1] = -halfW * sin + halfH * cos + y;
                quad[2] = z;
                quad[3] = w;
                float bx = -halfW * cos + -halfH * -sin + x;
                float by = -halfW * sin + -halfH * cos + y;
                quad[4] = bx;
                quad[5] = by;
                quad[6] = z;
                quad[7] = w;
                float cx = halfW * cos + halfH * -sin + x;
                float cy = halfW * sin + halfH * cos + y;
                quad[8] = cx;
                quad[9] = cy;
                quad[10] = z;
                quad[11] = w;
                quad[12] = cx;
                quad[13] = cy;
                quad[14] = z;
                quad[15] = w;
                quad[16] = bx;
                quad[17] = by;
                quad[18] = z;
                quad[19] = w;
                quad[20] = halfW * cos + -halfH * -sin + x;
                quad[21] = halfW * sin + -halfH * cos + y;
                quad[22] = z;
                quad[23] = w;
        }

        // ★★ 纯 Java 实现（修复 Mascot Capsule 3D 游戏崩溃）：
        //   上游 JL-Mod 的这三个方法在 libmicro3d.so 里（JNI 符号
        //   Java_ru_woesss_j2me_micro3d_Utils_*，与本项目旧打包
        //   com.mascotcapsule...Utils 的符号名不匹配，且 .so 未随包分发）→
        //   System.loadLibrary("micro3d") 抛 UnsatisfiedLinkError →
        //   所有 Mascot Capsule 3D 游戏闪退。
        //   这里按上游 cpp/micro3d/src/utils.cpp 的语义逐函数移植为纯 Java：
        //   fillBuffer / transform 是纯数学（顶点量极小，性能无虞）；
        //   glReadPixels 用 GLES20 逐行读回再 setPixels（与原生版同款逐行
        //   策略，行映射保持一致：GL 行 y+i → Bitmap 行 y+i）。

        static void fillBuffer(FloatBuffer buffer, FloatBuffer vertices, int[] indices) {
                // 原生版：for i: *dst++ = src[indices[i]]（Vec3f = 3 个 float）。
                // 原生从缓冲区首地址起写（忽略 Buffer.position），这里用绝对位置写：
                // 逐帧重用的缓冲区不会因 position 残留而溢出。
                int out = 0;
                for (int idx : indices) {
                        int srcPos = idx * 3;
                        buffer.put(out++, vertices.get(srcPos));
                        buffer.put(out++, vertices.get(srcPos + 1));
                        buffer.put(out++, vertices.get(srcPos + 2));
                }
        }

        static void glReadPixels(int x, int y, int width, int height, Bitmap bitmapBuffer) {
                // 原生版逐行读回（x!=0 或 width<bitmapWidth 时必须逐行，
                // 否则 stride 不匹配）；此处同款逐行 + setPixels 写位图。
                // 注意保持与原生版一致的行映射：GL 行 (y+i) → 位图行 (y+i)。
                android.opengl.GLES20.glPixelStorei(
                                android.opengl.GLES20.GL_PACK_ALIGNMENT, 1);
                java.nio.ByteBuffer row = java.nio.ByteBuffer.allocateDirect(width * 4)
                                .order(java.nio.ByteOrder.nativeOrder());
                int[] pixels = new int[width];
                for (int i = 0; i < height; i++) {
                        row.clear();
                        android.opengl.GLES20.glReadPixels(
                                        x, y + i, width, 1,
                                        android.opengl.GLES20.GL_RGBA,
                                        android.opengl.GLES20.GL_UNSIGNED_BYTE, row);
                        row.rewind();
                        for (int px = 0; px < width; px++) {
                                int r = row.get() & 0xFF;
                                int g = row.get() & 0xFF;
                                int b = row.get() & 0xFF;
                                int a = row.get() & 0xFF;
                                // Bitmap.setPixels 收非预乘 ARGB（不透明像素与
                                // 原生直写 RGBA 的结果完全一致）
                                pixels[px] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                        bitmapBuffer.setPixels(pixels, 0, width, x, y + i, width, 1);
                }
        }

        /**
         * 骨骼变换（对应原生 transform）。
         * Bone 结构（对齐上游 utils.h）：int length; int parent; float[12] matrix;
         * = 8 + 48 = 56 字节/骨骼（ByteBuffer 小端）。矩阵为 3x4 行主序：
         * m00 m01 m02 m03 / m10 m11 m12 m13 / m20 m21 m22 m23。
         */
        static void transform(FloatBuffer srcVertices, FloatBuffer dstVertices,
                        FloatBuffer srcNormals, FloatBuffer dstNormals,
                        ByteBuffer boneMatrices, float[] actionMatrices) {
                boneMatrices.rewind();
                final int boneBytes = 56;
                if (boneMatrices.remaining() < boneBytes) return;
                int bonesLen = boneMatrices.remaining() / boneBytes;
                float[][] tmp = new float[bonesLen][];
                int actionsLen = actionMatrices == null ? 0 : actionMatrices.length / 12;
                int vertexCount = srcVertices.remaining() / 3;
                int vertIdx = 0;
                int normIdx = 0;
                for (int i = 0; i < bonesLen; i++) {
                        int base = i * boneBytes;
                        int length = boneMatrices.getInt(base);
                        int parent = boneMatrices.getInt(base + 4);
                        float[] boneM = new float[12];
                        for (int k = 0; k < 12; k++) {
                                boneM[k] = boneMatrices.getFloat(base + 8 + k * 4);
                        }
                        float[] m;
                        if (parent == -1) {
                                m = boneM;
                        } else {
                                m = multiply(tmp[parent], boneM);
                        }
                        if (i < actionsLen) {
                                float[] act = new float[12];
                                System.arraycopy(actionMatrices, i * 12, act, 0, 12);
                                m = multiply(m, act);
                        }
                        tmp[i] = m;
                        for (int j = 0; j < length && vertIdx < vertexCount; j++, vertIdx++) {
                                int sp = vertIdx * 3;
                                float sx = srcVertices.get(sp), sy = srcVertices.get(sp + 1),
                                                sz = srcVertices.get(sp + 2);
                                int dp = vertIdx * 3;
                                // transformPoint = 向量变换 + 平移（m03/m13/m23）；
                                // 绝对位置写（与原生一致：总从首地址覆盖，忽略 position 残留）
                                dstVertices.put(dp, m[0] * sx + m[1] * sy + m[2] * sz + m[3]);
                                dstVertices.put(dp + 1, m[4] * sx + m[5] * sy + m[6] * sz + m[7]);
                                dstVertices.put(dp + 2, m[8] * sx + m[9] * sy + m[10] * sz + m[11]);
                                if (srcNormals != null && dstNormals != null) {
                                        float nx = srcNormals.get(sp), ny = srcNormals.get(sp + 1),
                                                nz = srcNormals.get(sp + 2);
                                        // transformVector = 无平移
                                        dstNormals.put(normIdx, m[0] * nx + m[1] * ny + m[2] * nz);
                                        dstNormals.put(normIdx + 1, m[4] * nx + m[5] * ny + m[6] * nz);
                                        dstNormals.put(normIdx + 2, m[8] * nx + m[9] * ny + m[10] * nz);
                                        normIdx += 3;
                                }
                        }
                }
        }

        /** 3x4 行主序矩阵乘：返回 l * r（对应上游 Matrix::multiply(l, r)）。 */
        private static float[] multiply(float[] l, float[] r) {
                float[] out = new float[12];
                out[0] = l[0] * r[0] + l[1] * r[4] + l[2] * r[8];
                out[1] = l[0] * r[1] + l[1] * r[5] + l[2] * r[9];
                out[2] = l[0] * r[2] + l[1] * r[6] + l[2] * r[10];
                out[3] = l[0] * r[3] + l[1] * r[7] + l[2] * r[11] + l[3];
                out[4] = l[4] * r[0] + l[5] * r[4] + l[6] * r[8];
                out[5] = l[4] * r[1] + l[5] * r[5] + l[6] * r[9];
                out[6] = l[4] * r[2] + l[5] * r[6] + l[6] * r[10];
                out[7] = l[4] * r[3] + l[5] * r[7] + l[6] * r[11] + l[7];
                out[8] = l[8] * r[0] + l[9] * r[4] + l[10] * r[8];
                out[9] = l[8] * r[1] + l[9] * r[5] + l[10] * r[9];
                out[10] = l[8] * r[2] + l[9] * r[6] + l[10] * r[10];
                out[11] = l[8] * r[3] + l[9] * r[7] + l[10] * r[11] + l[11];
                return out;
        }

        static {
                // 无原生依赖：libmicro3d.so 的 3 个函数已全部在上方用纯 Java 实现
        }
}