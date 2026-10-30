package li.gkd.app.ui.didi
import li.gkd.app.storage.appStorage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.image.appImageLoader
import java.io.File

/** 一条决策记录 */
data class DidiRecord(
    val ts: String, val price: String, val kind: String,
    val verdict: String, val shot: String,
    val addrFrom: String, val addrTo: String,
    val distMe: String, val distAll: String, val timeText: String,
)

object DidiReportData {
    val DIR: File get() = appStorage().didi
    val recordsFile get() = File(DIR, "records.jsonl")
    val capturesDir get() = File(DIR, "captures")
    val flowJsFile get() = File(DIR, "flow.js")

    /** 正则取字段(commonMain 不能用 org.json) */
    private fun field(line: String, key: String): String {
        val m = Regex("\"" + key + "\"\\s*:\\s*\"?([^\",}]*)\"?").find(line)
        return m?.groupValues?.get(1) ?: ""
    }

    /** 读全部记录, 新的在前 */
    fun load(): List<DidiRecord> {
        val f = recordsFile
        if (!f.exists()) return emptyList()
        return try {
            f.readLines()
                .filter { it.trim().startsWith("{") }
                .mapNotNull { l ->
                    val ts = field(l, "ts"); if (ts.isEmpty()) return@mapNotNull null
                    DidiRecord(
                        ts = ts, price = field(l, "price"), kind = field(l, "kind"),
                        verdict = field(l, "verdict"), shot = field(l, "shot"),
                        addrFrom = field(l, "addr_from"), addrTo = field(l, "addr_to"),
                        distMe = field(l, "dist_me"), distAll = field(l, "dist_all"),
                        timeText = field(l, "time_text"),
                    )
                }.asReversed()
        } catch (e: Exception) { emptyList() }
    }
}

/** 报告页: App 内查看 records.jsonl + 截图(无需 Termux/网页) */
@Composable
fun DidiReportPage() {
    val mainVm = MainViewModel.requireCurrent()
    val imageLoader = appImageLoader()
    var records by remember { mutableStateOf(DidiReportData.load()) }

    GkScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GkTopAppBar(
                title = { Text("SWD Report") },
                navigationIcon = { GkIconButton(imageVector = GkIcons.ArrowBack, onClick = mainVm.navigator::pop) },
                actions = { GkIconButton(imageVector = GkIcons.Autorenew, onClick = { records = DidiReportData.load() }) },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No records yet\n(awaiting first card)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records) { r -> DidiRecordItem(r, imageLoader) }
                item { GkPageBottomSpace() }
            }
        }
    }
}

@Composable
private fun DidiRecordItem(r: DidiRecord, imageLoader: coil3.ImageLoader) {
    val shotFile = remember(r.shot) { if (r.shot.isEmpty()) null else File(DidiReportData.capturesDir, r.shot) }
    val context = coil3.compose.LocalPlatformContext.current
    val request = remember(shotFile) {
        shotFile?.let { ImageRequest.Builder(context).data(it.path).diskCachePolicy(CachePolicy.DISABLED).build() }
    }
    val verdictColor = when (r.verdict) {
        "grab" -> MaterialTheme.colorScheme.primary
        "no_grab" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (request != null) {
            AsyncImage(
                imageLoader = imageLoader, model = request, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.ts, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(8.dp))
                Text(r.kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("¥${r.price}  ${r.verdict}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = verdictColor)
            if (r.addrTo.isNotEmpty()) {
                Text(r.addrTo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
