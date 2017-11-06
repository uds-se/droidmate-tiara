package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import org.droidmate.device.datatypes.Widget
import org.droidmate.report.uniqueString
import java.awt.Rectangle
import java.nio.file.Path

class ExploredWidget(val widget: Widget = dummyWidget,
                     val foundApis: MutableList<FoundApi> = ArrayList()) {

    fun addFoundAPI(api: IApi, screenshot: Path?) {
        // Insert only the first time it was found
        val exists = foundApis.any { it.api == api }

        if (exists)
            return

        foundApis.add(FoundApi(api, screenshot))
    }

    override fun toString(): String {
        return foundApis.joinToString(separator = "") { data ->
            "${widget.uniqueString}\t${data.api.uniqueString}\t${data.screenshot}\t$widget\n"
        }
    }

    override fun hashCode(): Int {
        return this.widget.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if ((other == null) || (other !is ExploredWidget))
            return false

        return this.widget.uniqueString == other.widget.uniqueString
    }

    fun merge(exploredWidget: ExploredWidget){
        assert(exploredWidget.widget.uniqueString == this.widget.uniqueString)

        val newApiData = exploredWidget.foundApis
                .filterNot { api -> this.foundApis.contains(api) }

        this.foundApis.addAll(newApiData)
    }

    companion object {
        val dummyWidget = Widget("<RESET>").apply {
            packageName = "STUB!"
            bounds = Rectangle(1, 1, 5, 5)
            deviceDisplayBounds = Rectangle(100, 100)
            enabled = true
            clickable = true
        }

    }
}