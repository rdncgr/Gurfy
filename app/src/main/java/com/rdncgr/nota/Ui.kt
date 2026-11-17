package com.rdncgr.nota

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Sekmeli ekranlarda içerik parmakla sağa / sola kaydırılınca sekme değişir.
 * [selected] dışarıdaki sekme durumudur; sekmeye dokununca sayfa da kayar.
 */
@Composable
fun SwipePages(
    selected: Int,
    count: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable (Int) -> Unit,
) {
    val state = rememberPagerState(initialPage = selected.coerceIn(0, count - 1)) { count }
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(selected) {
        if (state.currentPage != selected && selected in 0 until count) state.animateScrollToPage(selected)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }.collect { if (it != current) select(it) }
    }
    HorizontalPager(state = state, modifier = modifier, verticalAlignment = Alignment.Top) { page -> content(page) }
}

/** Bölüm başlığı ve yanında ⓘ: açıklama yalnızca dokununca açılır. */
@Composable
fun InfoTitle(title: String, info: String) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = { open = !open }) {
            Icon(
                Icons.Outlined.Info, contentDescription = if (open) "Açıklamayı gizle" else "Açıklama",
                modifier = Modifier.size(20.dp),
                tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AnimatedVisibility(open) {
        Text(
            info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}
