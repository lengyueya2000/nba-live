package com.nbalive.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbalive.app.data.model.Match
import com.nbalive.app.ui.screens.SearchScreen

/**
 * 搜索页覆盖层（review/03-ui.md P2-18）。
 *
 * 原来这段 `if (searchOpen) { Surface { SearchScreen(...) } }` 直接长在根组合项里，
 * 根节点因此还要读一遍 `listState.matches`。抽出来之后：
 *  - 列表状态的收集下沉到这里（只有覆盖层可见时才需要），根节点不再因为
 *    "刷新列表"而重组（review/03-ui.md P2-15）；
 *  - 根节点只剩一行 `if (searchOpen) SearchOverlay(...)`。
 *
 * ⚠️ 它是 Scaffold 的**兄弟节点**（全屏覆盖层，不占路由、不参与导航一致性判断），
 * 所以调用点仍在根节点，且 `SearchScreen` 自己补 inset —— 这与原来一致。
 */
@Composable
internal fun SearchOverlay(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onMatchClick: (Match) -> Unit,
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        SearchScreen(
            matches = listState.matches,
            onClose = onClose,
            onMatchClick = onMatchClick,
        )
    }
}