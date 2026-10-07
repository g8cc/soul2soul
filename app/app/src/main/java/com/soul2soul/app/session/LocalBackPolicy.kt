package com.soul2soul.app.session

/** Keep Android navigation gestures on the viewer device from ending an active remote-control call. */
object LocalBackPolicy {
    fun shouldRouteBackToRemote(live: Boolean, controlMode: Boolean): Boolean = live && controlMode
}
