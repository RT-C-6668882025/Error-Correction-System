package com.ecs.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ecs.core.agg.Aggregator
import com.ecs.core.direction.Direction
import com.ecs.core.tree.TopLevel
import com.ecs.ui.AppViewModel
import com.ecs.ui.component.Badge
import com.ecs.ui.component.BusyBar
import com.ecs.ui.component.MessageBar
import com.ecs.ui.component.SectionCard

/**
 * 递归式复习。两级，每一级读的都是上一级的输出：
 *
 *   一道题的分析 → 小方向（一个板块一棵树）→ 大方向
 *
 * 页面上只有形态与依据，没有错误率、次数、难度分布——那些答的是「错得怎么样」，
 * 不是「该填成什么形态」。
 */
@Composable
fun ReviewScreen(vm: AppViewModel, onEditPrompt: () -> Unit = {}) {
    val records by vm.records.collectAsState()
    val directions by vm.directions.collectAsState()
    val busy by vm.busy.collectAsState()
    var rootFilter by remember { mutableStateOf<String?>(null) }
    var masked by remember { mutableStateOf(false) }
    var showEmpty by remember { mutableStateOf(false) }

    val blocks = remember(records) { Aggregator.blocks(records, includeEmpty = true) }
    val analyzed = remember(records) { Aggregator.analyzed(records).size }
    val unclassified = remember(records) { Aggregator.unclassified(records).size }
    val notAnalyzed = remember(records) { Aggregator.notAnalyzed(records).size }
    val shown = blocks
        .filter { rootFilter == null || it.branch.root == rootFilter }
        .filter { showEmpty || it.size > 0 }
    val withData = blocks.count { it.size > 0 }
    val hidden = blocks.count { rootFilter == null || it.branch.root == rootFilter } - shown.size
    val major = directions.firstOrNull { it.scope == Direction.ALL }

    Column(Modifier.fillMaxSize()) {
        BusyBar(busy)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = rootFilter == null,
                onClick = { rootFilter = null },
                label = { Text("全部") },
            )
            TopLevel.all.forEach { root ->
                FilterChip(
                    selected = rootFilter == root,
                    onClick = { rootFilter = root },
                    label = { Text(root) },
                )
            }
            FilterChip(
                selected = masked,
                onClick = { masked = !masked },
                label = { Text("遮住形态") },
            )
        }

        // 复习页空着的时候，人第一个要知道的是「题去哪了」
        Text(
            "已分析 $analyzed 条进了板块" +
                (if (unclassified > 0) "　未归类 $unclassified 条" else "") +
                (if (notAnalyzed > 0) "　待分析 $notAnalyzed 条" else ""),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (analyzed == 0) {
            Text(
                if (unclassified > 0) {
                    "这 $unclassified 条跑过分析、也有答案形式，但模型给的板块不在十九支里，" +
                        "所以一支都没进。去原题页按「未归类」筛出来重跑一次。"
                } else {
                    "还没有分析好的题。先到原题页点「分析」。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            item {
                MajorCard(
                    major = major,
                    minorCount = directions.count { it.scope != Direction.ALL },
                    blocksWithData = withData,
                    masked = masked,
                    onBuild = { vm.buildMajor() },
                    onBuildAll = { vm.buildAllMinors() },
                    onEditPrompt = onEditPrompt,
                )
            }
            items(shown, key = { it.path }) { block ->
                BlockCard(
                    block = block,
                    direction = directions.firstOrNull { it.scope == block.path },
                    masked = masked,
                    onBuild = { vm.buildMinor(block.branch) },
                )
            }
            // 十九张「0 道」的卡片摊开来，本身就像坏了
            if (hidden > 0) {
                item {
                    TextButton(
                        onClick = { showEmpty = true },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text("另有 $hidden 支还没有题，展开") }
                }
            }
        }
        MessageBar(vm)
    }
}

@Composable
private fun MajorCard(
    major: Direction?,
    minorCount: Int,
    blocksWithData: Int,
    masked: Boolean,
    onBuild: () -> Unit,
    onBuildAll: () -> Unit,
    onEditPrompt: () -> Unit,
) {
    SectionCard("大方向") {
        Text(
            major?.let { "${it.size()} 个节点，读的是 ${it.fromCount} 个板块的输出" }
                ?: "还没生成。它的输入只有各板块的小方向，不回头读原题。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "$blocksWithData 个板块有题　$minorCount 个已汇总",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (major != null && !masked) {
            TreeText(major)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBuildAll, enabled = blocksWithData > 0) {
                Text("汇总全部板块")
            }
            OutlinedButton(onClick = onBuild, enabled = minorCount > 0) {
                Text(if (major == null) "生成大方向" else "重新生成")
            }
        }
        TextButton(onClick = onEditPrompt, modifier = Modifier.padding(top = 4.dp)) {
            Text("改汇总提示词")
        }
    }
}

@Composable
private fun BlockCard(
    block: Aggregator.Block,
    direction: Direction?,
    masked: Boolean,
    onBuild: () -> Unit,
) {
    var expanded by remember(block.path) { mutableStateOf(false) }
    // 汇总之后又分析了新题：这棵树该重跑了
    val stale = direction != null && direction.fromCount != block.size

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(block.branch.mid, style = MaterialTheme.typography.titleSmall)
                Badge(block.branch.root, MaterialTheme.colorScheme.secondary)
                Text("${block.size} 道", style = MaterialTheme.typography.labelSmall)
                if (stale) Badge("有新题", MaterialTheme.colorScheme.error)
            }
            Text(
                block.branch.scope,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            if (direction != null && !direction.empty) {
                if (masked) {
                    Text(
                        "${direction.size()} 个节点（已遮住）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    TreeText(direction)
                }
            } else if (block.size > 0) {
                Text(
                    "还没汇总。这一支下 ${block.size} 道题的分析就是它的输入。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBuild, enabled = block.size > 0) {
                    Text(if (direction == null) "汇总小方向" else "重新汇总")
                }
                if (block.size > 0) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起原始分析" else "看这 ${block.size} 条分析")
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                block.analyses.forEach { a ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            if (masked) "＿＿" else a.formShape,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "依据　${a.basis ?: "—"}" + (a.formContext?.let { "　语境　$it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        a.answer?.let {
                            Text(
                                if (masked) "答案　＿＿" else "答案　$it",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 树枝是用空格对齐的，必须等宽字体，否则层级会歪。 */
@Composable
private fun TreeText(direction: Direction) {
    Text(
        direction.render(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
    )
}
