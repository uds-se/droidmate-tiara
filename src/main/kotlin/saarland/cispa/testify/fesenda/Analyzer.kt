package saarland.cispa.testify.fesenda

import org.droidmate.android_sdk.AdbWrapper
import org.droidmate.apis.IApi
import org.droidmate.apis.IApiLogcatMessage
import org.droidmate.configuration.Configuration
import org.droidmate.configuration.ConfigurationBuilder
import org.droidmate.device.datatypes.Widget
import org.droidmate.exploration.actions.ResetAppExplorationAction
import org.droidmate.exploration.actions.WidgetExplorationAction
import org.droidmate.misc.SysCmdExecutor
import org.droidmate.report.isEquivalentIgnoreLocation
import org.droidmate.report.uniqueString
import org.slf4j.LoggerFactory
import saarland.cispa.testify.*
import saarland.cispa.testify.strategies.playback.MemoryPlayback
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Paths

object Analyzer{
    private val logger = LoggerFactory.getLogger(Analyzer::class.java)

    private val nrAttemptsConfirm = 1//3
    private val sensitiveApiList = ResourceManager.getResourceAsStringList("sensitiveApiList.txt")

    fun run(args: Array<String>){
        val additionalStrategies = getAdditionalExtraStrategies()
        val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())
        val expCfg = ExperimentConfiguration(cfg, ArrayList())
        val memoryData = start(args, additionalStrategies, true, cfg, expCfg)

