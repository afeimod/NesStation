/*
 * Copyright 2024 Yury Kharchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.woesss.gles;

import android.graphics.Bitmap;

public class GLESUtils {
	private GLESUtils() {}

	/** For GLES1 context */
	public static native void blit(int x, int y, int w, int h, Bitmap buffer);

	/** For GLES2 context */
	public static native void blit2(int x, int y, int w, int h, Bitmap buffer);

	static {
		System.loadLibrary("c++_shared");
		System.loadLibrary("gles_utils");
	}
}
