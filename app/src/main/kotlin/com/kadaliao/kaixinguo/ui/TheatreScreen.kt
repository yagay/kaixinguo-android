package com.kadaliao.kaixinguo.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Preserve all of the original browse/search/chart screens under the theatre tab. */
@Composable
fun TheatreScreen() {
    var section by rememberSaveable { mutableStateOf("推荐") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (tab in listOf("推荐", "排行榜", "新剧", "搜索")) {
                TextButton(onClick = { section = tab }) {
                    Text(if (section == tab) "● $tab" else tab)
                }
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            when (section) {
                "推荐" -> DiscoverScreen()
                "排行榜" -> RankScreen()
                "新剧" -> NewReleasesScreen()
                else -> SearchScreen()
            }
        }
    }
}
