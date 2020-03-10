/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.engine.gecko.webextension

import android.graphics.Bitmap
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.engine.gecko.await
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.ActionHandler
import mozilla.components.concept.engine.webextension.DisabledFlags
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Metadata
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.TabHandler
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension as GeckoNativeWebExtension
import org.mozilla.geckoview.WebExtension.Action as GeckoNativeWebExtensionAction

/**
 * Gecko-based implementation of [WebExtension], wrapping the native web
 * extension object provided by GeckoView.
 */
@Suppress("TooManyFunctions")
class GeckoWebExtension(
    id: String,
    url: String,
    val runtime: GeckoRuntime,
    allowContentMessaging: Boolean = true,
    supportActions: Boolean = false,
    val nativeExtension: GeckoNativeWebExtension = GeckoNativeWebExtension(
        url,
        id,
        createWebExtensionFlags(allowContentMessaging),
        runtime.webExtensionController
    ),
    private val connectedPorts: MutableMap<PortId, Port> = mutableMapOf()
) : WebExtension(id, url, supportActions) {

    private val logger = Logger("GeckoWebExtension")

    constructor(native: GeckoNativeWebExtension, runtime: GeckoRuntime) :
        this(native.id, native.location, runtime, true, true, native)

    init {
        // By default, we currently allow extension scripts to run for private sessions.
        // We'll later expose this API so apps can control this via an extension-specific setting:
        // https://github.com/mozilla-mobile/android-components/issues/6228
        if (nativeExtension.metaData?.allowedInPrivateBrowsing == false) {
            // We have do this once because GV defaults to 'false', and only once because
            // the state persists.
            runtime.webExtensionController.setAllowedInPrivateBrowsing(nativeExtension, true)
        }
    }

    /**
     * Uniquely identifies a port using its name and the session it
     * was opened for. Ports connected from background scripts will
     * have a null session.
     */
    data class PortId(val name: String, val session: EngineSession? = null)

    /**
     * See [WebExtension.registerBackgroundMessageHandler].
     */
    override fun registerBackgroundMessageHandler(name: String, messageHandler: MessageHandler) {
        val portDelegate = object : GeckoNativeWebExtension.PortDelegate {

            override fun onPortMessage(message: Any, port: GeckoNativeWebExtension.Port) {
                messageHandler.onPortMessage(message, GeckoPort(port))
            }

            override fun onDisconnect(port: GeckoNativeWebExtension.Port) {
                connectedPorts.remove(PortId(name))
                messageHandler.onPortDisconnected(GeckoPort(port))
            }
        }

        val messageDelegate = object : GeckoNativeWebExtension.MessageDelegate {

            override fun onConnect(port: GeckoNativeWebExtension.Port) {
                port.setDelegate(portDelegate)
                val geckoPort = GeckoPort(port)
                connectedPorts[PortId(name)] = geckoPort
                messageHandler.onPortConnected(geckoPort)
            }

            override fun onMessage(
                // We don't use the same delegate instance for multiple apps so we don't need to verify the name.
                name: String,
                message: Any,
                sender: GeckoNativeWebExtension.MessageSender
            ): GeckoResult<Any>? {
                val response = messageHandler.onMessage(message, null)
                return response?.let { GeckoResult.fromValue(it) }
            }
        }

        nativeExtension.setMessageDelegate(messageDelegate, name)
    }

    /**
     * See [WebExtension.registerContentMessageHandler].
     */
    override fun registerContentMessageHandler(session: EngineSession, name: String, messageHandler: MessageHandler) {
        val portDelegate = object : GeckoNativeWebExtension.PortDelegate {

            override fun onPortMessage(message: Any, port: GeckoNativeWebExtension.Port) {
                messageHandler.onPortMessage(message, GeckoPort(port, session))
            }

            override fun onDisconnect(port: GeckoNativeWebExtension.Port) {
                val geckoPort = GeckoPort(port, session)
                connectedPorts.remove(PortId(name, session))
                messageHandler.onPortDisconnected(geckoPort)
            }
        }

        val messageDelegate = object : GeckoNativeWebExtension.MessageDelegate {

            override fun onConnect(port: GeckoNativeWebExtension.Port) {
                port.setDelegate(portDelegate)
                val geckoPort = GeckoPort(port, session)
                connectedPorts[PortId(name, session)] = geckoPort
                messageHandler.onPortConnected(geckoPort)
            }

            override fun onMessage(
                // We don't use the same delegate instance for multiple apps so we don't need to verify the name.
                name: String,
                message: Any,
                sender: GeckoNativeWebExtension.MessageSender
            ): GeckoResult<Any>? {
                val response = messageHandler.onMessage(message, session)
                return response?.let { GeckoResult.fromValue(it) }
            }
        }

        val geckoSession = (session as GeckoEngineSession).geckoSession
        geckoSession.webExtensionController.setMessageDelegate(nativeExtension, messageDelegate, name)
    }

    /**
     * See [WebExtension.hasContentMessageHandler].
     */
    override fun hasContentMessageHandler(session: EngineSession, name: String): Boolean {
        val geckoSession = (session as GeckoEngineSession).geckoSession
        return geckoSession.webExtensionController.getMessageDelegate(nativeExtension, name) != null
    }

    /**
     * See [WebExtension.getConnectedPort].
     */
    override fun getConnectedPort(name: String, session: EngineSession?): Port? {
        return connectedPorts[PortId(name, session)]
    }

    /**
     * See [WebExtension.disconnectPort].
     */
    override fun disconnectPort(name: String, session: EngineSession?) {
        val portId = PortId(name, session)
        val port = connectedPorts[portId]
        port?.let {
            it.disconnect()
            connectedPorts.remove(portId)
        }
    }

    /**
     * See [WebExtension.registerActionHandler].
     */
    override fun registerActionHandler(actionHandler: ActionHandler) {
        if (!supportActions) {
            logger.error("Attempt to register default action handler but browser and page " +
                "action support is turned off for this extension: $id")
            return
        }

        val actionDelegate = object : GeckoNativeWebExtension.ActionDelegate {

            override fun onBrowserAction(
                ext: GeckoNativeWebExtension,
                // Session will always be null here for the global default delegate
                session: GeckoSession?,
                action: GeckoNativeWebExtensionAction
            ) {
                actionHandler.onBrowserAction(this@GeckoWebExtension, null, action.convert())
            }

            override fun onPageAction(
                ext: GeckoNativeWebExtension,
                // Session will always be null here for the global default delegate
                session: GeckoSession?,
                action: GeckoNativeWebExtensionAction
            ) {
                // Page action API doesn't support enable/disable so we enable by default.
                actionHandler.onPageAction(
                    this@GeckoWebExtension,
                    null,
                    action.convert().copy(enabled = true)
                )
            }

            override fun onTogglePopup(
                ext: GeckoNativeWebExtension,
                action: GeckoNativeWebExtensionAction
            ): GeckoResult<GeckoSession>? {
                val session = actionHandler.onToggleActionPopup(this@GeckoWebExtension, action.convert())
                return session?.let { GeckoResult.fromValue((session as GeckoEngineSession).geckoSession) }
            }
        }

        nativeExtension.setActionDelegate(actionDelegate)
    }

    /**
     * See [WebExtension.registerActionHandler].
     */
    override fun registerActionHandler(session: EngineSession, actionHandler: ActionHandler) {
        if (!supportActions) {
            logger.error("Attempt to register action handler on session but browser and page " +
                "action support is turned off for this extension: $id")
            return
        }

        val actionDelegate = object : GeckoNativeWebExtension.ActionDelegate {

            override fun onBrowserAction(
                ext: GeckoNativeWebExtension,
                geckoSession: GeckoSession?,
                action: GeckoNativeWebExtensionAction
            ) {
                actionHandler.onBrowserAction(this@GeckoWebExtension, session, action.convert())
            }

            override fun onPageAction(
                ext: GeckoNativeWebExtension,
                geckoSession: GeckoSession?,
                action: GeckoNativeWebExtensionAction
            ) {
                // Page action API doesn't support enable/disable so we enable by default.
                actionHandler.onPageAction(
                    this@GeckoWebExtension,
                    session,
                    action.convert().copy(enabled = true)
                )
            }
        }

        val geckoSession = (session as GeckoEngineSession).geckoSession
        geckoSession.webExtensionController.setActionDelegate(nativeExtension, actionDelegate)
    }

    /**
     * See [WebExtension.hasActionHandler].
     */
    override fun hasActionHandler(session: EngineSession): Boolean {
        val geckoSession = (session as GeckoEngineSession).geckoSession
        return geckoSession.webExtensionController.getActionDelegate(nativeExtension) != null
    }

    /**
     * See [WebExtension.registerTabHandler].
     */
    override fun registerTabHandler(tabHandler: TabHandler) {

        val tabDelegate = object : GeckoNativeWebExtension.TabDelegate {

            override fun onNewTab(
                ext: GeckoNativeWebExtension,
                tabDetails: GeckoNativeWebExtension.CreateTabDetails
            ): GeckoResult<GeckoSession>? {
                val geckoEngineSession = GeckoEngineSession(runtime, openGeckoSession = false)

                tabHandler.onNewTab(
                    this@GeckoWebExtension,
                    geckoEngineSession,
                    tabDetails.active == true,
                    tabDetails.url ?: ""
                )
                return GeckoResult.fromValue(geckoEngineSession.geckoSession)
            }
        }

        nativeExtension.tabDelegate = tabDelegate
    }

    /**
     * See [WebExtension.registerTabHandler].
     */
    override fun registerTabHandler(session: EngineSession, tabHandler: TabHandler) {

        val tabDelegate = object : GeckoNativeWebExtension.SessionTabDelegate {

            override fun onUpdateTab(
                ext: GeckoNativeWebExtension,
                geckoSession: GeckoSession,
                tabDetails: GeckoNativeWebExtension.UpdateTabDetails
            ): GeckoResult<AllowOrDeny> {

                return if (tabHandler.onUpdateTab(
                    this@GeckoWebExtension,
                    session,
                    tabDetails.active == true,
                    tabDetails.url)
                ) {
                    GeckoResult.ALLOW
                } else {
                    GeckoResult.DENY
                }
            }

            override fun onCloseTab(
                ext: GeckoNativeWebExtension?,
                geckoSession: GeckoSession
            ): GeckoResult<AllowOrDeny> {

                return if (ext != null) {
                    if (tabHandler.onCloseTab(this@GeckoWebExtension, session)) {
                        GeckoResult.ALLOW
                    } else {
                        GeckoResult.DENY
                    }
                } else {
                    GeckoResult.DENY
                }
            }
        }

        val geckoSession = (session as GeckoEngineSession).geckoSession
        geckoSession.webExtensionController.setTabDelegate(nativeExtension, tabDelegate)
    }

    /**
     * See [WebExtension.getMetadata].
     */
    override fun getMetadata(): Metadata? {
        return nativeExtension.metaData?.let {
            Metadata(
                name = it.name,
                description = it.description,
                developerName = it.creatorName,
                developerUrl = it.creatorUrl,
                homePageUrl = it.homepageUrl,
                version = it.version,
                permissions = it.permissions.toList(),
                hostPermissions = it.origins.toList(),
                disabledFlags = DisabledFlags.select(it.disabledFlags),
                optionsPageUrl = it.optionsPageUrl,
                openOptionsPageInTab = it.openOptionsPageInTab
            )
        }
    }

    override fun isEnabled(): Boolean {
        return nativeExtension.metaData?.enabled ?: true
    }
}

/**
 * Gecko-based implementation of [Port], wrapping the native port provided by GeckoView.
 */
class GeckoPort(
    internal val nativePort: GeckoNativeWebExtension.Port,
    engineSession: EngineSession? = null
) : Port(engineSession) {

    override fun postMessage(message: JSONObject) {
        nativePort.postMessage(message)
    }

    override fun name(): String {
        return nativePort.name
    }

    override fun disconnect() {
        nativePort.disconnect()
    }
}

private fun createWebExtensionFlags(allowContentMessaging: Boolean): Long {
    return if (allowContentMessaging) {
        GeckoNativeWebExtension.Flags.ALLOW_CONTENT_MESSAGING
    } else {
        GeckoNativeWebExtension.Flags.NONE
    }
}

private fun GeckoNativeWebExtensionAction.convert(): Action {
    val loadIcon: (suspend (Int) -> Bitmap?)? = icon?.let {
        { size -> icon?.get(size)?.await() }
    }

    val onClick = { click() }

    return Action(
        title,
        enabled,
        loadIcon,
        badgeText,
        badgeTextColor,
        badgeBackgroundColor,
        onClick
    )
}
