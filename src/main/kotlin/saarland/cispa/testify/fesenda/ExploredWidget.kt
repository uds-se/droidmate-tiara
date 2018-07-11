/*package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import org.droidmate.exploration.statemodel.Widget
import java.nio.file.Path

class ExploredWidget @JvmOverloads constructor(val widget: Widget = dummyWidget,
                                               val foundApis: MutableList<FoundApi> = mutableListOf()) {

    fun addFoundAPI(api: IApi, screenshot: Path?) {
        // Insert only the first time it was found
        val exists = foundApis.any { it.api == api }

        if (exists)
            return

        foundApis.add(FoundApi(api, screenshot))
    }

    override fun toString(): String {
        return foundApis.joinToString(separator = "") { data ->
            "${widget.uid}\t${data.api.uniqueString}\t${data.screenshot}\t$widget\n"
        }
    }

    override fun hashCode(): Int {
        return this.widget.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if ((other == null) || (other !is ExploredWidget))
            return false

        return this.widget.uid == other.widget.uid
    }

    fun merge(exploredWidget: ExploredWidget) {
        assert(exploredWidget.widget.uid == this.widget.uid)

        val newApiData = exploredWidget.foundApis
                .filterNot { api -> this.foundApis.contains(api) }

        this.foundApis.addAll(newApiData)
    }

    companion object {
        val dummyWidget: Widget = Widget()
    }
}*/