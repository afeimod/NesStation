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

package com.nokia.mid.m3d;

import static android.opengl.GLES20.*;

import android.opengl.GLU;
import android.util.Log;

import javax.microedition.util.ContextHolder;

class ShaderProgram {
	private static final String TAG = ShaderProgram.class.getName();
	private static final String VERTEX = "shaders/m3d.vsh";
	private static final String FRAGMENT = "shaders/m3d.fsh";

	final int aTexCoord;
	final int uTextureUnit;
	final int aPosition;
	final int uColor;
	final int uMatrix;

	ShaderProgram() {
		String vertexCode = ContextHolder.getAssetAsString(VERTEX);
		String fragmentCode = ContextHolder.getAssetAsString(FRAGMENT);
		int vertexId = loadShader(GL_VERTEX_SHADER, vertexCode);
		int fragmentId = loadShader(GL_FRAGMENT_SHADER, fragmentCode);
		int program = glCreateProgram();
		glAttachShader(program, vertexId);
		glAttachShader(program, fragmentId);
		glLinkProgram(program);
		int[] status = new int[1];
		glGetProgramiv(program, GL_LINK_STATUS, status, 0);
		if (status[0] == 0) {
			String s = glGetProgramInfoLog(program);
			Log.e(TAG, "createProgram: " + s);
		}
		int error = glGetError();
		if (error != GL_NO_ERROR) {
			String errorString = GLU.gluErrorString(error);
			Log.e(TAG, "init program: glError " + errorString);
		}
		aPosition = glGetAttribLocation(program, "a_position");
		aTexCoord = glGetAttribLocation(program, "a_texcoord0");
		uTextureUnit = glGetUniformLocation(program, "sampler0");
		uColor = glGetUniformLocation(program, "u_color");
		uMatrix = glGetUniformLocation(program, "u_matrix");
		glUseProgram(program);
		error = glGetError();
		if (error != GL_NO_ERROR) {
			String s = GLU.gluErrorString(error);
			Log.e(TAG, "init program: glError " + s);
			glDeleteShader(vertexId);
			glDeleteShader(fragmentId);
			glDeleteProgram(program);
			program = -1;
		}
		glDeleteShader(vertexId);
		glDeleteShader(fragmentId);
		glReleaseShaderCompiler();
		if (program == -1) {
			throw new RuntimeException("Init shader program error: see log for detail");
		}
	}

	private static int loadShader(int type, String shaderCode) {
		int shader = glCreateShader(type);
		glShaderSource(shader, shaderCode);
		glCompileShader(shader);
		int[] status = new int[1];
		glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0) {
			String s = glGetShaderInfoLog(shader);
			Log.e(TAG, "loadShader: " + s);
			glDeleteShader(shader);
			return -1;
		}
		return shader;
	}
}
