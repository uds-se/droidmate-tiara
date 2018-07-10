/*package saarland.cispa.testify.fesenda.reports

import org.droidmate.exploration.data_aggregators.IExplorationLog
import org.droidmate.report.apk.ApkReport
import java.nio.file.Path

class WidgetApiList: ApkReport(){
    override fun safeWriteApkReport(data: IExplorationLog, apkReportDir: Path) {

    }

    /*

        @JvmStatic
    fun writeWidgetApisList(expCfg: ExperimentConfiguration, widgetSummaryData: List<ExploredWidget>, reportName: String) {
        logger.info("Writing API widget summary .........")
        val summaryDir = Reporter(expCfg).initialize()

        // Widget - API Summary
        val widgetAPISummaryFile = summaryDir.resolve("$reportName-widget-api-summary.txt")
        Files.write(widgetAPISummaryFile, widgetSummaryData.joinToString(separator = "") { "$it\n" }.toByteArray())
    }

     */
}*/