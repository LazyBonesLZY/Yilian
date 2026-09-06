package com.esurfing.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 页面统一的水平内缩。 */
val ScreenPadding = 12.dp

/** 卡片内部统一留白。 */
val CardPadding = 16.dp

/** 与 Miuix CardDefaults.CornerRadius 保持一致，供手工拼卡片的地方复用。 */
val CardRadius = 16.dp

/**
 * 首页顶部的状态主卡：圆形图标 + 大标题 + 副标题，底色随状态平滑过渡。
 * 状态是这个 App 最重要的信息，值得占据一整块视觉重心。
 */
@Composable
fun HeroCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    val animatedAccent by animateColorAsState(accent, label = "heroAccent")
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = animatedAccent.copy(alpha = 0.12f)),
        insideMargin = PaddingValues(CardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(animatedAccent.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = animatedAccent,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(CardPadding))
            footer()
        }
    }
}

/**
 * 标签 → 值的一行。值偏右，超长内容（Ticket、Algo-ID）用等宽字体并允许换行，
 * 避免像之前那样被挤成一坨。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            // 用 weight 而不是固定比例：Ticket / Algo-ID 这种长值才能拿到剩余宽度换行
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 可点击的链接行：标题在上，地址在下。
 * 仓库地址比 [InfoRow] 里的值长得多，塞进同一行会被折成好几段，所以单独占一行。
 */
@Composable
fun LinkRow(title: String, url: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // clickable 放在 padding 之前，整行（含留白）都能点
            .clickable(onClick = onClick)
            .padding(horizontal = CardPadding, vertical = 11.dp),
    ) {
        Text(text = title, fontSize = 14.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            text = url,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/**
 * 标题 + 说明的可点击一行，样子对齐 Miuix 的各种 Preference。
 * 用在"点了会跳去系统设置"这类没有开关也没有下拉的项上。
 *
 * [highlight] 为 true 时说明文字用强调色——这一行存在的意义就是提醒用户去处理，
 * 处理完了就该退回普通样式，不该一直红着。
 */
@Composable
fun BasicPreferenceRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = CardPadding, vertical = 14.dp),
    ) {
        Text(text = title, fontSize = 15.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = summary,
            fontSize = 13.sp,
            color = if (highlight) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}

/** 卡片内分隔线，左右留出与内容一致的边距。 */
@Composable
fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = CardPadding))
}

/** 一组 InfoRow，自动插入分隔线。 */
@Composable
fun InfoCard(rows: List<Triple<String, String, Boolean>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, (label, value, mono) ->
            if (index > 0) RowDivider()
            InfoRow(label, value, mono)
        }
    }
}

/** 空状态占位，比一张写着"暂无"的卡片体面一些。 */
@Composable
fun EmptyHint(text: String) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardPadding)) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = text,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}
