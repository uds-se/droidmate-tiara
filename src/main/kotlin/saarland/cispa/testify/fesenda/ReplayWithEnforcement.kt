package saarland.cispa.testify.fesenda

import kotlinx.coroutines.runBlocking
import org.droidmate.configuration.ConfigurationWrapper
import org.droidmate.device.apis.IApi
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.isClick
import org.droidmate.deviceInterface.exploration.isLaunchApp
import org.droidmate.deviceInterface.exploration.isLongClick
import org.droidmate.exploration.strategy.playback.Playback
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.toUUID
import org.droidmate.misc.SysCmdExecutor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Playback with enforcement strategy. It attempts to playback a [recorded model][modelDir] extracted from
 * and exploration log, delimited by reset actions and restricts access to an [API][api]
 * while interacting with a specific widget
 *
 * @param widgetId Widget which should be restricted, use [emptyUUID] for actions without widget
 * @param api API which should be restricted
 * @param cfg Experiment configuration
 * @param modelDir Trace of previous exploration
 */
class ReplayWithEnforcement constructor(
    private val widgetId: UUID,
    private val api: IApi,
    private val cfg: ConfigurationWrapper,
    modelDir: Path
) : Playback(modelDir) {
    companion object {
        @JvmStatic
        val emptyUUID = "NONE".toUUID()
    }

    private val cmdExecutor = SysCmdExecutor()

    override suspend fun chooseAction(): ExplorationAction {
        val action = super.chooseAction()

        enforcePolicies(toExecute)

        return action
    }

    /**
     * Convert an API to it's string for policy enforcement. Format is:
     *
     * # API blocking usage (tab separated):
     * # METHOD_SIGNATURE	(URI)	POLICY
     * # - If the method possess multiple URI parameters, it is possible to specify the URI multiple times.
     * # - The same API can be blocked multiple times, as long as it uses different URIs
     *
     * # Usage examples (tab separated):
     * # android.hardware.Camera.open(int)	Deny
     * # java.net.URL.<init>(java.net.URL, java.lang.String, java.net.URLStreamHandler)	Deny
     * # android.content.ContentResolver->query(android.net.Uri, java.lang.String[], java.lang.String, java.lang.String[], java.lang.String, android.os.CancellationSignal)	content://sms	Deny
     * # android.content.ContentResolver->query(android.net.Uri, java.lang.String[], java.lang.String, java.lang.String[], java.lang.String, android.os.CancellationSignal)	content://call_log	Deny
     *
     * @return Api in string format
     */
    private fun IApi.toPolicyEnforcementString(): String {
        val params = this.paramTypes.joinToString()
        val uri = if (this.objectClass.contains("ContentResolver") && this.paramTypes.indexOf("android.net.Uri") != -1)
            "\t${this.parseUri()}"
        else
            ""

        return "$objectClass.$methodName($params)$uri\tMock"
    }

    private fun shouldEnablePolicy(actionData: Interaction): Boolean {
        val actionType = actionData.actionType
        return when {
            (actionType.isLaunchApp()) -> (widgetId == emptyUUID)
            // (action is PressBackExplorationAction) -> (widgetId == emptyUUID)
            (actionType.isClick()) -> (actionData.targetWidget?.uid == widgetId)
            (actionType.isLongClick()) -> (actionData.targetWidget?.uid == widgetId)
            else -> false
        }
    }

    private fun shouldDisablePolicy(actionData: Interaction): Boolean {
        val actionType = actionData.actionType

        return if (!actionType.isClick() && !actionType.isLongClick()) {
            true
        } else {
            val state = runBlocking { model.getState(actionData.prevState) }!!
            !state.isRequestRuntimePermissionDialogBox
        }
    }

    private fun enforcePolicies(actionData: Interaction) {
        // If the action is runtime permission, the current policy should continue to be used
        val state = runBlocking { model.getState(actionData.prevState) }!!
        if (state.isRequestRuntimePermissionDialogBox)
            return

        // Enforcement on reset
        if (shouldEnablePolicy(actionData)) {
            val policyStr = api.toPolicyEnforcementString()
            logger.warn("Enforcing policy $policyStr")
            writePoliciesFile(policyStr)
        } else if (shouldDisablePolicy(actionData))
            writePoliciesFile("")
    }

    private fun writePoliciesFile(policy: String) {
        try {
            val tempFile = Files.createTempFile("policies", ".droidmate")
            Files.delete(tempFile)

            Files.write(tempFile, policy.toByteArray())

            val command =
                "${cfg.adbCommand} -s ${cfg.deviceSerialNumber} push $tempFile /data/local/tmp/api_policies.txt"
            this.cmdExecutor.execute("Sending new policy enforcement file to device", command)
        } catch (e: IOException) {
            logger.error(e.message, e)
        }
    }
}