        memoryData.forEach { processMemory(it, args, expCfg) }
    }

    private fun start(args: Array<String>, 
                      additionalStrategies: List<ISelectableExplorationStrategy>,
                      moveOutputToAppDir: Boolean,
                      cfg: Configuration = ConfigurationBuilder().build(args, FileSystems.getDefault()),
                      expCfg: ExperimentConfiguration = ExperimentConfiguration(cfg, ArrayList())): List<Memory> {
        val wrapper = DroidmateWrapper(expCfg.workDir)
        val selectedStrategies = ApkExplorer.defaultStrategies
        val apkExploration = ApkExplorer.build(selectedStrategies, expCfg, wrapper, additionalStrategies)

        apkExploration.explore(moveOutputToAppDir)

        // Serialize memory
        MemoryProcessor.serializeResults(apkExploration.memoryData, expCfg)

        additionalStrategies
                .filterIsInstance(MemoryPlayback::class.java)
                .forEach { strategy ->
                    //logger.info("Playback similarity: ${strategy.getExplorationRatio()}")
                    Reporter(expCfg).writePlaybackResults(strategy.traces)
                }

        // Generate results
        MemoryProcessor.processFromMemory(apkExploration.memoryData, expCfg)

        return apkExploration.memoryData
    }

    private fun getAdditionalExtraStrategies(): List<ISelectableExplorationStrategy>{
        //val strategies : MutableList<ISelectableExplorationStrategy> = ArrayList()
        //strategies.add(LoginWithFacebook.build())
        //strategies.add(LoginWithGoogle.build())
        //return strategies
        return ArrayList()
    }

    private fun processMemory(memory: Memory, args: Array<String>, expCfg: ExperimentConfiguration) {
        val apk = memory.getApk()
        val appPkg = apk.packageName

        val widgetsApiList = memory.getApisPerWidget()
        Writer.writeWidgetApisList(expCfg, widgetsApiList, appPkg)

        val traceData = memory.buildPlaybackTraces()
        val candidateTraces = traceData.createCandidateTraces(widgetsApiList)
        candidateTraces.serialize("filtered", appPkg, expCfg)

        confirmCandidateTraces(expCfg, candidateTraces, args, appPkg)
        candidateTraces.serialize("confirmed", appPkg, expCfg)

        evaluateConfirmedTraces(expCfg, candidateTraces, args, appPkg)
        candidateTraces.serialize("evaluated", appPkg, expCfg)

        Writer.writeReports(candidateTraces, traceData)
    }

    private fun List<CandidateTrace>.serialize(suffix: String, appPackageName: String,
                                expCfg: ExperimentConfiguration){
        this.forEachIndexed { index, trace ->
            val outPath = expCfg.dataDir.resolve("${appPackageName}_${suffix}_$index.playbackTrace")
            try {
                logger.info("Serializing playbackTrace to ${outPath.fileName}")
                trace.serialize(outPath)
                logger.info("Trace serialized")
            }
            catch(e: IOException){
                logger.error("Filed to serialize playbackTrace to ${outPath.fileName}: ${e.message}", e)
            }
        }
    }

    private fun getWidgetsSeen(memoryRecords: List<IMemoryRecord>): List<Widget>{
        val seenWidgets : MutableList<Widget> = ArrayList()

        memoryRecords.forEach { record ->
            if (record.type in arrayListOf(ExplorationType.Explore, ExplorationType.Playback)) {

                record.widgetContext.guiState.widgets.forEach { w ->
                    if (!seenWidgets.any { q -> q.uniqueString == w.uniqueString })
                        seenWidgets.add(w)
                }
            }
        }

        return seenWidgets
    }

    /**
     * Run the confirmed traces with blocked API and see how similar they are to the normal
     */
    private fun evaluateConfirmedTraces(expCfg: ExperimentConfiguration, candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces with enforcement")
        candidateTraces
                .filter { it.confirmRatio == 1.0 }
                .forEachIndexed { index, candidate ->
            candidate.playbackTrace.reset()
            val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())

            if (cfg.deviceSerialNumber.isEmpty()) {
                val deviceSN = AdbWrapper(cfg, SysCmdExecutor()).androidDevicesDescriptors[cfg.deviceIndex].deviceSerialNumber
                cfg.deviceSerialNumber = deviceSN
            }
            val playbackStrategy = PlaybackWithEnforcement.build(appPackageName, arrayListOf(candidate.playbackTrace),
                    candidate.widget, candidate.api, cfg)
            val memoryData = start(args.filterNot { it == "-takeScreenshots" }.toTypedArray(),
                    playbackStrategy, false, cfg)

            memoryData.forEach { memory ->
                val apk = memory.getApk()
                val appPkg = apk.packageName
                val baseName = "${appPkg}_trace$index"

                val widgetApiList = memory.getApisPerWidget()
                Writer.writeWidgetApisList(expCfg, widgetApiList, baseName)

                if (widgetApiList.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                    val similarityRatio = candidate.getExploredRatio()

                    candidate.blockedRatio = similarityRatio
                    val seenWidgetsTrace = getWidgetsSeen(memory.getRecords())
                    candidate.seenWidgetsBlock.addAll(seenWidgetsTrace)

                    // Remove all widgets seen
                    // If they have resId or text, remove those which were not found as well
                    val unseenWidgets = candidate.seenWidgets.filterNot { c -> seenWidgetsTrace.any { t -> c.uniqueString == t.uniqueString } }
                            .filterNot { c -> !(c.text.isEmpty() && c.resourceId.isEmpty()) && seenWidgetsTrace.any { t -> t.isEquivalentIgnoreLocation(c) } }
                    val unseenWidgetsRatio = unseenWidgets.size / candidate.seenWidgets.size.toDouble()
                    candidate.unseenRatio = unseenWidgetsRatio
                }
            }
        }
    }

    /**
     * Run each playbackTrace multiple times to see if they can be constantly reproduced
     */
    private fun confirmCandidateTraces(expCfg: ExperimentConfiguration, candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces")
        candidateTraces.forEach { candidate ->
            // Repeat 3x to remove "flaky traces"
            var similarityRatio = 0.0
            var successCount = 0
            (0 until nrAttemptsConfirm).forEach { p ->
                logger.info("Exploring playbackTrace $p for (Api=${candidate.api.uniqueString} Widget=${candidate.widget.uniqueString})")
                candidate.playbackTrace.reset()
                val playbackStrategy = MemoryPlayback.build(appPackageName, arrayListOf(candidate.playbackTrace))
                val memoryData = start(args.filterNot { it == "-takeScreenshots" }.toTypedArray(),
                        playbackStrategy, false)

                memoryData.forEachIndexed { index, memory ->
                    val apk = memory.getApk()
                    val appPkg = apk.packageName
                    val baseName = "${appPkg}_trace$index"

                    val apiWidgetSummary = memory.getApisPerWidget()
                    Writer.writeWidgetApisList(expCfg, apiWidgetSummary, baseName)

                    if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                        successCount++

                        //similarityRatio += (playbackStrategy.first() as MemoryPlayback).getExplorationRatio(candidate.widget)
                        similarityRatio += candidate.getExploredRatio()

                        val seenWidgetsTrace = getWidgetsSeen(memory.getRecords())
                        val unseenWidgets = seenWidgetsTrace.filterNot { c -> candidate.seenWidgets.any { t -> c.uniqueString == t.uniqueString } }
                        candidate.seenWidgets.addAll(unseenWidgets)
                    }
                }
            }

            candidate.playbackTrace.reset()
            if (successCount == nrAttemptsConfirm) {
                candidate.confirmRatio = similarityRatio / nrAttemptsConfirm

            }
        }
    }

    /**
     * Filter all traces which contain a relevant API. Creates a playbackTrace for each API and widget to
     * allow to be independently evaluated
      */
    private fun MutableList<PlaybackTrace>.createCandidateTraces(exploredWidgetList: List<ExploredWidget>): List<CandidateTrace>{
        val candidateTraces : MutableList<CandidateTrace> = ArrayList()

        exploredWidgetList.forEach { exploredWidget ->
            // All traces have reset, so use the first time it started the app
            val tracesWithWidget = if (exploredWidget.widget == ExploredWidget.dummyWidget)
                arrayListOf(this.first())
            else
                this.filter { trace -> trace.contains(exploredWidget.widget) }

            exploredWidget.foundApis.forEach { data ->
                tracesWithWidget.forEach { playbackTrace ->
                    val newTrace = CandidateTrace(exploredWidget.widget, playbackTrace, data.api, data.screenshot?.toUri())
                    candidateTraces.add(newTrace)
                }
            }
        }

        return candidateTraces
    }

    private fun Memory.buildPlaybackTraces(): MutableList<PlaybackTrace>{
        val memoryRecords = this.getRecords()
        val traces : MutableList<PlaybackTrace> = ArrayList()
        val packageName = this.getApk().packageName

        // Create traces from memory records
        // Each playbackTrace starts with a reset
        // Last playbackTrace ends with terminate exploration
        for (i in 0 until memoryRecords.size) {
            val memoryRecord = memoryRecords[i]

            if (memoryRecord.type == ExplorationType.Reset)
                traces.add(PlaybackTrace())

            val widgetContext = this.getWidgetContext(memoryRecord.widgetContext.guiState, packageName)
            traces.last().add(memoryRecord.action, widgetContext)
        }

        return traces
    }

    private fun Memory.getApisPerWidget(): List<ExploredWidget>{
        val exploredWidgetsList: MutableList<ExploredWidget> = ArrayList()
        val history = this.getRecords()
        history.indices.forEach { index ->
            val newWidgetSummary = getExploredWidget(history, index)

            if (newWidgetSummary != null){
                val currWidgetSummary = exploredWidgetsList.firstOrNull { it == newWidgetSummary }

                // New widget, insert
                if (currWidgetSummary == null)
                    exploredWidgetsList.add(newWidgetSummary)
                // Widget already exists, merge
                else
                    currWidgetSummary.merge(newWidgetSummary)
            }
        }

        return exploredWidgetsList
    }

    private fun IApiLogcatMessage.matches(monitored: String): Boolean{
        var uri = ""
        if (this.objectClass.contains("ContentResolver") && this.paramTypes.indexOf("android.net.Uri") != -1)
            uri = "\t${this.parseUri()}"

        val params = this.paramTypes.joinToString(separator = ",")

        val thisValue = "${this.objectClass}->${this.methodName}($params)$uri"

        return thisValue.contains(monitored)
    }

    private fun getExploredWidget(history: List<IMemoryRecord>, index: Int): ExploredWidget? {
        val record = history[index]

        val candidateLogs = record.actionResult!!.deviceLogs.apiLogsOrEmpty
        // Remove all non monitored APIs
        val sensitiveLogs = candidateLogs.filter { candidate -> sensitiveApiList.any { monitored -> candidate.matches(monitored) } }

        // Nothing to monitor
        if (sensitiveLogs.isEmpty())
            return null

        if (record.action is WidgetExplorationAction){

            val explRecord = record.action as WidgetExplorationAction
            val lastAppWidget = if (explRecord.isEndorseRuntimePermission) {
                val previousNonPermissionDialogAction = getPreviousNonPermissionDialogAction(history, index)

                if (previousNonPermissionDialogAction.action is ResetAppExplorationAction)
                    ExploredWidget.dummyWidget
                else
                    (previousNonPermissionDialogAction.action as WidgetExplorationAction).widget
            }
            else
                explRecord.widget

            // Was an exploration action, record.type guarantees it
            val exploredWidget = ExploredWidget(lastAppWidget!!)

            val screenshot = record.actionResult?.screenshot
            val screenshotPath = if (screenshot != null)
                Paths.get(screenshot).fileName
            else
                Paths.get(".").fileName

            sensitiveLogs.forEach { log ->
                if (!exploredWidget.foundApis.any { it.api.uniqueString == (log as IApi).uniqueString })
                    exploredWidget.addFoundAPI(log as IApi, screenshotPath)
            }

            return exploredWidget
        }

        // Not an exploration action
        return null
    }

    private fun getPreviousNonPermissionDialogAction(memoryRecord: List<IMemoryRecord>, index: Int): IMemoryRecord {
        // First reset
        if (index == 0)
            return memoryRecord[index]

        val previousAction = memoryRecord[index - 1]

        return if (previousAction.action is WidgetExplorationAction) {
            if (previousAction.action.isEndorseRuntimePermission)
                getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
            else
                previousAction
        } else if (previousAction.type == ExplorationType.Reset)
            return previousAction
        else
            getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
    }
}