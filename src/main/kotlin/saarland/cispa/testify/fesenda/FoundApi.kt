package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import java.nio.file.Path

data class FoundApi(val api: IApi, val screenshot: Path?){
    override fun equals(other: Any?): Boolean {
        if ((other == null) || (other !is FoundApi))
            return false

        return this.api.uniqueString == other.api.uniqueString
    }

    override fun hashCode(): Int {
        var result = api.hashCode()
        result = 31 * result + (screenshot?.hashCode() ?: 0)
        return result
    }
}