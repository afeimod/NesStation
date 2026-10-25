/*
 * Copyright (c) 2009 Nokia Corporation and/or its subsidiary(-ies).
 * All rights reserved.
 * This component and the accompanying materials are made available
 * under the terms of "Eclipse Public License v1.0"
 * which accompanies this distribution, and is available
 * at the URL "http://www.eclipse.org/legal/epl-v10.html".
 *
 * Initial Contributors:
 * Nokia Corporation - initial contribution.
 *
 * Contributors:
 *
 * Description:
 *
 */
/**
 * \file
 * \brief Target platform dependent Java module for Symbian.
 */

package javax.microedition.m3g;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Implements platform-dependent functionality. At the moment, this
 * includes native finalization and some helper methods for
 * synchronizing 2D and 3D rendering.
 */
class Platform {

        private static boolean libraryLoaded = false;

        //------------------------------------------------------------------
        // Package private methods
        //------------------------------------------------------------------

        /**
         * Executes given runnable in UI thread if caller thread is not UI thread
         *
         * ★★ 对齐 JL-Mod：直接在调用线程执行 obj.run()。
         *
         * 旧实现（自老版 J2ME-Loader 移植）每次 3D 调用（bindTarget/clear/
         * render/renderWorld/releaseTarget …）都用 executor.submit(obj).get()
         * 阻塞往返到专用 "M3G" 线程 —— 一帧 3D 游戏往往有几十上百次这样的
         * 调用，每次都要经历 submit→唤醒→执行→get() 返回的两次线程切换。
         * 在 GameBox 嵌入式多线程环境（MIDlet 线程 + 队列线程 + GL 线程 +
         * 主线程）下线程调度延迟被成倍放大，表现为 3D 游戏帧率极低、加载
         * 画面卡死（用户反馈“3D 运行效率特别差，甚至会卡加载”）。
         *
         * JL-Mod（woesss/JL-Mod master）的同名方法就是直接 obj.run()：
         * 渲染在 MIDlet 调用线程内同步完成。原生层 m3g_rendercontext.inl
         * 在每次渲染前后自己做 eglMakeCurrent/释放（1163/1235/1445 行），
         * EGL 上下文按调用线程绑定，与调用方线程无关 —— 直接执行安全。
         * micro3d 侧本来就没有线程切换，无需改动。
         */
        static void executeInUIThread(M3gRunnable obj) {
                if (!uiThreadAvailable()) {
                        throw new Error("Ui thread not available");
                }

                if (obj != null) {
                        // In this case we are in UI thread so just execute directly
                        obj.run();
                        // Check if any exceptions occured in execution
                        // and throw forward in caller thread
                        obj.checkAndThrow();
                }
        }

        /**
         * Check the UI thread / toolkit init status and store display if it is available
         *
         * @return true if either lcdui or eswt toolkit is initialized and ui thread is accessible
         * otherwise false
         */
        static boolean uiThreadAvailable() {
                // UI thread is available, so load native library if not already loaded
                if (!libraryLoaded) {
                        System.loadLibrary("c++_shared");
                        System.loadLibrary("javam3g");
                        libraryLoaded = true;
                }
                return true;
        }

        /**
         * Registers an Object3D in the global handle-to-object map. The
         * handle of the object must already be set at this point!
         */
        static void registerFinalizer(Object3D obj) {
                //heuristicGC();
        }

        /**
         * Registers a Graphics3D object (not derived from Object3D) for
         * finalization.
         */
        static void registerFinalizer(Graphics3D g3d) {
                //heuristicGC();
        }

        /**
         * Registers an Interface object for finalization
         */
        static void registerFinalizer(Interface m3g) {
        }

        /**
         * Registers a Loader object for finalization
         */
        static void registerFinalizer(Loader loader) {
        }

        /**
         * Flushes all pending rendering to a Graphics context and blocks
         * until finished
         */
        static void sync(Graphics g) {
                //ToolkitInvoker invoker = ToolkitInvoker.getToolkitInvoker();
                //invoker.toolkitSync(invoker.getToolkit());
        }

        /**
         * Flushes all pending rendering to an Image object
         */
        static void sync(Image img) {
                //ToolkitInvoker invoker = ToolkitInvoker.getToolkitInvoker();
                //invoker.toolkitSync(invoker.getToolkit());
        }

        /**
         * Finalizes the native peer of an interface
         */
        static native void finalizeInterface(long handle);

        /**
         * Finalizes the native peer of an object
         * JCF: added this wrapper method so we could pass the toolkit handle to the native method.
         */
        static void finalizeObject(long handle) {
                try {
                        final long finalHandle = handle;
                        executeInUIThread(
                                        new M3gRunnable() {
                                                @Override
                                                void doRun() {
                                                        _finalizeObject(finalHandle);
                                                }
                                        });
                } catch (Exception e) {
                        // do nothing
                }
        }

        /**
         * Finalizes the native peer of an object associated with
         * given Interface instance
         */
        static void finalizeObject(long handle, Interface aInterface) {
                try {
                        final long finalHandle = handle;
                        executeInUIThread(
                                        new M3gRunnable() {
                                                @Override
                                                public void doRun() {
                                                        _finalizeObject(finalHandle);
                                                }
                                        });
                } catch (Exception e) {
                        // do nothing
                }
        }


        //------------------------------------------------------------------
        // Private methods
        //------------------------------------------------------------------

        /**
         * Trigger GC if minimum free memory limit has been exceeded in the native side
         */
        static void heuristicGC() {
        }

        private static native void _finalizeObject(long handle);
}

