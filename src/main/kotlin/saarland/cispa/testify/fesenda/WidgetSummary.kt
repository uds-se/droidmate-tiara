package saarland.cispa.testify.fesenda

import org.droidmate.device.datatypes.Widget
import org.droidmate.report.uniqueString

class WidgetSummary(val widget: Widget, val widgetText: String,
                    val apiData: MutableList<WidgetApiData> = ArrayList()) {
    fun addApiData(api: String, screenshot: String) {
        // Insert only the first time it was found
        val exists = apiData.any { it.api == api }

        if (exists)
            return

        apiData.add(WidgetApiData(api, screenshot))
    }

    override fun toString(): String {
        return apiData.joinToString(separator = "") { data -> "$widgetText\t${data.api}\t${data.screenShot}\t$widget\n" }
    }

    override fun hashCode(): Int {
        return this.widget.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if ((other == null) || (other !is WidgetSummary))
            return false

        return this.widget.uniqueString == other.widget.uniqueString
    }

    fun merge(widgetSummary: WidgetSummary){
        assert(widgetSummary.widget.uniqueString == this.widget.uniqueString)

        val newApiData = widgetSummary.apiData
                .filterNot { api -> this.apiData.contains(api) }

        this.apiData.addAll(newApiData)
    }
}