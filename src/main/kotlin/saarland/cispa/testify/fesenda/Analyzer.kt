package saarland.cispa.testify.fesenda

import org.droidmate.exploration.actions.WidgetExplorationAction
import saarland.cispa.testify.*
import java.nio.file.Paths

object Analyzer{
    private val sensitiveApiList = ResourceManager.getResourceAsStringList("sensitiveApiList.txt")

    fun run(args: Array<String>){
        val memoryData = saarland.cispa.testify.Main.start(args, ArrayList())

        memoryData.forEach { memory ->
            val apk = memory.getApk()
            val appPkg = apk.packageName
            val baseName = "${appPkg}_${System.currentTimeMillis()}"

            val apiWidgetSummary = getAPIWidgetSummary(memory)
            Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)
        }
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

    private fun getWidgetSummary(record: IMemoryRecord, previousNonPermissionDialogAction: IMemoryRecord?): WidgetSummary? {
        if (record.type == ExplorationType.Explore) {
            val droidmateLogs = record.actionResult!!.deviceLogs.apiLogsOrEmpty

            val candidateLogs = droidmateLogs.map { p ->
                var uri = ""
                if (p.objectClass.contains("ContentResolver") && p.paramTypes.indexOf("android.net.Uri") != -1)
                    uri = "\t${p.parseUri()}"

                "${p.objectClass}->${p.methodName}$uri"
            }

            // Remove all APIs which are contained in the API list
            val logs = candidateLogs.filter { candidate -> sensitiveApiList.any { monitored -> candidate.contains(monitored) } }

            if (logs.isNotEmpty()) {
                val text: String

                val explRecord = record.action as WidgetExplorationAction
                // When it's runtime permission, the action action happened before therefore must look for it
                // moreover, if no previous action was found it happened on a reset
                text = if (explRecord.isEndorseRuntimePermission) {
                    if (previousNonPermissionDialogAction != null)
                        Reporter.getActionText(previousNonPermissionDialogAction)
                    else
                        "<RESET>"
                } else
                    Reporter.getActionText(record)
                val widget = explRecord.widget
                val widgetSummary = WidgetSummary(widget, text, ArrayList())

                logs.forEach { log ->
                    var screenshot = ""
                    if (record.actionResult!!.screenshot != null)
                        screenshot = Paths.get(record.actionResult!!.screenshot).fileName.toString()
                    widgetSummary.addApiData(log, screenshot)
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