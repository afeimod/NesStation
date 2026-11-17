/*
 * Host interface for running a MIDlet's UI surface.
 *
 * Decouples the J2ME runtime (MidletThread, Display, ContextHolder) from the
 * concrete Activity that owns the rendering container. MicroActivity
 * implements this for the standalone J2ME-Loader path; an embedded host
 * driven by EmulatorScreen / J2meEngine can implement it without being an
 * Activity and without killing the process on exit.
 *
 * This is the first step of re-integrating J2ME into the unified
 * EmulatorScreen: until ContextHolder/MidletThread depend on this interface
 * instead of the concrete MicroActivity, a MIDlet cannot be driven by any
 * non-Activity host (MidletThread.resumeApp() requires
 * ContextHolder.getActivity() to return a visible MicroActivity, and
 * notifyDestroyed() calls finish() + Process.killProcess).
 */

package javax.microedition.shell;

import javax.microedition.lcdui.Displayable;

public interface J2meHost {
	/**
	 * Attach the displayable's view to the host's rendering container.
	 * Mirrors MicroActivity.setCurrent(Displayable).
	 */
	void setCurrent(Displayable displayable);

	/** @return the currently displayed displayable, or null. */
	Displayable getCurrent();

	/** @return whether the host is resumed and visible on screen. */
	boolean isVisible();

	/**
	 * Request the host to exit the MIDlet session.
	 *
	 * Standalone Activity host (MicroActivity): finish() the activity; the
	 * process is then killed by MidletThread to reset J2ME static state
	 * (only when MidletThread.embedded == false).
	 *
	 * Embedded host (J2meEngine inside EmulatorScreen): unload the engine
	 * and return to the library WITHOUT killing the app process
	 * (MidletThread.embedded == true).
	 */
	void requestExit();
}
