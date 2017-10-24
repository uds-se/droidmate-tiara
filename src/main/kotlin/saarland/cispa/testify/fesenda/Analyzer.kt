package saarland.cispa.testify.fesenda

import org.droidmate.android_sdk.AdbWrapper
import org.droidmate.apis.IApi
import org.droidmate.apis.IApiLogcatMessage
import org.droidmate.configuration.ConfigurationBuilder
import org.droidmate.device.datatypes.Widget
import org.droidmate.exploration.actions.WidgetExplorationAction
import org.droidmate.misc.SysCmdExecutor
import org.droidmate.report.uniqueString
import org.slf4j.LoggerFactory
import saarland.cispa.testify.*
import saarland.cispa.testify.strategies.login.LoginWithFacebook
import saarland.cispa.testify.strategies.login.LoginWithGoogle
import saarland.cispa.testify.strategies.playback.MemoryPlayback
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Paths

object Analyzer{
    private val logger = LoggerFactory.getLogger(Analyzer::class.java)

    private val nrAttemptsConfirm = 3
    private val sensitiveApiList = ResourceManager.getResourceAsStringList("sensitiveApiList.txt")

    fun run(args: Array<String>){
        val additionalStrategies = getAdditionalExtraStrategies()
        val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())
        cfg.actionsLimit = 10
        cfg.resetEveryNthExplorationForward = 10
        val expCfg = ExperimentConfiguration(cfg, ArrayList())
        val memoryData = saarland.cispa.testify.Main.start(args, additionalStrategies, cfg, expCfg)

