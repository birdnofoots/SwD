package li.gkd.app.settings

import li.gkd.app.model.Option
import li.gkd.app.resources.*
import org.jetbrains.compose.resources.StringResource

/** Explicit values are part of the persisted settings contract. */
enum class UpdateChannel(
    override val value: Int,
    override val labelResource: StringResource,
    val url: String,
) : Option<Int> {
    Stable(0, Res.string.update_channel_stable, "http://47.106.242.125:8790/update/index.json"),
    Beta(1, Res.string.update_channel_beta, "http://47.106.242.125:8790/update/index.json");

    override val options get() = entries
}
