package com.echonote.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonote.app.di.AppContainer
import com.echonote.app.domain.SearchHit
import com.echonote.app.ui.common.EmptyState
import com.echonote.app.ui.common.formatDuration
import com.echonote.app.ui.common.formatRelativeDay
import java.util.Calendar

/**
 * 全库搜索页：按逐字稿全文 / 录音标题 / 说话人名搜索，可按时间范围过滤。
 * 点击任一命中跳转详情页（导航层目前忽略 startMs，仅定位到录音）。
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    onOpenDetail: (Long, Long) -> Unit,
) {
    var textQuery by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(Scope.ALL_TEXT) }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }

    val trimmed = textQuery.trim()
    val (textQ, titleQ, speakerQ) = when (scope) {
        Scope.ALL_TEXT -> Triple(trimmed, "", "")
        Scope.TITLE -> Triple("", trimmed, "")
        Scope.SPEAKER -> Triple("", "", trimmed)
    }

    val results by remember(textQ, titleQ, speakerQ, range) {
        container.repository.search(
            textQuery = textQ,
            titleQuery = titleQ,
            speakerQuery = speakerQ,
            fromMs = range.fromMs(),
        )
    }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = textQuery,
            onValueChange = { textQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            placeholder = { Text("搜索逐字稿内容") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (textQuery.isNotEmpty()) {
                    IconButton(onClick = { textQuery = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Scope.entries.forEach { option ->
                FilterChip(
                    selected = scope == option,
                    onClick = { scope = option },
                    label = { Text(option.label) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Range.entries.forEach { option ->
                FilterChip(
                    selected = range == option,
                    onClick = { range = option },
                    label = { Text(option.label) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        if (trimmed.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = "搜索全部逐字稿",
                subtitle = "输入关键词，可按内容、标题或说话人查找",
            )
        } else if (results.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = "没有匹配结果",
                subtitle = "试试更短的关键词，或切换到其他搜索范围",
            )
        } else {
            Text(
                "${results.size} 条结果",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.segmentId }) { hit ->
                    SearchHitCard(hit = hit, query = trimmed, onClick = {
                        onOpenDetail(hit.recordingId, hit.startMs)
                    })
                }
            }
        }
    }
}

private enum class Scope(val label: String) {
    ALL_TEXT("全文"),
    TITLE("标题"),
    SPEAKER("说话人"),
}

private enum class Range(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    WEEK("7 天"),
    MONTH("30 天"),
    ;

    fun fromMs(): Long = when (this) {
        ALL -> -1L
        TODAY -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        WEEK -> System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        MONTH -> System.currentTimeMillis() - 30L * 24 * 3600 * 1000
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit, query: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.recordingTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatRelativeDay(hit.recordingCreatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    hit.speakerName ?: "未知",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${formatDuration(hit.startMs)} / ${formatDuration(hit.recordingDurationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(6.dp))
            HighlightedText(text = hit.text, query = query)
        }
    }
}

/** 命中关键词用主题色背景高亮；查询为空时原样返回。 */
@Composable
private fun HighlightedText(text: String, query: String) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val annotated = remember(text, query, highlight) {
        buildAnnotatedString {
            append(text)
            if (query.isNotEmpty()) {
                var index = text.indexOf(query, ignoreCase = true)
                while (index >= 0) {
                    addStyle(
                        SpanStyle(background = highlight, fontWeight = FontWeight.Bold),
                        index,
                        index + query.length,
                    )
                    index = text.indexOf(query, index + query.length, ignoreCase = true)
                }
            }
        }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
    )
}
