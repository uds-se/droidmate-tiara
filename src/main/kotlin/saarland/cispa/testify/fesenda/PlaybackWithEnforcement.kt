package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import org.droidmate.configuration.Configuration
import org.droidmate.device.datatypes.IGuiState
import org.droidmate.device.datatypes.Widget
import org.droidmate.exploration.actions.ExplorationAction
import org.droidmate.exploration.actions.ResetAppExplorationAction
import org.droidmate.exploration.actions.WidgetExplorationAction
import org.droidmate.misc.SysCmdExecutor
import org.droidmate.report.uniqueString
import saarland.cispa.testify.ISelectableExplorationStrategy
import saarland.cispa.testify.strategies.playback.MemoryPlayback
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.IOException
import java.nio.file.Files

class PlaybackWithEnforcement private constructor(packageName: String, newTraces: List<PlaybackTrace>,
                                                  private val widget: Widget, private val api: IApi,
                                                  private val cfg: Configuration) : MemoryPlayback(packageName, newTraces) {

    private val cmdExecutor = SysCmdExecutor()

    override fun chooseAction(guiState: IGuiState, appPackageName: String): ExplorationAction {
        val action = super.chooseAction(guiState, appPackageName)

        enforcePolicies(action)

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
    private fun IApi.toPolicyEnforcementString(): String{
        val params = this.paramTypes.joinToString()
        val uri = if (this.objectClass.contains("ContentResolver") && this.paramTypes.indexOf("android.net.Uri") != -1)
            "\t${this.parseUri()}"
        else
            ""

        return "$objectClass.$methodName($params)$uri\tMock"
    }

    private fun enforcePolicies(action: ExplorationAction){
        // If the action is runtime permission, the current policy should continue to be used
        if (action.isEndorseRuntimePermission)
            return

        // Enforcement on reset
        if ( ((action is ResetAppExplorationAction) && (widget.id == "<RESET>")) ||
        ((action is WidgetExplorationAction) && (widget.uniqueString == action.widget.uniqueString))){
            val policyStr = api.toPolicyEnforcementString()
            logger.warn("Enforcing policy $policyStr")
            writePoliciesFile(policyStr)
        }
        else if ((action is WidgetExplorationAction) && (!action.isEndorseRuntimePermission)){
            writePoliciesFile("")
        }
    }

    private fun writePoliciesFile(policy: String){
        try {
            val tempFile = Files.createTempFile("policies", ".droidmate")
            Files.delete(tempFile)

            Files.write(tempFile, policy.toByteArray())

            val command = "${cfg.adbCommand} -s ${cfg.deviceSerialNumber} push $tempFile /data/local/tmp/api_policies.txt"
            this.cmdExecutor.execute("Sending new policy enforcement file to device", command)
        }
        catch(e: IOException){
            logger.error(e.message, e)
        }
    }

    companion object {
        /**
         * Creates a new exploration strategy instance
         *
         * @param packageName Package name which should be loaded from the memory. Load all APKS if not provided
         * @param memoryTraces Trace of previous exploration (set of actions between 2 resets)
         */
        fun build(packageName: String, memoryTraces: List<PlaybackTrace>, widget: Widget,
                  api: IApi, cfg: Configuration): List<ISelectableExplorationStrategy> {
            return listOf(PlaybackWithEnforcement(packageName, memoryTraces, widget, api, cfg))
        }

    }
}

