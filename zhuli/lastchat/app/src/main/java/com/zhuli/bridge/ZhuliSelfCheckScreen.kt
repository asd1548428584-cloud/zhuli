package com.zhuli.bridge

import androidx.compose.runtime.Composable

/**
 * 自检面板入口。
 *
 * 页面自己带 Scaffold / 顶栏（[ZhuliScaffold]），这里不再套一层，
 * 否则会出现双层 TopAppBar 和两层内边距。
 */
@Composable
fun ZhuliSelfCheckScreen() {
    ZhuliSelfCheckPage()
}
