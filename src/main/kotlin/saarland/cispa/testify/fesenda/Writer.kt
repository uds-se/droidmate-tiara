package saarland.cispa.testify.fesenda

import org.slf4j.LoggerFactory
import saarland.cispa.testify.Reporter
import java.nio.file.Files

object Writer{
    private val logger = LoggerFactory.getLogger(Writer::class.java)

    fun writeAPIWidgetSummary(widgetSummaryData: List<WidgetSummary>, reportName: String) {
        logger.info("Writing API widget summary .........")
        val summaryDir = Reporter.initialize()

        // Widget - API Summary
        val widgetAPISummaryFile = summaryDir.resolve("$reportName-widget-api-summary.txt")
        Files.write(widgetAPISummaryFile, widgetSummaryData.joinToString(separator = "") { "$it\n" }.toByteArray())
    }

}