        memoryData.forEach { processMemory(it, args, expCfg) }
    }

    private fun getAdditionalExtraStrategies(): List<ISelectableExplorationStrategy>{
        val strategies : MutableList<ISelectableExplorationStrategy> = ArrayList()
        strategies.add(LoginWithFacebook.build())
        strategies.add(LoginWithGoogle.build())

        return strategies
    }

    private fun processMemory(memory: Memory, args: Array<String>, expCfg: ExperimentConfiguration){
        val apk = memory.getApk()
        val appPkg = apk.packageName

        val apiWidgetSummary = getAPIWidgetSummary(memory)
        Writer.writeAPIWidgetSummary(apiWidgetSummary, appPkg)

        val traceData = buildTraces(memory)
        val candidateTraces = filterTraces(traceData, apiWidgetSummary)
        serializeTraces(candidateTraces, "filtered", appPkg, expCfg)

        confirmCandidateTraces(candidateTraces, args, appPkg)
        serializeTraces(candidateTraces, "confirmed", appPkg, expCfg)

        evaluateConfirmedTraces(candidateTraces, args, appPkg)
        serializeTraces(candidateTraces, "evaluated", appPkg, expCfg)

        val confirmed = candidateTraces.filter { it.confirmed }
        val blocked = candidateTraces.filter { it.blocked }
        val partial = candidateTraces.filter { it.partiallyBlocked }
        logger.info("Unique traces: ${traceData.size}\tRelevant: ${candidateTraces.size}\tConfirmed: ${confirmed.count()}\tBlocked ${blocked.count()}\tPartially Blocked ${partial.count()}")
        logger.info("Blocked Sub-traces:")
        blocked.forEach { logger.info("${it.api}\t${it.widget}") }
        logger.info("Partially Blocked Sub-traces:")
        partial.forEach { logger.info("${it.api}\t${it.widget}") }
        logger.info("Not Blocked Sub-traces:")
        confirmed.filterNot { it.blocked || it.partiallyBlocked }.forEach { logger.info("${it.api.uniqueString}\t${it.widget}") }
        logger.info("Not confirmed Sub-traces:")
        candidateTraces.filterNot { it.confirmed }.forEach { logger.info("${it.api}\t${it.widget}") }
    }

    private fun serializeTraces(candidateTraces: List<CandidateTrace>, suffix: String, appPackageName: String,
                                expCfg: ExperimentConfiguration){
        candidateTraces.forEachIndexed { index, trace ->
            val outPath = expCfg.dataDir.resolve("${appPackageName}_${suffix}_$index.trace")
            try {
                logger.info("Serializing trace to ${outPath.fileName}")
                trace.serialize(outPath)
                logger.info("Trace serialized")
            }
            catch(e: IOException){
                logger.error("Filed to serialize trace to ${outPath.fileName}: ${e.message}", e)
            }
        }
    }

    private fun evaluateConfirmedTraces(candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces with enforcement")
        candidateTraces
                .filter { it.confirmed }
                .forEachIndexed { index, candidate ->
            candidate.trace.reset()
            val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())

            if (cfg.deviceSerialNumber.isEmpty()) {
                val deviceSN = AdbWrapper(cfg, SysCmdExecutor()).androidDevicesDescriptors[cfg.deviceIndex].deviceSerialNumber
                cfg.deviceSerialNumber = deviceSN
            }
            val playbackStrategy = PlaybackWithEnforcement.build(appPackageName, arrayListOf(candidate.trace),
                    candidate.widget, candidate.api, cfg)
            val memoryData = saarland.cispa.testify.Main.start(args, playbackStrategy, cfg)

            memoryData.forEach { memory ->
                val apk = memory.getApk()
                val appPkg = apk.packageName
                val baseName = "${appPkg}_trace$index"

                val apiWidgetSummary = getAPIWidgetSummary(memory)
                Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)

                if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                    val executedPlayback = playbackStrategy.first() as MemoryPlayback
                    val similarityRatio = executedPlayback.getExplorationRatio(candidate.widget)

                    if (similarityRatio >= candidate.similarityRatio)
                        candidate.blocked = true
                    else{
                        val executedTrace = executedPlayback.traces.first().getTraceCopy()
                        val lastWidgetAction = executedTrace.reversed().first { it.action is WidgetExplorationAction }

                        if (lastWidgetAction.explored)
                            candidate.partiallyBlocked = true
                    }
                }
            }
        }
    }

    private fun confirmCandidateTraces(candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces")
        candidateTraces.forEach { candidate ->
            // Repeat 3x to remove "flaky traces"
            var similarityRatio = 0.0
            var successCount = 0
            (0 until nrAttemptsConfirm).forEach { p ->
                logger.info("Exploring trace $p for (Api=${candidate.api.uniqueString} Widget=${candidate.widget.uniqueString})")
                candidate.trace.reset()
                val playbackStrategy = MemoryPlayback.build(appPackageName, arrayListOf(candidate.trace))
                val memoryData = saarland.cispa.testify.Main.start(args, playbackStrategy)

                memoryData.forEachIndexed { index, memory ->
                    val apk = memory.getApk()
                    val appPkg = apk.packageName
                    val baseName = "${appPkg}_trace$index"

                    val apiWidgetSummary = getAPIWidgetSummary(memory)
                    Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)

                    if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                        successCount++

                        similarityRatio += (playbackStrategy.first() as MemoryPlayback).getExplorationRatio(candidate.widget)
                    }
                }
            }

            candidate.trace.reset()
            if (successCount == nrAttemptsConfirm) {
                candidate.confirmed = true
                candidate.similarityRatio = similarityRatio / nrAttemptsConfirm
            }
        }
    }

    private fun filterTraces(traces: MutableList<PlaybackTrace>, apiWidgetSummary: List<WidgetSummary>): List<CandidateTrace>{
        val candidateTraces : MutableList<CandidateTrace> = ArrayList()

        apiWidgetSummary.forEach { widgetSummary ->
            // All traces have reset, so use the first time it started the app
            val filteredTraces = if (widgetSummary.widgetText == "<RESET>")
                arrayListOf(traces.first())
            else
                traces.filter { trace -> trace.contains(widgetSummary.widget) }

            filteredTraces.forEach { newTrace ->
                widgetSummary.apiData.forEach { (api) ->
                    candidateTraces.add(CandidateTrace(widgetSummary.widget, newTrace, api))
                }
            }
        }

        return candidateTraces
    }

    private fun buildTraces(memory: Memory): MutableList<PlaybackTrace>{
        val memoryRecords = memory.getRecords()
        val traces : MutableList<PlaybackTrace> = ArrayList()
        val packageName = memory.getApk().packageName

        // Create traces from memory records
        // Each trace starts with a reset
        // Last trace ends with terminate exploration
        for (i in 0 until memoryRecords.size) {
            val memoryRecord = memoryRecords[i]

            if (memoryRecord.type == ExplorationType.Reset)
                traces.add(PlaybackTrace())

            traces.last().add(memoryRecord.action, memoryRecord.state, packageName)
        }

        return traces
    }

    private fun getAPIWidgetSummary(memory: Memory): List<WidgetSummary>{
        val widgetSummaryData: MutableList<WidgetSummary> = ArrayList()
        val history = memory.getRecords()
        history.forEachIndexed { index, record ->
            val previousNonPermissionDialogAction = getPreviousNonPermissionDialogAction(history, index)

            val newWidgetSummary = getWidgetSummary(record, previousNonPermissionDialogAction)

            if (newWidgetSummary != null){
                val currWidgetSummary = widgetSummaryData.firstOrNull { it == newWidgetSummary }

                if (currWidgetSummary == null)
                    widgetSummaryData.add(newWidgetSummary)
                else
                    currWidgetSummary.merge(newWidgetSummary)
            }
        }

        return widgetSummaryData
    }

    private fun IApiLogcatMessage.matches(monitored: String): Boolean{
        var uri = ""
        if (this.objectClass.contains("ContentResolver") && this.paramTypes.indexOf("android.net.Uri") != -1)
            uri = "\t${this.parseUri()}"

        val params = this.paramTypes.joinToString(separator = ",")

        val thisValue = "${this.objectClass}->${this.methodName}($params)$uri"

        return thisValue.contains(monitored)
    }

    private fun getWidgetSummary(record: IMemoryRecord, previousNonPermissionDialogAction: IMemoryRecord?): WidgetSummary? {
        if (record.action is WidgetExplorationAction) {
            val candidateLogs = record.actionResult!!.deviceLogs.apiLogsOrEmpty

            // Remove all APIs which are contained in the API list
            val logs = candidateLogs.filter { candidate ->
                sensitiveApiList.any { monitored -> candidate.matches(monitored) }
            }

            if (logs.isNotEmpty()) {
                val text: String
                val widget : Widget

                val explRecord = record.action as WidgetExplorationAction
                // When it's runtime permission, the action action happened before therefore must look for it
                // moreover, if no previous action was found it happened on a reset
                if (explRecord.isEndorseRuntimePermission) {
                    if (previousNonPermissionDialogAction != null) {
                        val tmpWidget = Reporter.getActionWidget(previousNonPermissionDialogAction)
                        text = Reporter.getWidgetText(tmpWidget)
                        widget = tmpWidget ?: explRecord.widget
                    }
                    else {
                        text = "<RESET>"
                        widget = explRecord.widget
                    }
                } else {
                    val tmpWidget = Reporter.getActionWidget(record)
                    text = Reporter.getWidgetText(tmpWidget)
                    widget = explRecord.widget
                }

                val widgetSummary : WidgetSummary
                widgetSummary = if (text == "<RESET>")
                    WidgetSummary(text)
                else
                    WidgetSummary(text, widget)

                logs.forEach { log ->
                    var screenshot = ""
                    if (record.actionResult!!.screenshot != null)
                        screenshot = Paths.get(record.actionResult!!.screenshot).fileName.toString()
                    widgetSummary.addApiData(log as IApi, screenshot)
                }

                return widgetSummary
            }
        }

        return null
    }

    private fun getPreviousNonPermissionDialogAction(memoryRecord: List<IMemoryRecord>, index: Int): IMemoryRecord? {
        if (index <= 0)
            return null

        val previousAction = memoryRecord[index - 1]

        return if (previousAction.action is WidgetExplorationAction) {
            if (previousAction.action.isEndorseRuntimePermission)
                getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
            else
                previousAction
        } else
            getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
    }
}