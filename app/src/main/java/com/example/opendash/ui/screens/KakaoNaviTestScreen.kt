package com.example.opendash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kakaomobility.knsdk.ui.view.KNNaviView

@Composable
fun KakaoNaviTestScreen(
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                KNNaviView(context)
            },
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = "KakaoNavi UI Test",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        )
    }
}